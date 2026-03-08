package com.example.uai.ui.navigation

object Routes {
    const val CONVERSATIONS = "conversations"
    const val CONVERSATION_DETAIL = "conversation/{conversationId}"
    const val AGENTS = "agents"
    const val AGENT_EDIT = "agent_edit?agentId={agentId}"
    const val SETTINGS = "settings"

    fun conversationDetail(id: String) = "conversation/$id"
    fun agentEdit(agentId: String? = null) =
        if (agentId != null) "agent_edit?agentId=$agentId" else "agent_edit?agentId="
}
