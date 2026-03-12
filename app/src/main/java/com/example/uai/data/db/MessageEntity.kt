package com.example.uai.data.db

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
    val agentName: String? = null,   // non-null only for Agora assistant messages
    val responseModelId: String? = null,
    val responseModelIsFallback: Boolean = false
)
