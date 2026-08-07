package com.repository.listener.rc

import com.repository.listener.bt.BtProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end of the glasses answer path, from the projected row to the orchestrator call.
 *
 * The two hazards this covers are the ones the wire itself cannot: a permission answered through
 * the free-text call (which leaves the tool call pending forever), and a stale or repeated answer
 * reaching the orchestrator because an append-only row could not be un-drawn.
 */
class RcPromptAnswerRoutingTest {

    private class Harness {
        val store = RcMirrorStore()
        val prompts = RcPromptRegistry()
        val sent = mutableListOf<Pair<String, List<String>>>()

        /** sessionId, requestId, approved, mode, reason. The sessionId is recorded, not discarded:
         *  an answer delivered against the wrong session resolves a call the wearer never saw. */
        val permissionCalls = mutableListOf<List<String?>>()

        /** requestId, text */
        val userResponseCalls = mutableListOf<List<String>>()

        val bridge = RcBridge(
            store = store,
            prompts = prompts,
            send = { ch, args -> sent.add(ch to args.toList()); true },
            sendUserMessage = { _, _ -> },
            sendUserResponse = { _, requestId, text ->
                userResponseCalls.add(listOf(requestId, text))
            },
            sendPermissionResponse = { sessionId, requestId, approved, mode, reason ->
                permissionCalls.add(listOf(sessionId, requestId, approved.toString(), mode, reason))
            },
            markRead = { _, _ -> },
            requestTranscript = { },
            cachedTranscript = { null }
        )

        fun openThread(sessionId: String) {
            bridge.handleMessagesReq(listOf(sessionId, "-1"))
            sent.clear()
        }

        fun lastRows(): List<JSONObject> {
            val body = sent.last { it.first == BtProtocol.CH_RC_MESSAGES_RESP }.second[1]
            val arr = JSONObject(body).getJSONArray("rows")
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    @Test
    fun `a projected prompt carries the request id the answer has to quote`() {
        val h = Harness()
        h.openThread("s1")
        h.prompts.register("s1", "req-7", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        val row = h.store.appendPrompt("s1", "Allow Bash?", listOf(RcPrompts.APPROVE, RcPrompts.REJECT), "req-7")
        h.bridge.pushRows("s1", listOf(row))

        val wire = h.lastRows().single()
        assertEquals("prompt", wire.getString("r"))
        assertEquals("req-7", wire.getString("i"))
        assertEquals(listOf(RcPrompts.APPROVE, RcPrompts.REJECT),
            (0 until wire.getJSONArray("o").length()).map { wire.getJSONArray("o").getString(it) })
    }

    @Test
    fun `a row that is not a prompt carries no request id`() {
        val h = Harness()
        h.openThread("s1")
        h.bridge.pushRows("s1", listOf(h.store.appendUser("s1", "hi")))
        assertTrue("a non-prompt row must not carry an id", !h.lastRows().single().has("i"))
    }

    @Test
    fun `a permission answer goes to the permission call, never to the user-response call`() {
        val h = Harness()
        h.prompts.register("s1", "req-7", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        h.bridge.handleAnswerReq(listOf("s1", "req-7", RcPrompts.APPROVE))

        assertEquals(listOf(listOf("s1", "req-7", "true", null, null)), h.permissionCalls)
        assertEquals("a permission routed as free text would never resolve",
            emptyList<List<String>>(), h.userResponseCalls)
    }

    @Test
    fun `a rejected permission reports the negative verdict`() {
        val h = Harness()
        h.prompts.register("s1", "req-7", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        h.bridge.handleAnswerReq(listOf("s1", "req-7", RcPrompts.REJECT))
        assertEquals(listOf(listOf("s1", "req-7", "false", null, null)), h.permissionCalls)
    }

    @Test
    fun `an AskUserQuestion answer approves and carries the chosen label`() {
        val h = Harness()
        h.prompts.register("s1", "req-9", "AskUserQuestion", listOf("Postgres", "SQLite"))
        h.bridge.handleAnswerReq(listOf("s1", "req-9", "SQLite"))
        assertEquals(listOf(listOf("s1", "req-9", "true", null, "SQLite")), h.permissionCalls)
        assertEquals(emptyList<List<String>>(), h.userResponseCalls)
    }

    @Test
    fun `a double tap submits exactly once`() {
        val h = Harness()
        h.prompts.register("s1", "req-7", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        h.bridge.handleAnswerReq(listOf("s1", "req-7", RcPrompts.APPROVE))
        h.bridge.handleAnswerReq(listOf("s1", "req-7", RcPrompts.APPROVE))
        assertEquals(1, h.permissionCalls.size)
    }

    @Test
    fun `a prompt already resolved on the phone is not answerable from the glasses`() {
        val h = Harness()
        h.prompts.register("s1", "req-7", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        h.prompts.resolve("s1", "req-7")
        h.bridge.handleAnswerReq(listOf("s1", "req-7", RcPrompts.APPROVE))
        assertEquals(emptyList<List<String?>>(), h.permissionCalls)
        assertEquals(emptyList<List<String>>(), h.userResponseCalls)
    }

    @Test
    fun `an answer naming another session resolves nothing`() {
        val h = Harness()
        h.prompts.register("s1", "req-7", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        h.bridge.handleAnswerReq(listOf("s2", "req-7", RcPrompts.APPROVE))
        assertEquals("a request id is only meaningful inside its own session",
            emptyList<List<String?>>(), h.permissionCalls)
        assertEquals(emptyList<List<String>>(), h.userResponseCalls)
    }

    @Test
    fun `an unknown prompt sends nothing at all`() {
        val h = Harness()
        h.bridge.handleAnswerReq(listOf("s1", "req-nope", RcPrompts.APPROVE))
        assertEquals(emptyList<List<String?>>(), h.permissionCalls)
        assertEquals("an unrouted answer must not fall through to free text",
            emptyList<List<String>>(), h.userResponseCalls)
    }

    @Test
    fun `a label that was never offered is refused but leaves the prompt answerable`() {
        val h = Harness()
        h.prompts.register("s1", "req-7", "AskUserQuestion", listOf("Postgres"))
        h.bridge.handleAnswerReq(listOf("s1", "req-7", "rm -rf /"))
        assertEquals(emptyList<List<String?>>(), h.permissionCalls)
        h.bridge.handleAnswerReq(listOf("s1", "req-7", "Postgres"))
        assertEquals(listOf(listOf("s1", "req-7", "true", null, "Postgres")), h.permissionCalls)
    }
}
