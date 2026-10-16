package com.music.bitchord.data.liked

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The liked-rows codec: what is written on fetch day must come back
 * identical on restore day, or the "same list after restart" promise is a
 * lie the UI cannot detect. Store behavior (prefs, accounts, caps) rides
 * on this codec verbatim.
 */
class LikedCacheTest {

    private fun song(id: String, title: String = "Title $id") = Song(
        videoId = id,
        title = title,
        artist = "Artist",
        thumbnailUrl = "https://art/$id.jpg",
        albumName = "Album",
        durationText = "3:00",
        isExplicit = true,
    )

    @Test
    fun `rows round trip every field`() {
        val songs = listOf(song("a"), song("b"))
        val back = decodeLikedRows(encodeLikedRows(songs))
        assertEquals(2, back.size)
        assertEquals("a", back[0].videoId)
        assertEquals("Title a", back[0].title)
        assertEquals("Artist", back[0].artist)
        assertEquals("Album", back[0].albumName)
        assertEquals("https://art/a.jpg", back[0].thumbnailUrl)
        assertEquals("3:00", back[0].durationText)
        assertEquals(true, back[0].isExplicit)
    }

    @Test
    fun `separators inside fields cannot corrupt the parse`() {
        val tricky = listOf(song("a", title = "A BC"))
        val back = decodeLikedRows(encodeLikedRows(tricky))
        assertEquals(1, back.size)
        assertEquals("a", back[0].videoId)
    }

    @Test
    fun `blank text decodes to nothing`() {
        assertTrue(decodeLikedRows("").isEmpty())
        assertTrue(decodeLikedRows("   ").isEmpty())
    }

    @Test
    fun `rows without an id are dropped`() {
        assertTrue(decodeLikedRows("\u001fTitle\u001fArtist").isEmpty())
        assertEquals(1, decodeLikedRows("\u001fTitle\u001fArtist\u001eb\u001fT\u001fA").size)
    }

    @Test
    fun `encode caps at max rows, oldest first`() {
        val many = (0 until LikedCache.MAX_ROWS + 100).map { song("id$it") }
        val back = decodeLikedRows(encodeLikedRows(many))
        assertEquals(LikedCache.MAX_ROWS, back.size)
        assertEquals("id0", back.first().videoId)
    }

    @Test
    fun `explicit flag survives only as explicit`() {
        val plain = decodeLikedRows(encodeLikedRows(listOf(song("a").copy(isExplicit = null))))
        assertNull(plain.single().isExplicit)
        val falsey = decodeLikedRows(encodeLikedRows(listOf(song("a").copy(isExplicit = false))))
        assertNull(falsey.single().isExplicit)
    }
}
