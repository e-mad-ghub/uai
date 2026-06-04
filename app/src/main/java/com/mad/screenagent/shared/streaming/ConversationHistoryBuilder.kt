package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.db.MessageEntity
import com.mad.screenagent.data.db.attachmentMemoryOrNull
import com.mad.screenagent.data.db.hasDirectAttachmentContext
import com.mad.screenagent.data.db.storedImages
import com.mad.screenagent.data.db.toChatMessage

fun buildConversationHistory(
    messages: List<MessageEntity>,
    keepMostRecentRawImageTurn: Boolean
): List<ChatMessage> {
    val keepRawImageIndex = if (keepMostRecentRawImageTurn) {
        messages.indexOfLast { message ->
            message.role == "user" && message.storedImages().isNotEmpty()
        }.takeIf { it >= 0 }
    } else {
        null
    }

    val contextualMessages = messages.mapIndexed { index, message ->
        val content = if (message.role == "user" && index != keepRawImageIndex) {
            appendAttachmentMemoryToUserContent(
                originalContent = message.content,
                memory = message.attachmentMemoryOrNull()
            )
        } else {
            message.content
        }
        message.toChatMessage(contentOverride = content)
    }

    return compressHistory(
        messages = contextualMessages,
        keepMostRecentRawAttachments = keepMostRecentRawImageTurn
    )
}
