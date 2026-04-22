package com.mad.screenagent.feature.bubble

import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.model.AgentConfig

internal data class BubbleAgentOverride(
    val conversationId: String?,
    val agentId: String
)

internal fun resolveBubbleAgent(
    currentConversationId: String?,
    currentConversation: ConversationEntity?,
    agents: List<AgentConfig>,
    defaultAgent: AgentConfig?,
    draftAgentId: String?,
    override: BubbleAgentOverride?
): AgentConfig? {
    val resolvedDefaultAgent = defaultAgent?.takeIf { candidate ->
        agents.any { it.id == candidate.id }
    }
    val fallbackAgent = resolvedDefaultAgent ?: agents.firstOrNull()

    val overrideAgent = override
        ?.takeIf { it.conversationId == currentConversationId }
        ?.let { o -> agents.firstOrNull { it.id == o.agentId } }

    if (overrideAgent != null) return overrideAgent

    if (currentConversation != null) {
        return agents.firstOrNull { it.id == currentConversation.agentId } ?: fallbackAgent
    }

    if (draftAgentId != null) {
        return agents.firstOrNull { it.id == draftAgentId } ?: fallbackAgent
    }

    return fallbackAgent
}

