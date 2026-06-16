package com.repository.listener.bt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamMouseCodecTest {

    /** Build a 6-byte glasses report (little-endian) the way the glasses app does. */
    private fun glassesReport(buttons: Int, dx: Int, dy: Int, scroll: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        (dx and 0xFF).toByte(),
        ((dx shr 8) and 0xFF).toByte(),
        (dy and 0xFF).toByte(),
        ((dy shr 8) and 0xFF).toByte(),
        scroll.toByte()
    )

    @Test
    fun decode_positiveDeltas() {
        val m = decodeGlassesMouseReport(glassesReport(buttons = 0x01, dx = 300, dy = 12, scroll = 3))
        assertEquals(300, m.dx)
        assertEquals(12, m.dy)
        assertEquals(0x01, m.buttons)
        assertEquals(3, m.scroll)
    }

    @Test
    fun decode_negativeDeltasSignExtend() {
        val m = decodeGlassesMouseReport(glassesReport(buttons = 0x02, dx = -1, dy = -200, scroll = -5))
        assertEquals(-1, m.dx)
        assertEquals(-200, m.dy)
        assertEquals(0x02, m.buttons)
        assertEquals(-5, m.scroll)
    }

    @Test
    fun decode_buttonMaskStripsHighBits() {
        // Only the low 3 bits are buttons.
        val m = decodeGlassesMouseReport(glassesReport(buttons = 0xFF, dx = 0, dy = 0, scroll = 0))
        assertEquals(0x07, m.buttons)
    }

    @Test
    fun encode_bigEndianFrameWithOpcode() {
        // dx = 300 (0x012C), dy = -1 (0xFFFF), middle button, scroll = -5
        val buf = encodeStreamMouseReport(dx = 300, dy = -1, buttons = 0x04, scroll = -5)
        assertArrayEquals(
            byteArrayOf(
                0x02,
                0x01, 0x2C,                       // dx BE
                0xFF.toByte(), 0xFF.toByte(),     // dy BE
                0x04,
                0xFB.toByte()                     // scroll -5
            ),
            buf
        )
    }

    @Test
    fun encode_clampsRanges() {
        val buf = encodeStreamMouseReport(dx = 99999, dy = -99999, buttons = 0x01, scroll = 9999)
        // dx clamped to 32767 (0x7FFF), dy to -32768 (0x8000), scroll to 127 (0x7F)
        assertArrayEquals(
            byteArrayOf(
                0x02,
                0x7F, 0xFF.toByte(),
                0x80.toByte(), 0x00,
                0x01,
                0x7F
            ),
            buf
        )
    }

    @Test
    fun roundTrip_glassesToStreamFrame() {
        // Full transcode the production routing path performs.
        val report = glassesReport(buttons = 0x05, dx = -1234, dy = 567, scroll = 2)
        val m = decodeGlassesMouseReport(report)
        val frame = encodeStreamMouseReport(m.dx, m.dy, m.buttons, m.scroll)

        // -1234 = 0xFB2E, 567 = 0x0237
        assertArrayEquals(
            byteArrayOf(
                0x02,
                0xFB.toByte(), 0x2E,
                0x02, 0x37,
                0x05,
                0x02
            ),
            frame
        )
    }

    @Test
    fun encodeAbsolute_bigEndianFrame() {
        // monitor 1, normX = 0x1234, normY = 0xABCD, left button
        val buf = encodeAbsoluteMouseReport(monitor = 1, normX = 0x1234, normY = 0xABCD, buttons = 0x01)
        assertArrayEquals(
            byteArrayOf(
                0x03,
                0x01,                              // monitor
                0x12, 0x34,                        // normX BE
                0xAB.toByte(), 0xCD.toByte(),      // normY BE
                0x01,                              // buttons
                0x00                               // reserved
            ),
            buf
        )
    }

    @Test
    fun encodeAbsolute_clampsRanges() {
        // normX over max -> 65535 (0xFFFF), normY negative -> 0, monitor over u8 -> 255
        val buf = encodeAbsoluteMouseReport(monitor = 999, normX = 70000, normY = -5, buttons = 0x02)
        assertArrayEquals(
            byteArrayOf(
                0x03,
                0xFF.toByte(),                     // monitor clamped to 255
                0xFF.toByte(), 0xFF.toByte(),      // normX clamped to 65535
                0x00, 0x00,                        // normY clamped to 0
                0x02,
                0x00
            ),
            buf
        )
    }

    @Test
    fun encodeAbsolute_boundsExact() {
        // Corner: top-left of monitor 0
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            encodeAbsoluteMouseReport(monitor = 0, normX = 0, normY = 0, buttons = 0)
        )
        // Corner: bottom-right (max)
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00),
            encodeAbsoluteMouseReport(monitor = 0, normX = STREAM_MOUSE_ABS_MAX, normY = STREAM_MOUSE_ABS_MAX, buttons = 0)
        )
    }
}
