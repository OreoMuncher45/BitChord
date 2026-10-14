package com.music.bitchord.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Refusal memory: what each stream host last said when it refused bytes,
 * and for how long that stays worth reporting.
 */
class NerdStatsRefusalTest {

    @AfterTest
    fun tearDown() {
        NerdStats.clearRefusalsForTests()
    }

    @Test
    fun `a recorded refusal reads back by host, case-insensitively`() {
        NerdStats.noteRefusal("deezer.nom.deno.net", "Addon is inactive or no longer valid")
        assertEquals(
            "Addon is inactive or no longer valid",
            NerdStats.refusalFor("DEEZER.nom.DENO.net"),
        )
    }

    @Test
    fun `blanks record nothing and read nothing`() {
        NerdStats.noteRefusal("", "x")
        NerdStats.noteRefusal("h", "")
        NerdStats.noteRefusal(null, "x")
        assertNull(NerdStats.refusalFor(""))
        assertNull(NerdStats.refusalFor(null))
        assertNull(NerdStats.refusalFor("unrelated.example.com"))
    }

    @Test
    fun `messages are capped, not stored whole`() {
        NerdStats.noteRefusal("h.example.com", "x".repeat(500))
        assertEquals(160, NerdStats.refusalFor("h.example.com")?.length)
    }
}
