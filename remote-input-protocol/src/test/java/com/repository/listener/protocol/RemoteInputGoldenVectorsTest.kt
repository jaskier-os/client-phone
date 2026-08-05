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
 * The glasses live in a SEPARATE Gradle project written against the same spec, so
 * no shared symbol can stop the two implementations from drifting. A test where
 * each side asserts its own literals proves nothing: editing the glasses constant
 * and its own test leaves this side green.
 *
 * This file therefore generates vectors from the REAL codec and checks them in,
 * and BOTH repos assert against the file.
 *
 * ACCEPT vectors alone are structurally incapable of catching the failure that
 * matters most. A receiver still enforcing the superseded cap of 16 reproduces
 * every valid vector byte for byte and passes. A guard that cannot fail is worse
 * than no guard, because it manufactures confidence. The file therefore also
 * carries REJECT vectors, each naming the rule it violates, and the verification
 * below READS them back from the file rather than reconstructing them in code --
 * so deleting them from the file fails this test instead of silently weakening
 * the guard.
 *
 * The fixed key below is a published test vector. It is NOT a secret and is not
 * used by any real build.
 */
class RemoteInputGoldenVectorsTest {

    private companion object {
        const val GOLDEN_KEY_HEX = "000102030405060708090a0b0c0d0e0f"
        val GOLDEN_FILE = File("src/test/resources/golden-vectors-v1.ndjson")

        /** Every reject rule the file must exercise. Deleting one fails the test. */
        val REQUIRED_REJECT_RULES = setOf(
            "steps_out_of_range",
            "scroll_zero_steps",
            "non_scroll_carries_steps",
            "unknown_type_code",
            "reserved_not_zero",
            "frame_too_short",
            "frame_too_long",
            "src_not_allowed",
            "bad_version",
            "too_few_args",
        )
    }

    private val key = RemoteInputProtocol.parseHexOrNull(GOLDEN_KEY_HEX, 16)!!

    /**
     * Ensures the file exists before a read-back test uses it. JUnit gives no
     * ordering guarantee, so a read-back test must not depend on the generator
     * test having run first -- otherwise the suite passes or fails by luck.
     */
    private fun goldenLines(): List<String> {
        if (!GOLDEN_FILE.exists()) {
            GOLDEN_FILE.parentFile.mkdirs()
            GOLDEN_FILE.writeText(generate())
        }
        return GOLDEN_FILE.readLines()
    }

    // ---- Vector definitions ----

    private fun acceptVectors(): List<RemoteInputEvent> = listOf(
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

    /** A frame that MUST be refused, with the rule it violates. */
    private data class RejectVector(
        val rule: String,
        val reason: String,
        val encoding: String,
        val payloadHex: String? = null,
        val rfcommArgs: List<String>? = null,
    )

    private fun rejectVectors(): List<RejectVector> {
        val valid = RemoteInputEvent(
            sid = 1, seq = 1, type = EventType.SCROLL, steps = 1, wms = 1000,
        )
        val template = RemoteInputProtocol.encodeEvent(key, valid)
        fun mutate(block: (ByteArray) -> Unit): String =
            RemoteInputProtocol.toHex(template.copyOf().also(block))

        val out = mutableListOf<RejectVector>()

        // byte[1] is steps (int8). Anything outside +/-MAX_STEPS_PER_EVENT must be
        // refused, never accepted-and-wrapped: a wrapped magnitude inverts the
        // scroll direction. 9 and 16 are the values a stale cap-16 receiver would
        // wrongly accept -- they are the whole point of this section.
        for (s in listOf<Byte>(9, 16, 17, 127, -9, -16, -17, -128)) {
            out += RejectVector(
                rule = "steps_out_of_range",
                reason = "abs(steps)=${Math.abs(s.toInt())} exceeds MAX_STEPS_PER_EVENT=" +
                    "${RemoteInputProtocol.MAX_STEPS_PER_EVENT}",
                encoding = "A",
                payloadHex = mutate { it[1] = s },
            )
        }
        out += RejectVector(
            "scroll_zero_steps", "SCROLL must carry a non-zero magnitude", "A",
            payloadHex = mutate { it[1] = 0 },
        )
        val select = RemoteInputProtocol.encodeEvent(
            key, valid.copy(type = EventType.SELECT, steps = 0),
        )
        out += RejectVector(
            "non_scroll_carries_steps", "only SCROLL may carry steps", "A",
            payloadHex = RemoteInputProtocol.toHex(select.copyOf().also { it[1] = 3 }),
        )
        for (code in listOf<Byte>(0, 7, 99, -1)) {
            out += RejectVector(
                "unknown_type_code",
                "type code ${code.toInt() and 0xFF} is outside the v1 enum (1..6)",
                "A",
                payloadHex = mutate { it[0] = code },
            )
        }
        for (i in 22..25) {
            out += RejectVector(
                "reserved_not_zero", "reserved byte $i must be zero", "A",
                payloadHex = mutate { it[i] = 1 },
            )
        }
        out += RejectVector(
            "frame_too_short", "Encoding A is exactly 26 bytes, got 25", "A",
            payloadHex = RemoteInputProtocol.toHex(template.copyOf(25)),
        )
        out += RejectVector(
            "frame_too_long", "Encoding A is exactly 26 bytes, got 27", "A",
            payloadHex = RemoteInputProtocol.toHex(template.copyOf(27)),
        )

        // Encoding B rejects.
        val validArgs = RemoteInputProtocol.toRfcommArgs(key, valid).toList()
        out += RejectVector(
            "src_not_allowed", "src must be in the hard-coded allowlist", "B",
            rfcommArgs = validArgs.toMutableList().also { it[1] = "attacker" },
        )
        out += RejectVector(
            "bad_version", "receivers drop frames with v != 1", "B",
            rfcommArgs = validArgs.toMutableList().also { it[0] = "2" },
        )
        out += RejectVector(
            "too_few_args", "Encoding B requires at least 8 positional args", "B",
            rfcommArgs = validArgs.dropLast(1),
        )
        return out
    }

    // ---- Rendering ----

    private fun renderAccept(e: RemoteInputEvent): String {
        val tagHex = RemoteInputProtocol.computeTagHex(key, e)
        val payload = RemoteInputProtocol.toHex(RemoteInputProtocol.encodeEvent(key, e))
        val args = RemoteInputProtocol.toRfcommArgs(e, tagHex).joinToString("\",\"")
        // Hand-rolled JSON: this module has no serializer dependency and the shape
        // is fixed and tiny.
        return """{"kind":"accept","sid":${e.sidUnsigned},"seq":${e.seqUnsigned},""" +
            """"type":"${e.type.name}","typeCode":${e.type.code},"steps":${e.steps},""" +
            """"wms":${e.wmsUnsigned},"canonical":"${RemoteInputProtocol.canonicalString(e)}",""" +
            """"tag":"$tagHex","payloadHex":"$payload","rfcommArgs":["$args"]}"""
    }

    private fun renderReject(v: RejectVector): String {
        val payload = v.payloadHex?.let { ""","payloadHex":"$it"""" } ?: ""
        val args = v.rfcommArgs?.let { ""","rfcommArgs":["${it.joinToString("\",\"")}"]""" } ?: ""
        return """{"kind":"reject","rule":"${v.rule}","encoding":"${v.encoding}",""" +
            """"mustReject":true,"reason":"${v.reason}"$payload$args}"""
    }

    private fun generate(): String =
        (acceptVectors().map { renderAccept(it) } + rejectVectors().map { renderReject(it) })
            .joinToString("\n") + "\n"

    // ---- Minimal readers (no serializer dependency) ----

    private fun field(line: String, name: String): String? =
        Regex(""""$name":"([^"]*)"""").find(line)?.groupValues?.get(1)

    private fun argsField(line: String): List<String>? =
        Regex(""""rfcommArgs":\[(.*?)\]""").find(line)?.groupValues?.get(1)
            ?.let { body ->
                if (body.isBlank()) emptyList()
                else Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
            }

    // ---- Tests ----

    /**
     * Regenerates the file when absent, otherwise asserts the real codec still
     * reproduces it byte for byte. A failure means either this side changed the
     * contract or the file was hand-edited -- both of which would break the
     * glasses silently.
     */
    @Test
    fun goldenVectorsAreStable() {
        val generated = generate()
        if (!GOLDEN_FILE.exists()) {
            GOLDEN_FILE.parentFile.mkdirs()
            GOLDEN_FILE.writeText(generated)
            println("Generated golden vectors at ${GOLDEN_FILE.absolutePath}")
            return
        }
        assertEquals(
            "The codec no longer reproduces the checked-in golden vectors. If this " +
                "change is intentional, the glasses implementation must be updated in " +
                "lockstep and this file regenerated.",
            GOLDEN_FILE.readText(),
            generated,
        )
    }

    /**
     * Reads the ACCEPT vectors back FROM THE FILE and replays them through the real
     * codec, so the file -- not this source -- is what the assertions rest on.
     */
    @Test
    fun acceptVectorsFromFileAreAccepted() {
        val lines = goldenLines().filter { it.contains(""""kind":"accept"""") }
        assertTrue("no accept vectors in the file", lines.isNotEmpty())

        for (line in lines) {
            val payloadHex = field(line, "payloadHex")!!
            val bytes = RemoteInputProtocol.parseHexOrNull(payloadHex, payloadHex.length / 2)!!
            val decoded = RemoteInputProtocol.decodeEvent(bytes)

            assertEquals("canonical string drift", field(line, "canonical"), RemoteInputProtocol.canonicalString(decoded.event))
            assertEquals("tag drift", field(line, "tag"), RemoteInputProtocol.toHex(decoded.tag))
            assertTrue("tag must verify", RemoteInputProtocol.verifyTag(key, decoded.event, decoded.tag))

            val expectedArgs = argsField(line)!!
            val (fromB, tagB) = RemoteInputProtocol.fromRfcommArgs(expectedArgs)
            assertEquals("encodings must agree", decoded.event, fromB)
            assertEquals(RemoteInputProtocol.toHex(decoded.tag), tagB)
        }
    }

    /**
     * THE guard that accept-only vectors cannot provide. Reads the REJECT vectors
     * FROM THE FILE and asserts the decoder refuses every one. A receiver still
     * enforcing the superseded cap of 16 fails here on `steps_out_of_range`.
     */
    @Test
    fun rejectVectorsFromFileAreRefused() {
        val lines = goldenLines().filter { it.contains(""""kind":"reject"""") }
        assertTrue("no reject vectors in the file", lines.isNotEmpty())

        val seenRules = mutableSetOf<String>()
        for (line in lines) {
            val rule = field(line, "rule")!!
            seenRules += rule
            val encoding = field(line, "encoding")!!
            var rejected = false
            try {
                if (encoding == "A") {
                    val hex = field(line, "payloadHex")!!
                    val bytes = RemoteInputProtocol.parseHexOrNull(hex, hex.length / 2)!!
                    RemoteInputProtocol.decodeEvent(bytes)
                } else {
                    RemoteInputProtocol.fromRfcommArgs(argsField(line)!!)
                }
            } catch (expected: RemoteInputProtocol.MalformedFrameException) {
                rejected = true
            }
            assertTrue("vector for rule '$rule' was ACCEPTED but must be rejected", rejected)
        }

        // Guard the guard: if a rule is dropped from the file, fail loudly rather
        // than quietly covering less than before.
        assertEquals(
            "the reject vector file no longer covers every required rule",
            REQUIRED_REJECT_RULES, seenRules,
        )
    }

    /** The cap magnitudes a stale receiver would wrongly accept must be present. */
    @Test
    fun rejectVectorsCoverTheSupersededCapValues() {
        val text = goldenLines().joinToString("\n")
        for (stale in listOf("abs(steps)=9", "abs(steps)=16")) {
            assertTrue(
                "the file must carry a reject vector for $stale, or a receiver " +
                    "still using the superseded cap of 16 would pass the whole set",
                text.contains(stale),
            )
        }
    }

    @Test
    fun everyVectorRoundTripsThroughBothEncodings() {
        for (e in acceptVectors()) {
            val decodedA = RemoteInputProtocol.decodeEvent(RemoteInputProtocol.encodeEvent(key, e))
            assertEquals("Encoding A round trip for $e", e, decodedA.event)
            assertTrue(RemoteInputProtocol.verifyTag(key, decodedA.event, decodedA.tag))
            val (decodedB, tagHexB) = RemoteInputProtocol.fromRfcommArgs(
                RemoteInputProtocol.toRfcommArgs(key, e).toList()
            )
            assertEquals("Encoding B round trip for $e", e, decodedB)
            assertTrue(RemoteInputProtocol.verifyTagHex(key, decodedB, tagHexB))
            assertEquals(RemoteInputProtocol.toHex(decodedA.tag), tagHexB)
        }
    }

    @Test
    fun goldenVectorsCoverEveryEventType() {
        assertEquals(
            "every event type must appear in the accept vectors",
            EventType.entries.toSet(), acceptVectors().map { it.type }.toSet(),
        )
    }
}
