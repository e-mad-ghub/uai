package com.example.uai

import com.example.uai.service.calculateOverlayBubbleBounds
import com.example.uai.service.clampOverlayBubblePosition
import com.example.uai.service.defaultOverlayBubblePosition
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

        assertEquals(0 to 96, clamped)
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

        assertEquals(320 to 1852, clamped)
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

        assertEquals(904 to 974, defaultPosition)
    }
}
