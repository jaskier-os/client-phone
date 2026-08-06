package com.repository.listener.rc

import com.repository.listener.bt.BtProtocol

/**
 * Handles the six RC mirror channels, so the PhoneBtHost inbound when-block keeps one-line
 * delegations instead of growing this logic.
 *
 * Deliberately holds no Android types: every collaborator is a lambda, so the whole protocol is
 * JVM-testable.
 *
 * There is no clientMsgId dedup map and no "duplicate" status. The glasses -> phone direction has no
 * retry and no replay (the only outbound queue is phone -> glasses and is drop-oldest, not
 * duplicating), so a repeated CH_RC_SEND_REQ is unreachable on the wire. clientMsgId stays on the
 * wire purely as a correlation id for CH_RC_SEND_RESP.
 */
class RcBridge(
    private val store: RcMirrorStore,
    private val send: (channel: String, args: Array<String>) -> Unit,
    private val sendUserMessage: (sessionId: String, text: String) -> Unit,
    private val sendUserResponse: (sessionId: String, requestId: String, text: String) -> Unit,
    private val markRead: (sessionId: String, seenSeq: Long) -> Unit,
    private val requestTranscript: (sessionId: String) -> Unit,
    private val cachedTranscript: (sessionId: String) -> String?
) {

    /** The session whose thread the glasses currently have open, or null when they are in the list. */
    @Volatile
    var openSessionId: String? = null
        private set

    /**
     * args = [sessionId, seenSeq]. Sending this IS the read acknowledgement. An empty sessionId
     * means the glasses left the thread: stop pushing live rows for it and reply nothing.
     */
    fun handleMessagesReq(args: List<String>) {
        val sessionId = args.getOrNull(0).orEmpty()
        if (sessionId.isEmpty()) {
            openSessionId = null
            return
        }
        val seenSeq = args.getOrNull(1)?.toLongOrNull() ?: -1L
        markRead(sessionId, seenSeq)

        if (store.lastSeq(sessionId) < 0) {
            val cached = cachedTranscript(sessionId)
            // The only lazy path in the protocol: request once, reply with what we have now, and
            // let onTranscript emit the seeded rows when the round trip lands. Never a poll loop.
            if (cached != null) store.seedFromTranscript(sessionId, cached)
            else requestTranscript(sessionId)
        }
        openSessionId = sessionId
        emitRows(sessionId, store.tail(sessionId))
    }

    /** args = [sessionId, clientMsgId, text]. */
    fun handleSendReq(args: List<String>) {
        val sessionId = args.getOrNull(0).orEmpty()
        val clientMsgId = args.getOrNull(1).orEmpty()
        val text = args.getOrNull(2).orEmpty()
        if (sessionId.isEmpty() || text.isBlank()) return
        val status = try {
            sendUserMessage(sessionId, text)
            "sent"
        } catch (e: Exception) {
            // A rejected send produces no turn and therefore no confirming row push, so this frame
            // is the only thing standing between the user and a silently swallowed dictation.
            "error:${e.message ?: e.javaClass.simpleName}"
        }
        send(BtProtocol.CH_RC_SEND_RESP, arrayOf(sessionId, clientMsgId, status))
    }

    /** args = [sessionId, requestId, optionText]. */
    fun handleAnswerReq(args: List<String>) {
        val sessionId = args.getOrNull(0).orEmpty()
        val requestId = args.getOrNull(1).orEmpty()
        val text = args.getOrNull(2).orEmpty()
        if (sessionId.isEmpty() || requestId.isEmpty() || text.isEmpty()) return
        sendUserResponse(sessionId, requestId, text)
    }

    /** Live delta for the open thread. Rows for any other session are dropped. */
    fun pushRows(sessionId: String, rows: List<RcRow>) {
        if (rows.isEmpty() || sessionId != openSessionId) return
        emitRows(sessionId, rows to false)
    }

    /**
     * A lazily fetched transcript arrived. Emits only for the transcript's OWN session, and only
     * while it is still the open one: the user may have navigated away during the round trip, and
     * rows belonging to one session must never render tagged as another.
     */
    fun onTranscript(sessionId: String, transcriptJson: String) {
        if (sessionId != openSessionId) return
        store.seedFromTranscript(sessionId, transcriptJson)
        emitRows(sessionId, store.tail(sessionId))
    }

    private fun emitRows(sessionId: String, tail: Pair<List<RcRow>, Boolean>) {
        val (rows, moreAbove) = tail
        val sb = StringBuilder(512)
        sb.append("{\"rows\":[")
        rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"q\":").append(r.seq)
                .append(",\"r\":").append(quote(r.role))
                .append(",\"x\":").append(quote(r.text))
            if (r.toolCount > 0) sb.append(",\"c\":").append(r.toolCount)
            if (r.options.isNotEmpty()) {
                sb.append(",\"o\":[")
                r.options.forEachIndexed { j, o -> if (j > 0) sb.append(','); sb.append(quote(o)) }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append("],\"more\":").append(moreAbove)
            .append(",\"lastSeq\":").append(rows.lastOrNull()?.seq ?: -1L)
            .append('}')
        send(BtProtocol.CH_RC_MESSAGES_RESP, arrayOf(sessionId, sb.toString()))
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
