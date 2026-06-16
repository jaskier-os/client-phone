package com.repository.listener.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.view.animation.AccelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.repository.listener.R
import com.repository.listener.bt.encodeStreamMouseReport
import com.repository.listener.bt.encodeAbsoluteMouseReport
import com.repository.listener.bt.STREAM_MOUSE_ABS_MAX
import com.repository.listener.config.AppConfig
import java.util.UUID
import org.json.JSONObject
import com.repository.listener.network.Protocol
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import kotlin.math.abs

class DesktopFragment : Fragment() {

    companion object {
        private const val TAG = "DesktopFragment"
        // Stream mode moves the cursor across a whole PC desktop on the phone screen, so the
        // glasses head-tracking sensitivity is doubled relative to the configured base.
        private const val STREAM_SENSITIVITY_MULTIPLIER = 2
        private const val INPUT_ROW_ANIM_MS = 180L
private const val OVERLAY_AUTO_HIDE_MS = 3_000L
        private const val COLOR_GREEN = 0xFF00AA00.toInt()
        private const val COLOR_RED = 0xFFfb4934.toInt()
        private const val PAN_SPEED = 2.5f
        private const val PAN_JITTER_THRESHOLD = 2f
        private const val SPAN_SMOOTHING = 0.35f
        private const val TRANSFORM_SMOOTHING = 0.5f
    }

    private enum class StreamState { DISCONNECTED, IDLE, REQUESTING, STREAMING }
    private enum class AudioRelayState { IDLE, CONNECTING, ACTIVE, ERROR }

    // Pointer input source while streaming. Mutually exclusive.
    enum class InputMode { NONE, HID, GLASSES, FINGER }

    private lateinit var surfaceVideo: TextureView
    private lateinit var txtEmptyState: TextView
    private lateinit var controlOverlay: View
    private lateinit var dotOrchestrator: View
    private lateinit var btnStream: ImageButton
    private lateinit var btnMouse: ImageButton
private lateinit var monitorSwitcher: View
    private lateinit var btnMonitor: ImageButton
    private lateinit var txtMonitorBadge: TextView
    private lateinit var inputModeRow: View
    private lateinit var btnModeHid: TextView
    private lateinit var btnModeGlasses: TextView
    private lateinit var btnModeFinger: TextView
    private var inputMode = InputMode.NONE
    private var glassesMouseCommandId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    // Application context cached while attached, so teardown dispatches (e.g. stop_mouse on
    // fragment destroy) still work even when context/requireContext is no longer available.
    private var appContext: Context? = null
    private var streamState = StreamState.DISCONNECTED
    private var orchestratorConnected = false
    private var surfaceReady = false
    private var pendingStreamParams: IntArray? = null
    private var decoder: ScreenStreamDecoder? = null
    private var mouseRelayActive = false
    private var pointerDeviceConnected = false
    private var inputManager: InputManager? = null
    private var pointerPolling = false
private var overlayHideRunnable: Runnable? = null
    private var currentStreamId = 0
private var currentMonitor = 0
    private var monitorCount = 1
    private var streamWidth = 0
    private var streamHeight = 0

    private lateinit var btnAudio: ImageButton
    private lateinit var btnKeyboard: ImageButton
    private lateinit var progressLoading: ImageView
    private lateinit var audioContainer: View
    private lateinit var progressAudio: ImageView
    private lateinit var progressStream: ImageView
    private var audioRelayActive = false
    private var audioRelayState = AudioRelayState.IDLE
    private var streamRequestTimeoutRunnable: Runnable? = null

    // Zoom + pan state (pivot at 0,0; we manage transform manually)
    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var gestureStartSpan = 0f
    private var gestureStartScale = 0f
    private var smoothedSpan = 0f
    private val transformMatrix = Matrix()
    private var textureSurface: Surface? = null
    private var displayScale = 1f
    private var displayPanX = 0f
    private var displayPanY = 0f
    private var animating = false
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val streamEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ListenerService.ACTION_STREAM_ACK -> {
                    val streamId = intent.getIntExtra(ListenerService.EXTRA_STREAM_ID, 0)
                    val width = intent.getIntExtra(ListenerService.EXTRA_STREAM_WIDTH, 1280)
                    val height = intent.getIntExtra(ListenerService.EXTRA_STREAM_HEIGHT, 720)
                    val fps = intent.getIntExtra(ListenerService.EXTRA_STREAM_FPS, 24)
                    val monitors = intent.getIntExtra(ListenerService.EXTRA_STREAM_MONITOR_COUNT, 1)
                    currentStreamId = streamId
                    onStreamAck(streamId, width, height, fps, monitors)
                }
                ListenerService.ACTION_STREAM_ENDED -> {
                    onStreamEnded()
                }
                ListenerService.ACTION_AUDIO_RELAY_ACK -> {
                    val sampleRate = intent.getIntExtra(ListenerService.EXTRA_AUDIO_SAMPLE_RATE, 48000)
                    val channels = intent.getIntExtra(ListenerService.EXTRA_AUDIO_CHANNELS, 2)
                    val frameDurationMs = intent.getIntExtra(ListenerService.EXTRA_AUDIO_FRAME_DURATION_MS, 60)
                    onAudioRelayAck(sampleRate, channels, frameDurationMs)
                }
                ListenerService.ACTION_AUDIO_RELAY_ERROR -> {
                    val reason = intent.getStringExtra(ListenerService.EXTRA_ERROR_REASON) ?: "unknown"
                    onAudioRelayError(reason)
                }
                ListenerService.ACTION_STREAM_ERROR -> {
                    val reason = intent.getStringExtra(ListenerService.EXTRA_ERROR_REASON) ?: "unknown"
                    onStreamError(reason)
                }
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val detail = intent.getStringExtra(ListenerService.EXTRA_DETAIL) ?: return
            activity?.runOnUiThread {
                orchestratorConnected = detail.contains("Connected") && !detail.contains("Disconnected")
                updateOrchestratorDot()
                if (!orchestratorConnected && streamState == StreamState.STREAMING) {
                    stopStream()
                } else {
                    updateStreamState(if (orchestratorConnected) StreamState.IDLE else StreamState.DISCONNECTED)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_desktop, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appContext = view.context.applicationContext
        surfaceVideo = view.findViewById(R.id.surfaceVideo)
        txtEmptyState = view.findViewById(R.id.txtEmptyState)
        controlOverlay = view.findViewById(R.id.controlOverlay)
        dotOrchestrator = view.findViewById(R.id.dotOrchestrator)
        btnStream = view.findViewById(R.id.btnStream)
        btnMouse = view.findViewById(R.id.btnMouse)
monitorSwitcher = view.findViewById(R.id.monitorSwitcher)
        btnMonitor = view.findViewById(R.id.btnMonitor)
        txtMonitorBadge = view.findViewById(R.id.txtMonitorBadge)
        inputModeRow = view.findViewById(R.id.inputModeRow)
        btnModeHid = view.findViewById(R.id.btnModeHid)
        btnModeGlasses = view.findViewById(R.id.btnModeGlasses)
        btnModeFinger = view.findViewById(R.id.btnModeFinger)
        btnAudio = view.findViewById(R.id.btnAudio)
        btnKeyboard = view.findViewById(R.id.btnKeyboard)
        progressLoading = view.findViewById(R.id.progressLoading)
        audioContainer = view.findViewById(R.id.audioContainer)
        progressAudio = view.findViewById(R.id.progressAudio)
        progressStream = view.findViewById(R.id.progressStream)

        setupStatusDots()
        setupSurface()
        setupButtons()
        setupZoomAndPan()
        setupPointerDetection()

        updateStreamState(StreamState.DISCONNECTED)
    }

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(
            stateReceiver,
            IntentFilter(ListenerService.ACTION_STATE_UPDATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        requireContext().registerReceiver(
            streamEventReceiver,
            IntentFilter().apply {
                addAction(ListenerService.ACTION_STREAM_ACK)
                addAction(ListenerService.ACTION_STREAM_ENDED)
                addAction(ListenerService.ACTION_AUDIO_RELAY_ACK)
                addAction(ListenerService.ACTION_AUDIO_RELAY_ERROR)
                addAction(ListenerService.ACTION_STREAM_ERROR)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        ListenerService.streamFrameListener = this::onBinaryFrame

        orchestratorConnected = ListenerService.orchestratorConnected
        audioRelayActive = ListenerService.audioRelayActive
        updateOrchestratorDot()
        updateStreamState(if (orchestratorConnected) StreamState.IDLE else StreamState.DISCONNECTED)
    }

    override fun onPause() {
        super.onPause()
        ListenerService.streamFrameListener = null
        requireContext().unregisterReceiver(stateReceiver)
        requireContext().unregisterReceiver(streamEventReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStream()
        stopPointerPolling()
        resetInputMode()
        inputManager?.unregisterInputDeviceListener(inputDeviceListener)
    }

    private fun setupStatusDots() {
        val orchDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_RED)
        }
        dotOrchestrator.background = orchDrawable
    }

    private fun setupPointerDetection() {
        inputManager = requireContext().getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager?.registerInputDeviceListener(inputDeviceListener, handler)
        checkPointerDevices()
    }

    // BLE HID devices (e.g. WowMouse) don't reliably trigger InputDeviceListener.
    // Poll periodically while streaming to catch them.
    private val pointerPollRunnable = object : Runnable {
        override fun run() {
            checkPointerDevices()
            reconcileGlassesMode()
            if (pointerPolling) handler.postDelayed(this, 2000)
        }
    }

    // If the glasses BT link drops while GLASSES mode is active, fall back to FINGER so the
    // user keeps control instead of being stuck on a dead input source.
    private fun reconcileGlassesMode() {
        if (inputMode == InputMode.GLASSES && streamState == StreamState.STREAMING
            && !ListenerService.glassesConnected) {
            LogCollector.i(TAG, "Glasses link dropped while in GLASSES mode -- falling back to FINGER")
            setInputMode(InputMode.FINGER)
        } else if (inputModeRow.visibility == View.VISIBLE) {
            // Keep the footer's enabled/disabled states fresh while it's open.
            updateInputModeRow()
        }
    }

    private fun startPointerPolling() {
        if (pointerPolling) return
        pointerPolling = true
        handler.postDelayed(pointerPollRunnable, 2000)
    }

    private fun stopPointerPolling() {
        pointerPolling = false
        handler.removeCallbacks(pointerPollRunnable)
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { checkPointerDevices() }
        override fun onInputDeviceRemoved(deviceId: Int) { checkPointerDevices() }
        override fun onInputDeviceChanged(deviceId: Int) { checkPointerDevices() }
    }

    private fun checkPointerDevices() {
        // Note: do NOT early-return while mouseRelayActive -- we must still detect pointer
        // REMOVAL while HID mode is live so we can fall back instead of holding dead capture.
        val hasPointer = InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id)
            device != null && !device.isVirtual
                && (device.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        }
        if (hasPointer != pointerDeviceConnected) {
            pointerDeviceConnected = hasPointer
            LogCollector.i(TAG, "Pointer device ${if (hasPointer) "connected" else "disconnected"}")
            when {
                // Pointer vanished while in HID mode: tear the relay down and fall back to finger.
                !hasPointer && inputMode == InputMode.HID && streamState == StreamState.STREAMING ->
                    setInputMode(InputMode.FINGER)
                // Pointer appeared while streaming with no mode chosen yet: adopt HID.
                // Don't auto-steal an active FINGER/GLASSES choice the user made.
                hasPointer && streamState == StreamState.STREAMING && inputMode == InputMode.NONE ->
                    setInputMode(InputMode.HID)
                else -> {
                    updateMouseButton()
                    updateInputModeRow()
                }
            }
        }
    }

    private fun updateMouseButton() {
        btnMouse.visibility = if (streamState == StreamState.STREAMING) View.VISIBLE else View.GONE
        btnMouse.alpha = if (inputMode != InputMode.NONE) 1f else 0.4f
    }

    // -- Input-mode picker (footer row) --

    private fun updateInputModeRow() {
        styleModeButton(btnModeHid, enabled = pointerDeviceConnected, active = inputMode == InputMode.HID)
        styleModeButton(btnModeGlasses, enabled = ListenerService.glassesConnected, active = inputMode == InputMode.GLASSES)
        styleModeButton(btnModeFinger, enabled = true, active = inputMode == InputMode.FINGER)
    }

    private fun styleModeButton(btn: TextView, enabled: Boolean, active: Boolean) {
        btn.isEnabled = enabled
        btn.alpha = when {
            !enabled -> 0.4f
            active -> 1f
            else -> 0.7f
        }
        btn.setTextColor(
            resources.getColor(if (active) R.color.gbx_orange else R.color.gbx_fg, null)
        )
    }

    private fun setInputMode(mode: InputMode) {
        // Toggle off if re-selecting the current mode.
        val target = if (mode == inputMode) InputMode.NONE else mode

        // Guard: can't enter HID without a pointer, or GLASSES without a BT link.
        if (target == InputMode.HID && !pointerDeviceConnected) { updateInputModeRow(); return }
        if (target == InputMode.GLASSES && !ListenerService.glassesConnected) { updateInputModeRow(); return }

        // Tear down the current mode.
        when (inputMode) {
            InputMode.HID -> disableMouseRelay()
            InputMode.GLASSES -> stopGlassesMouse()
            else -> {}
        }

        inputMode = target

        // Set up the new mode.
        when (target) {
            InputMode.HID -> enableMouseRelay()
            InputMode.GLASSES -> startGlassesMouse()
            else -> {}
        }

        LogCollector.i(TAG, "Input mode -> $target")
        updateMouseButton()
        updateInputModeRow()
    }

    private fun resetInputMode() {
        when (inputMode) {
            InputMode.HID -> disableMouseRelay()
            InputMode.GLASSES -> stopGlassesMouse()
            else -> {}
        }
        // Ensure capture is released even if state drifted.
        if (mouseRelayActive) disableMouseRelay()
        inputMode = InputMode.NONE
        if (::inputModeRow.isInitialized) inputModeRow.visibility = View.GONE
    }

    private fun startGlassesMouse() {
        if (!isAdded) return
        val ctx = requireContext()
        val params = JSONObject().apply {
            // Double the sensitivity in video-streaming mode -- the cursor travels across a full
            // PC desktop shown on the phone, so it needs to move faster than the standalone case.
            put("sensitivity_x", AppConfig.getMouseSensitivityX(ctx) * STREAM_SENSITIVITY_MULTIPLIER)
            put("sensitivity_y", AppConfig.getMouseSensitivityY(ctx) * STREAM_SENSITIVITY_MULTIPLIER)
            // Empty device_address -> service routes to the active stream mouse channel.
            put("device_address", "")
            // Stream mode: the user explicitly chose "Glasses" in the on-stream footer, so
            // head-tracking must begin immediately (no on-glasses tap to toggle it), and the
            // glasses must NOT spin up their standalone BLE-HID mouse activity -- reports go
            // glasses RFCOMM -> phone -> stream, not via the glasses' own BLE-HID device.
            put("stream_mode", true)
        }
        val commandId = "desktop_mouse_${UUID.randomUUID().toString().take(8)}"
        glassesMouseCommandId = commandId
        val intent = Intent(ctx, ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", commandId)
            putExtra("type", "start_mouse")
            putExtra("params", params.toString())
        }
        ctx.startService(intent)
        LogCollector.i(TAG, "start_mouse (glasses, stream) sent id=$commandId")
    }

    private fun stopGlassesMouse() {
        // Use application context so the stop still dispatches even when the fragment is
        // being destroyed (onDestroyView -> resetInputMode), otherwise the glasses would
        // keep capturing mouse input after the user leaves the stream.
        val ctx = context?.applicationContext ?: appContext ?: return
        val commandId = "desktop_mouse_${UUID.randomUUID().toString().take(8)}"
        glassesMouseCommandId = null
        val intent = Intent(ctx, ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", commandId)
            putExtra("type", "stop_mouse")
            putExtra("params", "{}")
        }
        ctx.startService(intent)
        LogCollector.i(TAG, "stop_mouse (glasses) sent id=$commandId")
    }

    // -- Pointer capture (mouse relay) --

    private fun enableMouseRelay() {
        mouseRelayActive = true
        surfaceVideo.isFocusable = true
        surfaceVideo.isFocusableInTouchMode = true
        surfaceVideo.requestFocus()
        surfaceVideo.setOnCapturedPointerListener { _, event ->
            handleCapturedMouseEvent(event)
            true
        }
        surfaceVideo.requestPointerCapture()
        val hasCap = surfaceVideo.hasPointerCapture()
        LogCollector.i(TAG, "Mouse relay enabled, hasPointerCapture=$hasCap")
        updateMouseButton()
    }

    private fun disableMouseRelay() {
        if (!mouseRelayActive) return
        mouseRelayActive = false
        handler.removeCallbacks(mouseFlushRunnable)
        mouseDirty = false
        pendingDx = 0; pendingDy = 0; pendingScroll = 0; lastBtnMask = 0
        surfaceVideo.releasePointerCapture()
        surfaceVideo.setOnCapturedPointerListener(null)
        updateMouseButton()
        LogCollector.i(TAG, "Mouse relay disabled")
    }

    // Batched mouse state -- accumulate between ticks
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingButtons = 0
    private var pendingScroll = 0
    private var mouseDirty = false
    private val mouseFlushRunnable = Runnable { flushMouseBatch() }
    private var mouseRawEventCount = 0
    private var mouseFlushCount = 0
    private var mouseLastStatsTime = System.currentTimeMillis()

    private var lastBtnMask = 0

    private fun handleCapturedMouseEvent(event: MotionEvent) {
        val dx = event.getX(0).toInt()
        val dy = event.getY(0).toInt()
        val scroll = if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt()
        } else 0

        var btnMask = 0
        val buttons = event.buttonState
        if (buttons and MotionEvent.BUTTON_PRIMARY != 0) btnMask = btnMask or 0x01
        if (buttons and MotionEvent.BUTTON_SECONDARY != 0) btnMask = btnMask or 0x02
        if (buttons and MotionEvent.BUTTON_TERTIARY != 0) btnMask = btnMask or 0x04

        // Button state changed -- flush immediately so press/release aren't lost in batch
        if (btnMask != lastBtnMask) {
            // Flush any pending movement first with OLD button state
            if (mouseDirty) {
                handler.removeCallbacks(mouseFlushRunnable)
                flushMouseBatch()
            }
            // Now send this event with the NEW button state immediately
            pendingDx = dx
            pendingDy = dy
            pendingScroll = scroll
            pendingButtons = btnMask
            lastBtnMask = btnMask
            mouseDirty = true
            mouseRawEventCount++
            flushMouseBatch()
            return
        }

        pendingDx += dx
        pendingDy += dy
        pendingScroll += scroll
        pendingButtons = btnMask
        mouseRawEventCount++

        if (!mouseDirty) {
            mouseDirty = true
            handler.postDelayed(mouseFlushRunnable, 32) // ~30fps
        }
    }

    private fun flushMouseBatch() {
        if (!mouseDirty || !mouseRelayActive) return
        val dx = pendingDx
        val dy = pendingDy
        val btn = pendingButtons
        val scroll = pendingScroll
        pendingDx = 0
        pendingDy = 0
        pendingScroll = 0
        mouseDirty = false

        if (dx == 0 && dy == 0 && scroll == 0 && btn == 0) return

        mouseFlushCount++
        val now = System.currentTimeMillis()
        if (now - mouseLastStatsTime >= 2000) {
            val elapsed = (now - mouseLastStatsTime) / 1000.0
            LogCollector.i(TAG, "[mouse] phone: $mouseRawEventCount raw events -> $mouseFlushCount flushes in ${String.format("%.1f", elapsed)}s (${String.format("%.1f", mouseFlushCount / elapsed)} flush/s, ${String.format("%.1f", mouseRawEventCount / elapsed)} raw/s)")
            mouseRawEventCount = 0
            mouseFlushCount = 0
            mouseLastStatsTime = now
        }

        val buf = encodeStreamMouseReport(dx, dy, btn, scroll)
        ListenerService.mouseEventListener?.invoke(buf)
    }

    // -- Audio relay --

    private fun enableAudioRelay() {
        if (!isAdded) return
        val ctx = requireContext()
        AppConfig.setAudioRelayDesired(ctx, true)
        val bitrate = AppConfig.getAudioBitrate(ctx)
        LogCollector.i(TAG, "Audio relay: requesting (${bitrate}bps)")
        ctx.sendBroadcast(Intent(ListenerService.ACTION_AUDIO_RELAY_START).apply {
            setPackage(ctx.packageName)
            putExtra("bitrate", bitrate)
            putExtra("target_device_id", "desktop-listener")
        })
        updateAudioRelayState(AudioRelayState.CONNECTING)
    }

    private fun disableAudioRelay() {
        if (audioRelayState == AudioRelayState.IDLE) return
        audioRelayActive = false
        if (isAdded) {
            requireContext().sendBroadcast(Intent(ListenerService.ACTION_AUDIO_RELAY_STOP).apply {
                setPackage(requireContext().packageName)
                putExtra("target_device_id", "desktop-listener")
            })
        }
        updateAudioRelayState(AudioRelayState.IDLE)
        LogCollector.i(TAG, "Audio relay disabled")
    }

    private fun onAudioRelayAck(sampleRate: Int, channels: Int, frameDurationMs: Int) {
        handler.post {
            LogCollector.i(TAG, "Audio relay ACK received: ${sampleRate}Hz ${channels}ch frame=${frameDurationMs}ms")
            audioRelayActive = true
            updateAudioRelayState(AudioRelayState.ACTIVE)
        }
    }

    private fun onAudioRelayError(reason: String) {
        handler.post {
            LogCollector.e(TAG, "Audio relay error: $reason")
            audioRelayActive = false
            updateAudioRelayState(AudioRelayState.ERROR)
            val msg = when (reason) {
                "no_monitor_device" -> "Desktop has no audio output"
                "timeout" -> "Desktop did not respond"
                "desktop_offline" -> "Desktop is not connected"
                else -> "Audio relay error: $reason"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            // Auto-clear error after 3s back to IDLE
            handler.postDelayed({ if (audioRelayState == AudioRelayState.ERROR) updateAudioRelayState(AudioRelayState.IDLE) }, 3000)
        }
    }

    private fun onStreamError(reason: String) {
        handler.post {
            LogCollector.e(TAG, "Stream error: $reason")
            cancelStreamRequestTimeout()
            val msg = when (reason) {
                "ffmpeg_failed" -> "Screen capture failed on desktop"
                "timeout" -> "Desktop did not respond"
                else -> "Stream error: $reason"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            updateStreamState(StreamState.IDLE)
        }
    }

    private fun showSpinner(spinner: ImageView) {
        spinner.visibility = View.VISIBLE
        spinner.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.rotate_continuous))
    }

    private fun hideSpinner(spinner: ImageView) {
        spinner.clearAnimation()
        spinner.visibility = View.GONE
    }

    private fun updateAudioRelayState(state: AudioRelayState) {
        audioRelayState = state
        updateAudioButton()
    }

    private fun updateAudioButton() {
        val visible = streamState == StreamState.IDLE || streamState == StreamState.STREAMING
        audioContainer.visibility = if (visible) View.VISIBLE else View.GONE
        when (audioRelayState) {
            AudioRelayState.IDLE -> {
                btnAudio.visibility = View.VISIBLE
                hideSpinner(progressAudio)
                btnAudio.alpha = 0.4f
                btnAudio.isEnabled = true
            }
            AudioRelayState.CONNECTING -> {
                btnAudio.visibility = View.GONE
                showSpinner(progressAudio)
                btnAudio.isEnabled = false
            }
            AudioRelayState.ACTIVE -> {
                btnAudio.visibility = View.VISIBLE
                hideSpinner(progressAudio)
                btnAudio.alpha = 1f
                btnAudio.isEnabled = true
            }
            AudioRelayState.ERROR -> {
                btnAudio.visibility = View.VISIBLE
                hideSpinner(progressAudio)
                btnAudio.alpha = 0.4f
                btnAudio.isEnabled = false
            }
        }
    }

    private fun updateKeyboardButton() {
        btnKeyboard.visibility = if (streamState == StreamState.STREAMING) View.VISIBLE else View.GONE
    }

    private fun setupSurface() {
        surfaceVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surfaceReady = true
                textureSurface = Surface(st)
                LogCollector.i(TAG, "TextureView surface available")
                pendingStreamParams?.let { params ->
                    pendingStreamParams = null
                    startDecoder(params[0], params[1], textureSurface!!)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceReady = false
                textureSurface?.release()
                textureSurface = null
                if (streamState == StreamState.STREAMING && streamWidth > 0) {
                    // Surface destroyed during orientation change -- save params for auto-restart
                    decoder?.stop()
                    decoder = null
                    pendingStreamParams = intArrayOf(streamWidth, streamHeight)
                    LogCollector.i(TAG, "Surface destroyed during stream, will restart on new surface")
                    return true
                }
                stopStream()
                LogCollector.i(TAG, "TextureView surface destroyed")
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun setupButtons() {
        btnStream.setOnClickListener {
            when (streamState) {
                StreamState.IDLE -> requestStream()
                StreamState.STREAMING -> stopStream()
                else -> {}
            }
        }

        btnMouse.setOnClickListener {
            val show = inputModeRow.visibility != View.VISIBLE
            if (show) {
                updateInputModeRow()
                showInputModeRow()
                scheduleOverlayHide()
            } else {
                hideInputModeRow()
            }
        }

        btnModeHid.setOnClickListener { setInputMode(InputMode.HID) }
        btnModeGlasses.setOnClickListener { setInputMode(InputMode.GLASSES) }
        btnModeFinger.setOnClickListener { setInputMode(InputMode.FINGER) }

btnMonitor.setOnClickListener {
            if (monitorCount <= 1) return@setOnClickListener
            currentMonitor = (currentMonitor + 1) % monitorCount
            txtMonitorBadge.text = currentMonitor.toString()
            if (streamState == StreamState.STREAMING) switchMonitor()
        }

        btnAudio.setOnClickListener {
            if (audioRelayActive) {
                AppConfig.setAudioRelayDesired(requireContext(), false)
                disableAudioRelay()
            } else {
                enableAudioRelay()
            }
        }

        btnKeyboard.setOnClickListener {
            KeyboardInputFragment().show(childFragmentManager, "keyboard_input")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoomAndPan() {
        scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                gestureStartSpan = detector.currentSpan
                smoothedSpan = detector.currentSpan
                gestureStartScale = scaleFactor
                LogCollector.d(TAG, "ZOOM BEGIN span=%.0f scale=%.2f".format(gestureStartSpan, gestureStartScale))
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (gestureStartSpan <= 0f) return true
                smoothedSpan += (detector.currentSpan - smoothedSpan) * SPAN_SMOOTHING
                val spanRatio = smoothedSpan / gestureStartSpan
                val oldScale = scaleFactor
                val newScale = (gestureStartScale * spanRatio).coerceIn(1f, 8f)

                val focusX = detector.focusX
                val focusY = detector.focusY
                val ratio = newScale / scaleFactor
                panX = focusX - (focusX - panX) * ratio
                panY = focusY - (focusY - panY) * ratio

                scaleFactor = newScale
                clampPan()
                applyTransform()
                LogCollector.d(TAG, "ZOOM span=%.0f ratio=%.3f old=%.2f new=%.2f".format(
                    detector.currentSpan, spanRatio, oldScale, newScale))
                return true
            }
        })

        var pointerCountMax = 0
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var movedBeyondSlop = false
        surfaceVideo.setOnTouchListener { _, event ->
            if (streamState != StreamState.STREAMING) return@setOnTouchListener false

            scaleGestureDetector?.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerCountMax = 1
                    lastTouchX = event.x
                    lastTouchY = event.y
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                    isDragging = false
                    movedBeyondSlop = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pointerCountMax = maxOf(pointerCountMax, event.pointerCount)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && scaleFactor > 1f && !(scaleGestureDetector?.isInProgress == true)) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (!isDragging && (dx * dx + dy * dy) > 25) {
                            isDragging = true
                        }
                        if (isDragging && (dx * dx + dy * dy) > PAN_JITTER_THRESHOLD * PAN_JITTER_THRESHOLD) {
                            panX += dx * PAN_SPEED
                            panY += dy * PAN_SPEED
                            clampPan()
                            applyTransform()
                        }
                    }
                    val tdx = event.x - downX
                    val tdy = event.y - downY
                    if ((tdx * tdx + tdy * tdy) > 25) movedBeyondSlop = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Gesture stolen (e.g. system nav) -- abandon any pending tap, send nothing.
                    isDragging = false
                    movedBeyondSlop = true
                }
                MotionEvent.ACTION_UP -> {
                    val isTap = !movedBeyondSlop && !isDragging &&
                        (event.eventTime - downTime) < 300 &&
                        !(scaleGestureDetector?.isInProgress == true)
                    if (inputMode == InputMode.FINGER && isTap) {
                        // Jump-and-click: left for single tap, right for two-finger tap.
                        val right = pointerCountMax == 2
                        sendFingerClick(downX, downY, right)
                    } else if (pointerCountMax == 1 && !isDragging) {
                        // Overlay toggle is reserved for non-finger taps (or finger taps that
                        // weren't consumed as a click, e.g. while zooming).
                        val visible = controlOverlay.visibility == View.VISIBLE
                        controlOverlay.visibility = if (visible) View.GONE else View.VISIBLE
                        if (visible) hideInputModeRow()
                        scheduleOverlayHide()
                    }
                }
            }
            true
        }
    }

    // Maps a tap in surfaceVideo view px to PC-normalized absolute coords and sends a
    // press + release (so the desktop edge-triggers a click) via the stream mouse sink.
    private fun sendFingerClick(viewX: Float, viewY: Float, rightButton: Boolean) {
        if (ListenerService.mouseEventListener == null) return
        val w = surfaceVideo.width.toFloat()
        val h = surfaceVideo.height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Invert the committed (target) transform, not the in-flight display* values.
        val contentX = (viewX - panX) / scaleFactor
        val contentY = (viewY - panY) / scaleFactor
        val normXf = (contentX / w).coerceIn(0f, 1f)
        val normYf = (contentY / h).coerceIn(0f, 1f)
        val normX = (normXf * STREAM_MOUSE_ABS_MAX).toInt()
        val normY = (normYf * STREAM_MOUSE_ABS_MAX).toInt()
        val btn = if (rightButton) 0x02 else 0x01

        // Send press then release back-to-back. The desktop edge-triggers buttons per
        // report (two separate syn()s), so an immediate release still registers a click --
        // and avoids leaving a button held if the stream tears down between the two reports.
        val press = encodeAbsoluteMouseReport(currentMonitor, normX, normY, btn)
        val release = encodeAbsoluteMouseReport(currentMonitor, normX, normY, 0x00)
        val sink = ListenerService.mouseEventListener ?: return
        sink.invoke(press)
        sink.invoke(release)
        LogCollector.i(TAG, "finger click monitor=$currentMonitor norm=($normX,$normY) right=$rightButton")
    }

    private fun applyTransform() {
        if (!animating) {
            animating = true
            scheduleFrame()
        }
    }

    private fun scheduleFrame() {
        surfaceVideo.postOnAnimation {
            displayScale += (scaleFactor - displayScale) * TRANSFORM_SMOOTHING
            displayPanX += (panX - displayPanX) * TRANSFORM_SMOOTHING
            displayPanY += (panY - displayPanY) * TRANSFORM_SMOOTHING

            transformMatrix.reset()
            transformMatrix.postScale(displayScale, displayScale)
            transformMatrix.postTranslate(displayPanX, displayPanY)
            surfaceVideo.setTransform(transformMatrix)

            if (abs(panX - displayPanX) > 0.5f || abs(panY - displayPanY) > 0.5f
                || abs(scaleFactor - displayScale) > 0.005f) {
                scheduleFrame()
            } else {
                displayScale = scaleFactor
                displayPanX = panX
                displayPanY = panY
                animating = false
            }
        }
    }

    private fun clampPan() {
        if (scaleFactor <= 1f) {
            panX = 0f
            panY = 0f
            return
        }
        val w = surfaceVideo.width.toFloat()
        val h = surfaceVideo.height.toFloat()
        val maxPanX = 0f
        val minPanX = w - w * scaleFactor
        val maxPanY = 0f
        val minPanY = h - h * scaleFactor
        panX = panX.coerceIn(minPanX, maxPanX)
        panY = panY.coerceIn(minPanY, maxPanY)
    }

    private fun resetTransform() {
        scaleFactor = 1f
        panX = 0f
        panY = 0f
        displayScale = 1f
        displayPanX = 0f
        displayPanY = 0f
        animating = false
        transformMatrix.reset()
        surfaceVideo.setTransform(transformMatrix)
    }

    private fun scheduleOverlayHide() {
        overlayHideRunnable?.let { handler.removeCallbacks(it) }
        if (streamState == StreamState.STREAMING && controlOverlay.visibility == View.VISIBLE) {
            val runnable = Runnable {
                if (streamState == StreamState.STREAMING) {
                    controlOverlay.visibility = View.GONE
                    hideInputModeRow()
                }
            }
            overlayHideRunnable = runnable
            handler.postDelayed(runnable, OVERLAY_AUTO_HIDE_MS)
        }
    }

    // -- Input-mode footer slide animation --
    //
    // Driven by a manual per-frame tween (postOnAnimation) instead of ViewPropertyAnimator, so it
    // animates even when the device has the global animator duration scale set to 0 (animations
    // off in Developer Options), which would otherwise make .animate() snap instantly.

    private var inputRowAnimRunnable: Runnable? = null
    private val decelerate = DecelerateInterpolator()
    private val accelerate = AccelerateInterpolator()

    private fun rowDrop(): Float = inputModeRow.height.takeIf { it > 0 }?.toFloat() ?: 96f

    /** Slide the footer up + fade in. */
    private fun showInputModeRow() {
        if (!::inputModeRow.isInitialized) return
        val startY = if (inputModeRow.visibility != View.VISIBLE) rowDrop() else inputModeRow.translationY
        inputModeRow.visibility = View.VISIBLE
        tweenInputRow(fromY = startY, toY = 0f, fromA = inputModeRow.alpha, toA = 1f,
            interp = decelerate, endVisible = true)
    }

    /** Slide the footer down + fade out, then GONE. */
    private fun hideInputModeRow() {
        if (!::inputModeRow.isInitialized) return
        if (inputModeRow.visibility != View.VISIBLE) return
        tweenInputRow(fromY = inputModeRow.translationY, toY = rowDrop(), fromA = inputModeRow.alpha, toA = 0f,
            interp = accelerate, endVisible = false)
    }

    private fun tweenInputRow(
        fromY: Float, toY: Float, fromA: Float, toA: Float,
        interp: android.view.animation.Interpolator, endVisible: Boolean
    ) {
        inputRowAnimRunnable?.let { inputModeRow.removeCallbacks(it) }
        val start = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - start).toFloat() / INPUT_ROW_ANIM_MS).coerceIn(0f, 1f)
                val f = interp.getInterpolation(t)
                inputModeRow.translationY = fromY + (toY - fromY) * f
                inputModeRow.alpha = fromA + (toA - fromA) * f
                if (t < 1f) {
                    inputModeRow.postOnAnimation(this)
                } else {
                    inputRowAnimRunnable = null
                    if (!endVisible) {
                        inputModeRow.visibility = View.GONE
                        inputModeRow.translationY = 0f
                        inputModeRow.alpha = 1f
                    }
                }
            }
        }
        inputRowAnimRunnable = runnable
        inputModeRow.postOnAnimation(runnable)
    }

    // -- Streaming chrome (tabs, overlay) --

    private fun setStreamingChrome(streaming: Boolean) {
        activity?.findViewById<View>(R.id.tabLayout)?.visibility =
            if (streaming) View.GONE else View.VISIBLE
    }

    // -- Immersive mode --

    private fun enterImmersive() {
        val window = activity?.window ?: return
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitImmersive() {
        val window = activity?.window ?: return
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // -- Monitor switcher --

    private fun updateMonitorSwitcher() {
        if (monitorCount <= 1 || streamState != StreamState.STREAMING) {
            monitorSwitcher.visibility = View.GONE
            if (monitorCount <= 1) currentMonitor = 0
        } else {
            monitorSwitcher.visibility = View.VISIBLE
        }
        txtMonitorBadge.text = currentMonitor.toString()
    }

// -- Stream management --

    private fun requestStream() {
        updateStreamState(StreamState.REQUESTING)
        LogCollector.i(TAG, "Stream requested monitor=$currentMonitor")
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_STREAM_REQUEST).apply {
            setPackage(requireContext().packageName)
            putExtra(ListenerService.EXTRA_TARGET_DEVICE_ID, "desktop-listener")
            putExtra(ListenerService.EXTRA_MONITOR, currentMonitor)
        })
    }

    private fun switchMonitor() {
        LogCollector.i(TAG, "Switching to monitor $currentMonitor")
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_STREAM_SWITCH_MONITOR).apply {
            setPackage(requireContext().packageName)
            putExtra(ListenerService.EXTRA_STREAM_ID, currentStreamId)
            putExtra(ListenerService.EXTRA_MONITOR, currentMonitor)
        })
    }

    fun onStreamAck(streamId: Int, width: Int, height: Int, fps: Int, monitors: Int) {
        handler.post {
            monitorCount = monitors
            streamWidth = width
            streamHeight = height
            LogCollector.i(TAG, "Stream ACK: id=$streamId ${width}x${height} @${fps}fps monitors=$monitors")

            // Force landscape BEFORE showing surface/starting decoder to avoid
            // surface destruction mid-stream during orientation transition
            savedOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

            surfaceVideo.visibility = View.VISIBLE
            txtEmptyState.visibility = View.GONE

            // Delay decoder start to let orientation change and layout settle
            handler.postDelayed({
                if (decoder != null) return@postDelayed
                val surface = textureSurface
                if (surfaceReady && surface != null) {
                    startDecoder(streamWidth, streamHeight, surface)
                } else {
                    pendingStreamParams = intArrayOf(streamWidth, streamHeight)
                }
            }, 300)
        }
    }

    private fun startDecoder(width: Int, height: Int, surface: android.view.Surface) {
        try {
            decoder = ScreenStreamDecoder(width, height).also {
                it.start(surface)
            }
            updateStreamState(StreamState.STREAMING)
            LogCollector.i(TAG, "Decoder started (${width}x${height})")
        } catch (e: Exception) {
            LogCollector.e(TAG, "Decoder start failed: ${e.message}")
            decoder = null
            updateStreamState(if (orchestratorConnected) StreamState.IDLE else StreamState.DISCONNECTED)
        }
    }

    fun onBinaryFrame(data: ByteArray) {
        decoder?.feedFrame(data)
    }

    fun onStreamEnded() {
        handler.post {
            LogCollector.i(TAG, "Stream ended by remote")
            stopStream()
        }
    }

    private fun stopStream() {
        pendingStreamParams = null
        if (currentStreamId != 0) {
            requireContext().sendBroadcast(Intent(ListenerService.ACTION_STREAM_STOP).apply {
                setPackage(requireContext().packageName)
                putExtra(ListenerService.EXTRA_STREAM_ID, currentStreamId)
            })
            currentStreamId = 0
        }
        decoder?.stop()
        decoder = null
        resetTransform()
        updateStreamState(if (orchestratorConnected) StreamState.IDLE else StreamState.DISCONNECTED)
    }

    private fun updateStreamState(newState: StreamState) {
        streamState = newState
        when (newState) {
            StreamState.DISCONNECTED -> {
                exitImmersive()
                setStreamingChrome(false)
                activity?.requestedOrientation = savedOrientation
                surfaceVideo.visibility = View.GONE
                txtEmptyState.text = "No active stream"
                txtEmptyState.setTextColor(resources.getColor(R.color.gbx_gray, null))
                txtEmptyState.visibility = View.VISIBLE
                hideSpinner(progressLoading)
                controlOverlay.visibility = View.VISIBLE
                btnStream.visibility = View.VISIBLE
                hideSpinner(progressStream)
                btnStream.setImageResource(R.drawable.ic_play)
                btnStream.isEnabled = false
                btnStream.alpha = 0.4f
                monitorCount = 1
                updateMonitorSwitcher()
                stopPointerPolling()
                resetInputMode()
                audioRelayActive = false
                updateAudioRelayState(AudioRelayState.IDLE)
                cancelStreamRequestTimeout()
            }
            StreamState.IDLE -> {
                exitImmersive()
                setStreamingChrome(false)
                activity?.requestedOrientation = savedOrientation
                surfaceVideo.visibility = View.GONE
                txtEmptyState.text = "No active stream"
                txtEmptyState.setTextColor(resources.getColor(R.color.gbx_gray, null))
                txtEmptyState.visibility = View.VISIBLE
                hideSpinner(progressLoading)
                controlOverlay.visibility = View.VISIBLE
                btnStream.visibility = View.VISIBLE
                hideSpinner(progressStream)
                btnStream.setImageResource(R.drawable.ic_play)
                btnStream.isEnabled = true
                btnStream.alpha = 1f
                monitorCount = 1
                updateMonitorSwitcher()
                stopPointerPolling()
                resetInputMode()
                audioRelayActive = ListenerService.audioRelayActive
                updateAudioRelayState(if (audioRelayActive) AudioRelayState.ACTIVE else AudioRelayState.IDLE)
                cancelStreamRequestTimeout()
            }
            StreamState.REQUESTING -> {
                txtEmptyState.text = "Connecting..."
                txtEmptyState.setTextColor(resources.getColor(R.color.gbx_gray, null))
                showSpinner(progressLoading)
                btnStream.visibility = View.GONE
                showSpinner(progressStream)
                scheduleStreamRequestTimeout()
            }
            StreamState.STREAMING -> {
                // Orientation already forced to landscape in onStreamAck (before decoder start)
                enterImmersive()
                setStreamingChrome(true)
                surfaceVideo.visibility = View.VISIBLE
                txtEmptyState.visibility = View.GONE
                hideSpinner(progressLoading)
                controlOverlay.visibility = View.GONE
                btnStream.visibility = View.VISIBLE
                hideSpinner(progressStream)
                btnStream.setImageResource(R.drawable.ic_stop)
                btnStream.isEnabled = true
                btnStream.alpha = 1f
                updateMonitorSwitcher()
                // Auto-pick: HID when a pointer is present, otherwise finger. Never glasses.
                if (inputMode == InputMode.NONE) {
                    setInputMode(if (pointerDeviceConnected) InputMode.HID else InputMode.FINGER)
                }
                startPointerPolling()
                cancelStreamRequestTimeout()
                updateAudioButton()
            }
        }
        updateMouseButton()
        updateAudioButton()
        updateKeyboardButton()
    }

    private fun scheduleStreamRequestTimeout() {
        cancelStreamRequestTimeout()
        streamRequestTimeoutRunnable = Runnable {
            if (streamState == StreamState.REQUESTING) {
                LogCollector.w(TAG, "Stream request timed out")
                onStreamError("timeout")
            }
        }
        handler.postDelayed(streamRequestTimeoutRunnable!!, 15_000)
    }

    private fun cancelStreamRequestTimeout() {
        streamRequestTimeoutRunnable?.let { handler.removeCallbacks(it) }
        streamRequestTimeoutRunnable = null
    }

    // -- Status dots --

    private fun updateOrchestratorDot() {
        val drawable = dotOrchestrator.background as? GradientDrawable ?: return
        drawable.setColor(if (orchestratorConnected) COLOR_GREEN else COLOR_RED)
    }

}
