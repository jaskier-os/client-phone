package com.repository.listener.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.work.*
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class RecordingMetadata(
    val startTime: String,
    val endTime: String,
    val durationMs: Long,
    val sampleRate: Int,
    val bitrateKbps: Int,
    val compressionTier: String,
    val voiceSegments: List<VoiceSegmentData>,
    val pinned: Boolean = false,
    val name: String = ""
)

data class VoiceSegmentData(
    val startMs: Long,
    val endMs: Long,
    val transcription: String
)

data class AudioRecording(
    val audioFile: File,
    val metadataFile: File,
    val amplitudeFile: File,
    val metadata: RecordingMetadata
)

class AudioFileManager(private val context: Context) {
    companion object {
        private const val TAG = "AudioFileManager"
        private const val NORMAL_RETENTION_DAYS = 30
        private const val TIGHT_RETENTION_DAYS = 180
        private const val TIGHT_BIT_RATE = 12000 // 12kbps
    }

    private val recordingsDir: File
        get() = File(context.filesDir, "audio_recordings")
    private val failedAmplitudeFiles = mutableSetOf<String>()
    private val ingestLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun listRecordings(): List<AudioRecording> {
        val dir = recordingsDir
        if (!dir.exists()) return emptyList()

        return dir.listFiles { f -> f.extension == "aac" || f.extension == "m4a" }
            ?.mapNotNull { audioFile ->
                val ext = audioFile.extension
                val jsonFile = File(audioFile.absolutePath.replace(".$ext", ".json"))
                val ampFile = File(audioFile.absolutePath.replace(".$ext", ".amp"))

                try {
                    val metadata = if (jsonFile.exists()) {
                        parseMetadata(jsonFile)
                    } else {
                        // Orphaned audio file without metadata -- recover from filename and file size
                        recoverMetadata(audioFile)
                    }
                    AudioRecording(audioFile, jsonFile, ampFile, metadata)
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to parse ${audioFile.name}: ${e.message}")
                    null
                }
            }
            ?.sortedByDescending { it.metadata.startTime }
            ?: emptyList()
    }

    fun deleteRecording(recording: AudioRecording) {
        recording.audioFile.delete()
        recording.metadataFile.delete()
        recording.amplitudeFile.delete()
        LogCollector.i(TAG, "Deleted recording: ${recording.audioFile.name}")
    }

    private val persistentDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "pinned_recordings")
            dir.mkdirs()
            return dir
        }

    fun togglePin(recording: AudioRecording): Boolean {
        val newPinned = !recording.metadata.pinned
        if (recording.metadataFile.exists()) {
            try {
                val json = JSONObject(recording.metadataFile.readText())
                json.put("pinned", newPinned)
                recording.metadataFile.writeText(json.toString(2))
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to toggle pin: ${e.message}")
            }
        }

        // Copy to / remove from persistent external storage
        try {
            if (newPinned) {
                val destDir = persistentDir
                recording.audioFile.copyTo(File(destDir, recording.audioFile.name), overwrite = true)
                recording.metadataFile.copyTo(File(destDir, recording.metadataFile.name), overwrite = true)
                if (recording.amplitudeFile.exists()) {
                    recording.amplitudeFile.copyTo(File(destDir, recording.amplitudeFile.name), overwrite = true)
                }
                LogCollector.i(TAG, "Pinned + backed up to external: ${recording.audioFile.name}")
            } else {
                File(persistentDir, recording.audioFile.name).delete()
                File(persistentDir, recording.metadataFile.name).delete()
                File(persistentDir, recording.amplitudeFile.name).delete()
                LogCollector.i(TAG, "Unpinned + removed backup: ${recording.audioFile.name}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to manage persistent backup: ${e.message}")
        }

        return newPinned
    }

    fun deleteAllUnpinned(): Int {
        val recordings = listRecordings()
        var count = 0
        for (rec in recordings) {
            if (!rec.metadata.pinned) {
                deleteRecording(rec)
                count++
            }
        }
        LogCollector.i(TAG, "Deleted $count unpinned recordings")
        return count
    }

    fun getTotalStorageUsed(): Long {
        val dir = recordingsDir
        if (!dir.exists()) return 0
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    fun loadAmplitudeData(ampFile: File): ShortArray {
        if (ampFile.exists() && ampFile.length() > 0) {
            val bytes = ampFile.readBytes()
            val shorts = ShortArray(bytes.size / 2)
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in shorts.indices) {
                shorts[i] = buf.getShort()
            }
            return shorts
        }

        // No amplitude data -- try to generate from audio file (on caller's thread)
        var audioFile = File(ampFile.absolutePath.replace(".amp", ".aac"))
        // Legacy fallback: try .m4a if .aac not found
        if (!audioFile.exists()) audioFile = File(ampFile.absolutePath.replace(".amp", ".m4a"))
        if (!audioFile.exists()) return ShortArray(0)
        // Mark as attempted to avoid retrying every 5s on undecodable files
        if (failedAmplitudeFiles.contains(audioFile.absolutePath)) return ShortArray(0)
        return generateAmplitudeFromAudio(audioFile, ampFile)
    }

    private fun generateAmplitudeFromAudio(audioFile: File, ampFile: File): ShortArray {
        try {
            LogCollector.i(TAG, "Generating amplitude from ${audioFile.name}")
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            if (extractor.trackCount == 0) {
                extractor.release()
                return ShortArray(0)
            }
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return ShortArray(0)
            val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val windowSamples = sampleRate / 10 // 100ms window

            val decoder = android.media.MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val peaks = mutableListOf<Short>()
            val decInfo = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(decInfo, 5000)
                if (outIdx >= 0) {
                    if (decInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (decInfo.size > 0) {
                        val pcmBuf = decoder.getOutputBuffer(outIdx)!!
                        pcmBuf.position(decInfo.offset)
                        val shortBuf = pcmBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val samples = ShortArray(decInfo.size / 2)
                        shortBuf.get(samples)

                        // Compute peaks per window
                        var i = 0
                        while (i < samples.size) {
                            var maxAmp = 0
                            val end = minOf(i + windowSamples, samples.size)
                            for (j in i until end) {
                                val abs = kotlin.math.abs(samples[j].toInt())
                                if (abs > maxAmp) maxAmp = abs
                            }
                            peaks.add(maxAmp.coerceAtMost(32767).toShort())
                            i += windowSamples
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            // Cache to .amp file
            val result = ShortArray(peaks.size) { peaks[it] }
            try {
                val out = java.io.BufferedOutputStream(java.io.FileOutputStream(ampFile))
                val byteBuf = java.nio.ByteBuffer.allocate(result.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (s in result) byteBuf.putShort(s)
                out.write(byteBuf.array())
                out.flush()
                out.close()
                LogCollector.i(TAG, "Generated ${result.size} amplitude peaks for ${audioFile.name}")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to cache amplitude: ${e.message}")
            }

            return result
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to generate amplitude from ${audioFile.name}: ${e.message}")
            failedAmplitudeFiles.add(audioFile.absolutePath)
            return ShortArray(0)
        }
    }

    fun scheduleRetentionJob() {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "audio_retention",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        LogCollector.i(TAG, "Retention job scheduled")
    }

    private fun parseMetadata(jsonFile: File): RecordingMetadata {
        val json = JSONObject(jsonFile.readText())
        val segments = mutableListOf<VoiceSegmentData>()
        val segArray = json.optJSONArray("voiceSegments")
        var hadStaleMarkers = false
        if (segArray != null) {
            for (i in 0 until segArray.length()) {
                val seg = segArray.getJSONObject(i)
                val rawTranscription = seg.optString("transcription", "")
                val cleanTranscription = if (rawTranscription == "[transcribing...]") {
                    hadStaleMarkers = true
                    seg.put("transcription", "")
                    ""
                } else rawTranscription
                segments.add(VoiceSegmentData(
                    startMs = seg.getLong("startMs"),
                    endMs = seg.getLong("endMs"),
                    transcription = cleanTranscription
                ))
            }
        }
        // Clean up stale markers on disk
        if (hadStaleMarkers) {
            try {
                jsonFile.writeText(json.toString(2))
                LogCollector.i(TAG, "Cleaned stale transcription markers in ${jsonFile.name}")
            } catch (_: Exception) {}
        }
        return RecordingMetadata(
            startTime = json.getString("startTime"),
            endTime = json.getString("endTime"),
            durationMs = json.getLong("durationMs"),
            sampleRate = json.optInt("sampleRate", 16000),
            bitrateKbps = json.optInt("bitrateKbps", 32),
            compressionTier = json.optString("compressionTier", "normal"),
            voiceSegments = segments,
            pinned = json.optBoolean("pinned", false),
            name = json.optString("name", "")
        )
    }

    private fun recoverMetadata(audioFile: File): RecordingMetadata {
        // Recover from filename (yyyy-MM-dd_HH-mm.aac) and file modification time
        val name = audioFile.nameWithoutExtension
        val startDate = try {
            SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).parse(name)
        } catch (_: Exception) { null }

        val startTime = if (startDate != null) {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(startDate)
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(audioFile.lastModified()))
        }

        // Estimate duration from file size (32kbps AAC = ~4000 bytes/sec)
        val estimatedDurationMs = (audioFile.length() * 1000) / 4000
        val endTimeMs = (startDate?.time ?: audioFile.lastModified()) + estimatedDurationMs
        val endTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(endTimeMs))

        LogCollector.i(TAG, "Recovered metadata for orphaned file: ${audioFile.name}, ~${estimatedDurationMs / 1000}s")
        return RecordingMetadata(
            startTime = startTime,
            endTime = endTime,
            durationMs = estimatedDurationMs,
            sampleRate = 16000,
            bitrateKbps = 32,
            compressionTier = "normal",
            voiceSegments = emptyList(),
            name = ""
        )
    }

    /**
     * Transcode a glasses-archive .opus file (length-prefixed raw Opus 20ms frames, 16 kHz mono)
     * into an ADTS .aac in recordingsDir, plus a matching .json metadata triplet so
     * listRecordings() surfaces it exactly like a phone-recorded session. Idempotent: returns
     * true if the .aac+.json pair already exists. The source .opus is left untouched
     * (lossless backup in glasses-archive/).
     */
    fun ingestOpusFromGlasses(opusFile: File): Boolean {
        if (!opusFile.isFile || opusFile.extension != "opus") return false
        val base = opusFile.nameWithoutExtension
        val epochMillis = base.removePrefix("rec_").toLongOrNull()
        if (epochMillis == null) {
            LogCollector.w(TAG, "ingestOpusFromGlasses: cannot parse epoch from ${opusFile.name}")
            return false
        }
        val outName = "glasses_$epochMillis"
        // Per-file lock so two overlapping syncs can't double-transcode the same opus and produce
        // mismatched .json/.aac state (cause of "duration jumping" the user observed).
        val lock = ingestLocks.getOrPut(outName) { Any() }
        synchronized(lock) {
            return ingestOpusLocked(opusFile, epochMillis, outName)
        }
    }

    private fun ingestOpusLocked(opusFile: File, epochMillis: Long, outName: String): Boolean {
        val dir = recordingsDir
        if (!dir.exists()) dir.mkdirs()
        val outAac = File(dir, "$outName.aac")
        val outJson = File(dir, "$outName.json")
        if (outAac.exists() && outJson.exists()) return true
        // Stale .aac without matching .json (e.g. a previous transcode crashed before json write).
        // Delete it so the re-transcode below produces a coherent pair instead of overwriting in
        // place and ending up with mismatched duration if the new run differs.
        if (outAac.exists()) outAac.delete()
        if (outJson.exists()) outJson.delete()

        val durationMs = try {
            transcodeOpusToAdtsAac(opusFile, outAac)
        } catch (e: Exception) {
            LogCollector.e(TAG, "ingestOpusFromGlasses ${opusFile.name} failed: ${e.message}")
            outAac.delete()
            return false
        }
        if (durationMs <= 0L) {
            outAac.delete()
            return false
        }

        // Glasses' filename epoch is the openNewFile timestamp (close-to-start). The recording
        // therefore covers [epochMillis, epochMillis + durationMs]. UI sorts by startTime, so
        // anchor startTime to the epoch.
        val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        val json = JSONObject().apply {
            put("startTime", tsFmt.format(Date(epochMillis)))
            put("endTime", tsFmt.format(Date(epochMillis + durationMs)))
            put("durationMs", durationMs)
            put("sampleRate", 16000)
            put("bitrateKbps", 32)
            put("compressionTier", "normal")
            put("voiceSegments", org.json.JSONArray())
            put("pinned", false)
            put("name", "")
            put("source", "glasses")
        }
        try {
            outJson.writeText(json.toString(2))
        } catch (e: Exception) {
            LogCollector.e(TAG, "ingestOpusFromGlasses ${opusFile.name} json write failed: ${e.message}")
            outAac.delete()
            outJson.delete()
            return false
        }
        LogCollector.i(TAG, "ingestOpusFromGlasses: ${opusFile.name} -> ${outAac.name} (${durationMs}ms, ${outAac.length()} bytes)")
        return true
    }

    /**
     * Decode the glasses' length-prefixed raw-Opus-frame container in opusFile (matches the format
     * LocalOpusWriter emits and OpusStreamDecoder consumes from RFCOMM: [u16 LE length][N bytes
     * Opus packet] repeated, no Ogg wrapper, 20 ms frames @ 16 kHz mono) and re-encode the PCM as
     * ADTS AAC-LC at 32 kbps, 16 kHz mono. Returns the decoded duration in ms (>0 on success).
     * Duration is derived from the count of Opus frames (each = 20 ms) so it does NOT depend on
     * the platform decoder's actual output sample rate -- some Android opus decoders emit 48 kHz
     * PCM regardless of the requested rate, so byte-counted PTS would over-report by 3x.
     */
    private fun transcodeOpusToAdtsAac(opusFile: File, aacOut: File): Long {
        val targetRate = 16000
        val targetChannels = 1
        val frameMs = 20

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var out: java.io.BufferedOutputStream? = null
        var input: java.io.DataInputStream? = null
        try {
            // Configure opus decoder. setting 16kHz here is a HINT; some platform implementations
            // ignore it and emit 48kHz mono. We adapt at runtime based on KEY_SAMPLE_RATE on the
            // INFO_OUTPUT_FORMAT_CHANGED event and decimate to targetRate before encoding.
            // csd-1 / csd-2 must be 8-byte LITTLE_ENDIAN longs (codec delay & seek-pre-roll). Mirror
            // OpusStreamDecoder which is empirically working in the same app.
            val decFmt = MediaFormat.createAudioFormat("audio/opus", targetRate, targetChannels).apply {
                setByteBuffer("csd-0", buildOpusIdHeader(targetRate, targetChannels))
                val csd1 = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN); csd1.putLong(0); csd1.flip()
                setByteBuffer("csd-1", csd1)
                val csd2 = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN); csd2.putLong(3840); csd2.flip()
                setByteBuffer("csd-2", csd2)
            }
            decoder = MediaCodec.createDecoderByType("audio/opus")
            decoder.configure(decFmt, null, null, 0)
            decoder.start()

            val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, targetRate, targetChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            out = java.io.BufferedOutputStream(java.io.FileOutputStream(aacOut))
            input = java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(opusFile)))

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var opusFramesQueued = 0
            var pcmFramesIn = 0L          // PCM samples decoded so far at decoder's native rate
            var encoderPtsUs = 0L         // PTS for next encoder input chunk, in target-rate microseconds
            var pcmRateLogged = false
            var detectedDecoderRate = 0   // sample rate of decoder output, detected from first chunk
            var rmsAccum = 0.0
            var rmsCount = 0L

            // 1) Pump all input frames into decoder, draining decoder→encoder→out as we go.
            while (true) {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = input.read()
                if (b1 < 0) break
                val frameLen = (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
                if (frameLen <= 0 || frameLen > 8192) {
                    LogCollector.w(TAG, "transcodeOpusToAdtsAac: invalid frame length=$frameLen at frame#$opusFramesQueued")
                    break
                }
                val frame = ByteArray(frameLen)
                var read = 0
                while (read < frameLen) {
                    val n = input.read(frame, read, frameLen - read)
                    if (n < 0) break
                    read += n
                }
                if (read != frameLen) break

                // Queue Opus frame to decoder, retrying drain if no input slot. Bound the retry
                // loop so a stuck encoder (driver bug) can't pin the sync thread forever.
                var queued = false
                var retries = 0
                while (!queued && retries < 200) {
                    val inIdx = decoder.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        buf.put(frame)
                        decoder.queueInputBuffer(inIdx, 0, frameLen, opusFramesQueued.toLong() * frameMs * 1000L, 0)
                        opusFramesQueued++
                        queued = true
                        break
                    }
                    val stats = drainDecoderAndEncode(decoder, encoder, decInfo, encInfo, out, targetRate, encoderPtsUs)
                    if (stats != null) {
                        if (!pcmRateLogged && stats.detectedRate > 0) {
                            LogCollector.i(TAG, "transcodeOpusToAdtsAac: decoder native rate=${stats.detectedRate}Hz")
                            pcmRateLogged = true
                            detectedDecoderRate = stats.detectedRate
                        }
                        pcmFramesIn += stats.pcmShortsConsumed
                        encoderPtsUs = stats.lastEncoderPtsUs
                        rmsAccum += stats.rmsAccum; rmsCount += stats.rmsCount
                    }
                    retries++
                }
                if (!queued) {
                    LogCollector.w(TAG, "transcodeOpusToAdtsAac: gave up queueing frame#$opusFramesQueued (encoder stuck?)")
                    break
                }
                val stats = drainDecoderAndEncode(decoder, encoder, decInfo, encInfo, out, targetRate, encoderPtsUs)
                if (stats != null) {
                    if (!pcmRateLogged && stats.detectedRate > 0) {
                        LogCollector.i(TAG, "transcodeOpusToAdtsAac: decoder native rate=${stats.detectedRate}Hz")
                        pcmRateLogged = true
                        detectedDecoderRate = stats.detectedRate
                    }
                    pcmFramesIn += stats.pcmShortsConsumed
                    encoderPtsUs = stats.lastEncoderPtsUs
                    rmsAccum += stats.rmsAccum; rmsCount += stats.rmsCount
                }
            }

            // 2) Signal decoder EOS, drain remaining PCM, then signal encoder EOS, drain ADTS.
            val decEosIdx = decoder.dequeueInputBuffer(10000)
            if (decEosIdx >= 0) {
                decoder.queueInputBuffer(decEosIdx, 0, 0, opusFramesQueued.toLong() * frameMs * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // Drain decoder until EOS.
            var decoderEosSeen = false
            val drainDeadline = System.currentTimeMillis() + 5000
            while (!decoderEosSeen && System.currentTimeMillis() < drainDeadline) {
                val stats = drainDecoderAndEncode(decoder, encoder, decInfo, encInfo, out, targetRate, encoderPtsUs) ?: break
                pcmFramesIn += stats.pcmShortsConsumed
                encoderPtsUs = stats.lastEncoderPtsUs
                rmsAccum += stats.rmsAccum; rmsCount += stats.rmsCount
                if (stats.decoderEos) decoderEosSeen = true
            }
            // Now signal encoder EOS.
            val encEosIdx = encoder.dequeueInputBuffer(10000)
            if (encEosIdx >= 0) {
                encoder.queueInputBuffer(encEosIdx, 0, 0, encoderPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val flushDeadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < flushDeadline) {
                drainEncoderToAdts(encoder, encInfo, out)
                if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            out.flush()

            val durationMs = opusFramesQueued.toLong() * frameMs
            val rmsAvg = if (rmsCount > 0) Math.sqrt(rmsAccum / rmsCount.toDouble()) else 0.0
            LogCollector.i(TAG, "transcodeOpusToAdtsAac: ${opusFile.name} frames=$opusFramesQueued duration=${durationMs}ms decoderRate=${detectedDecoderRate}Hz rms=${"%.1f".format(rmsAvg)}")
            return durationMs
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        }
    }

    private data class DecodeStats(
        val pcmShortsConsumed: Long,
        val detectedRate: Int,
        val lastEncoderPtsUs: Long,
        val decoderEos: Boolean,
        val rmsAccum: Double,
        val rmsCount: Long,
    )

    /**
     * Pull all currently-available decoded PCM out of `decoder`, downsample S16LE mono from the
     * decoder's native rate to `targetRate` (drop-only -- glasses only emit 8 kHz speech band so
     * lowpass aliasing isn't a concern), feed it to `encoder`, and drain ADTS frames to `out`.
     * Returns null when the decoder has no output to drain (caller should also push more input).
     */
    private fun drainDecoderAndEncode(
        decoder: MediaCodec,
        encoder: MediaCodec,
        decInfo: MediaCodec.BufferInfo,
        encInfo: MediaCodec.BufferInfo,
        out: java.io.BufferedOutputStream,
        targetRate: Int,
        startPtsUs: Long,
    ): DecodeStats? {
        var pcmShorts = 0L
        var detectedRate = 0
        var lastPts = startPtsUs
        var eos = false
        var rmsAccum = 0.0
        var rmsCount = 0L
        // Carries a pending PCM buffer if encoder backpressures. Local to this drain pass only --
        // EOS handling is a separate phase so we don't need cross-call carry.
        while (true) {
            val outIdx = decoder.dequeueOutputBuffer(decInfo, 0)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = decoder.outputFormat
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    detectedRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                continue
            }
            if (outIdx < 0) break
            if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
            if (decInfo.size > 0) {
                val pcm = decoder.getOutputBuffer(outIdx)!!
                pcm.position(decInfo.offset)
                pcm.limit(decInfo.offset + decInfo.size)
                if (detectedRate <= 0) {
                    val f = decoder.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) detectedRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                val srcRate = if (detectedRate > 0) detectedRate else targetRate
                val resampled = downsampleS16Mono(pcm, srcRate, targetRate)
                pcmShorts += (decInfo.size / 2).toLong()
                // Compute average abs value for diagnostics.
                if (resampled.isNotEmpty()) {
                    val sb = java.nio.ByteBuffer.wrap(resampled).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sb.hasRemaining()) {
                        val v = sb.get().toInt()
                        rmsAccum += (v * v).toDouble()
                        rmsCount++
                    }
                }
                // Push resampled PCM into encoder.
                var off = 0
                while (off < resampled.size) {
                    val encInIdx = encoder.dequeueInputBuffer(5000)
                    if (encInIdx < 0) {
                        drainEncoderToAdts(encoder, encInfo, out)
                        continue
                    }
                    val encBuf = encoder.getInputBuffer(encInIdx)!!
                    encBuf.clear()
                    val chunk = minOf(encBuf.capacity(), resampled.size - off)
                    encBuf.put(resampled, off, chunk)
                    encoder.queueInputBuffer(encInIdx, 0, chunk, lastPts, 0)
                    lastPts += (chunk.toLong() * 1_000_000L) / (targetRate.toLong() * 2L)
                    off += chunk
                }
                drainEncoderToAdts(encoder, encInfo, out)
            }
            decoder.releaseOutputBuffer(outIdx, false)
        }
        return DecodeStats(pcmShorts, detectedRate, lastPts, eos, rmsAccum, rmsCount)
    }

    /**
     * Decimate S16LE mono PCM from `srcRate` to `dstRate` (drop-only). The glasses encode at
     * 16 kHz so the meaningful speech content is bandlimited to 8 kHz; if the platform decoder
     * upsamples to 48 kHz we just drop 2 of every 3 samples to land back at 16 kHz with no
     * audible difference for archival playback.
     */
    private fun downsampleS16Mono(src: java.nio.ByteBuffer, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) {
            val arr = ByteArray(src.remaining())
            src.get(arr)
            return arr
        }
        val srcShorts = src.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val srcCount = srcShorts.remaining()
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstCount = (srcCount / ratio).toInt()
        val dst = java.nio.ByteBuffer.allocate(dstCount * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var fi = 0.0
        for (i in 0 until dstCount) {
            val si = fi.toInt().coerceAtMost(srcCount - 1)
            dst.putShort(srcShorts.get(si))
            fi += ratio
        }
        return dst.array()
    }

    /** Build the 19-byte Opus identification header (csd-0) for MediaCodec at the given rate. */
    private fun buildOpusIdHeader(rate: Int, channels: Int): java.nio.ByteBuffer {
        val b = java.nio.ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray(Charsets.US_ASCII))
        b.put(1.toByte())                                           // version
        b.put(channels.toByte())                                    // channel count
        b.putShort(3840.toShort())                                  // pre-skip (80ms @ 48kHz)
        b.putInt(rate)                                              // input sample rate (informational)
        b.putShort(0.toShort())                                     // output gain Q7.8 (none)
        b.put(0.toByte())                                           // mapping family (mono/stereo)
        b.flip()
        return b
    }

    private fun drainEncoderToAdts(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        out: java.io.BufferedOutputStream
    ) {
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* no muxer track for ADTS */ }
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx) ?: break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        val frame = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(frame)
                        out.write(buildAdtsHeader(info.size))
                        out.write(frame)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    // 16 kHz mono AAC-LC ADTS header. Must match GlassesAudioArchiver.createAdtsHeader exactly so
    // the player and amplitude-decoder treat both glasses-synced and phone-recorded files identically.
    private fun buildAdtsHeader(frameLength: Int): ByteArray {
        val h = ByteArray(7)
        val packetLen = frameLength + 7
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte()
        h[2] = 0x60.toByte()
        h[3] = (0x40 or ((packetLen shr 11) and 0x03)).toByte()
        h[4] = ((packetLen shr 3) and 0xFF).toByte()
        h[5] = (((packetLen and 0x07) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
        return h
    }

    class RetentionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            val manager = AudioFileManager(applicationContext)
            val dir = manager.recordingsDir
            if (!dir.exists()) return Result.success()

            val now = System.currentTimeMillis()
            val recordings = manager.listRecordings()
            var deleted = 0
            var recompressed = 0

            for (recording in recordings) {
                try {
                    val fileDate = parseFileDate(recording.audioFile.name) ?: continue
                    val ageDays = (now - fileDate) / (24 * 60 * 60 * 1000)

                    when {
                        ageDays > TIGHT_RETENTION_DAYS -> {
                            manager.deleteRecording(recording)
                            deleted++
                        }
                        ageDays > NORMAL_RETENTION_DAYS && recording.metadata.compressionTier == "normal" -> {
                            recompressFile(recording)
                            recompressed++
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Retention error for ${recording.audioFile.name}: ${e.message}")
                }
            }

            LogCollector.i(TAG, "Retention job: deleted=$deleted, recompressed=$recompressed")
            return Result.success()
        }

        private fun parseFileDate(filename: String): Long? {
            return try {
                val dateStr = filename.substringBeforeLast(".")
                SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).parse(dateStr)?.time
            } catch (e: Exception) {
                null
            }
        }

        private fun recompressFile(recording: AudioRecording) {
            // Skip .aac files for now -- ADTS recompression not yet implemented
            if (recording.audioFile.extension == "aac") {
                LogCollector.i(TAG, "Skipping recompression for ADTS file: ${recording.audioFile.name}")
                return
            }

            // Decode existing M4A to PCM, re-encode at lower bitrate
            val tempFile = File(recording.audioFile.absolutePath + ".tmp")

            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(recording.audioFile.absolutePath)
                extractor.selectTrack(0)
                val inputFormat = extractor.getTrackFormat(0)
                val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                // Set up decoder
                val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                // Set up encoder at lower bitrate
                val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, TIGHT_BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                var muxerStarted = false

                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var decodeDone = false
                var pendingPcm: java.nio.ByteBuffer? = null
                var pendingPts = 0L

                while (!decodeDone) {
                    // Feed extractor -> decoder
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(5000)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Try to flush pending PCM into encoder first
                    if (pendingPcm != null) {
                        val encInIdx = encoder.dequeueInputBuffer(5000)
                        if (encInIdx >= 0) {
                            val encBuf = encoder.getInputBuffer(encInIdx)!!
                            encBuf.clear()
                            val size = pendingPcm!!.remaining()
                            encBuf.put(pendingPcm!!)
                            encoder.queueInputBuffer(encInIdx, 0, size, pendingPts, 0)
                            pendingPcm = null
                        }
                    }

                    // Decoder output -> encoder input
                    val decIdx = decoder.dequeueOutputBuffer(decInfo, 5000)
                    if (decIdx >= 0) {
                        val pcmBuf = decoder.getOutputBuffer(decIdx)!!
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decodeDone = true
                            // Signal encoder EOS
                            val encInIdx = encoder.dequeueInputBuffer(5000)
                            if (encInIdx >= 0) {
                                encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        } else if (decInfo.size > 0) {
                            val encInIdx = encoder.dequeueInputBuffer(5000)
                            if (encInIdx >= 0) {
                                val encBuf = encoder.getInputBuffer(encInIdx)!!
                                encBuf.clear()
                                pcmBuf.position(decInfo.offset)
                                pcmBuf.limit(decInfo.offset + decInfo.size)
                                encBuf.put(pcmBuf)
                                encoder.queueInputBuffer(encInIdx, 0, decInfo.size, decInfo.presentationTimeUs, 0)
                            } else {
                                // Encoder busy -- buffer PCM for retry on next iteration
                                pcmBuf.position(decInfo.offset)
                                pcmBuf.limit(decInfo.offset + decInfo.size)
                                val copy = java.nio.ByteBuffer.allocate(decInfo.size)
                                copy.put(pcmBuf)
                                copy.flip()
                                pendingPcm = copy
                                pendingPts = decInfo.presentationTimeUs
                            }
                        }
                        decoder.releaseOutputBuffer(decIdx, false)
                    }

                    // Drain encoder output -> muxer
                    while (true) {
                        val encIdx = encoder.dequeueOutputBuffer(encInfo, 0)
                        when {
                            encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    trackIndex = muxer.addTrack(encoder.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                            }
                            encIdx >= 0 -> {
                                val outBuf = encoder.getOutputBuffer(encIdx) ?: break
                                if (encInfo.size > 0 && muxerStarted) {
                                    outBuf.position(encInfo.offset)
                                    outBuf.limit(encInfo.offset + encInfo.size)
                                    muxer.writeSampleData(trackIndex, outBuf, encInfo)
                                }
                                encoder.releaseOutputBuffer(encIdx, false)
                                if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            }
                            else -> break
                        }
                    }
                }

                // Final drain of encoder
                while (true) {
                    val encIdx = encoder.dequeueOutputBuffer(encInfo, 5000)
                    when {
                        encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        encIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(encIdx) ?: break
                            if (encInfo.size > 0 && muxerStarted) {
                                outBuf.position(encInfo.offset)
                                outBuf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, encInfo)
                            }
                            encoder.releaseOutputBuffer(encIdx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                        else -> break
                    }
                }

                decoder.stop(); decoder.release()
                encoder.stop(); encoder.release()
                extractor.release()
                if (muxerStarted) muxer.stop()
                muxer.release()

                // Replace original with recompressed
                recording.audioFile.delete()
                tempFile.renameTo(recording.audioFile)

                // Update metadata
                val json = JSONObject(recording.metadataFile.readText())
                json.put("compressionTier", "tight")
                json.put("bitrateKbps", TIGHT_BIT_RATE / 1000)
                recording.metadataFile.writeText(json.toString(2))

                LogCollector.i(TAG, "Recompressed ${recording.audioFile.name} to ${TIGHT_BIT_RATE / 1000}kbps")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Recompression failed for ${recording.audioFile.name}: ${e.message}")
                tempFile.delete()
            }
        }
    }
}
