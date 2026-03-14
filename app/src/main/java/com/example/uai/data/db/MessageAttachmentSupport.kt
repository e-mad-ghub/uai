package com.example.uai.data.db

import com.example.uai.ai.ChatMessage
import com.example.uai.ai.FileAttachmentContext
import com.example.uai.ai.ImageAttachment

fun MessageEntity.fileAttachmentOrNull(): FileAttachmentContext? {
    val fileName = attachedFileName?.takeIf { it.isNotBlank() } ?: return null
    val extractedText = attachedFileText?.takeIf { it.isNotBlank() } ?: return null
    return FileAttachmentContext(
        displayName = fileName,
        extractedText = extractedText
    )
}

fun MessageEntity.toChatMessage(
    contentOverride: String = content,
    images: List<ImageAttachment> = emptyList()
): ChatMessage {
    return ChatMessage(
        role = role,
        content = contentOverride,
        images = images,
        fileAttachment = fileAttachmentOrNull()
    )
}
