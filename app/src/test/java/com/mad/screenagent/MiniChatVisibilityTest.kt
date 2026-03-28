package com.mad.screenagent

import com.mad.screenagent.feature.bubble.shouldDeferPanelRestoreAfterExternalFlow
import com.mad.screenagent.feature.bubble.shouldMinimizeMiniChatOnWindowFocusLoss
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniChatVisibilityTest {

    @Test
    fun windowFocusLoss_minimizesPanelWhenItWasVisibleAndAppIsBackgrounded() {
        val shouldMinimize = shouldMinimizeMiniChatOnWindowFocusLoss(
            hadWindowFocus = true,
            hasWindowFocus = false,
            isPanelAttached = true,
            isAppUiVisible = false,
            isExternalFlow = false,
            isScreenshotCaptureInProgress = false
        )

        assertTrue(shouldMinimize)
    }

    @Test
    fun windowFocusLoss_doesNotMinimizeBeforePanelEverHadFocus() {
        val shouldMinimize = shouldMinimizeMiniChatOnWindowFocusLoss(
            hadWindowFocus = false,
            hasWindowFocus = false,
            isPanelAttached = true,
            isAppUiVisible = false,
            isExternalFlow = false,
            isScreenshotCaptureInProgress = false
        )

        assertFalse(shouldMinimize)
    }

    @Test
    fun windowFocusLoss_doesNotMinimizeDuringExternalFlow() {
        val shouldMinimize = shouldMinimizeMiniChatOnWindowFocusLoss(
            hadWindowFocus = true,
            hasWindowFocus = false,
            isPanelAttached = true,
            isAppUiVisible = false,
            isExternalFlow = true,
            isScreenshotCaptureInProgress = false
        )

        assertFalse(shouldMinimize)
    }

    @Test
    fun externalFlowRestore_defersPanelReopenWhileAppIsStillForegrounded() {
        val shouldDefer = shouldDeferPanelRestoreAfterExternalFlow(
            reopenPanel = true,
            isAppUiVisible = true
        )

        assertTrue(shouldDefer)
    }

    @Test
    fun externalFlowRestore_doesNotQueuePanelWhenBubbleShouldReturn() {
        val shouldDefer = shouldDeferPanelRestoreAfterExternalFlow(
            reopenPanel = false,
            isAppUiVisible = true
        )

        assertFalse(shouldDefer)
    }
}
