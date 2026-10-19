/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.music.bitchord.ui.components.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.LayoutDirection
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.BackdropEffectScope
import com.music.bitchord.ui.components.backdrop.PoolLens
import com.music.bitchord.ui.components.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /**
     * Changes after the source layer is recorded. Samplers can read this plain
     * field during drawing without creating a snapshot invalidation loop.
     * Their recorded layers reference the source RenderNode, so content updates
     * appear without re-recording each sampler.
     */
    internal var contentVersion: Int = 0
        private set

    /** Called by the recording node after a successful record. */
    internal fun contentRecorded() {
        if (contentVersion == 0) geometryChanged()
        contentVersion++
    }

    /**
     * Changes when a sampler's source geometry changes, so its layer must be
     * recorded again. Content redraws update the referenced RenderNode directly.
     */
    internal var geometryVersion: Int = 0
        private set

    internal fun geometryChanged() {
        geometryVersion++
    }

    /**
     * Regions declared static THIS generation by [backdropStaticRegion] children,
     * in this backdrop's own coordinate space. Plain list, not snapshot state, for
     * the same draw-phase-write reason as [contentVersion]. Cleared and rebuilt by
     * [LayerBackdropNode.draw] every time the source re-records, so a stale
     * declaration from a since-removed or since-changed composable never lingers
     * past the generation it was made in.
     */
    internal val staticRegions = ArrayList<Rect>()

    /**
     * True if [rect] (in this backdrop's coordinate space) falls entirely inside a
     * single region declared static this generation. Deliberately checks a single
     * covering rect rather than the union of several: a target only partly covered
     * by static declarations is treated as unstable rather than pulled into
     * region-union math, so a miss only costs a skipped optimization, never a
     * stale pixel on a sampling glass surface.
     */
    internal fun isFullyStatic(rect: Rect): Boolean =
        staticRegions.any { region ->
            region.left <= rect.left && region.top <= rect.top &&
                region.right >= rect.right && region.bottom >= rect.bottom
        }

    /**
     * True once the source has been recorded at least once. Freezing before that would
     * leave sampling surfaces replaying an empty layer.
     */
    internal val hasRecording: Boolean get() = contentVersion > 0

    /**
     * Fraction of screen resolution [graphicsLayer] was recorded at. A pooled glass
     * source (see `glassPool`) records the whole screen once, already blurred, at a
     * fraction of its size; sampling surfaces still work in full-screen coordinates,
     * so the offset is converted here and the layer drawn back up to size.
     */
    internal var sourceScale: Float = 1f

    /**
     * Where [graphicsLayer]'s top-left sits in the source's coordinate space (unscaled px). A
     * pooled source records only the region its samplers read, so its layer starts there.
     */
    internal var sourceOrigin: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero

    /**
     * Set by a pooled source (see `glassPool`) that records only the area its samplers
     * actually read. Called with each sampler's rect, in this backdrop's own coordinate
     * space, before it draws — the pool grows its recorded area on the spot if needed.
     */
    internal var coverageRequest: ((Rect) -> Unit)? = null

    /**
     * Set by a pooled source that refracts its samplers itself. Called with the sampler's
     * key, the exact rect it samples (so the refraction lands where it is drawn), its
     * [PoolLens] and corner radii -- or nulls to drop it.
     */
    internal var lensRequest: ((key: Any, rect: Rect, lens: PoolLens?, radii: FloatArray?, outline: Outline?) -> Unit)? = null

    /**
     * True for a pooled source that masks its samplers to their own outlines, so they need
     * no shape clip of their own. Fixed at creation (see `rememberGlassPool`).
     */
    internal var masksSamplers: Boolean = false

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        val sourceScale = sourceScale
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // TODO: Account for outer transforms in this fallback calculation.
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            val sampled = Rect(offset, coordinates.size.toSize())
            coverageRequest?.invoke(sampled)
            lensRequest?.let { request ->
                val scope = density as? BackdropEffectScope
                val lens = scope?.poolLens
                val radii = if (lens != null) scope.cornerRadii() else null
                val outline = scope?.let { it.shape.createOutline(it.size, it.layoutDirection, it) }
                request(scope ?: coordinates, sampled, lens, radii, outline)
            }
            // Read after the requests above: a coverage request may just have moved it.
            val origin = sourceOrigin
            translate(origin.x - offset.x, origin.y - offset.y)
            if (sourceScale != 1f) {
                // Concatenated after the translate, so a layer pixel p lands at p / scale
                // - offset: scale up to screen space first, then shift. The offset is in
                // screen pixels and must not be scaled itself.
                scale(1f / sourceScale, 1f / sourceScale, androidx.compose.ui.geometry.Offset.Zero)
            }
        }) {
            drawLayer(graphicsLayer)
        }
    }

    /** Physical-corner radii (TL, TR, BR, BL) of the sampler's shape, as the lens shader wants. */
    private fun BackdropEffectScope.cornerRadii(): FloatArray {
        val size = size
        val max = size.minDimension / 2f
        val shape = shape as? CornerBasedShape ?: return FloatArray(4) { max }
        val ltr = layoutDirection == LayoutDirection.Ltr
        val ts = shape.topStart.toPx(size, this).coerceAtMost(max)
        val te = shape.topEnd.toPx(size, this).coerceAtMost(max)
        val be = shape.bottomEnd.toPx(size, this).coerceAtMost(max)
        val bs = shape.bottomStart.toPx(size, this).coerceAtMost(max)
        return if (ltr) floatArrayOf(ts, te, be, bs) else floatArrayOf(te, ts, bs, be)
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
