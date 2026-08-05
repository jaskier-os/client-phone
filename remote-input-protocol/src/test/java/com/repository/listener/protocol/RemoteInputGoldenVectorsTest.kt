package com.repository.listener.protocol

import com.repository.listener.protocol.RemoteInputProtocol.EventType
import com.repository.listener.protocol.RemoteInputProtocol.RemoteInputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-repo drift guard.
 *
 * The glasses live in a SEPARATE Gradle project written against the same written
 * spec, so no shared symbol can protect the two implementations from drifting.
 * A test where each side asserts its own literals proves nothing -- editing the
 * glasses constant and its own test leaves this side green.
 *
 * This file therefore generates golden vectors from the REAL codec and checks
 * them in. The glasses repo consumes the same file and asserts its parser
 * reproduces these exact bytes, args and tags. That is the only thing that
 * catches a disagreement about sign conventions, field widths, unsigned
 * rendering, or the canonical string used for the MAC.
 *
 * The key here is a FIXED TEST VECTOR, deliberately not a secret and never used
 * by a real build.
 */
class RemoteInputGoldenVectorsTest {

    private companion object {
        /** Fixed, published test key. NOT a secret and NOT used by any real build. */
        const val GOLDEN_KEY_HEX = "000102030405060708090a0b0c0d0e0f"

        val GOLDEN_FILE = File("src/test/resources/golden-vectors-v1.ndjson")
    }

    private val key = RemoteInputProtocol.parseHexOrNull(GOLDEN_KEY_HEX, 16)!!

    /**
     * The vector set. Deliberately covers the cases most likely to diverge between
     * two implementations: both scroll directions, the cap magnitudes, every event
     * type, and uint32 values above 2^31 where a signed rendering would differ.
     */
    private fun vectors(): List<RemoteInputEvent> = listOf(
        RemoteInputEvent(sid = 1, seq = 0, type = EventType.OPEN, steps = 0, wms = 0),
        RemoteInputEvent(sid = 1, seq = 1, type = EventType.SCROLL, steps = 1, wms = 1000),
        RemoteInputEvent(sid = 1, seq = 2, type = EventType.SCROLL, steps = -1, wms = 1060),
        // The cap magnitudes, where an int8 sign error would surface.
        RemoteInputEvent(sid = 1, seq = 3, type = EventType.SCROLL, steps = 8, wms = 1120),
        RemoteInputEvent(sid = 1, seq = 4, type = EventType.SCROLL, steps = -8, wms = 1180),
        RemoteInputEvent(sid = 1, seq = 5, type = EventType.SELECT, steps = 0, wms = 1240),
        RemoteInputEvent(sid = 1, seq = 6, type = EventType.BACK, steps = 0, wms = 1300),
        RemoteInputEvent(sid = 1, seq = 7, type = EventType.PING, steps = 0, wms = 11300),
        RemoteInputEvent(sid = 1, seq = 8, type = EventType.CLOSE, steps = 0, wms = 21300),
        // uint32 boundaries: a signed rendering anywhere would change the tag.
        RemoteInputEvent(sid = -1, seq = -1, type = EventType.SCROLL, steps = 8, wms = -1),
        RemoteInputEvent(
            sid = Int.MIN_VALUE, seq = Int.MIN_VALUE,
            type = EventType.SCROLL, steps = -8, wms = Int.MIN_VALUE,
        ),
        RemoteInputEvent(
            sid = Int.MAX_VALUE, seq = Int.MAX_VALUE,
            type = EventType.SCROLL, steps = 3, wms = Int.MAX_VALUE,
        ),
    )

    private fun renderVector(e: RemoteInputEvent): String {
        val tagHex = RemoteInputProtocol.computeTagHex(key, e)
        val payload = RemoteInputProtocol.toHex(RemoteInputProtocol.encodeEvent(key, e))
        val args = RemoteInputProtocol.toRfcommArgs(e, tagHex).joinToString("\",\"")
        // Hand-rolled JSON: this module has no serializer dependency, and the
        // shape is fixed and tiny.
        return """{"sid":${e.sidUnsigned},"seq":${e.seqUnsigned},"type":"${e.type.name}",""" +
            """"typeCode":${e.type.code},"steps":${e.steps},"wms":${e.wmsUnsigned},""" +
            """"canonical":"${RemoteInputProtocol.canonicalString(e)}","tag":"$tagHex",""" +
            """"payloadHex":"$payload","rfcommArgs":["$args"]}"""
    }

    /**
     * Regenerates the checked-in file when it is absent, and otherwise asserts the
     * real codec still reproduces it byte for byte. A failure here means either
     * this side changed the contract, or the file was edited by hand -- both of
     * which would silently break the glasses.
     */
    @Test
    fun goldenVectorsAreStable() {
        val generated = vectors().joinToString("\n") { renderVector(it) } + "\n"

        if (!GOLDEN_FILE.exists()) {
            GOLDEN_FILE.parentFile.mkdirs()
            GOLDEN_FILE.writeText(generated)
            println("Generated golden vectors at ${GOLDEN_FILE.absolutePath}")
            return
        }

        assertEquals(
            "The codec no longer reproduces the checked-in golden vectors. " +
                "If this change is intentional, the glasses implementation must be " +
                "updated in lockstep and this file regenerated.",
            GOLDEN_FILE.readText(),
            generated,
        )
    }

    /** Every vector must survive a full round trip through BOTH encodings. */
    @Test
    fun everyVectorRoundTripsThroughBothEncodings() {
        for (e in vectors()) {
            val decodedA = RemoteInputProtocol.decodeEvent(RemoteInputProtocol.encodeEvent(key, e))
            assertEquals("Encoding A round trip for $e", e, decodedA.event)
            assertTrue(
                "Encoding A tag must verify for $e",
                RemoteInputProtocol.verifyTag(key, decodedA.event, decodedA.tag),
            )

            val (decodedB, tagHexB) = RemoteInputProtocol.fromRfcommArgs(
                RemoteInputProtocol.toRfcommArgs(key, e).toList()
            )
            assertEquals("Encoding B round trip for $e", e, decodedB)
            assertTrue(
                "Encoding B tag must verify for $e",
                RemoteInputProtocol.verifyTagHex(key, decodedB, tagHexB),
            )

            // The two encodings must authenticate the SAME tuple, or the phone's
            // verbatim forward of a watch-signed event would not verify.
            assertEquals(
                "encodings must agree on the tag for $e",
                RemoteInputProtocol.toHex(decodedA.tag), tagHexB,
            )
        }
    }

    /**
     * REJECT vectors. The accept-only vectors above cannot catch a receiver whose
     * range check is still the superseded `abs(steps) > 16`: such a receiver
     * reproduces every valid vector byte for byte and passes. Drift is only caught
     * by asserting what must be REFUSED.
     *
     * The glasses repo must assert each of these is rejected, not merely that the
     * valid ones are accepted.
     */
    @Test
    fun rejectVectorsAreRefusedByTheDecoder() {
        val valid = RemoteInputEvent(
            sid = 1, seq = 1, type = EventType.SCROLL, steps = 1, wms = 1000,
        )
        val template = RemoteInputProtocol.encodeEvent(key, valid)

        // byte[1] is steps (int8). Everything outside +/-MAX_STEPS_PER_EVENT must
        // be refused rather than accepted-and-wrapped.
        val badSteps = listOf<Byte>(9, 16, 17, 127, -9, -16, -17, -128)
        for (s in badSteps) {
            val frame = template.copyOf().also { it[1] = s }
            assertRejected("steps=$s") { RemoteInputProtocol.decodeEvent(frame) }
        }

        // SCROLL must carry a non-zero magnitude.
        assertRejected("SCROLL with zero steps") {
            RemoteInputProtocol.decodeEvent(template.copyOf().also { it[1] = 0 })
        }

        // A non-SCROLL type must not carry steps.
        val select = RemoteInputProtocol.encodeEvent(
            key, valid.copy(type = EventType.SELECT, steps = 0),
        )
        assertRejected("SELECT carrying steps") {
            RemoteInputProtocol.decodeEvent(select.copyOf().also { it[1] = 3 })
        }

        // Type codes outside the enum.
        for (code in listOf<Byte>(0, 7, 99, -1)) {
            assertRejected("type code=$code") {
                RemoteInputProtocol.decodeEvent(template.copyOf().also { it[0] = code })
            }
        }

        // Reserved bytes 22..25 must be zero.
        for (i in 22..25) {
            assertRejected("reserved byte $i set") {
                RemoteInputProtocol.decodeEvent(template.copyOf().also { it[i] = 1 })
            }
        }

        // Length is checked EXACTLY, not >=.
        assertRejected("short frame") { RemoteInputProtocol.decodeEvent(template.copyOf(25)) }
        assertRejected("long frame") { RemoteInputProtocol.decodeEvent(template.copyOf(27)) }
    }

    private fun assertRejected(label: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected rejection but decode succeeded: $label")
        } catch (expected: RemoteInputProtocol.MalformedFrameException) {
            // Correct: rejected as malformed rather than silently accepted.
        }
    }

    @Test
    fun goldenVectorsCoverEveryEventType() {
        val covered = vectors().map { it.type }.toSet()
        assertEquals(
            "every event type must appear in the golden vectors",
            EventType.entries.toSet(), covered,
        )
    }
}
