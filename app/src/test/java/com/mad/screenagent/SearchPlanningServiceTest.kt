package com.mad.screenagent

import com.mad.screenagent.shared.streaming.AiProvider
import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.ImageAttachment
import com.mad.screenagent.shared.streaming.SearchPlanningService
import com.mad.screenagent.shared.streaming.StreamChunk
import com.mad.screenagent.shared.streaming.WebGateway
import com.mad.screenagent.shared.streaming.WebGroundingService
import com.mad.screenagent.shared.streaming.WebGroundingSource
import com.mad.screenagent.shared.streaming.WebSearchProvider
import com.mad.screenagent.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlanningServiceTest {

    @Test
    fun planner_extractsMultipleSearchPointsFromMixedRequest() = runBlocking {
        val service = SearchPlanningService(
            providerFactory = { FakeProvider("<search_plan>{\"needs_search\":true,\"queries\":[\"NVIDIA stock price yahoo finance\",\"TSLA stock price yahoo finance\",\"latest news about the iran war\"]}</search_plan>") }
        )

        val result = service.planSearches(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "What are the stock price for nvidia and tsla, also tell me something about the current war with iran"
                )
            ),
            config = AgentConfig(),
            previousState = null
        )

        assertEquals(
            listOf(
                "NVIDIA stock price yahoo finance",
                "TSLA stock price yahoo finance",
                "latest news about the iran war"
            ),
            result?.queries
        )
    }

    @Test
    fun planner_skipsLocalOnlyTaskWithoutCallingModel() = runBlocking {
        var called = false
        val service = SearchPlanningService(
            providerFactory = {
                called = true
                FakeProvider("<search_plan>{\"needs_search\":false,\"queries\":[]}</search_plan>")
            }
        )

        val result = service.planSearches(
            messages = listOf(
                ChatMessage(role = "user", content = "order these by name: Tesla, Nvidia, Apple")
            ),
            config = AgentConfig(),
            previousState = null
        )

        assertEquals(false, result?.needsSearch)
        assertEquals(false, called)
    }

    @Test
    fun planner_skipsAttachmentTurnsWithoutCallingModel() = runBlocking {
        var called = false
        val service = SearchPlanningService(
            providerFactory = {
                called = true
                FakeProvider("<search_plan>{\"needs_search\":true,\"queries\":[\"should not happen\"]}</search_plan>")
            }
        )

        val result = service.planSearches(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "What do you see in this screenshot?",
                    images = listOf(ImageAttachment("base64-image"))
                )
            ),
            config = AgentConfig(),
            previousState = null
        )

        assertEquals(false, result?.needsSearch)
        assertEquals(false, called)
    }

    @Test
    fun webGateway_prefersModelSearchPlanOverHeuristicGuess() = runBlocking {
        val gateway = WebGateway(
            groundingService = WebGroundingService(
                searchProvider = object : WebSearchProvider {
                    override suspend fun search(query: String, maxResults: Int): List<WebGroundingSource> =
                        listOf(
                            WebGroundingSource(
                                title = "Result for $query",
                                url = "https://example.com/${query.hashCode()}",
                                snippet = "Snippet for $query"
                            )
                        )
                }
            ),
            searchPlanningService = SearchPlanningService(
                providerFactory = {
                    FakeProvider(
                        "<search_plan>{\"needs_search\":true,\"queries\":[\"latest quote for NVIDIA\",\"latest quote for TSLA\",\"latest news about the iran war\"]}</search_plan>"
                    )
                }
            )
        )

        val prepared = gateway.prepareTurn(
            conversationKey = "conv-1",
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "What are the stock price for nvidia and tsla, also tell me something about the current war with iran"
                )
            ),
            planningConfig = AgentConfig()
        )

        assertEquals(
            listOf(
                "latest quote for NVIDIA",
                "latest quote for TSLA",
                "latest news about the iran war"
            ),
            prepared.plan.queries
        )
        assertTrue(prepared.grounding != null)
        assertEquals(3, prepared.plan.queries.size)
    }

    private class FakeProvider(
        private val text: String
    ) : AiProvider {
        override fun streamResponse(
            messages: List<ChatMessage>,
            config: AgentConfig
        ): Flow<StreamChunk> = flow {
            emit(StreamChunk.Token(text))
            emit(StreamChunk.Done)
        }
    }
}
