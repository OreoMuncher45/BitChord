/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.bitchord.ui.components.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import android.os.Build
import com.music.bitchord.ui.components.backdrop.internal.MultiRefractionMaxShapes
import com.music.bitchord.ui.components.backdrop.internal.MultiRefractionShaderString
import com.music.bitchord.ui.components.backdrop.internal.RuntimeShaderEffect
import com.music.bitchord.ui.components.backdrop.internal.chain
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop

/** Fraction of screen size the shared blur is computed at; the blur hides the upscale. */
// Lowered from 0.5 after scrolling profiles showed fewer janky frames.
// Recheck lens edge sharpness if changing it.
const val GlassPoolScale = 0.35f

/**
 * Requests refraction from the shared pool instead of chaining a per-surface lens. The pool
 * applies it for every sampler in one pass; this surface's own layer then carries no effect.
 */
fun BackdropEffectScope.poolLens(refractionHeight: Float, refractionAmount: Float, depthEffect: Boolean) {
    if (refractionHeight <= 0f || refractionAmount <= 0f) return
    poolLens = PoolLens(refractionHeight, refractionAmount, depthEffect)
}

/** Kept around every sampler's rect in the pool capture: covers the blur's reach and press stretch. */
private const val CoverageMarginDp = 32f

@Composable
fun rememberGlassPool(): LayerBackdrop = rememberLayerBackdrop().also { it.masksSamplers = true }

/**
 * Records [source] into [pooled], with [effects] applied, at [scale] of screen size.
 * Draws nothing itself: put it on a full-screen Box that sits above the content whose
 * blur it is, and it just keeps [pooled] current.
 */
fun Modifier.glassPool(
    source: Backdrop,
    pooled: LayerBackdrop,
    effects: BackdropEffectScope.() -> Unit,
    scale: Float,
): Modifier = this then GlassPoolElement(source, pooled, effects, scale.coerceIn(0.05f, 1f))

private class GlassPoolElement(
    val source: Backdrop,
    val pooled: LayerBackdrop,
    val effects: BackdropEffectScope.() -> Unit,
    val scale: Float,
) : ModifierNodeElement<GlassPoolNode>() {

    override fun create(): GlassPoolNode = GlassPoolNode(source, pooled, effects, scale)

    override fun update(node: GlassPoolNode) {
        node.update(source, pooled, effects, scale)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassPool"
        properties["scale"] = scale
    }

    override fun equals(other: Any?): Boolean =
        other is GlassPoolElement && source == other.source && pooled == other.pooled &&
            effects == other.effects && scale == other.scale

    override fun hashCode(): Int {
        var result = source.hashCode()
        result = 31 * result + pooled.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + scale.hashCode()
        return result
    }
}

private class GlassPoolNode(
    var source: Backdrop,
    var pooled: LayerBackdrop,
    var effects: BackdropEffectScope.() -> Unit,
    var scale: Float,
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    private val effectScope = object : BackdropEffectScopeImpl() {
        override val shape get() = androidx.compose.ui.graphics.RectangleShape
    }

    /** Holds the capture; the render effect lives here so it runs once, on the flatten below. */
    private var captureLayer: GraphicsLayer? = null

    private var coordinates: LayoutCoordinates? = null
    private var recordedSize: IntSize = IntSize.Zero
    private var recordedSourceVersion: Int? = null
    private var recordedScale = Float.NaN
    private var captureDirty = true

    /**
     * Area (pool-local px, unscaled) the samplers actually read, grown on demand through
     * [LayerBackdrop.coverageRequest] and never shrunk. The capture keeps its full-screen
     * size and coordinate space -- only its replay is clipped to this, so Skia quick-rejects
     * every op outside the bar region instead of re-rendering the whole screen. That full
     * second render (every op, just at a third of the pixels) was the dominant RenderThread
     * cost of glass: scale cuts GPU fill, not op submission.
     */
    private var region = Rect.Zero
    private var effectsReady = false

    /** Saturation + blur, before the samplers' refraction is chained on. */
    private var baseEffect: androidx.compose.ui.graphics.RenderEffect? = null

    private class LensTarget(val rect: Rect, val lens: PoolLens?, val radii: FloatArray?, val outline: Outline)

    /**
     * Every pooled sampler: its refraction, applied here in ONE pass instead of each sampler
     * promoting its own layer and running its own lens (two render passes per surface, four
     * surfaces on the bar), and its outline, which the flatten masks the glass to (see
     * [recordFlatten]). Keyed by the sampler's effect scope; rects are the exact regions the
     * samplers draw from, so refraction and silhouette land where each one shows them.
     */
    private val lensTargets = LinkedHashMap<Any, LensTarget>()

    private val lensRequest: (Any, Rect, PoolLens?, FloatArray?, Outline?) -> Unit = { key, rect, lens, radii, outline ->
        if (outline == null || rect.isEmpty) {
            if (lensTargets.remove(key) != null) {
                applyLens()
                recordFlatten()
            }
        } else {
            val old = lensTargets[key]
            val lensChanged = old == null || old.rect != rect || old.lens != lens ||
                !(old.radii contentEquals radii)
            val maskChanged = old == null || old.rect != rect || old.outline != outline
            if (lensChanged || maskChanged) {
                lensTargets[key] = LensTarget(rect, lens, radii, outline)
                if (lensChanged) applyLens()
                if (maskChanged) recordFlatten()
            }
        }
    }

    /**
     * Sets the capture's effect: the base chain plus the multi-shape refraction. A RenderNode
     * property, so a call from a sampler's own draw still lands in this frame.
     */
    private fun applyLens() {
        val capture = captureLayer ?: return
        val scale = recordedScale
        if (lensTargets.values.none { it.lens != null } || scale.isNaN() ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            capture.renderEffect = baseEffect
            return
        }
        val origin = region
        val max = MultiRefractionMaxShapes
        val rects = FloatArray(max * 4)
        val radii = FloatArray(max * 4)
        val lens = FloatArray(max * 4)
        var n = 0
        for (t in lensTargets.values) {
            if (n == max) break
            if (t.lens == null || t.radii == null) continue
            val o = n * 4
            // In capture pixels: the capture starts at the region's corner.
            rects[o] = (t.rect.left - origin.left) * scale
            rects[o + 1] = (t.rect.top - origin.top) * scale
            rects[o + 2] = (t.rect.right - origin.left) * scale
            rects[o + 3] = (t.rect.bottom - origin.top) * scale
            for (c in 0 until 4) radii[o + c] = t.radii[c] * scale
            lens[o] = t.lens.refractionHeight * scale
            lens[o + 1] = -t.lens.refractionAmount * scale
            lens[o + 2] = if (t.lens.depthEffect) 1f else 0f
            n++
        }
        val shader = effectScope.obtainRuntimeShader("MultiRefraction", MultiRefractionShaderString)
        shader.setFloatUniform("rects", rects)
        shader.setFloatUniform("radii", radii)
        shader.setFloatUniform("lens", lens)
        shader.setIntUniform("count", n)
        capture.renderEffect = baseEffect.chain(RuntimeShaderEffect(shader, "content"))
    }

    private val coverage: (Rect) -> Unit = coverage@{ rect ->
        if (!effectsReady || rect.isEmpty) return@coverage
        val margin = requireDensity().run { CoverageMarginDp.dp.toPx() }
        // Off-screen sampler rects (hidden tab row, morph overshoot) grew the region to ~1.4x
        // the screen width; the capture there is empty anyway, so clip to the pool's bounds.
        val bounds = coordinates?.size ?: return@coverage
        val want = rect.inflate(margin)
            .intersect(Rect(0f, 0f, bounds.width.toFloat(), bounds.height.toFloat()))
        if (want.isEmpty) return@coverage
        val r = region
        if (!r.isEmpty && r.left <= want.left && r.top <= want.top &&
            r.right >= want.right && r.bottom >= want.bottom
        ) return@coverage
        region = if (r.isEmpty) want else Rect(
            minOf(r.left, want.left), minOf(r.top, want.top),
            maxOf(r.right, want.right), maxOf(r.bottom, want.bottom),
        )
        // The layers now start at the new region's corner: the sampler asking reads the new
        // origin right after this call; the rest re-record on the version bump next frame.
        record()
        applyLens()
        pooled.geometryChanged()
    }

    fun update(
        source: Backdrop,
        pooled: LayerBackdrop,
        effects: BackdropEffectScope.() -> Unit,
        scale: Float,
    ) {
        if (this.source != source || this.pooled != pooled ||
            this.effects !== effects || this.scale != scale
        ) {
            captureDirty = true
        }
        this.source = source
        this.pooled = pooled
        this.effects = effects
        this.scale = scale
    }

    override fun ContentDrawScope.draw() {
        // Nothing of this node is visible; it exists to keep [pooled] current.
        val capture = captureLayer ?: return
        val coordinates = coordinates ?: return
        if (size.width <= 0f || size.height <= 0f) return

        val recordSize = IntSize(
            (size.width * scale).toInt().coerceAtLeast(1),
            (size.height * scale).toInt().coerceAtLeast(1),
        )
        // RenderNode references keep source content live; re-record only when its geometry changes.
        val sourceGeometry = (source as? LayerBackdrop)?.geometryVersion
        val sourceChanged = sourceGeometry == null || sourceGeometry != recordedSourceVersion
        if (!captureDirty && !sourceChanged && recordSize == recordedSize &&
            scale == recordedScale && pooled.hasRecording
        ) {
            return
        }

        effectScope.update(this, scale)
        effectScope.apply(effects)
        baseEffect = effectScope.renderEffect
        capture.colorFilter = effectScope.compositeColorFilter
        effectsReady = true

        recordedSize = recordSize
        recordedScale = scale
        applyLens()
        record()
        pooled.sourceScale = scale
        pooled.layerCoordinates = coordinates
        pooled.coverageRequest = coverage
        pooled.lensRequest = lensRequest
        pooled.contentRecorded()
        pooled.geometryChanged()

        recordedSourceVersion = sourceGeometry
        captureDirty = false
    }

    /**
     * Pool layers are sized to [region], not the screen: on a tiled GPU every pass loads and
     * stores its whole render target, so full-screen capture + flatten targets cost the
     * screen's pixels per pass even though only the bar region was ever drawn into them.
     */
    private fun regionSize(): IntSize {
        val clip = region
        if (clip.isEmpty || recordedScale.isNaN()) return IntSize(1, 1)
        return IntSize(
            ceil(clip.width * recordedScale).toInt().coerceAtLeast(1),
            ceil(clip.height * recordedScale).toInt().coerceAtLeast(1),
        )
    }

    private fun record() {
        val capture = captureLayer ?: return
        val coordinates = coordinates ?: return
        val scale = recordedScale
        val clip = region
        capture.record(requireDensity(), requireLayoutDirection(), regionSize()) {
            // Nothing sampled yet: record nothing rather than the whole screen.
            if (clip.isEmpty) return@record
            scale(scale, scale, Offset.Zero) {
                translate(-clip.left, -clip.top) {
                    with(source) {
                        drawBackdrop(
                            density = effectScope,
                            coordinates = coordinates,
                            layerBlock = null,
                        )
                    }
                }
            }
        }
        pooled.sourceOrigin = if (clip.isEmpty) Offset.Zero else clip.topLeft
        recordFlatten()
    }

    /** Draws the blur once into an offscreen texture shared by all samplers; outlines are filled here to avoid per-frame path clipping. */
    private fun recordFlatten() {
        val capture = captureLayer ?: return
        if (recordedScale.isNaN()) return
        val scale = recordedScale
        val clip = region
        val targets = lensTargets.values.toList()
        capture.blendMode = BlendMode.SrcIn
        pooled.graphicsLayer.compositingStrategy =
            androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        pooled.graphicsLayer.record(requireDensity(), requireLayoutDirection(), regionSize()) {
            // No registered outline, nothing to show: every pooled sampler relies on this
            // mask for its silhouette, so an unmasked frame would flash the glass as a full
            // bright rectangle (seen mid inline/expanded transition).
            if (clip.isEmpty || targets.isEmpty()) return@record
            run {
                scale(scale, scale, Offset.Zero) {
                    translate(-clip.left, -clip.top) {
                        for (t in targets) {
                            translate(t.rect.left, t.rect.top) { drawOutline(t.outline, Color.Black) }
                        }
                    }
                }
            }
            drawLayer(capture)
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            if (this.coordinates != coordinates) captureDirty = true
            this.coordinates = coordinates
        }
    }

    override fun onAttach() {
        captureLayer = requireGraphicsContext().createGraphicsLayer()
        captureDirty = true
    }

    override fun onDetach() {
        captureLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        captureLayer = null
        if (pooled.coverageRequest === coverage) pooled.coverageRequest = null
        if (pooled.lensRequest === lensRequest) pooled.lensRequest = null
        lensTargets.clear()
        baseEffect = null
        region = Rect.Zero
        pooled.sourceOrigin = Offset.Zero
        effectsReady = false
        coordinates = null
        recordedSize = IntSize.Zero
        recordedScale = Float.NaN
        recordedSourceVersion = null
        captureDirty = true
        effectScope.reset()
    }
}
