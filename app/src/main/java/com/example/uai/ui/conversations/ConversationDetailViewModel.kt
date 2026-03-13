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
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.repository.AgentRepository
import com.example.uai.data.repository.ConversationRepository
import com.example.uai.data.repository.OpenRouterCatalogRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.UUID

class ConversationDetailViewModel(
    val conversationId: String,
    private val repo: ConversationRepository,
    private val agentRepo: AgentRepository,
    private val httpClient: OkHttpClient,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository
) : ViewModel() {

    val conversation = repo.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages = repo.getMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agents = agentRepo.agentsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val defaultAgent = agentRepo.activeAgentFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val draftAgentId = MutableStateFlow<String?>(null)

    val activeAgent = combine(
        conversation,
        agents,
        defaultAgent,
        draftAgentId
    ) { conversation, agents, defaultAgent, draftAgentId ->
        when {
            conversation != null -> agents.firstOrNull { it.id == conversation.agentId }
            draftAgentId != null -> agents.firstOrNull { it.id == draftAgentId }
            else -> defaultAgent
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _errorEvent = Channel<String>(Channel.BUFFERED)
    val errorEvent = _errorEvent.receiveAsFlow()

    private var streamingJob: Job? = null

    fun onInputChange(text: String) { _inputText.value = text }

    fun setActiveAgent(agent: AgentConfig) {
        viewModelScope.launch {
            val existingConversation = conversation.value
            if (existingConversation != null) {
                if (existingConversation.agentId != agent.id || existingConversation.agentName != agent.name) {
                    repo.upsertConversation(
                        existingConversation.copy(
                            agentId = agent.id,
                            agentName = agent.name
                        )
                    )
                }
            } else {
                draftAgentId.value = agent.id
            }
        }
    }

    fun stopResponse() {
        streamingJob?.cancel()
        streamingJob = null
    }

    fun sendMessage(
        text: String,
        imageBase64: String? = null,
        imageUri: String? = null,
        titleHint: String? = null
    ) {
        val agent = activeAgent.value ?: return
        if ((text.isBlank() && imageBase64 == null) || _isLoading.value) return

        streamingJob = viewModelScope.launch {
            _isLoading.value = true
            _inputText.value = ""

            val isFirstMessage = messages.value.isEmpty()

            if (isFirstMessage) {
                val title = titleHint
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.take(60)
                    ?: text.trim().ifBlank { if (imageBase64 != null) "Image" else "Chat" }.take(60)
                val existing = conversation.value
                if (existing != null) {
                    repo.upsertConversation(
                        existing.copy(
                            title = title,
                            agentId = agent.id,
                            agentName = agent.name
                        )
                    )
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
                    createdAt = System.currentTimeMillis(),
                    imageUri = imageUri
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
                    isStreaming = true,
                    agentName = agent.name
                )
            )

            // If the agent doesn't support the attachment type, say so in the chat
            if (imageBase64 != null && !agent.canHandleImageRequests()) {
                repo.updateMessageContent(
                    assistantId,
                    "I don't support image analysis with \"${agent.model}\". Please switch to a vision-capable model in agent settings.",
                    false
                )
                repo.touchConversation(conversationId)
                return@launch
            }

            // Attach image input to the latest user message for the API call.
            val dbHistory = repo.getMessagesList(conversationId).filter { !it.isStreaming }
            val history = dbHistory.mapIndexed { index, msg ->
                if (index == dbHistory.lastIndex && msg.role == "user") {
                    when {
                        imageBase64 != null -> ChatMessage(
                            msg.role, msg.content,
                            images = listOf(com.example.uai.ai.ImageAttachment(imageBase64))
                        )
                        else -> ChatMessage(msg.role, msg.content)
                    }
                } else {
                    ChatMessage(msg.role, msg.content)
                }
            }

            var accumulated = ""
            try {
                AiProviderFactory.create(agent, httpClient, openRouterCatalogRepository)
                    .streamResponse(history, agent)
                    .catch { e -> emit(StreamChunk.Error(e)) }
                    .collect { chunk ->
                        when (chunk) {
                            is StreamChunk.Token -> {
                                accumulated += chunk.text
                                repo.updateMessageContent(assistantId, accumulated, true)
                            }
                            is StreamChunk.ModelSelection -> {
                                repo.updateMessageResponseModel(
                                    assistantId,
                                    chunk.modelId,
                                    chunk.viaFallback
                                )
                            }
                            is StreamChunk.Done -> {
                                repo.updateMessageContent(assistantId, accumulated, false)
                                repo.touchConversation(conversationId)
                            }
                            is StreamChunk.Error -> {
                                val errMsg = chunk.cause.message ?: "Unknown error"
                                repo.updateMessageContent(
                                    assistantId,
                                    "$accumulated\n[Error: $errMsg]",
                                    false
                                )
                                _errorEvent.trySend(
                                    "Request failed: $errMsg\n\nThe model \"${agent.model}\" may not support this request. Try switching to a different model."
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
        private val httpClient: OkHttpClient,
        private val openRouterCatalogRepository: OpenRouterCatalogRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            ConversationDetailViewModel(
                conversationId,
                repo,
                agentRepo,
                httpClient,
                openRouterCatalogRepository
            ) as T
    }
}
