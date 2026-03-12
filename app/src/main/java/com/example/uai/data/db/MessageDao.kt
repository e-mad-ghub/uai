package com.example.uai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessagesList(conversationId: String): List<MessageEntity>

    @Query("UPDATE messages SET content = :content, isStreaming = :isStreaming WHERE id = :id")
    suspend fun updateContent(id: String, content: String, isStreaming: Boolean)

    @Query("UPDATE messages SET responseModelId = :modelId, responseModelIsFallback = :isFallback WHERE id = :id")
    suspend fun updateResponseModel(id: String, modelId: String, isFallback: Boolean)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)
}
