package com.mad.screenagent.data.db

import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.FileAttachmentContext
import com.mad.screenagent.shared.streaming.ImageAttachment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private val gson = Gson()

fun MessageEntity.fileAttachmentOrNull(): FileAttachmentContext? {
    val fileName = attachedFileName?.takeIf { it.isNotBlank() } ?: return null
    val extractedText = attachedFileText?.takeIf { it.isNotBlank() } ?: return null
    return FileAttachmentContext(
        displayName = fileName,
        extractedText = extractedText
    )
}

fun MessageEntity.storedImages(): List<ImageAttachment> {
    val json = imagesJson?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        val type = object : TypeToken<List<ImageAttachment>>() {}.type
        gson.fromJson<List<ImageAttachment>>(json, type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

fun MessageEntity.toChatMessage(
    contentOverride: String = content,
    images: List<ImageAttachment> = storedImages()
): ChatMessage {
    return ChatMessage(
        role = role,
        content = contentOverride,
        images = images,
        fileAttachment = fileAttachmentOrNull(),
        documentBase64 = documentBase64
    )
}
