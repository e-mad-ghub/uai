package com.mad.screenagent.shared.streaming

/**
 * Compresses conversation history to avoid overloading model context windows.
 * Attachment payloads are retained only on the most recent user turn that still needs raw
 * multimodal context. Older image/file/document payloads are stripped before tiering so
 * follow-up turns do not keep resending the full attachment context and inflating prompt tokens.
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
    olderMessageMaxLength: Int = 300,
    keepMostRecentRawAttachments: Boolean = true
): List<ChatMessage> {
    if (messages.isEmpty()) return messages

    val normalizedMessages = retainMostRecentAttachmentContext(
        messages = messages,
        keepMostRecentRawAttachments = keepMostRecentRawAttachments
    )

    val recentMessages = normalizedMessages.takeLast(maxRecentMessages)
    val afterRecent = normalizedMessages.dropLast(maxRecentMessages)

    val midMessages = afterRecent.takeLast(maxMidMessages).map { msg ->
        msg.copy(images = emptyList(), fileAttachment = null, documentBase64 = null)
    }

    val olderMessages = afterRecent.dropLast(maxMidMessages).takeLast(maxOlderMessages).map { msg ->
        val maxLength = if (msg.hasAttachmentMemoryBlock()) {
            maxOf(olderMessageMaxLength, 1200)
        } else {
            olderMessageMaxLength
        }
        msg.copy(
            content = if (msg.content.length > maxLength)
                msg.content.take(maxLength) + "…"
            else
                msg.content,
            images = emptyList(),
            fileAttachment = null,
            documentBase64 = null
        )
    }

    return olderMessages + midMessages + recentMessages
}

private fun ChatMessage.withoutAttachmentContext(): ChatMessage =
    copy(images = emptyList(), fileAttachment = null, documentBase64 = null)

private fun ChatMessage.withoutRawImageContext(): ChatMessage =
    copy(images = emptyList())

internal fun retainMostRecentAttachmentContext(
    messages: List<ChatMessage>,
    keepMostRecentRawAttachments: Boolean = true
): List<ChatMessage> {
    if (messages.isEmpty()) return messages

    val keepAttachmentsIndex = if (keepMostRecentRawAttachments) {
        messages.indexOfLast { it.role == "user" && it.hasDirectAttachmentContext() }
            .takeIf { it >= 0 }
    } else {
        messages.indexOfLast { message ->
            message.role == "user" &&
                (message.fileAttachment != null || !message.documentBase64.isNullOrBlank())
        }.takeIf { it >= 0 }
    }

    return messages.mapIndexed { index, message ->
        when {
            index == keepAttachmentsIndex && keepMostRecentRawAttachments -> message
            index == keepAttachmentsIndex -> message.withoutRawImageContext()
            else -> message.withoutAttachmentContext()
        }
    }
}

internal fun retainCurrentTurnAttachmentsOnly(messages: List<ChatMessage>): List<ChatMessage> =
    retainMostRecentAttachmentContext(messages, keepMostRecentRawAttachments = true)
