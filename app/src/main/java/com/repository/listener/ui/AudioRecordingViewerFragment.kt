package com.repository.listener.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.repository.listener.R
import com.repository.listener.audio.AacPlaybackEngine
import com.repository.listener.audio.AudioFileManager
import com.repository.listener.audio.AudioRecording
import com.repository.listener.audio.VoiceSegmentData
import com.repository.listener.service.ListenerService
import com.repository.listener.ui.views.SpectrogramView
import com.repository.listener.util.LogCollector
import java.io.File

class AudioRecordingViewerFragment : Fragment() {

    companion object {
        private const val TAG = "AudioViewer"
        private const val ARG_AUDIO_PATH = "audio_path"
        private const val ARG_LIVE = "live"
        private const val PLAYBACK_UPDATE_INTERVAL_MS = 50L
        private const val LIVE_SPECTROGRAM_REFRESH_MS = 500L
        private const val FINALIZE_TIMEOUT_MS = 5000L

        fun newInstance(audioFilePath: String): AudioRecordingViewerFragment {
            return AudioRecordingViewerFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_AUDIO_PATH, audioFilePath)
                }
            }
        }

        fun newInstance(live: Boolean): AudioRecordingViewerFragment {
            return AudioRecordingViewerFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_LIVE, live)
                }
            }
        }
    }

    // -- State machine --

    private enum class ViewerState {
        IDLE,           // Ready, not playing (file mode default)
        PLAYING,        // AacPlaybackEngine active on finalized .aac file
        LIVE_FOLLOW,    // Live recording, auto-following head via AudioTrack
        LIVE_BROWSE,    // Live recording, user browsing (caret detached)
        LIVE_PLAYBACK   // Live recording, AacPlaybackEngine playing from browsed position
    }

    private var state = ViewerState.IDLE
    private val isLive get() = state == ViewerState.LIVE_FOLLOW
            || state == ViewerState.LIVE_BROWSE
            || state == ViewerState.LIVE_PLAYBACK

    // -- Core state --

    private var playbackEngine: AacPlaybackEngine? = null
    private var liveAudioTrack: android.media.AudioTrack? = null
    private var recording: AudioRecording? = null
    private var audioFileManager: AudioFileManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentPositionMs: Long = 0
    private var totalDurationMs: Long = 0
    private var segmentEndMs: Long = -1
    private var viewDestroyed = false
    private var selectionModeActive = false

    // -- Views --

    private lateinit var spectrogramView: SpectrogramView
    private lateinit var zoomSpectrogramView: SpectrogramView
    private lateinit var txtCurrentTime: TextView
    private lateinit var txtTotalTime: TextView
    private lateinit var txtViewerTitle: TextView
    private lateinit var btnViewerPin: ImageButton
    private lateinit var btnRename: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnPrev10s: MaterialButton
    private lateinit var btnNext10s: MaterialButton
    private lateinit var btnPrevVoice: MaterialButton
    private lateinit var btnNextVoice: MaterialButton
    private lateinit var transcriptionArea: View
    private lateinit var txtInlineTranscription: TextView
    private lateinit var btnTranscribe: MaterialButton
    private lateinit var segmentList: androidx.recyclerview.widget.RecyclerView
    private lateinit var segmentAdapter: VoiceSegmentAdapter

    // ==================== Playback updater (polls engine position) ====================

    private val playbackUpdater = object : Runnable {
        override fun run() {
            if (viewDestroyed) return
            if (state != ViewerState.PLAYING && state != ViewerState.LIVE_PLAYBACK) return
            val engine = playbackEngine ?: return
            try {
                val pos = engine.positionMs
                // Only update caret if engine is near where we expected.
                // If engine landed far away (e.g. file has less content than metadata),
                // freeze caret at user's tap position -- don't jump it around.
                val drift = kotlin.math.abs(pos - currentPositionMs)
                if (drift < 30_000) {
                    currentPositionMs = pos
                    spectrogramView.setPlaybackPosition(pos)
                    if (zoomSpectrogramView.visibility == View.VISIBLE) {
                        zoomSpectrogramView.setPlaybackPosition(pos)
                    }
                    txtCurrentTime.text = formatTime(pos)
                    updateTranscriptionForPosition(pos)
                }
                if (segmentEndMs > 0 && pos >= segmentEndMs) {
                    segmentEndMs = -1
                    transitionTo(if (isLive) ViewerState.LIVE_BROWSE else ViewerState.IDLE)
                    return
                }
            } catch (e: Exception) {
                LogCollector.d(TAG, "Playback updater error: ${e.message}")
            }
            handler.postDelayed(this, PLAYBACK_UPDATE_INTERVAL_MS)
        }
    }

    // ==================== Live spectrogram refresh ====================

    private val liveRefreshRunnable = object : Runnable {
        override fun run() {
            if (viewDestroyed || !isLive) return
            val archiver = ListenerService.audioArchiver
            if (archiver == null || !archiver.isRecording()) {
                transitionTo(ViewerState.IDLE)
                return
            }

            txtViewerTitle.text = if (archiver.isAudioFlowing()) "Live Recording" else "Audio paused"

            val ampData = archiver.getCurrentAmplitudeData()
            val segments = archiver.getCurrentVoiceSegments().map {
                VoiceSegmentData(it.startMs, it.endMs, it.transcription)
            }
            val durationMs = archiver.getCurrentDurationMs()
            totalDurationMs = durationMs

            if (ampData.isNotEmpty() && durationMs > 0) {
                spectrogramView.setData(ampData, segments, durationMs)
                if (state == ViewerState.LIVE_FOLLOW) {
                    // Always refit waveform to screen width as duration grows
                    spectrogramView.resetZoom()
                    currentPositionMs = durationMs
                    spectrogramView.setPlaybackPosition(durationMs)
                    txtCurrentTime.text = formatTime(durationMs)
                }
                txtTotalTime.text = formatTime(durationMs)
                segmentAdapter.setData(segments)
                updateTranscriptionForPosition(currentPositionMs)
                updateVoiceButtonState(segments)
            }
            handler.postDelayed(this, LIVE_SPECTROGRAM_REFRESH_MS)
        }
    }

    // ==================== State machine ====================

    private fun transitionTo(newState: ViewerState) {
        val old = state
        if (old == newState) return
        LogCollector.d(TAG, "State: $old -> $newState, pos=$currentPositionMs")

        // EXIT old state
        when (old) {
            ViewerState.PLAYING, ViewerState.LIVE_PLAYBACK -> {
                releaseEngine()
                handler.removeCallbacks(playbackUpdater)
            }
            ViewerState.LIVE_FOLLOW -> stopLiveAudioTrack()
            else -> {}
        }

        state = newState

        // ENTER new state
        when (newState) {
            ViewerState.IDLE -> {
                btnPlay.setIconResource(R.drawable.ic_play)
                if (old == ViewerState.LIVE_FOLLOW || old == ViewerState.LIVE_BROWSE || old == ViewerState.LIVE_PLAYBACK) {
                    releaseEngine()
                    handler.removeCallbacks(liveRefreshRunnable)
                    loadFinalizedRecording()
                }
            }
            ViewerState.PLAYING -> {
                if (!ensureEngine()) {
                    state = ViewerState.IDLE
                    btnPlay.setIconResource(R.drawable.ic_play)
                    Toast.makeText(requireContext(), "Audio file not available", Toast.LENGTH_SHORT).show()
                    return
                }
                playbackEngine?.seekTo(currentPositionMs)
                playbackEngine?.play()
                btnPlay.setIconResource(R.drawable.ic_pause)
                handler.post(playbackUpdater)
            }
            ViewerState.LIVE_FOLLOW -> {
                startLiveAudioTrack()
                btnPlay.setIconResource(R.drawable.ic_pause)
            }
            ViewerState.LIVE_BROWSE -> {
                btnPlay.setIconResource(R.drawable.ic_play)
            }
            ViewerState.LIVE_PLAYBACK -> {
                if (!ensureEngine()) {
                    state = ViewerState.LIVE_BROWSE
                    btnPlay.setIconResource(R.drawable.ic_play)
                    Toast.makeText(requireContext(), "Audio file not available", Toast.LENGTH_SHORT).show()
                    return
                }
                playbackEngine?.seekTo(currentPositionMs)
                playbackEngine?.play()
                btnPlay.setIconResource(R.drawable.ic_pause)
                handler.post(playbackUpdater)
            }
        }
    }

    // ==================== Playback engine management ====================

    private fun ensureEngine(): Boolean {
        val file = resolveAudioFile()
        if (file == null) {
            LogCollector.e(TAG, "ensureEngine: no audio file")
            return false
        }
        if (playbackEngine != null) return true

        LogCollector.d(TAG, "Creating AacPlaybackEngine for ${file.name} (${file.length()} bytes)")
        val engine = AacPlaybackEngine()
        engine.onComplete = {
            if (!viewDestroyed) {
                when (state) {
                    ViewerState.PLAYING -> transitionTo(ViewerState.IDLE)
                    ViewerState.LIVE_PLAYBACK -> transitionTo(ViewerState.LIVE_BROWSE)
                    else -> {}
                }
            }
        }
        if (!engine.prepare(file)) {
            LogCollector.e(TAG, "AacPlaybackEngine prepare failed")
            return false
        }
        playbackEngine = engine
        return true
    }

    private fun releaseEngine() {
        playbackEngine?.release()
        playbackEngine = null
    }

    private fun resolveAudioFile(): File? {
        recording?.audioFile?.let { f ->
            if (f.exists()) return f
        }
        val ctx = context ?: return null
        val dir = File(ctx.filesDir, "audio_recordings")
        return dir.listFiles { f -> f.extension == "aac" }?.maxByOrNull { it.lastModified() }
    }

    // ==================== Live AudioTrack (monitoring) ====================

    private fun startLiveAudioTrack() {
        val archiver = ListenerService.audioArchiver
        if (archiver == null || !archiver.isRecording()) return
        try {
            val sampleRate = 16000
            val minBuf = android.media.AudioTrack.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            liveAudioTrack = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(android.media.AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            liveAudioTrack?.play()
            archiver.monitorCallback = { samples ->
                try {
                    liveAudioTrack?.write(samples, 0, samples.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to start live AudioTrack: ${e.message}")
        }
    }

    private fun stopLiveAudioTrack() {
        ListenerService.audioArchiver?.monitorCallback = null
        try { liveAudioTrack?.stop() } catch (_: Exception) {}
        try { liveAudioTrack?.release() } catch (_: Exception) {}
        liveAudioTrack = null
    }

    // ==================== Seeking ====================

    private fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0, totalDurationMs.coerceAtLeast(1))
        currentPositionMs = clamped
        spectrogramView.setPlaybackPosition(clamped)
        if (zoomSpectrogramView.visibility == View.VISIBLE) {
            zoomSpectrogramView.setPlaybackPosition(clamped)
        }
        txtCurrentTime.text = formatTime(clamped)
        updateTranscriptionForPosition(clamped)

        if (state == ViewerState.LIVE_FOLLOW) {
            transitionTo(ViewerState.LIVE_BROWSE)
        }
        if (state == ViewerState.PLAYING || state == ViewerState.LIVE_PLAYBACK) {
            playbackEngine?.seekTo(clamped)
        }
    }

    private fun seekRelative(deltaMs: Long) {
        seekTo(currentPositionMs + deltaMs)
    }

    private fun seekToPrevVoice() {
        val segments = currentVoiceSegments()
        val prev = segments
            .filter { it.startMs < currentPositionMs - 500 }
            .maxByOrNull { it.startMs }
        if (prev != null) seekTo(prev.startMs)
    }

    private fun seekToNextVoice() {
        val segments = currentVoiceSegments()
        val next = segments
            .filter { it.startMs > currentPositionMs + 500 }
            .minByOrNull { it.startMs }
        if (next != null) seekTo(next.startMs)
    }

    private fun currentVoiceSegments(): List<VoiceSegmentData> {
        recording?.metadata?.voiceSegments?.let { return it }
        return ListenerService.audioArchiver?.getCurrentVoiceSegments()?.map {
            VoiceSegmentData(it.startMs, it.endMs, it.transcription)
        } ?: emptyList()
    }

    // ==================== Live -> File transition ====================

    private fun loadFinalizedRecording() {
        txtViewerTitle.text = "Recording complete"
        if (tryLoadRecording()) return

        val archiver = ListenerService.audioArchiver
        archiver?.onFinalized = { _ ->
            handler.post {
                if (viewDestroyed) return@post
                archiver.onFinalized = null
                tryLoadRecording()
            }
        }
        handler.postDelayed({
            if (viewDestroyed) return@postDelayed
            if (recording == null) {
                archiver?.onFinalized = null
                tryLoadRecording()
            }
        }, FINALIZE_TIMEOUT_MS)
    }

    private fun tryLoadRecording(): Boolean {
        if (viewDestroyed) return false
        val recordings = audioFileManager?.listRecordings() ?: return false
        val latest = recordings.firstOrNull() ?: return false
        recording = latest
        totalDurationMs = latest.metadata.durationMs
        txtViewerTitle.text = latest.metadata.name.ifEmpty { latest.audioFile.nameWithoutExtension }
        txtTotalTime.text = formatTime(totalDurationMs)

        val amplitudeData = audioFileManager?.loadAmplitudeData(latest.amplitudeFile) ?: ShortArray(0)
        if (amplitudeData.isNotEmpty()) {
            spectrogramView.setData(amplitudeData, latest.metadata.voiceSegments, totalDurationMs)
            spectrogramView.resetZoom()
        }
        segmentAdapter.setData(latest.metadata.voiceSegments)
        updateVoiceButtonState(latest.metadata.voiceSegments)

        spectrogramView.onTap = { ms, _, _ ->
            seekTo(ms)
            showInlineZoom(ms, amplitudeData, latest)
        }

        LogCollector.d(TAG, "Loaded finalized recording: ${latest.audioFile.name}")
        return true
    }

    // ==================== Fragment lifecycle ====================

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_audio_viewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewDestroyed = false
        val startAsLive = arguments?.getBoolean(ARG_LIVE, false) ?: false
        audioFileManager = AudioFileManager(requireContext())

        // Bind views
        spectrogramView = view.findViewById(R.id.spectrogramView)
        zoomSpectrogramView = view.findViewById(R.id.zoomSpectrogramView)
        txtCurrentTime = view.findViewById(R.id.txtCurrentTime)
        txtTotalTime = view.findViewById(R.id.txtTotalTime)
        txtViewerTitle = view.findViewById(R.id.txtViewerTitle)
        btnViewerPin = view.findViewById(R.id.btnViewerPin)
        btnRename = view.findViewById(R.id.btnRename)
        btnShare = view.findViewById(R.id.btnShare)
        btnPlay = view.findViewById(R.id.btnPlay)
        btnPrev10s = view.findViewById(R.id.btnPrev10s)
        btnNext10s = view.findViewById(R.id.btnNext10s)
        btnPrevVoice = view.findViewById(R.id.btnPrevVoice)
        btnNextVoice = view.findViewById(R.id.btnNextVoice)
        transcriptionArea = view.findViewById(R.id.transcriptionArea)
        txtInlineTranscription = view.findViewById(R.id.txtInlineTranscription)
        btnTranscribe = view.findViewById(R.id.btnTranscribe)
        zoomSpectrogramView.scrollEnabled = false
        zoomSpectrogramView.zoomEnabled = false

        segmentList = view.findViewById(R.id.segmentList)
        segmentAdapter = VoiceSegmentAdapter(
            onSegmentClick = { seg -> seekTo(seg.startMs) },
            onTranscribeClick = { seg -> transcribeSegment(seg) }
        )
        segmentList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        segmentList.adapter = segmentAdapter

        spectrogramView.onSeek = { ms -> seekTo(ms) }
        zoomSpectrogramView.onSeek = { ms -> seekTo(ms) }

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        btnViewerPin.setOnClickListener { toggleViewerPin() }
        btnRename.setOnClickListener { showRenameDialog() }
        btnShare.setOnClickListener { handleShareClick() }
        btnPrev10s.setOnClickListener { seekRelative(-10_000) }
        btnNext10s.setOnClickListener { seekRelative(10_000) }
        btnPrevVoice.setOnClickListener { seekToPrevVoice() }
        btnNextVoice.setOnClickListener { seekToNextVoice() }

        btnPlay.setOnClickListener {
            when (state) {
                ViewerState.IDLE -> transitionTo(ViewerState.PLAYING)
                ViewerState.PLAYING -> transitionTo(ViewerState.IDLE)
                ViewerState.LIVE_FOLLOW -> transitionTo(ViewerState.LIVE_BROWSE)
                ViewerState.LIVE_BROWSE -> transitionTo(ViewerState.LIVE_PLAYBACK)
                ViewerState.LIVE_PLAYBACK -> transitionTo(ViewerState.LIVE_BROWSE)
            }
        }

        if (startAsLive) {
            setupLiveMode()
        } else {
            setupFileMode()
        }
    }

    private fun setupFileMode() {
        val audioPath = arguments?.getString(ARG_AUDIO_PATH) ?: return
        val audioFile = File(audioPath)
        if (!audioFile.exists()) {
            LogCollector.e(TAG, "Recording file not found: $audioPath")
            return
        }

        val recordings = audioFileManager?.listRecordings() ?: return
        recording = recordings.find { it.audioFile.absolutePath == audioPath }
        val rec = recording ?: return

        totalDurationMs = rec.metadata.durationMs
        txtViewerTitle.text = rec.metadata.name.ifEmpty { audioFile.nameWithoutExtension }
        txtTotalTime.text = formatTime(totalDurationMs)
        btnViewerPin.setImageResource(if (rec.metadata.pinned) R.drawable.ic_star_filled else R.drawable.ic_star_outline)

        val amplitudeData = audioFileManager?.loadAmplitudeData(rec.amplitudeFile) ?: ShortArray(0)
        spectrogramView.setData(amplitudeData, rec.metadata.voiceSegments, rec.metadata.durationMs)
        segmentAdapter.setData(rec.metadata.voiceSegments)

        spectrogramView.onTap = { ms, _, _ ->
            seekTo(ms)
            showInlineZoom(ms, amplitudeData, rec)
        }
        zoomSpectrogramView.onTap = { ms, _, _ -> seekTo(ms) }

        updateVoiceButtonState(rec.metadata.voiceSegments)

        state = ViewerState.IDLE
        btnPlay.setIconResource(R.drawable.ic_play)
    }

    private fun setupLiveMode() {
        txtViewerTitle.text = "Live Recording"
        btnPrevVoice.isEnabled = false
        btnNextVoice.isEnabled = false

        spectrogramView.onTap = { ms, _, _ ->
            seekTo(ms)
            showLiveInlineZoom(ms)
        }
        zoomSpectrogramView.onTap = { ms, _, _ -> seekTo(ms) }

        transitionTo(ViewerState.LIVE_FOLLOW)
        handler.post(liveRefreshRunnable)
    }

    private fun showLiveInlineZoom(ms: Long) {
        val arch = ListenerService.audioArchiver ?: return
        val ampData = arch.getCurrentAmplitudeData()
        val segs = arch.getCurrentVoiceSegments().map {
            VoiceSegmentData(it.startMs, it.endMs, it.transcription)
        }
        val dur = arch.getCurrentDurationMs()
        if (ampData.isNotEmpty()) {
            val liveRec = AudioRecording(
                File(""), File(""), File(""),
                com.repository.listener.audio.RecordingMetadata("", "", dur, 16000, 32, "normal", segs)
            )
            showInlineZoom(ms, ampData, liveRec)
        }
    }

    // ==================== Voice button state ====================

    private fun updateVoiceButtonState(segments: List<VoiceSegmentData>) {
        val hasVoice = segments.isNotEmpty()
        btnPrevVoice.isEnabled = hasVoice
        btnNextVoice.isEnabled = hasVoice
        btnPrevVoice.alpha = if (hasVoice) 1f else 0.4f
        btnNextVoice.alpha = if (hasVoice) 1f else 0.4f
    }

    // ==================== Transcription ====================

    private fun updateTranscriptionForPosition(positionMs: Long) {
        val segments = currentVoiceSegments()
        val segment = segments.find { positionMs in it.startMs..it.endMs }
        if (segment != null) {
            val timeHeader = "${formatTime(segment.startMs)} - ${formatTime(segment.endMs)}"
            when {
                segment.transcription == "[transcribing...]" -> {
                    txtInlineTranscription.text = "$timeHeader\nTranscribing..."
                    btnTranscribe.visibility = View.GONE
                }
                segment.transcription.isNotEmpty() -> {
                    txtInlineTranscription.text = "$timeHeader\n${segment.transcription}"
                    btnTranscribe.visibility = View.GONE
                }
                else -> {
                    txtInlineTranscription.text = "$timeHeader\n(no transcription)"
                    btnTranscribe.visibility = View.VISIBLE
                    btnTranscribe.setOnClickListener { transcribeSegment(segment) }
                }
            }
        } else {
            txtInlineTranscription.text = ""
            btnTranscribe.visibility = View.GONE
        }
    }

    private fun transcribeSegment(segment: VoiceSegmentData) {
        val rec = recording ?: return
        val ctx = context ?: return
        btnTranscribe.isEnabled = false
        btnTranscribe.text = "Transcribing..."

        Thread {
            try {
                val audioFile = rec.audioFile
                val extractor = android.media.MediaExtractor()
                extractor.setDataSource(audioFile.absolutePath)
                if (extractor.trackCount == 0) { extractor.release(); return@Thread }
                extractor.selectTrack(0)
                val format = extractor.getTrackFormat(0)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return@Thread

                val decoder = android.media.MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()

                val allSamples = mutableListOf<Short>()
                val decInfo = android.media.MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val startUs = segment.startMs * 1000L
                val endUs = segment.endMs * 1000L
                var currentUs = 0L

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
                        if (decInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (decInfo.size > 0) {
                            val pcmBuf = decoder.getOutputBuffer(outIdx)!!
                            pcmBuf.position(decInfo.offset)
                            val shortBuf = pcmBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val samples = ShortArray(decInfo.size / 2)
                            shortBuf.get(samples)

                            val sampleDurationUs = (samples.size * 1_000_000L) / 16000
                            if (currentUs + sampleDurationUs >= startUs && currentUs <= endUs) {
                                allSamples.addAll(samples.toList())
                            }
                            currentUs += sampleDurationUs
                            if (currentUs > endUs) outputDone = true
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                    }
                }

                decoder.stop(); decoder.release(); extractor.release()

                if (allSamples.isEmpty()) {
                    handler.post {
                        if (viewDestroyed) return@post
                        btnTranscribe.text = "Transcribe"
                        btnTranscribe.isEnabled = true
                        txtInlineTranscription.text = "${formatTime(segment.startMs)} - ${formatTime(segment.endMs)}\n(no audio data)"
                    }
                    return@Thread
                }

                val whisper = com.repository.listener.audio.WhisperTranscriber(ctx)
                if (!whisper.isModelAvailable() || !whisper.initialize()) {
                    handler.post {
                        if (viewDestroyed) return@post
                        btnTranscribe.text = "Transcribe"
                        btnTranscribe.isEnabled = true
                        txtInlineTranscription.text = "${formatTime(segment.startMs)} - ${formatTime(segment.endMs)}\n(Whisper model not available)"
                    }
                    return@Thread
                }

                whisper.startUtterance()
                val shortArray = ShortArray(allSamples.size) { allSamples[it] }
                whisper.feedAudio(shortArray)
                val text = whisper.endUtterance()
                whisper.release()

                if (text.isNotEmpty()) {
                    val r = recording
                    if (r != null && r.metadataFile.exists()) {
                        try {
                            val json = org.json.JSONObject(r.metadataFile.readText())
                            val segs = json.optJSONArray("voiceSegments")
                            if (segs != null) {
                                for (i in 0 until segs.length()) {
                                    val s = segs.getJSONObject(i)
                                    if (s.getLong("startMs") == segment.startMs && s.getLong("endMs") == segment.endMs) {
                                        s.put("transcription", text)
                                        break
                                    }
                                }
                                json.put("voiceSegments", segs)
                                r.metadataFile.writeText(json.toString(2))
                            }
                        } catch (e: Exception) {
                            LogCollector.e(TAG, "Failed to save transcription: ${e.message}")
                        }
                    }
                }

                handler.post {
                    if (viewDestroyed) return@post
                    if (text.isNotEmpty()) {
                        val r = recording
                        if (r != null) {
                            val updatedSegs = r.metadata.voiceSegments.map {
                                if (it.startMs == segment.startMs && it.endMs == segment.endMs) {
                                    VoiceSegmentData(it.startMs, it.endMs, text)
                                } else it
                            }
                            recording = r.copy(metadata = r.metadata.copy(voiceSegments = updatedSegs))
                        }
                        txtInlineTranscription.text = "${formatTime(segment.startMs)} - ${formatTime(segment.endMs)}\n$text"
                        segmentAdapter.setData(recording?.metadata?.voiceSegments ?: emptyList())
                        val rec2 = recording
                        if (rec2 != null) {
                            spectrogramView.setData(spectrogramView.amplitudeData, rec2.metadata.voiceSegments, totalDurationMs)
                        }
                    } else {
                        txtInlineTranscription.text = "${formatTime(segment.startMs)} - ${formatTime(segment.endMs)}\n(Whisper returned empty)"
                    }
                    btnTranscribe.text = "Transcribe"
                    btnTranscribe.isEnabled = true
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Manual transcription failed: ${e.message}")
                handler.post {
                    if (viewDestroyed) return@post
                    btnTranscribe.text = "Transcribe"
                    btnTranscribe.isEnabled = true
                }
            }
        }.start()
    }

    // ==================== Inline zoom ====================

    private fun showInlineZoom(centerMs: Long, amplitudeData: ShortArray, rec: AudioRecording) {
        val viewWidth = if (zoomSpectrogramView.width > 0) zoomSpectrogramView.width
            else resources.displayMetrics.widthPixels
        val windowMs = 30_000f
        val zoomMsPerPx = windowMs / viewWidth

        zoomSpectrogramView.setZoomLevel(zoomMsPerPx)
        val scrollStart = (centerMs - 15_000L).coerceAtLeast(0).toFloat()
        zoomSpectrogramView.setScrollPosition(scrollStart)
        zoomSpectrogramView.setData(amplitudeData, rec.metadata.voiceSegments, rec.metadata.durationMs)
        zoomSpectrogramView.setPlaybackPosition(currentPositionMs)
        zoomSpectrogramView.visibility = View.VISIBLE
    }

    // ==================== Pin / Rename / Share ====================

    private fun toggleViewerPin() {
        val rec = recording ?: return
        val manager = audioFileManager ?: return
        val newPinned = manager.togglePin(rec)
        btnViewerPin.setImageResource(if (newPinned) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        val recordings = manager.listRecordings()
        recording = recordings.find { it.audioFile.absolutePath == rec.audioFile.absolutePath }
    }

    private fun showRenameDialog() {
        val rec = recording ?: return
        val editText = EditText(requireContext()).apply {
            setText(rec.metadata.name.ifEmpty { rec.audioFile.nameWithoutExtension })
            selectAll()
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Recording")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) saveRecordingName(newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveRecordingName(name: String) {
        val rec = recording ?: return
        if (rec.metadataFile.exists()) {
            try {
                val json = org.json.JSONObject(rec.metadataFile.readText())
                json.put("name", name)
                rec.metadataFile.writeText(json.toString(2))
                recording = rec.copy(metadata = rec.metadata.copy(name = name))
                txtViewerTitle.text = name
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to rename recording: ${e.message}")
            }
        }
    }

    private fun handleShareClick() {
        if (!selectionModeActive) {
            selectionModeActive = true
            spectrogramView.selectionMode = true
            spectrogramView.setSelectionAroundPosition(currentPositionMs)
            zoomSpectrogramView.selectionMode = true
            zoomSpectrogramView.setSelectionAroundPosition(currentPositionMs)
            Toast.makeText(requireContext(), "Drag handles to adjust selection, tap Share to send", Toast.LENGTH_SHORT).show()
            return
        }
        shareRecording()
        spectrogramView.selectionMode = false
        spectrogramView.clearSelection()
        zoomSpectrogramView.selectionMode = false
        zoomSpectrogramView.clearSelection()
        selectionModeActive = false
    }

    private fun shareRecording() {
        val rec = recording ?: return
        val selStart = spectrogramView.selectionStartMs
        val selEnd = spectrogramView.selectionEndMs
        val file = if (selStart >= 0 && selEnd > selStart) {
            extractAudioSegment(rec.audioFile, selStart, selEnd)
        } else {
            rec.audioFile
        }
        if (file == null || !file.exists()) return

        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", file
        )
        val displayName = recording?.metadata?.name?.ifEmpty { rec.audioFile.nameWithoutExtension }
            ?: rec.audioFile.nameWithoutExtension
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/aac"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, displayName)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share Recording"))
    }

    private fun extractAudioSegment(audioFile: File, startMs: Long, endMs: Long): File? {
        try {
            val outFile = File(requireContext().cacheDir, "shared_audio").also { it.mkdirs() }
                .let { File(it, "${recording?.metadata?.name?.ifEmpty { audioFile.nameWithoutExtension } ?: audioFile.nameWithoutExtension}_clip.aac") }

            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            if (extractor.trackCount == 0) { extractor.release(); return null }
            extractor.selectTrack(0)
            extractor.seekTo(startMs * 1000, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val out = java.io.BufferedOutputStream(java.io.FileOutputStream(outFile))
            val buffer = java.nio.ByteBuffer.allocate(65536)

            while (true) {
                val sampleTime = extractor.sampleTime / 1000
                if (sampleTime > endMs) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (sampleTime >= startMs) {
                    val frameData = ByteArray(size)
                    buffer.position(0)
                    buffer.get(frameData, 0, size)
                    val header = ByteArray(7)
                    val packetLen = size + 7
                    header[0] = 0xFF.toByte()
                    header[1] = 0xF1.toByte()
                    header[2] = 0x60.toByte()
                    header[3] = (0x40 or ((packetLen shr 11) and 0x03)).toByte()
                    header[4] = ((packetLen shr 3) and 0xFF).toByte()
                    header[5] = (((packetLen and 0x07) shl 5) or 0x1F).toByte()
                    header[6] = 0xFC.toByte()
                    out.write(header)
                    out.write(frameData)
                }
                extractor.advance()
            }

            out.flush()
            out.close()
            extractor.release()
            return outFile
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to extract segment: ${e.message}")
            return null
        }
    }

    // ==================== Utilities ====================

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    // ==================== Lifecycle ====================

    override fun onDestroyView() {
        super.onDestroyView()
        viewDestroyed = true
        handler.removeCallbacksAndMessages(null)
        stopLiveAudioTrack()
        releaseEngine()
        ListenerService.audioArchiver?.onFinalized = null
    }
}
