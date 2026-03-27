package com.mad.screenagent

import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.feature.bubble.updateConversationAgentSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MiniChatConversationSelectionTest {

    @Test
    fun updateConversationAgentSelection_updatesOnlyTargetConversation() {
        val currentAgent = AgentConfig(id = "agent-old", name = "Old Assistant")
        val replacementAgent = AgentConfig(id = "agent-new", name = "New Assistant")
        val conversations = listOf(
            ConversationEntity(
                id = "conversation-1",
                title = "One",
                agentId = currentAgent.id,
                agentName = currentAgent.name,
                createdAt = 1L,
                updatedAt = 1L
            ),
            ConversationEntity(
                id = "conversation-2",
                title = "Two",
                agentId = currentAgent.id,
                agentName = currentAgent.name,
                createdAt = 2L,
                updatedAt = 2L
            ),
            ConversationEntity(
                id = "agora-1",
                title = "Agora",
                agentId = currentAgent.id,
                agentName = currentAgent.name,
                createdAt = 3L,
                updatedAt = 3L,
                isAgora = true
            )
        )

        val updated = updateConversationAgentSelection(
            conversations = conversations,
            conversationId = "conversation-2",
            agent = replacementAgent
        )

        assertEquals(currentAgent.id, updated[0].agentId)
        assertEquals(currentAgent.name, updated[0].agentName)
        assertEquals(replacementAgent.id, updated[1].agentId)
        assertEquals(replacementAgent.name, updated[1].agentName)
        assertFalse(updated[2].agentId == replacementAgent.id)
    }
}
