package com.repository.listener.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

class BackgroundTranscriber(
    private val context: Context,
    private val archiver: GlassesAudioArchiver
) {
    companion object {
        private const val TAG = "BgTranscriber"
        private const val SCAN_INTERVAL_MS = 30_000L
        private const val SEGMENT_COOLDOWN_MS = 5_000L
        private const val MIN_FILE_AGE_MS = 10_000L
        private const val MAX_SEGMENT_DURATION_MS = 300_000L
    }

    private var whisper: WhisperTranscriber? = null
    private var job: Job? = null

    private val recordingsDir: File
        get() = File(context.filesDir, "audio_recordings")

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            initWhisper()
            transcriptionLoop()
        }
        LogCollector.i(TAG, "Background transcriber started")
    }

    fun stop() {
        val j = job
        job = null
        j?.cancel()
        // Wait for coroutine to finish before releasing native Whisper context
        runBlocking { j?.join() }
        whisper?.release()
        whisper = null
        LogCollector.i(TAG, "Background transcriber stopped")
    }

    private fun initWhisper() {
        try {
            val w = WhisperTranscriber(context)
            if (w.isModelAvailable() && w.initialize()) {
                whisper = w
                LogCollector.i(TAG, "Whisper model loaded")
            } else {
                LogCollector.i(TAG, "Whisper model not available")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to init Whisper: ${e.message}")
        }
    }

    private suspend fun transcriptionLoop() {
        while (currentCoroutineContext().isActive) {
            // Retry Whisper init if it failed on startup
            if (whisper == null) {
                initWhisper()
                if (whisper == null) {
                    delay(SCAN_INTERVAL_MS)
                    continue
                }
            }

            val tasks = scanForWork()
            if (tasks.isEmpty()) {
                delay(SCAN_INTERVAL_MS)
                continue
            }

            LogCollector.i(TAG, "Found ${tasks.size} segments to transcribe")

            for (task in tasks) {
                if (!currentCoroutineContext().isActive) break

                try {
                    val samples = decodeAacSegment(task.aacFile, task.startMs, task.endMs)
                    if (samples == null || samples.isEmpty()) {
                        LogCollector.e(TAG, "Failed to decode segment ${task.startMs}-${task.endMs}ms from ${task.aacFile.name}")
                        continue
                    }

                    val w = whisper ?: break
                    w.startUtterance()
                    w.feedAudio(samples)
                    val text = w.endUtterance()

                    updateJsonTranscription(task.jsonFile, task.startMs, task.endMs, text)

                    if (text.isNotEmpty()) {
                        LogCollector.i(TAG, "Transcribed ${task.aacFile.name} [${task.startMs}-${task.endMs}ms]: '$text'")
                    } else {
                        LogCollector.i(TAG, "Empty transcription for ${task.aacFile.name} [${task.startMs}-${task.endMs}ms]")
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Transcription error for ${task.aacFile.name} [${task.startMs}-${task.endMs}ms]: ${e.message}")
                }

                delay(SEGMENT_COOLDOWN_MS)
            }
        }
    }

    private fun scanForWork(): List<TranscriptionTask> {
        val dir = recordingsDir
        if (!dir.exists()) return emptyList()

        val activeFile = archiver.getCurrentFileName()
        val now = System.currentTimeMillis()
        val tasks = mutableListOf<TranscriptionTask>()

        val jsonFiles = dir.listFiles()?.filter { it.extension == "json" } ?: return emptyList()

        for (jsonFile in jsonFiles.sortedBy { it.lastModified() }) {
            // Skip files still being written
            if (now - jsonFile.lastModified() < MIN_FILE_AGE_MS) continue

            // Skip the currently active recording
            val baseName = jsonFile.nameWithoutExtension
            if (activeFile != null && activeFile.startsWith(baseName)) continue

            val aacFile = File(dir, "$baseName.aac")
            if (!aacFile.exists()) continue

            try {
                val json = JSONObject(jsonFile.readText())
                val segs = json.optJSONArray("voiceSegments") ?: continue

                for (i in 0 until segs.length()) {
                    val seg = segs.getJSONObject(i)
                    val transcription = seg.optString("transcription", "")
                    if (transcription.isEmpty() || transcription == "[transcribing...]") {
                        val startMs = seg.getLong("startMs")
                        val endMs = seg.getLong("endMs")
                        val duration = endMs - startMs
                        if (duration > MAX_SEGMENT_DURATION_MS) continue
                        tasks.add(TranscriptionTask(jsonFile, aacFile, startMs, endMs))
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse ${jsonFile.name}: ${e.message}")
            }
        }

        return tasks
    }

    private fun decodeAacSegment(aacFile: File, startMs: Long, endMs: Long): ShortArray? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(aacFile.absolutePath)
            if (extractor.trackCount == 0) return null
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val chunks = mutableListOf<ShortArray>()
            val decInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            var currentUs = 0L

            while (!outputDone) {
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

                val outIdx = decoder.dequeueOutputBuffer(decInfo, 5000)
                if (outIdx >= 0) {
                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (decInfo.size > 0) {
                        val pcmBuf = decoder.getOutputBuffer(outIdx)!!
                        pcmBuf.position(decInfo.offset)
                        val shortBuf = pcmBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val samples = ShortArray(decInfo.size / 2)
                        shortBuf.get(samples)

                        val sampleDurationUs = (samples.size * 1_000_000L) / 16000
                        if (currentUs + sampleDurationUs >= startUs && currentUs <= endUs) {
                            chunks.add(samples)
                        }
                        currentUs += sampleDurationUs
                        if (currentUs > endUs) outputDone = true
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                }
            }

            decoder.stop()

            if (chunks.isEmpty()) return null
            val totalSize = chunks.sumOf { it.size }
            val result = ShortArray(totalSize)
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            return result
        } catch (e: Exception) {
            LogCollector.e(TAG, "AAC decode error: ${e.message}")
            return null
        } finally {
            try { decoder?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun updateJsonTranscription(jsonFile: File, segStartMs: Long, segEndMs: Long, text: String) {
        try {
            val json = JSONObject(jsonFile.readText())
            val segs = json.optJSONArray("voiceSegments") ?: return

            var updated = false
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                if (s.getLong("startMs") == segStartMs && s.getLong("endMs") == segEndMs) {
                    s.put("transcription", text)
                    updated = true
                    break
                }
            }

            if (updated) {
                json.put("voiceSegments", segs)
                // Atomic write: tmp file then rename
                val tmpFile = File(jsonFile.parent, "${jsonFile.name}.tmp")
                tmpFile.writeText(json.toString(2))
                if (!tmpFile.renameTo(jsonFile)) {
                    // Rename failed (rare), fall back to direct write
                    jsonFile.writeText(json.toString(2))
                    tmpFile.delete()
                }
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to update ${jsonFile.name}: ${e.message}")
        }
    }

    private data class TranscriptionTask(
        val jsonFile: File,
        val aacFile: File,
        val startMs: Long,
        val endMs: Long
    )
}
