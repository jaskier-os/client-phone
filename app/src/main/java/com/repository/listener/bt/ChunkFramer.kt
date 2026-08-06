package com.repository.listener.bt

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

    /** @return the payload pieces in order, each paired with whether it is the final piece. */
    private fun split(json: String, maxChars: Int): List<Pair<String, Boolean>> {
        if (json.length <= maxChars) return listOf(json to true)
        val out = ArrayList<Pair<String, Boolean>>()
        var start = 0
        while (start < json.length) {
            var end = minOf(start + maxChars, json.length)
            // Avoid splitting a surrogate pair
            if (end < json.length && Character.isHighSurrogate(json[end - 1])) end--
            val isFinal = end >= json.length
            out.add(json.substring(start, end) to isFinal)
            start = end
        }
        return out
    }
}
