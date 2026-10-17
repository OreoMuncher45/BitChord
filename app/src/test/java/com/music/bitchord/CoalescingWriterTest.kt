package com.music.bitchord

import com.music.bitchord.download.CoalescingWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download index's write path.
 *
 * The bug this exists to prevent: two downloads finishing at the same time each
 * built a complete record, and whichever reached `SharedPreferences` last won —
 * so the other track's row was never written. On a batch download with four
 * workers that is routine, and the symptom is a library that loses tracks
 * between launches while the app still claims they are there.
 *
 * Two properties are pinned here:
 *
 * 1. **Nothing is lost.** Every submitted payload that was not superseded by a
 *    newer one reaches the sink.
 * 2. **A burst costs one write.** Fifty completions in a row must not become
 *    fifty writes of the whole library — which is what the per-completion write
 *    did, each one an atomic rewrite of the settings file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingWriterTest {

    private fun writer(
        writes: MutableList<Map<String, String>>,
        scope: TestScope,
    ): CoalescingWriter {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return CoalescingWriter(scope = CoroutineScope(dispatcher)) { writes += it }
    }

    @Test
    fun `a single payload is written once`() = TestScope().run {
        val writes = mutableListOf<Map<String, String>>()
        val w = writer(writes, this)
        w.submit(mapOf("k" to "1"))
        advanceUntilIdle()
        assertEquals(listOf(mapOf("k" to "1")), writes)
    }

    @Test
    fun `a burst coalesces down to the final state`() = TestScope().run {
        val writes = mutableListOf<Map<String, String>>()
        val w = writer(writes, this)
        repeat(50) { w.submit(mapOf("n" to it.toString())) }
        advanceUntilIdle()
        assertEquals(1, writes.size)
        assertEquals(mapOf("n" to "49"), writes.single())
    }

    @Test
    fun `a payload that lands mid-drain is not dropped`() = TestScope().run {
        val writes = mutableListOf<Map<String, String>>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var w: CoalescingWriter? = null
        w = CoalescingWriter(scope = CoroutineScope(dispatcher)) { payload ->
            writes += payload
            // Submitted while the sink is still running: it must survive.
            if (payload["n"] == "1") w?.submit(mapOf("n" to "late"))
        }
        w.submit(mapOf("n" to "1"))
        advanceUntilIdle()
        assertEquals(listOf(mapOf("n" to "1"), mapOf("n" to "late")), writes)
    }

    @Test
    fun `concurrent submissions never lose the newest payload`() = TestScope().run {
        val writes = mutableListOf<Map<String, String>>()
        val w = writer(writes, this)
        repeat(200) { w.submit(mapOf("n" to it.toString())) }
        advanceUntilIdle()
        assertTrue(writes.isNotEmpty())
        // Whichever write was last must be the newest state submitted.
        assertEquals("199", writes.last()["n"])
    }
}