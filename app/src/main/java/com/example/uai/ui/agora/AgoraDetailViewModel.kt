package com.example.uai.ui.agora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.ai.ChatMessage
import com.example.uai.ai.FileAttachmentContext
import com.example.uai.ai.ImageAttachment
import com.example.uai.ai.StreamChunk
import com.example.uai.ai.ThrottledStreamingMessageWriter
import com.example.uai.ai.ToolAwareAssistantRuntime
import com.example.uai.ai.WebGateway
import com.example.uai.ai.sanitizeGroundedAssistantResponse
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.db.toChatMessage
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.repository.AgentRepository
import com.example.uai.data.repository.ConversationRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
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
        .mapNotNull { name ->
            Regex("(?i)@${Regex.escape(name)}").find(text)?.let { match ->
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
    private val webGateway: WebGateway
) : ViewModel() {

    val conversation = repo.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages = repo.getMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }

    /** Update room name and/or agent list. Either can be blank/empty to leave unchanged. */
    fun updateRoom(name: String, agentIds: Set<String>) {
        if (name.isBlank() || agentIds.isEmpty()) return
        viewModelScope.launch {
            val conv = conversation.value ?: return@launch
            repo.upsertConversation(
                conv.copy(
                    title = if (name.isNotBlank()) name.trim() else conv.title,
                    agoraAgentIds = Gson().toJson(agentIds.toList())
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

            try {
                val allCurrentAgents = agentRepo.agentsFlow.first()
                val allAgents: List<AgentConfig> = conv.parseAgoraAgentIds()
                    .mapNotNull { id -> allCurrentAgents.find { it.id == id } }

                // Guard before committing the user's turn so the room never fills with
                // orphan messages when no participants are available to respond.
                if (allAgents.isEmpty()) {
                    _errorEvent.trySend(
                        "No agents in this council room. Tap the settings icon to add agents."
                    )
                    return@launch
                }

                val targetedAgents: List<AgentConfig>? = when {
                    replyToMessage != null -> {
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
                val currentRoundOthers = mutableListOf<MessageEntity>()
                val agentOtherNames = allAgents.associate { a ->
                    a.id to allAgents.filter { it.id != a.id }.map { it.name }
                }

                for (agent in agents) {
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
                        currentRoundOthers.add(
                            MessageEntity(
                                id = assistantId, conversationId = conversationId,
                                role = "assistant", content = unsupportedMsg,
                                createdAt = System.currentTimeMillis(),
                                agentId = agent.id,
                                agentName = agent.name
                            )
                        )
                        continue
                    }

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
                            // Other agents from historical rounds + already-responded agents this round
                            val otherAll = assistants.filter { it.agentName != agent.name } +
                                if (isLastRound) currentRoundOthers else emptyList()

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
                    }
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
                    val groundedHistory = webGateway.prepareTurn(
                        conversationKey = conversationId,
                        messages = groundingSeedHistory,
                        planningConfig = agent,
                        onStatusChanged = { status -> _onlineSearchStatus.value = status }
                    ).grounding?.let { grounding ->
                        webGateway.applyGrounding(history, grounding)
                    } ?: history

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
                        if (replyToMessage != null) {
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
                    val agoraAgent = agent.copy(
                        systemPrompt = "$nameContext\n\n${agent.systemPrompt}".trim()
                    )

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

                    var accumulated = ""
                    val streamingWriter = ThrottledStreamingMessageWriter { content, isStreaming ->
                        repo.updateMessageContent(assistantId, content, isStreaming)
                    }
                    try {
                        assistantRuntime
                            .streamResponse(
                                conversationKey = conversationId,
                                messages = groundedHistory,
                                config = agoraAgent,
                                onStatusChanged = { status -> _onlineSearchStatus.value = status }
                            )
                            .catch { e -> emit(StreamChunk.Error(e)) }
                            .collect { chunk ->
                                when (chunk) {
                                    is StreamChunk.Token -> {
                                        accumulated = (accumulated + chunk.text).stripNamePrefix()
                                        streamingWriter.emitStreaming(
                                            sanitizeGroundedAssistantResponse(accumulated)
                                        )
                                    }
                                    is StreamChunk.ModelSelection -> {
                                        repo.updateMessageResponseModel(
                                            assistantId,
                                            chunk.modelId,
                                            chunk.viaFallback
                                        )
                                    }
                                    is StreamChunk.Done -> {}
                                    is StreamChunk.Error -> {
                                        val errMsg = chunk.cause.message ?: "Unknown error"
                                        accumulated = "$accumulated\n[Error: $errMsg]"
                                        _errorEvent.trySend(
                                            "\"${agent.name}\" failed: $errMsg\n\nThis agent's model may not support this request."
                                        )
                                    }
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        accumulated = "[Error: ${e.message}]"
                        _errorEvent.trySend(
                            "\"${agent.name}\" failed: ${e.message}\n\nThis agent's model may not support this request."
                        )
                    } finally {
                        withContext(NonCancellable) {
                            if (accumulated.isBlank()) repo.deleteMessage(assistantId)
                            else {
                                val sanitized = sanitizeGroundedAssistantResponse(accumulated)
                                streamingWriter.emitFinal(sanitized)
                                accumulated = sanitized
                            }
                        }
                    }

                    if (accumulated.isNotBlank()) {
                        currentRoundOthers.add(
                            MessageEntity(
                                id = assistantId,
                                conversationId = conversationId,
                                role = "assistant",
                                content = accumulated,
                                createdAt = System.currentTimeMillis(),
                                agentId = agent.id,
                                agentName = agent.name
                            )
                        )
                    }
                }

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
        private val webGateway: WebGateway
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            AgoraDetailViewModel(
                conversationId,
                repo,
                agentRepo,
                assistantRuntime,
                webGateway
            ) as T
    }
}
