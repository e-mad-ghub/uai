package com.mad.screenagent

import android.view.Surface
import com.mad.screenagent.feature.bubble.calculateLegacyOverlaySafeInsets
import com.mad.screenagent.feature.bubble.calculateOverlayBubbleBounds
import com.mad.screenagent.feature.bubble.clampOverlayBubblePosition
import com.mad.screenagent.feature.bubble.defaultOverlayBubblePosition
import com.mad.screenagent.feature.bubble.projectLegacyOverlayBubblePosition
import com.mad.screenagent.feature.bubble.restoreSavedOverlayBubblePosition
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

    @Test
    fun restoreSavedBubblePosition_snapsLandscapeMidpointBackToRightEdge() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 2400,
            screenHeight = 1080,
            realHeight = 1080,
            statusBarHeight = 0,
            bubbleSize = 176
        )

        val restored = restoreSavedOverlayBubblePosition(
            x = 1600,
            y = 420,
            bounds = bounds
        )

        assertEquals(bounds.maxX to 420, restored)
    }

    @Test
    fun landscapeBounds_respectHorizontalSafeInsets() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 2400,
            screenHeight = 1080,
            bubbleSize = 176,
            leftInset = 84,
            topInset = 0,
            rightInset = 92,
            bottomInset = 0
        )

        val clamped = clampOverlayBubblePosition(
            x = 5000,
            y = 420,
            bounds = bounds
        )

        assertEquals(84, bounds.minX)
        assertEquals(2132, bounds.maxX)
        assertEquals(bounds.maxX to 420, clamped)
    }

    @Test
    fun legacyLandscapeInsets_placeSideNavOnRightForRotation90() {
        val insets = calculateLegacyOverlaySafeInsets(
            screenWidth = 2160,
            screenHeight = 1080,
            realWidth = 2280,
            realHeight = 1080,
            rotation = Surface.ROTATION_90,
            statusBarHeight = 24
        )

        assertEquals(0, insets.left)
        assertEquals(24, insets.top)
        assertEquals(120, insets.right)
        assertEquals(0, insets.bottom)
    }

    @Test
    fun legacyLandscapeInsets_placeSideNavOnLeftForRotation270() {
        val insets = calculateLegacyOverlaySafeInsets(
            screenWidth = 2160,
            screenHeight = 1080,
            realWidth = 2280,
            realHeight = 1080,
            rotation = Surface.ROTATION_270,
            statusBarHeight = 24
        )

        assertEquals(120, insets.left)
        assertEquals(24, insets.top)
        assertEquals(0, insets.right)
        assertEquals(0, insets.bottom)
    }

    @Test
    fun restoreSavedBubblePosition_snapsToNearestLeftEdge() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 2400,
            screenHeight = 1080,
            realHeight = 1080,
            statusBarHeight = 0,
            bubbleSize = 176
        )

        val restored = restoreSavedOverlayBubblePosition(
            x = 320,
            y = 420,
            bounds = bounds
        )

        assertEquals(bounds.minX to 420, restored)
    }

    @Test
    fun projectLegacyBubblePosition_preservesRightEdgeIntentForWideModeSeed() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 2400,
            screenHeight = 1080,
            realHeight = 1080,
            statusBarHeight = 0,
            bubbleSize = 176
        )

        val projected = projectLegacyOverlayBubblePosition(
            legacyX = 904,
            legacyY = 420,
            bounds = bounds
        )

        assertEquals(bounds.maxX to 420, projected)
    }

    @Test
    fun projectLegacyBubblePosition_preservesLeftEdgeIntentForWideModeSeed() {
        val bounds = calculateOverlayBubbleBounds(
            screenWidth = 2400,
            screenHeight = 1080,
            realHeight = 1080,
            statusBarHeight = 0,
            bubbleSize = 176
        )

        val projected = projectLegacyOverlayBubblePosition(
            legacyX = 0,
            legacyY = 420,
            bounds = bounds
        )

        assertEquals(bounds.minX to 420, projected)
    }
}
