package com.mad.screenagent

import com.mad.screenagent.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [ConversationEntity.parseAgoraAgentIds].
 *
 * Feature: conversations
 * Risk: If the JSON serialization format of agentIds ever changes these tests
 * will catch the mismatch before data is silently lost.
 */
class ConversationEntityTest {

    private fun entity(agoraAgentIds: String) = ConversationEntity(
        id = "test-id",
        title = "Test",
        agentId = "agent-1",
        agentName = "Agent",
        createdAt = 0L,
        updatedAt = 0L,
        isAgora = true,
        agoraAgentIds = agoraAgentIds
    )

    @Test
    fun parseAgoraAgentIds_returnsEmptyListForBlankString() {
        assertTrue(entity("").parseAgoraAgentIds().isEmpty())
        assertTrue(entity("   ").parseAgoraAgentIds().isEmpty())
    }

    @Test
    fun parseAgoraAgentIds_parsesValidJsonArray() {
        val result = entity("""["agent-a","agent-b","agent-c"]""").parseAgoraAgentIds()
        assertEquals(listOf("agent-a", "agent-b", "agent-c"), result)
    }

    @Test
    fun parseAgoraAgentIds_parsesSingleElementArray() {
        val result = entity("""["solo-agent"]""").parseAgoraAgentIds()
        assertEquals(listOf("solo-agent"), result)
    }

    // Note: inputs that cause Gson to throw (e.g. "not-json", wrong JSON type) call
    // android.util.Log.e() in the catch block, which is not available in JVM unit tests.
    // Those error paths must be covered by instrumented tests instead.

    @Test
    fun parseAgoraAgentIds_returnsEmptyListForEmptyJsonArray() {
        assertTrue(entity("[]").parseAgoraAgentIds().isEmpty())
    }
}
