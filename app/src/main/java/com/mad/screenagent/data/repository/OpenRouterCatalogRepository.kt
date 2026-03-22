package com.mad.screenagent.data.repository

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.OpenRouterCatalog
import com.mad.screenagent.data.model.OpenRouterCatalogEntry
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request

class OpenRouterCatalogRepository(
    private val prefs: com.mad.screenagent.data.prefs.AppPreferences,
    private val httpClient: OkHttpClient,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    val catalogFlow: Flow<OpenRouterCatalog> = prefs.openRouterCatalogFlow

    suspend fun getCatalog(): OpenRouterCatalog = catalogFlow.first()

    suspend fun refreshCatalogIfStale(
        maxAgeMs: Long = DEFAULT_CATALOG_MAX_AGE_MS,
        force: Boolean = false
    ): OpenRouterCatalog {
        val cached = getCatalog()
        val isFresh = cached.models.isNotEmpty() && (nowMillis() - cached.fetchedAt) < maxAgeMs
        if (!force && isFresh) return cached

        val refreshed = runCatching { fetchAndStoreCatalog() }
            .getOrElse { cached }
        return if (refreshed.models.isNotEmpty()) refreshed else cached
    }

    private suspend fun fetchAndStoreCatalog(): OpenRouterCatalog {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .build()

        val gson = Gson()
        val catalog = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use OpenRouterCatalog()
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return@use OpenRouterCatalog()
            }

            val root = gson.fromJson(body, JsonObject::class.java)
            val data = root.getAsJsonArray("data") ?: return@use OpenRouterCatalog()
            val models = buildList {
                data.forEach { element ->
                    val obj = element.asJsonObject
                    val id = obj.get("id")?.asString ?: return@forEach
                    val name = obj.get("name")?.asString ?: id
                    val description = obj.get("description")?.asString.orEmpty()
                    val contextLength = obj.get("context_length")?.asLong ?: 0L
                    val pricing = obj.getAsJsonObject("pricing")
                    val promptPrice = pricing?.get("prompt")?.asString?.toDoubleOrNull()
                    val completionPrice = pricing?.get("completion")?.asString?.toDoubleOrNull()
                    val architecture = obj.getAsJsonObject("architecture")
                    val supportsVision = architecture
                        ?.getAsJsonArray("input_modalities")
                        ?.any { modality ->
                            modality?.asString?.contains("image", ignoreCase = true) == true
                        }
                        ?: architecture?.get("modality")?.asString?.contains("image", ignoreCase = true)
                        ?: AgentConfig(
                            provider = AiProviderType.OPENROUTER,
                            model = id
                        ).supportsVision

                    add(
                        OpenRouterCatalogEntry(
                            id = id,
                            name = name,
                            description = description,
                            contextLength = contextLength,
                            promptPrice = promptPrice,
                            completionPrice = completionPrice,
                            supportsVision = supportsVision
                        )
                    )
                }
            }
                .sortedWith(
                    compareBy<OpenRouterCatalogEntry> { !it.isFree }
                        .thenBy { it.id }
                )

            OpenRouterCatalog(
                fetchedAt = nowMillis(),
                models = models
            )
        }

        if (catalog.models.isNotEmpty()) {
            prefs.saveOpenRouterCatalog(catalog)
        }
        return catalog
    }

    companion object {
        const val DEFAULT_CATALOG_MAX_AGE_MS = 6L * 60L * 60L * 1_000L
    }
}
