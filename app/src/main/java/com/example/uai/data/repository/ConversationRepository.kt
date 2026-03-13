package com.example.uai.data.repository

import android.content.Context
import com.example.uai.data.db.ConversationDao
import com.example.uai.data.db.ConversationEntity
import com.example.uai.data.db.MessageDao
import com.example.uai.data.db.MessageEntity
import com.example.uai.ui.chat.deletePersistedImageAttachment
import kotlinx.coroutines.flow.Flow

class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val context: Context
) {
    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    fun getConversation(id: String): Flow<ConversationEntity?> =
        conversationDao.getById(id)

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessages(conversationId)

    suspend fun getMessagesList(conversationId: String): List<MessageEntity> =
        messageDao.getMessagesList(conversationId)

    suspend fun upsertConversation(conversation: ConversationEntity) =
        conversationDao.upsert(conversation)

    suspend fun deleteConversation(conversation: ConversationEntity) {
        messageDao.getMessagesList(conversation.id)
            .forEach { deletePersistedImageAttachment(context, it.imageUri) }
        messageDao.deleteByConversation(conversation.id)
        conversationDao.delete(conversation)
    }

    suspend fun insertMessage(message: MessageEntity) =
        messageDao.insert(message)

    suspend fun updateMessageContent(id: String, content: String, isStreaming: Boolean) =
        messageDao.updateContent(id, content, isStreaming)

    suspend fun updateMessageResponseModel(id: String, modelId: String, isFallback: Boolean) =
        messageDao.updateResponseModel(id, modelId, isFallback)

    suspend fun deleteMessage(id: String) {
        deletePersistedImageAttachment(context, messageDao.getById(id)?.imageUri)
        messageDao.deleteById(id)
    }

    suspend fun touchConversation(id: String) =
        conversationDao.touchUpdatedAt(id)
}
