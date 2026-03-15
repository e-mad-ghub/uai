package com.example.uai

import com.example.uai.ui.agora.extractAgentScopedGroundingText
import org.junit.Assert.assertEquals
import org.junit.Test

class AgoraGroundingPromptTest {

    @Test
    fun extractAgentScopedGroundingText_returnsWholePromptWhenThereIsNoMultiMentionSplit() {
        val text = "Please provide the latest price of TSLA"

        assertEquals(
            text,
            extractAgentScopedGroundingText(
                text = text,
                agentName = "Ava",
                allAgentNames = listOf("Ava", "Noah")
            )
        )
    }

    @Test
    fun extractAgentScopedGroundingText_returnsMentionScopedSegmentForTargetAgent() {
        val text = "@Ava provide the TSLA stock price. @Noah provide the NVIDIA stock price."

        assertEquals(
            "provide the TSLA stock price.",
            extractAgentScopedGroundingText(
                text = text,
                agentName = "Ava",
                allAgentNames = listOf("Ava", "Noah")
            )
        )
        assertEquals(
            "provide the NVIDIA stock price.",
            extractAgentScopedGroundingText(
                text = text,
                agentName = "Noah",
                allAgentNames = listOf("Ava", "Noah")
            )
        )
    }
}
