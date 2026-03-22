package com.mad.screenagent

import com.mad.screenagent.shared.streaming.OpenRouterBestFreeRoutingStateStore
import com.mad.screenagent.data.model.OpenRouterFreeRoutingBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterBestFreeRoutingStateStoreTest {

    @Test
    fun remembersLastSuccessfulModelPerAssistantAndBucket() {
        var now = 0L
        val store = OpenRouterBestFreeRoutingStateStore(nowMillis = { now })

        store.recordSuccess("assistant-a", OpenRouterFreeRoutingBucket.GENERAL, "model-1")
        store.recordSuccess("assistant-a", OpenRouterFreeRoutingBucket.VISION, "model-2")
        store.recordSuccess("assistant-b", OpenRouterFreeRoutingBucket.GENERAL, "model-3")

        assertEquals(
            "model-1",
            store.lastSuccessfulModelId("assistant-a", OpenRouterFreeRoutingBucket.GENERAL, idleTimeoutMs = 600_000L)
        )
        assertEquals(
            "model-2",
            store.lastSuccessfulModelId("assistant-a", OpenRouterFreeRoutingBucket.VISION, idleTimeoutMs = 600_000L)
        )
        assertEquals(
            "model-3",
            store.lastSuccessfulModelId("assistant-b", OpenRouterFreeRoutingBucket.GENERAL, idleTimeoutMs = 600_000L)
        )
    }

    @Test
    fun expiresStoredModelAfterIdleTimeout() {
        var now = 0L
        val store = OpenRouterBestFreeRoutingStateStore(nowMillis = { now })

        store.recordSuccess("assistant-a", OpenRouterFreeRoutingBucket.DOCUMENT, "model-4")

        now = 9L * 60L * 1_000L
        assertEquals(
            "model-4",
            store.lastSuccessfulModelId("assistant-a", OpenRouterFreeRoutingBucket.DOCUMENT, idleTimeoutMs = 600_000L)
        )

        now = 11L * 60L * 1_000L
        assertNull(
            store.lastSuccessfulModelId("assistant-a", OpenRouterFreeRoutingBucket.DOCUMENT, idleTimeoutMs = 600_000L)
        )
    }
}
