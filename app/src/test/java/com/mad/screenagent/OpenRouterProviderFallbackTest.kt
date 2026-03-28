package com.mad.screenagent

import com.mad.screenagent.data.model.OpenRouterCatalog
import com.mad.screenagent.data.model.OpenRouterCatalogEntry
import com.mad.screenagent.data.model.OpenRouterFreeRoutingBucket
import com.mad.screenagent.shared.streaming.OpenRouterStreamingAttemptResult
import com.mad.screenagent.shared.streaming.StreamChunk
import com.mad.screenagent.shared.streaming.shouldRefreshOpenRouterCatalogForBucket
import com.mad.screenagent.shared.streaming.streamOpenRouterCandidatesWithFallback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterProviderFallbackTest {

    @Test
    fun catalogRefresh_skipsGeneralRequestsWhenCacheIsEmpty() {
        val shouldRefresh = shouldRefreshOpenRouterCatalogForBucket(
            bucket = OpenRouterFreeRoutingBucket.GENERAL,
            cachedCatalog = OpenRouterCatalog()
        )

        assertFalse(shouldRefresh)
    }

    @Test
    fun catalogRefresh_forcesAttachmentBucketsWhenCacheIsEmpty() {
        val visionRefresh = shouldRefreshOpenRouterCatalogForBucket(
            bucket = OpenRouterFreeRoutingBucket.VISION,
            cachedCatalog = OpenRouterCatalog()
        )
        val documentRefresh = shouldRefreshOpenRouterCatalogForBucket(
            bucket = OpenRouterFreeRoutingBucket.DOCUMENT,
            cachedCatalog = OpenRouterCatalog()
        )

        assertTrue(visionRefresh)
        assertTrue(documentRefresh)
    }

    @Test
    fun catalogRefresh_forcesVisionWhenCachedCatalogHasNoFreeVisionModels() {
        val shouldRefresh = shouldRefreshOpenRouterCatalogForBucket(
            bucket = OpenRouterFreeRoutingBucket.VISION,
            cachedCatalog = OpenRouterCatalog(
                fetchedAt = 123L,
                models = listOf(
                    OpenRouterCatalogEntry(
                        id = "meta-llama/llama-3.3-70b-instruct:free",
                        promptPrice = 0.0,
                        completionPrice = 0.0,
                        supportsVision = false
                    )
                )
            )
        )

        assertTrue(shouldRefresh)
    }

    @Test
    fun catalogRefresh_keepsVisionFastWhenCachedCatalogAlreadyHasFreeVisionModel() {
        val shouldRefresh = shouldRefreshOpenRouterCatalogForBucket(
            bucket = OpenRouterFreeRoutingBucket.VISION,
            cachedCatalog = OpenRouterCatalog(
                fetchedAt = 123L,
                models = listOf(
                    OpenRouterCatalogEntry(
                        id = "google/gemma-3-12b-it:free",
                        promptPrice = 0.0,
                        completionPrice = 0.0,
                        supportsVision = true
                    )
                )
            )
        )

        assertFalse(shouldRefresh)
    }

    @Test
    fun optimisticFallback_streamsFirstCandidateImmediatelyWithoutProbeDelay() = runBlocking {
        val invokedCandidates = mutableListOf<String>()
        val committedCandidates = mutableListOf<Pair<String, Int>>()
        val emittedChunks = mutableListOf<StreamChunk>()

        val result = streamOpenRouterCandidatesWithFallback(
            candidates = listOf("model-a", "model-b"),
            emit = { emittedChunks += it },
            streamCandidate = { candidate ->
                invokedCandidates += candidate
                flow {
                    emit(StreamChunk.Token("hello"))
                    emit(StreamChunk.Done)
                }
            },
            onCandidateCommitted = { candidate, index ->
                committedCandidates += candidate to index
                emittedChunks += StreamChunk.ModelSelection(candidate, viaFallback = index > 0)
            },
            shouldRetryOnChunklessError = { false }
        )

        assertEquals(OpenRouterStreamingAttemptResult.Terminated, result)
        assertEquals(listOf("model-a"), invokedCandidates)
        assertEquals(listOf("model-a" to 0), committedCandidates)
        assertEquals(
            listOf(
                StreamChunk.ModelSelection("model-a", viaFallback = false),
                StreamChunk.Token("hello"),
                StreamChunk.Done
            ),
            emittedChunks
        )
    }

    @Test
    fun optimisticFallback_retriesNextCandidateWhenErrorHappensBeforeFirstToken() = runBlocking {
        val invokedCandidates = mutableListOf<String>()
        val committedCandidates = mutableListOf<Pair<String, Int>>()
        val emittedChunks = mutableListOf<StreamChunk>()

        val result = streamOpenRouterCandidatesWithFallback(
            candidates = listOf("model-a", "model-b"),
            emit = { emittedChunks += it },
            streamCandidate = { candidate ->
                invokedCandidates += candidate
                when (candidate) {
                    "model-a" -> flow {
                        emit(StreamChunk.Error(Exception("HTTP 503")))
                    }

                    else -> flow {
                        emit(StreamChunk.Token("fallback"))
                        emit(StreamChunk.Done)
                    }
                }
            },
            onCandidateCommitted = { candidate, index ->
                committedCandidates += candidate to index
                emittedChunks += StreamChunk.ModelSelection(candidate, viaFallback = index > 0)
            },
            shouldRetryOnChunklessError = { true }
        )

        assertEquals(OpenRouterStreamingAttemptResult.Terminated, result)
        assertEquals(listOf("model-a", "model-b"), invokedCandidates)
        assertEquals(listOf("model-b" to 1), committedCandidates)
        assertEquals(
            listOf(
                StreamChunk.ModelSelection("model-b", viaFallback = true),
                StreamChunk.Token("fallback"),
                StreamChunk.Done
            ),
            emittedChunks
        )
    }

    @Test
    fun optimisticFallback_reportsExhaustedWhenAllCandidatesFailBeforeTokens() = runBlocking {
        val result = streamOpenRouterCandidatesWithFallback(
            candidates = listOf("model-a", "model-b"),
            emit = { },
            streamCandidate = {
                flow {
                    emit(StreamChunk.Error(Exception("HTTP 429")))
                }
            },
            shouldRetryOnChunklessError = { true }
        )

        assertTrue(result is OpenRouterStreamingAttemptResult.Exhausted)
        assertEquals("HTTP 429", (result as OpenRouterStreamingAttemptResult.Exhausted).lastFailure?.message)
    }

    @Test
    fun optimisticFallback_doesNotRetryAfterStreamingHasStarted() = runBlocking {
        val invokedCandidates = mutableListOf<String>()
        val emittedChunks = mutableListOf<StreamChunk>()

        val result = streamOpenRouterCandidatesWithFallback(
            candidates = listOf("model-a", "model-b"),
            emit = { emittedChunks += it },
            streamCandidate = { candidate: String ->
                invokedCandidates += candidate
                flow {
                    emit(StreamChunk.Token("partial"))
                    emit(StreamChunk.Error(Exception("HTTP 503")))
                }
            },
            onCandidateCommitted = { candidate, index ->
                emittedChunks += StreamChunk.ModelSelection(candidate, viaFallback = index > 0)
            },
            shouldRetryOnChunklessError = { true }
        )

        assertEquals(OpenRouterStreamingAttemptResult.Terminated, result)
        assertEquals(listOf("model-a"), invokedCandidates)
        assertEquals(
            listOf(
                StreamChunk.ModelSelection("model-a", viaFallback = false),
                StreamChunk.Token("partial"),
                StreamChunk.Error(Exception("HTTP 503"))
            ).map { chunk ->
                when (chunk) {
                    is StreamChunk.Error -> chunk.cause.message
                    is StreamChunk.ModelSelection -> chunk.modelId
                    is StreamChunk.Token -> chunk.text
                    StreamChunk.Done -> "done"
                    is StreamChunk.Usage -> "${chunk.inputTokens}:${chunk.outputTokens}"
                }
            },
            emittedChunks.map { chunk ->
                when (chunk) {
                    is StreamChunk.Error -> chunk.cause.message
                    is StreamChunk.ModelSelection -> chunk.modelId
                    is StreamChunk.Token -> chunk.text
                    StreamChunk.Done -> "done"
                    is StreamChunk.Usage -> "${chunk.inputTokens}:${chunk.outputTokens}"
                }
            }
        )
    }
}
