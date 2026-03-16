package com.example.uai.data.db

import android.util.Log
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val isAgora: Boolean = false,
    val agoraAgentIds: String = ""  // Gson JSON array of agent ID strings
) {
    fun parseAgoraAgentIds(): List<String> {
        if (agoraAgentIds.isBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(agoraAgentIds, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e("ConversationEntity", "Failed to parse agoraAgentIds: $agoraAgentIds", e)
            emptyList()
        }
    }

    companion object {
        private val gson = Gson()
    }
}
