package com.mad.screenagent.data.repository

import android.content.Context
import com.mad.screenagent.data.db.ConversationDao
import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.db.MessageDao
import com.mad.screenagent.data.db.MessageEntity
import com.mad.screenagent.shared.attachment.deletePersistedImageAttachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val context: Context
) {
    private val updateMutex = Mutex()
    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    fun getConversation(id: String): Flow<ConversationEntity?> =
        conversationDao.getById(id)

    suspend fun getConversationOnce(id: String): ConversationEntity? =
        conversationDao.getByIdOnce(id)

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
        updateMutex.withLock {
            messageDao.updateContent(id, content, isStreaming)
        }

    suspend fun updateMessageResponseModel(id: String, modelId: String, isFallback: Boolean) =
        messageDao.updateResponseModel(id, modelId, isFallback)

    suspend fun deleteMessage(id: String) {
        deletePersistedImageAttachment(context, messageDao.getById(id)?.imageUri)
        messageDao.deleteById(id)
    }

    suspend fun touchConversation(id: String) =
        conversationDao.touchUpdatedAt(id)
}
