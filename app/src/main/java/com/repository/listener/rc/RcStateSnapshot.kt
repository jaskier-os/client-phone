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
    const val FOLDER_CHARS = 40

    /** One RFCOMM frame's worth. An RC frame must never chunk. */
    const val MAX_FRAME_CHARS = RcJson.MAX_FRAME_CHARS

    fun build(wsConnected: Boolean, sessions: List<RcSessionState>): String {
        // The id tiebreak is load-bearing: the source is a ConcurrentHashMap whose iteration order
        // changes on resize, so without it an unchanged state would serialize differently each time
        // and defeat the byte-equality dedup that replaced the coalescer.
        val ordered = sessions
            .sortedWith(compareByDescending<RcSessionState> { it.lastActivityMs }.thenBy { it.id })
        var visible = ordered.take(MAX_SESSIONS)
        // An RC frame must never chunk, and a session id has no safe truncation, so the cap is
        // enforced by dropping the least recently active sessions until the frame fits.
        while (visible.isNotEmpty() && serialize(wsConnected, visible).length > MAX_FRAME_CHARS) {
            visible = visible.dropLast(1)
        }
        return serialize(wsConnected, visible)
    }

    private fun serialize(wsConnected: Boolean, visible: List<RcSessionState>): String {
        val sb = StringBuilder(256)
        sb.append("{\"ws\":").append(wsConnected).append(",\"s\":[")
        visible.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(quote(s.id))
                .append(",\"n\":").append(quote(s.name.take(NAME_CHARS)))
                .append(",\"w\":").append(quote(s.folder.take(FOLDER_CHARS)))
                .append(",\"st\":").append(if (s.ended) "\"ended\"" else "\"open\"")
                .append(",\"t\":").append(!s.ended && (s.turning || s.debouncePending))
                .append(",\"u\":").append(!s.ended && s.unread)
                .append(",\"q\":").append(s.lastSeq)
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun quote(raw: String): String = RcJson.quote(raw)
}
