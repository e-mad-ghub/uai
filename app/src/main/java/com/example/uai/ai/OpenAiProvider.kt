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

    override fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> = flow {
        val body = buildBody(messages, config)
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
                    parseLineToChunk(line)?.let { emit(it) }
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

    private fun buildBody(messages: List<ChatMessage>, config: AgentConfig): String {
        val msgs = buildList {
            if (config.systemPrompt.isNotBlank()) {
                add(mapOf("role" to "system", "content" to config.systemPrompt))
            }
            addAll(messages.map { msg ->
                val content: Any = if (msg.imageBase64 != null) {
                    buildList {
                        add(mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:${msg.imageMimeType ?: "image/jpeg"};base64,${msg.imageBase64}"
                            )
                        ))
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
                "stream" to true,
                "temperature" to config.temperature
            )
        )
    }

    private fun parseLineToChunk(line: String): StreamChunk? {
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
}
