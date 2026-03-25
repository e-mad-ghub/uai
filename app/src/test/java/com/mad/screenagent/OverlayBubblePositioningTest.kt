package com.mad.screenagent

import com.mad.screenagent.feature.bubble.calculateOverlayBubbleBounds
import com.mad.screenagent.feature.bubble.clampOverlayBubblePosition
import com.mad.screenagent.feature.bubble.defaultOverlayBubblePosition
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayBubblePositioningTest {

    @Test
    fun bubblePositionIsClampedBackIntoPortraitBoundsAfterLandscapePlacement() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 1080,
            screenHeight = 2094,
            realHeight = 2160,
            statusBarHeight = 96,
            bubbleSize = 176
        )

        val clamped = clampOverlayBubblePosition(
            x = 1664,
            y = 420,
            bounds = bounds
        )

        assertEquals(904 to 420, clamped)
    }

    @Test
    fun bubblePositionKeepsStatusBarSafeTopInset() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 1080,
            screenHeight = 2094,
            realHeight = 2160,
            statusBarHeight = 96,
            bubbleSize = 176
        )

        val clamped = clampOverlayBubblePosition(
            x = -24,
            y = 12,
            bounds = bounds
        )

        // minY now includes radial menu top padding (72dp @ density=1f) above status bar
        assertEquals(0 to 168, clamped)
    }

    @Test
    fun bubblePositionClampsToBottomVisibleArea() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 1080,
            screenHeight = 2094,
            realHeight = 2160,
            statusBarHeight = 96,
            bubbleSize = 176
        )

        val clamped = clampOverlayBubblePosition(
            x = 320,
            y = 5000,
            bounds = bounds
        )

        // maxY now reserves radial menu bottom padding (144dp @ density=1f) for custom action slots
        assertEquals(320 to 1708, clamped)
    }

    @Test
    fun defaultBubblePositionStartsOnRightSideMiddle() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 1080,
            screenHeight = 2094,
            realHeight = 2160,
            statusBarHeight = 96,
            bubbleSize = 176
        )

        val defaultPosition = defaultOverlayBubblePosition(bounds)

        // Default Y is midpoint of new safe zone (minY=168, maxY=1708) / 2 = 938
        assertEquals(904 to 938, defaultPosition)
    }
}
