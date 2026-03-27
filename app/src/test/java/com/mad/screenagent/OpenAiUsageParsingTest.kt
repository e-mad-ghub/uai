package com.mad.screenagent

import com.mad.screenagent.shared.streaming.OpenAiUsageTotals
import com.mad.screenagent.shared.streaming.parseChatCompletionsUsage
import com.mad.screenagent.shared.streaming.parseResponsesApiUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiUsageParsingTest {

    @Test
    fun mergeWith_keepsHighestUsageSnapshot() {
        val merged = OpenAiUsageTotals(inputTokens = 120, outputTokens = 40)
            .mergeWith(OpenAiUsageTotals(inputTokens = 100, outputTokens = 75))

        assertEquals(120, merged.inputTokens)
        assertEquals(75, merged.outputTokens)
    }

    @Test
    fun parseChatCompletionsUsage_readsUsageChunk() {
        val usage = parseChatCompletionsUsage(
            """data: {"choices":[],"usage":{"prompt_tokens":321,"completion_tokens":89}}"""
        )

        assertEquals(321, usage?.inputTokens)
        assertEquals(89, usage?.outputTokens)
    }

    @Test
    fun parseResponsesApiUsage_readsCompletedUsageChunk() {
        val usage = parseResponsesApiUsage(
            """data: {"type":"response.completed","response":{"usage":{"input_tokens":144,"output_tokens":55}}}"""
        )

        assertEquals(144, usage?.inputTokens)
        assertEquals(55, usage?.outputTokens)
    }

    @Test
    fun parseResponsesApiUsage_ignoresNonCompletedEvents() {
        val usage = parseResponsesApiUsage(
            """data: {"type":"response.output_text.delta","delta":"hello"}"""
        )

        assertNull(usage)
    }
}
