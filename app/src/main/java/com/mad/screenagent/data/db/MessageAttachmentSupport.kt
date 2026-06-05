package com.mad.screenagent.data.db

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mad.screenagent.shared.streaming.AttachmentImageMemory
import com.mad.screenagent.shared.streaming.AttachmentTurnMemory
import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.FileAttachmentContext
import com.mad.screenagent.shared.streaming.ImageAttachment

private val gson = Gson()

fun MessageEntity.fileAttachmentOrNull(): FileAttachmentContext? {
    val fileName = attachedFileName?.takeIf { it.isNotBlank() } ?: return null
    val extractedText = attachedFileText?.takeIf { it.isNotBlank() } ?: return null
    return FileAttachmentContext(
        displayName = fileName,
        extractedText = extractedText
    )
}

fun MessageEntity.hasDirectAttachmentContext(): Boolean {
    return storedImages().isNotEmpty() ||
        fileAttachmentOrNull() != null ||
        !documentBase64.isNullOrBlank()
}

fun MessageEntity.storedImages(): List<ImageAttachment> {
    val json = imagesJson?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        JsonParser.parseString(json)
            .asJsonArray
            .mapNotNull { element ->
                element.asObjectOrNull()?.toImageAttachmentOrNull()
            }
    } catch (_: Exception) {
        emptyList()
    }
}

fun imageAttachmentsJsonOrNull(images: List<ImageAttachment>): String? {
    return images.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
}

fun MessageEntity.attachmentMemoryOrNull(): AttachmentTurnMemory? {
    val json = attachmentMemoryJson?.takeIf { it.isNotBlank() } ?: return null
    return try {
        JsonParser.parseString(json)
            .asObjectOrNull()
            ?.toAttachmentTurnMemoryOrNull()
    } catch (_: Exception) {
        null
    }
}

fun attachmentMemoryJsonOrNull(memory: AttachmentTurnMemory?): String? {
    return memory?.takeIf { it.images.isNotEmpty() }?.let { gson.toJson(it) }
}

fun MessageEntity.toChatMessage(
    contentOverride: String = content,
    images: List<ImageAttachment> = storedImages()
): ChatMessage {
    return ChatMessage(
        messageId = id,
        role = role,
        content = contentOverride,
        images = images,
        fileAttachment = fileAttachmentOrNull(),
        documentBase64 = documentBase64
    )
}

private fun JsonElement.asObjectOrNull(): JsonObject? =
    takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonObject.longOrNull(name: String): Long? =
    get(name)?.takeUnless { it.isJsonNull }?.asLong

private fun JsonObject.intOrNull(name: String): Int? =
    get(name)?.takeUnless { it.isJsonNull }?.asInt

private fun JsonObject.toImageAttachmentOrNull(): ImageAttachment? {
    val base64 = stringOrNull("base64")?.takeIf { it.isNotBlank() } ?: return null
    val mimeType = stringOrNull("mimeType")?.takeIf { it.isNotBlank() } ?: "image/jpeg"
    return ImageAttachment(base64 = base64, mimeType = mimeType)
}

private fun JsonObject.toAttachmentImageMemoryOrNull(): AttachmentImageMemory? {
    val summary = stringOrNull("summary")?.takeIf { it.isNotBlank() } ?: return null
    return AttachmentImageMemory(
        index = intOrNull("index") ?: 0,
        label = stringOrNull("label")?.takeIf { it.isNotBlank() } ?: "Image",
        summary = summary
    )
}

private fun JsonObject.toAttachmentTurnMemoryOrNull(): AttachmentTurnMemory? {
    val images = get("images")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { it.asObjectOrNull()?.toAttachmentImageMemoryOrNull() }
        .orEmpty()
    return AttachmentTurnMemory(
        generatedAt = longOrNull("generatedAt") ?: System.currentTimeMillis(),
        generatedByProvider = stringOrNull("generatedByProvider"),
        generatedByModel = stringOrNull("generatedByModel"),
        fallbackReason = stringOrNull("fallbackReason"),
        images = images
    ).takeIf { it.images.isNotEmpty() }
}
