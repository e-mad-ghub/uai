package com.mad.screenagent.shared.streaming

/**
 * Compresses conversation history to avoid overloading model context windows.
 * Attachment payloads are only retained on the current user turn. Older image/file/document
 * payloads are stripped before tiering so follow-up turns do not keep resending the full
 * attachment context and inflating prompt tokens.
 *
 * Three tiers (oldest → newest):
 *
 * | Tier       | Count | Text       | Attachments |
 * |------------|-------|------------|-------------|
 * | Dropped    | rest  | —          | —           |
 * | Truncated  | 10    | ≤300 chars | stripped    |
 * | Mid        | 20    | full       | stripped    |
 * | Recent     | 20    | full       | kept        |
 */
fun compressHistory(
    messages: List<ChatMessage>,
    maxRecentMessages: Int = 20,
    maxMidMessages: Int = 20,
    maxOlderMessages: Int = 10,
    olderMessageMaxLength: Int = 300
): List<ChatMessage> {
    if (messages.isEmpty()) return messages

    val normalizedMessages = retainCurrentTurnAttachmentsOnly(messages)

    val recentMessages = normalizedMessages.takeLast(maxRecentMessages)
    val afterRecent = normalizedMessages.dropLast(maxRecentMessages)

    val midMessages = afterRecent.takeLast(maxMidMessages).map { msg ->
        msg.copy(images = emptyList(), fileAttachment = null, documentBase64 = null)
    }

    val olderMessages = afterRecent.dropLast(maxMidMessages).takeLast(maxOlderMessages).map { msg ->
        msg.copy(
            content = if (msg.content.length > olderMessageMaxLength)
                msg.content.take(olderMessageMaxLength) + "…"
            else
                msg.content,
            images = emptyList(),
            fileAttachment = null,
            documentBase64 = null
        )
    }

    return olderMessages + midMessages + recentMessages
}

fun compressOnDeviceHistory(messages: List<ChatMessage>): List<ChatMessage> =
    compressHistory(
        messages = messages,
        maxRecentMessages = 8,
        maxMidMessages = 6,
        maxOlderMessages = 4,
        olderMessageMaxLength = 160
    )

private fun ChatMessage.withoutAttachmentContext(): ChatMessage =
    copy(images = emptyList(), fileAttachment = null, documentBase64 = null)

internal fun retainCurrentTurnAttachmentsOnly(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages

    val lastUserIndex = messages.indexOfLast { it.role == "user" }
    val keepAttachmentsIndex = lastUserIndex.takeIf { index ->
        index >= 0 && messages[index].hasDirectAttachmentContext()
    }

    return messages.mapIndexed { index, message ->
        if (index == keepAttachmentsIndex) message else message.withoutAttachmentContext()
    }
}
