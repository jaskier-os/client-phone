package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.snackbar.Snackbar
import com.repository.listener.R
import com.repository.listener.arstream.ArStreamClient
import com.repository.listener.arstream.ArStreamRecorder
import com.repository.listener.arstream.ArStreamProtocol
import com.repository.listener.arstream.ArStreamSessionState
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Live view of the glasses' AR output (world camera + HUD composited on the glasses) with
 * two-way audio.
 *
 * The Activity lifecycle OWNS the session, following the contract TwoWayTranslationActivity
 * established: onCreate starts it, onDestroy always stops it. Closing this screen can therefore
 * never leave the glasses camera, both mics, or the WiFi-Direct group running unattended.
 */
class ArStreamActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var status: TextView
    private lateinit var btnPhoneMic: ImageButton
    private lateinit var btnGlassesMic: ImageButton
    private lateinit var btnRecord: ImageButton
    private lateinit var txtRecordTime: TextView

    /**
     * Session recorder. Volatile: started/stopped on main but fed from both socket threads, and a
     * stale null there would silently drop every frame of a recording that the UI says is running.
     */
    private val recorderRef = java.util.concurrent.atomic.AtomicReference<ArStreamRecorder?>(null)

    /**
     * Session recorder. Backed by an AtomicReference so the stop button and onDestroy cannot both
     * claim the same recorder: exactly one caller takes it and finalises it, and the loser does
     * not report a spurious failure over the winner's successful save.
     */
    private var recorder: ArStreamRecorder?
        get() = recorderRef.get()
        set(v) { recorderRef.set(v) }

    private val state = ArStreamSessionState()

    // Volatile: written on main, read on the socket threads. Without it a socket thread can keep
    // seeing a stale null decoder, and onDestroy can miss a client and leak sockets + the P2P
    // group + the joiner's registered BroadcastReceiver.
    @Volatile private var client: ArStreamClient? = null
    @Volatile private var decoder: ScreenStreamDecoder? = null

    /**
     * Last codec-config (SPS/PPS) frame seen on this connection.
     *
     * ScreenStreamDecoder caches nothing and the glasses send config once per connect, so a frame
     * that arrives before the decoder exists is gone for good -- and a decoder without it renders
     * black forever with no error anywhere. Keeping it here lets every new decoder be primed.
     */
    @Volatile private var lastConfigFrame: ByteArray? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var commandId: String? = null
    private var textureSurface: Surface? = null

    @Volatile private var streamStarted = false

    /** Guards against a duplicate result broadcast spawning a second connect thread. */
    @Volatile private var resultConsumed = false

    /** Set in onDestroy so work posted from background threads cannot resurrect the session. */
    @Volatile private var destroyed = false

    /** Connect parameters kept for a single mid-session reconnect after a transient drop. */
    @Volatile private var lastDetails: JSONObject? = null
    @Volatile private var lastVideoPort = ArStreamProtocol.VIDEO_PORT
    @Volatile private var lastAudioPort = ArStreamProtocol.AUDIO_PORT

    /** One retry only -- a reconnect loop would hide a genuinely dead session forever. */
    @Volatile private var reconnectAttempted = false

    private val replyTimeout = Runnable {
        if (!streamStarted) fail("Glasses did not answer the stream request")
    }

    private val connectTimeout = Runnable {
        if (!streamStarted) fail("Could not connect to the glasses stream")
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != ListenerService.ACTION_AR_STREAM_RESULT) return
            val id = intent.getStringExtra("command_id") ?: return
            if (id != commandId) return
            val resultJson = intent.getStringExtra("result") ?: return
            // One result per session: a duplicate would spawn a second connect thread and orphan
            // the first client's sockets.
            if (resultConsumed) return
            resultConsumed = true
            handleStartResult(resultJson)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_stream)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Playback is USAGE_MEDIA -> STREAM_MUSIC (see ArStreamClient.startPlayback), so the
        // hardware keys must adjust that stream and not the ringer. Deliberately NOT
        // MODE_IN_COMMUNICATION: that routes to the earpiece at call volume and reads as silence.
        volumeControlStream = AudioManager.STREAM_MUSIC

        textureView = findViewById(R.id.arStreamTexture)
        status = findViewById(R.id.txtArStreamStatus)
        btnPhoneMic = findViewById(R.id.btnPhoneMic)
        btnGlassesMic = findViewById(R.id.btnGlassesMic)

        btnRecord = findViewById(R.id.btnArRecord)
        txtRecordTime = findViewById(R.id.txtArRecordTime)

        btnPhoneMic.setOnClickListener { togglePhoneMic() }
        btnGlassesMic.setOnClickListener { toggleGlassesMic() }
        btnRecord.setOnClickListener { toggleRecording() }

        sizeTextureToAspect()
        setupSurface()
        enterImmersive()
        registerResultReceiver()
        startSession()
    }

    private fun registerResultReceiver() {
        val filter = IntentFilter(ListenerService.ACTION_AR_STREAM_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(resultReceiver, filter)
        }
    }

    /**
     * Letterbox the TextureView to the stream's aspect ratio.
     *
     * A match_parent TextureView stretches the decoded frame to the phone's screen shape, which on
     * a 20:9 phone showing a 4:3 stream both distorted the picture and ran it past the edges.
     * Sizing the view itself keeps the geometry correct without any scaling matrix.
     */
    private fun sizeTextureToAspect() {
        textureView.post {
            val parent = textureView.parent as? View ?: return@post
            val availW = parent.width
            val availH = parent.height
            if (availW == 0 || availH == 0) return@post
            val scale = minOf(
                availW.toFloat() / VIDEO_WIDTH,
                availH.toFloat() / VIDEO_HEIGHT
            )
            textureView.layoutParams = textureView.layoutParams.apply {
                width = (VIDEO_WIDTH * scale).toInt()
                height = (VIDEO_HEIGHT * scale).toInt()
            }
            textureView.requestLayout()
        }
    }

    private fun setupSurface() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                textureSurface = Surface(st)
                if (streamStarted) startDecoder()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // The decoder cannot re-attach to a new Surface, and it caches no SPS/PPS. So tear
                // it down and mark that the rebuilt one will need a fresh config + IDR, or the
                // view comes back permanently black with nothing logged.
                decoder?.stop()
                decoder = null
                state.onSurfaceDestroyed()
                textureSurface?.release()
                textureSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun startDecoder() {
        val surface = textureSurface ?: return
        if (decoder != null) return
        val d = ScreenStreamDecoder(VIDEO_WIDTH, VIDEO_HEIGHT).also { it.start(surface) }
        // Replay the cached SPS/PPS before anything else reaches the decoder. Re-requesting from
        // the glasses is a fallback, not the primary path: the encoder emits config once and a
        // sync-frame request does not reliably re-emit it.
        lastConfigFrame?.let { d.feedFrame(it) }
        decoder = d
        // Re-check after publishing: a config frame that arrived while the decoder was being
        // constructed saw decoder == null and was dropped. Feeding it twice is harmless; missing
        // it means a black surface for the rest of the session.
        lastConfigFrame?.let { d.feedFrame(it) }
        state.onDecoderStarted()
        client?.requestKeyframe()
    }

    /** Called on the video socket thread. */
    private fun onVideoFrame(frame: ByteArray) {
        // Header: [1] flags, bit1 = codec config.
        if (frame.size > 1 && (frame[1].toInt() and 0x02) != 0) {
            lastConfigFrame = frame
        }
        decoder?.feedFrame(frame)
        recorder?.onVideoFrame(frame)
    }

    private fun toggleRecording() {
        val rec = recorderRef.getAndSet(null)
        if (rec == null) startRecording() else stopRecording(rec)
    }

    private fun startRecording() {
        val config = lastConfigFrame
        if (config == null) {
            // Without SPS/PPS the muxer cannot describe the track; starting anyway would produce a
            // file no player can open, which is worse than refusing.
            snack(getString(R.string.ar_stream_recording_not_ready))
            return
        }
        val rec = ArStreamRecorder(applicationContext)
        try {
            rec.start(config, VIDEO_WIDTH, VIDEO_HEIGHT)
        } catch (e: Exception) {
            LogCollector.e(TAG, "record start failed: ${e.message}")
            snack(getString(R.string.ar_stream_recording_failed))
            return
        }
        // Publish the taps only after a successful start, so a failed start cannot leave the
        // socket threads feeding a dead recorder.
        client?.onUplinkAudio = { pcm -> recorder?.onGlassesAudio(pcm) }
        client?.onPhoneMicAudio = { pcm, len -> recorder?.onPhoneMicAudio(pcm, len) }
        recorder = rec
        // The glasses only send config once per connection, so the recording would otherwise start
        // at whatever GOP boundary comes next -- ask for an IDR immediately.
        client?.requestKeyframe()

        btnRecord.setImageResource(R.drawable.ic_stop)
        btnRecord.contentDescription = getString(R.string.ar_stream_record_stop)
        txtRecordTime.visibility = View.VISIBLE
        txtRecordTime.text = formatElapsed(0)
        mainHandler.post(recordTicker)
        snack(getString(R.string.ar_stream_recording_started))
    }

    /** @param rec already claimed from [recorderRef] by the caller. */
    private fun stopRecording(rec: ArStreamRecorder) {
        client?.onUplinkAudio = null
        client?.onPhoneMicAudio = null
        mainHandler.removeCallbacks(recordTicker)

        btnRecord.setImageResource(R.drawable.ic_fiber_manual_record)
        btnRecord.contentDescription = getString(R.string.ar_stream_record_start)
        txtRecordTime.visibility = View.GONE

        // stop() muxes, copies to MediaStore and joins codec threads -- all illegal on main.
        thread(name = "ArStream-recStop") {
            val uri = try {
                rec.stop()
            } catch (e: Exception) {
                LogCollector.e(TAG, "record stop failed: ${e.message}")
                null
            }
            mainHandler.post {
                if (isFinishing || destroyed) return@post
                snack(
                    getString(
                        if (uri != null) R.string.ar_stream_recording_saved
                        else R.string.ar_stream_recording_failed
                    )
                )
            }
        }
    }

    private val recordTicker = object : Runnable {
        override fun run() {
            val rec = recorder ?: return
            txtRecordTime.text = formatElapsed(rec.elapsedMs)
            mainHandler.postDelayed(this, 500L)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val total = ms / 1000
        return String.format(java.util.Locale.US, "REC %02d:%02d", total / 60, total % 60)
    }

    private fun snack(text: String) {
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_SHORT).show()
    }

    private fun startSession() {
        val id = "arstream_${UUID.randomUUID().toString().take(8)}"
        commandId = id
        status.text = getString(R.string.ar_stream_connecting)

        val intent = Intent(this, ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", id)
            putExtra("type", "start_ar_stream")
            putExtra("params", "{}")
        }
        startService(intent)

        // Only covers the BT round trip. The connect phase (group join up to 30s + network wait +
        // TCP connect) gets its own, longer budget once the glasses have answered -- one combined
        // timeout would kill healthy sessions on a slow WiFi-Direct negotiation.
        mainHandler.postDelayed(replyTimeout, REPLY_TIMEOUT_MS)
    }

    private fun handleStartResult(resultJson: String) {
        val obj = try {
            JSONObject(resultJson)
        } catch (e: Exception) {
            fail("Bad result from glasses: ${e.message}")
            return
        }
        if (!obj.optBoolean("success", false)) {
            fail(obj.optString("error", "Glasses refused to start the stream"))
            return
        }
        val details = try {
            JSONObject(obj.optString("details", "{}"))
        } catch (e: Exception) {
            fail("Bad WiFi-Direct details: ${e.message}")
            return
        }
        val videoPort = obj.optInt("video_port", ArStreamProtocol.VIDEO_PORT)
        val audioPort = obj.optInt("audio_port", ArStreamProtocol.AUDIO_PORT)

        // The glasses answered, so swap the short reply budget for the long connect budget.
        mainHandler.removeCallbacks(replyTimeout)
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)

        // Remembered so a mid-session stream drop can be retried without another BT round trip.
        lastDetails = details
        lastVideoPort = videoPort
        lastAudioPort = audioPort

        thread(name = "ArStream-connect") { connectClient(details, videoPort, audioPort) }
    }

    private fun connectClient(details: JSONObject, videoPort: Int, audioPort: Int) {
        // Application context, not the Activity: this object outlives onDestroy briefly while its
        // socket threads unwind, and holding the Activity there would leak the whole view tree.
        val c = ArStreamClient(applicationContext, state)
        c.onVideoFrame = { frame -> onVideoFrame(frame) }
        c.onError = { msg -> mainHandler.post { fail(msg) } }
        // A mid-session reconnect builds a NEW client; without re-arming the taps here an
        // in-flight recording would silently lose all audio after the drop.
        if (recorder != null) {
            c.onUplinkAudio = { pcm -> recorder?.onGlassesAudio(pcm) }
            c.onPhoneMicAudio = { pcm, len -> recorder?.onPhoneMicAudio(pcm, len) }
        }
        c.onConnected = {
            mainHandler.post {
                if (destroyed) return@post
                streamStarted = true
                mainHandler.removeCallbacks(connectTimeout)
                status.visibility = View.GONE
                startDecoder()
            }
        }
        client = c

        if (destroyed) {
            // Raced with teardown: nothing has connected yet, so just drop it.
            c.disconnect()
            return
        }

        if (!c.connect(details, videoPort, audioPort)) return

        // Publish then re-check: a plain "check, then assign" still loses the race if onDestroy
        // runs between the two, leaving a live mic tap wired to a dead session forever.
        ListenerService.arStreamMicSink = { samples -> c.sendMicAudio(samples, samples.size) }
        if (destroyed) {
            ListenerService.arStreamMicSink = null
            c.disconnect()
            return
        }
    }

    private fun togglePhoneMic() {
        val muted = !state.isPhoneMicMuted()
        client?.setPhoneMicMuted(muted) ?: state.setPhoneMicMuted(muted)
        btnPhoneMic.setImageResource(
            if (muted) R.drawable.ic_mic_phone_off else R.drawable.ic_mic_phone_on
        )
        btnPhoneMic.contentDescription = getString(
            if (muted) R.string.ar_stream_phone_mic_off else R.string.ar_stream_phone_mic_on
        )
        snack(
            getString(
                if (muted) R.string.ar_stream_phone_audio_disabled
                else R.string.ar_stream_phone_audio_enabled
            )
        )
    }

    private fun toggleGlassesMic() {
        val muted = !state.isGlassesMicMuted()
        client?.setGlassesMicMuted(muted) ?: state.setGlassesMicMuted(muted)
        btnGlassesMic.setImageResource(
            if (muted) R.drawable.ic_mic_glasses_off else R.drawable.ic_mic_glasses_on
        )
        btnGlassesMic.contentDescription = getString(
            if (muted) R.string.ar_stream_glasses_mic_off else R.string.ar_stream_glasses_mic_on
        )
        snack(
            getString(
                if (muted) R.string.ar_stream_glasses_audio_disabled
                else R.string.ar_stream_glasses_audio_enabled
            )
        )
    }

    private fun fail(message: String) {
        // A socket thread's error post can beat removeCallbacksAndMessages in onDestroy.
        if (destroyed || isFinishing) return

        // A running session that hits EOF on the video socket is usually a transient WiFi-Direct
        // drop, not the glasses going away -- killing the whole activity for it made one bad
        // frame end the session. Retry the socket connect ONCE (the glasses server is still
        // listening and re-primes any new client); only a second failure is fatal.
        if (streamStarted && !reconnectAttempted && message.contains(VIDEO_ENDED_MARKER, true)) {
            val d = lastDetails
            if (d != null) {
                reconnectAttempted = true
                LogCollector.e(TAG, "video stream ended, attempting single reconnect")
                streamStarted = false
                status.visibility = View.VISIBLE
                status.text = getString(R.string.ar_stream_connecting)
                ListenerService.arStreamMicSink = null
                val old = client
                client = null
                decoder?.stop()
                decoder = null
                lastConfigFrame = null
                val vp = lastVideoPort
                val ap = lastAudioPort
                thread(name = "ArStream-reconnect") {
                    try { old?.disconnect() } catch (_: Exception) {}
                    if (!destroyed) connectClient(d, vp, ap)
                }
                mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
                return
            }
        }

        LogCollector.e(TAG, "session failed: $message")
        status.visibility = View.VISIBLE
        status.text = message
        mainHandler.postDelayed({ if (!isFinishing) finish() }, ERROR_DISMISS_MS)
    }

    private fun enterImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Set FIRST: background threads check this before installing the mic sink or touching UI,
        // so nothing can be resurrected after teardown begins.
        destroyed = true
        // Unconditional teardown: this is the only guarantee that the glasses camera, both mics
        // and the P2P group are released no matter how the screen was left.
        ListenerService.arStreamMicSink = null
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)

        // Finalise any running recording BEFORE the client goes away. An MP4 abandoned without
        // MediaMuxer.stop() has no moov atom and is unplayable, so this must happen on every exit
        // path, not just the explicit stop button.
        val rec = recorderRef.getAndSet(null)
        if (rec != null) {
            val c0 = client
            c0?.onUplinkAudio = null
            c0?.onPhoneMicAudio = null
            thread(name = "ArStream-recFinalize") {
                try { rec.stop() } catch (e: Exception) {
                    LogCollector.e(TAG, "recorder finalize failed: ${e.message}")
                }
            }
        }

        decoder?.stop()
        decoder = null
        textureSurface?.release()
        textureSurface = null
        lastConfigFrame = null

        // Off the main thread: disconnect() writes CTRL_STOP to a socket and joins a thread, both
        // of which are illegal on main (NetworkOnMainThreadException would be swallowed by the
        // catch inside, silently skipping the stop notification). disconnect() is idempotent.
        val c = client
        client = null
        if (c != null) thread(name = "ArStream-teardown") { c.disconnect() }

        val stopId = "arstream_stop_${UUID.randomUUID().toString().take(8)}"
        try {
            startService(Intent(this, ListenerService::class.java).apply {
                action = ListenerService.ACTION_ADB_DISPATCH
                putExtra("command_id", stopId)
                putExtra("type", "stop_ar_stream")
                putExtra("params", "{}")
            })
        } catch (e: Exception) {
            LogCollector.e(TAG, "stop_ar_stream dispatch failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ArStreamActivity"
        /** The ArStreamClient error text that means "socket EOF", i.e. a retryable drop. */
        const val VIDEO_ENDED_MARKER = "video stream ended"

        /** Portrait 4:3, matching the glasses compositor output (sensor is 4032x3024). */
        const val VIDEO_WIDTH = 720
        const val VIDEO_HEIGHT = 960
        /** BT round trip to the glasses (they postDelay 1.5s before even starting). */
        const val REPLY_TIMEOUT_MS = 20_000L

        /** WiFi-Direct group join (up to 30s) + network wait (3s) + two TCP connects (10s each). */
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val ERROR_DISMISS_MS = 4_000L
    }
}
