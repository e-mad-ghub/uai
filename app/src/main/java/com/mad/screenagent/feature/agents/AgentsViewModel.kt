package com.mad.screenagent.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.repository.AgentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentsUiState(
    val agents: List<AgentConfig> = emptyList(),
    val activeAgentId: String? = null
)

class AgentsViewModel(private val repo: AgentRepository) : ViewModel() {

    val uiState: StateFlow<AgentsUiState> = combine(
        repo.agentsFlow,
        repo.activeAgentFlow
    ) { agents, activeAgent ->
        AgentsUiState(agents = agents, activeAgentId = activeAgent?.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentsUiState())

    fun setActiveAgent(id: String) {
        viewModelScope.launch { repo.setActiveAgent(id) }
    }

    fun reorderAgents(agents: List<AgentConfig>) {
        viewModelScope.launch { repo.saveAgentList(agents) }
    }

    fun deleteAgent(agent: AgentConfig) {
        viewModelScope.launch {
            val currentState = uiState.value
            val remainingAgents = currentState.agents.filterNot { it.id == agent.id }
            val replacementActiveAgentId = activeAgentIdAfterDeletingAgent(
                agentsBeforeDelete = currentState.agents,
                activeAgentId = currentState.activeAgentId,
                deletedAgentId = agent.id
            )
            repo.saveAgentList(remainingAgents)
            if (currentState.activeAgentId == agent.id) {
                repo.setActiveAgent(replacementActiveAgentId)
            }
        }
    }

    class Factory(private val repo: AgentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = AgentsViewModel(repo) as T
    }
}

internal fun activeAgentIdAfterDeletingAgent(
    agentsBeforeDelete: List<AgentConfig>,
    activeAgentId: String?,
    deletedAgentId: String
): String? {
    if (activeAgentId != deletedAgentId) return activeAgentId
    return agentsBeforeDelete.firstOrNull { it.id != deletedAgentId }?.id
}
