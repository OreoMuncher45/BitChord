/*
 * Copyright (C) 2026 BitChord
 *
 * The player's scrubber, driven by touch.
 *
 * This is the seek bar and the volume bar: `VolumeRow` and `PlayerScrubber` in
 * `PlayerControls.kt` are both `internal` to `:sharedUi` and neither can be
 * reached from a test, but they draw through `ThinSlider`, which is public and
 * is where the only decision the bar makes lives — where on the bar a finger
 * is, as a fraction of its width.
 *
 * It is tested here rather than left alone because it has already been wrong
 * once, and the fix is recorded in the file itself:
 *
 *   "Two separate detectors — a drag one plus a tap one — meant taps never
 *    landed: the drag detector took the pointer and a tap has no drag to
 *    report."
 *
 * That is the same shape of defect as the account button that shipped dead: a
 * pointer-input handler that looks correct and eats the gesture. It compiles
 * either way, it draws either way, and the only evidence is a control that does
 * nothing when touched. So each test below does what a finger does and checks
 * the number that came back.
 *
 * Robolectric cannot run on an aarch64 host — Conscrypt, which it loads by JNI,
 * ships no linux-aarch64 build — so this class is named to match the exclusion
 * in `app/build.gradle.kts` and runs for real on CI's x86_64.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.player.ThinSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// Not the manifest application: BitChordApplication.onCreate installs the
// stream hooks, whose class init loads innertubex's TokenProvider — published
// as Java 21 bytecode (class file 65) that a JDK 17 test worker's Robolectric
// sandbox cannot define, so every test in this class used to die with
// UnsupportedClassVersionError before a single assertion ran. The slider needs
// no app state, so the plain Application is used, exactly as the passing
// SongActionsMenuInteractionTest does.
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w411dp-h891dp-xhdpi",
)
class ThinSliderInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val reported = mutableListOf<Float>()
    private var finished = 0

    /**
     * A bar of a known width, so the fraction a touch reports can be stated
     * exactly rather than against whatever the device happens to be.
     */
    private fun setBar(width: Dp = 200.dp) {
        compose.setContent {
            Box(Modifier.width(width).testTag(BAR)) {
                ThinSlider(
                    value = 0f,
                    onValueChange = { reported += it },
                    onValueChangeFinished = { finished++ },
                )
            }
        }
    }

    private fun bar() = compose.onNodeWithTag(BAR)

    /**
     * The regression: a tap has to report where it landed. Before the single
     * gesture loop, the drag detector took the pointer and a tap produced
     * nothing at all — the bar looked right and did nothing, which is exactly
     * how a control ships dead without anybody noticing.
     */
    @Test
    fun aTapReportsTheFractionUnderTheFinger() {
        setBar()
        bar().performTouchInput { click(center) }

        assertEquals("a tap must report where it landed", 1, reported.size)
        assertEquals("halfway along the bar", 0.5f, reported.single(), TOLERANCE)
    }

    /** …and it must finish, or a caller seeking on release never hears about it. */
    @Test
    fun aTapFinishesTheGesture() {
        setBar()
        bar().performTouchInput { click(center) }

        assertEquals("one gesture, one finish", 1, finished)
    }

    /** Every position the finger crosses is reported, not only where it let go. */
    @Test
    fun aDragReportsEveryPositionItCrosses() {
        setBar()
        bar().performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            moveTo(Offset(width * 0.35f, height / 2f))
            moveTo(Offset(width * 0.6f, height / 2f))
            moveTo(Offset(width * 0.85f, height / 2f))
            up()
        }

        assertTrue("a drag must report more than its endpoints", reported.size >= 3)
        assertEquals("a drag starts where it was pressed", 0.1f, reported.first(), TOLERANCE)
        assertEquals("a drag ends where the finger let go", 0.85f, reported.last(), TOLERANCE)
        assertEquals(
            "every gesture finishes exactly once",
            1,
            finished,
        )
        // Monotone: a bar that reported a backwards jump mid-drag would be
        // seeking all over the track rather than following the finger.
        val sorted = reported.sorted()
        assertEquals("the reported fractions must follow the finger", sorted, reported)
    }

    /**
     * The mapping itself, across the bar. Tapping a spot is the only way a
     * person jumps to a position in a track, so this is the whole contract.
     */
    @Test
    fun theFractionFollowsTheFingerAcrossTheBar() {
        setBar()
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { fraction ->
            bar().performTouchInput {
                click(Offset(width * fraction, height / 2f))
            }
        }

        assertEquals(5, reported.size)
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEachIndexed { index, expected ->
            assertEquals(
                "tapping ${expected * 100}% along the bar must report that fraction",
                expected,
                reported[index],
                TOLERANCE,
            )
        }
        assertEquals("five gestures, five finishes", 5, finished)
    }

    /**
     * The two ends are the ends. A fraction outside 0..1 would be a seek
     * position outside the track, and both are reachable by a finger that lands
     * on the very edge of the drawn bar.
     */
    @Test
    fun theEndsOfTheBarAreZeroAndOne() {
        setBar()
        bar().performTouchInput { click(Offset(0f, height / 2f)) }
        bar().performTouchInput { click(Offset(width.toFloat(), height / 2f)) }

        assertEquals(0f, reported[0], TOLERANCE)
        assertEquals(1f, reported[1], TOLERANCE)
        assertTrue("no fraction may fall outside the track", reported.all { it in 0f..1f })
    }

    /**
     * A narrower bar is the same bar: the fraction is of the bar's own width,
     * not of the window's, so a volume slider that only spans part of the row
     * reports the same numbers as a seek bar that spans all of it.
     */
    @Test
    fun theFractionIsOfTheBarNotOfTheWindow() {
        setBar(width = 64.dp)
        bar().performTouchInput { click(Offset(width * 0.5f, height / 2f)) }

        assertEquals(
            "half of a 64dp bar is still half",
            0.5f,
            reported.single(),
            TOLERANCE,
        )
    }

    private companion object {
        const val BAR = "scrubber"
        /** A tap lands on a rounded pixel; the fraction is allowed to be that far out. */
        const val TOLERANCE = 0.02f
    }
}
