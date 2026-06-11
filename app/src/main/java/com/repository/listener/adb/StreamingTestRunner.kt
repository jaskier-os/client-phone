package com.repository.listener.adb

import android.content.Context
import com.repository.listener.network.OrchestratorClient
import com.repository.listener.util.LogCollector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs a streaming transcription test by loading a PCM file from disk
 * and streaming it through one of two modes:
 *
 * - **direct WS**: opens its own OkHttp WebSocket (bypasses app pipeline)
 * - **pipeline**: uses the app's real OrchestratorClient (connectTranscribeStream +
 *   sendTranscribeAudioFrame), same code path as live translation. Feeds 512-sample
 *   chunks to match SystemAudioCapturer.
 */
class StreamingTestRunner(
    private val context: Context,
    private val commandId: String,
    private val pcmFile: File,
    private val wsUrl: String,
    private val orchestratorClient: OrchestratorClient? = null,
    private val sourceLang: String = "en",
    private val targetLang: String = "ru",
    private val sourceNllb: String = "eng_Latn",
    private val targetNllb: String = "rus_Cyrl"
) {
    companion object {
        private const val TAG = "StreamingTestRunner"
        private const val BATCH_BYTES = 3200 // 100ms at 16kHz 16-bit mono
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE = 2
        private const val DRAIN_TIMEOUT_S = 7L
        // Match SystemAudioCapturer chunk size: 512 samples = 1024 bytes
        private const val SYSTEM_AUDIO_CHUNK_SAMPLES = 512
        private const val SYSTEM_AUDIO_CHUNK_BYTES = SYSTEM_AUDIO_CHUNK_SAMPLES * BYTES_PER_SAMPLE
    }

    private data class Event(
        val t: Double,
        val type: String,
        val segId: Int? = null,
        val text: String? = null
    )

    private fun fmt(v: Double, decimals: Int = 2): String =
        String.format(Locale.US, "%.${decimals}f", v)

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    fun run() {
        if (orchestratorClient != null) {
            runPipeline()
        } else {
            runDirectWs()
        }
    }

    /**
     * Pipeline mode: feeds audio through the real OrchestratorClient,
     * same code path as live system audio translation.
     * Uses 512-sample (1024-byte) chunks like SystemAudioCapturer.
     */
    private fun runPipeline() {
        val oc = orchestratorClient!!
        LogCollector.i(TAG, "Starting PIPELINE streaming test: pcm=${pcmFile.name} (${pcmFile.length()} bytes)")

        val pcmData = pcmFile.readBytes()
        val audioDurationS = pcmData.size.toDouble() / (SAMPLE_RATE * BYTES_PER_SAMPLE)
        val events = mutableListOf<Event>()
        val startNanos = System.nanoTime()

        fun elapsed(): Double = (System.nanoTime() - startNanos) / 1_000_000_000.0

        // Set up listener to capture events
        val prevListener = try {
            // Save previous listener if any (reflection not needed, just set ours)
            null
        } catch (_: Exception) { null }

        oc.setTranscribeStreamListener(object : OrchestratorClient.TranscribeStreamListener {
            override fun onTranscription(segId: Int, text: String, translation: String, partial: Boolean) {
                val eventType = if (partial) "partial" else "transcription_final"
                synchronized(events) {
                    events.add(Event(elapsed(), eventType, segId, text))
                    if (translation.isNotEmpty()) {
                        events.add(Event(elapsed(), "translation", segId, translation))
                    }
                }
                LogCollector.i(TAG, "[${fmt(elapsed())}s] $eventType seg=$segId: ${text.take(40)} | ${translation.take(40)}")
            }

            override fun onFinal(segId: Int, text: String, translation: String) {
                synchronized(events) {
                    events.add(Event(elapsed(), "final", segId, text))
                }
                LogCollector.i(TAG, "[${fmt(elapsed())}s] final seg=$segId: ${text.take(60)}")
            }

            override fun onStreamError(message: String) {
                synchronized(events) {
                    events.add(Event(elapsed(), "error", text = message))
                }
                LogCollector.e(TAG, "Stream error: $message")
            }
        })

        // Connect transcribe stream (same as start_translation does)
        synchronized(events) {
            events.add(Event(elapsed(), "connect_transcribe"))
        }
        oc.connectTranscribeStream(sourceLang, targetLang, sourceNllb, targetNllb)

        // Poll until WS is actually connected (instead of blind 2s sleep)
        val connectDeadline = System.currentTimeMillis() + 10_000
        while (!oc.isTranscribeConnected && System.currentTimeMillis() < connectDeadline) {
            Thread.sleep(50)
        }
        if (!oc.isTranscribeConnected) {
            AdbResultWriter.writeError(context, commandId, "test_streaming", "Transcribe WS connect timeout")
            return
        }
        LogCollector.i(TAG, "Transcribe WS connected in ${fmt(elapsed())}s")
        synchronized(events) {
            events.add(Event(elapsed(), "streaming_start"))
        }

        // Feed audio in 512-sample chunks (same as SystemAudioCapturer)
        // Paced to real-time: 512 samples at 16kHz = 32ms per chunk
        val chunkDurationMs = (SYSTEM_AUDIO_CHUNK_SAMPLES.toDouble() / SAMPLE_RATE * 1000).toLong()
        var offset = 0
        var chunkCount = 0

        LogCollector.i(TAG, "Streaming ${pcmData.size} bytes (${fmt(audioDurationS, 1)}s) in $SYSTEM_AUDIO_CHUNK_BYTES-byte chunks (${chunkDurationMs}ms each)")

        while (offset < pcmData.size) {
            val end = minOf(offset + SYSTEM_AUDIO_CHUNK_BYTES, pcmData.size)
            val chunk = pcmData.copyOfRange(offset, end)
            oc.sendTranscribeAudioFrame(chunk)
            chunkCount++
            offset = end

            if (offset < pcmData.size) {
                Thread.sleep(chunkDurationMs)
            }
        }

        LogCollector.i(TAG, "All $chunkCount chunks sent via sendTranscribeAudioFrame, waiting for results...")
        synchronized(events) {
            events.add(Event(elapsed(), "streaming_done", text = "$chunkCount chunks"))
        }

        // Wait for remaining results
        Thread.sleep(5000)

        // Disconnect (sends stop + close)
        synchronized(events) {
            events.add(Event(elapsed(), "disconnect"))
        }
        oc.disconnectTranscribeStream()

        Thread.sleep(2000)

        val wallTimeS = elapsed()
        writeResults(events, audioDurationS, wallTimeS)
    }

    /**
     * Direct WS mode: opens its own WebSocket, bypasses OrchestratorClient entirely.
     */
    private fun runDirectWs() {
        LogCollector.i(TAG, "Starting DIRECT WS streaming test: $wsUrl pcm=${pcmFile.name} (${pcmFile.length()} bytes)")

        val pcmData = pcmFile.readBytes()
        val audioDurationS = pcmData.size.toDouble() / (SAMPLE_RATE * BYTES_PER_SAMPLE)
        val events = mutableListOf<Event>()
        val startNanos = System.nanoTime()

        fun elapsed(): Double = (System.nanoTime() - startNanos) / 1_000_000_000.0

        val connectLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        var connectError: String? = null

        val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                LogCollector.i(TAG, "WS connected")
                synchronized(events) {
                    events.add(Event(elapsed(), "ws_connected"))
                }

                val startMsg = JSONObject().apply {
                    put("type", "start")
                    put("sampleRate", SAMPLE_RATE)
                    put("sourceLang", sourceLang)
                    put("targetLang", targetLang)
                    put("sourceNllb", sourceNllb)
                    put("targetNllb", targetNllb)
                }
                webSocket.send(startMsg.toString())

                val speechStartMsg = JSONObject().apply { put("type", "speech_start") }
                webSocket.send(speechStartMsg.toString())

                synchronized(events) {
                    events.add(Event(elapsed(), "start_sent"))
                }
                connectLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "transcription" -> {
                            val segId = json.optInt("segId")
                            val t = json.optString("text", "")
                            val partial = json.optBoolean("partial", true)
                            val eventType = if (partial) "partial" else "transcription_final"
                            synchronized(events) {
                                events.add(Event(elapsed(), eventType, segId, t))
                            }
                            LogCollector.i(TAG, "[${fmt(elapsed())}s] $eventType seg=$segId: ${t.take(60)}")
                        }
                        "translation" -> {
                            val segId = json.optInt("segId")
                            val translation = json.optString("translation", "")
                            synchronized(events) {
                                events.add(Event(elapsed(), "translation", segId, translation))
                            }
                            LogCollector.i(TAG, "[${fmt(elapsed())}s] translation seg=$segId: ${translation.take(60)}")
                        }
                        "final" -> {
                            val segId = json.optInt("segId")
                            val finalText = json.optString("text", "")
                            synchronized(events) {
                                events.add(Event(elapsed(), "final", segId, finalText))
                            }
                            LogCollector.i(TAG, "[${fmt(elapsed())}s] final seg=$segId: ${finalText.take(60)}")
                        }
                        "error" -> {
                            val msg = json.optString("message", "unknown")
                            synchronized(events) {
                                events.add(Event(elapsed(), "error", text = msg))
                            }
                            LogCollector.e(TAG, "Stream error: $msg")
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to parse WS message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LogCollector.i(TAG, "WS closed: code=$code")
                synchronized(events) {
                    events.add(Event(elapsed(), "ws_closed"))
                }
                doneLatch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogCollector.e(TAG, "WS failure: ${t.message}")
                connectError = t.message
                synchronized(events) {
                    events.add(Event(elapsed(), "ws_error", text = t.message))
                }
                connectLatch.countDown()
                doneLatch.countDown()
            }
        })

        if (!connectLatch.await(15, TimeUnit.SECONDS)) {
            AdbResultWriter.writeError(context, commandId, "test_streaming", "WS connect timeout")
            client.dispatcher.executorService.shutdown()
            return
        }

        if (connectError != null) {
            AdbResultWriter.writeError(context, commandId, "test_streaming", "WS connect failed: $connectError")
            client.dispatcher.executorService.shutdown()
            return
        }

        LogCollector.i(TAG, "Streaming ${pcmData.size} bytes (${fmt(audioDurationS, 1)}s) in $BATCH_BYTES-byte frames")
        synchronized(events) {
            events.add(Event(elapsed(), "streaming_start"))
        }

        val frameDurationMs = (BATCH_BYTES.toDouble() / (SAMPLE_RATE * BYTES_PER_SAMPLE) * 1000).toLong()
        var offset = 0
        var frameCount = 0

        while (offset < pcmData.size) {
            val end = minOf(offset + BATCH_BYTES, pcmData.size)
            val frame = pcmData.copyOfRange(offset, end)
            ws.send(ByteString.of(*frame))
            frameCount++
            offset = end

            if (offset < pcmData.size) {
                Thread.sleep(frameDurationMs)
            }
        }

        LogCollector.i(TAG, "All $frameCount frames sent, waiting for remaining results...")
        synchronized(events) {
            events.add(Event(elapsed(), "streaming_done", text = "$frameCount frames"))
        }

        Thread.sleep(5000)

        val stopMsg = JSONObject().apply { put("type", "stop") }
        ws.send(stopMsg.toString())
        synchronized(events) {
            events.add(Event(elapsed(), "stop_sent"))
        }

        Thread.sleep(2000)
        ws.close(1000, "Test complete")

        doneLatch.await(DRAIN_TIMEOUT_S, TimeUnit.SECONDS)

        val wallTimeS = elapsed()
        writeResults(events, audioDurationS, wallTimeS)
        client.dispatcher.executorService.shutdown()
    }

    private fun writeResults(events: List<Event>, audioDurationS: Double, wallTimeS: Double) {
        val eventsArray = JSONArray()

        var partialCount = 0
        var finalCount = 0
        var translationCount = 0
        var firstPartialS: Double? = null
        val partialTimes = mutableListOf<Double>()

        for (e in events) {
            val obj = JSONObject().apply {
                put("t", round2(e.t))
                put("type", e.type)
                if (e.segId != null) put("segId", e.segId)
                if (e.text != null) put("text", e.text)
            }
            eventsArray.put(obj)

            when (e.type) {
                "partial" -> {
                    partialCount++
                    partialTimes.add(e.t)
                    if (firstPartialS == null) firstPartialS = e.t
                }
                "final" -> finalCount++
                "translation" -> translationCount++
            }
        }

        val avgGap = if (partialTimes.size > 1) {
            partialTimes.zipWithNext { a, b -> b - a }.average()
        } else 0.0

        val maxGap = if (partialTimes.size > 1) {
            partialTimes.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0.0
        } else 0.0

        val summary = JSONObject().apply {
            put("partials", partialCount)
            put("finals", finalCount)
            put("translations", translationCount)
            put("first_partial_s", firstPartialS ?: 0.0)
            put("avg_partial_gap_s", round2(avgGap))
            put("max_partial_gap_s", round2(maxGap))
        }

        val data = JSONObject().apply {
            put("audio_duration_s", round1(audioDurationS))
            put("wall_time_s", round1(wallTimeS))
            put("events", eventsArray)
            put("summary", summary)
        }

        LogCollector.i(TAG, "Test complete: wall=${fmt(wallTimeS, 1)}s partials=$partialCount finals=$finalCount translations=$translationCount firstPartial=${firstPartialS?.let { fmt(it) } ?: "none"}")
        AdbResultWriter.writeSuccess(context, commandId, "test_streaming", data)
    }
}
