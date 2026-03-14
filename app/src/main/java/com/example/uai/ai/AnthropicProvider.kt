package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnthropicProvider(private val client: OkHttpClient) : AiProvider {

    private val gson = Gson()
    private val json = "application/json".toMediaType()

    override fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> = flow {
        val body = buildBody(messages, config)
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(json))
            .build()

        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(StreamChunk.Error(Exception(httpErrorMessage(response.code))))
                    return@use
                }
                val source = response.body?.source() ?: run {
                    emit(StreamChunk.Error(Exception("Empty response body")))
                    return@use
                }

                var currentEventType = ""
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event: ") -> {
                            currentEventType = line.removePrefix("event: ").trim()
                        }
                        line.startsWith("data: ") -> {
                            val data = line.removePrefix("data: ").trim()
                            when (currentEventType) {
                                "content_block_delta" -> {
                                    try {
                                        val obj = gson.fromJson(data, JsonObject::class.java)
                                        val text = obj?.getAsJsonObject("delta")?.get("text")?.asString
                                        if (!text.isNullOrEmpty()) emit(StreamChunk.Token(text))
                                    } catch (_: Exception) {}
                                }
                                "message_stop" -> {
                                    emit(StreamChunk.Done)
                                    return@use
                                }
                            }
                        }
                        line.isBlank() -> currentEventType = ""
                    }
                }
                emit(StreamChunk.Done)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (e: Exception) {
            emit(StreamChunk.Error(e))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildBody(messages: List<ChatMessage>, config: AgentConfig): String {
        val apiMessages = messages
            .filter { it.role != "system" }
            .map { msg ->
                val textContent = msg.contentWithFileContext()
                val content: Any = when {
                    msg.images.isNotEmpty() -> buildList {
                        for (img in msg.images) {
                            add(mapOf(
                                "type" to "image",
                                "source" to mapOf(
                                    "type" to "base64",
                                    "media_type" to img.mimeType,
                                    "data" to img.base64
                                )
                            ))
                        }
                        if (textContent.isNotBlank()) {
                            add(mapOf("type" to "text", "text" to textContent))
                        }
                    }
                    msg.documentBase64 != null -> buildList {
                        add(mapOf(
                            "type" to "document",
                            "source" to mapOf(
                                "type" to "base64",
                                "media_type" to "application/pdf",
                                "data" to msg.documentBase64
                            )
                        ))
                        if (textContent.isNotBlank()) {
                            add(mapOf("type" to "text", "text" to textContent))
                        }
                    }
                    else -> textContent
                }
                mapOf("role" to msg.role, "content" to content)
            }

        return gson.toJson(
            buildMap {
                put("model", config.model)
                put("max_tokens", 4096)
                put("stream", true)
                put("messages", apiMessages)
                if (config.systemPrompt.isNotBlank()) {
                    put("system", config.systemPrompt)
                }
            }
        )
    }
}
