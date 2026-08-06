package com.repository.listener.rc

/**
 * Minimal JSON string quoting for the RC frames.
 *
 * Hand-rolled rather than JSONObject because the state-push dedup relies on byte-equality and
 * JSONObject guarantees no key order.
 */
object RcJson {

    /** One RFCOMM frame's worth of characters. An RC frame must never chunk. */
    const val MAX_FRAME_CHARS = 10_000

    fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(escapeUnit(c))
                // A lone surrogate would encode to invalid UTF-8 on the wire, so escape any that is
                // not part of a well-formed pair. Valid pairs are passed through verbatim.
                Character.isHighSurrogate(c) -> {
                    val next = if (i + 1 < raw.length) raw[i + 1] else '\u0000'
                    if (Character.isLowSurrogate(next)) {
                        sb.append(c).append(next)
                        i++
                    } else {
                        sb.append(escapeUnit(c))
                    }
                }
                Character.isLowSurrogate(c) -> sb.append(escapeUnit(c))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
        return sb.toString()
    }

    private fun escapeUnit(c: Char): String = String.format("\\u%04x", c.code)
}
