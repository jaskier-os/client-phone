package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.util.UUID

class GlassesAppsFragment : Fragment() {

    companion object {
        private const val TAG = "GlassesAppsFragment"
        private const val START_TIMEOUT_MS = 15_000L
        private const val STOP_TIMEOUT_MS = 10_000L
        private const val TRANSLATION_LOADING_TIMEOUT_MS = 10_000L
    }

    // --- Tile views ---

    private lateinit var tileRecordVideo: MaterialCardView
    private lateinit var tileRecordArScreen: MaterialCardView
    private lateinit var tileTranslation: MaterialCardView
    private lateinit var tileTeleprompter: MaterialCardView
    private lateinit var tileAudioRecordings: MaterialCardView
    private lateinit var tileMouse: MaterialCardView
    private lateinit var tileLoneMode: MaterialCardView
    private lateinit var tileCopilot: MaterialCardView
    private lateinit var txtMouseStatus: TextView
    private lateinit var txtCopilotStatus: TextView
    private var mouseActive = false
    private var copilotActive = false
    private var copilotConnecting = false

    // --- Recording views ---

    private lateinit var recordingStatusSection: View
    private lateinit var txtRecordingStatus: TextView
    private lateinit var txtRecordingTimer: TextView
    private lateinit var btnStopRecording: MaterialButton
    private lateinit var txtRecordingResult: TextView
    private lateinit var imgRecordingDot: ImageView

    // --- Translation views ---

    private lateinit var btnSwitchAudioSource: MaterialButton
    private lateinit var btnTwoWayTranslation: MaterialButton
    private lateinit var translationStatusSection: View
    private lateinit var txtTranslationStatus: TextView
    private lateinit var btnStopTranslation: MaterialButton
    private lateinit var txtTranslationResult: TextView
    private lateinit var translationLoadingOverlay: FrameLayout
    private lateinit var txtTranslationLoading: TextView

    // --- Teleprompter views ---

    private lateinit var teleprompterControlSection: View
    private lateinit var txtTeleprompterStatus: TextView
    private lateinit var progressTeleprompter: ProgressBar
    private lateinit var btnTeleprompterScrollBack: MaterialButton
    private lateinit var btnTeleprompterScrollFwd: MaterialButton
    private lateinit var btnTeleprompterPause: MaterialButton
    private lateinit var btnTeleprompterStop: MaterialButton
    private lateinit var txtTeleprompterResult: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var recording = false
    private var recordingStartTime = 0L
    private var activeCommandId: String? = null
    private var activeRecordType: String? = null
    private var waitingForStart = false
    private var translating = false

    // Teleprompter state
    private var teleprompterActive = false
    private var teleprompterPaused = false
    private var teleprompterCommandId: String? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
            txtRecordingTimer.text = "${elapsed}s"
            handler.postDelayed(this, 1000)
        }
    }

    private val startTimeoutRunnable = Runnable {
        if (waitingForStart && recording) {
            LogCollector.w(TAG, "Start timeout -- no response from glasses, resetting UI")
            onRecordingResult(false, "No response from glasses (timeout)")
        }
    }

    private val stopTimeoutRunnable = Runnable {
        if (recording) {
            LogCollector.w(TAG, "Stop timeout -- force resetting UI")
            onRecordingResult(false, "Stop timed out -- recording may still be active on glasses")
        }
    }

    private val translationLoadingTimeoutRunnable = Runnable {
        LogCollector.w(TAG, "Translation loading timeout -- hiding overlay")
        hideTranslationLoading()
    }

    private val twoWayClosedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            activity?.runOnUiThread {
                // Two-way screen was closed -- disable two-way and restart without it
                if (AppConfig.getTranslationTwoWay(requireContext())) {
                    AppConfig.setTranslationTwoWay(requireContext(), false)
                    updateTwoWayButtonText(false)
                    if (translating) {
                        val ctx = requireContext()
                        showTranslationLoading("Restarting translation...")
                        val stopIntent = Intent(ctx, ListenerService::class.java).apply {
                            action = ListenerService.ACTION_ADB_DISPATCH
                            putExtra("command_id", "ui_twoway_close_stop_${UUID.randomUUID().toString().take(8)}")
                            putExtra("type", "stop_translation")
                            putExtra("params", "{}")
                        }
                        ctx.startService(stopIntent)
                        translating = false
                        handler.postDelayed({
                            val params = JSONObject().apply {
                                put("from_language", AppConfig.getTranslationFromLanguage(ctx))
                                put("to_language", AppConfig.getTranslationToLanguage(ctx))
                                put("font_size", AppConfig.getTranslationFontSize(ctx))
                                put("audio_source", AppConfig.getTranslationAudioSource(ctx))
                                put("provider", AppConfig.getTranslationProvider(ctx))
                                put("two_way", false)
                            }
                            val startIntent = Intent(ctx, ListenerService::class.java).apply {
                                action = ListenerService.ACTION_ADB_DISPATCH
                                putExtra("command_id", "ui_twoway_close_start_${UUID.randomUUID().toString().take(8)}")
                                putExtra("type", "start_translation")
                                putExtra("params", params.toString())
                            }
                            ctx.startService(startIntent)
                            translating = true
                            updateTilesEnabled()
                            translationStatusSection.visibility = View.VISIBLE
                            val from = AppConfig.getTranslationFromLanguage(ctx).uppercase()
                            val to = AppConfig.getTranslationToLanguage(ctx).uppercase()
                            txtTranslationStatus.text = "Translating: $from -> $to"
                        }, 500)
                    }
                }
            }
        }
    }

    private val translationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = intent.getBooleanExtra(ListenerService.EXTRA_TRANSLATION_ACTIVE, false)
            activity?.runOnUiThread {
                if (active && !translating) {
                    hideTranslationLoading()
                    translating = true
                    updateTilesEnabled()
                    translationStatusSection.visibility = View.VISIBLE
                    txtTranslationResult.visibility = View.GONE
                    val from = AppConfig.getTranslationFromLanguage(requireContext()).uppercase()
                    val to = AppConfig.getTranslationToLanguage(requireContext()).uppercase()
                    val twoWay = AppConfig.getTranslationTwoWay(requireContext())
                    txtTranslationStatus.text = "Translating: $from -> $to" + if (twoWay) " (two-way)" else ""
                    updateTwoWayButtonText(twoWay)
                } else if (!active && translating) {
                    onTranslationStopped()
                }
            }
        }
    }

    private val copilotStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = intent.getBooleanExtra(ListenerService.EXTRA_COPILOT_ACTIVE, false)
            val status = intent.getStringExtra(ListenerService.EXTRA_COPILOT_STATUS)
            activity?.runOnUiThread {
                if (active) {
                    // Both recognizers ready -> Listening (green).
                    copilotConnecting = false
                    copilotActive = true
                } else if (status != null && status != "Start failed" && status != "Azure not configured") {
                    // active=false with a non-failure status (e.g. "Connecting...")
                    // -> keep the tile in the Connecting (orange) state.
                    copilotActive = true
                    copilotConnecting = true
                } else {
                    // active=false with no status (stop/cleared) or a failure status
                    // -> tile off.
                    copilotActive = false
                    copilotConnecting = false
                }
                updateCopilotTileVisual()
            }
        }
    }

    private val glassesStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(ListenerService.EXTRA_GLASSES_CONNECTED, false)
            activity?.runOnUiThread {
                updateTilesEnabled()
                if (!connected) {
                    onTranslationStopped()
                    resetTeleprompterUI()
                    mouseActive = false
                    updateMouseTileVisual()
                }
            }
        }
    }

    private var mouseHidConnected = false
    private var mouseHidDeviceName = ""

    private val mouseHidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mouseHidConnected = intent.getBooleanExtra(ListenerService.EXTRA_MOUSE_HID_CONNECTED, false)
            mouseHidDeviceName = intent.getStringExtra(ListenerService.EXTRA_MOUSE_HID_DEVICE_NAME) ?: ""
            activity?.runOnUiThread { updateMouseTileVisual() }
        }
    }

    private val recordingResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val commandId = intent.getStringExtra("command_id") ?: return
            if (commandId != activeCommandId) return
            val success = intent.getBooleanExtra("success", false)
            val message = intent.getStringExtra("message") ?: ""
            activity?.runOnUiThread {
                // If we get a result, cancel start timeout -- glasses responded
                handler.removeCallbacks(startTimeoutRunnable)
                waitingForStart = false
                if (intent.getBooleanExtra("started", false)) {
                    // Recording confirmed started on glasses -- update label
                    val label = if (activeRecordType == "record_ar_screen") "AR recording..." else "Recording..."
                    txtRecordingStatus.text = label
                } else {
                    onRecordingResult(success, message)
                }
            }
        }
    }

    private val teleprompterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val commandId = intent.getStringExtra("command_id") ?: return
            if (commandId != teleprompterCommandId) return
            val state = intent.getStringExtra("state") ?: return
            val progress = intent.getFloatExtra("progress", 0f)
            activity?.runOnUiThread {
                onTeleprompterState(state, progress)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_glasses_apps, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Tiles
        tileRecordVideo = view.findViewById(R.id.tileRecordVideo)
        tileRecordArScreen = view.findViewById(R.id.tileRecordArScreen)
        tileTranslation = view.findViewById(R.id.tileTranslation)
        tileTeleprompter = view.findViewById(R.id.tileTeleprompter)

        tileAudioRecordings = view.findViewById(R.id.tileAudioRecordings)
        tileMouse = view.findViewById(R.id.tileMouse)
        txtMouseStatus = view.findViewById(R.id.txtMouseStatus)
        tileLoneMode = view.findViewById(R.id.tileLoneMode)
        tileCopilot = view.findViewById(R.id.tileCopilot)
        txtCopilotStatus = view.findViewById(R.id.txtCopilotStatus)

        tileRecordVideo.setOnClickListener { gateTileLaunch { startRecording("record_video") } }
        tileRecordArScreen.setOnClickListener { gateTileLaunch { startRecording("record_ar_screen") } }
        tileTranslation.setOnClickListener { gateTileLaunch { showTranslationConfigDialog() } }
        tileTeleprompter.setOnClickListener { gateTileLaunch { showTeleprompterDialog() } }
        tileAudioRecordings.setOnClickListener {
            // Audio recordings is a local activity (no glasses round-trip); no gate.
            startActivity(Intent(requireContext(), AudioRecordingsActivity::class.java))
        }
        tileMouse.setOnClickListener {
            // stopMouse is phone-side cleanup (HID bridge) -- no glasses ping needed.
            // Only showMouseConfigDialog needs the gate (it sends start_mouse to glasses).
            if (mouseActive) stopMouse() else gateTileLaunch { showMouseConfigDialog() }
        }
        tileLoneMode.setOnClickListener { gateTileLaunch { showLoneModeDialog() } }
        tileCopilot.setOnClickListener {
            if (copilotActive) stopCopilot() else gateTileLaunch { showCopilotConfigDialog() }
        }

        // Recording status
        recordingStatusSection = view.findViewById(R.id.recordingStatusSection)
        txtRecordingStatus = view.findViewById(R.id.txtRecordingStatus)
        txtRecordingTimer = view.findViewById(R.id.txtRecordingTimer)
        btnStopRecording = view.findViewById(R.id.btnStopRecording)
        txtRecordingResult = view.findViewById(R.id.txtRecordingResult)
        imgRecordingDot = view.findViewById(R.id.imgRecordingDot)
        btnStopRecording.setOnClickListener { stopRecording() }

        // Translation status
        translationStatusSection = view.findViewById(R.id.translationStatusSection)
        txtTranslationStatus = view.findViewById(R.id.txtTranslationStatus)
        btnStopTranslation = view.findViewById(R.id.btnStopTranslation)
        btnSwitchAudioSource = view.findViewById(R.id.btnSwitchAudioSource)
        btnTwoWayTranslation = view.findViewById(R.id.btnTwoWayTranslation)
        txtTranslationResult = view.findViewById(R.id.txtTranslationResult)
        translationLoadingOverlay = view.findViewById(R.id.translationLoadingOverlay)
        txtTranslationLoading = view.findViewById(R.id.txtTranslationLoading)

        btnSwitchAudioSource.setOnClickListener {
            val currentSource = AppConfig.getTranslationAudioSource(requireContext())
            val newSource = if (currentSource == "glasses") "system" else "glasses"
            AppConfig.setTranslationAudioSource(requireContext(), newSource)

            val intent = Intent(requireContext(), ListenerService::class.java).apply {
                action = ListenerService.ACTION_ADB_DISPATCH
                putExtra("command_id", "ui_switch_audio_${UUID.randomUUID().toString().take(8)}")
                putExtra("type", "switch_audio_source")
                putExtra("params", JSONObject().put("audio_source", newSource).toString())
            }
            requireContext().startService(intent)

            updateSwitchButtonText(newSource)
        }
        btnTwoWayTranslation.setOnClickListener {
            val ctx = requireContext()
            val twoWay = !AppConfig.getTranslationTwoWay(ctx)
            AppConfig.setTranslationTwoWay(ctx, twoWay)
            updateTwoWayButtonText(twoWay)
            if (translating) {
                showTranslationLoading(if (twoWay) "Switching to two-way..." else "Restarting translation...")
                // Stop current, restart with new two-way flag
                val stopIntent = Intent(ctx, ListenerService::class.java).apply {
                    action = ListenerService.ACTION_ADB_DISPATCH
                    putExtra("command_id", "ui_twoway_stop_${UUID.randomUUID().toString().take(8)}")
                    putExtra("type", "stop_translation")
                    putExtra("params", "{}")
                }
                ctx.startService(stopIntent)
                translating = false
                handler.postDelayed({
                    val params = JSONObject().apply {
                        put("from_language", AppConfig.getTranslationFromLanguage(ctx))
                        put("to_language", AppConfig.getTranslationToLanguage(ctx))
                        put("font_size", AppConfig.getTranslationFontSize(ctx))
                        put("audio_source", AppConfig.getTranslationAudioSource(ctx))
                        put("provider", AppConfig.getTranslationProvider(ctx))
                        put("two_way", twoWay)
                    }
                    val startIntent = Intent(ctx, ListenerService::class.java).apply {
                        action = ListenerService.ACTION_ADB_DISPATCH
                        putExtra("command_id", "ui_twoway_start_${UUID.randomUUID().toString().take(8)}")
                        putExtra("type", "start_translation")
                        putExtra("params", params.toString())
                    }
                    ctx.startService(startIntent)
                    translating = true
                    updateTilesEnabled()
                    translationStatusSection.visibility = View.VISIBLE
                    val from = AppConfig.getTranslationFromLanguage(ctx).uppercase()
                    val to = AppConfig.getTranslationToLanguage(ctx).uppercase()
                    txtTranslationStatus.text = "Translating: $from -> $to" + if (twoWay) " (two-way)" else ""
                    // Open two-way screen when enabling
                    if (twoWay) {
                        TwoWayTranslationActivity.launchFromConfig(ctx)
                    }
                }, 500)
            }
        }
        btnStopTranslation.setOnClickListener { stopTranslation() }

        // Teleprompter status
        teleprompterControlSection = view.findViewById(R.id.teleprompterControlSection)
        txtTeleprompterStatus = view.findViewById(R.id.txtTeleprompterStatus)
        progressTeleprompter = view.findViewById(R.id.progressTeleprompter)
        btnTeleprompterScrollBack = view.findViewById(R.id.btnTeleprompterScrollBack)
        btnTeleprompterScrollFwd = view.findViewById(R.id.btnTeleprompterScrollFwd)
        btnTeleprompterPause = view.findViewById(R.id.btnTeleprompterPause)
        btnTeleprompterStop = view.findViewById(R.id.btnTeleprompterStop)
        txtTeleprompterResult = view.findViewById(R.id.txtTeleprompterResult)

        btnTeleprompterScrollBack.setOnClickListener {
            sendTeleprompterControl("scroll", scrollAmount = -300)
        }
        btnTeleprompterScrollFwd.setOnClickListener {
            sendTeleprompterControl("scroll", scrollAmount = 300)
        }
        btnTeleprompterPause.setOnClickListener {
            if (teleprompterPaused) {
                sendTeleprompterControl("resume")
            } else {
                sendTeleprompterControl("pause")
            }
        }
        btnTeleprompterStop.setOnClickListener {
            sendTeleprompterControl("stop")
        }

        updateTilesEnabled()
    }

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        ctx.registerReceiver(
            glassesStateReceiver,
            IntentFilter(ListenerService.ACTION_GLASSES_STATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            recordingResultReceiver,
            IntentFilter(ListenerService.ACTION_RECORDING_RESULT),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            teleprompterStateReceiver,
            IntentFilter(ListenerService.ACTION_TELEPROMPTER_STATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            translationStateReceiver,
            IntentFilter(ListenerService.ACTION_TRANSLATION_STATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            copilotStateReceiver,
            IntentFilter(ListenerService.ACTION_COPILOT_STATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            twoWayClosedReceiver,
            IntentFilter(TwoWayTranslationActivity.ACTION_TWO_WAY_CLOSED),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            mouseHidReceiver,
            IntentFilter(ListenerService.ACTION_MOUSE_HID_STATUS),
            Context.RECEIVER_NOT_EXPORTED
        )
        updateTilesEnabled()

        // Sync translation state on resume. TwoWayTranslationActivity may have
        // stopped translation and set twoWay=false while we were paused.
        val twoWay = AppConfig.getTranslationTwoWay(ctx)
        updateTwoWayButtonText(twoWay)
        // If we thought we were translating in two-way, but twoWay is now false,
        // the TwoWayTranslationActivity stopped the session. Reset UI.
        if (translating && !twoWay && translationStatusSection.visibility == View.VISIBLE) {
            // Give the stop broadcast a moment to arrive via the receiver
            handler.postDelayed({
                // If still showing as translating but twoWay is off, force reset
                if (translating && !AppConfig.getTranslationTwoWay(requireContext())) {
                    onTranslationStopped()
                }
            }, 800)
        }
    }

    override fun onPause() {
        super.onPause()
        val ctx = requireContext()
        ctx.unregisterReceiver(glassesStateReceiver)
        ctx.unregisterReceiver(recordingResultReceiver)
        ctx.unregisterReceiver(teleprompterStateReceiver)
        ctx.unregisterReceiver(translationStateReceiver)
        ctx.unregisterReceiver(copilotStateReceiver)
        ctx.unregisterReceiver(twoWayClosedReceiver)
        ctx.unregisterReceiver(mouseHidReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(startTimeoutRunnable)
        handler.removeCallbacks(stopTimeoutRunnable)
        handler.removeCallbacks(translationLoadingTimeoutRunnable)
    }

    // --- Tile enable/disable ---

    private fun setTileEnabled(tile: MaterialCardView, enabled: Boolean) {
        tile.isClickable = enabled
        tile.alpha = if (enabled) 1.0f else 0.5f
    }

    /**
     * Tiles always look clickable regardless of glasses-connected state -- the
     * reachability check now happens at click time via [gateTileLaunch]. We still
     * disable a tile while its own session is in flight (recording/translating/etc.)
     * to avoid double-launches.
     */
    private fun updateTilesEnabled() {
        setTileEnabled(tileRecordVideo, !recording)
        setTileEnabled(tileRecordArScreen, !recording)
        setTileEnabled(tileTranslation, true)
        setTileEnabled(tileTeleprompter, !teleprompterActive)
        setTileEnabled(tileMouse, true)
    }

    /**
     * Verify glasses reachability via a fresh BLE ping before running a tile-launch
     * action. If unreachable, show a Toast and bail. Runs [action] on the main thread.
     */
    private fun showLoneModeDialog() {
        LoneModeDialog().show(childFragmentManager, "lone_mode")
    }

    private fun gateTileLaunch(action: () -> Unit) {
        val host = ListenerService.phoneBtHostInstance
        if (host == null) {
            Toast.makeText(requireContext(), "Glasses unreachable", Toast.LENGTH_SHORT).show()
            return
        }
        host.runWithGlasses(
            sessionLabel = "tile_launch",
            onUnreachable = {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Glasses unreachable", Toast.LENGTH_SHORT).show()
                }
            },
        ) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isAdded) action()
            }
        }
    }

    // --- Recording ---

    private fun startRecording(type: String) {
        if (recording) return

        val commandId = "ui_${UUID.randomUUID().toString().take(8)}"
        activeCommandId = commandId
        activeRecordType = type

        recording = true
        waitingForStart = true
        recordingStartTime = System.currentTimeMillis()

        // Update UI
        updateTilesEnabled()
        recordingStatusSection.visibility = View.VISIBLE
        txtRecordingResult.visibility = View.GONE

        val label = if (type == "record_ar_screen") "Starting AR recording..." else "Starting video..."
        txtRecordingStatus.text = label
        txtRecordingTimer.text = "0s"
        handler.post(timerRunnable)

        // Timeout: if glasses don't respond within START_TIMEOUT_MS, reset UI
        handler.removeCallbacks(startTimeoutRunnable)
        handler.postDelayed(startTimeoutRunnable, START_TIMEOUT_MS)

        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", commandId)
            putExtra("type", type)
            putExtra("params", "{\"duration_seconds\": 0, \"manual_stop\": true}")
        }
        requireContext().startService(intent)

        LogCollector.i(TAG, "Started $type (id=$commandId)")
    }

    private fun stopRecording() {
        if (!recording) return
        val commandId = activeCommandId ?: return

        // Cancel start timeout if still pending
        handler.removeCallbacks(startTimeoutRunnable)

        txtRecordingStatus.text = "Stopping..."
        btnStopRecording.isEnabled = false

        // Force-reset UI after STOP_TIMEOUT_MS regardless of response
        handler.removeCallbacks(stopTimeoutRunnable)
        handler.postDelayed(stopTimeoutRunnable, STOP_TIMEOUT_MS)

        val stopId = "ui_stop_${UUID.randomUUID().toString().take(8)}"
        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", stopId)
            putExtra("type", "stop_recording")
            putExtra("params", "{\"original_command_id\": \"$commandId\"}")
        }
        requireContext().startService(intent)

        LogCollector.i(TAG, "Stop recording requested (original=$commandId)")
    }

    private fun onRecordingResult(success: Boolean, message: String) {
        recording = false
        waitingForStart = false
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(startTimeoutRunnable)
        handler.removeCallbacks(stopTimeoutRunnable)

        recordingStatusSection.visibility = View.GONE
        btnStopRecording.isEnabled = true
        updateTilesEnabled()

        txtRecordingResult.visibility = View.VISIBLE
        txtRecordingResult.text = if (success) {
            "Recording saved: $message"
        } else {
            "Recording failed: $message"
        }

        activeCommandId = null
        activeRecordType = null
    }

    // --- Translation ---

    private fun showTranslationConfigDialog() {
        val dialog = TranslationConfigDialog()
        dialog.setSessionActive(translating)
        dialog.setListener(object : TranslationConfigDialog.Listener {
            override fun onTranslationConfigReady(config: TranslationConfigDialog.TranslationConfig) {
                if (translating) restartTranslation(config) else startTranslation(config)
            }

            override fun onTwoWayTranslationRequested(
                from: TranslationConfigDialog.Language,
                to: TranslationConfigDialog.Language,
                fontSize: Int
            ) {
                // The activity owns the session lifecycle; no further bookkeeping here.
                TwoWayTranslationActivity.launch(requireContext(), from, to, fontSize)
            }
        })
        dialog.show(childFragmentManager, "translation_config")
    }

    private fun restartTranslation(config: TranslationConfigDialog.TranslationConfig) {
        LogCollector.i(TAG, "Restarting translation with new config: ${config.from.code} -> ${config.to.code}")
        showTranslationLoading("Restarting translation...")
        // Stop current session, then start new one after a short delay to let
        // the glasses-side teardown complete before the new session begins.
        stopTranslation()
        // Force-reset translating so startTranslation's guard doesn't reject
        // the new session (the broadcast-based reset may not arrive in time).
        translating = false
        handler.postDelayed({ startTranslation(config) }, 500)
    }

    private fun startTranslation(config: TranslationConfigDialog.TranslationConfig) {
        if (translating) return

        showTranslationLoading("Starting translation...")

        // Normal start resets two-way (user uses the toggle button to enable it)
        AppConfig.setTranslationTwoWay(requireContext(), false)

        translating = true
        updateTilesEnabled()
        translationStatusSection.visibility = View.VISIBLE
        txtTranslationResult.visibility = View.GONE
        txtTranslationStatus.text = "Translating: ${config.from.name} -> ${config.to.name}"
        updateSwitchButtonText(config.audioSource)
        updateTwoWayButtonText(false)

        val params = JSONObject().apply {
            put("from_language", config.from.code.lowercase())
            put("to_language", config.to.code.lowercase())
            put("from_nllb", config.from.nllbCode)
            put("to_nllb", config.to.nllbCode)
            put("font_size", config.fontSize)
            put("audio_source", config.audioSource)
            put("provider", config.provider)
        }

        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", "ui_trans_${UUID.randomUUID().toString().take(8)}")
            putExtra("type", "start_translation")
            putExtra("params", params.toString())
        }
        requireContext().startService(intent)

        LogCollector.i(TAG, "Start translation: ${config.from.code} -> ${config.to.code}")
    }

    private fun stopTranslation() {
        if (!translating) return

        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", "ui_trans_stop_${UUID.randomUUID().toString().take(8)}")
            putExtra("type", "stop_translation")
            putExtra("params", "{}")
        }
        requireContext().startService(intent)
        LogCollector.i(TAG, "Stop translation requested")
    }

    private fun onTranslationStopped() {
        if (!translating) return
        hideTranslationLoading()
        translating = false
        translationStatusSection.visibility = View.GONE
        updateTilesEnabled()
    }

    private var loadingShownAt = 0L
    private val hideLoadingRunnable = Runnable {
        translationLoadingOverlay.visibility = View.GONE
    }

    private fun showTranslationLoading(message: String) {
        txtTranslationLoading.text = message
        translationLoadingOverlay.visibility = View.VISIBLE
        loadingShownAt = System.currentTimeMillis()
        handler.removeCallbacks(translationLoadingTimeoutRunnable)
        handler.removeCallbacks(hideLoadingRunnable)
        handler.postDelayed(translationLoadingTimeoutRunnable, TRANSLATION_LOADING_TIMEOUT_MS)
    }

    private fun hideTranslationLoading() {
        handler.removeCallbacks(translationLoadingTimeoutRunnable)
        // Ensure overlay is visible for at least 500ms so user sees feedback
        val elapsed = System.currentTimeMillis() - loadingShownAt
        val minDisplay = 500L
        if (elapsed >= minDisplay) {
            translationLoadingOverlay.visibility = View.GONE
        } else {
            handler.postDelayed(hideLoadingRunnable, minDisplay - elapsed)
        }
    }

    private fun updateSwitchButtonText(currentSource: String) {
        btnSwitchAudioSource.text = if (currentSource == "glasses")
            "Switch to System Audio" else "Switch to Glasses Mic"
    }

    private fun updateTwoWayButtonText(twoWay: Boolean) {
        btnTwoWayTranslation.text = if (twoWay) "Disable Two-Way" else "Enable Two-Way"
    }

    // --- Mouse ---

    private fun showMouseConfigDialog() {
        val dialog = MouseConfigDialog()
        dialog.setListener(object : MouseConfigDialog.Listener {
            override fun onMouseDeviceSelected(sensitivityX: Int, sensitivityY: Int, device: android.bluetooth.BluetoothDevice) {
                startMouse(sensitivityX, sensitivityY, device.address)
            }
        })
        dialog.show(childFragmentManager, "mouse_config")
    }

    private fun startMouse(sensX: Int, sensY: Int, deviceAddress: String) {
        mouseActive = true
        val params = JSONObject().apply {
            put("sensitivity_x", sensX)
            put("sensitivity_y", sensY)
            put("device_address", deviceAddress)
        }
        val commandId = "ui_mouse_${UUID.randomUUID().toString().take(8)}"
        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", commandId)
            putExtra("type", "start_mouse")
            putExtra("params", params.toString())
        }
        requireContext().startService(intent)
        updateMouseTileVisual()
        LogCollector.i(TAG, "start_mouse sent (id=$commandId, sensX=$sensX, sensY=$sensY, device=$deviceAddress)")
    }

    private fun stopMouse() {
        mouseActive = false
        val commandId = "ui_mouse_${UUID.randomUUID().toString().take(8)}"
        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", commandId)
            putExtra("type", "stop_mouse")
            putExtra("params", "{}")
        }
        requireContext().startService(intent)
        updateMouseTileVisual()
        LogCollector.i(TAG, "stop_mouse sent (id=$commandId)")
    }

    private fun updateMouseTileVisual() {
        if (mouseActive) {
            tileMouse.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            tileMouse.strokeColor = requireContext().getColor(R.color.gbx_orange)
            txtMouseStatus.text = if (mouseHidConnected) "PC: $mouseHidDeviceName" else "Waiting for PC..."
            txtMouseStatus.visibility = View.VISIBLE
        } else {
            tileMouse.strokeWidth = 0
            txtMouseStatus.visibility = View.GONE
        }
    }

    // --- Copilot ---

    private fun showCopilotConfigDialog() {
        val dialog = CopilotConfigDialog()
        dialog.setListener(object : CopilotConfigDialog.Listener {
            override fun onCopilotConfigReady(config: CopilotConfigDialog.CopilotConfig) {
                startCopilot(config)
            }
        })
        dialog.show(childFragmentManager, "copilot_config")
    }

    private fun startCopilot(config: CopilotConfigDialog.CopilotConfig) {
        // Azure init takes several seconds -- show a "Connecting..." state and
        // only flip to "Listening..." when ListenerService broadcasts ready.
        copilotActive = true
        copilotConnecting = true
        updateCopilotTileVisual()

        val params = JSONObject().apply {
            put("wearer_lang", config.wearerLang.bcp47)
            put("interlocutor_lang", config.interlocutorLang.bcp47)
            put("interlocutor_source", config.interlocutorSource)
            put("model", config.model)
        }
        val commandId = "ui_copilot_${UUID.randomUUID().toString().take(8)}"
        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", commandId)
            putExtra("type", "start_assistant")
            putExtra("params", params.toString())
        }
        requireContext().startService(intent)
        LogCollector.i(TAG, "start_assistant sent (id=$commandId, wearer=${config.wearerLang.bcp47}, interlocutor=${config.interlocutorLang.bcp47}, source=${config.interlocutorSource})")
    }

    private fun stopCopilot() {
        copilotActive = false
        copilotConnecting = false
        updateCopilotTileVisual()
        val commandId = "ui_copilot_stop_${UUID.randomUUID().toString().take(8)}"
        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", commandId)
            putExtra("type", "stop_assistant")
            putExtra("params", "{}")
        }
        requireContext().startService(intent)
        LogCollector.i(TAG, "stop_assistant sent (id=$commandId)")
    }

    private fun updateCopilotTileVisual() {
        if (copilotActive) {
            tileCopilot.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            if (copilotConnecting) {
                // Orange while spinning up Azure, like the mouse "waiting" state.
                tileCopilot.strokeColor = requireContext().getColor(R.color.gbx_orange)
                txtCopilotStatus.text = "Connecting..."
            } else {
                tileCopilot.strokeColor = requireContext().getColor(R.color.gbx_green)
                txtCopilotStatus.text = "Listening..."
            }
            txtCopilotStatus.visibility = View.VISIBLE
        } else {
            tileCopilot.strokeWidth = 0
            txtCopilotStatus.visibility = View.GONE
        }
    }

    // --- Teleprompter ---

    private fun showTeleprompterDialog() {
        val dialog = TeleprompterInputDialog()
        dialog.setListener(object : TeleprompterInputDialog.Listener {
            override fun onTeleprompterTextReady(text: String, fontSize: Int, lang: String) {
                startTeleprompter(text, fontSize, lang)
            }
        })
        dialog.show(childFragmentManager, "teleprompter_input")
    }

    private fun startTeleprompter(text: String, fontSize: Int, lang: String = "ru") {
        val commandId = "ui_tp_${UUID.randomUUID().toString().take(8)}"
        teleprompterCommandId = commandId
        teleprompterActive = true
        teleprompterPaused = false

        updateTilesEnabled()
        teleprompterControlSection.visibility = View.VISIBLE
        txtTeleprompterResult.visibility = View.GONE
        txtTeleprompterStatus.text = "Starting teleprompter..."
        progressTeleprompter.progress = 0

        val params = JSONObject().apply {
            put("text", text)
            put("font_size", fontSize)
            put("speech_tracking", true)
            put("lang", lang)
        }
        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", commandId)
            putExtra("type", "start_teleprompter")
            putExtra("params", params.toString())
        }
        requireContext().startService(intent)

        LogCollector.i(TAG, "Teleprompter started (id=$commandId, fontSize=$fontSize, textLen=${text.length})")
    }

    private fun sendTeleprompterControl(action: String, scrollAmount: Int = 0) {
        val commandId = teleprompterCommandId ?: return
        val params = JSONObject().apply {
            put("action", action)
            put("original_command_id", commandId)
            if (scrollAmount != 0) put("scroll_amount", scrollAmount)
        }
        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            this.action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", "ui_tpc_${UUID.randomUUID().toString().take(8)}")
            putExtra("type", "teleprompter_control")
            putExtra("params", params.toString())
        }
        requireContext().startService(intent)
    }

    private fun onTeleprompterState(state: String, progress: Float) {
        when (state) {
            "running" -> {
                teleprompterPaused = false
                txtTeleprompterStatus.text = "Teleprompter running"
                btnTeleprompterPause.text = "Pause"
                btnTeleprompterPause.setIconResource(R.drawable.ic_pause)
            }
            "paused" -> {
                teleprompterPaused = true
                txtTeleprompterStatus.text = "Teleprompter paused"
                btnTeleprompterPause.text = "Resume"
                btnTeleprompterPause.setIconResource(R.drawable.ic_play)
            }
            "stopped", "finished" -> {
                resetTeleprompterUI()
                txtTeleprompterResult.visibility = View.VISIBLE
                txtTeleprompterResult.text = if (state == "finished") {
                    "Teleprompter finished"
                } else {
                    "Teleprompter stopped"
                }
                return
            }
        }
        progressTeleprompter.progress = (progress * 100).toInt()
    }

    private fun resetTeleprompterUI() {
        teleprompterActive = false
        teleprompterPaused = false
        teleprompterCommandId = null
        teleprompterControlSection.visibility = View.GONE
        updateTilesEnabled()
    }
}
