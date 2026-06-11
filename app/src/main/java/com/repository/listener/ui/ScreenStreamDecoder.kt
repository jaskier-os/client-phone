package com.repository.listener.ui

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.repository.listener.util.LogCollector
import java.nio.ByteBuffer

class ScreenStreamDecoder(
    private var width: Int,
    private var height: Int
) {

    companion object {
        private const val TAG = "ScreenStreamDecoder"
        private const val MIME_TYPE = "video/avc"
        private const val HEADER_SIZE = 10
        private const val TIMEOUT_US = 10_000L
    }

    private val lock = Object()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null

    fun start(surface: Surface) {
        synchronized(lock) {
            this.surface = surface
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            codec = MediaCodec.createDecoderByType(MIME_TYPE).also { mc ->
                mc.configure(format, surface, null, 0)
                mc.start()
            }
        }
        LogCollector.i(TAG, "Decoder started (${width}x${height})")
    }

    fun feedFrame(data: ByteArray) {
        synchronized(lock) {
            val mc = codec ?: return
            if (data.size <= HEADER_SIZE) return

            // Parse 10-byte header:
            // [0] version, [1] flags (bit0=keyframe, bit1=config), [2..5] streamId, [6..9] timestamp
            val flags = data[1].toInt() and 0xFF
            val isConfig = (flags and 0x02) != 0

            val nalData = ByteArray(data.size - HEADER_SIZE)
            System.arraycopy(data, HEADER_SIZE, nalData, 0, nalData.size)

            if (isConfig) {
                handleConfigFrame(mc, nalData)
                return
            }

            val inputIndex = mc.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(nalData)
                mc.queueInputBuffer(inputIndex, 0, nalData.size, 0, 0)
            }

            drainOutput(mc)
        }
    }

    private fun handleConfigFrame(mc: MediaCodec, nalData: ByteArray) {
        val inputIndex = mc.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(nalData)
            mc.queueInputBuffer(inputIndex, 0, nalData.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }
        LogCollector.i(TAG, "Config frame fed (${nalData.size} bytes)")
    }

    private fun drainOutput(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = mc.dequeueOutputBuffer(info, 0)
            when {
                outputIndex >= 0 -> {
                    mc.releaseOutputBuffer(outputIndex, true)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = mc.outputFormat
                    LogCollector.i(TAG, "Output format changed: $newFormat")
                }
                else -> break
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            codec?.let { mc ->
                try { mc.stop() } catch (_: Exception) {}
                try { mc.release() } catch (_: Exception) {}
            }
            codec = null
            surface = null
        }
        LogCollector.i(TAG, "Decoder stopped")
    }
}
