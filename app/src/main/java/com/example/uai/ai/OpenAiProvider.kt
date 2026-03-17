package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
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

    override fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> =
        if (config.nativeWebSearchEnabled) streamResponsesApi(messages, config)
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
            val content = json
                ?.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("delta")
                ?.get("content")?.asString
            if (!content.isNullOrEmpty()) StreamChunk.Token(content) else null
        } catch (_: Exception) { null }
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
        } catch (_: Exception) { null }
    }
}
