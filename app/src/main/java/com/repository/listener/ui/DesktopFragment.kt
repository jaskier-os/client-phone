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
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.network.Protocol
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import kotlin.math.abs

class DesktopFragment : Fragment() {

    companion object {
        private const val TAG = "DesktopFragment"
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

    private lateinit var surfaceVideo: TextureView
    private lateinit var txtEmptyState: TextView
    private lateinit var controlOverlay: View
    private lateinit var dotOrchestrator: View
    private lateinit var btnStream: ImageButton
    private lateinit var btnMouse: ImageButton
private lateinit var monitorSwitcher: View
    private lateinit var btnMonitor: ImageButton
    private lateinit var txtMonitorBadge: TextView

    private val handler = Handler(Looper.getMainLooper())
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

        surfaceVideo = view.findViewById(R.id.surfaceVideo)
        txtEmptyState = view.findViewById(R.id.txtEmptyState)
        controlOverlay = view.findViewById(R.id.controlOverlay)
        dotOrchestrator = view.findViewById(R.id.dotOrchestrator)
        btnStream = view.findViewById(R.id.btnStream)
        btnMouse = view.findViewById(R.id.btnMouse)
monitorSwitcher = view.findViewById(R.id.monitorSwitcher)
        btnMonitor = view.findViewById(R.id.btnMonitor)
        txtMonitorBadge = view.findViewById(R.id.txtMonitorBadge)
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
        disableMouseRelay()
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
            if (pointerPolling) handler.postDelayed(this, 2000)
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
        if (mouseRelayActive) return
        val hasPointer = InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id)
            device != null && !device.isVirtual
                && (device.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        }
        if (hasPointer != pointerDeviceConnected) {
            pointerDeviceConnected = hasPointer
            LogCollector.i(TAG, "Pointer device ${if (hasPointer) "connected" else "disconnected"}")
            updateMouseButton()
            if (hasPointer && streamState == StreamState.STREAMING) {
                enableMouseRelay()
            }
        }
    }

    private fun updateMouseButton() {
        btnMouse.visibility = if (pointerDeviceConnected && streamState == StreamState.STREAMING) View.VISIBLE else View.GONE
        btnMouse.alpha = if (mouseRelayActive) 1f else 0.4f
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

        val clampedDx = dx.coerceIn(-32768, 32767)
        val clampedDy = dy.coerceIn(-32768, 32767)
        val buf = ByteArray(7)
        buf[0] = 0x02
        buf[1] = (clampedDx shr 8).toByte()
        buf[2] = clampedDx.toByte()
        buf[3] = (clampedDy shr 8).toByte()
        buf[4] = clampedDy.toByte()
        buf[5] = btn.toByte()
        buf[6] = scroll.coerceIn(-128, 127).toByte()
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
            if (mouseRelayActive) disableMouseRelay() else enableMouseRelay()
        }

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
        surfaceVideo.setOnTouchListener { _, event ->
            if (streamState != StreamState.STREAMING) return@setOnTouchListener false

            scaleGestureDetector?.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerCountMax = 1
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isDragging = false
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
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (pointerCountMax == 1 && !isDragging) {
                        controlOverlay.visibility = if (controlOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        scheduleOverlayHide()
                    }
                }
            }
            true
        }
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
                }
            }
            overlayHideRunnable = runnable
            handler.postDelayed(runnable, OVERLAY_AUTO_HIDE_MS)
        }
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
                disableMouseRelay()
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
                disableMouseRelay()
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
                if (pointerDeviceConnected && !mouseRelayActive) {
                    enableMouseRelay()
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
