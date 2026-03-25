package com.mad.screenagent.ui.navigation

object Routes {
    const val NEW_CONVERSATION = "new_conversation"
    const val CONVERSATION_DETAIL = "conversation/{conversationId}"
    const val AGENTS = "agents"
    const val AGENT_EDIT = "agent_edit?agentId={agentId}&duplicateFromId={duplicateFromId}"
    const val QUICK_ACTIONS = "quick_actions"
    const val SETTINGS = "settings"
    const val AGORA_LIST = "agora"
    const val AGORA_CREATE = "agora/create"
    const val AGORA_DETAIL = "agora/{agoraId}"

    fun conversationDetail(id: String) = "conversation/$id"
    fun agentEdit(agentId: String? = null, duplicateFromId: String? = null) =
        "agent_edit?agentId=${agentId.orEmpty()}&duplicateFromId=${duplicateFromId.orEmpty()}"
    fun agoraDetail(id: String) = "agora/$id"
}
