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
}
