/*
 * Copyright (C) 2026 BitChord
 *
 * Interaction tests for the Apple Music shell's chrome.
 *
 * These exist because the shell's account button and mini player were both
 * shipped dead — the account button because a drag detector stacked ahead of
 * `clickable` consumed the down event, and neither was caught because every
 * other test in this module is pure JVM logic that never renders a composable.
 * A dead callback compiles exactly as well as a live one.
 *
 * Each test does what a person does: touch the control, and check the callback
 * ran.
 */

package com.music.bitchord.ui.apple

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppleChromeInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    // Robolectric loads Conscrypt's native library by JNI during sandbox setup,
    // and Conscrypt ships no linux-aarch64 build, so on aarch64 every test here
    // dies with UnsatisfiedLinkError before reaching an assertion — which is
    // why the exclusion lives in build.gradle.kts rather than in an assumption
    // here: the sandbox is set up before any @Before runs.

    private val song = Song(
        videoId = "abc123",
        title = "Test Track",
        artist = "Test Artist",
        thumbnailUrl = null,
    )

    private val songTitle: String get() = requireNotNull(song.title)

    /** Tapping the mini player must raise the full player. */
    @Test
    fun miniPlayerTapOpensNowPlaying() {
        var expanded = false
        compose.setContent {
            AppleMusicTheme(darkTheme = false) {
                Box(Modifier) {
                    AppleMiniPlayer(
                        song = song,
                        isPlaying = false,
                        isLoading = false,
                        onPlayPause = {},
                        onNext = {},
                        onExpand = { expanded = true },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Now playing: $songTitle")
            .performClick()
        compose.waitForIdle()

        assertTrue("tapping the mini player must raise the full player", expanded)
    }

    /** The transport controls must not open the player as well. */
    @Test
    fun miniPlayerTransportDoesNotOpenNowPlaying() {
        var expanded = false
        var toggled = false
        compose.setContent {
            AppleMusicTheme(darkTheme = false) {
                AppleMiniPlayer(
                    song = song,
                    isPlaying = false,
                    isLoading = false,
                    onPlayPause = { toggled = true },
                    onNext = {},
                    onExpand = { expanded = true },
                )
            }
        }

        compose.onNodeWithContentDescription("Play").performClick()
        compose.waitForIdle()

        assertTrue("the play control must act", toggled)
        assertFalse("the play control must not also raise the player", expanded)
    }

    /**
     * The account button opens its own menu rather than jumping straight to
     * Settings — Settings is one of the menu's rows, not the button's action.
     */
    @Test
    fun accountButtonOpensMenuRatherThanSettings() {
        var settingsOpened = 0
        compose.setContent {
            AppleMusicTheme(darkTheme = false) {
                AppleTopBar(
                    title = "Home",
                    onBack = null,
                    onOpenAccount = { settingsOpened++ },
                    avatarUrl = null,
                    collapse = 0f,
                    accountActions = listOf(
                        AppleAccountAction("Settings") { settingsOpened++ },
                        AppleAccountAction("Listen Together") {},
                    ),
                )
            }
        }

        compose.onNodeWithText("Settings").assertDoesNotExist()

        compose.onNodeWithContentDescription("Account").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Listen Together").assertIsDisplayed()
        assertEquals(
            "opening the menu must not navigate anywhere on its own",
            0,
            settingsOpened,
        )

        // And the row is live.
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        assertEquals(1, settingsOpened)
    }

    /** With no rows configured the avatar is a plain button into Settings. */
    @Test
    fun accountButtonFallsBackToSettingsWhenNoActions() {
        var settingsOpened = 0
        compose.setContent {
            AppleMusicTheme(darkTheme = false) {
                AppleTopBar(
                    title = "Home",
                    onBack = null,
                    onOpenAccount = { settingsOpened++ },
                    avatarUrl = null,
                    collapse = 0f,
                )
            }
        }

        compose.onNodeWithContentDescription("Account").performClick()
        compose.waitForIdle()
        assertEquals(1, settingsOpened)
    }

    /** Swiping the avatar steps accounts, and does not open the menu. */
    @Test
    fun accountSwipeStepsAccountsWithoutOpeningMenu() {
        var stepped = 0
        compose.setContent {
            AppleMusicTheme(darkTheme = false) {
                AppleTopBar(
                    title = "Home",
                    onBack = null,
                    onOpenAccount = {},
                    avatarUrl = null,
                    collapse = 0f,
                    onSwipeProfile = { stepped++; true },
                    accountActions = listOf(AppleAccountAction("Settings") {}),
                )
            }
        }

        compose.onNodeWithContentDescription("Account").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals("one swipe steps one account", 1, stepped)
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }

    /** The back button is a plain click: no swipe handler to eat it. */
    @Test
    fun backButtonClickWorks() {
        var backs = 0
        compose.setContent {
            AppleMusicTheme(darkTheme = false) {
                AppleTopBar(
                    title = "Album",
                    onBack = { backs++ },
                    onOpenAccount = {},
                    avatarUrl = null,
                    collapse = 0f,
                )
            }
        }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
    }
}
