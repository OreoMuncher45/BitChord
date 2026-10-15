package com.music.bitchord

import com.music.bitchord.data.flow.decodeFlowSnapshot
import com.music.bitchord.data.flow.encodeFlowSnapshot
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted Flow mix, round-tripped without Android: what is saved on
 * build day must come back identical on restore day, or the "same mix
 * after restart" promise is a lie the UI cannot detect.
 */
class FlowSnapshotTest {

    private fun song(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist",
        album: String? = "Album",
        art: String? = "https://art/$id.jpg",
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = art,
        albumName = album,
    )

    @Test
    fun `round trip keeps every field`() {
        val songs = listOf(song("a"), song("b", album = null, art = null))
        val back = decodeFlowSnapshot(encodeFlowSnapshot(songs))
        assertEquals(2, back.size)
        assertEquals("a", back[0].videoId)
        assertEquals("Title a", back[0].title)
        assertEquals("https://art/a.jpg", back[0].thumbnailUrl)
        assertEquals("Album", back[0].albumName)
        assertNull(back[1].thumbnailUrl)
        assertNull(back[1].albumName)
    }

    @Test
    fun `separators inside titles cannot corrupt the parse`() {
        val tricky = "A\u001eB\u001fC"
        val songs = listOf(song("a", title = tricky, artist = "X\u001eY"))
        val back = decodeFlowSnapshot(encodeFlowSnapshot(songs))
        assertEquals(1, back.size)
        assertEquals("a", back[0].videoId)
        // Blanking keeps the length: nothing shifts, nothing splits.
        assertEquals(tricky.length, back[0].title.length)
        assertEquals(listOf("a"), back.map { it.videoId })
    }

    @Test
    fun `rows without an id are dropped, blank decodes to empty`() {
        assertTrue(decodeFlowSnapshot("").isEmpty())
        assertTrue(decodeFlowSnapshot("   ").isEmpty())
        val back = decodeFlowSnapshot(encodeFlowSnapshot(listOf(song("a"))))
        assertEquals(listOf("a"), back.map { it.videoId })
    }

    @Test
    fun `snapshot is capped so prefs stay small`() {
        val songs = (1..500).map { song("id$it") }
        val back = decodeFlowSnapshot(encodeFlowSnapshot(songs))
        assertEquals(200, back.size)
        assertEquals("id1", back.first().videoId)
    }

    @Test
    fun `empty list encodes to nothing restorable`() {
        assertTrue(decodeFlowSnapshot(encodeFlowSnapshot(emptyList())).isEmpty())
    }
}
