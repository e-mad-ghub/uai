package com.mad.screenagent.feature.bubble

import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.model.AgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubbleAgentResolverTest {

    @Test
    fun emptyAgents_returnsNull() {
        val resolved = resolveBubbleAgent(
            currentConversationId = null,
            currentConversation = null,
            agents = emptyList(),
            defaultAgent = null,
            draftAgentId = null,
            override = null
        )
        assertNull(resolved)
    }

    @Test
    fun conversationAgent_winsOverDraftAgent() {
        val agentA = AgentConfig(id = "a", name = "A")
        val agentB = AgentConfig(id = "b", name = "B")
        val conversation = ConversationEntity(
            id = "c1",
            title = "Chat",
            agentId = agentA.id,
            agentName = agentA.name,
            createdAt = 0L,
            updatedAt = 0L
        )

        val resolved = resolveBubbleAgent(
            currentConversationId = conversation.id,
            currentConversation = conversation,
            agents = listOf(agentA, agentB),
            defaultAgent = null,
            draftAgentId = agentB.id,
            override = null
        )
        assertEquals(agentA.id, resolved?.id)
    }

    @Test
    fun draftAgent_usedWhenNoConversation() {
        val agentA = AgentConfig(id = "a", name = "A")
        val agentB = AgentConfig(id = "b", name = "B")

        val resolved = resolveBubbleAgent(
            currentConversationId = null,
            currentConversation = null,
            agents = listOf(agentA, agentB),
            defaultAgent = null,
            draftAgentId = agentB.id,
            override = null
        )
        assertEquals(agentB.id, resolved?.id)
    }

    @Test
    fun override_appliesOnlyToMatchingConversation() {
        val agentA = AgentConfig(id = "a", name = "A")
        val agentB = AgentConfig(id = "b", name = "B")
        val conversation = ConversationEntity(
            id = "c1",
            title = "Chat",
            agentId = agentA.id,
            agentName = agentA.name,
            createdAt = 0L,
            updatedAt = 0L
        )

        val nonMatching = resolveBubbleAgent(
            currentConversationId = conversation.id,
            currentConversation = conversation,
            agents = listOf(agentA, agentB),
            defaultAgent = null,
            draftAgentId = null,
            override = BubbleAgentOverride(conversationId = "other", agentId = agentB.id)
        )
        assertEquals(agentA.id, nonMatching?.id)

        val matching = resolveBubbleAgent(
            currentConversationId = conversation.id,
            currentConversation = conversation,
            agents = listOf(agentA, agentB),
            defaultAgent = null,
            draftAgentId = null,
            override = BubbleAgentOverride(conversationId = conversation.id, agentId = agentB.id)
        )
        assertEquals(agentB.id, matching?.id)
    }

    @Test
    fun defaultAgent_usedWhenNoConversationOrDraft() {
        val agentA = AgentConfig(id = "a", name = "A")
        val agentB = AgentConfig(id = "b", name = "B")

        val resolved = resolveBubbleAgent(
            currentConversationId = null,
            currentConversation = null,
            agents = listOf(agentA, agentB),
            defaultAgent = agentB,
            draftAgentId = null,
            override = null
        )
        assertEquals(agentB.id, resolved?.id)
    }
}

