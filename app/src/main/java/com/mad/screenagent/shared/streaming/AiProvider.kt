package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow

data class ImageAttachment(
    val base64: String,
    val mimeType: String = "image/jpeg"
)

data class FileAttachmentContext(
    val displayName: String,
    val extractedText: String
)

data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<ImageAttachment> = emptyList(),
    val fileAttachment: FileAttachmentContext? = null,
    /** Base64-encoded PDF — only sent by providers that support it (Anthropic). */
    val documentBase64: String? = null,
    val messageId: String? = null
)

fun ChatMessage.contentWithFileContext(): String {
    val attachment = fileAttachment ?: return content
    return buildString {
        append("<attached_file name=\"")
        append(attachment.displayName)
        append("\">\n")
        append(attachment.extractedText)
        append("\n</attached_file>\n\n")
        append(content)
    }
}

/** Returns a user-friendly error message for an HTTP error response from an AI provider. */
fun httpErrorMessage(code: Int): String {
    val description = when (code) {
        400 -> "Bad request — the model may not support this message format or the request was malformed"
        401 -> "Invalid API key — check the API key in your agent settings"
        402 -> "Insufficient credits — add credits to your account to use this model"
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
     * Emits StreamChunk.Token for each fragment, StreamChunk.ModelSelection
     * when a provider chooses the concrete model for the response,
     * StreamChunk.Done on completion, and StreamChunk.Error on failure.
     * The Flow runs on Dispatchers.IO.
     */
    fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk>
}
