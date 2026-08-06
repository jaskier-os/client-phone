package com.repository.listener.rc

/**
 * One HUD-ready row of a mirrored remote-control session.
 *
 * `seq` is minted only inside [RcMirrorStore]: the orchestrator's `rc_message` carries no id, so
 * there is nothing on the wire to derive an ordering from.
 */
data class RcRow(
    val seq: Long,
    val role: String,                        // user | assistant | tools | prompt
    val text: String,                        // <= ROW_CHARS, markdown-stripped
    val toolCount: Int = 0,
    val options: List<String> = emptyList()  // non-empty only for role == "prompt"
)

/**
 * Bounded projection of orchestrator RC events into rows the glasses can render.
 *
 * The store is written from the OkHttp WS reader thread and read from the BT reader thread and the
 * main looper, so every public method takes [lock].
 */
class RcMirrorStore {

    private class Session {
        val rows = ArrayDeque<RcRow>()
        var nextSeq: Long = 0L
        var droppedAbove: Boolean = false
        /** LAST cumulative assistant text of the in-flight turn. Never a concatenation. */
        var pendingAssistant: String? = null
        /** Text of the most recently committed assistant row, to reject a re-committed turn. */
        var lastCommittedAssistant: String? = null
        val pendingToolNames = LinkedHashSet<String>()
        var pendingToolCount: Int = 0
    }

    private val lock = Any()

    private val sessions = object : LinkedHashMap<String, Session>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?): Boolean =
            size > MAX_SESSIONS
    }

    /**
     * Records the latest cumulative assistant text for the in-flight turn. STORES, never appends:
     * `rc_message.text` is cumulative (the phone UI itself replaces rather than appends), so
     * appending would duplicate prose quadratically.
     */
    fun noteAssistantText(sessionId: String, text: String) = synchronized(lock) {
        // Stripped and truncated on INGEST, not at commit: the raw cumulative text of a long turn
        // runs to hundreds of KB, and an interrupted turn never reaches commitTurn to release it.
        session(sessionId).pendingAssistant = strip(text)
    }

    fun noteTool(sessionId: String, toolName: String) = synchronized(lock) {
        val s = session(sessionId)
        // The joined row is truncated at commit anyway; the set itself must not grow unbounded
        // when a turn never commits.
        if (s.pendingToolNames.size < MAX_PENDING_TOOL_NAMES) s.pendingToolNames.add(toolName)
        s.pendingToolCount++
        s.lastCommittedAssistant = null
    }

    fun appendUser(sessionId: String, text: String): RcRow = synchronized(lock) {
        val s = session(sessionId)
        // A user message opens a genuinely new turn, so an identical reply is no longer a replay.
        s.lastCommittedAssistant = null
        add(s, "user", strip(text))
    }

    fun appendPrompt(sessionId: String, text: String, options: List<String>): RcRow =
        synchronized(lock) {
            val s = session(sessionId)
            s.lastCommittedAssistant = null
            add(s, "prompt", strip(text), options = options.toList())
        }

    /**
     * Flushes the pending tool row and the last cumulative assistant text as at most two rows, the
     * tool row first. An empty projection commits nothing and mints no seq.
     */
    fun commitTurn(sessionId: String): List<RcRow> = synchronized(lock) {
        val s = session(sessionId)
        val out = ArrayList<RcRow>(2)
        if (s.pendingToolNames.isNotEmpty()) {
            out.add(add(s, "tools", strip(s.pendingToolNames.joinToString(", ")),
                toolCount = s.pendingToolCount))
        }
        val assistant = s.pendingAssistant
        // A late rc_message re-seeds the same cumulative text after a commit; committing it again
        // would emit the whole turn's prose a second time.
        if (!assistant.isNullOrBlank() && assistant != s.lastCommittedAssistant) {
            out.add(add(s, "assistant", assistant))
            s.lastCommittedAssistant = assistant
        }
        s.pendingAssistant = null
        s.pendingToolNames.clear()
        s.pendingToolCount = 0
        out
    }

    /** @return the newest [n] rows plus whether older rows exist above them. */
    fun tail(sessionId: String, n: Int = TAIL_ROWS): Pair<List<RcRow>, Boolean> =
        synchronized(lock) {
            val s = sessions[sessionId] ?: return emptyList<RcRow>() to false
            val all = s.rows.toList()
            if (all.size <= n) return all to s.droppedAbove
            all.subList(all.size - n, all.size).toList() to true
        }

    fun lastSeq(sessionId: String): Long = synchronized(lock) {
        sessions[sessionId]?.rows?.lastOrNull()?.seq ?: -1L
    }

    /**
     * Drops a session's rows. This is a PROMPTNESS optimisation only, never the memory bound: its
     * caller `onRcSessionEnd` never fires on a dropped WS, a PC-side CLI kill or a restart.
     */
    fun clear(sessionId: String) = synchronized(lock) {
        sessions.remove(sessionId)
        Unit
    }

    /**
     * Projects a stored orchestrator transcript into rows, applying the same superseded-prefix rule
     * the phone RC UI applies (a streaming partial is dropped when the NEXT rc_message continues
     * it). Idempotent: a session that already holds rows is left untouched, so a second seed after
     * a lazy transcript fetch cannot duplicate the thread.
     */
    fun seedFromTranscript(sessionId: String, transcriptJson: String) = synchronized(lock) {
        if (sessions[sessionId]?.rows?.isNotEmpty() == true) return@synchronized
        val arr = try {
            org.json.JSONArray(transcriptJson)
        } catch (e: Exception) {
            return@synchronized
        }
        val s = session(sessionId)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val data = obj.optJSONObject(DATA)
            when (obj.optString("type", "")) {
                "user_message" -> {
                    val text = data?.optString("text", "").orEmpty()
                    if (text.isNotEmpty()) add(s, "user", strip(text))
                }
                "rc_message" -> {
                    val text = data?.optString("text", "").orEmpty()
                    if (text.isNotEmpty() && !isSuperseded(arr, i, text)) {
                        add(s, "assistant", strip(text))
                    }
                }
                "rc_permission_request" -> {
                    if (data != null) {
                        val description = data.optString("description", "")
                        val label = if (description.isNotEmpty()) description
                        else data.optString("toolName", "")
                        if (label.isNotEmpty()) {
                            add(s, "prompt", strip(label), options = optionsOf(data))
                        }
                    }
                }
            }
        }
    }

    /** True when the next rc_message CONTINUES this text, i.e. this is an earlier partial. */
    private fun isSuperseded(arr: org.json.JSONArray, index: Int, text: String): Boolean {
        for (j in (index + 1) until arr.length()) {
            val next = arr.optJSONObject(j) ?: continue
            if (next.optString("type", "") != "rc_message") continue
            val nextText = next.optJSONObject(DATA)?.optString("text", "").orEmpty()
            return nextText.length > text.length && nextText.startsWith(text)
        }
        return false
    }

    private fun optionsOf(data: org.json.JSONObject): List<String> {
        val raw = data.optJSONArray("options") ?: return emptyList()
        return (0 until raw.length()).mapNotNull { raw.optString(it, "").ifEmpty { null } }
    }

    /** Session ids currently held, least-recently-accessed first. */
    fun sessionIds(): List<String> = synchronized(lock) { sessions.keys.toList() }

    private fun session(sessionId: String): Session =
        sessions.getOrPut(sessionId) { Session() }

    private fun add(
        s: Session,
        role: String,
        text: String,
        toolCount: Int = 0,
        options: List<String> = emptyList()
    ): RcRow {
        val row = RcRow(s.nextSeq++, role, text, toolCount, options)
        s.rows.addLast(row)
        while (s.rows.size > MAX_ROWS) {
            s.rows.removeFirst()
            s.droppedAbove = true
        }
        return row
    }

    /** Markdown stripper, pure. Fenced code -> [code], inline markers dropped, links -> label. */
    private fun strip(raw: String): String {
        var t = FENCE.replace(raw, "[code]")
        t = LINK.replace(t) { it.groupValues[1] }
        t = t.replace("`", "")
        t = EMPHASIS.replace(t, "")
        t = t.trim()
        return if (t.length <= ROW_CHARS) t else t.take(ROW_CHARS - 3) + "..."
    }

    companion object {
        const val TAIL_ROWS = 20
        const val ROW_CHARS = 300
        const val MAX_ROWS = 40

        /**
         * The access-order LRU is the SOLE memory bound of this store, not a nice-to-have.
         * `onRcSessionEnd` deliberately retains its `rcDumpState` entry and only fires when the
         * orchestrator says so: a dropped WS, a PC-side CLI kill or an app restart never reach it.
         * [clear] is therefore a promptness optimisation and nothing may rely on it firing.
         * Worst case here stays MAX_ROWS x ROW_CHARS x MAX_SESSIONS ~= 96 KB.
         */
        const val MAX_SESSIONS = 8

        /** A tools row is truncated to ROW_CHARS anyway; this bounds the uncommitted set. */
        const val MAX_PENDING_TOOL_NAMES = 64

        private const val DATA = "data"

        private val FENCE = Regex("```[\\s\\S]*?```")
        private val LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
        private val EMPHASIS = Regex("(\\*\\*|\\*|__|_|~~)")
    }
}
