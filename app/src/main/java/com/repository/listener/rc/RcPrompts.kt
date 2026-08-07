package com.repository.listener.rc

import org.json.JSONObject

/**
 * Turns a blocking RC prompt into the option list the glasses can walk with the DPAD.
 *
 * Only the `rc_permission_request` family has options. `AskUserQuestion` enumerates them itself
 * inside `toolArgs`; every other tool's choice is the approve/reject verdict the phone UI already
 * offers as buttons. `rc_user_input` is free text and is deliberately absent from this object --
 * offering a list for it would be inventing choices the agent never named.
 */
object RcPrompts {

    const val APPROVE = "Approve"
    const val REJECT = "Reject"

    /**
     * A prompt row travels inside the same 10 KB frame as the rest of the tail, so its options are
     * capped. A question with more than this many answers is answered on the phone.
     */
    const val MAX_OPTIONS = 8

    /** A label longer than this would push the rest of the option list off a 480px waveguide. */
    const val MAX_OPTION_CHARS = 48

    /**
     * The options to offer for a permission request, in the order the wearer walks them.
     *
     * @return empty when the tool enumerates nothing usable, which mirrors the prompt as text only.
     */
    fun optionsFor(toolName: String, toolArgs: String): List<String> {
        if (toolName != ASK_USER_QUESTION) return listOf(APPROVE, REJECT)
        val questions = try {
            JSONObject(toolArgs).optJSONArray("questions")
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        val out = ArrayList<String>(MAX_OPTIONS)
        for (qi in 0 until questions.length()) {
            val opts = questions.optJSONObject(qi)?.optJSONArray("options") ?: continue
            for (oi in 0 until opts.length()) {
                val label = opts.optJSONObject(oi)?.optString("label", "").orEmpty()
                if (label.isEmpty()) continue
                // Duplicate labels across questions would be indistinguishable once answered, so
                // only the first occurrence is offered.
                val clipped = if (label.length <= MAX_OPTION_CHARS) label
                else label.take(MAX_OPTION_CHARS - 3) + "..."
                if (clipped in out) continue
                out.add(clipped)
                if (out.size == MAX_OPTIONS) return out
            }
        }
        return out
    }

    const val ASK_USER_QUESTION = "AskUserQuestion"
}

/** How an answer picked on the glasses must be delivered to the orchestrator. */
sealed class RcPromptAnswer {
    /**
     * Resolve through `sendRcPermissionResponse`. A permission answered through the user-response
     * call would leave the tool call pending forever, so the two are never interchangeable.
     */
    data class Permission(val approved: Boolean, val reason: String?) : RcPromptAnswer()
}

/**
 * Remembers which mirrored prompts are still answerable, and by which exact labels.
 *
 * Exists because the row projection is append-only: a delivered prompt row cannot be mutated to
 * withdraw its options, so "already resolved" has to be decided here, on the answer path, rather
 * than by what the glasses currently happen to be drawing. That single gate covers all three stale
 * cases -- a double tap, a prompt the phone user answered first, and a label that was never offered.
 *
 * Written from the BT reader thread and the WS reader thread, so every method is synchronized.
 */
class RcPromptRegistry {

    private class Pending(val toolName: String, val options: List<String>)

    private val lock = Any()

    /**
     * Keyed by session AND request id. The session is part of the key, not a passenger: the glasses
     * name the session separately in the answer frame, so keying on the request id alone would let
     * a frame naming session B resolve a prompt session A raised -- a tool call the wearer never saw
     * being approved by a keypress meant for another thread.
     */
    private val pending = object : LinkedHashMap<Pair<String, String>, Pending>(8, 0.75f, false) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, String>, Pending>?
        ): Boolean = size > MAX_PENDING
    }

    /** A prompt with no options is not registered: there is nothing that could be routed. */
    fun register(sessionId: String, requestId: String, toolName: String, options: List<String>) =
        synchronized(lock) {
            if (sessionId.isEmpty() || requestId.isEmpty() || options.isEmpty()) return@synchronized
            pending[sessionId to requestId] = Pending(toolName, options)
            Unit
        }

    /** The prompt was answered elsewhere (phone UI) or expired. Idempotent. */
    fun resolve(sessionId: String, requestId: String) = synchronized(lock) {
        pending.remove(sessionId to requestId)
        Unit
    }

    /**
     * The session ended. Its prompts can never be resolved now, and holding them only leaves a
     * stale hit for a request id the orchestrator may reuse.
     */
    fun clearSession(sessionId: String) = synchronized(lock) {
        pending.keys.removeAll { it.first == sessionId }
        Unit
    }

    /**
     * Consumes the prompt and says how to deliver [option], or null when it must not be delivered.
     *
     * An unoffered label, or one naming the wrong session, does NOT consume the prompt: a stray
     * frame must not cost the wearer the ability to answer.
     */
    fun route(sessionId: String, requestId: String, option: String): RcPromptAnswer? =
        synchronized(lock) {
        val p = pending[sessionId to requestId] ?: return null
        if (option !in p.options) return null
        pending.remove(sessionId to requestId)
        return when {
            p.toolName != RcPrompts.ASK_USER_QUESTION && option == RcPrompts.APPROVE ->
                RcPromptAnswer.Permission(approved = true, reason = null)
            p.toolName != RcPrompts.ASK_USER_QUESTION && option == RcPrompts.REJECT ->
                RcPromptAnswer.Permission(approved = false, reason = null)
            // A question's answer IS the label, carried as the reason the agent reads back.
            else -> RcPromptAnswer.Permission(approved = true, reason = option)
        }
    }

    companion object {
        /** Bounds the map on a long session; a shed prompt is answered on the phone. */
        const val MAX_PENDING = 16
    }
}
