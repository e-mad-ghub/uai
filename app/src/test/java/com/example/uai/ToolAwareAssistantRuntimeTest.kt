package com.example.uai

import com.example.uai.shared.streaming.AssistantToolRequest
import com.example.uai.shared.streaming.ChatMessage
import com.example.uai.shared.streaming.ImageAttachment
import com.example.uai.shared.streaming.SearchToolExecutor
import com.example.uai.shared.streaming.StreamChunk
import com.example.uai.shared.streaming.ToolAwareAssistantRuntime
import com.example.uai.shared.streaming.WebGroundingFact
import com.example.uai.shared.streaming.WebGroundingResult
import com.example.uai.shared.streaming.WebGroundingSource
import com.example.uai.shared.streaming.classifyOpenRouterRequestBucket
import com.example.uai.shared.streaming.parseAssistantToolRequest
import com.example.uai.data.model.OpenRouterFreeRoutingBucket
import com.example.uai.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolAwareAssistantRuntimeTest {

    @Test
    fun parseAssistantToolRequest_readsSearchWebQuery() {
        val request = parseAssistantToolRequest(
            "<tool_request>{\"tool\":\"search_web\",\"query\":\"latest tsla stock price\",\"reason\":\"Need fresh data\"}</tool_request>"
        )

        assertEquals(
            AssistantToolRequest(
                tool = "search_web",
                query = "latest tsla stock price",
                reason = "Need fresh data"
            ),
            request
        )
    }

    @Test
    fun runtime_executesSearchToolAndReturnsFinalAnswer() = runBlocking {
        val providers = ArrayDeque(
            listOf(
                FakeProvider(
                    listOf(
                        StreamChunk.ModelSelection("model-a"),
                        StreamChunk.Token(
                            "<tool_request>{\"tool\":\"search_web\",\"query\":\"tsla stock price yahoo finance\"}</tool_request>"
                        ),
                        StreamChunk.Done
                    )
                ),
                FakeProvider(
                    listOf(
                        StreamChunk.ModelSelection("model-a"),
                        StreamChunk.Token("TSLA is trading at 391.2 USD, according to Stooq."),
                        StreamChunk.Done
                    )
                )
            )
        )
        val searchQueries = mutableListOf<String>()
        val runtime = ToolAwareAssistantRuntime(
            providerFactory = { providers.removeFirst() },
            searchToolExecutor = SearchToolExecutor { _, query, _, _ ->
                searchQueries += query
                WebGroundingResult(
                    query = query,
                    sources = listOf(
                        WebGroundingSource(
                            title = "Stooq TSLA quote",
                            url = "https://stooq.com/q/l/?s=tsla.us&i=d",
                            snippet = ""
                        )
                    ),
                    facts = listOf(
                        WebGroundingFact(
                            label = "Latest available stock price for TSLA",
                            value = "391.2 USD as of 2026-03-13 15:55:00 UTC (ticker TSLA)",
                            sourceTitle = "Stooq quote for TSLA",
                            sourceUrl = "https://stooq.com/q/l/?s=tsla.us&i=d",
                            sourceQuery = query
                        )
                    )
                )
            }
        )

        val chunks = runtime.streamResponse(
            conversationKey = "conv-1",
            messages = listOf(ChatMessage(role = "user", content = "What is the latest TSLA stock price?")),
            config = AgentConfig()
        ).toList()

        assertEquals(listOf("tsla stock price yahoo finance"), searchQueries)
        val text = chunks.filterIsInstance<StreamChunk.Token>().joinToString("") { it.text }
        assertTrue(text.contains("391.2 USD"))
        assertFalse(text.contains("tool_request"))
        assertFalse(text.contains("provided context", ignoreCase = true))
    }

    @Test
    fun runtime_bypassesToolLoopForPlainImageAnalysisTurn() = runBlocking {
        var receivedImageCount = 0
        var receivedSystemPrompt = ""
        var searchCalled = false
        val runtime = ToolAwareAssistantRuntime(
            providerFactory = { config ->
                receivedSystemPrompt = config.systemPrompt
                object : com.example.uai.shared.streaming.AiProvider {
                    override fun streamResponse(
                        messages: List<ChatMessage>,
                        config: AgentConfig
                    ): Flow<StreamChunk> = flow {
                        receivedImageCount = messages.first { it.role == "user" }.images.size
                        emit(StreamChunk.Token("I can see the image."))
                        emit(StreamChunk.Done)
                    }
                }
            },
            searchToolExecutor = SearchToolExecutor { _, _, _, _ ->
                searchCalled = true
                fail("search tool should not run for plain image analysis")
                null
            }
        )

        val chunks = runtime.streamResponse(
            conversationKey = "conv-1",
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "What do you see in this image?",
                    images = listOf(ImageAttachment("base64-image"))
                )
            ),
            config = AgentConfig(systemPrompt = "base prompt")
        ).toList()

        assertEquals(1, receivedImageCount)
        assertEquals("base prompt", receivedSystemPrompt)
        assertEquals(false, searchCalled)
        val text = chunks.filterIsInstance<StreamChunk.Token>().joinToString("") { it.text }
        assertTrue(text.contains("I can see the image."))
    }

    @Test
    fun runtime_keepsOriginalImageMessageAcrossToolRounds() = runBlocking {
        val seenImageCounts = mutableListOf<Int>()
        val runtime = ToolAwareAssistantRuntime(
            providerFactory = {
                object : com.example.uai.shared.streaming.AiProvider {
                    private val callIndex = seenImageCounts.size

                    override fun streamResponse(
                        messages: List<ChatMessage>,
                        config: AgentConfig
                    ): Flow<StreamChunk> = flow {
                        seenImageCounts += messages.first { it.role == "user" }.images.size
                        if (callIndex == 0) {
                            emit(
                                StreamChunk.Token(
                                    "<tool_request>{\"tool\":\"search_web\",\"query\":\"latest news about the product in the screenshot\"}</tool_request>"
                                )
                            )
                        } else {
                            emit(StreamChunk.Token("I checked the screenshot and the sources."))
                        }
                        emit(StreamChunk.Done)
                    }
                }
            },
            searchToolExecutor = SearchToolExecutor { _, _, _, _ ->
                WebGroundingResult(
                    query = "latest news about the product in the screenshot",
                    sources = listOf(
                        WebGroundingSource(
                            title = "Example source",
                            url = "https://example.com",
                            snippet = "Example snippet"
                        )
                    )
                )
            }
        )

        runtime.streamResponse(
            conversationKey = "conv-1",
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "What product is this and what is the latest news about it?",
                    images = listOf(ImageAttachment("base64-image"))
                )
            ),
            config = AgentConfig()
        ).toList()

        assertEquals(listOf(1, 1), seenImageCounts)
    }

    @Test
    fun openRouterBucketUsesGeneralWhenLastUserMessageIsToolResultNotImage() {
        // The classifier only checks the *current* (last) user turn for images to avoid
        // locking all tool-round follow-ups into VISION even when no image is in scope.
        // A <tool_result> injected after an image turn therefore yields GENERAL, not VISION.
        val bucket = classifyOpenRouterRequestBucket(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "What product is this and what is the latest news about it?",
                    images = listOf(ImageAttachment("base64-image"))
                ),
                ChatMessage(
                    role = "assistant",
                    content = "<tool_request>{\"tool\":\"search_web\",\"query\":\"latest news about the product in the screenshot\"}</tool_request>"
                ),
                ChatMessage(
                    role = "user",
                    content = "<tool_result name=\"search_web\">...</tool_result>"
                )
            )
        )

        assertEquals(OpenRouterFreeRoutingBucket.GENERAL, bucket)
    }

    @Test
    fun openRouterBucketIsVisionWhenLastUserMessageContainsImage() {
        val bucket = classifyOpenRouterRequestBucket(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "What do you see in this image?",
                    images = listOf(ImageAttachment("base64-image"))
                )
            )
        )

        assertEquals(OpenRouterFreeRoutingBucket.VISION, bucket)
    }

    private class FakeProvider(
        private val chunks: List<StreamChunk>
    ) : com.example.uai.shared.streaming.AiProvider {
        override fun streamResponse(
            messages: List<ChatMessage>,
            config: AgentConfig
        ): Flow<StreamChunk> = flow {
            chunks.forEach { emit(it) }
        }
    }
}
