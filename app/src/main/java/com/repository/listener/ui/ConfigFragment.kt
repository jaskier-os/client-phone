package com.repository.listener.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import com.repository.listener.util.LogCollector.FILESYNC_TAGS
import com.repository.listener.util.LogCollector.VOICE_TAGS
import org.json.JSONObject

class ConfigFragment : Fragment() {

    companion object {
        const val ACTION_SETTINGS_CHANGED = "com.repository.listener.SETTINGS_CHANGED"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabLayout = view.findViewById<TabLayout>(R.id.configTabLayout)
        val viewPager = view.findViewById<ViewPager2>(R.id.configViewPager)

        viewPager.adapter = ConfigPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Server"
                1 -> "Audio Relay"
                2 -> "Video Relay"
                else -> ""
            }
        }.attach()
    }

    private class ConfigPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ServerConfigFragment()
                1 -> AudioConfigFragment()
                2 -> VideoConfigFragment()
                else -> throw IllegalStateException()
            }
        }
    }
}

/**
 * Server configuration tab -- all existing config fields moved here.
 */
class ServerConfigFragment : Fragment() {

    private lateinit var editOrchestratorUrl: EditText
    private lateinit var editApiKey: EditText
    private lateinit var editDeviceId: EditText
    private lateinit var dropdownModel: AutoCompleteTextView
    private lateinit var editGlassesSerial: EditText
    private lateinit var btnToggle: Button
    private lateinit var btnBatterySettings: Button

    private var isServiceRunning = false
    private val models = listOf("sonnet", "opus", "haiku")
    private val sttProviders = listOf("local", "anthropic")
    private val sttProviderLabels = listOf("Local", "Anthropic")
    private val sttLanguages = listOf("ru", "en")
    private val sttLanguageLabels = listOf("Russian", "English")
    private val locatorApis = listOf(AppConfig.LOCATOR_API_YANDEX, AppConfig.LOCATOR_API_GPS)
    private val locatorApiLabels = listOf("Yandex Locator API", "Regular GPS")
    private val mapProviders = listOf(AppConfig.MAP_PROVIDER_YANDEX, AppConfig.MAP_PROVIDER_GOOGLE)
    private val mapProviderLabels = listOf("Yandex", "Google")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_config_server, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        editOrchestratorUrl = view.findViewById(R.id.editOrchestratorUrl)
        editApiKey = view.findViewById(R.id.editApiKey)
        editDeviceId = view.findViewById(R.id.editDeviceId)
        dropdownModel = view.findViewById(R.id.dropdownModel)
        editGlassesSerial = view.findViewById(R.id.editGlassesSerial)
        btnToggle = view.findViewById(R.id.btnToggle)
        btnBatterySettings = view.findViewById(R.id.btnBatterySettings)

        editOrchestratorUrl.setText(AppConfig.getOrchestratorUrl(ctx))
        editApiKey.setText(AppConfig.getApiKey(ctx))
        editDeviceId.setText(AppConfig.getDeviceId(ctx))
        editGlassesSerial.setText(AppConfig.getGlassesSerial(ctx))

        // Auto-save serial on every edit so it persists across reboots
        editGlassesSerial.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                AppConfig.setGlassesSerial(ctx, s?.toString()?.trim() ?: "")
            }
        })

        val modelAdapter = NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, models)
        dropdownModel.setAdapter(modelAdapter)
        dropdownModel.setText(AppConfig.getModel(ctx), false)
        dropdownModel.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setModel(ctx, models[position])
            broadcastSettingsChanged()
        }

        // STT Provider dropdown
        val dropdownSttProvider = view.findViewById<AutoCompleteTextView>(R.id.dropdownSttProvider)
        val sttProviderAdapter = NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, sttProviderLabels)
        dropdownSttProvider.setAdapter(sttProviderAdapter)
        val currentProvider = AppConfig.getSttProvider(ctx)
        val providerIdx = sttProviders.indexOf(currentProvider).takeIf { it >= 0 } ?: 0
        dropdownSttProvider.setText(sttProviderLabels[providerIdx], false)
        dropdownSttProvider.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setSttProvider(ctx, sttProviders[position])
            broadcastSettingsChanged()
        }

        // STT Language dropdown
        val dropdownSttLanguage = view.findViewById<AutoCompleteTextView>(R.id.dropdownSttLanguage)
        val sttLanguageAdapter = NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, sttLanguageLabels)
        dropdownSttLanguage.setAdapter(sttLanguageAdapter)
        val currentLanguage = AppConfig.getSttLanguage(ctx)
        val languageIdx = sttLanguages.indexOf(currentLanguage).takeIf { it >= 0 } ?: 0
        dropdownSttLanguage.setText(sttLanguageLabels[languageIdx], false)
        dropdownSttLanguage.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setSttLanguage(ctx, sttLanguages[position])
            broadcastSettingsChanged()
        }

        // Default locator API: Yandex Locator (WiFi/cell/IP) or skip straight to FusedLocationProvider
        val dropdownLocatorApi = view.findViewById<AutoCompleteTextView>(R.id.dropdownLocatorApi)
        val locatorApiAdapter = NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, locatorApiLabels)
        dropdownLocatorApi.setAdapter(locatorApiAdapter)
        val currentLocator = AppConfig.getLocatorApiPreference(ctx)
        val locatorIdx = locatorApis.indexOf(currentLocator).takeIf { it >= 0 } ?: 0
        dropdownLocatorApi.setText(locatorApiLabels[locatorIdx], false)
        dropdownLocatorApi.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setLocatorApiPreference(ctx, locatorApis[position])
            broadcastSettingsChanged()
        }

        // Map provider for navigation (Yandex/Google). Applied idle-only by the service.
        val dropdownMapProvider = view.findViewById<AutoCompleteTextView>(R.id.dropdownMapProvider)
        val mapProviderAdapter = NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, mapProviderLabels)
        dropdownMapProvider.setAdapter(mapProviderAdapter)
        val currentMapProvider = AppConfig.getMapProvider(ctx)
        val mapProviderIdx = mapProviders.indexOf(currentMapProvider).takeIf { it >= 0 } ?: 0
        dropdownMapProvider.setText(mapProviderLabels[mapProviderIdx], false)
        dropdownMapProvider.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setMapProvider(ctx, mapProviders[position])
            broadcastSettingsChanged()
        }

        // Phone AI trigger toggle: when OFF, phone wake word will delegate to glasses
        // (if connected) instead of starting a phone-local AI session
        val switchPhoneAiTrigger = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPhoneAiTrigger)
        switchPhoneAiTrigger.isChecked = AppConfig.getPhoneAiTriggerEnabled(ctx)
        switchPhoneAiTrigger.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.setPhoneAiTriggerEnabled(ctx, isChecked)
        }

        view.findViewById<View>(R.id.btnSystemPrompt).setOnClickListener {
            SystemPromptDialog().show(childFragmentManager, "system_prompt")
        }

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopListenerService()
            } else {
                startListenerService()
            }
        }

        view.findViewById<View>(R.id.btnViewFileSyncLogs).setOnClickListener {
            showFilteredLogs("File Sync Logs", FILESYNC_TAGS)
        }
        view.findViewById<View>(R.id.btnShareFileSyncLogs).setOnClickListener {
            shareFilteredLogs("filesync", FILESYNC_TAGS)
        }
        view.findViewById<View>(R.id.btnViewVoiceLogs).setOnClickListener {
            showFilteredLogs("Voice Input Logs", VOICE_TAGS)
        }
        view.findViewById<View>(R.id.btnShareVoiceLogs).setOnClickListener {
            shareFilteredLogs("voice_input", VOICE_TAGS)
        }
        view.findViewById<View>(R.id.btnViewAllLogs).setOnClickListener {
            showFilteredLogs("All Logs", null)
        }
        view.findViewById<View>(R.id.btnShareAllLogs).setOnClickListener {
            shareFilteredLogs("all", null)
        }

        btnBatterySettings.setOnClickListener {
            try {
                val intent = Intent()
                intent.component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        }

        // Detect if service is already running (started by MainActivity)
        isServiceRunning = isListenerServiceRunning()
        btnToggle.text = if (isServiceRunning) "Stop" else "Start"
    }

    /**
     * Show a live-updating log dialog filtered to the given tags.
     * Pass null for [filterTags] to show all logs.
     */
    private fun showFilteredLogs(title: String, filterTags: Set<String>?) {
        val tv = TextView(requireContext()).apply {
            textSize = 10f
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = android.widget.ScrollView(requireContext()).apply {
            addView(tv)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> LogCollector.clear() }
            .create()

        var lastCount = -1
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val refreshRunnable = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val count = if (filterTags != null) LogCollector.getEntryCount(filterTags) else LogCollector.getEntryCount()
                if (count != lastCount) {
                    val wasAtBottom = !scroll.canScrollVertically(1)
                    val savedY = scroll.scrollY
                    lastCount = count
                    val logs = if (filterTags != null) LogCollector.getText(filterTags) else LogCollector.getText()
                    tv.text = logs.ifEmpty { "(no logs yet)" }
                    dialog.setTitle("$title ($count entries)")
                    scroll.postDelayed({
                        if (wasAtBottom) {
                            scroll.fullScroll(View.FOCUS_DOWN)
                        } else {
                            scroll.scrollTo(0, savedY)
                        }
                    }, 150)
                }
                handler.postDelayed(this, 1000)
            }
        }

        dialog.setOnDismissListener { handler.removeCallbacks(refreshRunnable) }
        dialog.show()
        refreshRunnable.run()
        scroll.postDelayed({ scroll.fullScroll(View.FOCUS_DOWN) }, 150)
    }

    /**
     * Share recent logs filtered to the given tags.
     * Pass null for [filterTags] to share all logs.
     */
    private fun shareFilteredLogs(filename: String, filterTags: Set<String>?) {
        val logs = if (filterTags != null) {
            LogCollector.getTextRecent(filterTags, 5).ifEmpty { "(no logs in the last 5 minutes)" }
        } else {
            LogCollector.getText().takeLast(50000).ifEmpty { "(no logs)" }
        }
        val logsDir = java.io.File(requireContext().cacheDir, "logs")
        logsDir.mkdirs()
        val file = java.io.File(logsDir, "$filename.log")
        file.writeText(logs)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share $filename logs"))
    }

    private fun startListenerService() {
        val ctx = requireContext()

        AppConfig.setOrchestratorUrl(ctx, editOrchestratorUrl.text.toString().trim())
        AppConfig.setApiKey(ctx, editApiKey.text.toString().trim())
        AppConfig.setDeviceId(ctx, editDeviceId.text.toString().trim())
        AppConfig.setGlassesSerial(ctx, editGlassesSerial.text.toString().trim())

        val intent = Intent(ctx, ListenerService::class.java).apply {
            action = ListenerService.ACTION_START
        }
        ctx.startForegroundService(intent)

        isServiceRunning = true
        btnToggle.text = "Stop"
    }

    private fun broadcastSettingsChanged() {
        val ctx = context ?: return
        val settings = JSONObject().apply {
            put("model", AppConfig.getModel(ctx))
            put("deviceId", AppConfig.getDeviceId(ctx))
            put("sttProvider", AppConfig.getSttProvider(ctx))
            put("sttLanguage", AppConfig.getSttLanguage(ctx))
        }
        ctx.sendBroadcast(Intent(ConfigFragment.ACTION_SETTINGS_CHANGED).apply {
            setPackage(ctx.packageName)
            putExtra("settingsJson", settings.toString())
        })
    }

    @Suppress("DEPRECATION")
    private fun isListenerServiceRunning(): Boolean {
        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in am.getRunningServices(Integer.MAX_VALUE)) {
            if (ListenerService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun stopListenerService() {
        val ctx = requireContext()

        val intent = Intent(ctx, ListenerService::class.java).apply {
            action = ListenerService.ACTION_STOP
        }
        ctx.startService(intent)

        isServiceRunning = false
        btnToggle.text = "Start"
    }
}

/**
 * Audio relay configuration tab.
 */
class AudioConfigFragment : Fragment() {

    private val bitrateOptions = listOf(10000, 32000, 64000, 96000, 128000, 192000, 256000, 320000, 450000)
    private val bitrateLabels = bitrateOptions.map { "${it / 1000} kbps" }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_config_audio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val dropdownBitrate = view.findViewById<AutoCompleteTextView>(R.id.dropdownBitrate)

        // Bitrate dropdown
        val bitrateAdapter = NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, bitrateLabels)
        dropdownBitrate.setAdapter(bitrateAdapter)
        val currentBitrate = AppConfig.getAudioBitrate(ctx)
        val bitrateIdx = bitrateOptions.indexOf(currentBitrate).takeIf { it >= 0 } ?: 2
        dropdownBitrate.setText(bitrateLabels[bitrateIdx], false)
        dropdownBitrate.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setAudioBitrate(ctx, bitrateOptions[position])
        }
    }
}

/**
 * Video relay configuration tab.
 */
class VideoConfigFragment : Fragment() {

    private val resolutionOptions = listOf("480p", "720p", "1080p")

    private val fpsOptions = listOf(15, 20, 24, 30)
    private val fpsLabels = fpsOptions.map { "${it} fps" }

    private val presetOptions = listOf("ultrafast", "superfast", "veryfast")

    private val profileOptions = listOf("baseline", "main")

    private val keyframeOptions = listOf(1, 2, 3, 5)
    private val keyframeLabels = keyframeOptions.map { "${it}s" }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_config_video, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        // Resolution
        val dropdownResolution = view.findViewById<AutoCompleteTextView>(R.id.dropdownResolution)
        dropdownResolution.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, resolutionOptions))
        val currentRes = AppConfig.getVideoResolution(ctx)
        val resIdx = resolutionOptions.indexOf(currentRes).takeIf { it >= 0 } ?: 1
        dropdownResolution.setText(resolutionOptions[resIdx], false)
        dropdownResolution.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setVideoResolution(ctx, resolutionOptions[position])
        }

        // FPS
        val dropdownFps = view.findViewById<AutoCompleteTextView>(R.id.dropdownFps)
        dropdownFps.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, fpsLabels))
        val currentFps = AppConfig.getVideoFps(ctx)
        val fpsIdx = fpsOptions.indexOf(currentFps).takeIf { it >= 0 } ?: 2
        dropdownFps.setText(fpsLabels[fpsIdx], false)
        dropdownFps.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setVideoFps(ctx, fpsOptions[position])
        }

        // Preset
        val dropdownPreset = view.findViewById<AutoCompleteTextView>(R.id.dropdownPreset)
        dropdownPreset.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, presetOptions))
        val currentPreset = AppConfig.getVideoPreset(ctx)
        val presetIdx = presetOptions.indexOf(currentPreset).takeIf { it >= 0 } ?: 0
        dropdownPreset.setText(presetOptions[presetIdx], false)
        dropdownPreset.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setVideoPreset(ctx, presetOptions[position])
        }

        // Profile
        val dropdownProfile = view.findViewById<AutoCompleteTextView>(R.id.dropdownProfile)
        dropdownProfile.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, profileOptions))
        val currentProfile = AppConfig.getVideoProfile(ctx)
        val profileIdx = profileOptions.indexOf(currentProfile).takeIf { it >= 0 } ?: 0
        dropdownProfile.setText(profileOptions[profileIdx], false)
        dropdownProfile.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setVideoProfile(ctx, profileOptions[position])
        }

        // Keyframe interval
        val dropdownKeyframe = view.findViewById<AutoCompleteTextView>(R.id.dropdownKeyframeInterval)
        dropdownKeyframe.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, keyframeLabels))
        val currentKf = AppConfig.getVideoKeyframeInterval(ctx)
        val kfIdx = keyframeOptions.indexOf(currentKf).takeIf { it >= 0 } ?: 1
        dropdownKeyframe.setText(keyframeLabels[kfIdx], false)
        dropdownKeyframe.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setVideoKeyframeInterval(ctx, keyframeOptions[position])
        }
    }
}
