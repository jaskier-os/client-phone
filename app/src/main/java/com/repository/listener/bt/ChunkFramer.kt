package com.repository.listener.bt

import java.util.concurrent.atomic.AtomicLong

/**
 * Pure, Android-free chunk building. Extracted verbatim from PhoneBtHost.sendChunkedJson so the
 * framing is JVM-testable. The arg layout is UNCHANGED and must stay wire-compatible with the
 * currently deployed glasses build.
 *
 * The split algorithm mirrors the original loop exactly, including the surrogate-pair guard: a
 * chunk boundary is pulled back by one char when it would land between a high and a low surrogate.
 * A naive String.chunked(maxChars) would NOT do this and would corrupt astral characters.
 */
object ChunkFramer {

    /**
     * Frames a payload with a trailing stream id and sequence number.
     *
     * Layout: [leading...] + [chunk] + [isFinal] + [streamId] + [seq]. The trailing pair is what
     * lets the receiver detect a lost chunk instead of concatenating across a gap; the glasses
     * assembler refuses any frame without it, so EVERY chunked channel must go through here.
     *
     * [leading] is the channel's own header args, repeated verbatim on every chunk. Most channels
     * have none or one (a conversation or chat id); CH_CONTACTS has three (op, mac, hash) and the
     * glasses read its prefix at index 2, which is why this is a list and not a single nullable.
     */
    fun frame(
        channel: String,
        leading: List<String>,
        json: String,
        maxChars: Int
    ): List<Array<String>> {
        require(maxChars > 0) { "maxChars must be > 0" }
        val streamId = "$SENTINEL$channel#${streamCounter.incrementAndGet()}"
        return split(json, maxChars).mapIndexed { i, (piece, isFinal) ->
            val flag = if (isFinal) "1" else "0"
            (leading + listOf(piece, flag, streamId, i.toString())).toTypedArray()
        }
    }

    fun frame(channel: String, prefix: String?, json: String, maxChars: Int): List<Array<String>> =
        frame(channel, listOfNotNull(prefix), json, maxChars)

    /** @return the payload pieces in order, each paired with whether it is the final piece. */
    private fun split(json: String, maxChars: Int): List<Pair<String, Boolean>> {
        if (json.length <= maxChars) return listOf(json to true)
        val out = ArrayList<Pair<String, Boolean>>()
        var start = 0
        while (start < json.length) {
            var end = minOf(start + maxChars, json.length)
            // Avoid splitting a surrogate pair. The end - 1 > start guard keeps the pullback from
            // producing an empty piece (and thus a non-terminating loop) when maxChars is 1; the
            // original inline loop had no such guard. Unreachable at MAX_CAPS_CHARS.
            if (end < json.length && end - 1 > start && Character.isHighSurrogate(json[end - 1])) end--
            val isFinal = end >= json.length
            out.add(json.substring(start, end) to isFinal)
            start = end
        }
        return out
    }

    /**
     * Marks a stream id. Must be a control character: chunks are raw JSON substrings and prefixes
     * are conversation or chat ids, so any printable sentinel could occur naturally in either.
     */
    const val SENTINEL = "\u0001cs#"

    /**
     * Seeded from the wall clock so a stream id minted after a process restart cannot collide with
     * one a receiver is still buffering. A plain zero-based counter would restart at 1 every launch.
     */
    private val streamCounter = AtomicLong(System.currentTimeMillis())
}
