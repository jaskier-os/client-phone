package com.repository.listener.protocol

/**
 * Session identity rules: how a session id is minted on the watch, and how a
 * receiver decides whether to accept an OPEN.
 *
 * These exist because an earlier design minted `sid` as
 * `elapsedRealtime XOR Random.nextInt()` and reset `lastSeq = 0` on every OPEN.
 * Two consequences, both real:
 *
 *  1. REPLAY. An attacker who captured one session could replay its OPEN (valid
 *     tag, TTL-exempt) to reset `lastSeq` to 0, then replay every captured event
 *     in order -- all with valid tags, all passing the monotonic-seq rule. That
 *     rule was the entire stated reason for not needing a nonce, and it did not
 *     survive.
 *  2. UNORDERED SIDS. A random `sid` cannot be compared, so the "older sid ->
 *     drop" rule was not implementable as a numeric comparison at all.
 *
 * The fix is to make `sid` genuinely monotonic and persisted, which closes the
 * replay window without a challenge round trip.
 *
 * Deliberately Android-free so the rules are unit-testable on the JVM; the watch
 * supplies SharedPreferences-backed persistence and the glasses supply their own.
 */
object SessionIdentity {

    /** Persistence keys, shared so the watch and any test harness agree. */
    const val PREF_FILE = "remote_input_session"
    const val KEY_LAST_SID = "last_sid"

    /** The first session id ever minted. 0 is reserved as "never minted". */
    const val FIRST_SID = 1

    /**
     * Mints the next session id from the previously persisted one.
     *
     * The caller MUST persist the result BEFORE sending the first frame; a crash
     * between minting and persisting would otherwise reuse a sid, and a reused sid
     * with a lower `lastSeq` is exactly the replay window we are closing.
     *
     * Wrap behaviour is defined rather than left implicit: `sid` is a uint32 and
     * one increment per app session start makes wrap unreachable in practice, but
     * if it ever happened the counter returns to [FIRST_SID] rather than to 0
     * (reserved) or to a negative Int. The receiver compares with a WRAP-SAFE
     * signed difference, so it reads the wrapped value as NEWER and accepts the
     * session -- the source is not locked out. A plain `<=` would refuse every
     * subsequent session forever, which is precisely why the comparison is
     * wrap-safe.
     */
    fun mintNextSid(previousSid: Int): Int {
        val next = previousSid + 1
        // Skip 0 on wrap: 0 means "never minted" on the receiver side.
        return if (next == 0) FIRST_SID else next
    }

    /** What a receiver should do with an OPEN frame. */
    enum class OpenDecision {
        /** New session, strictly newer than anything seen. Reset lastSeq to 0. */
        ACCEPT_NEW_SESSION,

        /**
         * An OPEN for the session already in progress. Accept it as liveness, but
         * PRESERVE lastSeq. This is what makes a replayed OPEN harmless: it can no
         * longer rewind the receiver's sequence state.
         */
        ACCEPT_PRESERVE_SEQ,

        /** Older than the stored session: a replay. Reject and log. */
        REJECT_REPLAY,
    }

    /**
     * Decides how to handle an OPEN, using a wrap-safe signed comparison so uint32
     * wraparound does not permanently lock out the source.
     *
     * @param incomingSid the sid on the OPEN frame.
     * @param storedSid the highest sid this receiver has durably recorded for this
     *        src, or 0 if it has never seen one.
     */
    fun decideOpen(incomingSid: Int, storedSid: Int): OpenDecision {
        // 0 is reserved: a source that never minted cannot open a session.
        if (incomingSid == 0) return OpenDecision.REJECT_REPLAY
        if (storedSid == 0) return OpenDecision.ACCEPT_NEW_SESSION
        val diff = RemoteInputProtocol.seqDifference(incomingSid, storedSid)
        return when {
            diff > 0 -> OpenDecision.ACCEPT_NEW_SESSION
            diff == 0 -> OpenDecision.ACCEPT_PRESERVE_SEQ
            else -> OpenDecision.REJECT_REPLAY
        }
    }

    /**
     * Whether a non-OPEN frame's sid is acceptable. Events for a sid older than the
     * stored one are replays; events for a newer sid arrived before their OPEN and
     * are accepted, since OPEN can be lost and refusing them would strand the
     * session until expiry.
     */
    fun isAcceptableEventSid(incomingSid: Int, storedSid: Int): Boolean {
        if (incomingSid == 0) return false
        if (storedSid == 0) return true
        return RemoteInputProtocol.seqDifference(incomingSid, storedSid) >= 0
    }
}
