/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.bitchord.ui.components.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.toSize

/**
 * Marks this composable's bounds unchanged for the current backdrop generation.
 * Glass samplers may reuse a capture only when it falls entirely inside a declared
 * region. Partial or undeclared areas are invalidated; overlapping changing content
 * must not be marked static.
 */
fun Modifier.backdropStaticRegion(backdrop: LayerBackdrop): Modifier =
    this then BackdropStaticRegionElement(backdrop)

private class BackdropStaticRegionElement(
    val backdrop: LayerBackdrop
) : ModifierNodeElement<BackdropStaticRegionNode>() {

    override fun create(): BackdropStaticRegionNode = BackdropStaticRegionNode(backdrop)

    override fun update(node: BackdropStaticRegionNode) {
        node.backdrop = backdrop
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "backdropStaticRegion"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackdropStaticRegionElement) return false
        return backdrop == other.backdrop
    }

    override fun hashCode(): Int = backdrop.hashCode()
}

private class BackdropStaticRegionNode(
    var backdrop: LayerBackdrop
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    private var coordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates.takeIf { it.isAttached }
    }

    override fun ContentDrawScope.draw() {
        val self = coordinates
        val source = backdrop.layerCoordinates
        if (self != null && source != null) {
            try {
                val topLeft = source.localPositionOf(self)
                backdrop.staticRegions.add(Rect(topLeft, self.size.toSize()))
            } catch (_: Exception) {
                // Position unavailable this frame: simply don't declare. The
                // fail-safe default (always record) applies as if undeclared.
            }
        }
        drawContent()
    }

    override fun onDetach() {
        coordinates = null
    }
}
