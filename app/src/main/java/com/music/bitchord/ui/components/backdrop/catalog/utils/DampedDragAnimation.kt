/*
 * Copyright 2026, compose-miuix-ui contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported one-to-one from the reference app's DampedDragAnimation. The only Convx extension is
 * snapValue(), used when an external pager already owns the position animation;
 * it is not used by pointer drag/press/release behavior.
 */
package com.music.bitchord.ui.components.backdrop.catalog.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * Keeps the logical tab position, pressed morphology, and normalized drag velocity in sync.
 * Callers translate the dimensionless [value] into pixels for their own layout.
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private var pressJob: Job? = null
    private var releaseJob: Job? = null
    private var settleJob: Job? = null
    private val velocityTracker = VelocityTracker()
    private val startMark = TimeSource.Monotonic.markNow()

    private fun nowMillis(): Long = startMark.elapsedNow().inWholeMilliseconds

    val value: Float get() = valueAnimation.value
    val progress: Float
        get() = (value - valueRange.start) /
            (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            val isInside = canDrag(change.position)
            val wasInside = canDrag(change.previousPosition)
            if (isInside && wasInside) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        releaseJob?.cancel()
        pressJob?.cancel()
        velocityTracker.resetTracking()
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        releaseJob?.cancel()
        pressJob?.cancel()
        releaseJob = animationScope.launch {
            withFrameMillis { }
            // Cap the wait so drag input during release cannot hold the stretch indefinitely.
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                withTimeoutOrNull(400) {
                    snapshotFlow { valueAnimation.value }
                        .first { abs(it - valueAnimation.targetValue) < threshold }
                }
            }
            launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /**
     * Drag path: the finger owns the position, so the event snaps state
     * synchronously instead of queueing an `animateTo` per delta behind the
     * in-flight frame (which lagged the puck under fast drags). Settle springs
     * run only on release / programmatic taps ([animateToValue]).
     */
    fun updateValue(value: Float) {
        val coerced = if (value.isFinite()) value.coerceIn(valueRange) else valueAnimation.value
        // Snap, don't animate: one synchronous state write per drag event, no queue.
        animationScope.launch {
            valueAnimation.snapTo(coerced)
            snapVelocity()
        }
    }

    /**
     * Tap path: single-flight. Rapid taps cancel the previous settle instead of
     * queueing behind it in [mutatorMutex] (which stranded pressProgress at 1 and
     * left the puck at 1.39x scale). The finally guarantees the pressed
     * morphology always deflates, even when a newer tap supersedes this one.
     */
    fun animateToValue(value: Float) {
        settleJob?.cancel()
        settleJob = animationScope.launch {
            mutatorMutex.mutate {
                var released = false
                try {
                    press()
                    val targetValue = if (value.isFinite()) value.coerceIn(valueRange) else valueAnimation.value
                    valueAnimation.animateTo(targetValue, valueAnimationSpec)
                    if (velocityAnimation.value != 0f) {
                        velocityAnimation.animateTo(0f, velocityAnimationSpec)
                    }
                    release()
                    released = true
                } finally {
                    // Cancelled mid-flight by a newer tap: release() never ran, so
                    // deflate directly without awaiting settle. On the normal path
                    // release() already owns the deflate — no duplicate.
                    if (!released &&
                        (pressProgressAnimation.value != 0f ||
                            scaleXAnimation.value != initialScale ||
                            scaleYAnimation.value != initialScale)
                    ) {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
                        launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
                    }
                }
            }
        }
    }

    /** Convx pager extension; pointer drag behavior remains identical to the reference app. */
    suspend fun snapValue(value: Float) {
        valueAnimation.snapTo(if (value.isFinite()) value.coerceIn(valueRange) else valueAnimation.value)
    }

    private suspend fun snapVelocity() {
        velocityTracker.addPosition(nowMillis(), Offset(valueAnimation.value, 0f))
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
        val raw = velocityTracker.calculateVelocity().x / span
        velocityAnimation.snapTo(if (raw.isFinite()) raw else 0f)
    }
}
