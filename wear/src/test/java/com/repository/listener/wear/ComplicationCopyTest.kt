package com.repository.listener.wear

import com.repository.listener.protocol.RemoteInputProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the state -> complication text mapping.
 *
 * The point of these tests is that a complication slot silently ellipsises text
 * that does not fit, so an overrun does not fail loudly on the device -- it just
 * renders "Discon..." forever. These assertions are the only place that failure
 * mode is caught.
 */
class ComplicationCopyTest {

    @Test
    fun `every state has short text within the slot budget`() {
        // Iterating LinkState.values() rather than listing states means a new
        // enum constant fails here instead of shipping with blank copy.
        for (state in LinkState.values()) {
            val text = ComplicationCopy.shortText(state)
            assertTrue("$state short text is blank", text.isNotBlank())
            assertTrue(
                "$state short text \"$text\" is ${text.length} chars, " +
                    "over the ${ComplicationCopy.SHORT_TEXT_MAX} budget",
                text.length <= ComplicationCopy.SHORT_TEXT_MAX,
            )
        }
    }

    @Test
    fun `every state has long text within the slot budget`() {
        for (state in LinkState.values()) {
            val text = ComplicationCopy.longText(state)
            assertTrue("$state long text is blank", text.isNotBlank())
            assertTrue(
                "$state long text \"$text\" is ${text.length} chars, " +
                    "over the ${ComplicationCopy.LONG_TEXT_MAX} budget",
                text.length <= ComplicationCopy.LONG_TEXT_MAX,
            )
        }
    }

    @Test
    fun `no state renders an ellipsis or truncation marker`() {
        // Copy is authored to fit, never cut. A marker here would mean somebody
        // reintroduced a substring call.
        for (state in LinkState.values()) {
            for (text in listOf(
                ComplicationCopy.shortText(state),
                ComplicationCopy.longText(state),
            )) {
                assertTrue("$state copy \"$text\" is truncated", !text.contains("\u2026"))
                assertTrue("$state copy \"$text\" is truncated", !text.endsWith("..."))
            }
        }
    }

    @Test
    fun `every state has a content description naming the app and the state`() {
        for (state in LinkState.values()) {
            val description = ComplicationCopy.contentDescription(state)
            assertTrue(description.contains("Glasses Remote"))
            // TalkBack must read the real diagnosis, not the abbreviated slot copy,
            // and it must carry the actionable half too: a non-sighted user gets
            // the whole message from this string or from nothing.
            assertTrue(
                "$state description drops the state title",
                description.contains(state.title),
            )
            assertTrue(
                "$state description drops the state hint",
                state.hint.isEmpty() || description.contains(state.hint),
            )
        }
    }

    @Test
    fun `every state has a title and a hint that are honest and distinct`() {
        val titles = mutableSetOf<String>()
        for (state in LinkState.values()) {
            assertTrue("$state has an empty title", state.title.isNotEmpty())
            assertTrue("$state has an empty hint", state.hint.isNotEmpty())
            // Two states rendering the same headline would make them
            // indistinguishable on screen, which is exactly the failure mode a
            // status readout exists to prevent.
            assertTrue("$state duplicates the title \"${state.title}\"", titles.add(state.title))
            // The title is the glance line; it must fit the round display.
            assertTrue(
                "$state title \"${state.title}\" is too long to glance at",
                state.title.length <= 20,
            )
            assertTrue(
                "$state hint \"${state.hint}\" is too long for two lines",
                state.hint.length <= 34,
            )
        }
    }

    @Test
    fun `severity agrees with whether input can actually land`() {
        for (state in LinkState.values()) {
            // LIVE claims input is being accepted right now, so it may never be
            // attached to a state that refuses input -- that is the specific lie
            // this app has been burned by before.
            if (state.severity == LinkSeverity.LIVE) {
                assertTrue("$state is LIVE but refuses input", state.inputEnabled)
            }
            if (state.severity == LinkSeverity.BLOCKED) {
                assertTrue("$state is BLOCKED but accepts input", !state.inputEnabled)
            }
        }
    }

    @Test
    fun `only states that can hang show an elapsed timer`() {
        for (state in LinkState.values()) {
            if (state.showsElapsed) {
                assertTrue(
                    "$state shows elapsed but is not a transient",
                    state.severity == LinkSeverity.WORKING,
                )
            }
        }
    }

    /** Every health bit set, no problem bits. */
    private fun healthyBits(): Int {
        val flags = RemoteInputProtocol.StatusFlags
        return flags.GLASSES_LINK_UP or flags.PHONE_SERVICE_ALIVE or flags.GLASSES_SINK_ATTACHED
    }

    private fun stateFor(bits: Int): LinkState = LinkState.fromStatus(
        bits = bits,
        bluetoothOn = true,
        phoneNodeKnown = true,
        everSawPhoneNode = true,
        statusFresh = true,
    )

    @Test
    fun `only READY confirms input, and it is the only LIVE state`() {
        // inputConfirmed drives the CONFIRM tap haptic, which asserts to a user
        // who is NOT looking that their tap will land. Exactly one state may make
        // that claim, and only where the chain is healthy and nothing refuses.
        assertEquals(listOf(LinkState.READY), LinkState.values().filter { it.inputConfirmed })

        for (state in LinkState.values()) {
            if (!state.inputConfirmed) continue
            assertTrue("$state confirms input but does not send", state.inputEnabled)
            assertTrue("$state confirms input while refusing", !state.isRefusal)
            assertEquals("$state confirms input but is not LIVE", LinkSeverity.LIVE, state.severity)
        }
    }

    @Test
    fun `a refusal never claims input is confirmed, and always keeps sending`() {
        val refusals = LinkState.values().filter { it.isRefusal }
        assertTrue("no refusal states are defined", refusals.isNotEmpty())
        for (state in refusals) {
            assertTrue("$state refuses yet confirms input", !state.inputConfirmed)
            // A refusal describes the glasses a moment ago. Gating sends on it
            // would remove the double tap that gets the user back out.
            assertTrue("$state stops sending on a stale refusal", state.inputEnabled)
        }
    }

    @Test
    fun `every refusal reason maps to its own state, title and slot word`() {
        // The whole point of carrying a reason is that "not allowed here",
        // "folded" and "in use" send the user to different actions. If any two
        // collapse to the same state or the same words, the reason was wasted.
        val mapped = RemoteInputProtocol.RefusalReason.values().map { reason ->
            stateFor(RemoteInputProtocol.StatusFlags.encodeReason(
                healthyBits() or RemoteInputProtocol.StatusFlags.GLASSES_REFUSING_INPUT,
                reason,
            ))
        }
        assertEquals("reasons collapsed to one state", mapped.size, mapped.distinct().size)
        for (state in mapped) {
            assertTrue("$state is not flagged as a refusal", state.isRefusal)
        }
        assertEquals(
            "refusal titles are not distinct",
            mapped.size,
            mapped.map { it.title }.distinct().size,
        )
        assertEquals(
            "refusal complication words are not distinct",
            mapped.size,
            mapped.map { ComplicationCopy.shortText(it) }.distinct().size,
        )
    }

    @Test
    fun `an unknown refusal reason degrades to the honest generic`() {
        // A reason code newer than this build must never render as READY.
        val state = stateFor(
            healthyBits() or RemoteInputProtocol.StatusFlags.GLASSES_REFUSING_INPUT,
        )
        assertEquals(LinkState.REFUSED, state)
        assertTrue("an unknown refusal claimed input was confirmed", !state.inputConfirmed)
    }

    @Test
    fun `a healthy chain with no refusal is READY`() {
        assertEquals(LinkState.READY, stateFor(healthyBits()))
    }

    @Test
    fun `a refusal never masks a real link fault`() {
        val flags = RemoteInputProtocol.StatusFlags
        val linkDown = stateFor(
            (healthyBits() and flags.GLASSES_LINK_UP.inv()) or flags.GLASSES_REFUSING_INPUT,
        )
        assertEquals("a refusal masked a down link", LinkState.PHONE_ONLY, linkDown)
    }

    @Test
    fun `a refusal never shows a full health arc`() {
        // The arc reads as "can I use this right now". A full arc while every tap
        // is declined is the false reassurance this signal exists to end.
        for (state in LinkState.values().filter { it.isRefusal }) {
            assertTrue(
                "$state shows a full health arc while refusing",
                ComplicationCopy.healthRank(state) < ComplicationCopy.HEALTH_MAX,
            )
        }
    }

    @Test
    fun `health rank stays inside the ranged value bounds`() {
        for (state in LinkState.values()) {
            val rank = ComplicationCopy.healthRank(state)
            assertTrue("$state rank $rank below 0", rank >= 0f)
            assertTrue(
                "$state rank $rank above ${ComplicationCopy.HEALTH_MAX}",
                rank <= ComplicationCopy.HEALTH_MAX,
            )
        }
    }

    @Test
    fun `only READY reaches full health and only dead states reach zero`() {
        assertEquals(ComplicationCopy.HEALTH_MAX, ComplicationCopy.healthRank(LinkState.READY), 0f)

        for (state in LinkState.values()) {
            if (state != LinkState.READY) {
                assertNotEquals(
                    "$state must not claim full health",
                    ComplicationCopy.HEALTH_MAX,
                    ComplicationCopy.healthRank(state),
                    0f,
                )
            }
        }

        // A state where the watch cannot even see the phone must read as zero, so
        // the arc is empty rather than partially filled.
        for (state in listOf(
            LinkState.BT_OFF,
            LinkState.SETUP,
            LinkState.UNPAIRED,
            LinkState.UNREACHABLE,
        )) {
            assertEquals("$state must be zero health", 0f, ComplicationCopy.healthRank(state), 0f)
        }
    }

    @Test
    fun `states with input enabled never rank as dead`() {
        // The rank is what the user reads at a glance. Showing an empty arc while
        // the bezel actually works would be a lie.
        for (state in LinkState.values()) {
            if (state.inputEnabled) {
                assertTrue(
                    "$state accepts input but ranks 0",
                    ComplicationCopy.healthRank(state) > 0f,
                )
            }
        }
    }

    @Test
    fun `distinguishable states do not collapse to the same short text`() {
        // Two different problems showing the same word would make the complication
        // useless for diagnosis, which is its whole purpose.
        val texts = LinkState.values().map { ComplicationCopy.shortText(it) }
        assertEquals(
            "duplicate short text: ${texts.groupBy { it }.filter { it.value.size > 1 }.keys}",
            texts.size,
            texts.distinct().size,
        )
    }

    @Test
    fun `long text is distinct per state as well`() {
        val texts = LinkState.values().map { ComplicationCopy.longText(it) }
        assertEquals(texts.size, texts.distinct().size)
    }

    @Test
    fun `the inactive copy fits the same budgets as a real state`() {
        // The null-state copy renders in the same slots, so it is subject to the
        // same limits; it just does not come from the enum.
        assertTrue(ComplicationCopy.INACTIVE_SHORT.isNotBlank())
        assertTrue(
            "inactive short text \"${ComplicationCopy.INACTIVE_SHORT}\" overruns",
            ComplicationCopy.INACTIVE_SHORT.length <= ComplicationCopy.SHORT_TEXT_MAX,
        )
        assertTrue(ComplicationCopy.INACTIVE_LONG.isNotBlank())
        assertTrue(
            "inactive long text \"${ComplicationCopy.INACTIVE_LONG}\" overruns",
            ComplicationCopy.INACTIVE_LONG.length <= ComplicationCopy.LONG_TEXT_MAX,
        )
        assertTrue(ComplicationCopy.INACTIVE_DESCRIPTION.contains("Glasses Remote"))
    }

    @Test
    fun `the inactive copy is distinct from every real state`() {
        // The whole point of the inactive copy is that a dead service does not
        // masquerade as SETUP. If it collided with any state it would be useless.
        for (state in LinkState.values()) {
            assertNotEquals(
                "inactive short text collides with $state",
                ComplicationCopy.shortText(state),
                ComplicationCopy.INACTIVE_SHORT,
            )
            assertNotEquals(
                "inactive long text collides with $state",
                ComplicationCopy.longText(state),
                ComplicationCopy.INACTIVE_LONG,
            )
        }
    }

    @Test
    fun `the title fits alongside the short text`() {
        assertTrue(ComplicationCopy.TITLE.isNotBlank())
        assertTrue(ComplicationCopy.TITLE.length <= ComplicationCopy.SHORT_TEXT_MAX)
    }
}
