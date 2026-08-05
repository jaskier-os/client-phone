package com.repository.listener.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Framing tests for the glasses -> phone back-channel.
 *
 * This path had NO test at all, and it was broken in the most silent way
 * available: the input socket read the bytes into a scratch buffer and discarded
 * them, and the only parser lived on a different socket that never receives them.
 * The user-visible result was the watch reporting "Glasses screen not active"
 * permanently, including while scrolling worked -- a status display that lies.
 *
 * The encoder below is written independently, from the wire format the glasses'
 * `MessageRelay.buildFrame` produces, rather than by calling any phone-side
 * helper. A test that encodes with the same code it decodes with proves only
 * self-consistency, which is exactly how a cross-device format drifts unnoticed.
 */
class InputBackChannelParserTest {

    /** Independent encoder, matching MessageRelay.buildFrame on the glasses. */
    private fun frame(channel: String, vararg args: String): ByteArray {
        val payload = ByteArrayOutputStream()
        val chan = channel.toByteArray(Charsets.UTF_8)
        payload.write(chan.size)
        payload.write(chan)
        payload.write(args.size)
        for (a in args) {
            val b = a.toByteArray(Charsets.UTF_8)
            payload.write(ByteBuffer.allocate(4).putInt(b.size).array())
            payload.write(b)
        }
        val body = payload.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(body.size).array())
        out.write(body)
        return out.toByteArray()
    }

    private val received = mutableListOf<Pair<String, List<String>>>()

    private fun newParser(maxBytes: Int = InputBackChannelParser.DEFAULT_MAX_BUFFER_BYTES) =
        InputBackChannelParser(maxBufferBytes = maxBytes) { channel, args ->
            received += channel to args
        }

    private fun InputBackChannelParser.feed(bytes: ByteArray) = onBytes(bytes, bytes.size)

    @Test
    fun decodesASinkFrame() {
        val parser = newParser()
        parser.feed(frame(BtProtocol.CH_REMOTE_INPUT_SINK, "1"))
        assertEquals(listOf(BtProtocol.CH_REMOTE_INPUT_SINK to listOf("1")), received)
    }

    @Test
    fun decodesAStatusFrameWithAllThreeArgs() {
        val parser = newParser()
        parser.feed(frame(BtProtocol.CH_REMOTE_INPUT_STATUS, "1", "0", "7"))
        assertEquals(
            listOf(BtProtocol.CH_REMOTE_INPUT_STATUS to listOf("1", "0", "7")),
            received,
        )
    }

    /**
     * RFCOMM delivers whatever the kernel has, so a frame arriving one byte at a
     * time is ordinary rather than exotic. A parser that only works on whole reads
     * looks correct in a test and drops frames on hardware.
     */
    @Test
    fun reassemblesAFrameSplitAcrossEveryPossibleBoundary() {
        val bytes = frame(BtProtocol.CH_REMOTE_INPUT_SINK, "0")
        val parser = newParser()
        for (b in bytes) parser.feed(byteArrayOf(b))
        assertEquals(listOf(BtProtocol.CH_REMOTE_INPUT_SINK to listOf("0")), received)
    }

    @Test
    fun decodesTwoFramesArrivingInOneRead() {
        val parser = newParser()
        parser.feed(
            frame(BtProtocol.CH_REMOTE_INPUT_SINK, "1") +
                frame(BtProtocol.CH_REMOTE_INPUT_STATUS, "1", "1", "0"),
        )
        assertEquals(2, received.size)
        assertEquals(BtProtocol.CH_REMOTE_INPUT_SINK, received[0].first)
        assertEquals(BtProtocol.CH_REMOTE_INPUT_STATUS, received[1].first)
    }

    /** A trailing partial frame must be held, not dropped or misread. */
    @Test
    fun holdsAPartialTrailingFrameUntilTheRestArrives() {
        val whole = frame(BtProtocol.CH_REMOTE_INPUT_SINK, "1")
        val second = frame(BtProtocol.CH_REMOTE_INPUT_STATUS, "0", "0", "3")
        val parser = newParser()
        parser.feed(whole + second.copyOfRange(0, 5))
        assertEquals("only the complete frame may be dispatched", 1, received.size)
        parser.feed(second.copyOfRange(5, second.size))
        assertEquals(2, received.size)
        assertEquals(listOf("0", "0", "3"), received[1].second)
    }

    /**
     * A malformed BODY is survivable: its declared length was valid, so the next
     * frame boundary is still known and the stream must continue.
     */
    @Test
    fun survivesAMalformedBodyAndKeepsParsingTheNextFrame() {
        val parser = newParser()
        // Declares 3 args but supplies none.
        val body = ByteArrayOutputStream().apply {
            val chan = BtProtocol.CH_REMOTE_INPUT_SINK.toByteArray()
            write(chan.size); write(chan); write(3)
        }.toByteArray()
        val bad = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(body.size).array()); write(body)
        }.toByteArray()

        parser.feed(bad)
        assertEquals("a malformed body must not be dispatched", 0, received.size)
        parser.feed(frame(BtProtocol.CH_REMOTE_INPUT_SINK, "1"))
        assertEquals("the stream must survive a bad body", 1, received.size)
    }

    /**
     * An absurd declared length cannot be trusted and cannot be skipped -- the
     * reader no longer knows where the next frame starts. It must say so rather
     * than resume on misaligned bytes and appear to work.
     */
    @Test
    fun anImpossibleLengthDesynchronizesRatherThanSilentlyResuming() {
        val parser = newParser()
        parser.feed(ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array())
        assertTrue("must report desynchronization", parser.desynchronized)
        parser.feed(frame(BtProtocol.CH_REMOTE_INPUT_SINK, "1"))
        assertEquals("must not pretend to parse while misaligned", 0, received.size)
        parser.reset()
        parser.feed(frame(BtProtocol.CH_REMOTE_INPUT_SINK, "1"))
        assertEquals("a fresh socket must recover", 1, received.size)
    }

    /** The accumulation buffer must be bounded by us, not by the peer. */
    @Test
    fun aFloodDoesNotGrowTheBufferUnbounded() {
        val parser = newParser(maxBytes = 128)
        parser.feed(ByteArray(4096) { 0x41 })
        assertTrue("an oversized accumulation must desynchronize", parser.desynchronized)
        parser.reset()
        parser.feed(frame(BtProtocol.CH_REMOTE_INPUT_SINK, "1"))
        assertEquals(1, received.size)
    }

    /** Frames for other channels are ignored without disturbing alignment. */
    @Test
    fun anUnrelatedChannelDoesNotBreakTheStream() {
        val parser = newParser()
        parser.feed(frame("listener_something_else", "x"))
        parser.feed(frame(BtProtocol.CH_REMOTE_INPUT_SINK, "1"))
        assertEquals(2, received.size)
        assertEquals(BtProtocol.CH_REMOTE_INPUT_SINK, received[1].first)
    }
}
