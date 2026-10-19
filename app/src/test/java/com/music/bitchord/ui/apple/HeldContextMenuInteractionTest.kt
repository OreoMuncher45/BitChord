/*
 * Copyright (C) 2026 BitChord
 *
 * The held context menu, driven by touch and by the back gesture.
 *
 * This menu replaced a bottom sheet for a hold, and the sheet's rows did not
 * come with it — that is the failure this file exists to make impossible again.
 * A menu that renders correctly, sits in the right place, is the right shape,
 * and does nothing when tapped is a shipped defect that compiles cleanly and
 * shows up in no log. It has happened twice in this project's history.
 *
 * The surface was recently changed to liquid glass and given a morph out of the
 * press origin. That was a *surface* change and it was supposed to change
 * nothing else: same rows, same callbacks, same dismissal. So this file tests
 * the behaviour, and the pure-logic `HeldMenuMorphTest` alongside it tests the
 * geometry the morph was built from — between them they are the two halves of
 * what the change could have broken.
 *
 * The menu itself owns no rows. It takes them as a composable slot, and what it
 * owns is what happens around them: the tap that dismisses, the back that
 * dismisses, the lifted item that opens what it stands for, and whether a row
 * can be reached at all. Those are what is checked here. The rows themselves are
 * checked where they are drawn, in `SongActionsMenuInteractionTest`.
 *
 * Robolectric cannot run on an aarch64 host — Conscrypt, which it loads by JNI,
 * publishes no linux-aarch64 build — so this class is named to match the
 * exclusion in `app/build.gradle.kts` and runs for real on CI's x86_64.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.ActionRow
import com.music.bitchord.ui.components.ActionRowStyle
import com.music.bitchord.ui.components.HeldContextMenu
import com.music.bitchord.ui.components.HeldItem
import com.music.bitchord.ui.components.LocalActionRowStyle
import com.music.bitchord.ui.components.LocalAppBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.emptyBackdrop
import dev.chrisbanes.haze.HazeState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    // The real Application opens an encrypted keystore, a WebSocket and a
    // thread pool before a single line of UI is drawn, none of which a unit
    // test wants and all of which would decide whether this file passes for
    // reasons that have nothing to do with the menu.
    application = Application::class,
    // Tall enough that every row is on screen, so a row is never reached by
    // accident through a scroll rather than through its own click.
    qualifiers = "w411dp-h891dp-xhdpi",
)
class HeldContextMenuInteractionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val hazeState = HazeState()

    private var ranFirst = 0
    private var ranSecond = 0
    private var dismissed = 0
    private var previewed: String? = null
    private var shown: String? = null

    /**
     * Where the finger was when the item was held, in window pixels — well
     * inside the window so the lifted card is neither clamped nor scaled, and
     * high enough that the menu opens below it with room to spare.
     */
    private val heldBounds = Rect(left = 200f, top = 800f, right = 600f, bottom = 1_000f)

    /**
     * Both switches the menu honours, restored afterwards because they are
     * process-wide and the tests in a JVM share them.
     */
    private var reduceAnimation = false
    private var reduceDynamicBlur = false

    @Before
    fun pinMotionSettings() {
        reduceAnimation = AppSettings.reduceAnimation.value
        reduceDynamicBlur = AppSettings.reduceDynamicBlur.value
        // The entrance becomes a plain 140ms fade instead of an under-damped
        // spring. Nothing here is about the animation, and a spring that has
        // not settled makes every "is it open yet" question in these tests a
        // question about timing rather than about the menu.
        AppSettings.reduceAnimation.value = true
        // No Haze. The blurred scrim samples a backdrop layer that no test page
        // has recorded, and asking it to is a test of Haze rather than of the
        // menu. The plain scrim is the same menu with the blur switched off by
        // the listener, and it is a branch the menu has to keep working.
        AppSettings.reduceDynamicBlur.value = true
    }

    @After
    fun restoreMotionSettings() {
        AppSettings.reduceAnimation.value = reduceAnimation
        AppSettings.reduceDynamicBlur.value = reduceDynamicBlur
    }

    /**
     * A [HeldItem] stands for what was on screen when the finger went down, and
     * the class records exactly that: a rectangle. Its constructor is internal
     * to `:sharedUi`, so it cannot be written out from here — it is built
     * through the constructor instead, which is public in the bytecode that
     * Kotlin's `internal` is a compile-time rule about and nothing more.
     *
     * That is a deliberate shortcut, and it skips a real path: in the app a
     * `HeldItem` is what a *hold* leaves behind, recorded by `LongPressOrigin`
     * and carrying a snapshot of the item's own drawing. `longPressMenuClickable`
     * is public and could be driven instead, but that path builds a graphics
     * layer and rasterises the item to a bitmap, which is a test of the
     * snapshot machinery rather than of the menu. So what is under test here is
     * everything from "the menu has a held item" onwards.
     */
    private fun heldItem(bounds: Rect = heldBounds): HeldItem {
        val constructor = HeldItem::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(bounds) as HeldItem
    }

    private fun setMenu(
        item: String? = ITEM,
        held: HeldItem? = heldItem(),
        onPreviewClick: ((String) -> Unit)? = { previewed = it },
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAppBackdrop provides emptyBackdrop()) {
                // A page for the menu to sit over, as it always does. It is
                // also what a tap on "outside" would land on if the menu's own
                // scrim were not there to catch it first.
                Column(Modifier.fillMaxSize()) {
                    HeldContextMenu(
                        item = item,
                        held = held,
                        hazeState = hazeState,
                        onDismiss = { dismissed++ },
                        onPreviewClick = onPreviewClick,
                    ) { menuItem ->
                        shown = menuItem
                        CompositionLocalProvider(
                            LocalActionRowStyle provides ActionRowStyle.Menu,
                        ) {
                            Column {
                                ActionRow(
                                    icon = Icons.Rounded.Add,
                                    label = ROW_ONE,
                                ) { ranFirst++ }
                                ActionRow(
                                    icon = Icons.Rounded.Add,
                                    label = ROW_TWO,
                                ) { ranSecond++ }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The menu opens out of the item that was held, so it hands that item to
     * whoever supplied its rows. A menu that opened with the wrong item would
     * offer the right actions on the wrong song.
     */
    @Test
    fun theRowsAreGivenTheItemThatWasHeld() {
        setMenu()
        compose.waitForIdle()

        compose.onNodeWithText(ROW_ONE).assertIsDisplayed()
        assertEquals(ITEM, shown)
    }

    /** A row's click runs the action it was given. The point of the file. */
    @Test
    fun aRowsClickRunsItsAction() {
        setMenu()
        compose.waitForIdle()

        compose.onNodeWithText(ROW_ONE).performClick()
        compose.waitForIdle()

        assertEquals("the row's own callback must run", 1, ranFirst)
        assertEquals("…and only that row's", 0, ranSecond)
    }

    /** Every row answers for itself, and no two rows share a callback by accident. */
    @Test
    fun everyRowAnswersForItself() {
        setMenu()
        compose.waitForIdle()

        compose.onNodeWithText(ROW_ONE).performClick()
        compose.onNodeWithText(ROW_TWO).performClick()
        compose.onNodeWithText(ROW_TWO).performClick()
        compose.waitForIdle()

        assertEquals(1, ranFirst)
        assertEquals(2, ranSecond)
        assertEquals("no row may dismiss the menu on its own", 0, dismissed)
    }

    /**
     * The page behind the menu is the way out, and it is the way the sheet
     * before it was dismissed. The scrim is a full-screen clickable painted
     * under the card and the menu, so a tap that misses both lands on it.
     */
    @Test
    fun aTapOutsideTheMenuDismissesIt() {
        setMenu()
        compose.waitForIdle()

        // The top-left corner: the lifted card is centred on the held bounds
        // lower down the window, and the menu opens below that.
        compose.onRoot().performTouchInput { click(OUTSIDE) }
        compose.waitForIdle()

        assertEquals("a tap on the page behind the menu must dismiss it", 1, dismissed)
    }

    /**
     * And the back gesture does the same thing — the other way out of a menu,
     * and the one that has to keep working now that the menu is a layout of its
     * own rather than a sheet the platform dismisses for it.
     */
    @Test
    fun backDismissesTheMenu() {
        setMenu()
        compose.waitForIdle()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertEquals("back must dismiss the menu", 1, dismissed)
    }

    /**
     * Tapping the lifted item opens what it stands for. It is a separate
     * gesture from its rows and a separate target: the picture of the item is
     * drawn above the menu and catches the tap first, so a `clickable` that was
     * put on the wrong one of the two would leave the other dead.
     */
    @Test
    fun tappingTheLiftedItemOpensWhatItStandsFor() {
        setMenu()
        compose.waitForIdle()

        // The card is drawn unscaled and centred on the item that was held.
        compose.onRoot().performTouchInput {
            click(
                Offset(
                    (heldBounds.left + heldBounds.right) / 2f,
                    (heldBounds.top + heldBounds.bottom) / 2f,
                ),
            )
        }
        compose.waitForIdle()

        assertEquals("the lifted item must open what it stands for", ITEM, previewed)
        assertEquals("…without also running a row's action", 0, ranFirst)
    }

    /**
     * A menu that has been dismissed takes its rows with it. Nothing here is
     * asserting that the rows are merely inert on the way out — that the menu
     * is gone, which is what makes a second tap somewhere harmless.
     */
    @Test
    fun aDismissedMenuStopsOfferingItsRows() {
        val item = mutableStateOf<String?>(ITEM)
        val held = mutableStateOf<HeldItem?>(heldItem())
        var dismissals = 0
        var actions = 0

        compose.setContent {
            CompositionLocalProvider(LocalAppBackdrop provides emptyBackdrop()) {
                Column(Modifier.fillMaxSize()) {
                    HeldContextMenu(
                        item = item.value,
                        held = held.value,
                        hazeState = hazeState,
                        onDismiss = {
                            dismissals++
                            item.value = null
                            held.value = null
                        },
                        onPreviewClick = null,
                    ) {
                        ActionRow(icon = Icons.Rounded.Add, label = ROW_ONE) { actions++ }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(ROW_ONE).assertIsDisplayed()

        compose.onRoot().performTouchInput { click(OUTSIDE) }
        compose.waitForIdle()

        assertEquals(1, dismissals)
        compose.onNodeWithText(ROW_ONE).assertDoesNotExist()
        assertEquals("a dismissed menu must not run a row behind the dismisser's back", 0, actions)
    }

    /**
     * Without an item there is nothing to lift and nothing to offer: a menu
     * wired to render rows against a null item is how a dismissed menu throws
     * on its way out instead of quietly disappearing.
     */
    @Test
    fun aMenuWithNothingHeldRendersNothing() {
        setMenu(item = null, held = null)
        compose.waitForIdle()

        compose.onNodeWithText(ROW_ONE).assertDoesNotExist()
        compose.onNodeWithText(ROW_TWO).assertDoesNotExist()
    }

    private companion object {
        const val ITEM = "Test Track"
        const val ROW_ONE = "Play next"
        const val ROW_TWO = "Add to queue"

        /** Top-left of the window, clear of both the lifted card and the menu. */
        val OUTSIDE = Offset(24f, 24f)
    }
}
