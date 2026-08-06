package com.repository.listener.protocol

import com.repository.listener.protocol.RemoteInputProtocol.StatusFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards two whole-system invariants that no component owns on its own, and that
 * have each already shipped broken.
 *
 * Both defects presented identically to the user -- the watch reading
 * "Phone app stopped - open it" while the phone was demonstrably running -- and
 * neither was visible in any single-component test, because neither is a property
 * of any single component.
 */
class LinkTimingCoherenceTest {

    /**
     * A keepalive slower than the timeout it exists to defeat is not a tuning
     * problem, it is a guaranteed failure on a healthy link.
     *
     * PING_IDLE_BACKOFF_MS was 30 s against a 20 s glasses session expiry, so every
     * idle session died ~10 s before its own keepalive was due; the watch observed
     * ~29 s of silence and reopened, forever. This is the SECOND instance of the
     * class here: STATUS_TIMEOUT_MS was previously below the same backoff.
     */
    @Test
    fun keepaliveCadenceBeatsEverySessionTimeoutItRacies() {
        RemoteInputProtocol.assertTimingCoherent()

        assertTrue(
            "idle keepalive (${RemoteInputProtocol.PING_IDLE_BACKOFF_MS} ms) must beat the " +
                "glasses session expiry (${RemoteInputProtocol.SESSION_EXPIRY_MS} ms)",
            RemoteInputProtocol.PING_IDLE_BACKOFF_MS < RemoteInputProtocol.SESSION_EXPIRY_MS,
        )
        // Margin, not just ordering: a cadence one millisecond under the expiry
        // still dies on any transport delay, and the measured round trip reaches
        // ~1 s. Require enough room for several of those.
        val margin = RemoteInputProtocol.SESSION_EXPIRY_MS - RemoteInputProtocol.PING_IDLE_BACKOFF_MS
        assertTrue(
            "only ${margin}ms of margin between the idle keepalive and session expiry; " +
                "a single delayed frame would expire the session",
            margin >= 5_000L,
        )
    }

    /**
     * The status fold must be able to reach a healthy state from its own starting
     * value.
     *
     * [StatusFlags.applyAdvisory] AND-folds the health bits so an unauthenticated
     * frame can never assert health the watch has not observed. That makes a
     * health-bits-clear starting value an ABSORBING state: `0 AND anything == 0`,
     * so no number of perfectly healthy frames can ever turn a health bit on, and
     * the watch reports the phone app stopped forever.
     *
     * This asserts the property (reachability), not the seed constant, so it stays
     * meaningful if the seed is expressed differently later.
     */
    @Test
    fun aHealthyLinkIsReachableFromTheWatchsStartingStatus() {
        val healthy = StatusFlags.encode(
            glassesLinkUp = true,
            phoneServiceAlive = true,
            lastSendDropped = false,
            glassesSinkAttached = true,
            wakingGlasses = false,
        )
        val healthyBits = StatusFlags.decode(healthy)

        val seed = StatusFlags.GLASSES_LINK_UP or
            StatusFlags.PHONE_SERVICE_ALIVE or
            StatusFlags.GLASSES_SINK_ATTACHED

        var bits = seed
        repeat(3) { bits = StatusFlags.applyAdvisory(bits, healthyBits, trusted = false) }

        assertTrue(
            "PHONE_SERVICE_ALIVE must be reachable, or the watch shows " +
                "'Phone app stopped' on a healthy link",
            StatusFlags.isSet(bits, StatusFlags.PHONE_SERVICE_ALIVE),
        )
        assertTrue(StatusFlags.isSet(bits, StatusFlags.GLASSES_LINK_UP))
        assertTrue(StatusFlags.isSet(bits, StatusFlags.GLASSES_SINK_ATTACHED))
        assertEquals("a healthy fold must reach exactly the reported state", healthyBits, bits)
    }

    /**
     * The zero seed is the actual shipped bug, stated as its own case so the reason
     * the seed exists cannot be optimised away by someone who reads only the field.
     */
    @Test
    fun aClearedHealthMaskIsAnAbsorbingStateAndThereforeAnInvalidSeed() {
        val healthyBits = StatusFlags.decode(
            StatusFlags.encode(
                glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                glassesSinkAttached = true, wakingGlasses = false,
            )
        )
        var bits = 0
        repeat(10) { bits = StatusFlags.applyAdvisory(bits, healthyBits, trusted = false) }
        assertEquals(
            "0 is absorbing under the health AND-fold; this is why the seed is not 0",
            0, bits,
        )
    }

    /**
     * The containment the AND-fold exists for must survive the seed: once the watch
     * has folded in a failure, an unauthenticated frame still cannot clear it.
     */
    @Test
    fun aForgedFrameStillCannotClearAnObservedFailure() {
        val seed = StatusFlags.GLASSES_LINK_UP or
            StatusFlags.PHONE_SERVICE_ALIVE or
            StatusFlags.GLASSES_SINK_ATTACHED

        // The phone honestly reports its service is gone.
        val stopped = StatusFlags.decode(
            StatusFlags.encode(
                glassesLinkUp = false, phoneServiceAlive = false, lastSendDropped = true,
                glassesSinkAttached = false, wakingGlasses = false,
            )
        )
        var bits = StatusFlags.applyAdvisory(seed, stopped, trusted = false)
        assertTrue(
            "the failure must be observed",
            !StatusFlags.isSet(bits, StatusFlags.PHONE_SERVICE_ALIVE),
        )

        // A forged "everything is fine" must not undo it.
        val forgedHealthy = StatusFlags.decode(
            StatusFlags.encode(
                glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                glassesSinkAttached = true, wakingGlasses = false,
            )
        )
        repeat(5) { bits = StatusFlags.applyAdvisory(bits, forgedHealthy, trusted = false) }
        assertTrue(
            "a forged frame must never assert health over an observed failure",
            !StatusFlags.isSet(bits, StatusFlags.PHONE_SERVICE_ALIVE),
        )
        assertTrue(
            "a latched problem bit must not be clearable by a forged frame",
            StatusFlags.isSet(bits, StatusFlags.LAST_SEND_DROPPED),
        )
    }

    /**
     * The absorbing-bit class, stated as an invariant over EVERY bit in both directions
     * rather than as a case for whichever one was last reported.
     *
     * Health direction: `applyAdvisory` is only ever called with `trusted = false`, and
     * health was re-seeded only in `openSession()`, which runs once per process. So one
     * `replyPhoneStopped` during the ordinary cold-start race pinned the watch at "Phone
     * service down" for the life of the process.
     *
     * Problem direction: problem bits are OR-folded and nothing lowered them, so a single
     * refusal pinned the watch at "Not allowed here" indefinitely while every subsequent
     * frame from the phone reported no refusal at all.
     */
    @Test
    fun noBitIsAbsorbing() {
        StatusFlags.assertNoAbsorbingBit()
    }

    /**
     * The problem-bit clear must be earned by CORRELATION, exactly like the health-bit
     * recovery. Without this the fix for the latch would hand any writer on this
     * unauthenticated channel the power to erase a failure the watch observed.
     */
    @Test
    fun anUncorrelatedFrameCannotClearAProblemBit() {
        val healthy = StatusFlags.decode(
            StatusFlags.encode(
                glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                glassesSinkAttached = true, wakingGlasses = false,
            )
        )
        val refusing = StatusFlags.decode(
            StatusFlags.encode(
                glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                glassesSinkAttached = true, wakingGlasses = false,
                glassesRefusingInput = true, refusalReason = RemoteInputProtocol.RefusalReason.LOCKED,
            )
        )
        var bits = StatusFlags.foldStatus(healthy, refusing, correlated = true)
        assertTrue(
            "the refusal must be observed",
            StatusFlags.isSet(bits, StatusFlags.GLASSES_REFUSING_INPUT),
        )
        repeat(5) { bits = StatusFlags.foldStatus(bits, healthy, correlated = false) }
        assertTrue(
            "an uncorrelated frame must never clear an observed refusal",
            StatusFlags.isSet(bits, StatusFlags.GLASSES_REFUSING_INPUT),
        )
        bits = StatusFlags.foldStatus(bits, healthy, correlated = true)
        assertTrue(
            "a correlated frame reporting no refusal must clear it",
            !StatusFlags.isSet(bits, StatusFlags.GLASSES_REFUSING_INPUT),
        )
    }

    /**
     * The recovery must be earned by CORRELATION, not granted to anyone who can write to
     * the channel -- otherwise the fix for the latch would undo the containment that the
     * AND-fold exists to provide.
     */
    @Test
    fun onlyACorrelatedFrameCanClearALatchedHealthBit() {
        val healthy = StatusFlags.decode(
            StatusFlags.encode(
                glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                glassesSinkAttached = true, wakingGlasses = false,
            )
        )
        val latchedOff = StatusFlags.GLASSES_LINK_UP or StatusFlags.GLASSES_SINK_ATTACHED

        var uncorrelated = latchedOff
        repeat(5) {
            uncorrelated = StatusFlags.foldStatus(uncorrelated, healthy, correlated = false)
        }
        assertTrue(
            "an unsolicited frame must not be able to assert health the watch latched off",
            !StatusFlags.isSet(uncorrelated, StatusFlags.PHONE_SERVICE_ALIVE),
        )

        val recovered = StatusFlags.foldStatus(latchedOff, healthy, correlated = true)
        assertTrue(
            "a frame answering our own PING must be able to restore health, or the watch " +
                "is stuck at 'Phone service down' until the app is killed",
            StatusFlags.isSet(recovered, StatusFlags.PHONE_SERVICE_ALIVE),
        )
    }

    /**
     * The refusal signal is a PROBLEM bit, so it must OR in like the other problem bits
     * and must survive alongside a healthy link -- the whole point is that it reports the
     * case where the link is fine and the glasses declined anyway.
     */
    @Test
    fun theRefusalSignalRidesAlongsideAHealthyLink() {
        val refusing = StatusFlags.decode(
            StatusFlags.encode(
                glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                glassesSinkAttached = true, wakingGlasses = false,
                glassesRefusingInput = true,
                refusalReason = RemoteInputProtocol.RefusalReason.FOLDED,
            )
        )
        assertTrue(StatusFlags.isSet(refusing, StatusFlags.GLASSES_REFUSING_INPUT))
        assertTrue(
            "the link must still read healthy: 'refusing' is not 'disconnected'",
            StatusFlags.isSet(refusing, StatusFlags.GLASSES_LINK_UP),
        )
        assertEquals(
            RemoteInputProtocol.RefusalReason.FOLDED,
            StatusFlags.decodeReason(refusing),
        )

        // And the reason survives the fold, so the watch can say WHY rather than just that
        // something is wrong.
        val folded = StatusFlags.foldStatus(
            current = StatusFlags.GLASSES_LINK_UP or StatusFlags.PHONE_SERVICE_ALIVE or
                StatusFlags.GLASSES_SINK_ATTACHED,
            received = refusing,
            correlated = true,
        )
        assertTrue(StatusFlags.isSet(folded, StatusFlags.GLASSES_REFUSING_INPUT))
        assertEquals(
            RemoteInputProtocol.RefusalReason.FOLDED,
            StatusFlags.decodeReason(folded),
        )
    }

    /**
     * An older reader masks only the low five bits. Packing the reason above them must
     * therefore leave every pre-existing bit exactly as it was.
     */
    @Test
    fun theReasonBitsDoNotDisturbTheOriginalFlags() {
        val legacyMask = StatusFlags.GLASSES_LINK_UP or StatusFlags.PHONE_SERVICE_ALIVE or
            StatusFlags.LAST_SEND_DROPPED or StatusFlags.GLASSES_SINK_ATTACHED or
            StatusFlags.WAKING_GLASSES
        val withoutReason = StatusFlags.decode(
            StatusFlags.encode(
                glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                glassesSinkAttached = true, wakingGlasses = false,
            )
        )
        for (reason in RemoteInputProtocol.RefusalReason.values()) {
            val withReason = StatusFlags.decode(
                StatusFlags.encode(
                    glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                    glassesSinkAttached = true, wakingGlasses = false,
                    glassesRefusingInput = true, refusalReason = reason,
                )
            )
            assertEquals(
                "packing reason $reason must not disturb the flags an older reader sees",
                withoutReason and legacyMask,
                withReason and legacyMask,
            )
            assertEquals(reason, StatusFlags.decodeReason(withReason))
        }
    }
}
