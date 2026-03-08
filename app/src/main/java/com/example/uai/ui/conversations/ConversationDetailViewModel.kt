package com.example.uai.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.ai.AiProviderFactory
import com.example.uai.ai.ChatMessage
import com.example.uai.ai.StreamChunk
import com.example.uai.data.db.ConversationEntity
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.repository.AgentRepository
import com.example.uai.data.repository.ConversationRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID

class ConversationDetailViewModel(
    private val conversationId: String,
    private val repo: ConversationRepository,
    private val agentRepo: AgentRepository,
    private val httpClient: OkHttpClient
) : ViewModel() {

    val conversation: StateFlow<ConversationEntity?> = repo.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<MessageEntity>> = repo.getMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    fun onInputChange(text: String) { _inputText.value = text }

    fun sendMessage(text: String, agent: AgentConfig) {
        if (text.isBlank() || _isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _inputText.value = ""

            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "user",
                content = text,
                createdAt = System.currentTimeMillis()
            )
            repo.insertMessage(userMsg)

            val assistantId = UUID.randomUUID().toString()
            val assistantMsg = MessageEntity(
                id = assistantId,
                conversationId = conversationId,
                role = "assistant",
                content = "",
                createdAt = System.currentTimeMillis(),
                isStreaming = true
            )
            repo.insertMessage(assistantMsg)

            val history = repo.getMessagesList(conversationId)
                .filter { !it.isStreaming }
                .map { ChatMessage(it.role, it.content) }

            var accumulated = ""
            AiProviderFactory.create(agent, httpClient)
                .streamResponse(history, agent)
                .catch { e -> emit(StreamChunk.Error(e)) }
                .collect { chunk ->
                    when (chunk) {
                        is StreamChunk.Token -> {
                            accumulated += chunk.text
                            repo.updateMessageContent(assistantId, accumulated, true)
                        }
                        is StreamChunk.Done -> {
                            repo.updateMessageContent(assistantId, accumulated, false)
                            repo.touchConversation(conversationId)
                        }
                        is StreamChunk.Error -> {
                            val errMsg = "$accumulated\n[Error: ${chunk.cause.message}]"
                            repo.updateMessageContent(assistantId, errMsg, false)
                        }
                    }
                }

            _isLoading.value = false
        }
    }

    class Factory(
        private val conversationId: String,
        private val repo: ConversationRepository,
        private val agentRepo: AgentRepository,
        private val httpClient: OkHttpClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            ConversationDetailViewModel(conversationId, repo, agentRepo, httpClient) as T
    }
}
