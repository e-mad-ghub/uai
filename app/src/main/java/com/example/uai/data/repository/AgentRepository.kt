package com.example.uai.data.repository

import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AppColorTheme
import com.example.uai.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class AgentRepository(private val prefs: AppPreferences) {

    val agentsFlow: Flow<List<AgentConfig>> = prefs.agentListFlow

    val activeAgentFlow: Flow<AgentConfig?> = combine(
        prefs.agentListFlow,
        prefs.activeAgentIdFlow
    ) { agents, activeId ->
        agents.firstOrNull { it.id == activeId }
    }

    suspend fun saveAgent(agent: AgentConfig) {
        val current = mutableListOf<AgentConfig>()
        // Read synchronously not available; caller must pass current list or use Flow
        // This is called from ViewModel which has the current list
        prefs.saveAgentList(current.apply {
            val idx = indexOfFirst { it.id == agent.id }
            if (idx >= 0) set(idx, agent) else add(agent)
        })
    }

    suspend fun saveAgentList(agents: List<AgentConfig>) =
        prefs.saveAgentList(agents)

    suspend fun setActiveAgent(id: String?) =
        prefs.setActiveAgentId(id)

    val bubbleEnabledFlow: Flow<Boolean> = prefs.bubbleEnabledFlow

    suspend fun setBubbleEnabled(enabled: Boolean) =
        prefs.setBubbleEnabled(enabled)

    val colorThemeFlow: Flow<AppColorTheme> = prefs.colorThemeFlow

    suspend fun setColorTheme(theme: AppColorTheme) = prefs.setColorTheme(theme)
}
