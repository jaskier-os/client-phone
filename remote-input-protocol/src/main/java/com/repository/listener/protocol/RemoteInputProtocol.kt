package com.repository.listener.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Frozen wire contract v1 for remote input (watch -> phone -> glasses).
 *
 * This file is the single source of truth for the watch/phone halves. The glasses
 * live in a separate Gradle project and duplicate the constants in their own
 * BtProtocol.kt; cross-repo safety comes from the checked-in golden vectors
 * (see RemoteInputGoldenVectorsTest), not from a shared symbol.
 *
 * Two encodings:
 *   A. watch -> phone, Wear MessageClient, 26-byte big-endian binary payload.
 *   B. phone -> glasses, dedicated RFCOMM socket, 8 positional decimal-ASCII args.
 *
 * Both carry exactly the same authenticated tuple. The phone never rewrites a
 * field that the tag covers -- see the note on coalescing below.
 */
object RemoteInputProtocol {

    /** Protocol version. Receivers drop frames with v != 1 and log loudly. */
    const val PROTOCOL_VERSION = 1

    /** Source id. The sequence space is per-src. v1 allows exactly this one value. */
    const val SRC_WATCH = "watch"

    /**
     * Hard allowlist of accepted source ids. `src` is attacker-controlled, so this
     * is enforced BEFORE any MAC work and before any per-source state is allocated;
     * otherwise a peer sends a million distinct src values and both the rate limit
     * and the session map grow unbounded.
     */
    val ALLOWED_SOURCES: Set<String> = setOf(SRC_WATCH)

    // ---- Encoding A: Wear Data Layer message paths ----

    const val PATH_EVENT = "/remote-input/v1/event"
    const val PATH_OPEN = "/remote-input/v1/open"
    const val PATH_STATUS = "/remote-input/v1/status"

    /** Prefix used by the phone manifest's intent filter and its path guard. */
    const val PATH_PREFIX = "/remote-input/v1/"

    // ---- Encoding B: RFCOMM channel (duplicated in both BtProtocol.kt files) ----

    const val CH_REMOTE_INPUT = "listener_remote_input"

    /** Dedicated RFCOMM socket for input. NEVER the shared message socket. */
    const val INPUT_UUID = "d4e5f6a7-b8c9-0123-def0-345678901234"
    const val INPUT_SERVICE_NAME = "GlassesRemoteInput"

    // ---- Sizes ----

    /** Encoding A payload size. Receivers require EXACTLY this, not >=. */
    const val EVENT_PAYLOAD_BYTES = 26

    /** Tag length in bytes (HMAC-SHA256 truncated) and its hex rendering length. */
    const val TAG_BYTES = 8
    const val TAG_HEX_CHARS = 16

    /** Encoding B positional arg count. Receivers reject fewer, ignore extra. */
    const val RFCOMM_ARG_COUNT = 8

    // ---- Tunables ----

    /**
     * Leading-edge coalescing window.
     *
     * NOTE ON WHERE COALESCING RUNS. The parent plan (revision 2) placed coalescing
     * on the phone. That is not implementable together with a watch-computed tag:
     * the tag covers `seq` and `steps`, and a phone-side merge rewrites both (summed
     * steps, last consumed seq), so the glasses could never verify a coalesced event.
     * Coalescing therefore runs on the WATCH, which signs the already-coalesced
     * tuple. The phone forwards watch-signed tuples VERBATIM and never rewrites a
     * covered field. No byte of the wire contract changes, so the glasses side is
     * unaffected either way.
     */
    const val COALESCE_WINDOW_MS = 60L

    /**
     * Max detents merged into one event. Surplus is CARRIED, never dropped.
     *
     * Set from measured hardware, not guessed. Intra-gesture detent spacing on the
     * user's watch, both directions, deduped to one dispatch path:
     *   n=36  min=32ms  p50=74ms  p90=109ms  max=222ms, magnitude uniformly +/-1.
     * At 32 ms minimum spacing a 100 ms window physically holds ~3-4 detents, so 8
     * is 2x headroom over the fastest spin a person can produce. A larger cap is
     * unreachable and therefore meaningless.
     *
     * The cap is enforced by CHUNKING on overflow, never by trusting the producer:
     * a merged magnitude above 127 would wrap the int8 wire field and invert the
     * scroll direction.
     */
    const val MAX_STEPS_PER_EVENT = 8

    /** Reorder hold deadline for an out-of-order event on the phone. */
    const val REORDER_DEADLINE_MS = 40L

    /**
     * How long a producer waits after a tap before it can call that tap a SINGLE.
     *
     * Owned by the SOURCE, because the source is the only place where the wait is
     * free. See the note on [EventType] for the layering. The value matches the
     * glasses' own physical-touchpad threshold (`MainActivity.DOUBLE_TAP_THRESHOLD_MS`)
     * so the watch and the touchpad feel the same to the user; the two constants live
     * in separate Gradle projects and cannot share a symbol, which is why this one
     * states the relationship explicitly.
     */
    const val DOUBLE_TAP_WINDOW_MS = 400L

    const val PING_INTERVAL_MS = 10_000L

    /**
     * Keepalive cadence once the session has genuinely been idle.
     *
     * MUST stay below [SESSION_EXPIRY_MS], with margin for the transport. This was
     * 30 s against a 20 s expiry, which guaranteed the failure it exists to prevent:
     * every idle session died on the glasses ~10 s before its own keepalive was due,
     * and the watch then observed a ~29 s silence and reopened, forever. The
     * measured round trip reaches ~1 s, so 12 s leaves 8 s of margin.
     *
     * This is the SECOND instance of this bug class here -- the watch's
     * STATUS_TIMEOUT_MS was previously below this same backoff. The invariant is
     * therefore executable rather than a comment; see [assertTimingCoherent].
     */
    const val PING_IDLE_BACKOFF_MS = 12_000L

    const val IDLE_BEFORE_PING_BACKOFF_MS = 60_000L

    /** Glasses expire a session after this long with no event and no PING. */
    const val SESSION_EXPIRY_MS = 20_000L

    /**
     * Fails loudly if a keepalive cadence is set above a timeout it must beat.
     *
     * A comment asking the next editor to remember this has already failed twice, so
     * the invariant is checked by the test suite instead of trusted.
     */
    fun assertTimingCoherent() {
        require(PING_IDLE_BACKOFF_MS < SESSION_EXPIRY_MS) {
            "PING_IDLE_BACKOFF_MS ($PING_IDLE_BACKOFF_MS) must be below " +
                "SESSION_EXPIRY_MS ($SESSION_EXPIRY_MS): a keepalive slower than the " +
                "expiry it prevents guarantees the session dies every idle cycle"
        }
        require(PING_INTERVAL_MS < SESSION_EXPIRY_MS) {
            "PING_INTERVAL_MS ($PING_INTERVAL_MS) must be below " +
                "SESSION_EXPIRY_MS ($SESSION_EXPIRY_MS)"
        }
    }

    /**
     * TTL calibration arithmetic.
     *
     * There is deliberately no mutable `ttlMs` here and no `applyMeasuredTtl`. Both
     * existed, and neither had a production call site: the sender never consulted the
     * value and the glasses enforce staleness with their own constant, so the "calibration
     * mechanism" was two independent hardcoded numbers that could silently drift apart.
     * An inert knob is worse than none, because it invites someone to "fix" the feature by
     * tuning a number that is not read. The enforcement point owns its own cutoff; this
     * object is kept only for the arithmetic, which is exercised by tests.
     */
    object TtlCalibration {
        const val FLOOR_MS = 400L
        const val MULTIPLIER = 2.5

        /**
         * Placeholder until the on-device measurement lands. Deliberately the floor
         * so an unmeasured build is conservative rather than silently dropping
         * healthy input.
         */
        const val DEFAULT_TTL_MS = FLOOR_MS

        /** TTL = ceil(p95 * 2.5) rounded up to the next 100 ms, floored at 400. */
        fun fromP95(p95OneWayMs: Double): Long {
            val scaled = Math.ceil(p95OneWayMs * MULTIPLIER).toLong()
            val rounded = ((scaled + 99) / 100) * 100
            return maxOf(FLOOR_MS, rounded)
        }
    }

    // ---- Event vocabulary ----

    /**
     * Event types.
     *
     * ACTION SEMANTICS (revised 2026-08-05, binding, and it REPLACES the earlier
     * raw-tap design). Every value here is a SEMANTIC ACTION -- what the user asked
     * for -- never a raw physical gesture. Gesture recognition belongs to the
     * SOURCE; interpretation of the action belongs to the receiver.
     *
     * The layering:
     *  - SOURCE (the watch): recognises its own gestures locally, with zero network
     *    latency, and emits the action they mean. Single tap -> [SELECT],
     *    double tap -> [BACK]. The disambiguation wait ([DOUBLE_TAP_WINDOW_MS]) is
     *    paid entirely here, where it costs nothing extra.
     *  - RELAY (the phone): forwards actions verbatim and AGNOSTICALLY. It reasons
     *    about sessions, ordering, replay and staleness -- never about what an
     *    action means.
     *  - RECEIVER (the glasses): maps an action onto its own UI and gates it. It
     *    does NOT re-derive gestures from actions.
     *
     * Why the earlier design (raw taps, glasses-side disambiguation) was wrong: to
     * tell a single tap from a double, the receiver must DEFER acting on the first
     * tap for the whole window. Stacked on ~450 ms of transport round trip that made
     * a single tap take ~850 ms; the code that skipped the deferral instead emitted
     * BOTH a select and a back for one double tap, which cancelled out. Recognising
     * on the source removes the deferral from the latency path entirely, and it
     * generalises: a future source with a real back button, a chorded gesture or a
     * voice trigger emits the same actions and the glasses need no knowledge of its
     * gesture vocabulary.
     *
     * Consequences for the producer, which are correctness requirements rather
     * than polish:
     *  - Emit exactly ONE action per user intent. A double tap is one [BACK], never
     *    a [SELECT] followed by a [BACK].
     *  - NEVER put an action through the scroll coalescing window. Coalescing exists
     *    for detent bursts; actions and scroll steps are never merged, and an action
     *    is sent the moment it is recognised.
     *  - Populate [RemoteInputEvent.wms] at the moment of the gesture that produced
     *    the action, not at enqueue or send time. `wms` drives the receiver's TTL and
     *    ordering, so a late stamp makes a live action look stale and be dropped. It
     *    no longer carries any tap-disambiguation meaning.
     */
    enum class EventType(val code: Int) {
        SCROLL(1),

        /** Select / enter. On the watch this is what ONE tap resolves to. */
        SELECT(2),

        /**
         * Back / exit. A semantic action, produced by whatever affordance the source
         * has for "go back" -- on the watch, a locally recognised double tap.
         */
        BACK(3),
        OPEN(4),
        CLOSE(5),
        PING(6);

        /** SCROLL is the only type carrying a non-zero steps payload. */
        val carriesSteps: Boolean get() = this == SCROLL

        /** OPEN/CLOSE/PING are session lifecycle, not input; exempt from staleness. */
        val isLifecycle: Boolean get() = this == OPEN || this == CLOSE || this == PING

        /** OPEN/CLOSE/PING are exempt from the staleness cutoff. */
        val ttlExempt: Boolean get() = isLifecycle

        /**
         * A human just did something, as opposed to the session announcing or
         * maintaining itself.
         *
         * Defined as the complement of [isLifecycle] rather than by listing action
         * types, so a relay that keys off it stays agnostic: adding a new action to
         * the vocabulary must not require editing anything between the source and the
         * receiver.
         */
        val isUserAction: Boolean get() = !isLifecycle

        companion object {
            private val BY_CODE = entries.associateBy { it.code }

            /** Null for any code outside the enum -- a forged type must not throw. */
            fun fromCode(code: Int): EventType? = BY_CODE[code]
        }
    }

    /**
     * One authenticated input event.
     *
     * [sid], [seq] and [wms] are uint32 carried in a Kotlin Int. They are ALWAYS
     * rendered unsigned in the canonical string and in Encoding B; rendering them
     * signed would make the watch and the glasses disagree on the MAC input for
     * every value above 2^31.
     */
    data class RemoteInputEvent(
        val version: Int = PROTOCOL_VERSION,
        val src: String = SRC_WATCH,
        val sid: Int,
        val seq: Int,
        val type: EventType,
        val steps: Int,
        val wms: Int,
    ) {
        /** Unsigned renderings, used by both the canonical string and Encoding B. */
        val sidUnsigned: String get() = sid.toUInt().toString()
        val seqUnsigned: String get() = seq.toUInt().toString()
        val wmsUnsigned: String get() = wms.toUInt().toString()
    }

    /** Thrown when a frame is structurally invalid. Callers must catch it. */
    class MalformedFrameException(message: String) : Exception(message)

    // ---- Validation ----

    /**
     * Structural validation applied identically on both sides before a frame is
     * trusted. Returns null when valid, else the reject reason.
     */
    fun validate(event: RemoteInputEvent): String? {
        if (event.version != PROTOCOL_VERSION) return "bad version ${event.version}"
        if (event.src !in ALLOWED_SOURCES) return "src not allowed"
        // `src` lands in a '|'-delimited canonical string. The allowlist already
        // excludes a separator, but assert it so widening the allowlist later
        // cannot silently introduce MAC ambiguity.
        if (event.src.contains(CANONICAL_SEPARATOR)) return "src contains separator"
        if (event.type.carriesSteps) {
            if (event.steps == 0) return "SCROLL with zero steps"
            // Range check, NOT Math.abs: abs(Int.MIN_VALUE) is itself negative, so
            // an abs-based check would pass Int.MIN_VALUE through to encode, where
            // it truncates to 0x00 and emits a SCROLL the peer rejects.
            if (event.steps !in -MAX_STEPS_PER_EVENT..MAX_STEPS_PER_EVENT) {
                return "steps magnitude ${event.steps} exceeds $MAX_STEPS_PER_EVENT"
            }
        } else if (event.steps != 0) {
            return "non-SCROLL with steps ${event.steps}"
        }
        return null
    }

    // ---- Canonical string + HMAC ----

    private const val CANONICAL_SEPARATOR = '|'
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * The exact byte string the tag is computed over: "v|src|sid|seq|type|steps|wms".
     *
     * Frozen formatting rules, because three independent implementations must agree
     * byte for byte:
     *   - ASCII digits only, built by Kotlin's Int/UInt toString (locale-independent).
     *   - sid, seq, wms rendered UNSIGNED.
     *   - type rendered as its numeric code, not its name.
     *   - steps rendered signed with a leading '-' only; never a leading '+'.
     *   - no padding, no leading zeros, no whitespace.
     */
    fun canonicalString(event: RemoteInputEvent): String =
        buildString {
            append(event.version); append(CANONICAL_SEPARATOR)
            append(event.src); append(CANONICAL_SEPARATOR)
            append(event.sidUnsigned); append(CANONICAL_SEPARATOR)
            append(event.seqUnsigned); append(CANONICAL_SEPARATOR)
            append(event.type.code); append(CANONICAL_SEPARATOR)
            append(event.steps); append(CANONICAL_SEPARATOR)
            append(event.wmsUnsigned)
        }

    /** HMAC-SHA256 over the canonical string, truncated to the first 8 bytes. */
    fun computeTag(key: ByteArray, event: RemoteInputEvent): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        val full = mac.doFinal(canonicalString(event).toByteArray(Charsets.UTF_8))
        return full.copyOf(TAG_BYTES)
    }

    fun computeTagHex(key: ByteArray, event: RemoteInputEvent): String =
        toHex(computeTag(key, event))

    /**
     * Constant-time tag check. Timing is not realistically attackable across BLE
     * plus RFCOMM jitter, but MessageDigest.isEqual costs nothing and removes the
     * question entirely.
     */
    fun verifyTag(key: ByteArray, event: RemoteInputEvent, tag: ByteArray): Boolean =
        MessageDigest.isEqual(computeTag(key, event), tag)

    fun verifyTagHex(key: ByteArray, event: RemoteInputEvent, tagHex: String): Boolean {
        val parsed = parseHexOrNull(tagHex, TAG_BYTES) ?: return false
        return verifyTag(key, event, parsed)
    }

    // ---- Encoding A: 26-byte big-endian payload ----

    /**
     *   [0]      type    u8
     *   [1]      steps   i8
     *   [2..5]   seq     u32
     *   [6..9]   sid     u32   (FULL width on every frame, never truncated)
     *   [10..13] wms     u32
     *   [14..21] tag     8 bytes
     *   [22..25] reserved, MUST be zero
     *
     * `v` and `src` are not on the wire: v1 fixes them, and both are covered by the
     * tag, so a peer that disagrees about either produces a tag mismatch rather
     * than a silently accepted frame.
     */
    fun encodeEvent(event: RemoteInputEvent, tag: ByteArray): ByteArray {
        require(tag.size == TAG_BYTES) { "tag must be $TAG_BYTES bytes, got ${tag.size}" }
        validate(event)?.let { throw IllegalArgumentException("refusing to encode: $it") }
        return ByteBuffer.allocate(EVENT_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(event.type.code.toByte())
            put(event.steps.toByte())
            putInt(event.seq)
            putInt(event.sid)
            putInt(event.wms)
            put(tag)
            putInt(0) // reserved
        }.array()
    }

    fun encodeEvent(key: ByteArray, event: RemoteInputEvent): ByteArray =
        encodeEvent(event, computeTag(key, event))

    /** A decoded frame: the event plus the tag exactly as it arrived. */
    data class DecodedEvent(val event: RemoteInputEvent, val tag: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is DecodedEvent && event == other.event && tag.contentEquals(other.tag)

        override fun hashCode(): Int = 31 * event.hashCode() + tag.contentHashCode()
    }

    /**
     * Strict decode. Throws [MalformedFrameException] for anything structurally
     * wrong; it never throws anything else, because the caller runs on a Binder
     * thread where an uncaught throw kills the service.
     */
    fun decodeEvent(payload: ByteArray?): DecodedEvent {
        if (payload == null) throw MalformedFrameException("null payload")
        // Exact, not >=. A longer frame is a different protocol, not a v1 frame
        // with trailing slack.
        if (payload.size != EVENT_PAYLOAD_BYTES) {
            throw MalformedFrameException("payload is ${payload.size} bytes, expected $EVENT_PAYLOAD_BYTES")
        }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val typeCode = buf.get().toInt() and 0xFF
        val steps = buf.get().toInt() // signed on purpose
        val seq = buf.int
        val sid = buf.int
        val wms = buf.int
        val tag = ByteArray(TAG_BYTES).also { buf.get(it) }
        val reserved = buf.int
        if (reserved != 0) throw MalformedFrameException("reserved bytes not zero")

        val type = EventType.fromCode(typeCode)
            ?: throw MalformedFrameException("unknown type code $typeCode")

        val event = RemoteInputEvent(
            version = PROTOCOL_VERSION,
            src = SRC_WATCH,
            sid = sid,
            seq = seq,
            type = type,
            steps = steps,
            wms = wms,
        )
        validate(event)?.let { throw MalformedFrameException(it) }
        return DecodedEvent(event, tag)
    }

    // ---- Encoding B: RFCOMM positional string args ----

    /**
     * Fixed positional order: [v, src, sid, seq, type, steps, wms, tag].
     * All decimal ASCII except src, type and tag. `type` is the readable NAME here
     * (the persistent glasses log has no decoder), while the MAC still covers the
     * numeric code -- the two renderings are a pure bijection over the enum.
     */
    fun toRfcommArgs(event: RemoteInputEvent, tagHex: String): Array<String> {
        require(tagHex.length == TAG_HEX_CHARS) {
            "tag must be $TAG_HEX_CHARS hex chars, got ${tagHex.length}"
        }
        return arrayOf(
            event.version.toString(),
            event.src,
            event.sidUnsigned,
            event.seqUnsigned,
            event.type.name,
            event.steps.toString(),
            event.wmsUnsigned,
            tagHex,
        )
    }

    fun toRfcommArgs(key: ByteArray, event: RemoteInputEvent): Array<String> =
        toRfcommArgs(event, computeTagHex(key, event))

    /**
     * Parser for the glasses side, kept here so the phone's tests can prove the
     * real encoder meets a real parser. Extra trailing args are IGNORED (forward
     * compatibility inside v1); fewer than 8 is rejected.
     */
    fun fromRfcommArgs(args: List<String>): Pair<RemoteInputEvent, String> {
        if (args.size < RFCOMM_ARG_COUNT) {
            throw MalformedFrameException("expected >= $RFCOMM_ARG_COUNT args, got ${args.size}")
        }
        val version = args[0].toIntOrNull()
            ?: throw MalformedFrameException("bad version arg")
        val src = args[1]
        val sid = args[2].toUIntOrNull()?.toInt()
            ?: throw MalformedFrameException("bad sid arg")
        val seq = args[3].toUIntOrNull()?.toInt()
            ?: throw MalformedFrameException("bad seq arg")
        val type = EventType.entries.firstOrNull { it.name == args[4] }
            ?: throw MalformedFrameException("unknown type ${args[4]}")
        val steps = args[5].toIntOrNull()
            ?: throw MalformedFrameException("bad steps arg")
        val wms = args[6].toUIntOrNull()?.toInt()
            ?: throw MalformedFrameException("bad wms arg")
        val tagHex = args[7]
        if (parseHexOrNull(tagHex, TAG_BYTES) == null) {
            throw MalformedFrameException("bad tag arg")
        }
        val event = RemoteInputEvent(version, src, sid, seq, type, steps, wms)
        validate(event)?.let { throw MalformedFrameException(it) }
        return event to tagHex
    }

    // ---- Status backchannel (phone -> watch), 1 byte ----

    // This whole backchannel is advisory only, and deliberately NOT authenticated: the tag
    // key lives on the watch and the glasses, not on the phone, so the phone cannot sign
    // anything.
    //
    // The consequence is a real and accepted limitation, stated plainly rather than papered
    // over: any app able to deliver on this path can forge a status frame. The containment is
    // that status is advisory input to the watch's DISPLAY state only. It must never be
    // allowed to make the watch send more, nor to clear a failure the watch observed locally
    // -- see applyAdvisory and foldStatus, which are where that rule is enforced.

    /**
     * Why the glasses are declining input, as carried to the watch.
     *
     * Ordinals are wire values: append only, never reorder.
     */
    enum class RefusalReason(val code: Int) {
        /**
         * The glasses are folded.
         *
         * Code 2, with no code 1 above it. Code 1 was `NOT_ALLOWED` -- "this action is
         * not on the allowlist for this state" -- which is no longer reported at all:
         * an action the gate does not permit is simply consumed, silently. It was the
         * overwhelmingly common denial (every BACK at the top level produces one) and
         * saying so told the user nothing they could act on, unlike these two. Codes are
         * wire values, so the retired one is left unused rather than reassigned.
         */
        FOLDED(2),

        /** A call, recording or reply owns the glasses UI. */
        LOCKED(3);

        companion object {
            fun fromCode(code: Int): RefusalReason? = values().firstOrNull { it.code == code }

            /** Parse the name the glasses put on the wire. Null for absent/unknown. */
            fun fromName(name: String?): RefusalReason? =
                name?.let { n -> values().firstOrNull { it.name == n } }
        }
    }

    object StatusFlags {
        const val GLASSES_LINK_UP = 1 shl 0
        const val PHONE_SERVICE_ALIVE = 1 shl 1
        const val LAST_SEND_DROPPED = 1 shl 2
        const val GLASSES_SINK_ATTACHED = 1 shl 3
        const val WAKING_GLASSES = 1 shl 4

        /**
         * The glasses received input and REFUSED it, recently.
         *
         * Distinct from every other bit here, which describe the LINK. This one says the
         * link is fine and the UI declined anyway -- the case that previously rendered as
         * "Connected" while nothing the user did had any effect. Set while the glasses'
         * refusal counter has advanced within [REFUSAL_FRESH_MS].
         */
        const val GLASSES_REFUSING_INPUT = 1 shl 5

        /**
         * How long a refusal stays "current".
         *
         * Long enough to survive the gap between status pushes so the bit does not
         * flicker, short enough that it clears on its own once the user moves somewhere
         * input is accepted -- the signal must never outlive the condition, or it becomes
         * the next thing that lies to the user.
         */
        const val REFUSAL_FRESH_MS = 3_000L

        /**
         * The refusal reason, packed into the two bits above the flags.
         *
         * Carried in the same byte rather than a new field so an older reader, which masks
         * only the low five bits, is unaffected.
         */
        const val REASON_SHIFT = 6
        const val REASON_MASK = 0x3 shl REASON_SHIFT

        fun encodeReason(bits: Int, reason: RefusalReason?): Int =
            (bits and REASON_MASK.inv()) or
                ((reason?.code ?: 0) shl REASON_SHIFT and REASON_MASK)

        fun decodeReason(bits: Int): RefusalReason? =
            RefusalReason.fromCode((bits and REASON_MASK) ushr REASON_SHIFT)

        fun encode(
            glassesLinkUp: Boolean,
            phoneServiceAlive: Boolean,
            lastSendDropped: Boolean,
            glassesSinkAttached: Boolean,
            wakingGlasses: Boolean,
            glassesRefusingInput: Boolean = false,
            refusalReason: RefusalReason? = null,
        ): ByteArray {
            var b = 0
            if (glassesLinkUp) b = b or GLASSES_LINK_UP
            if (phoneServiceAlive) b = b or PHONE_SERVICE_ALIVE
            if (lastSendDropped) b = b or LAST_SEND_DROPPED
            if (glassesSinkAttached) b = b or GLASSES_SINK_ATTACHED
            if (wakingGlasses) b = b or WAKING_GLASSES
            if (glassesRefusingInput) b = b or GLASSES_REFUSING_INPUT
            if (glassesRefusingInput) b = encodeReason(b, refusalReason)
            return byteArrayOf(b.toByte())
        }

        fun decode(payload: ByteArray?): Int {
            if (payload == null || payload.isEmpty()) {
                throw MalformedFrameException("status payload must not be empty")
            }
            return payload[0].toInt() and 0xFF
        }

        /**
         * Optional 4-byte correlation suffix: the `seq` of the PING this status is
         * replying to, or absent for an unsolicited push.
         *
         * Without this the watch cannot tell a PING reply from a spontaneous status
         * push (link-state change, dropped send, waking glasses), and timing an
         * unsolicited frame against the last PING produces a fabricated round trip
         * of up to a whole ping interval -- landing in exactly the upper tail that
         * sets the staleness cutoff.
         */
        const val REPLY_SUFFIX_BYTES = 4

        fun encodeWithReplyTo(bits: ByteArray, replyToSeq: Int): ByteArray =
            java.nio.ByteBuffer.allocate(1 + REPLY_SUFFIX_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .put(bits[0])
                .putInt(replyToSeq)
                .array()

        /** The correlated PING seq, or null when this is an unsolicited push. */
        fun replyToSeq(payload: ByteArray?): Int? {
            if (payload == null || payload.size < 1 + REPLY_SUFFIX_BYTES) return null
            return java.nio.ByteBuffer.wrap(payload, 1, REPLY_SUFFIX_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN).int
        }

        fun isSet(bits: Int, flag: Int): Boolean = (bits and flag) != 0

        /**
         * Folds an unauthenticated status frame into the watch's view of the link.
         *
         * A forged frame may only ever make the watch MORE pessimistic. Bits that
         * report a problem (dropped, waking) are OR-ed in, so a forger can raise
         * them but the watch's own observations can never be erased. Bits that
         * report health (link up, service alive, sink attached) are AND-ed with
         * what the watch already believes, so a forger cannot assert READY over a
         * locally observed failure or resume sending that the watch has stopped.
         *
         * @param current the watch's current bits.
         * @param received the bits from the frame just received.
         * @param trusted true only when the watch itself cleared its local
         *        failure state (a genuine reconnect), which is the sole path
         *        allowed to turn health bits back on.
         */
        fun applyAdvisory(current: Int, received: Int, trusted: Boolean): Int {
            if (trusted) return received
            val healthMask = GLASSES_LINK_UP or PHONE_SERVICE_ALIVE or GLASSES_SINK_ATTACHED
            val problemMask = LAST_SEND_DROPPED or WAKING_GLASSES or GLASSES_REFUSING_INPUT
            val health = current and received and healthMask
            val problems = (current or received) and problemMask
            // The reason belongs to whichever frame is currently asserting a refusal, so it
            // is taken from the received frame rather than AND/OR-folded -- folding two
            // enums bitwise would synthesize a third, wrong reason.
            val reason = received and REASON_MASK
            return health or problems or reason
        }

        /**
         * Fold a status frame while GUARANTEEING every health bit stays clearable.
         *
         * [applyAdvisory] AND-folds the health bits so a forged frame can never assert
         * health over a locally observed failure. The cost is that once a health bit goes
         * to zero, no untrusted frame can ever raise it again -- and for the whole life of
         * the watch process the ONLY caller passed `trusted = false`. A single
         * `replyPhoneStopped` during the ordinary cold-start race therefore pinned the
         * watch at "Phone service down" permanently, and reopening the phone app could not
         * clear it.
         *
         * This is the third bug of that exact shape on this feature (the AND-fold seed and
         * the `wakingGlasses` latch were the first two), so the fix is structural rather
         * than another careful caller: a health bit that has been observed healthy again,
         * on a frame we CORRELATED to our own outstanding request, is restored. A
         * correlated reply is as trusted as this unauthenticated channel gets, and it is
         * evidence the watch generated the demand for -- a forger cannot produce one
         * without already being able to answer our pings.
         *
         * Every path that clears a health bit must therefore have a matching path that can
         * set it, which is the invariant [assertNoAbsorbingBit] pins down.
         *
         * @param correlated true when this frame answers the watch's own outstanding PING.
         */
        fun foldStatus(current: Int, received: Int, correlated: Boolean): Int =
            applyAdvisory(
                current = if (correlated) {
                    (current or healthBitsIn(received)) and problemBitsIn(received).inv()
                } else {
                    current
                },
                received = received,
                trusted = false,
            )

        private fun healthBitsIn(bits: Int): Int =
            bits and (GLASSES_LINK_UP or PHONE_SERVICE_ALIVE or GLASSES_SINK_ATTACHED)

        /**
         * The problem bits NOT asserted by [bits], i.e. the ones it reports as resolved.
         *
         * Named for what it is used for: [foldStatus] clears exactly these off `current`
         * when the frame is correlated. Problem bits are OR-folded by [applyAdvisory] so a
         * forger can raise them, which -- with nothing able to lower them again -- made
         * every one of them absorbing: a single refusal pinned the watch at
         * "Not allowed here" for the life of the process while the phone was reporting no
         * refusal at all on every subsequent frame. That is the fourth bug of this exact
         * shape on this feature, so it is fixed the same structural way as the health bits
         * rather than at one careful caller, and pinned by [assertNoAbsorbingBit].
         */
        private fun problemBitsIn(bits: Int): Int =
            (LAST_SEND_DROPPED or WAKING_GLASSES or GLASSES_REFUSING_INPUT) and bits.inv()

        /**
         * Executable statement of the invariant: NO bit is absorbing, in either direction.
         *
         * A health bit that has been lost must be regainable, and a problem bit that has
         * been raised must be clearable, once a correlated frame says so. Called from tests,
         * so the property is checked rather than merely documented -- a comment saying "do
         * not latch this" is what failed every previous time.
         *
         * Both directions are asserted here because they are the same bug wearing two
         * faces: the health direction pinned the watch at "Phone service down", and the
         * problem direction pinned it at "Not allowed here". Checking only the direction
         * that happened to break last is what let the second one ship.
         */
        fun assertNoAbsorbingBit() {
            val healthBits = listOf(GLASSES_LINK_UP, PHONE_SERVICE_ALIVE, GLASSES_SINK_ATTACHED)
            val problemBits = listOf(LAST_SEND_DROPPED, WAKING_GLASSES, GLASSES_REFUSING_INPUT)
            val allHealthy = healthBits.fold(0) { acc, b -> acc or b }

            // A problem bit raised once must be cleared by a frame that no longer reports it.
            for (bit in problemBits) {
                val latchedOn = foldStatus(
                    current = allHealthy,
                    received = allHealthy or bit,
                    correlated = true,
                )
                check(latchedOn and bit != 0) {
                    "status bit $bit was not raised by a frame reporting it, so the watch " +
                        "would never learn about the problem at all"
                }
                val cleared = foldStatus(
                    current = latchedOn,
                    received = allHealthy,
                    correlated = true,
                )
                check(cleared and bit == 0) {
                    "status bit $bit is absorbing: once raised it can never be cleared, so " +
                        "the watch would report a problem that has long since resolved for " +
                        "the life of the process"
                }
            }

            for (bit in healthBits) {
                val latchedOff = allHealthy and bit.inv()
                val recovered = foldStatus(
                    current = latchedOff,
                    received = allHealthy,
                    correlated = true,
                )
                check(recovered and bit != 0) {
                    "status bit $bit is absorbing: once cleared it can never be set again, " +
                        "so the watch would be pinned in a failure state for the life of " +
                        "the process"
                }
            }
        }
    }

    // ---- Hex helpers ----

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /**
     * Strict hex parse of an exact byte count. Null on anything else.
     *
     * Encoding accepts BOTH cases even though [toHex] always emits lowercase: an
     * independent implementation using "%02X" would otherwise produce tags this
     * side silently rejected, which presents as the feature being 100 % broken
     * with no useful error. Emit lowercase, accept either.
     */
    fun parseHexOrNull(hex: String, expectedBytes: Int): ByteArray? {
        if (hex.length != expectedBytes * 2) return null
        val out = ByteArray(expectedBytes)
        for (i in 0 until expectedBytes) {
            val hi = hexDigit(hex[i * 2]) ?: return null
            val lo = hexDigit(hex[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    // ---- Sequence arithmetic ----

    /**
     * Wrap-safe comparison of two uint32 sequence numbers carried in an Int.
     * Returns > 0 when [seq] is newer than [lastSeq], 0 when equal, < 0 when older.
     * A plain `<=` deadlocks the source forever at uint32 wraparound.
     */
    fun seqDifference(seq: Int, lastSeq: Int): Int = seq - lastSeq
}
