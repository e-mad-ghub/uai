package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val IMAGE_SUMMARY_SYSTEM_PROMPT =
    "You create neutral image summaries for conversation memory. " +
        "Describe only what is visually present, include readable text when visible, " +
        "preserve uncertainties, and do not answer the user's request directly."

enum class ProviderFailureKind {
    MULTI_IMAGE_LIMIT,
    VISION_UNSUPPORTED,
    OTHER
}

fun classifyProviderFailure(throwable: Throwable): ProviderFailureKind {
    val message = throwable.message?.lowercase().orEmpty()
    val mentionsImage = message.contains("image") || message.contains("vision") || message.contains("multimodal")

    if (mentionsImage) {
        val multiImageSignals = listOf(
            "multi-image",
            "multi image",
            "multiple image",
            "multiple images",
            "too many images",
            "more than 1 image",
            "more than one image",
            "at most 1 image",
            "at most one image",
            "only one image",
            "only supports one image",
            "single image",
            "max 1 image",
            "maximum number of images"
        )
        if (multiImageSignals.any(message::contains)) {
            return ProviderFailureKind.MULTI_IMAGE_LIMIT
        }

        val unsupportedSignals = listOf(
            "vision is not supported",
            "does not support image",
            "images are not supported",
            "image input is not supported",
            "multimodal is not supported",
            "vision-capable"
        )
        if (unsupportedSignals.any(message::contains)) {
            return ProviderFailureKind.VISION_UNSUPPORTED
        }
    }

    return ProviderFailureKind.OTHER
}

class MultiImageConversationRuntime(
    private val providerFactory: (AgentConfig) -> AiProvider
) {

    fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        directStreamFactory: (List<ChatMessage>, AgentConfig) -> Flow<StreamChunk>,
        onAttachmentMemoryGenerated: suspend (String, AttachmentTurnMemory) -> Unit = { _, _ -> }
    ): Flow<StreamChunk> = flow {
        val fallbackIndex = messages.indexOfLast { it.role == "user" && it.images.size > 1 }
        if (fallbackIndex == -1) {
            directStreamFactory(messages, config).collect { emit(it) }
            return@flow
        }

        val bufferedSelections = mutableListOf<StreamChunk.ModelSelection>()
        var streamedAnyToken = false
        var completed = false
        var terminalError: Throwable? = null

        directStreamFactory(messages, config).collect { chunk ->
            when (chunk) {
                is StreamChunk.ModelSelection -> {
                    if (streamedAnyToken) emit(chunk) else bufferedSelections += chunk
                }

                is StreamChunk.Token -> {
                    if (!streamedAnyToken) {
                        streamedAnyToken = true
                        bufferedSelections.forEach { emit(it) }
                        bufferedSelections.clear()
                    }
                    emit(chunk)
                }

                is StreamChunk.Usage -> if (streamedAnyToken) emit(chunk)

                is StreamChunk.Done -> {
                    if (!streamedAnyToken) {
                        bufferedSelections.forEach { emit(it) }
                    }
                    emit(StreamChunk.Done)
                    completed = true
                }

                is StreamChunk.Error -> {
                    if (streamedAnyToken) {
                        emit(chunk)
                        completed = true
                    } else {
                        terminalError = chunk.cause
                    }
                }
            }
        }

        if (completed || streamedAnyToken) return@flow

        val directFailure = terminalError
        if (directFailure == null || classifyProviderFailure(directFailure) != ProviderFailureKind.MULTI_IMAGE_LIMIT) {
            if (directFailure != null) emit(StreamChunk.Error(directFailure))
            return@flow
        }

        val targetMessage = messages[fallbackIndex]
        val memory = summarizeImageTurn(
            message = targetMessage,
            config = config
        ) { usage ->
            emit(usage)
        } ?: run {
            emit(StreamChunk.Error(directFailure))
            return@flow
        }

        targetMessage.messageId?.let { onAttachmentMemoryGenerated(it, memory) }

        val fallbackMessages = messages.mapIndexed { index, message ->
            if (index == fallbackIndex) {
                message.copy(
                    content = buildFallbackImageTurnContent(message.content, memory),
                    images = emptyList()
                )
            } else {
                message
            }
        }

        directStreamFactory(fallbackMessages, config).collect { emit(it) }
    }

    suspend fun summarizeImageTurn(
        message: ChatMessage,
        config: AgentConfig,
        onUsage: suspend (StreamChunk.Usage) -> Unit = {}
    ): AttachmentTurnMemory? {
        if (message.images.isEmpty()) return null

        val summaryConfig = config.copy(
            systemPrompt = IMAGE_SUMMARY_SYSTEM_PROMPT,
            nativeWebSearchEnabled = false,
            nativeWebSearchToolType = null,
            temperature = 0.1f
        )

        val imageSummaries = message.images.mapIndexed { index, image ->
            val prompt = buildSingleImageSummaryPrompt(
                originalUserPrompt = message.content,
                imageIndex = index + 1,
                totalImages = message.images.size
            )
            val summary = collectResponseText(
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = prompt,
                        images = listOf(image)
                    )
                ),
                config = summaryConfig,
                onUsage = onUsage
            )
            AttachmentImageMemory(
                index = index + 1,
                label = "Image ${index + 1}",
                summary = summary.ifBlank { "No reliable visual summary was produced for this image." }
            )
        }

        return AttachmentTurnMemory(
            generatedByProvider = config.provider.name,
            generatedByModel = config.model,
            fallbackReason = "multi_image_refusal",
            images = imageSummaries
        )
    }

    private suspend fun collectResponseText(
        messages: List<ChatMessage>,
        config: AgentConfig,
        onUsage: suspend (StreamChunk.Usage) -> Unit
    ): String {
        val text = StringBuilder()
        var terminalError: Throwable? = null

        providerFactory(config).streamResponse(messages, config).collect { chunk ->
            when (chunk) {
                is StreamChunk.Token -> text.append(chunk.text)
                is StreamChunk.Usage -> onUsage(chunk)
                is StreamChunk.Error -> terminalError = chunk.cause
                is StreamChunk.ModelSelection,
                is StreamChunk.Done -> Unit
            }
        }

        terminalError?.let { throw it }
        return text.toString().trim()
    }
}

private fun buildSingleImageSummaryPrompt(
    originalUserPrompt: String,
    imageIndex: Int,
    totalImages: Int
): String = buildString {
    appendLine("Summarize this image for durable conversation memory.")
    appendLine("Focus on visible objects, people, layout, readable text, counts, colors, and notable details.")
    appendLine("State uncertainty instead of guessing.")
    appendLine("Return plain text only.")
    appendLine()
    appendLine("Image position in the user turn: $imageIndex of $totalImages.")
    val normalizedPrompt = originalUserPrompt.trim()
    if (normalizedPrompt.isNotBlank()) {
        appendLine("Original user prompt for the whole image turn:")
        append(normalizedPrompt)
    }
}
