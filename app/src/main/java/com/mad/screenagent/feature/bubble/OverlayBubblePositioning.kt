package com.mad.screenagent.feature.bubble

import android.view.Surface

internal data class OverlayBubbleBounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int
)

internal data class OverlaySafeInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Extra padding (dp) reserved around the bubble for the radial quick-access menu.
 * Top / bottom are for the "Open App" and custom-action icons respectively.
 * Left / right are 0 because side icons always expand toward the screen center.
 */
internal const val RADIAL_MENU_TOP_PAD_DP = 72     // Open App icon height + gap
internal const val RADIAL_MENU_BOTTOM_PAD_DP = 144  // 2 custom-action icons + gaps

internal fun calculateOverlayBubbleBounds(
    screenWidth: Int,
    screenHeight: Int,
    bubbleSize: Int,
    density: Float = 1f,
    leftInset: Int = 0,
    topInset: Int = 0,
    rightInset: Int = 0,
    bottomInset: Int = 0
): OverlayBubbleBounds {
    val radialTopPx = (RADIAL_MENU_TOP_PAD_DP * density).toInt()
    val radialBottomPx = (RADIAL_MENU_BOTTOM_PAD_DP * density).toInt()
    val minX = leftInset.coerceAtLeast(0)
    val maxX = (screenWidth - rightInset - bubbleSize).coerceAtLeast(minX)
    val minY = (topInset + radialTopPx).coerceAtLeast(0)
    val maxY = (screenHeight - bottomInset - bubbleSize - radialBottomPx).coerceAtLeast(minY)
    return OverlayBubbleBounds(
        minX = minX,
        maxX = maxX,
        minY = minY,
        maxY = maxY
    )
}

internal fun calculateLegacyOverlaySafeInsets(
    screenWidth: Int,
    screenHeight: Int,
    realWidth: Int,
    realHeight: Int,
    rotation: Int,
    statusBarHeight: Int
): OverlaySafeInsets {
    val horizontalInset = (realWidth - screenWidth).coerceAtLeast(0)
    val bottomInset = (realHeight - screenHeight).coerceAtLeast(0)
    val leftInset = when {
        horizontalInset == 0 -> 0
        rotation == Surface.ROTATION_270 -> horizontalInset
        else -> 0
    }
    val rightInset = when {
        horizontalInset == 0 -> 0
        rotation == Surface.ROTATION_90 -> horizontalInset
        rotation == Surface.ROTATION_270 -> 0
        else -> horizontalInset
    }
    return OverlaySafeInsets(
        left = leftInset,
        top = statusBarHeight.coerceAtLeast(0),
        right = rightInset,
        bottom = bottomInset
    )
}

internal fun calculateOverlayBubbleBounds(
    screenWidth: Int,
    screenHeight: Int,
    realHeight: Int,
    statusBarHeight: Int,
    bubbleSize: Int,
    density: Float = 1f
): OverlayBubbleBounds {
    return calculateOverlayBubbleBounds(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        bubbleSize = bubbleSize,
        density = density,
        topInset = statusBarHeight,
        bottomInset = (realHeight - screenHeight).coerceAtLeast(0)
    )
}

internal fun clampOverlayBubblePosition(
    x: Int,
    y: Int,
    bounds: OverlayBubbleBounds
): Pair<Int, Int> {
    return x.coerceIn(bounds.minX, bounds.maxX) to
        y.coerceIn(bounds.minY, bounds.maxY)
}

/**
 * Snap the bubble to the nearest valid position after the user releases a free drag.
 * X always snaps to the nearest screen edge (left or right).
 * Y is clamped to [bounds] without further snapping (it stays wherever the user left it,
 * just moved inside the safe zone if needed).
 */
internal fun snapOverlayBubblePosition(
    x: Int,
    y: Int,
    screenWidth: Int,
    bubbleSize: Int,
    bounds: OverlayBubbleBounds
): Pair<Int, Int> {
    val bubbleCenterX = x + bubbleSize / 2
    val leftCenterX = bounds.minX + bubbleSize / 2
    val rightCenterX = bounds.maxX + bubbleSize / 2
    val snappedX = if (
        kotlin.math.abs(bubbleCenterX - leftCenterX) <=
        kotlin.math.abs(rightCenterX - bubbleCenterX)
    ) bounds.minX else bounds.maxX
    val clampedY = y.coerceIn(bounds.minY, bounds.maxY)
    return snappedX to clampedY
}

/**
 * Restores a saved position into the current layout. Bubble positions are edge-snapped by design,
 * so a restore also normalizes the horizontal position back onto the nearest edge in the current
 * layout. This keeps interrupted drags or stale mid-screen saves from reappearing in the middle.
 */
internal fun restoreSavedOverlayBubblePosition(
    x: Int,
    y: Int,
    bounds: OverlayBubbleBounds
): Pair<Int, Int> {
    val distanceToLeft = kotlin.math.abs(x - bounds.minX)
    val distanceToRight = kotlin.math.abs(bounds.maxX - x)
    val projectedX = if (distanceToLeft <= distanceToRight) bounds.minX else bounds.maxX
    val projectedY = y.coerceIn(bounds.minY, bounds.maxY)
    return projectedX to projectedY
}

/**
 * Projects a legacy single-layout save into the current mode. Older builds only stored one x/y
 * pair, so a previously right-snapped portrait bubble would otherwise land mid-screen in wide.
 * Treat any positive legacy x as a right-edge position.
 */
internal fun projectLegacyOverlayBubblePosition(
    legacyX: Int,
    legacyY: Int,
    bounds: OverlayBubbleBounds
): Pair<Int, Int> {
    val projectedX = if (legacyX <= 0) bounds.minX else bounds.maxX
    val projectedY = legacyY.coerceIn(bounds.minY, bounds.maxY)
    return projectedX to projectedY
}

internal fun defaultOverlayBubblePosition(
    bounds: OverlayBubbleBounds
): Pair<Int, Int> {
    return bounds.maxX to ((bounds.minY + bounds.maxY) / 2)
}
