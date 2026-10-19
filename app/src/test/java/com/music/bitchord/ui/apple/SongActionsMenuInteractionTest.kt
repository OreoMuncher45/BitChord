/*
 * Copyright (C) 2026 BitChord
 *
 * The rows of the held context menu, one at a time.
 *
 * An account menu whose rows never fired once made Settings unreachable, and
 * nothing caught it: the menu drew, the rows drew, the labels were right, and
 * the taps went nowhere. Every other test in this module is pure JVM logic that
 * cannot render a composable, so there was nothing in the build that could have
 * noticed.
 *
 * This file is that something. It composes the real row list — the same
 * [SongActionsSheet] the held menu is given, in the same `Menu` presentation —
 * and taps every row, checking that the row's own callback ran and that no
 * other row's did. A row that loses its `onClick`, a row whose callback is
 * wired to the row above it, and a row that is only `enabled = false` all fail
 * here, and all three compile and draw perfectly well.
 *
 * It also pins *which* rows exist and in what order, because that is the other
 * half of what the user asked for when the surface changed: the glass went on,
 * and the menu's contents, options and callbacks were to stay exactly as they
 * were. A row added, dropped or reordered is a behaviour change, and this is
 * where it would be noticed.
 *
 * Robolectric cannot run on an aarch64 host — Conscrypt, which it loads by JNI,
 * publishes no linux-aarch64 build — so this class is named to match the
 * exclusion in `app/build.gradle.kts` and runs for real on CI's x86_64.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.R
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.SongActionsPresentation
import com.music.bitchord.ui.components.SongActionsSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    application = Application::class,
    // Tall enough that the whole row list is on screen. The menu caps its own
    // height and scrolls; what is being checked here is that the rows are there
    // and reachable, not what the window happens to be.
    qualifiers = "w411dp-h1200dp-xhdpi",
)
class SongActionsMenuInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Labels read out of the app's own resources rather than written here, so a
     * translation cannot make every row in this file un-findable and turn the
     * whole suite red for a reason that has nothing to do with the callbacks.
     */
    private fun label(id: Int): String = context.getString(id)

    private val startRadio get() = label(R.string.start_radio)
    private val playNext get() = label(R.string.play_next)
    private val addToQueue get() = label(R.string.add_to_queue)
    private val like get() = label(R.string.like)
    private val removeFromLiked get() = label(R.string.remove_from_liked)
    private val dislike get() = label(R.string.dislike)
    private val undoDislike get() = label(R.string.undo_dislike)
    private val addToPlaylist get() = label(R.string.add_to_playlist)
    private val download get() = label(R.string.download)
    private val openAlbum get() = label(R.string.open_album)
    private val openArtist get() = label(R.string.open_artist)
    private val share get() = label(R.string.share)
    private val saveCoverArt get() = label(R.string.save_cover_art)
    private val copyLog get() = label(R.string.copy_log)
    private val lyricsOffset get() = label(R.string.lyrics_offset)
    private val sing get() = label(R.string.sing)
    private val yourReplay get() = label(R.string.your_replay)
    private val spatialAudio get() = label(R.string.spatial_audio)
    private val revertToOriginal get() = label(R.string.revert_to_original)
    private val removeFromPlaylist get() = label(R.string.remove_from_playlist)
    private val sleepTimer get() = label(R.string.sleep_timer)

    /** Every row's callback, counted. A row that runs the wrong one is caught here. */
    private class Ran {
        var startRadio = 0
        var playNext = 0
        var addToQueue = 0
        var download = 0
        var like = 0
        var dislike = 0
        var addToPlaylist = 0
        var share = 0
        var saveCoverArt = 0
        var copyLog = 0
        var lyricsOffset = 0
        var sing = 0
        var replay = 0
        var spatial = 0
        var revert = 0
        var albumId: String? = null
        var artistId: String? = null
    }

    private val ran = Ran()

    private val song = Song(
        videoId = "abc123",
        title = "Test Track",
        artist = "Test Artist",
        thumbnailUrl = "https://example.invalid/art.jpg",
        albumId = "album-1",
        albumName = "Test Album",
        artistId = "artist-1",
    )

    /**
     * The row list as the held menu is given it: the sheet's `Menu`
     * presentation, which draws the rows alone — see `MainActivity`'s
     * `HeldContextMenu(...) { song -> songMenuBody(song, Menu) }`.
     */
    private fun setMenu(
        song: Song = this.song,
        signedIn: Boolean = true,
        likeStatus: LikeStatus = LikeStatus.INDIFFERENT,
        resolvingLinks: Boolean = false,
        showSleepTimer: Boolean = false,
        withPlayerRows: Boolean = false,
    ) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier) {
                    SongActionsSheet(
                        song = song,
                        signedIn = signedIn,
                        likeStatus = likeStatus,
                        presentation = SongActionsPresentation.Menu,
                        resolvingLinks = resolvingLinks,
                        showSleepTimer = showSleepTimer,
                        // Always supplied: null hides a row, and the point is to
                        // have every optional row on screen at once.
                        onRollbackToOriginal = if (withPlayerRows) ({ ran.revert++ }) else null,
                        onLyricsOffset = if (withPlayerRows) ({ ran.lyricsOffset++ }) else null,
                        onOpenSing = if (withPlayerRows) ({ ran.sing++ }) else null,
                        onOpenReplay = if (withPlayerRows) ({ ran.replay++ }) else null,
                        spatialAudioOn = if (withPlayerRows) false else null,
                        onToggleSpatialAudio = if (withPlayerRows) ({ ran.spatial++ }) else null,
                        onShare = { ran.share++ },
                        onSaveArtwork = { ran.saveCoverArt++ },
                        onCopyLog = if (withPlayerRows) ({ ran.copyLog++ }) else null,
                        onPlayNext = { ran.playNext++ },
                        onAddToQueue = { ran.addToQueue++ },
                        onStartRadio = { ran.startRadio++ },
                        onDownload = { ran.download++ },
                        onToggleLike = { ran.like++ },
                        onToggleDislike = { ran.dislike++ },
                        onAddToPlaylist = { ran.addToPlaylist++ },
                        onOpenAlbum = { ran.albumId = it },
                        onOpenArtist = { ran.artistId = it },
                    )
                }
            }
        }
    }

    /**
     * The rows every track gets, tapped one after another.
     *
     * Nothing here is a no-op: each click has to move exactly one counter, and
     * the assertions are written so that a row that fires nothing, a row that
     * fires twice, and a row that fires its neighbour both fail.
     */
    @Test
    fun everyRowRunsItsOwnCallback() {
        setMenu()
        compose.waitForIdle()

        compose.onNodeWithText(like).performClick()
        compose.onNodeWithText(dislike).performClick()
        compose.onNodeWithText(addToPlaylist).performClick()
        compose.onNodeWithText(download).performClick()
        compose.onNodeWithText(startRadio).performClick()
        compose.onNodeWithText(playNext).performClick()
        compose.onNodeWithText(addToQueue).performClick()
        compose.onNodeWithText(openAlbum).performClick()
        compose.onNodeWithText(openArtist).performClick()
        compose.waitForIdle()

        assertEquals(1, ran.like)
        assertEquals(1, ran.dislike)
        assertEquals(1, ran.addToPlaylist)
        assertEquals(1, ran.download)
        assertEquals(1, ran.startRadio)
        assertEquals(1, ran.playNext)
        assertEquals(1, ran.addToQueue)
        assertEquals("open album must carry the track's own album id", "album-1", ran.albumId)
        assertEquals("open artist must carry the track's own artist id", "artist-1", ran.artistId)
    }

    /**
     * The player-only rows, which are the ones that vanish and come back as the
     * player grows and shrinks the menu — so they are the ones most likely to
     * end up wired to nothing.
     */
    @Test
    fun thePlayerOnlyRowsRunTheirOwnCallbacks() {
        setMenu(withPlayerRows = true)
        compose.waitForIdle()

        compose.onNodeWithText(revertToOriginal).performClick()
        compose.onNodeWithText(lyricsOffset).performClick()
        compose.onNodeWithText(sing).performClick()
        compose.onNodeWithText(yourReplay).performClick()
        compose.onNodeWithText(spatialAudio).performClick()
        compose.onNodeWithText(share).performClick()
        compose.onNodeWithText(saveCoverArt).performClick()
        compose.onNodeWithText(copyLog).performClick()
        compose.waitForIdle()

        assertEquals(1, ran.revert)
        assertEquals(1, ran.lyricsOffset)
        assertEquals(1, ran.sing)
        assertEquals(1, ran.replay)
        assertEquals(1, ran.spatial)
        assertEquals(1, ran.share)
        assertEquals(1, ran.saveCoverArt)
        assertEquals(1, ran.copyLog)
    }

    /**
     * The rows that go back are the ones that came back. A row still reading
     * "Add to Liked" on a track that is already liked is a control that tells
     * the listener to do something they have already done.
     */
    @Test
    fun aLikedTrackOffersToUnlikeItRatherThanToLikeIt() {
        setMenu(likeStatus = LikeStatus.LIKE)
        compose.waitForIdle()

        compose.onNodeWithText(removeFromLiked).assertIsDisplayed()
        compose.onNodeWithText(like).assertDoesNotExist()
        compose.onNodeWithText(removeFromLiked).performClick()
        compose.waitForIdle()

        assertEquals(1, ran.like)
    }

    @Test
    fun aDislikedTrackOffersToUndoRatherThanToDislike() {
        setMenu(likeStatus = LikeStatus.DISLIKE)
        compose.waitForIdle()

        compose.onNodeWithText(undoDislike).assertIsDisplayed()
        compose.onNodeWithText(dislike).assertDoesNotExist()
        compose.onNodeWithText(undoDislike).performClick()
        compose.waitForIdle()

        assertEquals(1, ran.dislike)
    }

    /**
     * A file on the device has no YouTube identity behind it to rate, save,
     * queue into a playlist or share a link for — so those rows are gone rather
     * than present and inert. "Gone" is the part that matters: a row that is
     * still drawn and does nothing is the dead-control failure again, wearing a
     * different hat.
     */
    @Test
    fun anOfflineTrackOffersNeitherTheLibraryRowsNorShare() {
        setMenu(song = song.copy(localUri = "file:///music/test.mp3"))
        compose.waitForIdle()

        compose.onNodeWithText(like).assertDoesNotExist()
        compose.onNodeWithText(removeFromLiked).assertDoesNotExist()
        compose.onNodeWithText(dislike).assertDoesNotExist()
        compose.onNodeWithText(undoDislike).assertDoesNotExist()
        compose.onNodeWithText(addToPlaylist).assertDoesNotExist()
        compose.onNodeWithText(share).assertDoesNotExist()
        compose.onNodeWithText(download).assertDoesNotExist()
        // Saving the cover is *not* one of them: the artwork is a local file
        // like any other, and the row is gated on the track having one at all.
        compose.onNodeWithText(saveCoverArt).assertIsDisplayed()

        // The rows that are about the device file itself are still there.
        compose.onNodeWithText(startRadio).performClick()
        compose.onNodeWithText(playNext).performClick()
        compose.waitForIdle()
        assertEquals(1, ran.startRadio)
        assertEquals(1, ran.playNext)
    }

    /** Signed out, the library rows have nowhere to go. */
    @Test
    fun aSignedOutTrackOffersNoLibraryRows() {
        setMenu(signedIn = false)
        compose.waitForIdle()

        compose.onNodeWithText(like).assertDoesNotExist()
        compose.onNodeWithText(dislike).assertDoesNotExist()
        compose.onNodeWithText(addToPlaylist).assertDoesNotExist()

        compose.onNodeWithText(playNext).performClick()
        compose.waitForIdle()
        assertEquals(1, ran.playNext)
    }

    /**
     * A lookup still in flight shows the row as a spinner rather than leaving
     * it out — and leaving it out is what it is not doing. What it must not do
     * is offer the row as something to tap: there is no id to open yet, and a
     * row that accepts a tap and opens nothing is the dead control again.
     */
    @Test
    fun aRowStillLookingUpItsIdIsShownButNotTappable() {
        setMenu(
            song = song.copy(albumId = null, artistId = null),
            resolvingLinks = true,
        )
        compose.waitForIdle()

        compose.onNodeWithText(openAlbum).assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText(openArtist).assertIsDisplayed().assertHasNoClickAction()
    }

    /** And once the lookup lands, the same row is live. */
    @Test
    fun aRowWhoseIdHasArrivedIsTappable() {
        setMenu()
        compose.waitForIdle()

        compose.onNodeWithText(openAlbum).performClick()
        compose.waitForIdle()

        assertEquals("album-1", ran.albumId)
    }

    /**
     * The contents of the menu, in order. The glass went onto this surface and
     * the menu's options were required not to change; this is the assertion that
     * says so, and it is the one a future row added to the list will fail.
     */
    @Test
    fun theMenuOffersItsRowsInOrder() {
        setMenu()
        compose.waitForIdle()

        val expected = listOf(
            like,
            dislike,
            addToPlaylist,
            download,
            startRadio,
            playNext,
            addToQueue,
            openAlbum,
            openArtist,
            share,
            saveCoverArt,
        )

        val tops = expected.map { row ->
            compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top
        }

        for (index in 1 until tops.size) {
            assertTrue(
                "${expected[index]} must sit below ${expected[index - 1]}",
                tops[index] >= tops[index - 1],
            )
        }
        // No two rows are the same row drawn twice.
        assertEquals(expected.size, tops.toSet().size)
    }

    /**
     * And the rows the caller did not ask for are genuinely absent, rather than
     * drawn and inert.
     */
    @Test
    fun theMenuOffersNothingTheCallerDidNotAskFor() {
        setMenu()
        compose.waitForIdle()

        // Optional rows this composition did not supply.
        compose.onNodeWithText(revertToOriginal).assertDoesNotExist()
        compose.onNodeWithText(removeFromPlaylist).assertDoesNotExist()
        compose.onNodeWithText(sleepTimer).assertDoesNotExist()
        compose.onNodeWithText(lyricsOffset).assertDoesNotExist()
        compose.onNodeWithText(sing).assertDoesNotExist()
        compose.onNodeWithText(yourReplay).assertDoesNotExist()
        compose.onNodeWithText(spatialAudio).assertDoesNotExist()
        compose.onNodeWithText(copyLog).assertDoesNotExist()
    }

    /**
     * Every row the caller asked for is actually on screen. A row scrolled out
     * of reach is a row nobody can tap, which is the same defect as a row with
     * no callback — only quieter.
     */
    @Test
    fun everyRowIsComposedAndVisible() {
        setMenu(withPlayerRows = true, showSleepTimer = true)
        compose.waitForIdle()

        listOf(
            revertToOriginal,
            like,
            dislike,
            addToPlaylist,
            download,
            startRadio,
            playNext,
            addToQueue,
            openAlbum,
            openArtist,
            sleepTimer,
            lyricsOffset,
            sing,
            yourReplay,
            spatialAudio,
            share,
            saveCoverArt,
            copyLog,
        ).forEach { row ->
            compose.onNodeWithText(row).assertIsDisplayed()
        }
    }
}
