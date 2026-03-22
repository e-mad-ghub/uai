package com.mad.screenagent.feature.agora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.repository.AgentRepository
import com.mad.screenagent.data.repository.ConversationRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class AgoraCreateViewModel(
    private val repo: ConversationRepository,
    private val agentRepo: AgentRepository
) : ViewModel() {

    val agents: StateFlow<List<AgentConfig>> = agentRepo.agentsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val roomName = MutableStateFlow("")
    val selectedAgentIds = MutableStateFlow<Set<String>>(emptySet())

    private val _createdId = MutableStateFlow<String?>(null)
    val createdId: StateFlow<String?> = _createdId

    fun setName(name: String) { roomName.value = name }

    fun toggleAgent(id: String) {
        selectedAgentIds.value = selectedAgentIds.value.toMutableSet().apply {
            if (contains(id)) remove(id) else add(id)
        }
    }

    val canCreate: StateFlow<Boolean> = combine(roomName, selectedAgentIds) { name, ids ->
        name.isNotBlank() && ids.size >= 2
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun create() {
        if (!canCreate.value) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            repo.upsertConversation(
                ConversationEntity(
                    id = id,
                    title = roomName.value.trim(),
                    agentId = "",
                    agentName = "Agora",
                    createdAt = now,
                    updatedAt = now,
                    isAgora = true,
                    agoraAgentIds = Gson().toJson(selectedAgentIds.value.toList())
                )
            )
            _createdId.value = id
        }
    }

    class Factory(
        private val repo: ConversationRepository,
        private val agentRepo: AgentRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            AgoraCreateViewModel(repo, agentRepo) as T
    }
}
