package com.repository.listener.audio

import android.media.MediaCodec
import android.media.MediaFormat
import com.repository.listener.util.LogCollector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus decoder using Android MediaCodec.
 * Decodes length-prefixed Opus frames from RFCOMM stream back to PCM16 ShortArray.
 */
class OpusStreamDecoder(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1
) {
    companion object {
        private const val TAG = "OpusStreamDecoder"
        private const val MIME = "audio/opus"
        private const val FRAME_MS = 20
    }

    private var codec: MediaCodec? = null
    private val frameSamples = sampleRate * FRAME_MS / 1000 // 320 at 16kHz

    fun initialize(): Boolean {
        return try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels)
            format.setByteBuffer("csd-0", buildOpusHead())
            // CSD-1: pre-skip (3840 samples at 48kHz = 80ms, standard)
            val csd1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            csd1.putLong(0)
            csd1.flip()
            format.setByteBuffer("csd-1", csd1)
            // CSD-2: seek pre-roll (80ms = 3840 samples at 48kHz)
            val csd2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            csd2.putLong(3840)
            csd2.flip()
            format.setByteBuffer("csd-2", csd2)

            codec = MediaCodec.createDecoderByType(MIME)
            codec!!.configure(format, null, null, 0)
            codec!!.start()
            LogCollector.i(TAG, "OpusStreamDecoder initialized (${sampleRate}Hz)")
            true
        } catch (e: Exception) {
            LogCollector.e(TAG, "OpusStreamDecoder init failed: ${e.message}")
            codec = null
            false
        }
    }

    /**
     * Decode a single Opus frame to PCM samples.
     */
    fun decode(opusFrame: ByteArray): ShortArray? {
        val dec = codec ?: return null

        // Feed Opus frame
        val inIdx = dec.dequeueInputBuffer(5000)
        if (inIdx < 0) return null
        val inBuf = dec.getInputBuffer(inIdx) ?: return null
        inBuf.clear()
        inBuf.put(opusFrame)
        dec.queueInputBuffer(inIdx, 0, opusFrame.size, 0, 0)

        // Get decoded PCM
        val bufInfo = MediaCodec.BufferInfo()
        val outIdx = dec.dequeueOutputBuffer(bufInfo, 5000)
        if (outIdx < 0) return null

        val outBuf = dec.getOutputBuffer(outIdx) ?: run {
            dec.releaseOutputBuffer(outIdx, false)
            return null
        }

        val pcmBytes = ByteArray(bufInfo.size)
        outBuf.position(bufInfo.offset)
        outBuf.get(pcmBytes)
        dec.releaseOutputBuffer(outIdx, false)

        // Convert bytes to ShortArray
        val decoded = ShortArray(pcmBytes.size / 2)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(decoded)

        // MediaCodec Opus always decodes to 48kHz — downsample to target rate
        if (decoded.size > frameSamples * 2) {
            val ratio = 48000 / sampleRate // 3 for 16kHz
            val resampled = ShortArray(decoded.size / ratio)
            for (i in resampled.indices) {
                resampled[i] = decoded[i * ratio]
            }
            return resampled
        }
        return decoded
    }

    /**
     * Release and re-create the MediaCodec so a wedged/desynced decoder can
     * recover its internal state without tearing down the RFCOMM socket.
     */
    fun reinitialize(): Boolean {
        release()
        return initialize()
    }

    fun release() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }

    private fun buildOpusHead(): ByteBuffer {
        val buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusHead".toByteArray())
        buf.put(1) // version
        buf.put(channels.toByte())
        buf.putShort(0) // pre-skip
        buf.putInt(sampleRate)
        buf.putShort(0) // output gain
        buf.put(0) // channel mapping family
        buf.flip()
        return buf
    }
}
