package com.repository.listener.protocol

import com.repository.listener.protocol.RemoteInputProtocol.EventType
import com.repository.listener.protocol.RemoteInputProtocol.MalformedFrameException
import com.repository.listener.protocol.RemoteInputProtocol.RemoteInputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deliberate sweep of identity and overflow values at type boundaries, rather
 * than the incidental coverage that grew out of feature tests.
 *
 * This exists because `Math.abs(Int.MIN_VALUE)` is itself negative, which let
 * `Int.MIN_VALUE` pass validation and encode as a zero-step SCROLL. That class of
 * bug -- the value where an operation's normal identity breaks down -- is worth
 * hunting for systematically at every boundary the codec touches.
 */
class BoundaryValueTest {

    private val key = "boundary-test-key".toByteArray()

    // ---- Int.MIN_VALUE: the value where abs() and negate() break ----

    @Test
    fun intMinValueStepsIsRejectedNotSilentlyTruncated() {
        val e = RemoteInputEvent(
            sid = 1, seq = 1, type = EventType.SCROLL, steps = Int.MIN_VALUE, wms = 0,
        )
        assertNotNull("Int.MIN_VALUE steps must be refused by validate", RemoteInputProtocol.validate(e))
    }

    @Test
    fun intMaxValueStepsIsRejected() {
        val e = RemoteInputEvent(
            sid = 1, seq = 1, type = EventType.SCROLL, steps = Int.MAX_VALUE, wms = 0,
        )
        assertNotNull(RemoteInputProtocol.validate(e))
    }

    /** i8 boundaries: -128 has no positive counterpart, +127 is the max. */
    @Test
    fun int8BoundaryStepsAreRejectedAsOutOfRange() {
        for (steps in listOf(-128, 127, -127, 8 + 1, -8 - 1)) {
            val e = RemoteInputEvent(sid = 1, seq = 1, type = EventType.SCROLL, steps = steps, wms = 0)
            assertNotNull("steps=$steps must be refused", RemoteInputProtocol.validate(e))
        }
    }

    @Test
    fun exactCapMagnitudesAreAccepted() {
        for (steps in listOf(RemoteInputProtocol.MAX_STEPS_PER_EVENT, -RemoteInputProtocol.MAX_STEPS_PER_EVENT)) {
            val e = RemoteInputEvent(sid = 1, seq = 1, type = EventType.SCROLL, steps = steps, wms = 0)
            assertEquals("steps=$steps must be accepted", null, RemoteInputProtocol.validate(e))
        }
    }

    // ---- uint32 boundaries carried in a signed Int ----

    @Test
    fun uint32MaxRoundTripsAndRendersUnsigned() {
        val e = RemoteInputEvent(
            sid = -1, seq = -1, type = EventType.SCROLL, steps = 1, wms = -1,
        )
        assertEquals("4294967295", e.sidUnsigned)
        assertEquals("4294967295", e.seqUnsigned)
        assertEquals("4294967295", e.wmsUnsigned)
        val decoded = RemoteInputProtocol.decodeEvent(RemoteInputProtocol.encodeEvent(key, e))
        assertEquals(e, decoded.event)
        assertTrue(RemoteInputProtocol.verifyTag(key, decoded.event, decoded.tag))
    }

    @Test
    fun uint32SignBoundaryRendersUnsigned() {
        // 0x80000000 is where a signed rendering would flip to negative.
        val e = RemoteInputEvent(
            sid = Int.MIN_VALUE, seq = Int.MIN_VALUE, type = EventType.PING, steps = 0,
            wms = Int.MIN_VALUE,
        )
        assertEquals("2147483648", e.sidUnsigned)
        assertFalse("no field may render with a minus sign", RemoteInputProtocol.canonicalString(e).contains("-"))
    }

    @Test
    fun seqWrapAroundIsForwardProgressNotRegression() {
        // 0xFFFFFFFF -> 0x00000000 is +1, not a 4-billion step backwards.
        assertTrue(RemoteInputProtocol.seqDifference(0, -1) > 0)
        assertEquals(1, RemoteInputProtocol.seqDifference(0, -1))
        // and the far half-range boundary still orders correctly
        assertTrue(RemoteInputProtocol.seqDifference(Int.MIN_VALUE, Int.MAX_VALUE) > 0)
    }

    // ---- Frame size boundaries ----

    @Test
    fun emptyAndOversizedFramesAreRejected() {
        val sizes = listOf(0, 1, 25, 27, 64, 1024)
        for (n in sizes) {
            try {
                RemoteInputProtocol.decodeEvent(ByteArray(n))
                throw AssertionError("expected rejection for a $n-byte frame")
            } catch (expected: MalformedFrameException) {
                // correct
            }
        }
    }

    @Test
    fun statusFrameRejectsAnythingNarrowerThanTheBitfield() {
        // 2 bytes is the bare bitfield and 6 is the legal correlated form (2 bitfield +
        // 4 reply seq). Anything narrower than the bitfield is rejected outright rather
        // than zero-extended: a truncated frame must never be READ as "no problems
        // reported", because that is the one verdict the watch acts on by doing nothing.
        //
        // 1 is in the list deliberately. It is the PREVIOUS wire width, so this is also
        // the assertion that widening is a real, detectable break rather than a silent
        // reinterpretation -- which is why the phone must be deployed before the watch.
        for (n in listOf(0, 1)) {
            try {
                RemoteInputProtocol.StatusFlags.decode(ByteArray(n))
                throw AssertionError("expected rejection for a $n-byte status frame")
            } catch (expected: MalformedFrameException) {
                // correct
            }
        }
        // And the legal widths decode.
        assertEquals(0, RemoteInputProtocol.StatusFlags.decode(ByteArray(2)))
        assertEquals(0, RemoteInputProtocol.StatusFlags.decode(ByteArray(6)))
    }

    @Test
    fun rfcommArgsRejectEmptyList() {
        try {
            RemoteInputProtocol.fromRfcommArgs(emptyList())
            throw AssertionError("expected rejection for an empty arg list")
        } catch (expected: MalformedFrameException) {
            // correct
        }
    }

    /** A uint32 field must not accept a value beyond the unsigned range. */
    @Test
    fun rfcommArgsRejectOutOfRangeUnsignedFields() {
        val valid = RemoteInputProtocol.toRfcommArgs(
            key, RemoteInputEvent(sid = 1, seq = 1, type = EventType.SCROLL, steps = 1, wms = 0),
        ).toMutableList()
        for (index in listOf(2, 3, 6)) {
            val args = valid.toMutableList()
            args[index] = "4294967296" // 2^32, one past the top
            try {
                RemoteInputProtocol.fromRfcommArgs(args)
                throw AssertionError("expected rejection for arg $index out of uint32 range")
            } catch (expected: MalformedFrameException) {
                // correct
            }
            val negative = valid.toMutableList()
            negative[index] = "-1"
            try {
                RemoteInputProtocol.fromRfcommArgs(negative)
                throw AssertionError("expected rejection for a negative unsigned arg $index")
            } catch (expected: MalformedFrameException) {
                // correct
            }
        }
    }

    // ---- Float identity values entering the accumulator ----
    //
    // Floats never cross the wire, but AXIS_SCROLL arrives as a Float from the
    // platform. A non-finite value would poison the running sum permanently:
    // NaN propagates through every subsequent addition, so the bezel would go
    // dead for the rest of the session with no error anywhere.

    @Test
    fun negativeZeroDeltaProducesNoDetentAndNoStateChange() {
        val a = DetentAccumulator(1.0f)
        assertEquals(0, a.onDelta(-0.0f, 0L))
        assertFalse(a.hasUndeliveredDetents)
        // The accumulator must still work normally afterwards.
        assertEquals(1, a.onDelta(1.0f, 10L))
    }

    @Test
    fun nanDeltaDoesNotPoisonTheAccumulator() {
        val a = DetentAccumulator(1.0f)
        a.onDelta(Float.NaN, 0L)
        assertEquals(
            "a NaN sample must not wedge the bezel for the rest of the session",
            1, a.onDelta(1.0f, 10L),
        )
    }

    @Test
    fun infiniteDeltaDoesNotPoisonTheAccumulator() {
        for (bad in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val a = DetentAccumulator(1.0f)
            a.onDelta(bad, 0L)
            assertEquals(
                "an infinite sample must not wedge the bezel ($bad)",
                1, a.onDelta(1.0f, 10L),
            )
        }
    }

    @Test
    fun hugeFiniteDeltaIsChunkedWithinTheWireRange() {
        val a = DetentAccumulator(1.0f)
        val emitted = a.onDelta(1_000_000f, 0L)
        assertTrue("emission must stay within the per-event clamp", Math.abs(emitted) <= 4)
        // And every subsequent drain must also stay in range.
        repeat(50) {
            val step = a.drain(it * 30L)
            assertTrue("drain must stay within the clamp", Math.abs(step) <= 4)
        }
    }

    // ---- Coalescer emissions stay encodable under boundary input ----

    @Test
    fun coalescerNeverEmitsAnUnencodableEvent() {
        val emissions = mutableListOf<Pair<EventType, Int>>()
        val c = ScrollCoalescer(sink = { type, steps, _ -> emissions += type to steps })
        var now = 0L
        for (burst in listOf(1, 8, 9, 100, -100, -9, 3)) {
            c.onDetents(burst, now)
            now += 5L
        }
        c.flush(now)
        for ((type, steps) in emissions) {
            val e = RemoteInputEvent(sid = 1, seq = 1, type = type, steps = steps, wms = 0)
            assertEquals(
                "coalescer emitted an unencodable event: type=$type steps=$steps",
                null, RemoteInputProtocol.validate(e),
            )
            RemoteInputProtocol.encodeEvent(key, e)
        }
    }
}
