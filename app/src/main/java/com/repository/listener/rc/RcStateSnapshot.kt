package com.repository.listener.rc

/**
 * One session as the state push describes it.
 *
 * [turning] is the raw `rcDumpState` flag, which flips false on every inter-tool `isFinal`;
 * [debouncePending] is true while the turn-finish debounce runnable is still queued. The wire's
 * `t` is their OR, which is the phone's own authoritative "the turn really ended" edge and does not
 * strobe once per tool-chain segment.
 */
data class RcSessionState(
    val id: String,
    val name: String,
    val folder: String,
    val ended: Boolean,
    val turning: Boolean,
    val debouncePending: Boolean,
    val unread: Boolean,
    val lastSeq: Long,
    val lastActivityMs: Long
)

/**
 * Builds the full authoritative session-list snapshot pushed on CH_RC_STATE_PUSH.
 *
 * The output is built by hand rather than through JSONObject because the dedup that replaces the
 * deleted coalescer relies on byte-equality: JSONObject makes no key-order guarantee.
 */
object RcStateSnapshot {

    const val MAX_SESSIONS = 8
    const val NAME_CHARS = 40

    /** One RFCOMM frame's worth. An RC frame must never chunk. */
    const val MAX_FRAME_CHARS = 10_000

    fun build(wsConnected: Boolean, sessions: List<RcSessionState>): String {
        val visible = sessions.sortedByDescending { it.lastActivityMs }.take(MAX_SESSIONS)
        val sb = StringBuilder(256)
        sb.append("{\"ws\":").append(wsConnected).append(",\"s\":[")
        visible.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(quote(s.id))
                .append(",\"n\":").append(quote(s.name.take(NAME_CHARS)))
                .append(",\"w\":").append(quote(s.folder))
                .append(",\"st\":").append(if (s.ended) "\"ended\"" else "\"open\"")
                .append(",\"t\":").append(!s.ended && (s.turning || s.debouncePending))
                .append(",\"u\":").append(!s.ended && s.unread)
                .append(",\"q\":").append(s.lastSeq)
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (c in raw) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
