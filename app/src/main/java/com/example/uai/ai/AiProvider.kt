package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow

data class ChatMessage(
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    /** Base64-encoded PDF — only sent by providers that support it (Anthropic). */
    val documentBase64: String? = null
)

/** Returns a user-friendly error message for an HTTP error response from an AI provider. */
fun httpErrorMessage(code: Int): String {
    val description = when (code) {
        400 -> "Bad request — the model may not support this message format or the request was malformed"
        401 -> "Invalid API key — check the API key in your agent settings"
        403 -> "Access denied — your API key may not have permission to use this model"
        404 -> "Model not found — check the model name in your agent settings"
        429 -> "Rate limit exceeded — too many requests, please wait a moment and try again"
        500 -> "Internal server error — the provider is experiencing issues, try again later"
        502 -> "Bad gateway — the provider returned an invalid response"
        503 -> "Service unavailable — the provider is temporarily down, try again later"
        529 -> "Provider overloaded — too many requests are being processed, try again later"
        else -> "Unexpected error from the provider"
    }
    return "HTTP $code · $description"
}

interface AiProvider {
    /**
     * Returns a cold Flow that streams the AI response token-by-token.
     * Emits StreamChunk.Token for each fragment, StreamChunk.Done on completion,
     * StreamChunk.Error on failure. The Flow runs on Dispatchers.IO.
     */
    fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk>
}
