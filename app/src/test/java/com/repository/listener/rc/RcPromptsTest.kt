package com.repository.listener.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt options a mirrored RC session offers the glasses, and where an answer to each is
 * routed.
 *
 * Two prompt families reach the mirror and they resolve through DIFFERENT orchestrator calls:
 *
 *  - `rc_permission_request` -> `sendRcPermissionResponse(requestId, approved, mode, reason)`.
 *    An `AskUserQuestion` carries its options inside `toolArgs`; every other tool is approve/deny.
 *  - `rc_user_input` -> `sendRcUserResponse(requestId, text)`. Free text the orchestrator does not
 *    enumerate, so it is never offered as a list and never registered here.
 *
 * Answering a permission through the user-response call would leave the permission pending, so the
 * routing is asserted per family rather than assumed.
 */
class RcPromptsTest {

    private val askArgs = """
        {"questions":[{"question":"Which database?","options":[
          {"label":"Postgres","description":"relational"},
          {"label":"SQLite","description":"embedded"}]}]}
    """.trimIndent()

    // --- option extraction -------------------------------------------------

    @Test
    fun `AskUserQuestion options come from toolArgs labels`() {
        assertEquals(listOf("Postgres", "SQLite"), RcPrompts.optionsFor("AskUserQuestion", askArgs))
    }

    @Test
    fun `a multi-question AskUserQuestion offers every question's labels in order`() {
        val args = """
            {"questions":[
              {"question":"a","options":[{"label":"A1"},{"label":"A2"}]},
              {"question":"b","options":[{"label":"B1"}]}]}
        """.trimIndent()
        assertEquals(listOf("A1", "A2", "B1"), RcPrompts.optionsFor("AskUserQuestion", args))
    }

    @Test
    fun `an AskUserQuestion with unreadable args offers nothing rather than a fake choice`() {
        assertEquals(emptyList<String>(), RcPrompts.optionsFor("AskUserQuestion", "not json"))
        assertEquals(emptyList<String>(), RcPrompts.optionsFor("AskUserQuestion", "{}"))
        assertEquals(emptyList<String>(),
            RcPrompts.optionsFor("AskUserQuestion", """{"questions":[{"question":"q"}]}"""))
    }

    @Test
    fun `every other tool is an approve-or-reject permission`() {
        assertEquals(listOf(RcPrompts.APPROVE, RcPrompts.REJECT), RcPrompts.optionsFor("Bash", "{}"))
        assertEquals(listOf(RcPrompts.APPROVE, RcPrompts.REJECT),
            RcPrompts.optionsFor("ExitPlanMode", """{"plan":"do things"}"""))
    }

    @Test
    fun `an option list is capped so one prompt cannot blow the frame`() {
        val opts = (1..40).joinToString(",") { """{"label":"opt$it"}""" }
        val parsed = RcPrompts.optionsFor("AskUserQuestion",
            """{"questions":[{"question":"q","options":[$opts]}]}""")
        assertEquals(RcPrompts.MAX_OPTIONS, parsed.size)
    }

    // --- routing -----------------------------------------------------------

    @Test
    fun `an AskUserQuestion answer resolves the permission with the label as the reason`() {
        val r = RcPromptRegistry()
        r.register("s1", "req-1", "AskUserQuestion", listOf("Postgres", "SQLite"))
        assertEquals(RcPromptAnswer.Permission(approved = true, reason = "SQLite"),
            r.route("s1", "req-1", "SQLite"))
    }

    @Test
    fun `approve and reject map to the permission verdict, never to a reason`() {
        val r = RcPromptRegistry()
        r.register("s1", "req-1", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        assertEquals(RcPromptAnswer.Permission(approved = true, reason = null),
            r.route("s1", "req-1", RcPrompts.APPROVE))
        r.register("s1", "req-2", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        assertEquals(RcPromptAnswer.Permission(approved = false, reason = null),
            r.route("s1", "req-2", RcPrompts.REJECT))
    }

    @Test
    fun `an option that was never offered is refused instead of forwarded`() {
        val r = RcPromptRegistry()
        r.register("s1", "req-1", "AskUserQuestion", listOf("Postgres"))
        assertNull("only an offered label may be answered", r.route("s1", "req-1", "DROP TABLE"))
        // Refusing must not consume the prompt: the real answer still has to work.
        assertEquals(RcPromptAnswer.Permission(true, "Postgres"), r.route("s1", "req-1", "Postgres"))
    }

    @Test
    fun `a second answer to the same prompt is refused`() {
        val r = RcPromptRegistry()
        r.register("s1", "req-1", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        assertEquals(RcPromptAnswer.Permission(true, null), r.route("s1", "req-1", RcPrompts.APPROVE))
        assertNull("a double tap must not submit twice", r.route("s1", "req-1", RcPrompts.APPROVE))
        assertNull("nor may it flip the verdict", r.route("s1", "req-1", RcPrompts.REJECT))
    }

    @Test
    fun `a prompt resolved on the phone can no longer be answered on the glasses`() {
        val r = RcPromptRegistry()
        r.register("s1", "req-1", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        r.resolve("s1", "req-1")
        assertNull("a stale answer must not reach the orchestrator",
            r.route("s1", "req-1", RcPrompts.APPROVE))
    }

    @Test
    fun `an unknown request id is refused`() {
        assertNull(RcPromptRegistry().route("s1", "never-seen", RcPrompts.APPROVE))
    }

    /**
     * A request id is unique to its own session, but the glasses name the session separately in the
     * frame. Answering session A's prompt while B's thread is open would resolve a tool call the
     * wearer never saw, so the session is part of the key, not a passenger.
     */
    @Test
    fun `a prompt cannot be answered under another session's name`() {
        val r = RcPromptRegistry()
        r.register("s1", "req-7", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        assertNull("a cross-session answer must not resolve anything",
            r.route("s2", "req-7", RcPrompts.APPROVE))
        assertEquals("and it must not consume the prompt either",
            RcPromptAnswer.Permission(true, null), r.route("s1", "req-7", RcPrompts.APPROVE))
    }

    @Test
    fun `ending a session drops only its own pending prompts`() {
        val r = RcPromptRegistry()
        r.register("s1", "req-1", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        r.register("s2", "req-2", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        r.clearSession("s1")
        assertNull("the ended session's prompt is gone", r.route("s1", "req-1", RcPrompts.APPROVE))
        assertEquals("the other session is untouched",
            RcPromptAnswer.Permission(true, null), r.route("s2", "req-2", RcPrompts.APPROVE))
    }

    @Test
    fun `an over-long label is clipped so it cannot push the list off the waveguide`() {
        val long = "x".repeat(RcPrompts.MAX_OPTION_CHARS + 40)
        val parsed = RcPrompts.optionsFor("AskUserQuestion",
            """{"questions":[{"question":"q","options":[{"label":"$long"}]}]}""")
        assertEquals(RcPrompts.MAX_OPTION_CHARS, parsed.single().length)
        assertTrue("the clip must be visible, not silent", parsed.single().endsWith("..."))
    }

    /**
     * Two questions may name the same label. Offered twice they would be indistinguishable once
     * chosen -- the answer is the label string, so the second entry could never be selected.
     */
    @Test
    fun `a label repeated across questions is offered once`() {
        val parsed = RcPrompts.optionsFor("AskUserQuestion", """
            {"questions":[
              {"question":"a","options":[{"label":"Yes"},{"label":"No"}]},
              {"question":"b","options":[{"label":"Yes"},{"label":"Maybe"}]}]}
        """.trimIndent())
        assertEquals(listOf("Yes", "No", "Maybe"), parsed)
    }

    @Test
    fun `answering a newer prompt leaves an older pending one answerable`() {
        val r = RcPromptRegistry()
        r.register("s1", "old", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        r.register("s1", "new", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        assertEquals(RcPromptAnswer.Permission(true, null), r.route("s1", "new", RcPrompts.APPROVE))
        assertEquals("consuming one prompt must not consume the rest",
            RcPromptAnswer.Permission(false, null), r.route("s1", "old", RcPrompts.REJECT))
    }

    @Test
    fun `a prompt with no options is never registered and never answerable`() {
        val r = RcPromptRegistry()
        r.register("s1", "req-1", "AskUserQuestion", emptyList())
        assertNull("free text has no option to route", r.route("s1", "req-1", "anything"))
    }

    @Test
    fun `the registry is bounded so a long session cannot grow it without limit`() {
        val r = RcPromptRegistry()
        repeat(RcPromptRegistry.MAX_PENDING + 5) {
            r.register("s1", "req-$it", "Bash", listOf(RcPrompts.APPROVE, RcPrompts.REJECT))
        }
        assertNull("the oldest pending prompt is shed first", r.route("s1", "req-0", RcPrompts.APPROVE))
        assertTrue("the newest stays answerable",
            r.route("s1", "req-${RcPromptRegistry.MAX_PENDING + 4}", RcPrompts.APPROVE) != null)
    }
}
