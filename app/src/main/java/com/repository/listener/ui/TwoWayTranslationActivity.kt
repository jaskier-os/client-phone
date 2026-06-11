package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.repository.listener.config.AppConfig
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.repository.listener.R
import com.repository.listener.service.ListenerService
import com.repository.listener.ui.views.MicLevelBarsView
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.util.UUID

/**
 * Full-screen UI for two-way translation. The wearer reads incoming
 * source-language audio (translated -> target) on the glasses; the OTHER
 * person reads the wearer's target-language audio (translated -> source) on
 * this screen.
 *
 * Lifecycle owns the translation session: onCreate fires start_translation
 * with two_way=true; onDestroy fires stop_translation. Closing the activity
 * (back, swipe, app kill) ALWAYS stops capture.
 */
class TwoWayTranslationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TwoWayTranslation"
        const val EXTRA_FROM_CODE = "from_code"
        const val EXTRA_TO_CODE = "to_code"
        const val EXTRA_FROM_NAME = "from_name"
        const val EXTRA_TO_NAME = "to_name"
        const val EXTRA_FROM_NLLB = "from_nllb"
        const val EXTRA_TO_NLLB = "to_nllb"
        const val EXTRA_FONT_SIZE = "font_size"

        // Service -> activity. text + partial flag.
        const val ACTION_TWO_WAY_PHONE_RESULT = "com.repository.listener.TWO_WAY_PHONE_RESULT"
        const val EXTRA_SEG_ID = "seg_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PARTIAL = "partial"

        private const val MAX_VISIBLE_CHUNKS = 5
        private const val FADE_OUT_MS = 15_000L
        private const val FADE_DIM_AT_MS = 10_000L

        // Service -> activity. raw int16 PCM frame for spectrogram.
        const val ACTION_TWO_WAY_AUDIO_LEVEL = "com.repository.listener.TWO_WAY_AUDIO_LEVEL"
        const val EXTRA_PCM = "pcm"

        const val ACTION_TWO_WAY_CLOSED = "com.repository.listener.TWO_WAY_CLOSED"

        fun launch(
            ctx: Context,
            from: TranslationConfigDialog.Language,
            to: TranslationConfigDialog.Language,
            fontSize: Int
        ) {
            val intent = Intent(ctx, TwoWayTranslationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_FROM_CODE, from.code.lowercase())
                putExtra(EXTRA_TO_CODE, to.code.lowercase())
                putExtra(EXTRA_FROM_NAME, from.name)
                putExtra(EXTRA_TO_NAME, to.name)
                putExtra(EXTRA_FROM_NLLB, from.nllbCode)
                putExtra(EXTRA_TO_NLLB, to.nllbCode)
                putExtra(EXTRA_FONT_SIZE, fontSize)
            }
            ctx.startActivity(intent)
        }

        /** Launch from raw config strings (no Language objects needed). */
        fun launchFromConfig(ctx: Context) {
            val intent = Intent(ctx, TwoWayTranslationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_FROM_CODE, AppConfig.getTranslationFromLanguage(ctx).lowercase())
                putExtra(EXTRA_TO_CODE, AppConfig.getTranslationToLanguage(ctx).lowercase())
                putExtra(EXTRA_FROM_NAME, AppConfig.getTranslationFromLanguage(ctx).uppercase())
                putExtra(EXTRA_TO_NAME, AppConfig.getTranslationToLanguage(ctx).uppercase())
                putExtra(EXTRA_FROM_NLLB, "")
                putExtra(EXTRA_TO_NLLB, "")
                putExtra(EXTRA_FONT_SIZE, AppConfig.getTranslationFontSize(ctx))
            }
            ctx.startActivity(intent)
        }
    }

    private lateinit var chunksContainer: android.widget.LinearLayout
    private lateinit var chunksScroll: android.widget.ScrollView
    private lateinit var txtLangs: TextView
    // Spectrogram removed -- inward mic comes from glasses ch0, not phone mic
    private val handler = Handler(Looper.getMainLooper())
    private var fromCode = ""
    private var toCode = ""
    private var fromNllb = ""
    private var toNllb = ""
    private var fontSize = 14
    private var sessionStarted = false

    // Per-segment view tracking. Same pattern as the glasses MainActivity:
    // partial updates rewrite the current view in place; final marks it done
    // and schedules a 10s/15s fade-out.
    private val segmentViews = mutableMapOf<Int, TextView>()

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val segId = intent.getIntExtra(EXTRA_SEG_ID, -1)
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            val partial = intent.getBooleanExtra(EXTRA_PARTIAL, true)
            if (segId < 0 || text.isEmpty()) return
            handlePhoneResult(segId, text, partial)
        }
    }

    // Audio level receiver removed -- no spectrogram in two-way mode
    private val audioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // no-op: spectrogram removed
        }
    }

    // decayTick removed -- no spectrogram

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge black, screen on, secure-on-lock-bypass not needed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
        }
        setContentView(R.layout.activity_two_way_translation)

        txtLangs = findViewById(R.id.txtTwoWayLangs)
        chunksScroll = findViewById(R.id.twoWayScroll)
        chunksContainer = findViewById(R.id.twoWayChunks)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTwoWayBack)
            .setOnClickListener { finish() }

        fromCode = intent.getStringExtra(EXTRA_FROM_CODE).orEmpty()
        toCode = intent.getStringExtra(EXTRA_TO_CODE).orEmpty()
        fromNllb = intent.getStringExtra(EXTRA_FROM_NLLB).orEmpty()
        toNllb = intent.getStringExtra(EXTRA_TO_NLLB).orEmpty()
        fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 14)

        val fromName = intent.getStringExtra(EXTRA_FROM_NAME).orEmpty()
        val toName = intent.getStringExtra(EXTRA_TO_NAME).orEmpty()
        // Phone shows the OTHER person's reading direction: target -> source.
        // (Wearer speaks target, other person reads source on this screen.)
        txtLangs.text = "${toName.uppercase()} -> ${fromName.uppercase()}"

        registerReceiver(
            resultReceiver,
            IntentFilter(ACTION_TWO_WAY_PHONE_RESULT),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            audioReceiver,
            IntentFilter(ACTION_TWO_WAY_AUDIO_LEVEL),
            Context.RECEIVER_NOT_EXPORTED
        )
        startTranslation()
    }

    private fun startTranslation() {
        if (sessionStarted) return
        val params = JSONObject().apply {
            put("from_language", fromCode)
            put("to_language", toCode)
            put("from_nllb", fromNllb)
            put("to_nllb", toNllb)
            put("font_size", fontSize)
            put("audio_source", "glasses")
            put("provider", "azure")
            put("two_way", true)
        }
        val intent = Intent(this, ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", "ui_2way_${UUID.randomUUID().toString().take(8)}")
            putExtra("type", "start_translation")
            putExtra("params", params.toString())
        }
        startService(intent)
        sessionStarted = true
        LogCollector.i(TAG, "Two-way translation started: $fromCode <-> $toCode")
    }

    private fun stopTranslation() {
        if (!sessionStarted) return
        // Closing the two-way screen does NOT stop translation outright -- it
        // hands off to the regular one-way flow (source -> target on glasses)
        // so the wearer keeps reading translations after the phone screen is
        // gone. Sending start_translation with two_way=false reuses the existing
        // teardown-then-init path inside the service.
        val params = JSONObject().apply {
            put("from_language", fromCode)
            put("to_language", toCode)
            put("from_nllb", fromNllb)
            put("to_nllb", toNllb)
            put("font_size", fontSize)
            put("audio_source", "glasses")
            put("provider", "azure")
            put("two_way", false)
        }
        val intent = Intent(this, ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", "ui_2way_to1way_${UUID.randomUUID().toString().take(8)}")
            putExtra("type", "start_translation")
            putExtra("params", params.toString())
        }
        startService(intent)
        sessionStarted = false
        LogCollector.i(TAG, "Two-way ended; handing off to one-way $fromCode -> $toCode")
    }

    private fun handlePhoneResult(segId: Int, text: String, partial: Boolean) {
        val existing = segmentViews[segId]
        val view = existing ?: createSegmentView(segId).also {
            segmentViews[segId] = it
            chunksContainer.addView(it)
            // Slide-up entry animation matching the glasses look.
            it.translationY = 16f * resources.displayMetrics.density
            it.alpha = 0f
            it.animate().translationY(0f).alpha(1f).setDuration(220L).start()
        }
        view.text = text
        view.alpha = if (partial) 0.85f else 1f

        // Cap visible chunks: drop oldest.
        while (chunksContainer.childCount > MAX_VISIBLE_CHUNKS) {
            val oldView = chunksContainer.getChildAt(0) as? TextView ?: break
            val oldId = oldView.tag as? Int
            chunksContainer.removeViewAt(0)
            if (oldId != null) {
                segmentViews.remove(oldId)
                handler.removeCallbacksAndMessages(oldId)
            }
        }

        // Schedule fade-out only on finals so partials don't decay mid-utterance.
        if (!partial) scheduleFadeOut(segId)

        chunksScroll.post { chunksScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createSegmentView(segId: Int): TextView {
        return TextView(this).apply {
            tag = segId
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            setLineSpacing(0f, 1.15f)
            val pad = (8f * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
    }

    private fun scheduleFadeOut(segId: Int) {
        // 10s: dim to 50%
        handler.postDelayed({
            segmentViews[segId]?.animate()?.alpha(0.5f)?.setDuration(4500L)?.start()
        }, FADE_DIM_AT_MS)
        // 15s: drop entirely
        handler.postDelayed({
            val v = segmentViews.remove(segId) ?: return@postDelayed
            v.animate().alpha(0f).setDuration(500L).withEndAction {
                chunksContainer.removeView(v)
            }.start()
        }, FADE_OUT_MS)
    }

    override fun onDestroy() {
        // cleanup
        try { unregisterReceiver(resultReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(audioReceiver) } catch (_: Throwable) {}
        stopTranslation()
        // Mark two-way as disabled directly (fragment may be paused and miss the broadcast)
        AppConfig.setTranslationTwoWay(this, false)
        sendBroadcast(Intent(ACTION_TWO_WAY_CLOSED).apply { setPackage(packageName) })
        super.onDestroy()
    }
}
