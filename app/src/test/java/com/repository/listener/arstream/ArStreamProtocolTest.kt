package com.repository.listener.arstream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ArStreamProtocolTest {

    @Test
    fun videoHeaderMatchesScreenStreamDecoderLayout() {
        val payload = byteArrayOf(1, 2, 3)
        val framed = ArStreamProtocol.frameVideo(payload, keyframe = true, config = false, timestampMs = 0x01020304L)

        val declaredLen = ((framed[0].toInt() and 0xFF) shl 24) or
            ((framed[1].toInt() and 0xFF) shl 16) or
            ((framed[2].toInt() and 0xFF) shl 8) or
            (framed[3].toInt() and 0xFF)
        assertEquals(ArStreamProtocol.VIDEO_HEADER_SIZE + payload.size, declaredLen)

        val body = framed.copyOfRange(4, framed.size)
        assertEquals(ArStreamProtocol.VERSION, body[0])
        assertEquals(ArStreamProtocol.FLAG_KEYFRAME, body[1])
        assertArrayEquals(payload, body.copyOfRange(ArStreamProtocol.VIDEO_HEADER_SIZE, body.size))
    }

    @Test
    fun configFlagIsBitOne() {
        val framed = ArStreamProtocol.frameVideo(byteArrayOf(9), keyframe = false, config = true, timestampMs = 0L)
        assertEquals(ArStreamProtocol.FLAG_CONFIG, framed[5])
    }

    @Test
    fun audioAndControlMessagesAreDistinguishable() {
        val audio = ArStreamProtocol.frameAudio(ShortArray(160))
        val control = ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_MUTE_GLASSES_MIC, on = true)
        assertEquals(ArStreamProtocol.MSG_AUDIO, audio[4])
        assertEquals(ArStreamProtocol.MSG_CONTROL, control[4])
    }

    @Test
    fun audioRoundTripsThroughLittleEndianPcm16() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 1234)
        val framed = ArStreamProtocol.frameAudio(pcm)
        val body = framed.copyOfRange(4, framed.size)
        assertArrayEquals(pcm, ArStreamProtocol.decodeAudio(body))
    }

    @Test
    fun timestampSurvivesRoundTrip() {
        val ts = 0x0A0B0C0DL
        val framed = ArStreamProtocol.frameVideo(byteArrayOf(0), keyframe = false, config = false, timestampMs = ts)
        val body = framed.copyOfRange(4, framed.size)
        val decoded = ((body[6].toLong() and 0xFF) shl 24) or
            ((body[7].toLong() and 0xFF) shl 16) or
            ((body[8].toLong() and 0xFF) shl 8) or
            (body[9].toLong() and 0xFF)
        assertEquals(ts, decoded)
    }
}
