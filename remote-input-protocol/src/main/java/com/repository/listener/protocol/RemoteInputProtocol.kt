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
     * Minimum spacing between two session re-announcements on the source.
     *
     * Re-announcing means minting a NEW session id, and minting is a synchronous
     * SharedPreferences commit plus a permanent step of a monotonic counter. The
     * limit is what stops the unauthenticated status channel from being an sid-churn
     * amplifier: a forger who can raise [StatusFlags.GLASSES_SESSION_LOST] on every
     * frame still gets at most one mint per this interval.
     *
     * MUST stay below [SESSION_EXPIRY_MS]. A reopen cadence slower than the rate at
     * which a session can die would leave the source dead for whole expiry cycles,
     * which is the same shape of bug as a keepalive slower than the timeout it
     * defeats -- see [assertTimingCoherent], which checks it.
     */
    const val REOPEN_MIN_INTERVAL_MS = 5_000L

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
        // The recovery path must be able to run at least once per session lifetime.
        //
        // This is the same bug class as the two above, in the one place it actually
        // bit hardest: the source's OTHER recovery trigger -- re-announcing after
        // SESSION_EXPIRY_MS of silence -- is UNREACHABLE by construction, because the
        // keepalive above deliberately stamps a frame more often than that. So the
        // status-driven reopen is not a second-chance path, it is the ONLY one, and a
        // rate limit at or above the expiry would silently restore the deadlock it
        // exists to break.
        require(REOPEN_MIN_INTERVAL_MS < SESSION_EXPIRY_MS) {
            "REOPEN_MIN_INTERVAL_MS ($REOPEN_MIN_INTERVAL_MS) must be below " +
                "SESSION_EXPIRY_MS ($SESSION_EXPIRY_MS): the keepalive makes the " +
                "silence-driven reopen unreachable, so a reopen limit slower than the " +
                "expiry leaves the source deadlocked for whole expiry cycles"
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

        /**
         * Press and hold. A semantic action like every other value here: the user asked
         * to hold, and the receiver decides what a hold means on the surface in focus.
         * On the glasses that is whatever the physical touchpad hold does.
         *
         * Code 7, after PING(6). Codes are wire values and are append-only, so this
         * takes the next free number rather than slotting in beside the other actions.
         *
         * The hold DURATION is deliberately NOT on the wire. The receiver owns the
         * threshold and publishes it on the status back channel
         * ([StatusFlags.holdMs]); a source that signed its own duration would let the
         * two ends disagree about what a hold is while each believed it was right,
         * which is the constant-drift bug this file has already collected four of.
         */
        HOLD(7),

        /** Capture a still photo. What that means is entirely the receiver's business. */
        PHOTO(8),

        /** Start recording, or stop the recording already running. Receiver decides which. */
        VIDEO(9),

        OPEN(4),
        CLOSE(5),
        PING(6),

        /**
         * An action this build does not know about.
         *
         * THIS IS WHAT MAKES THE RELAY PERMANENTLY AGNOSTIC. The phone sits between the
         * watch and the glasses and needs to know exactly two things about a frame: its
         * (sid, seq), for ordering, and whether it is a user action or session lifecycle,
         * for the audio-relay teardown. It has never needed to know what an action MEANS.
         *
         * Before this existed, `decodeEvent` threw on an unrecognised opcode, so every new
         * action in the vocabulary was a hard dependency on the phone being rebuilt and
         * redeployed in lockstep -- and until it was, the frames were not merely ignored,
         * they were rejected as malformed. An opaque action instead rides straight through
         * a relay that has never heard of it.
         *
         * The real opcode is preserved in [RemoteInputEvent.typeCode] and is what the MAC
         * covers, so a frame that passes through an older phone reaches the glasses with a
         * byte-identical tag. This value is a LOCAL marker, never a wire code -- see
         * [WIRE_CODE_NONE].
         */
        OPAQUE_ACTION(WIRE_CODE_NONE);

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
            private val BY_CODE = entries.filter { it.code != WIRE_CODE_NONE }.associateBy { it.code }

            /** Null for any code outside the enum -- a forged type must not throw. */
            fun fromCode(code: Int): EventType? = BY_CODE[code]
        }
    }

    /**
     * The `code` of an [EventType] that is a local marker rather than a wire value.
     *
     * Negative, so it can never collide with a real opcode (which are unsigned bytes on
     * the wire), and excluded from the code lookup so it is unreachable by decoding.
     */
    const val WIRE_CODE_NONE = -1

    /**
     * Opcodes a relay forwards without understanding.
     *
     * A frame is structurally valid if it parses, carries a plausible steps payload and
     * verifies its tag -- none of which requires knowing what the action does. Anything
     * in this range is accepted and relayed as [EventType.OPAQUE_ACTION]. The range is
     * bounded rather than open so a garbage byte is still rejected: an opcode must at
     * least be a plausible future member of the vocabulary.
     */
    const val MIN_OPAQUE_CODE = 10
    const val MAX_OPAQUE_CODE = 63

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
        /**
         * The opcode exactly as it appeared on the wire.
         *
         * Normally redundant with `type.code`, and defaulted from it. It matters only for
         * [EventType.OPAQUE_ACTION], where this build has no name for the action: the MAC
         * covers the NUMERIC code, so a relay that re-derived the code from its own enum
         * would compute a tag over a different string and the receiver would reject every
         * forwarded frame. Carrying the original byte is what lets an unaware relay pass a
         * signed frame through untouched.
         */
        val typeCode: Int = type.code,
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
            // The WIRE code, not the enum's. For an opaque action these differ, and using
            // the enum's would sign a string the receiver never sees.
            append(event.typeCode); append(CANONICAL_SEPARATOR)
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
            put(event.typeCode.toByte())
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

        // An unknown opcode inside the opaque range is FORWARDED, not rejected. This one
        // line is the difference between a relay that must be rebuilt for every new
        // action and one that never needs touching again -- it used to throw here, which
        // meant a new action was not merely unrecognised by an older phone but actively
        // dropped as malformed.
        val type = EventType.fromCode(typeCode)
            ?: if (typeCode in MIN_OPAQUE_CODE..MAX_OPAQUE_CODE) {
                EventType.OPAQUE_ACTION
            } else {
                throw MalformedFrameException("unknown type code $typeCode")
            }

        val event = RemoteInputEvent(
            version = PROTOCOL_VERSION,
            src = SRC_WATCH,
            sid = sid,
            seq = seq,
            type = type,
            steps = steps,
            wms = wms,
            typeCode = typeCode,
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
            // The readable name where this build has one, else the raw numeric code. An
            // opaque action has no name here BY DEFINITION -- the relay has never heard of
            // it -- and inventing one would be a lie the receiver then has to parse. The
            // glasses accept either rendering.
            if (event.type == EventType.OPAQUE_ACTION) {
                event.typeCode.toString()
            } else {
                event.type.name
            },
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
        // Accept a NAME or a numeric code. The numeric form is how a relay renders an
        // action it has no name for, so rejecting it would put the coupling this whole
        // mechanism removes straight back on the RFCOMM leg.
        val named = EventType.entries.firstOrNull { it.name == args[4] }
        val numeric = args[4].toIntOrNull()
        val typeCode = named?.code
            ?: numeric
            ?: throw MalformedFrameException("unknown type ${args[4]}")
        val type = named
            ?: EventType.fromCode(typeCode)
            ?: if (typeCode in MIN_OPAQUE_CODE..MAX_OPAQUE_CODE) {
                EventType.OPAQUE_ACTION
            } else {
                throw MalformedFrameException("unknown type code $typeCode")
            }
        val steps = args[5].toIntOrNull()
            ?: throw MalformedFrameException("bad steps arg")
        val wms = args[6].toUIntOrNull()?.toInt()
            ?: throw MalformedFrameException("bad wms arg")
        val tagHex = args[7]
        if (parseHexOrNull(tagHex, TAG_BYTES) == null) {
            throw MalformedFrameException("bad tag arg")
        }
        val event = RemoteInputEvent(version, src, sid, seq, type, steps, wms, typeCode)
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

        /**
         * The glasses hold NO open session for this source, so nothing it sends can be
         * accepted until it re-announces itself.
         *
         * Placed ABOVE [REASON_MASK] because bits 0..7 were completely full -- flags at
         * 0..5, reason at 6..7 -- which is what forces [FRAME_BYTES] to two. Every
         * pre-existing bit keeps its position.
         *
         * ## What this bit is, and what it deliberately is not
         *
         * It is an ACTUATOR, not a display state: the only thing the watch does with it
         * is mint a new session id and send a fresh OPEN. There is no [LinkState] for
         * it, because it resolves within one round trip and a state for it would do
         * nothing but flicker.
         *
         * ## Why it exists
         *
         * The glasses keep the live session in memory only, while the replay defence
         * (the highest session id ever accepted) is persisted. After a glasses restart
         * the persisted id is therefore present while the live session is gone, and the
         * receiver correctly rejects every frame for a session it holds no OPEN for --
         * it must never adopt one implicitly, or a captured burst could establish its
         * own baseline. The source has no other way to learn this: its own keepalive
         * keeps succeeding, so its silence-based re-announce trigger can never fire.
         * This bit is the receiver telling it.
         *
         * ## Security
         *
         * This channel is UNAUTHENTICATED -- the tag key lives on the watch and the
         * glasses, never on the phone. So state the worst case plainly: anyone able to
         * write here can raise this bit and force the watch to churn session ids. That
         * is a battery and counter-burn nuisance, bounded by
         * [RemoteInputProtocol.REOPEN_MIN_INTERVAL_MS] and by the id being a wrap-safe
         * uint32.
         *
         * It grants ZERO replay capability, and that is a property of what the bit can
         * express rather than of who can send it. Its only possible effect is to make
         * the watch produce a NEW, HIGHER session id and sign a fresh OPEN with the key
         * the attacker does not have. It cannot admit a frame, cannot lower an id,
         * cannot rewind a sequence floor, and cannot cause any previously captured
         * frame to become acceptable -- a new session strictly invalidates old ones.
         */
        const val GLASSES_SESSION_LOST = 1 shl 8

        /**
         * Frame width in bytes, big-endian.
         *
         * Two because bits 0..7 are exhausted, not by preference. Producers and
         * consumers of a 1-byte frame and a 2-byte frame cannot interoperate, so the
         * phone must be deployed before the watch; in between the watch reports
         * UNREACHABLE and self-heals once both sides match.
         */
        const val FRAME_BYTES = 2

        /**
         * The receiver's hold threshold, in ms, carried right after the flags.
         *
         * The RECEIVER owns this number and the source obeys it, so a hold feels the
         * same on the watch as on the glasses' own touchpad. Shipping it instead of
         * duplicating a constant is the point: the glasses touchpad daemon's value can
         * change, and a frozen copy on the watch would then silently disagree.
         *
         * Zero means "not reported" -- an older receiver, or one that has not learned
         * its own threshold yet. Sources must fall back to [DEFAULT_HOLD_MS] rather
         * than treating zero as an instant hold.
         */
        const val HOLD_MS_BYTES = 2

        /**
         * RECEIVER-DEFINED state bits, relayed verbatim.
         *
         * The counterpart to [EventType.OPAQUE_ACTION], and it exists for the same
         * reason: the receiver is the only device that knows its own state, and the relay
         * between them must never need to be taught what that state means. The glasses
         * assign the bits, the watch reads them, and the phone copies the field across
         * without interpreting a single one -- so a future indicator costs a glasses
         * change and a watch change, and nothing in between.
         *
         * Bit meanings therefore live with the DEVICES, not here; this file only
         * guarantees the field's width and position.
         */
        const val DEVICE_STATE_BYTES = 2

        /** Bit 0 of the device state: a video recording is in progress. */
        const val DEVICE_STATE_RECORDING = 1 shl 0

        /**
         * Fallback when the receiver has not reported a threshold.
         *
         * Matches the glasses touchpad daemon's `custom_long_press_ms` at the time of
         * writing. It is a FALLBACK, not the source of truth: whenever the receiver
         * reports a value, that value wins -- which is the whole reason the threshold
         * is on the wire instead of duplicated as a constant on both sides.
         */
        const val DEFAULT_HOLD_MS = 800

        /** Sanity bounds. A forged frame must not make a hold unreachable or instant. */
        const val MIN_HOLD_MS = 150
        const val MAX_HOLD_MS = 3_000

        /**
         * Clamps a reported threshold into something a human can actually perform.
         *
         * The status channel is unauthenticated, so this value is attacker-controlled.
         * The worst it can do is make holds slightly awkward; clamping removes the two
         * cases that would make the gesture impossible (a 0 ms hold firing on contact,
         * or a 60 s hold that can never complete).
         */
        fun sanitizeHoldMs(reported: Int): Int = when {
            reported <= 0 -> DEFAULT_HOLD_MS
            else -> reported.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        }

        /**
         * Bits reporting the link is HEALTHY, and bits reporting a PROBLEM.
         *
         * Named constants, and the single definition each, because the fold, the
         * recovery helper and the invariant all need the same answer and all three
         * previously hardcoded their own copy. A new bit added to one list but not the
         * others is silently discarded by [applyAdvisory] -- which is how this file
         * accumulated four separate absorbing-bit bugs.
         */
        const val HEALTH_MASK = GLASSES_LINK_UP or PHONE_SERVICE_ALIVE or GLASSES_SINK_ATTACHED
        const val PROBLEM_MASK =
            LAST_SEND_DROPPED or WAKING_GLASSES or GLASSES_REFUSING_INPUT or GLASSES_SESSION_LOST

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
            glassesSessionLost: Boolean = false,
        ): ByteArray {
            var b = 0
            if (glassesLinkUp) b = b or GLASSES_LINK_UP
            if (phoneServiceAlive) b = b or PHONE_SERVICE_ALIVE
            if (lastSendDropped) b = b or LAST_SEND_DROPPED
            if (glassesSinkAttached) b = b or GLASSES_SINK_ATTACHED
            if (wakingGlasses) b = b or WAKING_GLASSES
            if (glassesRefusingInput) b = b or GLASSES_REFUSING_INPUT
            if (glassesRefusingInput) b = encodeReason(b, refusalReason)
            if (glassesSessionLost) b = b or GLASSES_SESSION_LOST
            return java.nio.ByteBuffer.allocate(FRAME_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putShort(b.toShort())
                .array()
        }

        /**
         * Every bit [encode] is capable of emitting, discovered by ASKING it.
         *
         * Derived by encoding all-true and OR-ing in each reason, rather than by OR-ing
         * the mask constants together. That distinction is the whole value: a check
         * built from [HEALTH_MASK] and [PROBLEM_MASK] cannot detect a flag missing from
         * those masks, because it is made of them. `encode` is the independent witness
         * -- a flag that is not classified still shows up here, and the classification
         * check then fails, which is exactly the failure that must not be silent.
         */
        fun encodableBits(): Int {
            var bits = decode(
                encode(
                    glassesLinkUp = true,
                    phoneServiceAlive = true,
                    lastSendDropped = true,
                    glassesSinkAttached = true,
                    wakingGlasses = true,
                    glassesRefusingInput = true,
                    glassesSessionLost = true,
                )
            )
            for (reason in RefusalReason.entries) {
                bits = bits or decode(
                    encode(
                        glassesLinkUp = true,
                        phoneServiceAlive = true,
                        lastSendDropped = true,
                        glassesSinkAttached = true,
                        wakingGlasses = true,
                        glassesRefusingInput = true,
                        refusalReason = reason,
                        glassesSessionLost = true,
                    )
                )
            }
            return bits
        }

        fun decode(payload: ByteArray?): Int {
            // Exactly the same strictness as before, only at the new width: a frame
            // narrower than the flags field is not an old frame to be zero-extended,
            // it is a peer speaking a different protocol. Zero-extending would read a
            // truncated frame as "no problems reported", which is the one answer that
            // must never be inferred.
            if (payload == null || payload.size < FRAME_BYTES) {
                throw MalformedFrameException(
                    "status payload must be at least $FRAME_BYTES bytes, got ${payload?.size ?: 0}"
                )
            }
            return java.nio.ByteBuffer.wrap(payload, 0, FRAME_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
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

        /**
         * Frame layout: flags [0..1], holdMs [2..3], replyToSeq [4..7].
         *
         * holdMs sits BEFORE the reply suffix so both are at fixed offsets. Appending
         * it after a variable-presence suffix would make the suffix's own offset depend
         * on whether the frame was solicited.
         */
        const val HOLD_MS_OFFSET = FRAME_BYTES
        const val DEVICE_STATE_OFFSET = FRAME_BYTES + HOLD_MS_BYTES
        const val REPLY_OFFSET = FRAME_BYTES + HOLD_MS_BYTES + DEVICE_STATE_BYTES

        /** Flags, hold threshold and device state, with no correlation suffix. */
        fun encodeWithHoldMs(bits: ByteArray, holdMs: Int, deviceState: Int = 0): ByteArray {
            require(bits.size == FRAME_BYTES) {
                "status bits must be $FRAME_BYTES bytes, got ${bits.size}"
            }
            return java.nio.ByteBuffer.allocate(FRAME_BYTES + HOLD_MS_BYTES + DEVICE_STATE_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .put(bits)
                .putShort(holdMs.toShort())
                .putShort(deviceState.toShort())
                .array()
        }

        fun encodeWithReplyTo(
            bits: ByteArray,
            replyToSeq: Int,
            holdMs: Int = 0,
            deviceState: Int = 0,
        ): ByteArray {
            require(bits.size == FRAME_BYTES) {
                "status bits must be $FRAME_BYTES bytes, got ${bits.size}"
            }
            return java.nio.ByteBuffer
                .allocate(FRAME_BYTES + HOLD_MS_BYTES + DEVICE_STATE_BYTES + REPLY_SUFFIX_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .put(bits)
                .putShort(holdMs.toShort())
                .putShort(deviceState.toShort())
                .putInt(replyToSeq)
                .array()
        }

        /** The receiver's opaque state bits, or 0 when the frame carries none. */
        fun deviceState(payload: ByteArray?): Int {
            if (payload == null || payload.size < DEVICE_STATE_OFFSET + DEVICE_STATE_BYTES) return 0
            return java.nio.ByteBuffer.wrap(payload, DEVICE_STATE_OFFSET, DEVICE_STATE_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        }

        /**
         * The receiver's hold threshold, or null when the frame does not carry one.
         *
         * Null and zero are both "not reported"; callers go to [DEFAULT_HOLD_MS]. Read
         * unsigned, so a threshold above 32767 ms does not arrive negative -- it is then
         * clamped by [sanitizeHoldMs] like any other out-of-range value.
         */
        fun holdMs(payload: ByteArray?): Int? {
            if (payload == null || payload.size < HOLD_MS_OFFSET + HOLD_MS_BYTES) return null
            val raw = java.nio.ByteBuffer.wrap(payload, HOLD_MS_OFFSET, HOLD_MS_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            return if (raw == 0) null else raw
        }

        /** The correlated PING seq, or null when this is an unsolicited push. */
        fun replyToSeq(payload: ByteArray?): Int? {
            if (payload == null || payload.size < REPLY_OFFSET + REPLY_SUFFIX_BYTES) return null
            return java.nio.ByteBuffer.wrap(payload, REPLY_OFFSET, REPLY_SUFFIX_BYTES)
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
            // Both masks come from the single shared definitions. They were duplicated
            // here, and a new bit omitted from this copy is not a partial failure -- it
            // is folded away to zero and never seen by the watch at all.
            val health = current and received and HEALTH_MASK
            val problems = (current or received) and PROBLEM_MASK
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

        private fun healthBitsIn(bits: Int): Int = bits and HEALTH_MASK

        /** The individual set bits of [mask], so an invariant can iterate a mask. */
        private fun bitsOf(mask: Int): List<Int> =
            (0 until Int.SIZE_BITS).map { 1 shl it }.filter { mask and it != 0 }

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
        private fun problemBitsIn(bits: Int): Int = PROBLEM_MASK and bits.inv()

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
         *
         * @return the bits actually exercised, OR-ed together. Returned rather than kept
         *         private because the dangerous failure of an invariant like this is not
         *         failing -- it is silently checking FEWER bits than exist and passing.
         *         A hand-written bit list did exactly that here, and no test noticed.
         *         The caller asserts this equals the full flag space, so coverage is
         *         itself checked rather than assumed.
         */
        fun assertNoAbsorbingBit(): Int {
            // ENUMERATED FROM THE MASKS, never hand-listed. A hand-written list is an
            // invariant that silently stops covering the bit you just added, which is
            // the failure mode this whole function exists to prevent -- it would have
            // been checking three of four bits and passing.
            val healthBits = bitsOf(HEALTH_MASK)
            val problemBits = bitsOf(PROBLEM_MASK)
            check(healthBits.isNotEmpty() && problemBits.isNotEmpty()) {
                "the health and problem masks must both be non-empty, or this invariant " +
                    "passes by checking nothing"
            }
            check(HEALTH_MASK and PROBLEM_MASK == 0) {
                "a bit cannot be both health and problem: the fold would AND and OR the " +
                    "same bit and the later term would silently win"
            }
            check(HEALTH_MASK and REASON_MASK == 0 && PROBLEM_MASK and REASON_MASK == 0) {
                "a flag bit overlaps REASON_MASK, so folding the flags would corrupt the " +
                    "refusal reason into a different, wrong reason"
            }
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

            return (healthBits + problemBits).fold(0) { acc, b -> acc or b }
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
