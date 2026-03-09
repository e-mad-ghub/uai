package com.example.uai.ui.navigation

object Routes {
    const val NEW_CONVERSATION = "new_conversation"
    const val CONVERSATION_DETAIL = "conversation/{conversationId}"
    const val AGENTS = "agents"
    const val AGENT_EDIT = "agent_edit?agentId={agentId}"
    const val SETTINGS = "settings"
    const val AGORA_LIST = "agora"
    const val AGORA_CREATE = "agora/create"
    const val AGORA_DETAIL = "agora/{agoraId}"

    fun conversationDetail(id: String) = "conversation/$id"
    fun agentEdit(agentId: String? = null) =
        if (agentId != null) "agent_edit?agentId=$agentId" else "agent_edit?agentId="
    fun agoraDetail(id: String) = "agora/$id"
}
