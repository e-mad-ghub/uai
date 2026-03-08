package com.example.uai.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index("updatedAt")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val agentId: String,
    val agentName: String,      // Denormalized for display without DataStore lookup
    val createdAt: Long,
    val updatedAt: Long
)
