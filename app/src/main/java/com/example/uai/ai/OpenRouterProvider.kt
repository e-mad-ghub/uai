package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.OpenRouterFreeRoutingBucket
import com.example.uai.data.repository.OpenRouterCatalogRepository
import com.example.uai.data.model.SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
import com.example.uai.data.model.isOpenRouterConcreteFreeModel
import com.example.uai.data.model.openRouterFreeFallbackModels
import com.example.uai.data.model.openRouterBestFreeCandidates
import com.example.uai.data.model.shouldRetryOpenRouterFreeFallback
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenRouterProvider(
    client: OkHttpClient,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository? = null,
    private val bestFreeRoutingStateStore: OpenRouterBestFreeRoutingStateStore? = null
) : AiProvider {

    private val routedClient = client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("HTTP-Referer", "https://uai.app")
                .header("X-Title", "SideAgent")
                .build()
            chain.proceed(request)
        }
        .build()

    private val delegate = OpenAiProvider(
        client = routedClient,
        baseUrl = "https://openrouter.ai/api/v1"
    )

    private val gson = Gson()
    private val json = "application/json".toMediaType()

    override fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> = flow {
        val isBestFreeRoute = config.model == SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
        val requestBucket = classifyRequestBucket(messages)
        val catalogEntries = openRouterCatalogRepository
            ?.refreshCatalogIfStale(
                maxAgeMs = if (isBestFreeRoute) BEST_FREE_CATALOG_MAX_AGE_MS
                else OpenRouterCatalogRepository.DEFAULT_CATALOG_MAX_AGE_MS
            )
            ?.models
            .orEmpty()
        val fetchedModelIds = catalogEntries.map { it.id }
        val freeModelIds = catalogEntries
            .asSequence()
            .filter { it.isFree }
            .map { it.id }
            .toSet()

        if (!isBestFreeRoute && !isOpenRouterConcreteFreeModel(config.model, freeModelIds)) {
            emit(StreamChunk.ModelSelection(config.model))
            delegate.streamResponse(messages, config).collect { emit(it) }
            return@flow
        }

        val candidates = if (isBestFreeRoute) {
            val startModelId = bestFreeRoutingStateStore?.lastSuccessfulModelId(
                assistantId = config.id,
                bucket = requestBucket,
                idleTimeoutMs = BEST_FREE_IDLE_TIMEOUT_MS
            )
            openRouterBestFreeCandidates(
                bucket = requestBucket,
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedModelIds,
                freeModelIds = freeModelIds,
                startModelId = startModelId
            )
        } else {
            openRouterFreeFallbackModels(
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedModelIds,
                freeModelIds = freeModelIds,
                currentModel = config.model,
                requireVision = requestBucket == OpenRouterFreeRoutingBucket.VISION
            )
        }

        var lastFailure: Exception? = null
        for ((index, candidate) in candidates.withIndex()) {
            val candidateConfig = config.copy(model = candidate)
            val failure = probeCandidate(messages, candidateConfig)
            if (failure == null) {
                if (isBestFreeRoute) {
                    bestFreeRoutingStateStore?.recordSuccess(
                        assistantId = config.id,
                        bucket = requestBucket,
                        modelId = candidate
                    )
                }
                emit(
                    StreamChunk.ModelSelection(
                        modelId = candidate,
                        viaFallback = index > 0
                    )
                )
                delegate.streamResponse(messages, candidateConfig).collect { emit(it) }
                return@flow
            }

            lastFailure = failure
            if (!shouldRetryOpenRouterFreeFallback(extractHttpCode(failure.message), failure.message.orEmpty())) {
                emit(StreamChunk.Error(failure))
                return@flow
            }
        }

        val summary = if (requestBucket == OpenRouterFreeRoutingBucket.VISION) {
            "No available free OpenRouter image model responded. SideAgent tried fallback models automatically."
        } else {
            "No available free OpenRouter model responded. SideAgent tried fallback models automatically."
        }
        if (isBestFreeRoute) {
            bestFreeRoutingStateStore?.clear(
                assistantId = config.id,
                bucket = requestBucket
            )
        }
        emit(StreamChunk.Error(Exception(lastFailure?.message ?: summary)))
    }.flowOn(Dispatchers.IO)

    private fun probeCandidate(
        messages: List<ChatMessage>,
        config: AgentConfig
    ): Exception? {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(buildBody(messages, config, stream = false, maxTokens = 1).toRequestBody(json))
            .build()

        routedClient.newCall(request).execute().use { response ->
            return if (response.isSuccessful) {
                null
            } else {
                Exception(httpErrorMessage(response.code))
            }
        }
    }

    private fun buildBody(
        messages: List<ChatMessage>,
        config: AgentConfig,
        stream: Boolean,
        maxTokens: Int
    ): String {
        val msgs = buildList {
            if (config.systemPrompt.isNotBlank()) {
                add(mapOf("role" to "system", "content" to config.systemPrompt))
            }
            addAll(messages.map { msg ->
                val content: Any = if (msg.images.isNotEmpty()) {
                    buildList {
                        for (img in msg.images) {
                            add(
                                mapOf(
                                    "type" to "image_url",
                                    "image_url" to mapOf(
                                        "url" to "data:${img.mimeType};base64,${img.base64}"
                                    )
                                )
                            )
                        }
                        if (msg.content.isNotBlank()) {
                            add(mapOf("type" to "text", "text" to msg.content))
                        }
                    }
                } else {
                    msg.content
                }
                mapOf("role" to msg.role, "content" to content)
            })
        }

        return gson.toJson(
            mapOf(
                "model" to config.model,
                "messages" to msgs,
                "stream" to stream,
                "temperature" to config.temperature,
                "max_tokens" to maxTokens
            )
        )
    }

    private fun extractHttpCode(message: String?): Int? {
        return Regex("""HTTP\s+(\d+)""")
            .find(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun classifyRequestBucket(messages: List<ChatMessage>): OpenRouterFreeRoutingBucket {
        val lastUserContent = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        return when {
            messages.any { it.images.isNotEmpty() } -> OpenRouterFreeRoutingBucket.VISION
            lastUserContent.contains("<attached_file ", ignoreCase = true) ->
                OpenRouterFreeRoutingBucket.DOCUMENT
            else -> OpenRouterFreeRoutingBucket.GENERAL
        }
    }

    companion object {
        const val BEST_FREE_CATALOG_MAX_AGE_MS = 10L * 60L * 1_000L
        const val BEST_FREE_IDLE_TIMEOUT_MS = 10L * 60L * 1_000L
    }
}
