package com.example.uai

import com.example.uai.ai.ThrottledStreamingMessageWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottledStreamingMessageWriterTest {

    @Test
    fun firstStreamingUpdateEmitsImmediately() = runBlocking {
        var nowMs = 0L
        val emitted = mutableListOf<Pair<String, Boolean>>()
        val writer = ThrottledStreamingMessageWriter(
            minUpdateIntervalMs = 75L,
            nowMs = { nowMs }
        ) { content, isStreaming ->
            emitted += content to isStreaming
        }

        writer.emitStreaming("Hel")

        assertEquals(listOf("Hel" to true), emitted)
    }

    @Test
    fun streamingUpdatesAreBatchedWithinInterval() = runBlocking {
        var nowMs = 0L
        val emitted = mutableListOf<Pair<String, Boolean>>()
        val writer = ThrottledStreamingMessageWriter(
            minUpdateIntervalMs = 75L,
            nowMs = { nowMs }
        ) { content, isStreaming ->
            emitted += content to isStreaming
        }

        writer.emitStreaming("Hel")
        nowMs = 20L
        writer.emitStreaming("Hello")
        nowMs = 40L
        writer.emitStreaming("Hello,")

        assertEquals(listOf("Hel" to true), emitted)
    }

    @Test
    fun streamingUpdateFlushesAgainAfterInterval() = runBlocking {
        var nowMs = 0L
        val emitted = mutableListOf<Pair<String, Boolean>>()
        val writer = ThrottledStreamingMessageWriter(
            minUpdateIntervalMs = 75L,
            nowMs = { nowMs }
        ) { content, isStreaming ->
            emitted += content to isStreaming
        }

        writer.emitStreaming("Hel")
        nowMs = 90L
        writer.emitStreaming("Hello")

        assertEquals(
            listOf(
                "Hel" to true,
                "Hello" to true
            ),
            emitted
        )
    }

    @Test
    fun finalFlushEmitsLatestContentEvenInsideInterval() = runBlocking {
        var nowMs = 0L
        val emitted = mutableListOf<Pair<String, Boolean>>()
        val writer = ThrottledStreamingMessageWriter(
            minUpdateIntervalMs = 75L,
            nowMs = { nowMs }
        ) { content, isStreaming ->
            emitted += content to isStreaming
        }

        writer.emitStreaming("Hel")
        nowMs = 20L
        writer.emitStreaming("Hello")
        nowMs = 25L
        writer.emitFinal("Hello")

        assertEquals(
            listOf(
                "Hel" to true,
                "Hello" to false
            ),
            emitted
        )
    }
}
