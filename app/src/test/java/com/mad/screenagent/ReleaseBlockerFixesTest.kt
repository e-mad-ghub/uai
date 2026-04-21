package com.mad.screenagent

import androidx.compose.ui.unit.Constraints
import com.mad.screenagent.feature.bubble.calculateChatPanelMessageHeight
import com.mad.screenagent.shared.attachment.calculateImageDecodeSampleSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseBlockerFixesTest {

    @Test
    fun chatPanelMessageHeightShrinksToStayOnScreen() {
        val result = calculateChatPanelMessageHeight(
            totalHeightPx = 600,
            headerHeightPx = 260,
            footerHeightPx = 380,
            maxMessageHeightPx = 400
        )

        assertEquals(0, result)
    }

    @Test
    fun chatPanelMessageHeightCapsToConfiguredMaximum() {
        val result = calculateChatPanelMessageHeight(
            totalHeightPx = 1400,
            headerHeightPx = 220,
            footerHeightPx = 240,
            maxMessageHeightPx = 560
        )

        assertEquals(560, result)
    }

    @Test
    fun chatPanelMessageHeightUsesMaxWhenParentIsUnbounded() {
        val result = calculateChatPanelMessageHeight(
            totalHeightPx = Constraints.Infinity,
            headerHeightPx = 220,
            footerHeightPx = 240,
            maxMessageHeightPx = 320
        )

        assertEquals(320, result)
    }

    @Test
    fun imageDecodeSampleSizeKeepsLargeImagesNearAttachmentLimit() {
        val result = calculateImageDecodeSampleSize(
            width = 4000,
            height = 3000,
            maxSidePx = 2048
        )

        assertEquals(2, result)
    }

    @Test
    fun imageDecodeSampleSizeLeavesSmallerImagesUnchanged() {
        val result = calculateImageDecodeSampleSize(
            width = 1200,
            height = 900,
            maxSidePx = 2048
        )

        assertEquals(1, result)
    }
}
