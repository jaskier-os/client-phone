package com.repository.listener.wear

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
            // TalkBack must read the real diagnosis, not the abbreviated slot copy.
            assertTrue(
                "$state description drops the state label",
                description.contains(state.label),
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
