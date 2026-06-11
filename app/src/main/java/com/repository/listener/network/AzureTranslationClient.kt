package com.repository.listener.network

import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageConfig
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageResult
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechRecognizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import com.microsoft.cognitiveservices.speech.translation.SpeechTranslationConfig
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognizer
import com.repository.listener.util.LogCollector
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around Azure Speech Translation SDK.
 * fromLang / toLang use BCP-47 codes (e.g. "en-US", "ru-RU" for fromLang;
 * Azure target codes are typically the short form like "ru", "en", "zh-Hans").
 */
class AzureTranslationClient(
    private val key: String,
    private val region: String
) {
    companion object {
        private const val TAG = "AzureTranslation"
        private const val START_TIMEOUT_MS = 10_000L
        private const val STOP_TIMEOUT_MS = 3_000L
        // 16kHz mono 16-bit PCM = 32000 bytes/sec. 320_000 bytes = ~10s of audio.
        // Hard cap on the pre-start buffer so a stuck/never-connecting recognizer
        // can't let buffered PCM grow without bound.
        private const val PRESTART_MAX_BYTES = 320_000
    }

    private var config: SpeechTranslationConfig? = null
    private var pushStream: PushAudioInputStream? = null
    private var audioConfig: AudioConfig? = null
    private var recognizer: TranslationRecognizer? = null
    // Recognition-only (no translation) path used by the Assistant feature.
    private var speechConfig: SpeechConfig? = null
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile private var targetLang: String = ""

    // Serializes pushPcm direct-writes against the start() flush so buffered
    // frames always reach the recognizer ahead of any live frame.
    private val pushLock = Any()
    // PCM captured before the recognizer goes live (during Azure SDK init). Held
    // in FIFO order and flushed into pushStream the instant the recognizer is
    // alive, so the user's first words aren't lost. Guarded by pushLock.
    private val preStartFrames = java.util.ArrayDeque<ByteArray>()
    private var preStartBytes = 0

    // Debug counters: aggregate audio + recognition events over a 1s window so
    // we can confirm at a glance whether (a) PCM is reaching the SDK and (b) the
    // SDK is acknowledging it via partial/final events. Logged from pushPcm so
    // the cadence matches incoming audio without a separate timer.
    @Volatile private var statsWindowStartMs: Long = 0L
    @Volatile private var statsBytesPushed: Long = 0L
    @Volatile private var statsPushCalls: Int = 0
    @Volatile private var statsPartialEvents: Int = 0
    @Volatile private var statsFinalEvents: Int = 0
    @Volatile private var sessionConnected: Boolean = false
    // Set false on stop() so SDK callbacks that race with teardown drop their
    // payload instead of touching freed JNI handles or invoking listener code
    // whose receivers (BT host, coroutine scope) may be torn down.
    @Volatile private var alive: Boolean = false

    fun start(
        fromLang: String,
        toLang: String,
        onPartial: (String, String) -> Unit,
        onFinal: (String, String) -> Unit
    ) {
        try {
            targetLang = toLang
            // Sanity-log credentials presence (NOT the key itself) so a misconfigured
            // AppConfig is obvious in logcat instead of silently failing inside the SDK.
            val keyLen = key.length
            val keyMasked = if (keyLen >= 8) "${key.take(4)}...${key.takeLast(4)}" else "<too-short>"
            LogCollector.i(TAG, "start(): keyLen=$keyLen ($keyMasked) region='$region' from=$fromLang to=$toLang")
            if (key.isBlank() || region.isBlank()) {
                LogCollector.e(TAG, "start(): MISSING Azure key/region -- recognizer will not connect")
            }

            val cfg = SpeechTranslationConfig.fromSubscription(key, region)
            cfg.speechRecognitionLanguage = fromLang
            cfg.addTargetLanguage(toLang)
            // Continuous-translation tuning. Defaults are tuned for short
            // command-style utterances and stop emitting after ~500ms silence,
            // which makes the experience feel like translation "died" mid-talk.
            // Bump segmentation + initial silence so a natural pause between
            // sentences doesn't terminate recognition for that segment.
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.Speech_SegmentationSilenceTimeoutMs,
                "1500"
            )
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs,
                "20000"
            )
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.SpeechServiceConnection_EndSilenceTimeoutMs,
                "5000"
            )
            // Dictation mode for free-form speech (vs. short command default).
            cfg.enableDictation()
            config = cfg

            val format = AudioStreamFormat.getWaveFormatPCM(16000, 16, 1)
            val stream = PushAudioInputStream.create(format)
            pushStream = stream
            val ac = AudioConfig.fromStreamInput(stream)
            audioConfig = ac

            val rec = TranslationRecognizer(cfg, ac)
            recognizer = rec

            rec.recognizing.addEventListener { _, e ->
                if (!alive) return@addEventListener
                val src = e.result.text ?: ""
                val tgt = e.result.translations?.get(targetLang) ?: ""
                statsPartialEvents++
                LogCollector.i(TAG, "recognizing: srcLen=${src.length} tgtLen=${tgt.length} reason=${e.result.reason}")
                if (src.isNotEmpty() || tgt.isNotEmpty()) {
                    try { onPartial(src, tgt) } catch (t: Throwable) {
                        LogCollector.w(TAG, "onPartial threw: ${t.message}")
                    }
                }
            }
            rec.recognized.addEventListener { _, e ->
                if (!alive) return@addEventListener
                val src = e.result.text ?: ""
                val tgt = e.result.translations?.get(targetLang) ?: ""
                statsFinalEvents++
                LogCollector.i(TAG, "recognized: srcLen=${src.length} tgtLen=${tgt.length} reason=${e.result.reason}")
                if (src.isNotEmpty() || tgt.isNotEmpty()) {
                    try { onFinal(src, tgt) } catch (t: Throwable) {
                        LogCollector.w(TAG, "onFinal threw: ${t.message}")
                    }
                }
            }
            rec.canceled.addEventListener { _, e ->
                sessionConnected = false
                LogCollector.e(TAG, "Recognizer CANCELED: reason=${e.reason} errorCode=${e.errorCode} details=${e.errorDetails}")
            }
            rec.sessionStarted.addEventListener { _, _ ->
                sessionConnected = true
                LogCollector.i(TAG, "SDK CONNECTED -- sessionStarted (from=$fromLang to=$toLang)")
            }
            rec.sessionStopped.addEventListener { _, _ ->
                sessionConnected = false
                LogCollector.i(TAG, "SDK DISCONNECTED -- sessionStopped")
            }
            rec.speechStartDetected.addEventListener { _, _ ->
                LogCollector.i(TAG, "SDK speechStartDetected -- voice activity reached recognizer")
            }
            rec.speechEndDetected.addEventListener { _, _ ->
                LogCollector.i(TAG, "SDK speechEndDetected")
            }

            // Bound the start so a stuck network/auth handshake can't wedge the
            // service thread forever. If we time out, treat as failure.
            rec.startContinuousRecognitionAsync().get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Flush any PCM buffered during init and arm the live path inside the
            // same lock so a concurrent pushPcm can't write a live frame ahead of
            // the buffered (earlier) frames.
            synchronized(pushLock) {
                val ps = pushStream
                if (ps != null) {
                    var flushed = 0
                    while (preStartFrames.isNotEmpty()) {
                        val f = preStartFrames.pollFirst()
                        try { ps.write(f) } catch (t: Throwable) { LogCollector.w(TAG, "preStart flush write failed: ${t.message}") }
                        flushed += f.size
                    }
                    preStartBytes = 0
                    if (flushed > 0) LogCollector.i(TAG, "[Azure] flushed ${flushed} preStart bytes (~${flushed / 32} ms) into recognizer")
                }
                alive = true
            }
            LogCollector.i(TAG, "Azure recognizer started (startContinuousRecognitionAsync returned). connectedSoFar=$sessionConnected")
        } catch (t: Throwable) {
            val cause = generateSequence(t) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            LogCollector.e(TAG, "Failed to start Azure recognizer: $cause")
            stop()
            throw t
        }
    }

    /**
     * Two-way / bidirectional translation. Auto-detects which of the two source
     * languages is spoken on each utterance and produces translations into BOTH
     * targets. Caller routes results based on [detectedLang] (the BCP-47 source
     * language Azure picked) and [translations] map (target short code -> text).
     *
     * sourceLangsBcp47: e.g. ["en-US", "ru-RU"] -- both languages we expect.
     * targetCodes: short codes corresponding to the OTHER direction for each
     *   source, e.g. ["en", "ru"]. We add both as targets so the recognizer
     *   produces translations for whichever direction Azure detected.
     */
    fun startTwoWay(
        sourceLangsBcp47: List<String>,
        targetCodes: List<String>,
        onPartial: (src: String, translations: Map<String, String>, detectedLang: String) -> Unit,
        onFinal: (src: String, translations: Map<String, String>, detectedLang: String) -> Unit
    ) {
        try {
            val keyLen = key.length
            val keyMasked = if (keyLen >= 8) "${key.take(4)}...${key.takeLast(4)}" else "<too-short>"
            LogCollector.i(TAG, "startTwoWay(): keyLen=$keyLen ($keyMasked) region='$region' sources=$sourceLangsBcp47 targets=$targetCodes")
            if (key.isBlank() || region.isBlank()) {
                LogCollector.e(TAG, "startTwoWay(): MISSING Azure key/region")
            }

            // Multi-source-language translation requires the v2/universal endpoint;
            // the default (v1) endpoint that fromSubscription() picks rejects the
            // request with HTTP 400 when AutoDetectSourceLanguageConfig is attached.
            val v2Endpoint = java.net.URI.create("wss://$region.stt.speech.microsoft.com/speech/universal/v2")
            val cfg = SpeechTranslationConfig.fromEndpoint(v2Endpoint, key)
            // No fixed speechRecognitionLanguage -- AutoDetectSourceLanguageConfig drives it.
            for (t in targetCodes) cfg.addTargetLanguage(t)
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.Speech_SegmentationSilenceTimeoutMs,
                "1500"
            )
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs,
                "20000"
            )
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.SpeechServiceConnection_EndSilenceTimeoutMs,
                "5000"
            )
            // Continuous language ID -- without this, AutoDetect locks on the
            // first detected language and won't switch back, so the second
            // speaker's tongue never gets recognized. Continuous is supported
            // on the v2/universal endpoint we're using above.
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.SpeechServiceConnection_LanguageIdMode,
                "Continuous"
            )
            cfg.enableDictation()
            config = cfg

            val format = AudioStreamFormat.getWaveFormatPCM(16000, 16, 1)
            val stream = PushAudioInputStream.create(format)
            pushStream = stream
            val ac = AudioConfig.fromStreamInput(stream)
            audioConfig = ac

            val autoDetect = AutoDetectSourceLanguageConfig.fromLanguages(sourceLangsBcp47)
            val rec = TranslationRecognizer(cfg, autoDetect, ac)
            recognizer = rec

            fun extractDetected(result: com.microsoft.cognitiveservices.speech.translation.TranslationRecognitionResult): String {
                return try {
                    AutoDetectSourceLanguageResult.fromResult(result).language ?: ""
                } catch (t: Throwable) {
                    LogCollector.w(TAG, "extractDetected failed: ${t.message}")
                    ""
                }
            }

            fun translationsToMap(e: com.microsoft.cognitiveservices.speech.translation.TranslationRecognitionEventArgs): Map<String, String> {
                val raw = e.result.translations ?: return emptyMap()
                // raw is java.util.Map<String, String>; copy into Kotlin Map.
                val m = HashMap<String, String>(targetCodes.size)
                for (t in targetCodes) {
                    val v = raw[t]
                    if (v != null) m[t] = v
                }
                return m
            }

            rec.recognizing.addEventListener { _, e ->
                if (!alive) return@addEventListener
                val src = e.result.text ?: ""
                val translations = translationsToMap(e)
                val detected = extractDetected(e.result)
                statsPartialEvents++
                LogCollector.i(TAG, "recognizing[2way]: srcLen=${src.length} det=$detected reason=${e.result.reason}")
                if (src.isNotEmpty() || translations.isNotEmpty()) {
                    try { onPartial(src, translations, detected) } catch (t: Throwable) {
                        LogCollector.w(TAG, "onPartial threw: ${t.message}")
                    }
                }
            }
            rec.recognized.addEventListener { _, e ->
                if (!alive) return@addEventListener
                val src = e.result.text ?: ""
                val translations = translationsToMap(e)
                val detected = extractDetected(e.result)
                statsFinalEvents++
                LogCollector.i(TAG, "recognized[2way]: srcLen=${src.length} det=$detected reason=${e.result.reason}")
                if (src.isNotEmpty() || translations.isNotEmpty()) {
                    try { onFinal(src, translations, detected) } catch (t: Throwable) {
                        LogCollector.w(TAG, "onFinal threw: ${t.message}")
                    }
                }
            }
            rec.canceled.addEventListener { _, e ->
                sessionConnected = false
                LogCollector.e(TAG, "Recognizer[2way] CANCELED: reason=${e.reason} errorCode=${e.errorCode} details=${e.errorDetails}")
            }
            rec.sessionStarted.addEventListener { _, _ ->
                sessionConnected = true
                LogCollector.i(TAG, "SDK[2way] CONNECTED -- sessionStarted (sources=$sourceLangsBcp47)")
            }
            rec.sessionStopped.addEventListener { _, _ ->
                sessionConnected = false
                LogCollector.i(TAG, "SDK[2way] DISCONNECTED -- sessionStopped")
            }

            rec.startContinuousRecognitionAsync().get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            alive = true
            LogCollector.i(TAG, "Azure two-way recognizer started. connectedSoFar=$sessionConnected")
        } catch (t: Throwable) {
            val cause = generateSequence(t) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            LogCollector.e(TAG, "Failed to start two-way Azure recognizer: $cause")
            stop()
            throw t
        }
    }

    /**
     * Transcription-only continuous recognition (no translation). Used by the
     * Assistant feature where we want the raw text of each speaker in a single
     * language. recognitionLang is BCP-47 (e.g. "en-US", "ru-RU"). onPartial /
     * onFinal receive the recognized text.
     */
    fun startRecognition(
        recognitionLang: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit
    ) {
        try {
            val keyLen = key.length
            val keyMasked = if (keyLen >= 8) "${key.take(4)}...${key.takeLast(4)}" else "<too-short>"
            LogCollector.i(TAG, "startRecognition(): keyLen=$keyLen ($keyMasked) region='$region' lang=$recognitionLang")
            if (key.isBlank() || region.isBlank()) {
                LogCollector.e(TAG, "startRecognition(): MISSING Azure key/region -- recognizer will not connect")
            }

            val cfg = SpeechConfig.fromSubscription(key, region)
            cfg.speechRecognitionLanguage = recognitionLang
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.Speech_SegmentationSilenceTimeoutMs,
                "1500"
            )
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs,
                "20000"
            )
            cfg.setProperty(
                com.microsoft.cognitiveservices.speech.PropertyId.SpeechServiceConnection_EndSilenceTimeoutMs,
                "5000"
            )
            cfg.enableDictation()
            speechConfig = cfg

            val format = AudioStreamFormat.getWaveFormatPCM(16000, 16, 1)
            val stream = PushAudioInputStream.create(format)
            pushStream = stream
            val ac = AudioConfig.fromStreamInput(stream)
            audioConfig = ac

            val rec = SpeechRecognizer(cfg, ac)
            speechRecognizer = rec

            rec.recognizing.addEventListener { _, e ->
                if (!alive) return@addEventListener
                val src = e.result.text ?: ""
                statsPartialEvents++
                if (src.isNotEmpty()) {
                    try { onPartial(src) } catch (t: Throwable) {
                        LogCollector.w(TAG, "onPartial threw: ${t.message}")
                    }
                }
            }
            rec.recognized.addEventListener { _, e ->
                if (!alive) return@addEventListener
                val src = e.result.text ?: ""
                statsFinalEvents++
                LogCollector.i(TAG, "recognized[recog]: srcLen=${src.length} reason=${e.result.reason}")
                if (src.isNotEmpty()) {
                    try { onFinal(src) } catch (t: Throwable) {
                        LogCollector.w(TAG, "onFinal threw: ${t.message}")
                    }
                }
            }
            rec.canceled.addEventListener { _, e ->
                sessionConnected = false
                LogCollector.e(TAG, "Recognizer[recog] CANCELED: reason=${e.reason} errorCode=${e.errorCode} details=${e.errorDetails}")
            }
            rec.sessionStarted.addEventListener { _, _ ->
                sessionConnected = true
                LogCollector.i(TAG, "SDK[recog] CONNECTED -- sessionStarted (lang=$recognitionLang)")
            }
            rec.sessionStopped.addEventListener { _, _ ->
                sessionConnected = false
                LogCollector.i(TAG, "SDK[recog] DISCONNECTED -- sessionStopped")
            }

            rec.startContinuousRecognitionAsync().get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            synchronized(pushLock) {
                val ps = pushStream
                if (ps != null) {
                    var flushed = 0
                    while (preStartFrames.isNotEmpty()) {
                        val f = preStartFrames.pollFirst()
                        try { ps.write(f) } catch (t: Throwable) { LogCollector.w(TAG, "preStart flush write failed: ${t.message}") }
                        flushed += f.size
                    }
                    preStartBytes = 0
                    if (flushed > 0) LogCollector.i(TAG, "[Azure] flushed ${flushed} preStart bytes into recognizer")
                }
                alive = true
            }
            LogCollector.i(TAG, "Azure recognition started (lang=$recognitionLang). connectedSoFar=$sessionConnected")
        } catch (t: Throwable) {
            val cause = generateSequence(t) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            LogCollector.e(TAG, "Failed to start Azure recognition: $cause")
            stop()
            throw t
        }
    }

    fun pushPcm(bytes: ByteArray) {
        synchronized(pushLock) {
            val ps = pushStream
            if (alive && ps != null) {
                try {
                    ps.write(bytes)
                    statsPushCalls++
                    statsBytesPushed += bytes.size
                    val now = System.currentTimeMillis()
                    if (statsWindowStartMs == 0L) statsWindowStartMs = now
                    val elapsed = now - statsWindowStartMs
                    if (elapsed >= 1000L) {
                        // 16kHz mono 16-bit = 32000 bytes/sec at full saturation. So a
                        // healthy stream should report ~32k bytes per 1s window.
                        LogCollector.i(
                            TAG,
                            "audio[1s]: bytes=$statsBytesPushed pushes=$statsPushCalls partials=$statsPartialEvents finals=$statsFinalEvents connected=$sessionConnected"
                        )
                        statsWindowStartMs = now
                        statsBytesPushed = 0
                        statsPushCalls = 0
                        statsPartialEvents = 0
                        statsFinalEvents = 0
                    }
                } catch (t: Throwable) {
                    LogCollector.e(TAG, "pushPcm failed: ${t.message}")
                }
            } else {
                // Recognizer not live yet -- buffer so the user's first words aren't
                // lost during Azure SDK init. Copy: the caller may reuse the array.
                preStartFrames.addLast(bytes.copyOf())
                preStartBytes += bytes.size
                while (preStartBytes > PRESTART_MAX_BYTES) {
                    val old = preStartFrames.pollFirst() ?: break
                    preStartBytes -= old.size
                }
            }
        }
    }

    fun stop() {
        // Mark dead first so any in-flight SDK callbacks short-circuit before
        // touching state we are about to free.
        alive = false
        // Drop any PCM buffered during init so a dead session's audio can't leak
        // into a later session that reuses this client.
        synchronized(pushLock) {
            preStartFrames.clear()
            preStartBytes = 0
        }
        try {
            // Bounded wait: the SDK's stop future has been observed to hang
            // indefinitely when the connection is in a bad state. We accept
            // a small leak in that pathological case rather than wedging the
            // caller (which is usually the foreground service main thread).
            recognizer?.stopContinuousRecognitionAsync()?.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            LogCollector.w(TAG, "stopContinuousRecognition error/timeout: ${t.message}")
        }
        try {
            speechRecognizer?.stopContinuousRecognitionAsync()?.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            LogCollector.w(TAG, "stopContinuousRecognition[recog] error/timeout: ${t.message}")
        }
        try { pushStream?.close() } catch (_: Throwable) {}
        try { recognizer?.close() } catch (_: Throwable) {}
        try { speechRecognizer?.close() } catch (_: Throwable) {}
        try { audioConfig?.close() } catch (_: Throwable) {}
        try { config?.close() } catch (_: Throwable) {}
        try { speechConfig?.close() } catch (_: Throwable) {}
        recognizer = null
        speechRecognizer = null
        pushStream = null
        audioConfig = null
        config = null
        speechConfig = null
        sessionConnected = false
        LogCollector.i(TAG, "Azure recognizer closed")
    }
}
