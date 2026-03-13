package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import com.example.uai.data.repository.OpenRouterCatalogRepository
import com.example.uai.data.model.isOpenRouterFreeModel
import com.example.uai.data.model.openRouterFreeFallbackModels
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
    private val openRouterCatalogRepository: OpenRouterCatalogRepository? = null
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
        if (!isOpenRouterFreeModel(config.model)) {
            emit(StreamChunk.ModelSelection(config.model))
            delegate.streamResponse(messages, config).collect { emit(it) }
            return@flow
        }

        val requiresVision = messages.any { it.images.isNotEmpty() }
        val catalogEntries = openRouterCatalogRepository
            ?.refreshCatalogIfStale()
            ?.models
            .orEmpty()
        val candidates = openRouterFreeFallbackModels(
            catalogEntries = catalogEntries,
            currentModel = config.model,
            requireVision = requiresVision
        )

        var lastFailure: Exception? = null
        for (candidate in candidates) {
            val candidateConfig = config.copy(model = candidate)
            val failure = probeCandidate(messages, candidateConfig)
            if (failure == null) {
                emit(
                    StreamChunk.ModelSelection(
                        modelId = candidate,
                        viaFallback = candidate != config.model
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

        val summary = if (requiresVision) {
            "No available free OpenRouter image model responded. SideAgent tried fallback models automatically."
        } else {
            "No available free OpenRouter model responded. SideAgent tried fallback models automatically."
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
}
