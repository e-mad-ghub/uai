package com.mad.screenagent.shared.streaming

private const val ATTACHMENT_MEMORY_START_TAG = "<attached_image_turn_memory>"
private const val ATTACHMENT_MEMORY_END_TAG = "</attached_image_turn_memory>"

data class AttachmentImageMemory(
    val index: Int,
    val label: String,
    val summary: String
)

data class AttachmentTurnMemory(
    val generatedAt: Long = System.currentTimeMillis(),
    val generatedByProvider: String? = null,
    val generatedByModel: String? = null,
    val fallbackReason: String? = null,
    val images: List<AttachmentImageMemory> = emptyList()
)

fun AttachmentTurnMemory.hasUsableContent(): Boolean =
    images.any { it.summary.isNotBlank() }

fun AttachmentTurnMemory.toConversationMemoryBlock(): String = buildString {
    appendLine(ATTACHMENT_MEMORY_START_TAG)
    appendLine("Visual context from a previous user turn with ${images.size} attached image(s):")
    images.forEach { image ->
        appendLine("${image.label}:")
        appendLine(image.summary.trim())
        appendLine()
    }
    append(ATTACHMENT_MEMORY_END_TAG)
}

fun appendAttachmentMemoryToUserContent(
    originalContent: String,
    memory: AttachmentTurnMemory?
): String {
    val normalizedContent = originalContent.trim()
    if (memory == null || !memory.hasUsableContent()) return normalizedContent
    val memoryBlock = memory.toConversationMemoryBlock()
    return if (normalizedContent.isBlank()) {
        memoryBlock
    } else {
        "$normalizedContent\n\n$memoryBlock"
    }
}

fun buildFallbackImageTurnContent(
    originalContent: String,
    memory: AttachmentTurnMemory
): String = buildString {
    appendLine("Use the image summaries below as the visual context for this user turn.")
    appendLine()
    append(memory.toConversationMemoryBlock())
    val trimmedOriginal = originalContent.trim()
    if (trimmedOriginal.isNotBlank()) {
        appendLine()
        appendLine()
        appendLine("Original user request for this image turn:")
        append(trimmedOriginal)
    }
}

internal fun ChatMessage.hasAttachmentMemoryBlock(): Boolean =
    content.contains(ATTACHMENT_MEMORY_START_TAG)
