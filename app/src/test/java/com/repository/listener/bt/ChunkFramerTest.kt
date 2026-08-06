package com.repository.listener.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation tests. These pin the EXACT arg layout that PhoneBtHost.sendChunkedJson emits
 * today, so the extraction into ChunkFramer can be proven byte-identical. If a change here is
 * needed to make a test pass, the change is a wire regression, not a test fix.
 */
class ChunkFramerTest {

    /**
     * The leading args of each framed chunk, i.e. everything before [streamId][seq].
     *
     * The split algorithm (surrogate pullback, boundary arithmetic, isFinal placement) is what the
     * tests below are about, and it is independent of the trailing framing. Dropping the last two
     * args keeps those assertions readable without reintroducing an unframed overload -- nothing
     * may build a frame the glasses would refuse.
     */
    private fun leadingArgs(prefix: String?, json: String, maxChars: Int): List<List<String>> =
        ChunkFramer.frame("ch.test", prefix, json, maxChars).map { it.dropLast(2) }

    @Test
    fun singleShortPayloadIsOneFinalChunkNoPrefix() {
        val out = leadingArgs(prefix = null, json = "{\"a\":1}", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(listOf("{\"a\":1}", "1"), out[0].toList())
    }

    @Test
    fun singleShortPayloadWithPrefixKeepsPrefixAtIndexZero() {
        val out = leadingArgs(prefix = "conv-7", json = "{\"a\":1}", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(listOf("conv-7", "{\"a\":1}", "1"), out[0].toList())
    }

    @Test
    fun longPayloadSplitsAndOnlyLastIsFinal() {
        val json = "x".repeat(25)
        val out = leadingArgs(prefix = null, json = json, maxChars = 10)
        assertEquals(3, out.size)
        assertEquals(listOf("xxxxxxxxxx", "0"), out[0].toList())
        assertEquals(listOf("xxxxxxxxxx", "0"), out[1].toList())
        assertEquals(listOf("xxxxx", "1"), out[2].toList())
    }

    @Test
    fun longPayloadWithPrefixRepeatsPrefixOnEveryChunk() {
        val out = leadingArgs(prefix = "c1", json = "x".repeat(15), maxChars = 10)
        assertEquals(2, out.size)
        assertEquals(listOf("c1", "x".repeat(10), "0"), out[0].toList())
        assertEquals(listOf("c1", "x".repeat(5), "1"), out[1].toList())
    }

    @Test
    fun oneCharOverMaxSplitsIntoExactlyTwoPieces() {
        val out = leadingArgs(prefix = null, json = "z".repeat(11), maxChars = 10)
        assertEquals(2, out.size)
        assertEquals(listOf("z".repeat(10), "0"), out[0].toList())
        assertEquals(listOf("z", "1"), out[1].toList())
    }

    @Test
    fun consecutiveChunkBoundariesEachPullBackForSurrogates() {
        // Emoji sit at indices 9-10 and 18-19, so both boundaries need a pullback.
        val json = "a".repeat(9) + "\uD83D\uDE00" + "b".repeat(7) + "\uD83D\uDE01" + "c"
        val out = leadingArgs(prefix = null, json = json, maxChars = 10)
        assertEquals(3, out.size)
        assertEquals(listOf("a".repeat(9), "0"), out[0].toList())
        assertEquals(listOf("\uD83D\uDE00" + "b".repeat(7), "0"), out[1].toList())
        assertEquals(listOf("\uD83D\uDE01c", "1"), out[2].toList())
        assertEquals(json, out.joinToString("") { it[0] })
    }

    @Test
    fun trailingUnpairedHighSurrogateIsNotPulledBack() {
        // A high surrogate as the very last char: end == json.length, so the guard does not fire.
        val json = "a".repeat(10) + "b".repeat(9) + "\uD83D"
        val out = leadingArgs(prefix = null, json = json, maxChars = 10)
        assertEquals(2, out.size)
        assertEquals(json, out.joinToString("") { it[0] })
        assertEquals("1", out.last().last())
    }

    @Test
    fun emptyPrefixIsStillWrittenAsAnArg() {
        val out = leadingArgs(prefix = "", json = "{}", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(listOf("", "{}", "1"), out[0].toList())
    }

    @Test
    fun maxCharsOfOneTerminatesAndPreservesThePayload() {
        // Degenerate, unreachable at MAX_CAPS_CHARS, but must not hang: the pullback is suppressed
        // when it would emit an empty piece.
        val json = "a\uD83D\uDE00b"
        val out = leadingArgs(prefix = null, json = json, maxChars = 1)
        assertEquals(json, out.joinToString("") { it[0] })
        assertEquals("1", out.last().last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveMaxCharsIsRejected() {
        leadingArgs(prefix = null, json = "x", maxChars = 0)
    }

    // --- streamId / seq framing (spec 0.6.2). The args above are APPENDED to, never reordered. ---

    @Test
    fun trailingArgsMakeTheArityExactlyFourWithoutAPrefix() {
        val out = ChunkFramer.frame("ch.a", prefix = null, json = "{}", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(4, out[0].size)
        // Positions 0..1 are byte-identical to the legacy layout.
        assertEquals("{}", out[0][0])
        assertEquals("1", out[0][1])
        assertEquals("0", out[0][3])
    }

    @Test
    fun trailingArgsMakeTheArityExactlyFiveWithAPrefix() {
        val out = ChunkFramer.frame("ch.h", prefix = "conv-7", json = "{}", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(5, out[0].size)
        // Positions 0..2 are byte-identical to the legacy layout.
        assertEquals("conv-7", out[0][0])
        assertEquals("{}", out[0][1])
        assertEquals("1", out[0][2])
        assertEquals("0", out[0][4])
    }

    @Test
    fun streamIdStartsWithTheControlCharSentinelAndNamesTheChannel() {
        val out = ChunkFramer.frame("ch.a", prefix = null, json = "{}", maxChars = 10_000)
        val streamId = out[0][2]
        assertTrue(streamId.startsWith(ChunkFramer.SENTINEL))
        assertTrue(streamId.startsWith("\u0001cs#"))
        assertTrue(streamId.contains("ch.a"))
    }

    @Test
    fun twoCallsOnTheSameChannelGetDifferentStreamIds() {
        val a = ChunkFramer.frame("ch.a", prefix = null, json = "{}", maxChars = 10_000)[0][2]
        val b = ChunkFramer.frame("ch.a", prefix = null, json = "{}", maxChars = 10_000)[0][2]
        assertNotEquals(a, b)
    }

    @Test
    fun streamIdsAreNotSeededAtZeroSoARestartCannotReplayThem() {
        val n = ChunkFramer.frame("ch.a", prefix = null, json = "{}", maxChars = 10_000)[0][2]
            .substringAfterLast('#').toLong()
        assertTrue("stream counter must not restart from a low value, got $n", n > 1_000_000L)
    }

    @Test
    fun everyChunkOfOneCallSharesOneStreamIdAndSeqCountsFromZero() {
        val out = ChunkFramer.frame("ch.a", prefix = null, json = "x".repeat(25), maxChars = 10)
        assertEquals(3, out.size)
        val streamId = out[0][2]
        assertEquals(listOf(streamId, streamId, streamId), out.map { it[2] })
        assertEquals(listOf("0", "1", "2"), out.map { it[3] })
    }

    @Test
    fun seqIsPlainDecimalAndStrictlyIncreasingAcrossAPrefixedStream() {
        val out = ChunkFramer.frame("ch.h", prefix = "c1", json = "x".repeat(25), maxChars = 10)
        assertEquals(3, out.size)
        assertEquals(listOf("0", "1", "2"), out.map { it[4] })
        out.forEach { assertTrue(it[4].matches(Regex("^\\d+$"))) }
        assertEquals(listOf("c1", "c1", "c1"), out.map { it[0] })
    }

    @Test
    fun theLegacyPositionsAreUnchangedByTheNewTrailingArgs() {
        // The whole safety argument: an old glasses build reads fixed indices only, so the first
        // two/three args must equal the characterisation expectations exactly.
        val legacy = leadingArgs(prefix = "c1", json = "x".repeat(15), maxChars = 10)
        val framed = ChunkFramer.frame("ch.h", prefix = "c1", json = "x".repeat(15), maxChars = 10)
        assertEquals(legacy.size, framed.size)
        legacy.indices.forEach { i ->
            assertEquals(legacy[i].toList(), framed[i].take(3))
        }
    }

    @Test
    fun emptyPayloadStillEmitsOneFinalChunk() {
        val out = leadingArgs(prefix = null, json = "", maxChars = 10_000)
        assertEquals(1, out.size)
        assertEquals(listOf("", "1"), out[0].toList())
    }

    @Test
    fun payloadExactlyMaxCharsIsASingleFinalChunk() {
        // The current code branches on json.length <= MAX_CAPS_CHARS, so an exact fit never
        // enters the splitting loop.
        val out = leadingArgs(prefix = null, json = "y".repeat(10), maxChars = 10)
        assertEquals(1, out.size)
        assertEquals(listOf("y".repeat(10), "1"), out[0].toList())
    }

    @Test
    fun splitNeverBreaksASurrogatePair() {
        // 9 filler chars then an astral emoji: a naive chunked() split at 10 would cut the pair.
        val json = "a".repeat(9) + "\uD83D\uDE00" + "bbb"
        val out = leadingArgs(prefix = null, json = json, maxChars = 10)
        assertEquals(2, out.size)
        assertEquals(listOf("a".repeat(9), "0"), out[0].toList())
        assertEquals(listOf("\uD83D\uDE00bbb", "1"), out[1].toList())
    }

    // --- multi-arg headers (CH_CONTACTS) ---
    //
    // Contacts is the one channel with THREE header args, and its hand-rolled sender was missed
    // when the rest moved to this framer. The glasses assembler refuses an unframed frame outright,
    // so the miss meant contacts could never sync again -- pinned here so it cannot recur.

    @Test
    fun threeLeadingArgsAreRepeatedOnEveryChunkAheadOfTheFraming() {
        val out = ChunkFramer.frame(
            channel = "listener_contacts",
            leading = listOf("LIST", "AA:BB", "h1"),
            json = "x".repeat(15),
            maxChars = 10
        )
        assertEquals(2, out.size)
        out.forEach { assertEquals(listOf("LIST", "AA:BB", "h1"), it.take(3)) }
        // Glasses read prefixIndex 2 (the hash), chunk at 3, isFinal at 4, then streamId and seq.
        assertEquals(listOf("LIST", "AA:BB", "h1", "x".repeat(10), "0"), out[0].take(5).toList())
        assertEquals(listOf("LIST", "AA:BB", "h1", "x".repeat(5), "1"), out[1].take(5).toList())
        assertEquals(7, out[0].size)
        assertEquals(out[0][5], out[1][5])   // one stream id shared by the whole stream
        assertEquals(listOf("0", "1"), out.map { it[6] })
    }

    @Test
    fun aSingleChunkContactsPayloadIsAlsoFramed() {
        // The short-payload path is the one that skipped the loop entirely and shipped 5 raw args.
        val out = ChunkFramer.frame(
            channel = "listener_contacts",
            leading = listOf("LIST", "AA:BB", "h1"),
            json = "{}",
            maxChars = 10_000
        )
        assertEquals(1, out.size)
        assertEquals(7, out[0].size)
        assertTrue(out[0][5].startsWith(ChunkFramer.SENTINEL))
        assertEquals("0", out[0][6])
    }
}
