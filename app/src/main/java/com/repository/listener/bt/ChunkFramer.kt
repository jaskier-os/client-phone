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
     * @return one Array<String> of send-args per chunk, in order.
     *         Layout: [prefix?] + [chunk] + [isFinal "1"/"0"].
     */
    fun frame(prefix: String?, json: String, maxChars: Int): List<Array<String>> {
        require(maxChars > 0) { "maxChars must be > 0" }
        return split(json, maxChars).map { (piece, isFinal) ->
            val flag = if (isFinal) "1" else "0"
            if (prefix != null) arrayOf(prefix, piece, flag) else arrayOf(piece, flag)
        }
    }

    /**
     * Frames a payload with a trailing stream id and sequence number.
     *
     * Layout: [prefix?] + [chunk] + [isFinal] + [streamId] + [seq]. The leading positions are
     * byte-identical to [frame] above, so a receiver that reads fixed indices sees no difference;
     * the trailing pair is what lets a receiver detect a lost chunk instead of concatenating
     * across a gap.
     */
    fun frame(channel: String, prefix: String?, json: String, maxChars: Int): List<Array<String>> {
        require(maxChars > 0) { "maxChars must be > 0" }
        val streamId = "$SENTINEL$channel#${streamCounter.incrementAndGet()}"
        return split(json, maxChars).mapIndexed { i, (piece, isFinal) ->
            val flag = if (isFinal) "1" else "0"
            val seq = i.toString()
            if (prefix != null) arrayOf(prefix, piece, flag, streamId, seq)
            else arrayOf(piece, flag, streamId, seq)
        }
    }

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

    private val streamCounter = AtomicLong(0)
}
