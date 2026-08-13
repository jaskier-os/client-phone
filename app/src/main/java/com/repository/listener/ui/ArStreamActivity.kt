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
import com.repository.listener.R
import com.repository.listener.arstream.ArStreamClient
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

    private val state = ArStreamSessionState()
    private var client: ArStreamClient? = null
    private var decoder: ScreenStreamDecoder? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var commandId: String? = null
    private var textureSurface: Surface? = null
    private var streamStarted = false

    /** Audio mode we found before taking over, restored verbatim on teardown. */
    private var previousAudioMode: Int? = null
    private var previousSpeakerphone: Boolean? = null

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != ListenerService.ACTION_AR_STREAM_RESULT) return
            val id = intent.getStringExtra("command_id") ?: return
            if (id != commandId) return
            val resultJson = intent.getStringExtra("result") ?: return
            handleStartResult(resultJson)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_stream)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textureView = findViewById(R.id.arStreamTexture)
        status = findViewById(R.id.txtArStreamStatus)
        btnPhoneMic = findViewById(R.id.btnPhoneMic)
        btnGlassesMic = findViewById(R.id.btnGlassesMic)

        btnPhoneMic.setOnClickListener { togglePhoneMic() }
        btnGlassesMic.setOnClickListener { toggleGlassesMic() }

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
                textureSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun startDecoder() {
        val surface = textureSurface ?: return
        if (decoder != null) return
        decoder = ScreenStreamDecoder(VIDEO_WIDTH, VIDEO_HEIGHT).also { it.start(surface) }
        state.onDecoderStarted()
        // Fresh decoder, no cached parameter sets: ask the glasses to resend them.
        client?.requestKeyframe()
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

        mainHandler.postDelayed({
            if (!streamStarted) fail("Glasses did not start the stream")
        }, START_TIMEOUT_MS)
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

        thread(name = "ArStream-connect") { connectClient(details, videoPort, audioPort) }
    }

    private fun connectClient(details: JSONObject, videoPort: Int, audioPort: Int) {
        val c = ArStreamClient(this, state)
        c.onVideoFrame = { frame -> decoder?.feedFrame(frame) }
        c.onError = { msg -> mainHandler.post { fail(msg) } }
        c.onConnected = {
            mainHandler.post {
                streamStarted = true
                status.visibility = View.GONE
                startDecoder()
            }
        }
        client = c

        if (!c.connect(details, videoPort, audioPort)) return

        mainHandler.post {
            engageVoiceCommunicationMode()
            // Tap the existing recorder rather than opening a second AudioRecord.
            ListenerService.arStreamMicSink = { samples -> c.sendMicAudio(samples, samples.size) }
        }
    }

    /**
     * Put the phone in voice-communication mode for the session. This is the path the platform's
     * echo cancellation is actually tuned for; the wake-word recorder already attaches an
     * AcousticEchoCanceler, and MODE_IN_COMMUNICATION is what makes it behave like a call.
     */
    private fun engageVoiceCommunicationMode() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            previousAudioMode = am.mode
            @Suppress("DEPRECATION")
            previousSpeakerphone = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            LogCollector.e(TAG, "audio mode switch failed: ${e.message}")
        }
    }

    private fun restoreAudioMode() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            previousAudioMode?.let { am.mode = it }
            @Suppress("DEPRECATION")
            previousSpeakerphone?.let { am.isSpeakerphoneOn = it }
        } catch (e: Exception) {
            LogCollector.e(TAG, "audio mode restore failed: ${e.message}")
        } finally {
            previousAudioMode = null
            previousSpeakerphone = null
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
    }

    private fun fail(message: String) {
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
        // Unconditional teardown: this is the only guarantee that the glasses camera, both mics
        // and the P2P group are released no matter how the screen was left.
        ListenerService.arStreamMicSink = null
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)

        decoder?.stop()
        decoder = null
        textureSurface = null

        client?.disconnect()
        client = null
        restoreAudioMode()

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
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val START_TIMEOUT_MS = 45_000L
        const val ERROR_DISMISS_MS = 4_000L
    }
}
