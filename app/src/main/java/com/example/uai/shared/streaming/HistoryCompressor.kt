package com.example.uai.shared.streaming

/**
 * Compresses conversation history to avoid overloading model context windows.
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

    val recentMessages = messages.takeLast(maxRecentMessages)
    val afterRecent = messages.dropLast(maxRecentMessages)

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
