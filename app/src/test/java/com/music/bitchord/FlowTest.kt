package com.music.bitchord

import com.music.bitchord.data.flow.FlowConfig
import com.music.bitchord.data.flow.FlowEngine
import com.music.bitchord.data.flow.FlowMood
import com.music.bitchord.data.flow.FlowRules
import com.music.bitchord.data.flow.FlowTuner
import com.music.bitchord.data.flow.genreHints
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FlowTest {

    private fun song(id: String, title: String, artist: String = "Artist $id") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    // ---- Unlock rules (Deezer: 16 favs, or 10 artists on newer builds) ----

    @Test
    fun `flow unlocks at 16 favorites total`() {
        assertTrue(FlowRules.isUnlocked(16, 0))
        assertTrue(FlowRules.isUnlocked(10, 6))
        assertFalse(FlowRules.isUnlocked(15, 0))
    }

    @Test
    fun `flow unlocks at 10 artists alone`() {
        assertTrue(FlowRules.isUnlocked(0, 10))
        assertFalse(FlowRules.isUnlocked(0, 9))
    }

    // ---- Tuner ----

    @Test
    fun `tuner toggles genres off and on`() {
        val off = FlowTuner().toggleGenre("Rock", false)
        assertFalse(off.isGenreEnabled("rock"))
        assertTrue(off.toggleGenre("Rock", true).isGenreEnabled("Rock"))
    }

    @Test
    fun `tuner clamps sliders`() {
        assertEquals(1f, FlowTuner().withDiscovery(2f).discovery)
        assertEquals(0f, FlowTuner().withDiscovery(-1f).discovery)
    }

    // ---- Exclusions: bans, dislikes, chronic skips, tuned-out genres ----

    @Test
    fun `banned and disliked tracks never play`() {
        val a = song("a", "A")
        assertTrue(
            FlowEngine.isExcluded(
                a, bannedIds = setOf("a"), likeStatuses = emptyMap(),
                skipCounts = emptyMap(), tuner = FlowTuner(),
            ),
        )
        assertTrue(
            FlowEngine.isExcluded(
                a, bannedIds = emptySet(),
                likeStatuses = mapOf("a" to LikeStatus.DISLIKE),
                skipCounts = emptyMap(), tuner = FlowTuner(),
            ),
        )
        assertFalse(
            FlowEngine.isExcluded(
                a, bannedIds = emptySet(), likeStatuses = emptyMap(),
                skipCounts = emptyMap(), tuner = FlowTuner(),
            ),
        )
    }

    @Test
    fun `three skips excludes, two demote`() {
        val a = song("a", "A")
        val tuner = FlowTuner()
        assertTrue(
            FlowEngine.isExcluded(
                a, emptySet(), emptyMap(), mapOf("a" to 3), tuner,
            ),
        )
        val demoted = FlowEngine.score(a, emptySet(), emptyMap(), mapOf("a" to 2), tuner, emptyList())
        val fresh = FlowEngine.score(a, emptySet(), emptyMap(), emptyMap(), tuner, emptyList())
        assertTrue(demoted < fresh)
    }

    @Test
    fun `tuned-out genre excludes`() {
        val a = song("a", "Rock anthem", "Some Band")
        val tuner = FlowTuner().toggleGenre("rock", false)
        assertTrue(
            FlowEngine.isExcluded(
                a, emptySet(), emptyMap(), emptyMap(), tuner,
                genreOf = { listOf("Rock") },
            ),
        )
    }

    // ---- Ordering: favorites first, skips last, artists spread ----

    @Test
    fun `favorites outrank fresh tracks`() {
        val fav = song("fav", "Loved")
        val fresh = song("new", "New")
        val tuner = FlowTuner()
        val favScore = FlowEngine.score(fav, setOf("fav"), emptyMap(), emptyMap(), tuner, emptyList())
        val freshScore = FlowEngine.score(fresh, setOf("fav"), emptyMap(), emptyMap(), tuner, emptyList())
        assertTrue(favScore > freshScore)
    }

    @Test
    fun `local pool orders favorites first and spreads artists`() {
        val tracks = listOf(
            song("1", "One", "Same Artist"),
            song("2", "Two", "Same Artist"),
            song("3", "Three", "Same Artist"),
            song("4", "Four", "Other Artist"),
        )
        val ordered = FlowEngine.orderLocalPool(
            pool = tracks,
            favIds = setOf("4"),
            history = emptyList(),
            skipCounts = emptyMap(),
            tuner = FlowTuner(),
            mood = FlowMood.FLOW,
            limit = 4,
            random = Random(0),
        )
        assertEquals("4", ordered.first().videoId)
        // Same-artist tracks must not occupy the first three slots alone.
        assertTrue(ordered.take(3).map { it.artist }.toSet().size > 1)
    }

    @Test
    fun `mood boosts matching genres`() {
        val chill = song("c", "Midnight", "Ambient Band")
        val plain = song("p", "Hit", "Pop Star")
        val hints = FlowMood.CHILL.genreHints()
        val genreOf: (String) -> List<String> = {
            if (it == "Ambient Band") listOf("Ambient") else listOf("Pop")
        }
        val tuner = FlowTuner()
        val chillScore = FlowEngine.score(chill, emptySet(), emptyMap(), emptyMap(), tuner, hints, genreOf)
        val plainScore = FlowEngine.score(plain, emptySet(), emptyMap(), emptyMap(), tuner, hints, genreOf)
        assertTrue(chillScore > plainScore)
    }

    @Test
    fun `flow tags carry source for now playing`() {
        val tagged = FlowEngine.tagFlow(listOf(song("a", "A")))
        assertEquals("Flow", tagged.single().radioName)
        assertEquals("bitchord:flow", tagged.single().playbackSourceId)
    }

    @Test
    fun `config defaults to unfiltered flow`() {
        assertEquals(FlowMood.FLOW, FlowConfig().mood)
    }
}
