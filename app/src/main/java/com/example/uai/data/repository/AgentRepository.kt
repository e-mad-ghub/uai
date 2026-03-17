package com.example.uai.data.repository

import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AppColorTheme
import com.example.uai.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentRepository(private val prefs: AppPreferences) {

    private val tokenUsageMutex = Mutex()

    val agentsFlow: Flow<List<AgentConfig>> = prefs.agentListFlow

    val activeAgentFlow: Flow<AgentConfig?> = combine(
        prefs.agentListFlow,
        prefs.activeAgentIdFlow
    ) { agents, activeId ->
        agents.firstOrNull { it.id == activeId }
    }

    suspend fun saveAgent(agent: AgentConfig) {
        val current = prefs.agentListFlow.first().toMutableList()
        val idx = current.indexOfFirst { it.id == agent.id }
        if (idx >= 0) current[idx] = agent else current.add(agent)
        prefs.saveAgentList(current)
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

    /** Add [tokens] to [agentId]'s monthly usage, auto-resetting on month change. */
    suspend fun addTokenUsage(agentId: String, tokens: Long) {
        if (tokens <= 0L) return
        tokenUsageMutex.withLock {
            val currentMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
            val current = prefs.agentListFlow.first().toMutableList()
            val idx = current.indexOfFirst { it.id == agentId }
            if (idx < 0) return@withLock
            val agent = current[idx]
            val prevUsed = if (agent.tokenUsedMonth == currentMonth) agent.tokenUsed else 0L
            current[idx] = agent.copy(
                tokenUsed = prevUsed + tokens,
                tokenUsedMonth = currentMonth
            )
            prefs.saveAgentList(current)
        }
    }

    /** Reset token usage counter for [agentId] to zero. */
    suspend fun resetTokenUsage(agentId: String) {
        tokenUsageMutex.withLock {
            val current = prefs.agentListFlow.first().toMutableList()
            val idx = current.indexOfFirst { it.id == agentId }
            if (idx < 0) return@withLock
            current[idx] = current[idx].copy(tokenUsed = 0L, tokenUsedMonth = "")
            prefs.saveAgentList(current)
        }
    }
}
