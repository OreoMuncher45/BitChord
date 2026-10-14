package com.music.bitchord

import com.music.bitchord.playback.ChunkedDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a stream refusal says, read off the bytes.
 *
 * A catalogue that answers while its files refuse is the common shape of a
 * dead install, and the status code alone never says so — the 403 could be
 * anything. The JSON error bodies these servers send ("Addon is inactive",
 * "Token inválido") are what the Sources screen repeats, so parsing them
 * right is what stands between a fixable setup problem and a mystery.
 */
class ChunkedDataSourceTest {

    @Test
    fun `json error body yields its reason`() {
        assertEquals(
            "Addon is inactive or no longer valid",
            ChunkedDataSource.refusalMessage(
                403,
                """{"error":"Addon is inactive or no longer valid"}""".toByteArray(),
            ),
        )
    }

    @Test
    fun `unicode bodies survive the parse`() {
        assertEquals(
            "Token inv\u00e1lido o expirado",
            ChunkedDataSource.refusalMessage(
                403,
                "{\"error\":\"Token inválido o expirado\"}".toByteArray(),
            ),
        )
    }

    @Test
    fun `bodies that state nothing yield nothing`() {
        assertNull(ChunkedDataSource.refusalMessage(403, ByteArray(0)))
        assertNull(ChunkedDataSource.refusalMessage(403, "<html>nope</html>".toByteArray()))
        assertNull(ChunkedDataSource.refusalMessage(500, "   ".toByteArray()))
    }
}
