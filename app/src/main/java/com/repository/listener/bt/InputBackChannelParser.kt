package com.repository.listener.bt

import java.nio.ByteBuffer

/**
 * Incremental parser for the glasses -> phone back-channel on the input socket.
 *
 * Split out of [InputRfcommClient] so the framing can be tested without a
 * Bluetooth socket. It had none: the shipped client read into a 64-byte scratch
 * buffer and discarded it, so the sink state the glasses publish could never be
 * read and the watch showed "Glasses screen not active" forever even while
 * scrolling worked. A wrong endianness or an off-by-one here reproduces exactly
 * that symptom, silently, which is why it needs to be reachable from a test.
 *
 * The wire format is the one the glasses' `MessageRelay.buildFrame` writes:
 *   [4B payload length BE][1B channel name length][channel UTF-8]
 *   [1B arg count]{[4B arg length BE][arg UTF-8]}*
 *
 * Not thread-safe: it is fed from the single socket read loop.
 */
class InputBackChannelParser(
    /**
     * Accumulation ceiling. The largest legitimate frame here is a few dozen
     * bytes; this is slack, not a real size. A peer controls the byte rate, so the
     * buffer must be bounded by something other than the peer's claims.
     */
    private val maxBufferBytes: Int = DEFAULT_MAX_BUFFER_BYTES,
    private val log: (String) -> Unit = {},
    private val onFrame: (channel: String, args: List<String>) -> Unit,
) {

    private var buffer = ByteArray(0)

    /**
     * Whether the reader is aligned to a frame boundary.
     *
     * After a buffer reset mid-frame the next bytes are the tail of a frame, so
     * every subsequent length read is garbage and the stream never recovers on its
     * own. Tracking it means the condition is reported rather than presenting as a
     * back-channel that silently stops until the socket happens to reconnect.
     */
    var desynchronized: Boolean = false
        private set

    /** Feeds [count] bytes from [chunk] and dispatches every whole frame in them. */
    fun onBytes(chunk: ByteArray, count: Int) {
        if (count <= 0) return
        if (buffer.size + count > maxBufferBytes) {
            // Discarding a partial frame leaves the reader mid-frame; say so rather
            // than resuming on misaligned bytes and pretending nothing happened.
            log("input rx buffer overflow; desynchronized until reconnect")
            buffer = ByteArray(0)
            desynchronized = true
            return
        }
        buffer = buffer + chunk.copyOfRange(0, count)
        if (desynchronized) return
        drain()
    }

    /** Called on a fresh socket: the next byte is a frame boundary again. */
    fun reset() {
        buffer = ByteArray(0)
        desynchronized = false
    }

    private fun drain() {
        var offset = 0
        while (buffer.size - offset >= LENGTH_PREFIX_BYTES) {
            val len = ByteBuffer.wrap(buffer, offset, LENGTH_PREFIX_BYTES).int
            if (len < 0 || len > maxBufferBytes) {
                log("input rx invalid frame length=$len; desynchronized until reconnect")
                buffer = ByteArray(0)
                desynchronized = true
                return
            }
            if (buffer.size - offset - LENGTH_PREFIX_BYTES < len) break
            try {
                val (channel, args) = parseFrame(buffer, offset + LENGTH_PREFIX_BYTES, len)
                onFrame(channel, args)
            } catch (e: Exception) {
                // A malformed frame body is survivable: its length was valid, so the
                // next boundary is still known. Alignment is intact; skip it.
                log("input rx frame parse failed: ${e.message}")
            }
            offset += LENGTH_PREFIX_BYTES + len
        }
        if (offset > 0) buffer = buffer.copyOfRange(offset, buffer.size)
    }

    private fun parseFrame(buf: ByteArray, start: Int, length: Int): Pair<String, List<String>> {
        var p = start
        val end = start + length
        val chanLen = buf[p].toInt() and 0xFF; p++
        require(p + chanLen <= end) { "channel bytes overflow" }
        val channel = String(buf, p, chanLen, Charsets.UTF_8); p += chanLen
        require(p < end) { "missing arg count" }
        val argCount = buf[p].toInt() and 0xFF; p++
        val args = ArrayList<String>(argCount)
        repeat(argCount) {
            require(p + 4 <= end) { "arg length overflow" }
            val argLen = ByteBuffer.wrap(buf, p, 4).int; p += 4
            require(argLen >= 0 && p + argLen <= end) { "arg bytes overflow: len=$argLen" }
            args.add(String(buf, p, argLen, Charsets.UTF_8)); p += argLen
        }
        return channel to args
    }

    companion object {
        const val DEFAULT_MAX_BUFFER_BYTES = 4 * 1024
        private const val LENGTH_PREFIX_BYTES = 4
    }
}
