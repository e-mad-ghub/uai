package com.mad.screenagent

import com.mad.screenagent.shared.streaming.completeImageTurnMemoryIfNeeded
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentMemoryCompletionTest {

    @Test
    fun completeImageTurnMemoryIfNeeded_preparesMemoryForImageCapableTurn() = runBlocking {
        val preparedIds = mutableListOf<String>()

        val prepared = completeImageTurnMemoryIfNeeded(
            hasImages = true,
            canHandleImages = true,
            userMessageId = "message-1"
        ) { messageId ->
            preparedIds += messageId
        }

        assertTrue(prepared)
        assertEquals(listOf("message-1"), preparedIds)
    }

    @Test
    fun completeImageTurnMemoryIfNeeded_skipsWhenTurnCannotProduceUsefulMemory() = runBlocking {
        var prepareCount = 0

        val noImages = completeImageTurnMemoryIfNeeded(
            hasImages = false,
            canHandleImages = true,
            userMessageId = "message-1"
        ) { prepareCount++ }
        val noVision = completeImageTurnMemoryIfNeeded(
            hasImages = true,
            canHandleImages = false,
            userMessageId = "message-1"
        ) { prepareCount++ }
        val missingMessageId = completeImageTurnMemoryIfNeeded(
            hasImages = true,
            canHandleImages = true,
            userMessageId = null
        ) { prepareCount++ }

        assertFalse(noImages)
        assertFalse(noVision)
        assertFalse(missingMessageId)
        assertEquals(0, prepareCount)
    }
}
