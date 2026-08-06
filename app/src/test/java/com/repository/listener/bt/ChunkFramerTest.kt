package com.repository.listener.bt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterisation tests. These pin the EXACT arg layout that PhoneBtHost.sendChunkedJson emits
 * today, so the extraction into ChunkFramer can be proven byte-identical. If a change here is
 * needed to make a test pass, the change is a wire regression, not a test fix.
 */
class ChunkFramerTest {

    @Test
    fun singleShortPayloadIsOneFinalChunkNoPrefix() {
        val out = ChunkFramer.frame(prefix = null, json = "{\"a\":1}", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(listOf("{\"a\":1}", "1"), out[0].toList())
    }

    @Test
    fun singleShortPayloadWithPrefixKeepsPrefixAtIndexZero() {
        val out = ChunkFramer.frame(prefix = "conv-7", json = "{\"a\":1}", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(listOf("conv-7", "{\"a\":1}", "1"), out[0].toList())
    }

    @Test
    fun longPayloadSplitsAndOnlyLastIsFinal() {
        val json = "x".repeat(25)
        val out = ChunkFramer.frame(prefix = null, json = json, maxChars = 10)
        assertEquals(3, out.size)
        assertEquals(listOf("xxxxxxxxxx", "0"), out[0].toList())
        assertEquals(listOf("xxxxxxxxxx", "0"), out[1].toList())
        assertEquals(listOf("xxxxx", "1"), out[2].toList())
    }

    @Test
    fun longPayloadWithPrefixRepeatsPrefixOnEveryChunk() {
        val out = ChunkFramer.frame(prefix = "c1", json = "x".repeat(15), maxChars = 10)
        assertEquals(2, out.size)
        assertEquals("c1", out[0][0])
        assertEquals("c1", out[1][0])
        assertEquals("1", out[1].last())
    }

    @Test
    fun emptyPayloadStillEmitsOneFinalChunk() {
        val out = ChunkFramer.frame(prefix = null, json = "", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(listOf("", "1"), out[0].toList())
    }

    @Test
    fun payloadExactlyMaxCharsIsASingleFinalChunk() {
        // The current code branches on json.length <= MAX_CAPS_CHARS, so an exact fit never
        // enters the splitting loop.
        val out = ChunkFramer.frame(prefix = null, json = "y".repeat(10), maxChars = 10)
        assertEquals(1, out.size)
        assertEquals(listOf("y".repeat(10), "1"), out[0].toList())
    }

    @Test
    fun splitNeverBreaksASurrogatePair() {
        // 9 filler chars then an astral emoji: a naive chunked() split at 10 would cut the pair.
        val json = "a".repeat(9) + "\uD83D\uDE00" + "bbb"
        val out = ChunkFramer.frame(prefix = null, json = json, maxChars = 10)
        assertEquals(2, out.size)
        assertEquals(listOf("a".repeat(9), "0"), out[0].toList())
        assertEquals(listOf("\uD83D\uDE00bbb", "1"), out[1].toList())
    }
}
