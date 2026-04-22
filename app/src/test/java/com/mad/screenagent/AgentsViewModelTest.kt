package com.mad.screenagent

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.feature.agents.activeAgentIdAfterDeletingAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentsViewModelTest {

    @Test
    fun activeAgentIdAfterDeletingAgent_selectsFirstRemainingWhenActiveDeleted() {
        val active = AgentConfig(id = "agent-a", name = "A")
        val replacement = AgentConfig(id = "agent-b", name = "B")

        val result = activeAgentIdAfterDeletingAgent(
            agentsBeforeDelete = listOf(active, replacement),
            activeAgentId = active.id,
            deletedAgentId = active.id
        )

        assertEquals(replacement.id, result)
    }

    @Test
    fun activeAgentIdAfterDeletingAgent_clearsActiveWhenOnlyActiveDeleted() {
        val active = AgentConfig(id = "agent-a", name = "A")

        val result = activeAgentIdAfterDeletingAgent(
            agentsBeforeDelete = listOf(active),
            activeAgentId = active.id,
            deletedAgentId = active.id
        )

        assertNull(result)
    }

    @Test
    fun activeAgentIdAfterDeletingAgent_keepsActiveWhenInactiveDeleted() {
        val active = AgentConfig(id = "agent-a", name = "A")
        val deleted = AgentConfig(id = "agent-b", name = "B")

        val result = activeAgentIdAfterDeletingAgent(
            agentsBeforeDelete = listOf(active, deleted),
            activeAgentId = active.id,
            deletedAgentId = deleted.id
        )

        assertEquals(active.id, result)
    }
}
