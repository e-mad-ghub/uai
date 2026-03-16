package com.example.uai.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.ai.AssistantStreamingSession
import com.example.uai.ai.FileAttachmentContext
import com.example.uai.ai.ImageAttachment
import com.example.uai.ai.StreamChunk
import com.google.gson.Gson
import com.example.uai.ai.ThrottledStreamingMessageWriter
import com.example.uai.ai.ToolAwareAssistantRuntime
import com.example.uai.ai.WebGateway
import com.example.uai.ai.sanitizeGroundedAssistantResponse
import com.example.uai.data.db.ConversationEntity
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.db.toChatMessage
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.repository.AgentRepository
import com.example.uai.data.repository.ConversationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ConversationDetailViewModel(
    val conversationId: String,
    private val repo: ConversationRepository,
    private val agentRepo: AgentRepository,
    private val assistantRuntime: ToolAwareAssistantRuntime,
    private val webGateway: WebGateway
) : ViewModel() {

    private data class RepairResolution(
        val resolvedDefaultAgent: AgentConfig?,
        val fallbackAgent: AgentConfig?
    )

    val conversation = repo.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _activeSession = MutableStateFlow<AssistantStreamingSession?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sessionStateFlow: Flow<AssistantStreamingSession.State?> =
        _activeSession.flatMapLatest { it?.state ?: flowOf(null) }

    val messages: StateFlow<List<MessageEntity>> = combine(
        repo.getMessages(conversationId),
        sessionStateFlow
    ) { dbMessages, state ->
        if (state == null) return@combine dbMessages
        dbMessages.mapNotNull { msg ->
            when {
                msg.id != state.messageId -> msg
                state.hidden -> null
                else -> msg.copy(content = state.content, isStreaming = state.isStreaming)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        val resolvedDefaultAgent = resolveRepairResolution(agents, defaultAgent).resolvedDefaultAgent
        when {
            conversation != null -> {
                agents.firstOrNull { it.id == conversation.agentId }
                    ?: resolvedDefaultAgent
                    ?: agents.firstOrNull()
            }
            draftAgentId != null -> {
                agents.firstOrNull { it.id == draftAgentId }
                    ?: resolvedDefaultAgent
                    ?: agents.firstOrNull()
            }
            else -> resolvedDefaultAgent ?: agents.firstOrNull()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _onlineSearchStatus = MutableStateFlow<String?>(null)
    val onlineSearchStatus: StateFlow<String?> = _onlineSearchStatus

    private val _errorEvent = Channel<String>(Channel.BUFFERED)
    val errorEvent = _errorEvent.receiveAsFlow()

    private val _assistantRepairEvent = Channel<String>(Channel.BUFFERED)
    val assistantRepairEvent = _assistantRepairEvent.receiveAsFlow()

    private var streamingJob: Job? = null
    private var repairInFlightKey: String? = null
    private var lastAssistantRepairNotificationKey: String? = null

    init {
        viewModelScope.launch {
            combine(conversation, agents, defaultAgent) { conversation, agents, defaultAgent ->
                val resolution = resolveRepairResolution(agents, defaultAgent)
                Triple(conversation, agents, resolution)
            }.collect { (conversation, agents, resolution) ->
                when {
                    conversation != null -> repairConversationAssignmentIfNeeded(
                        conversation = conversation,
                        agents = agents,
                        resolution = resolution
                    )
                    draftAgentId.value != null && agents.none { it.id == draftAgentId.value } -> {
                        draftAgentId.value = resolution.fallbackAgent?.id
                        if (resolution.resolvedDefaultAgent == null && resolution.fallbackAgent != null) {
                            agentRepo.setActiveAgent(resolution.fallbackAgent.id)
                        }
                    }
                }
            }
        }
    }

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
        _isLoading.value = false
        _onlineSearchStatus.value = null
        _activeSession.value?.markStopped()
    }

    private suspend fun repairConversationAssignmentIfNeeded(
        conversation: ConversationEntity,
        agents: List<AgentConfig>,
        resolution: RepairResolution
    ) {
        val assignedAgent = agents.firstOrNull { it.id == conversation.agentId }
        when {
            assignedAgent != null -> {
                val syncKey = "sync:${conversation.id}:${assignedAgent.id}:${assignedAgent.name}"
                if (conversation.agentName != assignedAgent.name && repairInFlightKey != syncKey) {
                    repairInFlightKey = syncKey
                    try {
                        repo.upsertConversation(
                            conversation.copy(agentName = assignedAgent.name)
                        )
                    } finally {
                        repairInFlightKey = null
                    }
                }
            }
            resolution.fallbackAgent != null -> {
                val fallbackAgent = resolution.fallbackAgent
                val repairKey = "repair:${conversation.id}:${conversation.agentId}:${fallbackAgent.id}"
                if (repairInFlightKey == repairKey) return
                repairInFlightKey = repairKey
                try {
                    if (resolution.resolvedDefaultAgent == null) {
                        agentRepo.setActiveAgent(fallbackAgent.id)
                    }
                    repo.upsertConversation(
                        conversation.copy(
                            agentId = fallbackAgent.id,
                            agentName = fallbackAgent.name
                        )
                    )
                    if (lastAssistantRepairNotificationKey != repairKey) {
                        lastAssistantRepairNotificationKey = repairKey
                        _assistantRepairEvent.trySend(
                            "This chat's previous assistant is no longer available. Switched to ${fallbackAgent.name}."
                        )
                    }
                } finally {
                    repairInFlightKey = null
                }
            }
        }
    }

    private fun resolveRepairResolution(
        agents: List<AgentConfig>,
        defaultAgent: AgentConfig?
    ): RepairResolution {
        val resolvedDefaultAgent = defaultAgent?.takeIf { candidate ->
            agents.any { it.id == candidate.id }
        }
        return RepairResolution(
            resolvedDefaultAgent = resolvedDefaultAgent,
            fallbackAgent = resolvedDefaultAgent ?: agents.firstOrNull()
        )
    }

    fun sendMessage(
        text: String,
        images: List<ImageAttachment> = emptyList(),
        imageUri: String? = null,
        titleHint: String? = null,
        attachedFile: FileAttachmentContext? = null
    ) {
        val agent = activeAgent.value ?: return
        if ((text.isBlank() && images.isEmpty() && attachedFile == null) || _isLoading.value) return

        streamingJob = viewModelScope.launch {
            _isLoading.value = true
            _inputText.value = ""

            val isFirstMessage = messages.value.isEmpty()

            if (isFirstMessage) {
                val title = titleHint
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.take(60)
                    ?: text.trim().ifBlank {
                        when {
                            attachedFile != null -> attachedFile.displayName
                            images.isNotEmpty() -> "Image"
                            else -> "Chat"
                        }
                    }.take(60)
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
                    imageUri = imageUri,
                    attachedFileName = attachedFile?.displayName,
                    attachedFileText = attachedFile?.extractedText,
                    imagesJson = if (images.isNotEmpty()) Gson().toJson(images) else null
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
                    agentId = agent.id,
                    agentName = agent.name
                )
            )
            val session = AssistantStreamingSession(assistantId)
            session.start(this)
            _activeSession.value = session
            var accumulated = ""
            val streamingWriter = ThrottledStreamingMessageWriter { content, isStreaming ->
                repo.updateMessageContent(assistantId, content, isStreaming)
            }

            try {
                // If the agent doesn't support the attachment type, say so in the chat
                if (images.isNotEmpty() && !agent.canHandleImageRequests()) {
                    accumulated =
                        "I don't support image analysis with \"${agent.model}\". Please switch to a vision-capable model in agent settings."
                    return@launch
                }

                // Build history; the latest user message already has imagesJson stored in DB.
                val dbHistory = repo.getMessagesList(conversationId).filter { !it.isStreaming }
                val history = dbHistory.map { msg -> msg.toChatMessage() }
                val groundedHistory = webGateway.prepareTurn(
                    conversationKey = conversationId,
                    messages = history,
                    planningConfig = agent
                ) { status ->
                    _onlineSearchStatus.value = status
                }.messages

                assistantRuntime
                    .streamResponse(
                        conversationKey = conversationId,
                        messages = groundedHistory,
                        config = agent,
                        onStatusChanged = { status -> _onlineSearchStatus.value = status }
                    )
                    .catch { e -> if (currentCoroutineContext().isActive) emit(StreamChunk.Error(e)) }
                    .collect { chunk ->
                        when (chunk) {
                            is StreamChunk.Token -> {
                                accumulated += chunk.text
                                val sanitized = sanitizeGroundedAssistantResponse(accumulated)
                                session.onToken(sanitized)
                                streamingWriter.emitStreaming(sanitized)
                            }
                            is StreamChunk.ModelSelection -> {
                                repo.updateMessageResponseModel(
                                    assistantId,
                                    chunk.modelId,
                                    chunk.viaFallback
                                )
                            }
                            is StreamChunk.Done -> Unit
                            is StreamChunk.Error -> {
                                val errMsg = chunk.cause.message ?: "Unknown error"
                                accumulated = if (accumulated.isBlank()) {
                                    "[Error: $errMsg]"
                                } else {
                                    "$accumulated\n[Error: $errMsg]"
                                }
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
                    if (accumulated.isBlank()) {
                        session.markDeleted()
                        repo.deleteMessage(assistantId)
                    } else {
                        val sanitized = sanitizeGroundedAssistantResponse(accumulated)
                        streamingWriter.emitFinal(sanitized)
                        session.finalize(sanitized)
                    }
                    repo.touchConversation(conversationId)
                    // Guard: stopResponse() sets _isLoading=false immediately, which lets
                    // a new sendMessage() start before this finally block runs. Only clear
                    // loading state if this session is still the active one — otherwise
                    // we'd wipe out the new message's streaming overlay.
                    // Note: _activeSession is intentionally NOT nulled out here. Keeping the
                    // session alive (hidden=true or isStreaming=false) bridges the Room
                    // propagation lag so the empty bubble doesn't flash back. The next
                    // sendMessage() overwrites _activeSession with the new session.
                    if (_activeSession.value === session) {
                        _onlineSearchStatus.value = null
                        _isLoading.value = false
                    }
                }
            }
        }
    }

    class Factory(
        private val conversationId: String,
        private val repo: ConversationRepository,
        private val agentRepo: AgentRepository,
        private val assistantRuntime: ToolAwareAssistantRuntime,
        private val webGateway: WebGateway
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            ConversationDetailViewModel(
                conversationId,
                repo,
                agentRepo,
                assistantRuntime,
                webGateway
            ) as T
    }
}
