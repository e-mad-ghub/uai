package com.mad.screenagent.shared.streaming

import android.util.Log
import com.mad.screenagent.data.model.AgentConfig
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
    private val tag = "AnthropicProvider"
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
                    val errorBody = response.body?.string()?.take(400) ?: ""
                    emit(StreamChunk.Error(Exception("${httpErrorMessage(response.code)}\n$errorBody".trim())))
                    return@use
                }
                val source = response.body?.source() ?: run {
                    emit(StreamChunk.Error(Exception("Empty response body")))
                    return@use
                }

                var currentEventType = ""
                var inputTokens = 0
                var outputTokens = 0
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
                                "message_start" -> {
                                    try {
                                        val obj = gson.fromJson(data, JsonObject::class.java)
                                        inputTokens = obj?.getAsJsonObject("message")
                                            ?.getAsJsonObject("usage")
                                            ?.get("input_tokens")?.asInt ?: 0
                                    } catch (e: Exception) {
                                        Log.w(tag, "Failed to parse message_start usage", e)
                                    }
                                }
                                "content_block_delta" -> {
                                    try {
                                        val obj = gson.fromJson(data, JsonObject::class.java)
                                        val text = obj?.getAsJsonObject("delta")?.get("text")?.asString
                                        if (!text.isNullOrEmpty()) emit(StreamChunk.Token(text))
                                    } catch (e: Exception) {
                                        Log.w(tag, "Failed to parse content_block_delta", e)
                                    }
                                }
                                "message_delta" -> {
                                    try {
                                        val obj = gson.fromJson(data, JsonObject::class.java)
                                        outputTokens = obj?.getAsJsonObject("usage")
                                            ?.get("output_tokens")?.asInt ?: outputTokens
                                    } catch (e: Exception) {
                                        Log.w(tag, "Failed to parse message_delta usage", e)
                                    }
                                }
                                "message_stop" -> {
                                    if (inputTokens > 0 || outputTokens > 0) {
                                        emit(StreamChunk.Usage(inputTokens, outputTokens))
                                    }
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
        // Anthropic requires: first message is "user", messages alternate user/assistant.
        // After history compression the list may start with an "assistant" turn — drop leading
        // non-user messages. Then merge consecutive same-role messages so the list alternates.
        val normalized = messages
            .filter { it.role != "system" }
            .dropWhile { it.role != "user" }
            .fold(mutableListOf<ChatMessage>()) { acc, msg ->
                val last = acc.lastOrNull()
                if (last != null && last.role == msg.role) {
                    // Merge: concatenate text content; keep attachments from last entry
                    acc[acc.lastIndex] = last.copy(
                        content = if (last.content.isBlank()) msg.content
                                  else if (msg.content.isBlank()) last.content
                                  else "${last.content}\n${msg.content}"
                    )
                } else {
                    acc += msg
                }
                acc
            }

        val apiMessages = normalized.map { msg ->
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

        val toolType = config.nativeWebSearchToolType
            ?.takeIf { it.isNotBlank() }
            ?: NativeWebSearchConfig.ANTHROPIC_DEFAULT_TOOL_TYPE

        return gson.toJson(
            buildMap {
                put("model", config.model)
                put("max_tokens", 4096)
                put("stream", true)
                put("messages", apiMessages)
                if (config.systemPrompt.isNotBlank()) {
                    put("system", config.systemPrompt)
                }
                if (config.nativeWebSearchEnabled) {
                    put("tools", listOf(
                        mapOf(
                            "type" to toolType,
                            "name" to "web_search",
                            "max_uses" to NativeWebSearchConfig.ANTHROPIC_MAX_USES
                        )
                    ))
                }
            }
        )
    }
}
