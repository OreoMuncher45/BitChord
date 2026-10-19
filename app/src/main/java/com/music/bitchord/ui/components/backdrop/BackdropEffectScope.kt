/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.music.bitchord.ui.components.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    val size: Size

    val layoutDirection: LayoutDirection

    val shape: Shape

    var padding: Float

    var renderEffect: RenderEffect?

    /**
     * A colour transform to apply when the recorded layer is COMPOSITED, rather than as
     * another link in [renderEffect]'s chain. Each link is its own render pass, and a
     * colour matrix is linear: it commutes with the blur's weighted average and with the
     * lens's warp, so the pixels are the same either way. The layer is already drawn
     * offscreen for its render effect, so compositing through a colour filter is free.
     */
    var compositeColorFilter: ColorFilter?

    /**
     * Set (via `poolLens`) instead of chaining a lens effect when sampling the shared glass
     * pool: the pool then refracts this surface's region in its single multi-shape pass, and
     * this surface needs no offscreen layer or lens pass of its own.
     */
    var poolLens: PoolLens?
}

/** Refraction a pooled glass surface asks the pool for, in this surface's pixels. */
data class PoolLens(
    val refractionHeight: Float,
    val refractionAmount: Float,
    val depthEffect: Boolean,
)

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    override var renderEffect: RenderEffect? = null
    override var compositeColorFilter: ColorFilter? = null
    override var poolLens: PoolLens? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    // Vendored change: [contentScale] reports the size the effects actually operate
    // on when the backdrop layer is recorded at a reduced resolution, so shader
    // geometry (e.g. the lens size uniform) matches the layer's pixel grid.
    fun update(scope: DrawScope, contentScale: Float = 1f): Boolean {
        val newDensity = scope.density
        val newFontScale = scope.fontScale
        val newSize = if (contentScale != 1f) scope.size * contentScale else scope.size
        val newLayoutDirection = scope.layoutDirection

        val changed = newDensity != density ||
                newFontScale != fontScale ||
                newSize != size ||
                newLayoutDirection != layoutDirection

        if (changed) {
            density = newDensity
            fontScale = newFontScale
            size = newSize
            layoutDirection = newLayoutDirection
        }

        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        padding = 0f
        renderEffect = null
        compositeColorFilter = null
        poolLens = null
        effects()
    }

    fun reset() {
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        compositeColorFilter = null
        poolLens = null
        runtimeShaderCache.clear()
    }
}
