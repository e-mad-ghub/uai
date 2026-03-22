package com.mad.screenagent.feature.bubble

internal data class OverlayBubbleBounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int
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
    realHeight: Int,
    statusBarHeight: Int,
    bubbleSize: Int,
    density: Float = 1f
): OverlayBubbleBounds {
    val navBarHeight = (realHeight - screenHeight).coerceAtLeast(0)
    val radialTopPx = (RADIAL_MENU_TOP_PAD_DP * density).toInt()
    val radialBottomPx = (RADIAL_MENU_BOTTOM_PAD_DP * density).toInt()
    val maxX = (screenWidth - bubbleSize).coerceAtLeast(0)
    val minY = (statusBarHeight + radialTopPx).coerceAtLeast(0)
    val maxY = (screenHeight - bubbleSize - navBarHeight - radialBottomPx).coerceAtLeast(minY)
    return OverlayBubbleBounds(
        minX = 0,
        maxX = maxX,
        minY = minY,
        maxY = maxY
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
    val snappedX = if (x + bubbleSize / 2 < screenWidth / 2) bounds.minX else bounds.maxX
    val clampedY = y.coerceIn(bounds.minY, bounds.maxY)
    return snappedX to clampedY
}

internal fun defaultOverlayBubblePosition(
    bounds: OverlayBubbleBounds
): Pair<Int, Int> {
    return bounds.maxX to ((bounds.minY + bounds.maxY) / 2)
}
