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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.UUID

class ConversationDetailViewModel(
    private val conversationId: String,
    private val repo: ConversationRepository,
    private val agentRepo: AgentRepository,
    private val httpClient: OkHttpClient
) : ViewModel() {

    val conversation = repo.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages = repo.getMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agents = agentRepo.agentsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAgent = agentRepo.activeAgentFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private var streamingJob: Job? = null

    fun onInputChange(text: String) { _inputText.value = text }

    fun setActiveAgent(agent: AgentConfig) {
        viewModelScope.launch { agentRepo.setActiveAgent(agent.id) }
    }

    fun stopResponse() {
        streamingJob?.cancel()
        streamingJob = null
    }

    fun sendMessage(text: String) {
        val agent = activeAgent.value ?: return
        if (text.isBlank() || _isLoading.value) return

        streamingJob = viewModelScope.launch {
            _isLoading.value = true
            _inputText.value = ""

            val isFirstMessage = messages.value.isEmpty()

            if (isFirstMessage) {
                val title = text.trim().take(60)
                val existing = conversation.value
                if (existing != null) {
                    repo.upsertConversation(existing.copy(title = title))
                } else {
                    repo.upsertConversation(
                        ConversationEntity(
                            id = conversationId,
                            title = title,
                            agentId = agent.id,
                            agentName = agent.name,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            repo.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "user",
                    content = text,
                    createdAt = System.currentTimeMillis()
                )
            )

            val assistantId = UUID.randomUUID().toString()
            repo.insertMessage(
                MessageEntity(
                    id = assistantId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = "",
                    createdAt = System.currentTimeMillis(),
                    isStreaming = true
                )
            )

            val history = repo.getMessagesList(conversationId)
                .filter { !it.isStreaming }
                .map { ChatMessage(it.role, it.content) }

            var accumulated = ""
            try {
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
                                repo.updateMessageContent(
                                    assistantId,
                                    "$accumulated\n[Error: ${chunk.cause.message}]",
                                    false
                                )
                            }
                        }
                    }
            } finally {
                // Runs on both normal completion AND cancellation (user pressed stop).
                // NonCancellable lets us call suspend functions even when cancelled.
                withContext(NonCancellable) {
                    repo.updateMessageContent(assistantId, accumulated, false)
                    _isLoading.value = false
                }
            }
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
