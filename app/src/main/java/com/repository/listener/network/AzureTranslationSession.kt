package com.repository.listener.network

import com.repository.listener.util.LogCollector

/**
 * Owns an [AzureTranslationClient] and forwards results.
 *
 * Audio is pushed in via [pushPcm] from whichever source ListenerService is configured
 * for (glasses-mic decoded PCM, or system-audio capturer). Results are delivered via
 * the provided callbacks -- callers should forward them into the same channels used
 * by the default-provider TranslationClient flow (e.g. phoneBtHost.sendTranslationResultToApp).
 *
 * fromLang must be BCP-47 (e.g. "en-US"). toLang is the short Azure target language code
 * (e.g. "ru", "en", "zh-Hans").
 */
class AzureTranslationSession(
    private val key: String,
    private val region: String
) {
    companion object {
        private const val TAG = "AzureTranslation"
    }

    private var client: AzureTranslationClient? = AzureTranslationClient(key, region)
    @Volatile private var running = false
    @Volatile private var currentSource: String = "glasses"

    fun start(
        fromLang: String,
        toLang: String,
        onPartial: (src: String, translation: String) -> Unit,
        onFinal: (src: String, translation: String) -> Unit
    ) {
        if (running) {
            LogCollector.w(TAG, "Session already running, ignoring start")
            return
        }
        LogCollector.i(TAG, "Starting Azure session from=$fromLang to=$toLang region=$region")
        if (client == null) {
            client = AzureTranslationClient(key, region)
        }
        client?.start(fromLang, toLang, onPartial, onFinal)
        running = true
    }

    fun startTwoWay(
        sourceLangsBcp47: List<String>,
        targetCodes: List<String>,
        onPartial: (src: String, translations: Map<String, String>, detectedLang: String) -> Unit,
        onFinal: (src: String, translations: Map<String, String>, detectedLang: String) -> Unit
    ) {
        if (running) {
            LogCollector.w(TAG, "Session already running, ignoring startTwoWay")
            return
        }
        LogCollector.i(TAG, "Starting Azure two-way session sources=$sourceLangsBcp47 targets=$targetCodes region=$region")
        if (client == null) {
            client = AzureTranslationClient(key, region)
        }
        client?.startTwoWay(sourceLangsBcp47, targetCodes, onPartial, onFinal)
        running = true
    }

    /**
     * Transcription-only continuous recognition (no translation). Used by the
     * Assistant feature. recognitionLang is BCP-47 (e.g. "en-US").
     */
    fun startRecognition(
        recognitionLang: String,
        onPartial: (src: String) -> Unit,
        onFinal: (src: String) -> Unit
    ) {
        if (running) {
            LogCollector.w(TAG, "Session already running, ignoring startRecognition")
            return
        }
        LogCollector.i(TAG, "Starting Azure recognition lang=$recognitionLang region=$region")
        if (client == null) {
            client = AzureTranslationClient(key, region)
        }
        client?.startRecognition(recognitionLang, onPartial, onFinal)
        running = true
    }

    fun pushPcm(bytes: ByteArray) {
        // Do NOT gate on `running`: it only flips true after client.start() returns
        // (~1-2s of Azure SDK init). The client owns the alive/buffer logic and
        // buffers PCM during init, so forward whenever a client exists. After
        // stop() the client is nulled, so this no-ops.
        client?.pushPcm(bytes)
    }

    /**
     * Notify the session that the audio source changed (e.g. glasses<->system).
     * The push stream is source-agnostic, so we only need to record the new source
     * and let pushPcm continue feeding from the new producer. No recognizer teardown.
     */
    fun switchAudioSource(newSource: String) {
        if (!running) {
            LogCollector.w(TAG, "switchAudioSource called while not running, ignoring")
            return
        }
        if (newSource == currentSource) return
        LogCollector.i(TAG, "Azure session audio source switched: $currentSource -> $newSource (rebind without recognizer teardown)")
        currentSource = newSource
    }

    fun stop() {
        // Always tear down -- avoid JNI leaks even on partial-init failure.
        running = false
        try { client?.stop() } catch (t: Throwable) { LogCollector.w(TAG, "client.stop error: ${t.message}") }
        client = null
        LogCollector.i(TAG, "Azure session stopped")
    }

    val isRunning: Boolean get() = running
}
