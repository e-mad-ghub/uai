package com.example.uai.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.repository.AgentRepository
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

    fun deleteAgent(agent: AgentConfig) {
        viewModelScope.launch {
            val current = uiState.value.agents.toMutableList()
            current.removeIf { it.id == agent.id }
            repo.saveAgentList(current)
            if (uiState.value.activeAgentId == agent.id) {
                repo.setActiveAgent(current.firstOrNull()?.id)
            }
        }
    }

    class Factory(private val repo: AgentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = AgentsViewModel(repo) as T
    }
}
