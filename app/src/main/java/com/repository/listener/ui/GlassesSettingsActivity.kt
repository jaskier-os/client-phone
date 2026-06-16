package com.repository.listener.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class GlassesSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GlassesSettings"

        val screenTimeoutOptions = listOf(
            "Always on" to "0",
            "5 seconds" to "5",
            "10 seconds" to "10",
            "15 seconds" to "15",
            "20 seconds" to "20",
            "30 seconds" to "30",
            "45 seconds" to "45",
            "1 minute" to "60",
            "5 minutes" to "300",
            "10 minutes" to "600",
            "20 minutes" to "1200",
            "1 hour" to "3600",
            "24 hours" to "86400"
        )

        val powerTimeoutOptions = listOf(
            "Off" to "0",
            "10 minutes" to "10",
            "20 minutes (default)" to "20",
            "30 minutes" to "30",
            "1 hour" to "60",
            "2 hours" to "120",
            "3 hours" to "180"
        )

        val notificationSoundOptions = listOf("On" to "true", "Off" to "false")

        val notificationDurationOptions = listOf(
            "3 seconds" to "3",
            "5 seconds (default)" to "5",
            "10 seconds" to "10",
            "15 seconds" to "15"
        )

    }

    private data class SettingsSnapshot(
        val brightness: Int,
        val screenTimeout: String,
        val powerTimeout: String,
        val notificationSound: String,
        val notificationDuration: String,
        val chatFontSize: String,
        val wakewordEnabled: Boolean
    )

    private lateinit var seekBrightness: SeekBar
    private lateinit var lblBrightness: TextView
    private lateinit var dropdownScreenTimeout: AutoCompleteTextView
    private lateinit var dropdownPowerTimeout: AutoCompleteTextView
    private lateinit var dropdownNotificationSound: AutoCompleteTextView
    private lateinit var dropdownNotificationDuration: AutoCompleteTextView
    private lateinit var seekChatFontSize: SeekBar
    private lateinit var lblChatFontSize: TextView
    private lateinit var swWakeword: SwitchCompat

    private lateinit var initialSnapshot: SettingsSnapshot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_glasses_settings)

        // Bind views
        lblBrightness = findViewById(R.id.lblBrightness)
        seekBrightness = findViewById(R.id.seekBrightness)
        dropdownScreenTimeout = findViewById(R.id.dropdownScreenTimeout)
        dropdownPowerTimeout = findViewById(R.id.dropdownPowerTimeout)
        dropdownNotificationSound = findViewById(R.id.dropdownNotificationSound)
        dropdownNotificationDuration = findViewById(R.id.dropdownNotificationDuration)
        seekChatFontSize = findViewById(R.id.seekChatFontSize)
        lblChatFontSize = findViewById(R.id.lblChatFontSize)
        swWakeword = findViewById(R.id.swWakeword)

        loadValues()
        setupListeners()
        initialSnapshot = captureSnapshot()

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener {
            val current = captureSnapshot()
            persistAll(current)
            applyChanges(initialSnapshot, current)
            initialSnapshot = current
            Toast.makeText(this, "Settings applied", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.view.View>(R.id.btnClose).setOnClickListener { finish() }
    }

    private fun loadValues() {
        // Brightness
        val brightness = AppConfig.getGlassesBrightness(this)
        seekBrightness.progress = brightness
        lblBrightness.text = "Brightness: $brightness"

        // Dropdowns
        setupDropdown(dropdownScreenTimeout, screenTimeoutOptions, AppConfig.getGlassesScreenTimeout(this))
        setupDropdown(dropdownPowerTimeout, powerTimeoutOptions, AppConfig.getGlassesPowerTimeout(this))
        setupDropdown(dropdownNotificationSound, notificationSoundOptions, AppConfig.getGlassesNotificationSound(this))
        setupDropdown(dropdownNotificationDuration, notificationDurationOptions, AppConfig.getGlassesNotificationDuration(this))

        // Chat font size
        val storedChatFontSize = AppConfig.getGlassesChatFontSize(this)
        val chatFontSizeValue = storedChatFontSize.toIntOrNull() ?: 14
        seekChatFontSize.progress = chatFontSizeToProgress(chatFontSizeValue)
        lblChatFontSize.text = "Chat font size: ${chatFontSizeValue}sp"

        // Wake word and VAD switch
        swWakeword.isChecked = AppConfig.getGlassesWakewordEnabled(this)
    }

    private fun setupListeners() {
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                lblBrightness.text = "Brightness: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekChatFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                lblChatFontSize.text = "Chat font size: ${progressToChatFontSize(progress)}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun captureSnapshot(): SettingsSnapshot {
        return SettingsSnapshot(
            brightness = seekBrightness.progress,
            screenTimeout = getDropdownValue(dropdownScreenTimeout, screenTimeoutOptions),
            powerTimeout = getDropdownValue(dropdownPowerTimeout, powerTimeoutOptions),
            notificationSound = getDropdownValue(dropdownNotificationSound, notificationSoundOptions),
            notificationDuration = getDropdownValue(dropdownNotificationDuration, notificationDurationOptions),
            chatFontSize = progressToChatFontSize(seekChatFontSize.progress).toString(),
            wakewordEnabled = swWakeword.isChecked
        )
    }

    private fun persistAll(s: SettingsSnapshot) {
        AppConfig.setGlassesBrightness(this, s.brightness)
        AppConfig.setGlassesScreenTimeout(this, s.screenTimeout)
        AppConfig.setGlassesPowerTimeout(this, s.powerTimeout)
        AppConfig.setGlassesNotificationSound(this, s.notificationSound)
        AppConfig.setGlassesNotificationDuration(this, s.notificationDuration)
        AppConfig.setGlassesChatFontSize(this, s.chatFontSize)
        AppConfig.setGlassesWakewordEnabled(this, s.wakewordEnabled)
    }

    /** Send settings to glasses listener via CH_SETTINGS (GlassesConfig.applySettings). */
    private fun sendAppSettings(vararg pairs: Pair<String, String>) {
        val svc = ListenerService.phoneBtHostInstance ?: run {
            LogCollector.w(TAG, "sendAppSettings: phoneBtHostInstance null; settings push dropped")
            return
        }
        val json = JSONObject().apply {
            pairs.forEach { (key, value) -> put(key, value) }
        }.toString()
        try {
            svc.sendSettings(json)
        } catch (e: Exception) {
            LogCollector.e(TAG, "sendAppSettings failed: ${e.message}")
        }
    }

    // Debounce token + handler for brightness/screen-timeout/power-timeout
    // pushes over CH_SETTINGS. Callers that invoke this from a continuous
    // slider can call queueSettingsPush() multiple times and only the last
    // one within a 300ms window will actually fire.
    private val settingsPushToken = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun queueSettingsPush() {
        mainHandler.removeCallbacksAndMessages(settingsPushToken)
        mainHandler.postAtTime(
            { ListenerService.phoneBtHostInstance?.pushGlassesSettings() },
            settingsPushToken,
            SystemClock.uptimeMillis() + 300
        )
    }

    private fun applyChanges(old: SettingsSnapshot, new: SettingsSnapshot) {
        // Brightness is now part of the CH_SETTINGS JSON contract --
        // persist via AppConfig and fire a debounced full push to glasses.
        if (old.brightness != new.brightness) {
            AppConfig.setGlassesBrightness(this, new.brightness)
            LogCollector.i(TAG, "Set brightness via CH_SETTINGS: ${new.brightness}")
            queueSettingsPush()
        }

        // Screen timeout and power timeout also ride on the CH_SETTINGS JSON
        // contract now -- persist via AppConfig (which also clamps) and push.
        if (old.screenTimeout != new.screenTimeout && new.screenTimeout.isNotEmpty()) {
            val secs = new.screenTimeout.toIntOrNull()
            if (secs != null) {
                AppConfig.setGlassesScreenTimeoutSec(this, secs)
                LogCollector.i(TAG, "Set screen timeout via CH_SETTINGS: ${secs}s")
                queueSettingsPush()
            } else {
                LogCollector.e(TAG, "Invalid screen timeout value: ${new.screenTimeout}")
            }
        }

        if (old.powerTimeout != new.powerTimeout && new.powerTimeout.isNotEmpty()) {
            val mins = new.powerTimeout.toIntOrNull()
            if (mins != null) {
                AppConfig.setGlassesPowerTimeoutMin(this, mins)
                LogCollector.i(TAG, "Set power timeout via CH_SETTINGS: ${mins}min")
                queueSettingsPush()
            } else {
                LogCollector.e(TAG, "Invalid power timeout value: ${new.powerTimeout}")
            }
        }

        if (old.notificationSound != new.notificationSound && new.notificationSound.isNotEmpty()) {
            try {
                sendAppSettings("settings_msg_notification_sound_enabled" to new.notificationSound)
                LogCollector.i(TAG, "Set notification sound: ${new.notificationSound}")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Set notification sound failed: ${e.message}")
            }
        }

        if (old.notificationDuration != new.notificationDuration && new.notificationDuration.isNotEmpty()) {
            try {
                sendAppSettings("settings_msg_notification_display_duration" to new.notificationDuration)
                LogCollector.i(TAG, "Set notification duration: ${new.notificationDuration}s")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Set notification duration failed: ${e.message}")
            }
        }

        if (old.chatFontSize != new.chatFontSize && new.chatFontSize.isNotEmpty()) {
            try {
                sendAppSettings("settings_chat_font_size" to new.chatFontSize)
                LogCollector.i(TAG, "Set chat font size: ${new.chatFontSize}sp")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Set chat font size failed: ${e.message}")
            }
        }

        if (old.wakewordEnabled != new.wakewordEnabled) {
            try {
                sendAppSettings("wakeword_enabled" to new.wakewordEnabled.toString())
                LogCollector.i(TAG, "Set wakeword enabled: ${new.wakewordEnabled}")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Set wakeword enabled failed: ${e.message}")
            }
            // Rokid's built-in offline wakeword/assistant must NEVER run -- only our
            // pipeline does speech detection. Force it off on every change via the relay
            // voice_ctrl_off handler (glasses drive the AssistantSuppressor + persist it).
            ListenerService.phoneBtHostInstance?.let { svc ->
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        svc.sendDeviceCommand("voice_ctrl_off")
                        LogCollector.i(TAG, "Forced Rokid voice control OFF (voice_ctrl_off)")
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Force voice_ctrl_off failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun setupDropdown(
        dropdown: AutoCompleteTextView,
        options: List<Pair<String, String>>,
        storedValue: String
    ) {
        val labels = options.map { it.first }
        val adapter = NoFilterAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        dropdown.setAdapter(adapter)
        val matchingLabel = options.find { it.second == storedValue }?.first ?: labels.first()
        dropdown.setText(matchingLabel, false)
    }

    private fun getDropdownValue(
        dropdown: AutoCompleteTextView,
        options: List<Pair<String, String>>
    ): String {
        val selected = dropdown.text.toString()
        return options.find { it.first == selected }?.second ?: options.first().second
    }

    private fun chatFontSizeToProgress(sizeInSp: Int): Int = (sizeInSp - 8).coerceIn(0, 16)

    private fun progressToChatFontSize(progress: Int): Int = (progress + 8).coerceIn(8, 24)
}
