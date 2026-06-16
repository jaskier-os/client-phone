package com.repository.listener.bt

/**
 * Shared codec for the two mouse wire formats used in the app:
 *
 *  - Glasses RFCOMM report: 6 bytes, little-endian, relative deltas.
 *      byte0 = buttons (bit0=L, bit1=R, bit2=M)
 *      byte1..2 = dx int16 LE
 *      byte3..4 = dy int16 LE
 *      byte5 = scroll int8
 *
 *  - Stream report (sent to the PC over the video-stream mouse channel,
 *    parsed by AI/clients/desktop orchestrator_client.py): 7 bytes,
 *    big-endian, relative deltas.
 *      byte0 = 0x02 opcode
 *      byte1..2 = dx int16 BE
 *      byte3..4 = dy int16 BE
 *      byte5 = buttons (bit0=L, bit1=R, bit2=M)
 *      byte6 = scroll int8
 *
 * Button bit layout and relative-delta semantics are identical between the two,
 * so transcoding glasses -> stream is a little-endian -> big-endian re-pack with
 * an opcode prefix.
 */

data class GlassesMouse(val dx: Int, val dy: Int, val buttons: Int, val scroll: Int)

/** Relative stream mouse report opcode the desktop side expects. */
const val STREAM_MOUSE_OPCODE: Int = 0x02

/** Absolute (jump-to-point) stream mouse report opcode. */
const val STREAM_MOUSE_ABS_OPCODE: Int = 0x03

/** Upper bound of the normalized absolute coordinate range (inclusive). */
const val STREAM_MOUSE_ABS_MAX: Int = 65535

/**
 * Builds the 7-byte big-endian relative stream mouse report. dx/dy are clamped to
 * int16, scroll to int8.
 */
fun encodeStreamMouseReport(dx: Int, dy: Int, buttons: Int, scroll: Int): ByteArray {
    val clampedDx = dx.coerceIn(-32768, 32767)
    val clampedDy = dy.coerceIn(-32768, 32767)
    val buf = ByteArray(7)
    buf[0] = STREAM_MOUSE_OPCODE.toByte()
    buf[1] = (clampedDx shr 8).toByte()
    buf[2] = clampedDx.toByte()
    buf[3] = (clampedDy shr 8).toByte()
    buf[4] = clampedDy.toByte()
    buf[5] = buttons.toByte()
    buf[6] = scroll.coerceIn(-128, 127).toByte()
    return buf
}

/**
 * Builds the 8-byte big-endian absolute stream mouse report used by finger
 * jump-and-click. The PC maps (normX, normY) -- each normalized to
 * 0..[STREAM_MOUSE_ABS_MAX] -- onto the captured region of the given [monitor],
 * moves the cursor there, then applies [buttons].
 *
 *   0x03 | monitor(u8) | normX(u16 BE) | normY(u16 BE) | buttons(u8)
 */
fun encodeAbsoluteMouseReport(monitor: Int, normX: Int, normY: Int, buttons: Int): ByteArray {
    val m = monitor.coerceIn(0, 255)
    val nx = normX.coerceIn(0, STREAM_MOUSE_ABS_MAX)
    val ny = normY.coerceIn(0, STREAM_MOUSE_ABS_MAX)
    val buf = ByteArray(8)
    buf[0] = STREAM_MOUSE_ABS_OPCODE.toByte()
    buf[1] = m.toByte()
    buf[2] = (nx shr 8).toByte()
    buf[3] = nx.toByte()
    buf[4] = (ny shr 8).toByte()
    buf[5] = ny.toByte()
    buf[6] = buttons.toByte()
    buf[7] = 0 // reserved
    return buf
}

/**
 * Parses the 6-byte little-endian glasses RFCOMM mouse report into signed deltas.
 * dx/dy are sign-extended from int16; scroll from int8; buttons masked to 0x07.
 */
fun decodeGlassesMouseReport(report: ByteArray): GlassesMouse {
    require(report.size == 6) { "Glasses mouse report must be 6 bytes, got ${report.size}" }
    val buttons = report[0].toInt() and 0x07
    val dx = (((report[2].toInt() and 0xFF) shl 8) or (report[1].toInt() and 0xFF)).toShort().toInt()
    val dy = (((report[4].toInt() and 0xFF) shl 8) or (report[3].toInt() and 0xFF)).toShort().toInt()
    val scroll = report[5].toInt() // Byte is already signed
    return GlassesMouse(dx, dy, buttons, scroll)
}
