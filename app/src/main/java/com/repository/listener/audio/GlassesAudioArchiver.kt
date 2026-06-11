package com.repository.listener.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.repository.listener.util.LogCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GlassesAudioArchiver(private val context: Context) {
    companion object {
        private const val TAG = "GlassesAudioArchiver"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BIT_RATE = 32000 // 32kbps AAC
        private const val FILE_DURATION_MS = 3_600_000L // 1 hour
        private const val SILENCE_TIMEOUT_MS = 5_000L // 5s no audio -> finalize
        private const val AMPLITUDE_WINDOW_MS = 100L // 100ms per amplitude sample
        private const val AMPLITUDE_WINDOW_SAMPLES = (SAMPLE_RATE * AMPLITUDE_WINDOW_MS / 1000).toInt() // 1600
        private const val VAD_THRESHOLD = 0.5f
        private const val VAD_SPEECH_PAD_MS = 300L // pad voice segments by 300ms
    }

    private val recording = AtomicBoolean(false)
    private val lastChunkTime = AtomicLong(0)
    private val audioQueue = ConcurrentLinkedQueue<ShortArray>()
    private val lock = Object()
    private val needsFinalize = AtomicBoolean(false)

    // Live audio monitor callback (set by UI for real-time playback)
    @Volatile var monitorCallback: ((ShortArray) -> Unit)? = null

    // Finalization callback (set by UI to know when file is ready for playback)
    @Volatile var onFinalized: ((File?) -> Unit)? = null

    // Encoder state
    private var encoder: MediaCodec? = null
    private var audioOutputStream: BufferedOutputStream? = null
    private var encoderThread: Thread? = null
    private var currentFile: File? = null
    private var fileStartTime = 0L
    private val totalSamplesWritten = AtomicLong(0)

    // VAD state
    private var vadEngine: VadEngine? = null
    private var inSpeech = false
    private var speechStartMs = 0L
    private val voiceSegments = mutableListOf<VoiceSegment>()

    // Amplitude state
    private var amplitudeFile: OutputStream? = null
    private var maxAmplitude: Int = 0

    // In-memory amplitude peaks for live UI access
    private val amplitudePeaks = mutableListOf<Int>()

    // Silence detection
    private var silenceCheckThread: Thread? = null

    private val recordingsDir: File
        get() = File(context.filesDir, "audio_recordings").also { it.mkdirs() }

    data class VoiceSegment(
        val startMs: Long,
        var endMs: Long,
        var transcription: String = ""
    )

    fun start() {
        if (recording.getAndSet(true)) return
        LogCollector.i(TAG, "Starting audio archiver")

        // Initialize VAD
        try {
            vadEngine = VadEngine(context).also { it.initialize() }
            LogCollector.i(TAG, "VAD engine initialized for archiver")
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to init VAD: ${e.message}")
        }

        // Don't create file yet -- wait for first audio chunk
        startEncoderThread()
        startSilenceCheck()
    }

    fun stop() {
        if (!recording.getAndSet(false)) return
        LogCollector.i(TAG, "Stopping audio archiver")

        silenceCheckThread?.interrupt()
        silenceCheckThread = null

        // Wait for encoder thread to finish draining and finalize
        val thread = encoderThread
        if (thread != null) {
            thread.join(10_000)
            if (thread.isAlive) {
                thread.interrupt()
                thread.join(2_000)
            }
        }
        encoderThread = null

        // Idempotent -- if encoder thread already finalized, this is a no-op
        finalizeCurrentFile()

        vadEngine?.release()
        vadEngine = null
    }

    fun onAudioChunk(samples: ShortArray) {
        if (!recording.get()) return
        lastChunkTime.set(System.currentTimeMillis())
        audioQueue.offer(samples.copyOf())
        monitorCallback?.invoke(samples)
    }

    fun isRecording(): Boolean = recording.get()

    fun getCurrentFileName(): String? = currentFile?.name

    fun getCurrentAmplitudeData(): ShortArray {
        synchronized(amplitudePeaks) {
            return ShortArray(amplitudePeaks.size) { amplitudePeaks[it].coerceIn(0, 32767).toShort() }
        }
    }

    fun getCurrentVoiceSegments(): List<VoiceSegment> {
        synchronized(lock) {
            return voiceSegments.toList()
        }
    }

    fun getCurrentDurationMs(): Long {
        return (totalSamplesWritten.get() * 1000) / SAMPLE_RATE
    }

    fun getFileStartTime(): Long = fileStartTime

    fun isAudioFlowing(): Boolean {
        val lastTime = lastChunkTime.get()
        return lastTime > 0 && System.currentTimeMillis() - lastTime < SILENCE_TIMEOUT_MS
    }

    private fun startNewFile() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            // Hour-aligned filename: all recordings within the same hour go to the same file
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val hourStart = cal.timeInMillis
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH", Locale.US).format(Date(hourStart))

            val audioFile = File(recordingsDir, "$timestamp.aac")
            val ampFile = File(recordingsDir, "$timestamp.amp")
            val jsonFile = File(recordingsDir, "$timestamp.json")
            val resuming = audioFile.exists() && audioFile.length() > 0

            maxAmplitude = 0
            inSpeech = false

            if (resuming && jsonFile.exists()) {
                // Resume existing file -- load previous state
                fileStartTime = hourStart
                try {
                    val json = JSONObject(jsonFile.readText())
                    totalSamplesWritten.set(json.optLong("totalSamples", 0))
                    voiceSegments.clear()
                    val segs = json.optJSONArray("voiceSegments")
                    if (segs != null) {
                        for (i in 0 until segs.length()) {
                            val s = segs.getJSONObject(i)
                            voiceSegments.add(VoiceSegment(s.getLong("startMs"), s.getLong("endMs"), s.optString("transcription", "")))
                        }
                    }
                    // Reload amplitude peaks for live UI
                    synchronized(amplitudePeaks) {
                        amplitudePeaks.clear()
                        if (ampFile.exists() && ampFile.length() > 0) {
                            val bytes = ampFile.readBytes()
                            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            while (buf.remaining() >= 2) {
                                amplitudePeaks.add(buf.getShort().toInt())
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to load previous state: ${e.message}")
                    totalSamplesWritten.set(0)
                    voiceSegments.clear()
                    synchronized(amplitudePeaks) { amplitudePeaks.clear() }
                }
            } else {
                // Brand new file
                fileStartTime = hourStart
                totalSamplesWritten.set(0)
                voiceSegments.clear()
                synchronized(amplitudePeaks) { amplitudePeaks.clear() }
            }

            currentFile = audioFile

            try {
                // Set up AAC encoder
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AMPLITUDE_WINDOW_SAMPLES * 2)
                }

                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }

                // Open in append mode if resuming
                audioOutputStream = BufferedOutputStream(FileOutputStream(audioFile, resuming))
                amplitudeFile = BufferedOutputStream(FileOutputStream(ampFile, resuming))

                // Fill silence gap
                val expectedSamplesNow = ((now - hourStart) * SAMPLE_RATE) / 1000
                val gapSamples = expectedSamplesNow - totalSamplesWritten.get()
                if (gapSamples > AMPLITUDE_WINDOW_SAMPLES) {
                    fillSilenceGap(gapSamples)
                }

                if (resuming) {
                    LogCollector.i(TAG, "Resumed recording: ${audioFile.name}, gap=${gapSamples / SAMPLE_RATE}s")
                } else {
                    LogCollector.i(TAG, "New recording file: ${audioFile.name}")
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to start recording file: ${e.message}")
                encoder = null
                audioOutputStream = null
            }
        }
    }

    private fun fillSilenceGap(gapSamples: Long) {
        val bufferInfo = MediaCodec.BufferInfo()
        val silentBlock = ShortArray(AMPLITUDE_WINDOW_SAMPLES) // all zeros
        val enc = encoder ?: return
        var remaining = gapSamples
        while (remaining >= AMPLITUDE_WINDOW_SAMPLES) {
            // Block until encoder accepts the input (drain output to free input buffers)
            var queued = false
            for (attempt in 0 until 100) {
                drainEncoder(bufferInfo, false)
                val inputIndex = enc.dequeueInputBuffer(10_000) // 10ms wait
                if (inputIndex >= 0) {
                    val inputBuffer = enc.getInputBuffer(inputIndex) ?: break
                    inputBuffer.clear()
                    val byteBuffer = ByteBuffer.allocate(AMPLITUDE_WINDOW_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until AMPLITUDE_WINDOW_SAMPLES) byteBuffer.putShort(0)
                    byteBuffer.flip()
                    inputBuffer.put(byteBuffer)
                    val pts = (totalSamplesWritten.get() * 1_000_000L) / SAMPLE_RATE
                    enc.queueInputBuffer(inputIndex, 0, AMPLITUDE_WINDOW_SAMPLES * 2, pts, 0)
                    drainEncoder(bufferInfo, false)
                    queued = true
                    break
                }
            }
            if (queued) {
                writeAmplitudePeak(0)
                totalSamplesWritten.addAndGet(AMPLITUDE_WINDOW_SAMPLES.toLong())
            } else {
                LogCollector.e(TAG, "fillSilenceGap: encoder refused input after 100 attempts, stopping gap fill")
                break
            }
            remaining -= AMPLITUDE_WINDOW_SAMPLES
        }
        try {
            audioOutputStream?.flush()
            amplitudeFile?.flush()
        } catch (_: Exception) {}
        LogCollector.i(TAG, "Filled ${(gapSamples - remaining) / SAMPLE_RATE}s of silence")
    }

    private fun startEncoderThread() {
        encoderThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffer = ShortArray(AMPLITUDE_WINDOW_SAMPLES)
            var pcmBufferPos = 0

            while (recording.get() || audioQueue.isNotEmpty()) {
                // Check if silence thread requested finalize
                if (needsFinalize.getAndSet(false)) {
                    // Flush remaining PCM before finalize
                    if (pcmBufferPos > 0) {
                        for (i in pcmBufferPos until AMPLITUDE_WINDOW_SAMPLES) pcmBuffer[i] = 0
                        writeAmplitudePeak(maxAmplitude)
                        encodePcm(pcmBuffer, pcmBufferPos, bufferInfo)
                        totalSamplesWritten.addAndGet(pcmBufferPos.toLong())
                        pcmBufferPos = 0
                        maxAmplitude = 0
                    }
                    // Drain encoder with EOS so the file is complete
                    drainEncoder(bufferInfo, true)
                    finalizeCurrentFile()
                    // Wait for audio to resume
                    try {
                        while (recording.get() && System.currentTimeMillis() - lastChunkTime.get() > SILENCE_TIMEOUT_MS) {
                            Thread.sleep(500)
                        }
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    if (recording.get()) {
                        LogCollector.i(TAG, "Audio resumed, starting new file")
                        startNewFile()
                        pcmBufferPos = 0
                    }
                    continue
                }

                val chunk = audioQueue.poll()
                if (chunk == null) {
                    try {
                        Thread.sleep(10)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }

                // Create file on first audio chunk (not on start)
                if (currentFile == null) {
                    startNewFile()
                }

                // Process VAD with current position before incrementing
                val currentMs = (totalSamplesWritten.get() * 1000) / SAMPLE_RATE
                processVad(chunk, currentMs)

                // Accumulate for amplitude + encoding
                var chunkOffset = 0
                while (chunkOffset < chunk.size) {
                    val toCopy = minOf(chunk.size - chunkOffset, AMPLITUDE_WINDOW_SAMPLES - pcmBufferPos)
                    System.arraycopy(chunk, chunkOffset, pcmBuffer, pcmBufferPos, toCopy)
                    chunkOffset += toCopy
                    pcmBufferPos += toCopy

                    // Track max amplitude in window
                    for (i in pcmBufferPos - toCopy until pcmBufferPos) {
                        val abs = kotlin.math.abs(pcmBuffer[i].toInt()).coerceAtMost(32767)
                        if (abs > maxAmplitude) maxAmplitude = abs
                    }

                    if (pcmBufferPos >= AMPLITUDE_WINDOW_SAMPLES) {
                        // Write amplitude peak
                        writeAmplitudePeak(maxAmplitude)
                        maxAmplitude = 0

                        // Encode PCM to AAC
                        encodePcm(pcmBuffer, pcmBufferPos, bufferInfo)

                        totalSamplesWritten.addAndGet(pcmBufferPos.toLong())
                        pcmBufferPos = 0

                        // Periodic flush of amplitude file (every ~30s = 300 peaks)
                        if (totalSamplesWritten.get() % (AMPLITUDE_WINDOW_SAMPLES * 300L) < AMPLITUDE_WINDOW_SAMPLES) {
                            try { amplitudeFile?.flush() } catch (_: Exception) {}
                            try { audioOutputStream?.flush() } catch (_: Exception) {}
                        }
                        // Periodic metadata JSON write (every ~30s) to survive force-stop
                        if (totalSamplesWritten.get() % (AMPLITUDE_WINDOW_SAMPLES * 300L) < AMPLITUDE_WINDOW_SAMPLES) {
                            writeMetadataJson()
                        }

                        // Check file rotation (hour boundary crossed)
                        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                        val fileHour = Calendar.getInstance().apply { timeInMillis = fileStartTime }.get(Calendar.HOUR_OF_DAY)
                        if (currentHour != fileHour) {
                            drainEncoder(bufferInfo, true)
                            finalizeCurrentFile()
                            if (recording.get()) startNewFile()
                        }
                    }
                }
            }

            // Flush remaining PCM
            if (pcmBufferPos > 0) {
                // Zero-pad to full window
                for (i in pcmBufferPos until AMPLITUDE_WINDOW_SAMPLES) pcmBuffer[i] = 0
                writeAmplitudePeak(maxAmplitude)
                encodePcm(pcmBuffer, pcmBufferPos, bufferInfo)
                totalSamplesWritten.addAndGet(pcmBufferPos.toLong())
            }

            // Signal end of stream
            drainEncoder(bufferInfo, true)

            // Finalize before thread exits
            finalizeCurrentFile()
        }, "AudioArchiver-Encoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun encodePcm(samples: ShortArray, count: Int, bufferInfo: MediaCodec.BufferInfo) {
        val enc = encoder ?: return
        if (!recording.get()) return

        try {
            val inputIndex = enc.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer = enc.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                val byteBuffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) byteBuffer.putShort(samples[i])
                byteBuffer.flip()
                inputBuffer.put(byteBuffer)
                val pts = (totalSamplesWritten.get() * 1_000_000L) / SAMPLE_RATE
                enc.queueInputBuffer(inputIndex, 0, count * 2, pts, 0)
            }
        } catch (e: IllegalStateException) {
            LogCollector.w(TAG, "encodePcm: codec in bad state: ${e.message}")
            return
        }

        drainEncoder(bufferInfo, false)
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        val enc = encoder ?: return
        val out = audioOutputStream ?: return
        var eosRetries = 0

        try {
            if (endOfStream) {
                val inputIndex = enc.dequeueInputBuffer(5000)
                if (inputIndex >= 0) {
                    enc.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
        } catch (e: IllegalStateException) {
            LogCollector.w(TAG, "drainEncoder: EOS signal failed: ${e.message}")
            return
        }

        while (true) {
            val outputIndex = try {
                enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) 5000 else 0)
            } catch (e: IllegalStateException) {
                LogCollector.w(TAG, "drainEncoder: dequeueOutput failed: ${e.message}")
                return
            }
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // No action needed for ADTS (no muxer track to add)
                }
                outputIndex >= 0 -> {
                    val outputBuffer = enc.getOutputBuffer(outputIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Skip codec config buffer (CSD) -- ADTS headers replace it
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        // Write ADTS header + raw AAC frame
                        val frameData = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.get(frameData)
                        val adtsHeader = createAdtsHeader(bufferInfo.size)
                        try {
                            out.write(adtsHeader)
                            out.write(frameData)
                        } catch (e: Exception) {
                            LogCollector.e(TAG, "Failed to write ADTS frame: ${e.message}")
                        }
                    }
                    enc.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (endOfStream && eosRetries++ < 10) continue else break
            }
        }
    }

    private fun createAdtsHeader(frameLength: Int): ByteArray {
        val header = ByteArray(7)
        val packetLen = frameLength + 7
        // AAC-LC profile, 16kHz sample rate (index 8), mono channel
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte() // MPEG-4, Layer 0, no CRC
        header[2] = 0x60.toByte() // AAC-LC (profile 1), freq index 8 (16kHz), mono high bit
        header[3] = (0x40 or ((packetLen shr 11) and 0x03)).toByte()
        header[4] = ((packetLen shr 3) and 0xFF).toByte()
        header[5] = (((packetLen and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte() // VBR, 0 raw data blocks
        return header
    }

    // VAD debounce state
    private var vadSpeechFrames = 0
    private var vadSilenceFrames = 0
    private val VAD_SPEECH_CONFIRM_FRAMES = 5   // ~320ms of continuous speech to trigger start
    private val VAD_SILENCE_CONFIRM_FRAMES = 12  // ~770ms of continuous silence to trigger end
    private val MIN_SEGMENT_DURATION_MS = 2000L  // minimum 2s segment

    private fun processVad(samples: ShortArray, currentMs: Long) {
        val vad = vadEngine ?: return
        val floats = FloatArray(samples.size) { samples[it].toFloat() / 32768f }
        val prob = vad.processChunk(floats)
        if (prob <= 0f) return

        if (prob >= VAD_THRESHOLD) {
            vadSpeechFrames++
            vadSilenceFrames = 0
        } else {
            vadSilenceFrames++
            vadSpeechFrames = 0
        }

        if (!inSpeech && vadSpeechFrames >= VAD_SPEECH_CONFIRM_FRAMES) {
            inSpeech = true
            speechStartMs = maxOf(0, currentMs - VAD_SPEECH_PAD_MS)
            LogCollector.i(TAG, "VAD: speech start at ${speechStartMs}ms")
        } else if (inSpeech && vadSilenceFrames >= VAD_SILENCE_CONFIRM_FRAMES) {
            inSpeech = false
            val endMs = currentMs + VAD_SPEECH_PAD_MS
            val segDuration = endMs - speechStartMs

            if (segDuration >= MIN_SEGMENT_DURATION_MS) {
                val segment = VoiceSegment(speechStartMs, endMs, "")
                synchronized(lock) { voiceSegments.add(segment) }
                LogCollector.i(TAG, "VAD: speech segment at ${speechStartMs}-${endMs}ms (${segDuration}ms)")
            } else {
                LogCollector.i(TAG, "VAD: discarded short segment (${segDuration}ms)")
            }
        }
    }

    private fun writeAmplitudePeak(peak: Int) {
        synchronized(amplitudePeaks) { amplitudePeaks.add(peak) }
        val amp = amplitudeFile ?: return
        try {
            val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(peak.toShort())
            amp.write(buf.array())
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to write amplitude: ${e.message}")
        }
    }

    private fun writeMetadataJson() {
        val file = currentFile ?: return
        val durationMs = (totalSamplesWritten.get() * 1000) / SAMPLE_RATE
        if (durationMs < 1000) return

        try {
            val metadata = JSONObject().apply {
                put("startTime", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(fileStartTime)))
                put("endTime", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(fileStartTime + durationMs)))
                put("durationMs", durationMs)
                put("sampleRate", SAMPLE_RATE)
                put("bitrateKbps", BIT_RATE / 1000)
                put("compressionTier", "normal")
                put("totalSamples", totalSamplesWritten.get())
                put("voiceSegments", JSONArray().apply {
                    synchronized(lock) {
                        voiceSegments.forEach { seg ->
                            put(JSONObject().apply {
                                put("startMs", seg.startMs)
                                put("endMs", seg.endMs)
                                put("transcription", seg.transcription)
                            })
                        }
                    }
                })
            }

            val jsonFile = File(file.absolutePath.replace(".aac", ".json"))
            jsonFile.writeText(metadata.toString(2))
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to write metadata: ${e.message}")
        }
    }

    private fun finalizeCurrentFile() {
        synchronized(lock) {
            val file = currentFile ?: return

            // Close speech if still in progress (save segment without transcription)
            if (inSpeech) {
                inSpeech = false
                val endMs = (totalSamplesWritten.get() * 1000) / SAMPLE_RATE
                voiceSegments.add(VoiceSegment(speechStartMs, endMs, ""))
            }

            // Stop encoder
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {
                LogCollector.e(TAG, "Encoder release error: ${e.message}")
            }
            encoder = null

            // Close audio output stream (ADTS file is always valid, no finalization needed)
            try {
                audioOutputStream?.flush()
                audioOutputStream?.close()
            } catch (e: Exception) {
                LogCollector.e(TAG, "Audio stream close error: ${e.message}")
            }
            audioOutputStream = null

            // Close amplitude file
            try {
                amplitudeFile?.flush()
                amplitudeFile?.close()
            } catch (e: Exception) {
                LogCollector.e(TAG, "Amplitude file close error: ${e.message}")
            }
            amplitudeFile = null

            // Delete too-short recordings
            val durationMs = (totalSamplesWritten.get() * 1000) / SAMPLE_RATE
            if (durationMs < 1000) {
                file.delete()
                File(file.absolutePath.replace(".aac", ".amp")).delete()
                LogCollector.i(TAG, "Deleted too-short recording (${durationMs}ms)")
                currentFile = null
                return
            }

            writeMetadataJson()

            LogCollector.i(TAG, "Finalized recording: ${file.name}, duration=${(totalSamplesWritten.get() * 1000) / SAMPLE_RATE}ms, voiceSegments=${voiceSegments.size}")

            currentFile = null
        }
        // Notify UI outside synchronized block to avoid deadlock
        onFinalized?.invoke(null)
    }

    private fun startSilenceCheck() {
        silenceCheckThread = Thread({
            while (recording.get()) {
                try {
                    Thread.sleep(1000)
                    val lastTime = lastChunkTime.get()
                    if (lastTime > 0 && System.currentTimeMillis() - lastTime > SILENCE_TIMEOUT_MS) {
                        if (!needsFinalize.get()) {
                            LogCollector.i(TAG, "No audio for ${SILENCE_TIMEOUT_MS}ms, requesting finalize")
                            needsFinalize.set(true)
                        }
                        // Wait for audio to resume (don't spam the flag)
                        while (recording.get() && System.currentTimeMillis() - lastChunkTime.get() > SILENCE_TIMEOUT_MS) {
                            Thread.sleep(500)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "AudioArchiver-SilenceCheck").apply {
            isDaemon = true
            start()
        }
    }
}
