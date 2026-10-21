/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.bitchord.ui.components.foldingtabbar

import kotlin.math.roundToInt

internal data class FoldGeometry(
    val height: Int,
    val rowY: Int,
    val navWidth: Int,
    val searchWidth: Int,
    val searchX: Int,
    val selectedX: Int,
    val playerX: Int,
    val playerY: Int,
)

/**
 * Every position of the folding bar from two progress values, so a reversed fold retraces
 * the same path. [fold] may overshoot 0..1 under a spring; sizes are clamped, positions are not.
 */
internal fun foldGeometry(
    width: Int,
    expandedRowHeight: Int,
    collapsedRowHeight: Int,
    spacing: Int,
    playerHeight: Int,
    hasPlayer: Boolean,
    fold: Float,
    search: Float,
    tabsCount: Int,
    selectedIndex: Int,
    inset: Float,
): FoldGeometry {
    fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    val p = fold
    val s = search.coerceIn(0f, 1f)
    val rowHeight = mix(expandedRowHeight.toFloat(), collapsedRowHeight.toFloat(), p).roundToInt().coerceAtLeast(1)
    val circle = rowHeight.coerceAtMost(width)
    val rowY = if (hasPlayer) ((playerHeight + spacing) * (1f - p)).roundToInt().coerceAtLeast(0) else 0
    val tabWidth = (width - 2 * inset) / tabsCount.coerceAtLeast(1)
    val searchCenter = inset + tabWidth * (tabsCount - 0.5f)
    val searchWidth = mix(circle.toFloat(), (width - circle - spacing).coerceAtLeast(circle).toFloat(), s * (1f - p))
        .roundToInt().coerceAtLeast(circle)
    val detachedSearchX = mix(searchCenter - circle / 2f, (width - circle).toFloat(), p)
    return FoldGeometry(
        height = rowHeight + rowY,
        rowY = rowY,
        navWidth = mix(mix(width.toFloat(), circle.toFloat(), s), circle.toFloat(), p).roundToInt().coerceIn(circle, width),
        searchWidth = searchWidth,
        searchX = mix(detachedSearchX, (width - searchWidth).toFloat(), s).roundToInt(),
        selectedX = mix(inset + tabWidth * (selectedIndex + 0.5f) - circle / 2f, 0f, maxOf(p, s)).roundToInt(),
        playerX = mix(0f, (circle + spacing).toFloat(), p).roundToInt(),
        playerY = (rowY - mix((playerHeight + spacing).toFloat(), (playerHeight - rowHeight) / 2f, p)).roundToInt(),
    )
}
