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
    /** Decides whether a prompt is still answerable and which call resolves it. */
    private val prompts: RcPromptRegistry,
    /** Writes one frame, returning whether it actually reached the socket. */
    private val send: (channel: String, args: Array<String>) -> Boolean,
    private val sendUserMessage: (sessionId: String, text: String) -> Unit,
    private val sendUserResponse: (sessionId: String, requestId: String, text: String) -> Unit,
    /** Resolves a blocking permission. A permission sent through sendUserResponse stays pending. */
    private val sendPermissionResponse: (
        sessionId: String, requestId: String, approved: Boolean, mode: String?, reason: String?
    ) -> Unit,
    private val markRead: (sessionId: String, seenSeq: Long) -> Unit,
    private val requestTranscript: (sessionId: String) -> Unit,
    private val cachedTranscript: (sessionId: String) -> String?
) {

    /**
     * Serializes the open-session read-modify-write. Without it two inbound frames can interleave
     * so that openSessionId names one thread while the last frame emitted belonged to another,
     * routing every later live row push to the wrong thread.
     */
    private val lock = Any()

    /** The session whose thread the glasses currently have open, or null when they are in the list. */
    @Volatile
    var openSessionId: String? = null
        private set

    /**
     * args = [sessionId, seenSeq]. Sending this IS the read acknowledgement. An empty sessionId
     * means the glasses left the thread: stop pushing live rows for it and reply nothing.
     */
    fun handleMessagesReq(args: List<String>) = synchronized(lock) {
        val sessionId = args.getOrNull(0).orEmpty()
        if (sessionId.isEmpty()) {
            openSessionId = null
            return@synchronized
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
        } catch (t: Throwable) {
            // Throwable, not Exception: an Error escaping here would leave the glasses with no
            // reply at all, which is exactly the silently swallowed dictation this frame exists to
            // prevent. A rejected send produces no turn and so no confirming row push.
            "error:${t.message ?: t.javaClass.simpleName}"
        }
        send(BtProtocol.CH_RC_SEND_RESP, arrayOf(sessionId, clientMsgId, status))
    }

    /**
     * args = [sessionId, requestId, optionText].
     *
     * The registry, not the frame, decides what happens: it is the only place that knows which
     * prompts are still pending and which orchestrator call resolves each. An answer it refuses --
     * a repeat, a prompt the phone user already answered, a label never offered -- sends NOTHING.
     * Falling back to [sendUserResponse] here would deliver a permission down the free-text path,
     * leaving the tool call blocked forever.
     */
    fun handleAnswerReq(args: List<String>) {
        val sessionId = args.getOrNull(0).orEmpty()
        val requestId = args.getOrNull(1).orEmpty()
        val text = args.getOrNull(2).orEmpty()
        if (sessionId.isEmpty() || requestId.isEmpty() || text.isEmpty()) return
        when (val answer = prompts.route(requestId, text)) {
            is RcPromptAnswer.Permission ->
                sendPermissionResponse(sessionId, requestId, answer.approved, null, answer.reason)
            null -> Unit
        }
    }

    /**
     * Live delta for the open thread. Rows for any other session are dropped.
     *
     * @return true when the frame reached the glasses. Delivering rows to an open thread counts as
     *         the read, so a dropped frame must never be mistaken for one the user has seen.
     */
    fun pushRows(sessionId: String, rows: List<RcRow>): Boolean = synchronized(lock) {
        if (rows.isEmpty() || sessionId != openSessionId) return false
        return emitRows(sessionId, rows to false)
    }

    /**
     * A lazily fetched transcript arrived. Emits only for the transcript's OWN session, and only
     * while it is still the open one: the user may have navigated away during the round trip, and
     * rows belonging to one session must never render tagged as another.
     */
    fun onTranscript(sessionId: String, transcriptJson: String) = synchronized(lock) {
        if (sessionId != openSessionId) return@synchronized
        store.seedFromTranscript(sessionId, transcriptJson)
        emitRows(sessionId, store.tail(sessionId))
    }

    private fun emitRows(sessionId: String, tail: Pair<List<RcRow>, Boolean>): Boolean {
        var (rows, moreAbove) = tail
        // Escaping can multiply a row's 300 chars sixfold (a control char becomes \u00xx), so the
        // cap has to be enforced on the serialized bytes, not assumed from the row cap. Older rows
        // are shed first and their loss is reported through `more`.
        var body = serialize(rows, moreAbove)
        while (rows.size > 1 && body.length > RcJson.MAX_FRAME_CHARS) {
            rows = rows.drop(1)
            moreAbove = true
            body = serialize(rows, moreAbove)
        }
        return send(BtProtocol.CH_RC_MESSAGES_RESP, arrayOf(sessionId, body))
    }

    private fun serialize(rows: List<RcRow>, moreAbove: Boolean): String {
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
            // Only a prompt has one; omitted otherwise so a non-prompt row can never be answered.
            if (r.requestId.isNotEmpty()) sb.append(",\"i\":").append(quote(r.requestId))
            sb.append('}')
        }
        sb.append("],\"more\":").append(moreAbove)
            .append(",\"lastSeq\":").append(rows.lastOrNull()?.seq ?: -1L)
            .append('}')
        return sb.toString()
    }

    private fun quote(raw: String): String = RcJson.quote(raw)
}
