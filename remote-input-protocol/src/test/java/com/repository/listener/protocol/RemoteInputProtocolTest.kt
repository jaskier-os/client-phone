package com.repository.listener.protocol

import com.repository.listener.protocol.RemoteInputProtocol.EventType
import com.repository.listener.protocol.RemoteInputProtocol.MalformedFrameException
import com.repository.listener.protocol.RemoteInputProtocol.RemoteInputEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputProtocolTest {

    private val key = "test-key-not-a-real-secret".toByteArray()

    private fun event(
        sid: Int = 0x11223344,
        seq: Int = 7,
        type: EventType = EventType.SCROLL,
        steps: Int = 3,
        wms: Int = 123456,
    ) = RemoteInputEvent(sid = sid, seq = seq, type = type, steps = steps, wms = wms)

    // ---- Encoding A round trips ----

    @Test
    fun encodingA_roundTripsAllFields() {
        val e = event()
        val decoded = RemoteInputProtocol.decodeEvent(RemoteInputProtocol.encodeEvent(key, e))
        assertEquals(e, decoded.event)
        assertTrue(RemoteInputProtocol.verifyTag(key, decoded.event, decoded.tag))
    }

    @Test
    fun encodingA_payloadIsExactly26Bytes() {
        assertEquals(26, RemoteInputProtocol.encodeEvent(key, event()).size)
    }

    @Test
    fun encodingA_roundTripsEveryEventType() {
        for (type in EventType.entries) {
            val steps = if (type.carriesSteps) 2 else 0
            val e = event(type = type, steps = steps)
            val decoded = RemoteInputProtocol.decodeEvent(RemoteInputProtocol.encodeEvent(key, e))
            assertEquals("type $type", e, decoded.event)
        }
    }

    @Test
    fun encodingA_roundTripsStepsBoundaries() {
        for (steps in listOf(-8, -4, -1, 1, 4, 8)) {
            val e = event(steps = steps)
            val decoded = RemoteInputProtocol.decodeEvent(RemoteInputProtocol.encodeEvent(key, e))
            assertEquals("steps $steps", steps, decoded.event.steps)
        }
    }

    /** uint32 values above 2^31 must survive as the same bit pattern. */
    @Test
    fun encodingA_roundTripsHighUnsignedValues() {
        val e = event(sid = -1, seq = Int.MIN_VALUE, wms = -12345)
        val decoded = RemoteInputProtocol.decodeEvent(RemoteInputProtocol.encodeEvent(key, e))
        assertEquals(e.sid, decoded.event.sid)
        assertEquals(e.seq, decoded.event.seq)
        assertEquals(e.wms, decoded.event.wms)
        assertEquals("4294967295", decoded.event.sidUnsigned)
        assertEquals("2147483648", decoded.event.seqUnsigned)
    }

    // ---- Encoding A rejection ----

    @Test(expected = MalformedFrameException::class)
    fun encodingA_rejectsShortPayload() {
        RemoteInputProtocol.decodeEvent(ByteArray(25))
    }

    /** Exact length, not >=: a longer frame is a different protocol. */
    @Test(expected = MalformedFrameException::class)
    fun encodingA_rejectsLongPayload() {
        RemoteInputProtocol.decodeEvent(ByteArray(27))
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingA_rejectsNullPayload() {
        RemoteInputProtocol.decodeEvent(null)
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingA_rejectsUnknownTypeCode() {
        val bytes = RemoteInputProtocol.encodeEvent(key, event())
        bytes[0] = 99
        RemoteInputProtocol.decodeEvent(bytes)
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingA_rejectsNonZeroReservedBytes() {
        val bytes = RemoteInputProtocol.encodeEvent(key, event())
        bytes[25] = 1
        RemoteInputProtocol.decodeEvent(bytes)
    }

    /**
     * steps is an int8 on the wire. A summed magnitude above 127 would wrap and
     * flip direction, turning a large scroll into a large scroll the OTHER way,
     * so out-of-range magnitudes must be rejected rather than accepted-and-wrapped.
     */
    @Test(expected = MalformedFrameException::class)
    fun encodingA_rejectsStepsAboveCap() {
        val bytes = RemoteInputProtocol.encodeEvent(key, event())
        bytes[1] = 17
        RemoteInputProtocol.decodeEvent(bytes)
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingA_rejectsScrollWithZeroSteps() {
        val bytes = RemoteInputProtocol.encodeEvent(key, event())
        bytes[1] = 0
        RemoteInputProtocol.decodeEvent(bytes)
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingA_rejectsNonScrollCarryingSteps() {
        val bytes = RemoteInputProtocol.encodeEvent(key, event(type = EventType.SELECT, steps = 0))
        bytes[1] = 5
        RemoteInputProtocol.decodeEvent(bytes)
    }

    // ---- HMAC ----

    @Test
    fun tag_isStableAndEightBytes() {
        val e = event()
        assertEquals(8, RemoteInputProtocol.computeTag(key, e).size)
        assertEquals(16, RemoteInputProtocol.computeTagHex(key, e).length)
        assertArrayEquals(
            RemoteInputProtocol.computeTag(key, e),
            RemoteInputProtocol.computeTag(key, e),
        )
    }

    /** Every covered field must change the tag, or it is not actually covered. */
    @Test
    fun tag_changesWithEveryCoveredField() {
        val base = event()
        val baseTag = RemoteInputProtocol.computeTagHex(key, base)
        val mutations = listOf(
            base.copy(sid = base.sid + 1),
            base.copy(seq = base.seq + 1),
            base.copy(type = EventType.SELECT, steps = 0),
            base.copy(steps = base.steps + 1),
            base.copy(wms = base.wms + 1),
            base.copy(version = 2),
        )
        for (m in mutations) {
            assertNotEquals("mutation $m", baseTag, RemoteInputProtocol.computeTagHex(key, m))
        }
    }

    @Test
    fun tag_rejectsWrongKey() {
        val e = event()
        val tag = RemoteInputProtocol.computeTag(key, e)
        assertFalse(RemoteInputProtocol.verifyTag("other-key".toByteArray(), e, tag))
    }

    @Test
    fun tag_rejectsMalformedHex() {
        val e = event()
        assertFalse(RemoteInputProtocol.verifyTagHex(key, e, "zzzz"))
        assertFalse(RemoteInputProtocol.verifyTagHex(key, e, "00"))
    }

    /**
     * The canonical string is the contract between three independent
     * implementations. Pin its exact shape: unsigned sid/seq/wms, numeric type
     * code, signed steps with no leading '+', no padding.
     */
    @Test
    fun canonicalString_hasFrozenShape() {
        val e = RemoteInputEvent(sid = -1, seq = 5, type = EventType.SCROLL, steps = -3, wms = 42)
        assertEquals("1|watch|4294967295|5|1|-3|42", RemoteInputProtocol.canonicalString(e))
    }

    // ---- Encoding B ----

    @Test
    fun encodingB_roundTrips() {
        val e = event(steps = -4)
        val args = RemoteInputProtocol.toRfcommArgs(key, e).toList()
        assertEquals(8, args.size)
        val (parsed, tagHex) = RemoteInputProtocol.fromRfcommArgs(args)
        assertEquals(e, parsed)
        assertTrue(RemoteInputProtocol.verifyTagHex(key, parsed, tagHex))
    }

    @Test
    fun encodingB_stepsSignIsPreservedWithoutPlusPrefix() {
        val args = RemoteInputProtocol.toRfcommArgs(key, event(steps = 5)).toList()
        assertEquals("5", args[5])
        val negative = RemoteInputProtocol.toRfcommArgs(key, event(steps = -5)).toList()
        assertEquals("-5", negative[5])
    }

    /** Forward compatibility inside v1: extra trailing args are ignored. */
    @Test
    fun encodingB_ignoresExtraTrailingArgs() {
        val args = RemoteInputProtocol.toRfcommArgs(key, event()).toList() + listOf("future", "x")
        val (parsed, _) = RemoteInputProtocol.fromRfcommArgs(args)
        assertEquals(event(), parsed)
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingB_rejectsTooFewArgs() {
        RemoteInputProtocol.fromRfcommArgs(
            RemoteInputProtocol.toRfcommArgs(key, event()).toList().dropLast(1)
        )
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingB_rejectsNonNumericSeq() {
        val args = RemoteInputProtocol.toRfcommArgs(key, event()).toMutableList()
        args[3] = "not-a-number"
        RemoteInputProtocol.fromRfcommArgs(args)
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingB_rejectsDisallowedSrc() {
        val args = RemoteInputProtocol.toRfcommArgs(key, event()).toMutableList()
        args[1] = "attacker"
        RemoteInputProtocol.fromRfcommArgs(args)
    }

    @Test(expected = MalformedFrameException::class)
    fun encodingB_rejectsBadVersion() {
        val args = RemoteInputProtocol.toRfcommArgs(key, event()).toMutableList()
        args[0] = "2"
        RemoteInputProtocol.fromRfcommArgs(args)
    }

    /** The two encodings must authenticate the SAME tuple, or the phone's
     *  verbatim forward would not verify on the glasses. */
    @Test
    fun encodings_agreeOnTheTag() {
        val e = event(steps = -6)
        val fromA = RemoteInputProtocol.decodeEvent(RemoteInputProtocol.encodeEvent(key, e))
        val (fromB, tagHexB) = RemoteInputProtocol.fromRfcommArgs(
            RemoteInputProtocol.toRfcommArgs(key, e).toList()
        )
        assertEquals(fromA.event, fromB)
        assertEquals(RemoteInputProtocol.toHex(fromA.tag), tagHexB)
    }

    // ---- Status ----

    @Test
    fun status_roundTripsEachBit() {
        val bits = RemoteInputProtocol.StatusFlags.encode(
            glassesLinkUp = true,
            phoneServiceAlive = false,
            lastSendDropped = true,
            glassesSinkAttached = false,
            wakingGlasses = true,
        )
        val decoded = RemoteInputProtocol.StatusFlags.decode(bits)
        assertTrue(RemoteInputProtocol.StatusFlags.isSet(decoded, RemoteInputProtocol.StatusFlags.GLASSES_LINK_UP))
        assertFalse(RemoteInputProtocol.StatusFlags.isSet(decoded, RemoteInputProtocol.StatusFlags.PHONE_SERVICE_ALIVE))
        assertTrue(RemoteInputProtocol.StatusFlags.isSet(decoded, RemoteInputProtocol.StatusFlags.LAST_SEND_DROPPED))
        assertFalse(RemoteInputProtocol.StatusFlags.isSet(decoded, RemoteInputProtocol.StatusFlags.GLASSES_SINK_ATTACHED))
        assertTrue(RemoteInputProtocol.StatusFlags.isSet(decoded, RemoteInputProtocol.StatusFlags.WAKING_GLASSES))
    }

    @Test(expected = MalformedFrameException::class)
    fun status_rejectsWrongLength() {
        RemoteInputProtocol.StatusFlags.decode(ByteArray(2))
    }

    /**
     * The status path is unauthenticated by design, so a forged frame must only
     * ever be able to make the watch more pessimistic -- never to assert health
     * over a locally observed failure, and never to resume sending.
     */
    @Test
    fun status_forgedHealthCannotOverrideALocalFailure() {
        val flags = RemoteInputProtocol.StatusFlags
        // The watch locally believes the link is down and a send was dropped.
        val current = flags.LAST_SEND_DROPPED
        // A forged frame claims everything is healthy.
        val forged = flags.GLASSES_LINK_UP or flags.PHONE_SERVICE_ALIVE or
            flags.GLASSES_SINK_ATTACHED
        val merged = flags.applyAdvisory(current, forged, trusted = false)
        assertFalse(flags.isSet(merged, flags.GLASSES_LINK_UP))
        assertFalse(flags.isSet(merged, flags.PHONE_SERVICE_ALIVE))
        assertTrue("a locally observed failure must survive", flags.isSet(merged, flags.LAST_SEND_DROPPED))
    }

    @Test
    fun status_forgedProblemsAreStillHonoured() {
        val flags = RemoteInputProtocol.StatusFlags
        val current = flags.GLASSES_LINK_UP or flags.PHONE_SERVICE_ALIVE
        val merged = flags.applyAdvisory(current, flags.WAKING_GLASSES, trusted = false)
        assertTrue(flags.isSet(merged, flags.WAKING_GLASSES))
    }

    @Test
    fun status_trustedTransitionRestoresHealth() {
        val flags = RemoteInputProtocol.StatusFlags
        val healthy = flags.GLASSES_LINK_UP or flags.PHONE_SERVICE_ALIVE or
            flags.GLASSES_SINK_ATTACHED
        val merged = flags.applyAdvisory(flags.LAST_SEND_DROPPED, healthy, trusted = true)
        assertEquals("a genuine local reconnect may clear failure state", healthy, merged)
    }

    /**
     * The glasses are an independent implementation. If they render the tag with
     * "%02X" we must still accept it, or the feature is 100 % broken with no
     * useful error. Emit lowercase, accept either case.
     */
    @Test
    fun tag_acceptsUppercaseHexFromAnotherImplementation() {
        val e = event()
        val lower = RemoteInputProtocol.computeTagHex(key, e)
        assertEquals("we always EMIT lowercase", lower, lower.lowercase())
        assertTrue(RemoteInputProtocol.verifyTagHex(key, e, lower.uppercase()))
    }

    // ---- Sequence arithmetic ----

    /** A plain `<=` deadlocks the source forever at uint32 wraparound. */
    @Test
    fun seqDifference_isWrapSafe() {
        assertTrue(RemoteInputProtocol.seqDifference(5, 4) > 0)
        assertTrue(RemoteInputProtocol.seqDifference(4, 5) < 0)
        assertEquals(0, RemoteInputProtocol.seqDifference(5, 5))
        // 0xFFFFFFFF -> 0x00000000 is a forward step of 1, not a 4-billion regression.
        assertTrue(RemoteInputProtocol.seqDifference(0, -1) > 0)
        assertTrue(RemoteInputProtocol.seqDifference(Int.MIN_VALUE, Int.MAX_VALUE) > 0)
    }

    // ---- TTL calibration ----

    @Test
    fun ttl_derivesFromMeasuredP95WithFloor() {
        assertEquals(400L, RemoteInputProtocol.TtlCalibration.fromP95(10.0))
        assertEquals(400L, RemoteInputProtocol.TtlCalibration.fromP95(150.0))
        assertEquals(500L, RemoteInputProtocol.TtlCalibration.fromP95(200.0))
        // 300 * 2.5 = 750, rounded UP to the next 100 ms boundary.
        assertEquals(800L, RemoteInputProtocol.TtlCalibration.fromP95(300.0))
    }

    @Test
    fun validate_acceptsWellFormedEvents() {
        assertNull(RemoteInputProtocol.validate(event()))
        assertNull(RemoteInputProtocol.validate(event(type = EventType.PING, steps = 0)))
    }
}
