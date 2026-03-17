package com.example.uai.data.repository

import com.example.uai.data.model.AiProviderType
import com.example.uai.data.model.ProviderModelCatalog
import com.example.uai.data.model.ProviderModelCatalogEntry
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ProviderModelCatalogRepository(
    private val prefs: com.example.uai.data.prefs.AppPreferences,
    private val httpClient: OkHttpClient,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    fun catalogFlow(provider: AiProviderType): Flow<ProviderModelCatalog> = when (provider) {
        AiProviderType.OPENAI -> prefs.openAiModelCatalogFlow
        AiProviderType.ANTHROPIC -> prefs.anthropicModelCatalogFlow
        AiProviderType.OPENROUTER -> error("OpenRouter uses OpenRouterCatalogRepository")
        AiProviderType.CUSTOM -> error("Custom provider catalogs are fetched per assistant")
    }

    suspend fun getCatalog(provider: AiProviderType): ProviderModelCatalog =
        catalogFlow(provider).first()

    suspend fun refreshCatalogIfStale(
        provider: AiProviderType,
        apiKey: String,
        maxAgeMs: Long = DEFAULT_CATALOG_MAX_AGE_MS,
        force: Boolean = false
    ): ProviderModelCatalog {
        require(provider != AiProviderType.OPENROUTER) { "Use OpenRouterCatalogRepository for OpenRouter" }
        require(provider != AiProviderType.CUSTOM) { "Custom provider catalogs are fetched per assistant" }

        val cached = getCatalog(provider)
        val isFresh = cached.models.isNotEmpty() && (nowMillis() - cached.fetchedAt) < maxAgeMs
        if (!force && isFresh) return cached
        if (apiKey.isBlank()) return cached

        val refreshed = runCatching {
            when (provider) {
                AiProviderType.OPENAI -> fetchOpenAiCatalog(apiKey)
                AiProviderType.ANTHROPIC -> fetchAnthropicCatalog(apiKey)
                AiProviderType.OPENROUTER -> error("Unsupported provider")
                AiProviderType.CUSTOM -> error("Unsupported provider")
            }
        }.getOrElse { cached }

        return if (refreshed.models.isNotEmpty()) refreshed else cached
    }

    private suspend fun fetchOpenAiCatalog(apiKey: String): ProviderModelCatalog {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .build()

        val gson = Gson()
        val catalog = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }.use { response ->
            if (!response.isSuccessful) return@use ProviderModelCatalog()

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use ProviderModelCatalog()

            val root = gson.fromJson(body, JsonObject::class.java)
            val data = root.getAsJsonArray("data") ?: return@use ProviderModelCatalog()
            val models = buildList {
                data.forEach { element ->
                    val obj = element.asJsonObject
                    val id = obj.get("id")?.asString ?: return@forEach
                    if (!looksLikeOpenAiChatModel(id)) return@forEach
                    add(
                        ProviderModelCatalogEntry(
                            id = id,
                            displayName = id,
                            createdAt = obj.get("created")?.asLong ?: 0L
                        )
                    )
                }
            }.sortedWith(
                compareByDescending<ProviderModelCatalogEntry> { it.createdAt }
                    .thenByDescending { it.id }
            )

            ProviderModelCatalog(
                fetchedAt = nowMillis(),
                models = models
            )
        }

        if (catalog.models.isNotEmpty()) {
            prefs.saveProviderModelCatalog(AiProviderType.OPENAI, catalog)
        }
        return catalog
    }

    private suspend fun fetchAnthropicCatalog(apiKey: String): ProviderModelCatalog {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models?limit=1000")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()

        val gson = Gson()
        val catalog = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }.use { response ->
            if (!response.isSuccessful) return@use ProviderModelCatalog()

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use ProviderModelCatalog()

            val root = gson.fromJson(body, JsonObject::class.java)
            val data = root.getAsJsonArray("data") ?: return@use ProviderModelCatalog()
            val models = buildList {
                data.forEach { element ->
                    val obj = element.asJsonObject
                    val id = obj.get("id")?.asString ?: return@forEach
                    if (!id.startsWith("claude")) return@forEach
                    add(
                        ProviderModelCatalogEntry(
                            id = id,
                            displayName = obj.get("display_name")?.asString ?: id,
                            createdAt = obj.get("created_at")?.asString
                                ?.let(::parseIsoTimestamp)
                                ?: 0L
                        )
                    )
                }
            }.sortedWith(
                compareByDescending<ProviderModelCatalogEntry> { it.createdAt }
                    .thenByDescending { it.id }
            )

            ProviderModelCatalog(
                fetchedAt = nowMillis(),
                models = models
            )
        }

        if (catalog.models.isNotEmpty()) {
            prefs.saveProviderModelCatalog(AiProviderType.ANTHROPIC, catalog)
        }
        return catalog
    }

    private fun looksLikeOpenAiChatModel(id: String): Boolean {
        val normalized = id.lowercase()
        if (
            normalized.contains("embed") ||
            normalized.contains("image") ||
            normalized.contains("audio") ||
            normalized.contains("transcribe") ||
            normalized.contains("tts") ||
            normalized.contains("whisper") ||
            normalized.contains("realtime") ||
            normalized.contains("moderation") ||
            normalized.contains("dall") ||
            normalized.contains("search-preview") ||
            normalized.contains("transcription") ||
            normalized.contains("speech")
        ) {
            return false
        }

        return normalized.startsWith("gpt-") || normalized.startsWith("chatgpt-")
    }

    private fun parseIsoTimestamp(raw: String): Long? =
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()

    companion object {
        const val DEFAULT_CATALOG_MAX_AGE_MS = 6L * 60L * 60L * 1_000L
    }
}
