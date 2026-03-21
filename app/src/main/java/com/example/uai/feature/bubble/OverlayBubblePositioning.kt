package com.example.uai.feature.bubble

internal data class OverlayBubbleBounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int
)

internal fun calculateOverlayBubbleBounds(
    screenWidth: Int,
    screenHeight: Int,
    realHeight: Int,
    statusBarHeight: Int,
    bubbleSize: Int
): OverlayBubbleBounds {
    val navBarHeight = (realHeight - screenHeight).coerceAtLeast(0)
    val maxX = (screenWidth - bubbleSize).coerceAtLeast(0)
    val maxY = (screenHeight - bubbleSize - navBarHeight).coerceAtLeast(statusBarHeight)
    return OverlayBubbleBounds(
        minX = 0,
        maxX = maxX,
        minY = statusBarHeight.coerceAtLeast(0),
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

internal fun defaultOverlayBubblePosition(
    bounds: OverlayBubbleBounds
): Pair<Int, Int> {
    return bounds.maxX to ((bounds.minY + bounds.maxY) / 2)
}
