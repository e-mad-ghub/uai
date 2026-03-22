package com.example.uai

import com.example.uai.shared.streaming.ChatMessage
import com.example.uai.shared.streaming.ConversationIntent
import com.example.uai.shared.streaming.ConversationWorkingState
import com.example.uai.shared.streaming.ImageAttachment
import com.example.uai.shared.streaming.WebTurnMode
import com.example.uai.shared.streaming.WebTurnPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class WebGatewayTest {

    @Test
    fun plan_returnsAutoGroundForSingleFreshQuery() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what is the latest NVIDIA stock price?")
            )
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("NVIDIA stock price yahoo finance"), plan.queries)
        assertEquals("Looking up the latest market price…", plan.statusText)
        assertEquals(ConversationIntent.STOCK_PRICE, plan.intent)
    }

    @Test
    fun plan_normalizesConversationalStockPrompt() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "Okay, whats the stock price of tsla")
            )
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("tsla stock price yahoo finance"), plan.queries)
        assertEquals(ConversationIntent.STOCK_PRICE, plan.intent)
    }

    @Test
    fun plan_returnsToolSearchForMultiQueryTurn() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "provide the latest stock price of TSLA and NVIDIA")
            )
        )

        assertEquals(WebTurnMode.TOOL_SEARCH, plan.mode)
        assertEquals(
            listOf("TSLA stock price yahoo finance", "NVIDIA stock price yahoo finance"),
            plan.queries
        )
        assertEquals("Looking up the latest market prices…", plan.statusText)
        assertEquals(ConversationIntent.STOCK_PRICE, plan.intent)
    }

    @Test
    fun plan_keepsToolSearchModeAcrossFollowUpsWhenSessionWasToolDriven() {
        val session = ConversationWorkingState(
            conversationKey = "conv-1",
            activeIntent = ConversationIntent.STOCK_PRICE,
            activeSubjects = listOf("NVIDIA"),
            freshnessRequired = true,
            lastMode = WebTurnMode.TOOL_SEARCH,
            lastGroundedQueries = listOf("TSLA stock price yahoo finance", "NVIDIA stock price yahoo finance"),
            lastUpdatedAt = 123L
        )
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what is the latest stock price of NVIDIA?"),
                ChatMessage(role = "assistant", content = "NVIDIA is trading around ..."),
                ChatMessage(role = "user", content = "what about TSLA?")
            ),
            sessionState = session
        )

        assertEquals(WebTurnMode.TOOL_SEARCH, plan.mode)
        assertEquals(listOf("TSLA stock price yahoo finance"), plan.queries)
    }

    @Test
    fun plan_reusesLastStockQueriesForVaguePriceFollowUp() {
        val session = ConversationWorkingState(
            conversationKey = "conv-1",
            activeIntent = ConversationIntent.STOCK_PRICE,
            activeSubjects = listOf("NVIDIA"),
            freshnessRequired = true,
            lastMode = WebTurnMode.AUTO_GROUND,
            lastGroundedQueries = listOf("NVIDIA stock price yahoo finance"),
            lastUpdatedAt = 123L
        )
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "Tell me the price")
            ),
            sessionState = session
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("NVIDIA stock price yahoo finance"), plan.queries)
    }

    @Test
    fun plan_rebuildsStockContextFromBacklogForVagueFollowUp() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what is the latest NVIDIA stock price?"),
                ChatMessage(role = "assistant", content = "Let me check that for you."),
                ChatMessage(role = "user", content = "tell me the price")
            )
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("NVIDIA stock price yahoo finance"), plan.queries)
        assertEquals(ConversationIntent.STOCK_PRICE, plan.intent)
    }

    @Test
    fun plan_rebuildsStockContextFromBacklogForEntitySwapFollowUp() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what is the latest NVIDIA stock price?"),
                ChatMessage(role = "assistant", content = "Let me check that for you."),
                ChatMessage(role = "user", content = "what about tesla?")
            )
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("tesla stock price yahoo finance"), plan.queries)
        assertEquals(ConversationIntent.STOCK_PRICE, plan.intent)
    }

    @Test
    fun plan_routesExplicitTimeQuestionThroughCurrentTimeLookup() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what time is it in Tokyo right now?")
            )
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("current time in Tokyo"), plan.queries)
        assertEquals("Checking the current time…", plan.statusText)
        assertEquals(ConversationIntent.CURRENT_TIME, plan.intent)
    }

    @Test
    fun plan_routesGeneralNewsQuestionThroughWebGrounding() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what is the latest news about the iran war?")
            )
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("what is the latest news about the iran war"), plan.queries)
        assertEquals(ConversationIntent.GENERAL_WEB, plan.intent)
        assertEquals("Looking online for fresh results…", plan.statusText)
    }

    @Test
    fun plan_keepsLocalOrderingRequestOffTheNetwork() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "order these by name: Tesla, Nvidia, Apple")
            )
        )

        assertEquals(WebTurnMode.NONE, plan.mode)
        assertEquals(emptyList<String>(), plan.queries)
        assertEquals(ConversationIntent.NONE, plan.intent)
    }

    @Test
    fun plan_keepsImageAnalysisTurnOffTheNetwork() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "What do you see in this image?",
                    images = listOf(ImageAttachment("base64-image"))
                )
            )
        )

        assertEquals(WebTurnMode.NONE, plan.mode)
        assertEquals(emptyList<String>(), plan.queries)
        assertEquals(ConversationIntent.NONE, plan.intent)
    }

    @Test
    fun plan_resolvesImplicitTimeFollowUpFromConversationState() {
        val session = ConversationWorkingState(
            conversationKey = "conv-1",
            activeIntent = ConversationIntent.CURRENT_TIME,
            activeSubjects = listOf("Berlin"),
            activeLocation = "Berlin",
            freshnessRequired = true,
            lastMode = WebTurnMode.AUTO_GROUND,
            lastGroundedQueries = listOf("current time in Berlin"),
            lastUpdatedAt = 123L
        )

        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what time is it there?")
            ),
            sessionState = session
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("current time in Berlin"), plan.queries)
        assertEquals("Checking the current time…", plan.statusText)
    }

    @Test
    fun plan_reusesGeneralWebContextForWhatAboutFollowUp() {
        val session = ConversationWorkingState(
            conversationKey = "conv-1",
            activeIntent = ConversationIntent.GENERAL_WEB,
            activeSubjects = listOf("iran war latest news"),
            freshnessRequired = true,
            lastMode = WebTurnMode.AUTO_GROUND,
            lastGroundedQueries = listOf("iran war latest news"),
            lastUpdatedAt = 123L
        )

        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what about oil prices?")
            ),
            sessionState = session
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(ConversationIntent.GENERAL_WEB, plan.intent)
        assertEquals(false, plan.queries.isEmpty())
    }

    @Test
    fun plan_rebuildsTimeContextFromBacklogForThereFollowUp() {
        val plan = WebTurnPlanner().plan(
            messages = listOf(
                ChatMessage(role = "user", content = "what time is it in Berlin?"),
                ChatMessage(role = "assistant", content = "I can check that."),
                ChatMessage(role = "user", content = "what time is it there?")
            )
        )

        assertEquals(WebTurnMode.AUTO_GROUND, plan.mode)
        assertEquals(listOf("current time in Berlin"), plan.queries)
        assertEquals(ConversationIntent.CURRENT_TIME, plan.intent)
    }
}
