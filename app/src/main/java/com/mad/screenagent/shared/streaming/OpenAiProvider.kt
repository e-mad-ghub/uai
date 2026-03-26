package com.mad.screenagent.shared.streaming

import android.util.Log
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiProvider(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.openai.com/v1"
) : AiProvider {

    private val gson = Gson()
    private val json = "application/json".toMediaType()
    private val tag = "OpenAiProvider"

    override fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> =
        if (shouldUseOpenAiResponsesApi(config)) streamResponsesApi(messages, config)
        else streamChatCompletions(messages, config)

    // Standard Chat Completions path (/v1/chat/completions)
    private fun streamChatCompletions(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> = flow {
        val body = buildChatCompletionsBody(messages, config)
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
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
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parseChatCompletionsLine(line)?.let { emit(it) }
                    if (line == "data: [DONE]") break
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

    // Responses API path (/v1/responses) — used for native web search
    private fun streamResponsesApi(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> = flow {
        val body = buildResponsesApiBody(messages, config)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${config.apiKey}")
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
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    // Handle response.completed inline so we can emit Usage + Done together
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        try {
                            val obj = gson.fromJson(data, JsonObject::class.java)
                            if (obj?.get("type")?.asString == "response.completed") {
                                val usageObj = obj.getAsJsonObject("response")?.getAsJsonObject("usage")
                                if (usageObj != null) {
                                    val input = usageObj.get("input_tokens")?.asInt ?: 0
                                    val output = usageObj.get("output_tokens")?.asInt ?: 0
                                    if (input > 0 || output > 0) emit(StreamChunk.Usage(input, output))
                                }
                                emit(StreamChunk.Done)
                                return@use
                            }
                        } catch (e: Exception) { Log.w(tag, "Failed to parse response.completed event", e) }
                    }
                    parseResponsesApiLine(line)?.let { chunk ->
                        emit(chunk)
                        if (chunk == StreamChunk.Done) return@use
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

    private fun buildChatCompletionsBody(messages: List<ChatMessage>, config: AgentConfig): String {
        val msgs = buildList {
            if (config.systemPrompt.isNotBlank()) {
                add(mapOf("role" to "system", "content" to config.systemPrompt))
            }
            addAll(messages.map { msg ->
                val textContent = msg.contentWithFileContext()
                val content: Any = if (msg.images.isNotEmpty()) {
                    buildList {
                        for (img in msg.images) {
                            add(mapOf(
                                "type" to "image_url",
                                "image_url" to mapOf(
                                    "url" to "data:${img.mimeType};base64,${img.base64}"
                                )
                            ))
                        }
                        if (textContent.isNotBlank()) {
                            add(mapOf("type" to "text", "text" to textContent))
                        }
                    }
                } else {
                    textContent
                }
                mapOf("role" to msg.role, "content" to content)
            })
        }
        return gson.toJson(
            mapOf(
                "model" to config.model,
                "messages" to msgs,
                "stream" to true,
                "stream_options" to mapOf("include_usage" to true),
                "temperature" to config.temperature
            )
        )
    }

    private fun buildResponsesApiBody(messages: List<ChatMessage>, config: AgentConfig): String {
        val toolType = config.nativeWebSearchToolType
            ?.takeIf { it.isNotBlank() }
            ?: NativeWebSearchConfig.OPENAI_DEFAULT_TOOL_TYPE

        val input = messages.map { msg ->
            mapOf("role" to msg.role, "content" to msg.contentWithFileContext())
        }
        return gson.toJson(
            buildMap {
                put("model", config.model)
                put("input", input)
                put("stream", true)
                put("tools", listOf(mapOf("type" to toolType)))
                if (config.systemPrompt.isNotBlank()) {
                    put("instructions", config.systemPrompt)
                }
            }
        )
    }

    private fun parseChatCompletionsLine(line: String): StreamChunk? {
        if (!line.startsWith("data: ")) return null
        val data = line.removePrefix("data: ").trim()
        if (data == "[DONE]") return StreamChunk.Done
        return try {
            val json = gson.fromJson(data, JsonObject::class.java)
            // Usage chunk: choices is empty, usage field is present
            val usageObj = json?.getAsJsonObject("usage")
            if (usageObj != null) {
                val input = usageObj.get("prompt_tokens")?.asInt ?: 0
                val output = usageObj.get("completion_tokens")?.asInt ?: 0
                if (input > 0 || output > 0) return StreamChunk.Usage(input, output)
            }
            val content = json
                ?.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("delta")
                ?.get("content")?.asString
            if (!content.isNullOrEmpty()) StreamChunk.Token(content) else null
        } catch (e: Exception) { Log.w(tag, "Failed to parse chat completions SSE line", e); null }
    }

    private fun parseResponsesApiLine(line: String): StreamChunk? {
        if (!line.startsWith("data: ")) return null
        val data = line.removePrefix("data: ").trim()
        if (data.isBlank()) return null
        return try {
            val obj = gson.fromJson(data, JsonObject::class.java) ?: return null
            when (obj.get("type")?.asString) {
                "response.output_text.delta" -> {
                    val delta = obj.get("delta")?.asString
                    if (!delta.isNullOrEmpty()) StreamChunk.Token(delta) else null
                }
                "response.completed" -> StreamChunk.Done
                "response.failed" -> {
                    val error = obj.getAsJsonObject("response")
                        ?.getAsJsonObject("error")
                        ?.get("message")?.asString
                        ?: "Response failed"
                    StreamChunk.Error(Exception(error))
                }
                else -> null
            }
        } catch (e: Exception) { Log.w(tag, "Failed to parse responses API SSE line", e); null }
    }
}

internal fun shouldUseOpenAiResponsesApi(config: AgentConfig): Boolean =
    config.provider == AiProviderType.OPENAI && config.nativeWebSearchEnabled
