package com.example.uai.feature.agora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.shared.streaming.AiProvider
import com.example.uai.shared.streaming.AssistantStreamingSession
import com.example.uai.shared.streaming.ChatMessage
import com.example.uai.shared.streaming.FileAttachmentContext
import com.example.uai.shared.streaming.ImageAttachment
import com.example.uai.shared.streaming.StreamChunk
import com.example.uai.shared.streaming.ThrottledStreamingMessageWriter
import com.example.uai.shared.streaming.ToolAwareAssistantRuntime
import com.example.uai.shared.streaming.WebGateway
import com.example.uai.shared.streaming.compressHistory
import com.example.uai.shared.streaming.sanitizeGroundedAssistantResponse
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.db.toChatMessage
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.model.isSideAgentManagedOpenRouterFreeRoute
import com.example.uai.data.repository.AgentRepository
import com.example.uai.data.repository.ConversationRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal fun extractAgentScopedGroundingText(
    text: String,
    agentName: String,
    allAgentNames: List<String>
): String {
    val mentions = allAgentNames
        .distinct()
        .flatMap { name ->
            Regex("(?i)@${Regex.escape(name)}").findAll(text).map { match ->
                Triple(name, match.range.first, match.range.last + 1)
            }
        }
        .sortedBy { it.second }

    if (mentions.size < 2) return text

    val target = mentions.firstOrNull { it.first.equals(agentName, ignoreCase = true) } ?: return text
    val nextMentionStart = mentions.firstOrNull { it.second > target.second }?.second ?: text.length
    val scoped = text.substring(target.third, nextMentionStart)
        .trim(' ', '\n', '\t', ',', ';', ':')

    return scoped.ifBlank { text }
}

class AgoraDetailViewModel(
    val conversationId: String,
    private val repo: ConversationRepository,
    private val agentRepo: AgentRepository,
    private val assistantRuntime: ToolAwareAssistantRuntime,
    private val webGateway: WebGateway,
    private val providerFactory: (AgentConfig) -> AiProvider,
    private val agentResolver: suspend (AgentConfig) -> AgentConfig = { it }
) : ViewModel() {

    private val gson = Gson()

    val conversation = repo.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Flat map of messageId → session State, updated directly by each agent's session collector.
    // This avoids the fragile flatMapLatest+combine(List<Flow>) pattern that caused Agora
    // to show only one reply when multiple agents responded in parallel.
    private val _sessionStates = MutableStateFlow<Map<String, AssistantStreamingSession.State>>(emptyMap())
    // Keyed by assistantId; used only for stopResponse() to call markStopped() on each session.
    private val activeSessions = mutableMapOf<String, AssistantStreamingSession>()

    val messages: StateFlow<List<MessageEntity>> = combine(
        repo.getMessages(conversationId),
        _sessionStates
    ) { dbMessages, sessionStates ->
        if (sessionStates.isEmpty()) return@combine dbMessages
        dbMessages.mapNotNull { msg ->
            val state = sessionStates[msg.id] ?: return@mapNotNull msg
            when {
                state.hidden -> null
                else -> msg.copy(content = state.content, isStreaming = state.isStreaming)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** All agents configured in the app — used by the room-edit bottom sheet. */
    val allAvailableAgents: StateFlow<List<AgentConfig>> = agentRepo.agentsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Names of agents assigned to this room (for top bar subtitle). */
    val participantNames: StateFlow<List<String>> = combine(
        conversation,
        agentRepo.agentsFlow
    ) { conv, agents ->
        if (conv == null) return@combine emptyList()
        conv.parseAgoraAgentIds().mapNotNull { id -> agents.find { it.id == id }?.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _onlineSearchStatus = MutableStateFlow<String?>(null)
    val onlineSearchStatus: StateFlow<String?> = _onlineSearchStatus

    private val _errorEvent = Channel<String>(Channel.BUFFERED)
    val errorEvent = _errorEvent.receiveAsFlow()

    private var streamingJob: Job? = null

    fun onInputChange(text: String) { _inputText.value = text }

    fun stopResponse() {
        streamingJob?.cancel()
        streamingJob = null
        _isLoading.value = false
        _onlineSearchStatus.value = null
        activeSessions.values.forEach { it.markStopped() }
        // The state collector coroutines are children of the per-agent coroutines and are
        // cancelled along with streamingJob, so markStopped()'s isStreaming=false update may
        // never reach _sessionStates. Clear the streaming flag directly so typing indicators
        // disappear immediately rather than waiting for the async finally blocks to finish.
        _sessionStates.update { states ->
            states.mapValues { (_, state) ->
                // Also hide entries with no content — avoids a ~2s empty bubble flash
                // while the NonCancellable finally blocks run the DB cleanup.
                if (state.content.isBlank()) state.copy(isStreaming = false, hidden = true)
                else state.copy(isStreaming = false)
            }
        }
    }

    /** Update room name and/or agent list. Either can be blank/empty to leave unchanged. */
    fun updateRoom(name: String, agentIds: Set<String>) {
        if (name.isBlank()) {
            _errorEvent.trySend("Room name cannot be empty.")
            return
        }
        if (agentIds.isEmpty()) {
            _errorEvent.trySend("An Agora room must have at least one agent.")
            return
        }
        viewModelScope.launch {
            val conv = conversation.value ?: return@launch
            repo.upsertConversation(
                conv.copy(
                    title = name.trim(),
                    agoraAgentIds = gson.toJson(agentIds.toList())
                )
            )
        }
    }

    fun sendMessage(
        text: String,
        imageBase64: String? = null,
        imageUri: String? = null,
        replyToMessage: MessageEntity? = null,
        attachedFile: FileAttachmentContext? = null
    ) {
        val conv = conversation.value ?: return
        if ((text.isBlank() && imageBase64 == null && attachedFile == null) || _isLoading.value) return

        streamingJob = viewModelScope.launch {
            _isLoading.value = true
            _inputText.value = ""
            // Clear stale overlay entries left from the previous round (entries are intentionally
            // kept alive until here so the final isStreaming=false state bridges any Room lag).
            _sessionStates.value = emptyMap()

            try {
                val allCurrentAgents = agentRepo.agentsFlow.first()
                val allAgents: List<AgentConfig> = conv.parseAgoraAgentIds()
                    .mapNotNull { id -> allCurrentAgents.find { it.id == id } }

                // Guard before committing the user's turn so the room never fills with
                // orphan messages when no participants are available to respond.
                if (allAgents.isEmpty()) {
                    _errorEvent.trySend(
                        "No agents in this Agora room. Tap the settings icon to add agents."
                    )
                    return@launch
                }

                val targetedAgents: List<AgentConfig>? = when {
                    replyToMessage?.role == "assistant" -> {
                        val target = when {
                            !replyToMessage.agentId.isNullOrBlank() ->
                                allAgents.find { it.id == replyToMessage.agentId }

                            !replyToMessage.agentName.isNullOrBlank() -> {
                                val nameMatches = allAgents.filter { it.name == replyToMessage.agentName }
                                nameMatches.singleOrNull()
                            }

                            else -> null
                        }
                        if (target == null) {
                            _errorEvent.trySend(
                                if (!replyToMessage.agentId.isNullOrBlank()) {
                                    "The assistant you replied to is no longer available in this room."
                                } else {
                                    "This older reply no longer maps to a unique assistant in this room. Reply to a newer message or use @Name."
                                }
                            )
                            return@launch
                        }
                        listOf(target)
                    }
                    else -> null
                }

                // Insert user message
                repo.insertMessage(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "user",
                        content = text,
                        createdAt = System.currentTimeMillis(),
                        imageUri = imageUri,
                        attachedFileName = attachedFile?.displayName,
                        attachedFileText = attachedFile?.extractedText
                    )
                )

                // Routing priority:
                // 1. Reply to a specific agent → only that agent responds.
                // 2. @mention(s) in text → only mentioned agents respond.
                // 3. No targeting → all agents respond.
                val agents: List<AgentConfig> = when {
                    targetedAgents != null -> targetedAgents
                    else -> {
                        val mentioned = allAgents.filter { agent ->
                            text.contains("@${agent.name}", ignoreCase = true)
                        }
                        if (mentioned.isEmpty()) allAgents else mentioned
                    }
                }

                // Precompute history and per-agent context once.
                val dbHistory = repo.getMessagesList(conversationId).filter { !it.isStreaming }
                val lastUserIndex = dbHistory.indexOfLast { it.role == "user" }
                val historyMessages = if (lastUserIndex >= 0) dbHistory.subList(0, lastUserIndex + 1) else emptyList()
                val agentOtherNames = allAgents.associate { a ->
                    a.id to allAgents.filter { it.id != a.id }.map { it.name }
                }

                // Agents respond in parallel — current-round cross-agent context is not available
                // within the same round; each agent sees only prior-round history from other agents.
                coroutineScope {
                for (agent in agents) { launch {
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

                    // If this agent can't handle the attachment, say so and skip the API call
                    val unsupportedMsg: String? = when {
                        imageBase64 != null && !agent.canHandleImageRequests() ->
                            "I don't support image analysis with \"${agent.model}\"."
                        else -> null
                    }
                    if (unsupportedMsg != null) {
                        repo.updateMessageContent(assistantId, unsupportedMsg, false)
                        return@launch
                    }

                    // Create the session BEFORE any network calls (history build + web search).
                    // This ensures _sessionStates is populated immediately so stopResponse()
                    // can call markStopped(), and the finally block always cleans up the DB
                    // placeholder even if the coroutine is cancelled during prepareTurn().
                    val session = AssistantStreamingSession(assistantId)
                    session.start(this)
                    activeSessions[assistantId] = session
                    // Collect this session's state changes into the shared flat map so the UI
                    // sees all agents' states simultaneously without flatMapLatest restarts.
                    // The Job reference is cancelled in the finally block — StateFlow.collect
                    // never terminates on its own, so without an explicit cancel the per-agent
                    // coroutine would hang waiting for this child and coroutineScope{} would
                    // never return, keeping _isLoading=true indefinitely.
                    val stateCollectorJob = launch {
                        session.state.collect { state ->
                            _sessionStates.update { it + (assistantId to state) }
                        }
                    }
                    var accumulated = ""
                    val streamingWriter = ThrottledStreamingMessageWriter { content, isStreaming ->
                        repo.updateMessageContent(assistantId, content, isStreaming)
                    }
                    try {
                        // Option A history: group into rounds so each agent's "assistant" slot
                        // contains only its own words. Other agents' turns are appended to the
                        // preceding user message as "[Name said]: …" context. This eliminates
                        // the cross-agent role confusion caused by merged assistant buffers.
                        val history = buildList<ChatMessage> {
                            val rounds = mutableListOf<Pair<MessageEntity, List<MessageEntity>>>()
                            var pendingUser: MessageEntity? = null
                            val pendingAssistants = mutableListOf<MessageEntity>()
                            for (msg in historyMessages) {
                                if (msg.role == "user") {
                                    pendingUser?.let { rounds.add(it to pendingAssistants.toList()) }
                                    pendingAssistants.clear()
                                    pendingUser = msg
                                } else {
                                    pendingAssistants.add(msg)
                                }
                            }
                            pendingUser?.let { rounds.add(it to pendingAssistants.toList()) }

                            rounds.forEachIndexed { roundIdx, (userMsg, assistants) ->
                                val isLastRound = roundIdx == rounds.lastIndex
                                // Other agents from historical rounds (current-round agents run in parallel)
                                val otherAll = assistants.filter { it.agentName != agent.name }

                                val contextSuffix = if (otherAll.isNotEmpty())
                                    "\n\n[Other participants in this round — read-only context, do not continue:]\n" +
                                    otherAll.joinToString("\n") { "${it.agentName}: ${it.content}" } +
                                    "\n[End of other participants' context]"
                                else ""

                                val userText = userMsg.content + contextSuffix
                                when {
                                    isLastRound && imageBase64 != null ->
                                        add(
                                            userMsg.toChatMessage(
                                                contentOverride = userText,
                                                images = listOf(ImageAttachment(imageBase64))
                                            )
                                        )
                                    else ->
                                        add(userMsg.toChatMessage(contentOverride = userText))
                                }

                                // This agent's own past response (absent on the last round — generating now)
                                assistants.find { it.agentId == agent.id }
                                    ?: assistants.find { it.agentName == agent.name }
                                    ?.let { add(it.toChatMessage()) }
                            }
                        }.let { compressHistory(it) }
                        val scopedGroundingText = extractAgentScopedGroundingText(
                            text = text,
                            agentName = agent.name,
                            allAgentNames = agents.map { it.name }
                        )
                        val groundingSeedHistory = history.mapIndexed { index, message ->
                            if (index == history.lastIndex && message.role == "user") {
                                message.copy(content = scopedGroundingText)
                            } else {
                                message
                            }
                        }
                        val groundedHistory = if (isSideAgentManagedOpenRouterFreeRoute(agent.model)) {
                            webGateway.prepareTurn(
                                conversationKey = conversationId,
                                messages = groundingSeedHistory,
                                planningConfig = agent,
                                onStatusChanged = { status -> _onlineSearchStatus.value = status }
                            ).grounding?.let { grounding ->
                                webGateway.applyGrounding(history, grounding)
                            } ?: history
                        } else {
                            history
                        }

                        val otherNames = agentOtherNames[agent.id] ?: emptyList()
                        val nameContext = buildString {
                            append("You are participating in a group discussion. ")
                            append("Your name is \"${agent.name}\". ")
                            if (otherNames.isNotEmpty()) {
                                append("The other participants are: ${otherNames.joinToString(", ")}. ")
                                append(
                                    "IMPORTANT: You may only speak as yourself. " +
                                    "Never write what ${otherNames.joinToString(" or ")} would say. " +
                                    "Do not simulate, quote, roleplay, or predict any other participant. " +
                                    "Do NOT describe, announce, or narrate what another participant will do — " +
                                    "for example, do not write phrases like 'the Professor will now...' or '${otherNames.first()} will add...'. " +
                                    "If asked to play a game or task alongside others, only perform your own assigned action, then stop immediately. "
                                )
                            }
                            // Only inject reply context when this specific agent was targeted
                            if (replyToMessage != null && targetedAgents != null) {
                                append(
                                    "The user is replying directly to your previous message: " +
                                    "\"${replyToMessage.content.take(300)}\". " +
                                    "Focus your response as a follow-up to that message. "
                                )
                            }
                            append(
                                "You have been selected to respond. " +
                                "Write your reply directly — no name tag, no prefix, no label of any kind. " +
                                "CRITICAL: The conversation history contains context blocks like '[Name said]: ...' showing what other participants said. " +
                                "Do NOT reproduce, continue, or append these blocks. They are read-only context. " +
                                "Your reply ends when you finish your own thought. Stop there. "
                            )
                        }
                        val agoraAgent = agentResolver(agent.copy(
                            systemPrompt = "$nameContext\n\n${agent.systemPrompt}".trim()
                        ))

                        fun String.stripNamePrefix(): String {
                            // Strip any [Name]: or [Name said]: prefix at the start
                            var s = replace(Regex("^\\[.+?(?:\\s+said)?\\]:\\s*"), "")
                            // Truncate at any other participant's marker mid-response
                            for (name in otherNames) {
                                for (marker in listOf("[${name}]:", "[${name} said]:")) {
                                    val idx = s.indexOf(marker)
                                    if (idx >= 0) s = s.substring(0, idx).trimEnd()
                                }
                            }
                            return s
                        }

                        val responseFlow = if (isSideAgentManagedOpenRouterFreeRoute(agent.model)) {
                            assistantRuntime.streamResponse(
                                conversationKey = conversationId,
                                messages = groundedHistory,
                                config = agoraAgent,
                                onStatusChanged = { status -> _onlineSearchStatus.value = status }
                            )
                        } else {
                            providerFactory(agoraAgent).streamResponse(groundedHistory, agoraAgent)
                        }
                        responseFlow
                            .catch { e -> if (currentCoroutineContext().isActive) emit(StreamChunk.Error(e)) }
                            .collect { chunk ->
                                when (chunk) {
                                    is StreamChunk.Token -> {
                                        accumulated = (accumulated + chunk.text).stripNamePrefix()
                                        val sanitized = if (isSideAgentManagedOpenRouterFreeRoute(agent.model)) sanitizeGroundedAssistantResponse(accumulated) else accumulated
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
                                    is StreamChunk.Usage ->
                                        agentRepo.addTokenUsage(
                                            agent.id,
                                            (chunk.inputTokens + chunk.outputTokens).toLong()
                                        )
                                    is StreamChunk.Done -> {}
                                    is StreamChunk.Error -> {
                                        val errMsg = chunk.cause.message ?: "Unknown error"
                                        _errorEvent.trySend(
                                            "\"${agent.name}\" failed: $errMsg\n\nThis agent's model may not support this request."
                                        )
                                    }
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _errorEvent.trySend(
                            "\"${agent.name}\" failed: ${e.message}\n\nThis agent's model may not support this request."
                        )
                    } finally {
                        withContext(NonCancellable) {
                            // Cancel the state collector first so it stops feeding _sessionStates.
                            stateCollectorJob.cancel()
                            if (accumulated.isBlank()) {
                                // Suppress the placeholder immediately via hidden=true before Room
                                // propagates the deletion — avoids a flash of isStreaming=true.
                                _sessionStates.update { states ->
                                    val cur = states[assistantId]
                                    if (cur != null) states + (assistantId to cur.copy(isStreaming = false, hidden = true))
                                    else states
                                }
                                session.markDeleted()
                                repo.deleteMessage(assistantId)
                            } else {
                                val sanitized = if (isSideAgentManagedOpenRouterFreeRoute(agent.model)) sanitizeGroundedAssistantResponse(accumulated) else accumulated
                                streamingWriter.emitFinal(sanitized)
                                session.finalize(sanitized)
                                // Lock the overlay entry to isStreaming=false before Room catches up.
                                // Do NOT remove the entry here — MutableStateFlow conflates rapid
                                // updates, so set-then-remove may be seen as just "remove", causing
                                // the stale DB message (still isStreaming=true) to flash back.
                                // Stale entries are cleared at the start of the next sendMessage().
                                _sessionStates.update { states ->
                                    val cur = states[assistantId]
                                    if (cur != null) states + (assistantId to cur.copy(content = sanitized, isStreaming = false))
                                    else states
                                }
                            }
                            activeSessions.remove(assistantId)
                        }
                    }

                } } } // end launch, for, coroutineScope

                repo.touchConversation(conversationId)
            } finally {
                withContext(NonCancellable) {
                    _onlineSearchStatus.value = null
                    _isLoading.value = false
                }
            }
        }
    }

    class Factory(
        private val conversationId: String,
        private val repo: ConversationRepository,
        private val agentRepo: AgentRepository,
        private val assistantRuntime: ToolAwareAssistantRuntime,
        private val webGateway: WebGateway,
        private val providerFactory: (AgentConfig) -> AiProvider,
        private val agentResolver: suspend (AgentConfig) -> AgentConfig = { it }
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            AgoraDetailViewModel(
                conversationId,
                repo,
                agentRepo,
                assistantRuntime,
                webGateway,
                providerFactory,
                agentResolver
            ) as T
    }
}
