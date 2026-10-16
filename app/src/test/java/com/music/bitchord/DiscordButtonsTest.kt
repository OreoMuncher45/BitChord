package com.music.bitchord

import com.music.bitchord.data.discord.DiscordRPC
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordButtonsTest {

    @Test
    fun `empty custom link keeps the default page`() {
        assertEquals("https://x.test/a", DiscordRPC.buttonUrl("", "https://x.test/a"))
        assertEquals("https://x.test/a", DiscordRPC.buttonUrl("   ", "https://x.test/a"))
    }

    @Test
    fun `bare host gains https`() {
        assertEquals("https://example.com/y", DiscordRPC.buttonUrl("example.com/y", "https://d.test"))
    }

    @Test
    fun `non-url falls back to default`() {
        assertEquals("https://d.test", DiscordRPC.buttonUrl("not a url", "https://d.test"))
        assertEquals("https://d.test", DiscordRPC.buttonUrl("ftp://files.test/x", "https://d.test"))
    }

    @Test
    fun `labels truncate to discord's limit instead of killing the pair`() {
        val long = "a".repeat(50)
        assertEquals(32, DiscordRPC.buttonLabel(long).length)
        assertEquals("Listen on YouTube Music", DiscordRPC.buttonLabel("Listen on YouTube Music"))
    }
}
