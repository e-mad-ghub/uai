package com.mad.screenagent.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,           // "user" | "assistant"
    val content: String,
    val createdAt: Long,
    val isStreaming: Boolean = false,
    val imageUri: String? = null,
    val attachedFileName: String? = null,
    val attachedFileText: String? = null,
    val attachedPageScanCount: Int = 0,
    val agentId: String? = null,
    val agentName: String? = null,   // set when the reply should keep its assistant identity in the transcript
    val responseModelId: String? = null,
    val responseModelIsFallback: Boolean = false,
    val imagesJson: String? = null,       // Gson JSON array of base64 image strings for multi-image messages
    val documentBase64: String? = null    // base64-encoded PDF for Anthropic document support
)
