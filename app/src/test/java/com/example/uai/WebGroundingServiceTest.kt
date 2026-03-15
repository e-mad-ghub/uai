package com.example.uai

import com.example.uai.ai.ChatMessage
import com.example.uai.ai.FallbackWebSearchProvider
import com.example.uai.ai.WebGroundingResult
import com.example.uai.ai.WebGroundingService
import com.example.uai.ai.WebGroundingSource
import com.example.uai.ai.WebSearchProvider
import com.example.uai.ai.deriveWebSearchQuery
import com.example.uai.ai.deriveWebSearchQueries
import com.example.uai.ai.isLikelyWebGroundingFollowUp
import com.example.uai.ai.sanitizeGroundedAssistantResponse
import com.example.uai.ai.shouldUseWebGrounding
import com.example.uai.ai.stripQuotedReplyContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebGroundingServiceTest {

    @Test
    fun stripQuotedReplyContext_removesQuotedLinesBeforeSearchParsing() {
        val cleaned = stripQuotedReplyContext(
            """
            > Older assistant reply
            > second line

            what is the latest OpenAI news today?
            """.trimIndent()
        )

        assertEquals("what is the latest OpenAI news today?", cleaned)
    }

    @Test
    fun shouldUseWebGrounding_detectsExplicitAndFreshnessSignals() {
        assertTrue(shouldUseWebGrounding("please search online for the latest NVIDIA news"))
        assertTrue(shouldUseWebGrounding("what is the current price of bitcoin today?"))
        assertFalse(shouldUseWebGrounding("rewrite this paragraph in a calmer tone"))
    }

    @Test
    fun deriveWebSearchQuery_cleansCommandWords() {
        val query = deriveWebSearchQuery("please search online for the latest Grok API updates")

        assertEquals("the latest Grok API updates", query)
    }

    @Test
    fun followUpGrounding_detectsEllipticalFreshnessFollowUp() {
        assertTrue(
            isLikelyWebGroundingFollowUp(
                userText = "what about tesla?",
                previousUserText = "what's the latest price of nvidia?"
            )
        )
        assertFalse(
            isLikelyWebGroundingFollowUp(
                userText = "what about tesla?",
                previousUserText = "rewrite this paragraph in a calmer tone"
            )
        )
    }

    @Test
    fun deriveWebSearchQuery_fromMessages_buildsFollowUpQueryFromPreviousFreshTurn() {
        val query = deriveWebSearchQuery(
            listOf(
                ChatMessage(role = "user", content = "what's the latest price of nvidia?"),
                ChatMessage(role = "assistant", content = "NVIDIA is trading around ..."),
                ChatMessage(role = "user", content = "what about tesla?")
            )
        )

        assertEquals("tesla stock price yahoo finance", query)
    }

    @Test
    fun deriveWebSearchQueries_splitsMultiStockPromptIntoPerTickerSearches() {
        val queries = deriveWebSearchQueries(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "Please provide the latest stock price of TSLA and NVIDIA"
                )
            )
        )

        assertEquals(
            listOf("TSLA stock price yahoo finance", "NVIDIA stock price yahoo finance"),
            queries
        )
    }

    @Test
    fun deriveWebSearchQueries_singleStockPromptUsesFinanceOrientedQuery() {
        val queries = deriveWebSearchQueries(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "what is the latest NVIDIA stock price?"
                )
            )
        )

        assertEquals(listOf("NVIDIA stock price yahoo finance"), queries)
    }

    @Test
    fun applyGrounding_injectsPromptIntoLastUserMessageOnly() {
        val service = WebGroundingService(
            searchProvider = object : WebSearchProvider {
                override suspend fun search(query: String, maxResults: Int): List<WebGroundingSource> =
                    emptyList()
            }
        )
        val history = listOf(
            ChatMessage(role = "user", content = "older question"),
            ChatMessage(role = "assistant", content = "older answer"),
            ChatMessage(role = "user", content = "latest OpenAI news")
        )
        val grounding = WebGroundingResult(
            query = "latest OpenAI news",
            sources = listOf(
                WebGroundingSource(
                    title = "OpenAI News",
                    url = "https://openai.com/news",
                    snippet = "Fresh announcements"
                )
            )
        )

        val grounded = service.applyGrounding(history, grounding)

        assertEquals("older question", grounded.first().content)
        assertTrue(grounded.last().content.contains("<web_search_context>"))
        assertTrue(grounded.last().content.contains("OpenAI News"))
        assertTrue(grounded.last().content.contains("latest OpenAI news"))
    }

    @Test
    fun sanitizeGroundedAssistantResponse_hidesInternalContextLanguage() {
        val sanitized = sanitizeGroundedAssistantResponse(
            "The TSLA price is not available in the provided search results, and it is not listed within the shared context."
        )

        assertFalse(sanitized.contains("provided search results", ignoreCase = true))
        assertFalse(sanitized.contains("shared context", ignoreCase = true))
        assertTrue(sanitized.contains("sources I checked"))
    }

    @Test
    fun extractTickerCandidatesFromSearchHtml_readsFinanceWidgetTicker() {
        val service = WebGroundingService(
            searchProvider = object : WebSearchProvider {
                override suspend fun search(query: String, maxResults: Int): List<WebGroundingSource> =
                    emptyList()
            }
        )

        val candidates = service.extractTickerCandidatesFromSearchHtml(
            """
            <script>
            window.__STATE__ = {"quote":{"symbol":"TSLA","shortName":"Tesla, Inc."}};
            </script>
            """.trimIndent()
        )

        assertEquals(listOf("TSLA"), candidates)
    }

    @Test
    fun fallbackWebSearchProvider_usesNextProviderWhenFirstReturnsEmpty() {
        val fallback = FallbackWebSearchProvider(
            listOf(
                object : WebSearchProvider {
                    override suspend fun search(query: String, maxResults: Int): List<WebGroundingSource> =
                        emptyList()
                },
                object : WebSearchProvider {
                    override suspend fun search(query: String, maxResults: Int): List<WebGroundingSource> =
                        listOf(
                            WebGroundingSource(
                                title = "NVIDIA Finance",
                                url = "https://example.com/nvda",
                                snippet = "NVDA latest stock price"
                            )
                        )
                }
            )
        )

        val results = kotlinx.coroutines.runBlocking {
            fallback.search("latest nvidia stock price")
        }

        assertEquals(1, results.size)
        assertEquals("NVIDIA Finance", results.first().title)
    }
}
