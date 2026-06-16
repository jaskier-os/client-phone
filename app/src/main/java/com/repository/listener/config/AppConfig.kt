package com.repository.listener.config

import android.content.Context
import com.repository.listener.BuildConfig

object AppConfig {
    private const val PREFS_NAME = "listener_config"

    private const val KEY_ORCHESTRATOR_URL = "orchestrator_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MODEL = "model"
    private const val KEY_MOUSE_SENSITIVITY_X = "mouse_sensitivity_x"
    private const val KEY_MOUSE_SENSITIVITY_Y = "mouse_sensitivity_y"
    private const val KEY_GLASSES_SERIAL = "glasses_serial"
    private const val KEY_GLASSES_BRIGHTNESS = "glasses_brightness"
    private const val KEY_GLASSES_SCREEN_TIMEOUT = "glasses_screen_timeout"
    private const val KEY_GLASSES_POWER_TIMEOUT = "glasses_power_timeout"
    private const val KEY_GLASSES_NOTIFICATION_SOUND = "glasses_notification_sound"
    private const val KEY_GLASSES_NOTIFICATION_DURATION = "glasses_notification_duration"
    private const val KEY_GLASSES_WAKEWORD_ENABLED = "glasses_wakeword_enabled"
    private const val KEY_GLASSES_MAC = "glasses_mac"
    private const val KEY_GLASSES_DEVICE_NAME = "glasses_device_name"
    private const val KEY_GLASSES_ADB_ENABLED = "glasses_adb_enabled"
    private const val KEY_DISPLAY_POSITION_Y = "display_position_y"
    private const val KEY_AUDIO_BITRATE = "audio_bitrate"
    private const val KEY_AUDIO_RELAY_DESIRED = "audio_relay_desired"
    private const val KEY_VIDEO_RESOLUTION = "video_resolution"
    private const val KEY_VIDEO_FPS = "video_fps"
    private const val KEY_VIDEO_PRESET = "video_preset"
    private const val KEY_VIDEO_PROFILE = "video_profile"
    private const val KEY_VIDEO_KEYFRAME_INTERVAL = "video_keyframe_interval"
    private const val KEY_TRANSLATION_FROM_LANGUAGE = "translation_from_language"
    private const val KEY_TRANSLATION_TO_LANGUAGE = "translation_to_language"
    private const val KEY_DEBUG_PIPELINE = "debug_pipeline"
    private const val KEY_TELEPROMPTER_FONT_SIZE = "teleprompter_font_size"
    private const val KEY_TRANSLATION_FONT_SIZE = "translation_font_size"
    private const val KEY_TRANSLATION_AUDIO_SOURCE = "translation_audio_source"
    private const val KEY_ASSISTANT_WEARER_LANGUAGE = "assistant_wearer_language"
    private const val KEY_ASSISTANT_INTERLOCUTOR_LANGUAGE = "assistant_interlocutor_language"
    private const val KEY_ASSISTANT_INTERLOCUTOR_SOURCE = "assistant_interlocutor_source"
    private const val KEY_ASSISTANT_MODEL = "assistant_model"
    // Fastest model + the only one the Communicator does not wrap in adaptive
    // thinking, so it is the right default for the every-5-10s fact-check.
    const val DEFAULT_ASSISTANT_MODEL = "haiku"
    private const val KEY_GLASSES_CHAT_FONT_SIZE = "glasses_chat_font_size"
    private const val KEY_PINNED_CHAT_IDS = "pinned_chat_ids"
    private const val KEY_USER_SYSTEM_PROMPT = "user_system_prompt"
    private const val KEY_STT_PROVIDER = "stt_provider"
    private const val KEY_STT_LANGUAGE = "stt_language"
    private const val KEY_PHONE_AI_TRIGGER_ENABLED = "phone_ai_trigger_enabled"
    private const val KEY_LOCATOR_API_KEY = "locator_api_key"
    private const val KEY_LOCATOR_API_PREFERENCE = "locator_api_preference"
    private const val KEY_MAP_PROVIDER = "map_provider"
    private const val KEY_WEATHER_ENABLED = "weather_enabled"
    private const val KEY_WEATHER_REFRESH_MIN = "weather_refresh_min"
    private const val KEY_WEATHER_LAST_ICON = "weather_last_icon"
    private const val KEY_WEATHER_LAST_TEMP = "weather_last_temp"
    private const val KEY_WEATHER_LAST_LOCATION = "weather_last_location"
    private const val KEY_WEATHER_LAST_FETCH_MS = "weather_last_fetch_ms"
    private const val KEY_LAST_KNOWN_LAT = "last_known_lat"
    private const val KEY_LAST_KNOWN_LON = "last_known_lon"
    private const val KEY_AZURE_SPEECH_KEY = "azure_speech_key"
    private const val KEY_AZURE_SPEECH_REGION = "azure_speech_region"
    private const val KEY_TRANSLATION_PROVIDER = "translation_provider"
    private const val KEY_TRANSLATION_TWO_WAY = "translation_two_way"
    private const val KEY_RC_DEFAULT_PERMISSION_MODE = "rc_default_permission_mode"
    private const val KEY_LONE_TRUSTED = "lone_trusted_macs"

    /** Orchestrator-side permission mode names (NOT CLI names). The phone never converts. */
    val ALLOWED_PERMISSION_MODES = setOf(
        "ask_on_potentially_safe",
        "acceptAll",
        "bypassAll",
        "plan"
    )
    val DEFAULT_AZURE_SPEECH_KEY = BuildConfig.AZURE_SPEECH_KEY
    const val DEFAULT_AZURE_SPEECH_REGION = "southeastasia"
    const val DEFAULT_TRANSLATION_PROVIDER = "azure"
    val DEFAULT_ORCHESTRATOR_URL = BuildConfig.DEFAULT_ORCHESTRATOR_URL
    val DEFAULT_API_KEY = BuildConfig.ORCHESTRATOR_API_KEY
    const val DEFAULT_DEVICE_ID = "phone-01"
    const val DEFAULT_MODEL = "sonnet"
    /** Yandex Locator API key (https://developer.tech.yandex.com/). Separate from MapKit. */
    val DEFAULT_LOCATOR_API_KEY = BuildConfig.LOCATOR_API_KEY

    /** Default location source. "yandex" = Yandex Locator (WiFi/cell/IP) with GPS fallback;
     *  "gps" = skip Locator and use Android FusedLocationProviderClient directly. */
    const val LOCATOR_API_YANDEX = "yandex"
    const val LOCATOR_API_GPS = "gps"
    const val DEFAULT_LOCATOR_API_PREFERENCE = LOCATOR_API_YANDEX

    /** Map provider for navigation: routing, geocoding, POI search, interactive
     *  map, glasses minimap, location/mock. Independent of the Locator preference
     *  above. Runtime-switchable (idle-only). */
    const val MAP_PROVIDER_YANDEX = "yandex"
    const val MAP_PROVIDER_GOOGLE = "google"
    const val DEFAULT_MAP_PROVIDER = MAP_PROVIDER_YANDEX

    // openWakeWord detection parameters
    const val OWW_THRESHOLD = 0.5f          // Score threshold for single frame
    const val OWW_REQUIRED_HITS = 1         // Frames above threshold needed
    const val OWW_WINDOW_SIZE = 3           // Within this many recent frames

    // Phone-side end-of-speech VAD silence threshold. Triggers transitions
    // (LISTENING -> CONFIRMING, TG voice finish). Anthropic STT already
    // endpoints upstream at 700ms; 1500ms here covers VAD jitter + minor
    // mid-sentence pauses without making the user wait too long.
    const val SILENCE_THRESHOLD_MS = 1500L
    const val MAX_RECORDING_SECONDS = 600
    const val MIN_RECORDING_MS = 3000L
    /** Hard timeout: dismiss LISTENING if no speech detected within this window.
     *  Fires independently of audio chunk arrival (guards against BT stream stalls). */
    const val NO_SPEECH_TIMEOUT_MS = 7000L
    const val VAD_THRESHOLD = 0.65f
    const val ENABLE_RMS_GATE = true
    const val RMS_THRESHOLD = 0.002f
    const val SPEAKER_VERIFICATION_ENABLED = false
    const val SPEAKER_SIMILARITY_THRESHOLD = 0.25f
    const val PROMPT_SPEAKER_VERIFICATION_ENABLED = false
    const val VAD_WAKE_THRESHOLD = 0.40f
    const val WAKE_COOLDOWN_MS = 1500L

    @Volatile var debugPipeline = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrchestratorUrl(context: Context): String =
        prefs(context).getString(KEY_ORCHESTRATOR_URL, DEFAULT_ORCHESTRATOR_URL) ?: DEFAULT_ORCHESTRATOR_URL

    fun setOrchestratorUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_ORCHESTRATOR_URL, url).apply()
    }

    fun getOrchestratorHttpUrl(context: Context): String {
        val wsUrl = getOrchestratorUrl(context)
        return wsUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .replace(Regex("/ws/.*$"), "")
    }

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun getDeviceId(context: Context): String =
        prefs(context).getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID

    fun setDeviceId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_DEVICE_ID, id).apply()
    }

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model).apply()
    }

    fun getSttProvider(context: Context): String =
        prefs(context).getString(KEY_STT_PROVIDER, "local") ?: "local"

    fun setSttProvider(context: Context, provider: String) {
        prefs(context).edit().putString(KEY_STT_PROVIDER, provider).apply()
    }

    fun getSttLanguage(context: Context): String =
        prefs(context).getString(KEY_STT_LANGUAGE, "ru") ?: "ru"

    fun setSttLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_STT_LANGUAGE, language).apply()
    }

    fun getPhoneAiTriggerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PHONE_AI_TRIGGER_ENABLED, true)

    fun setPhoneAiTriggerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PHONE_AI_TRIGGER_ENABLED, enabled).apply()
    }

    fun getLocatorApiKey(context: Context): String =
        prefs(context).getString(KEY_LOCATOR_API_KEY, DEFAULT_LOCATOR_API_KEY) ?: DEFAULT_LOCATOR_API_KEY

    fun setLocatorApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_LOCATOR_API_KEY, key).apply()
    }

    fun getLocatorApiPreference(context: Context): String =
        prefs(context).getString(KEY_LOCATOR_API_PREFERENCE, DEFAULT_LOCATOR_API_PREFERENCE)
            ?: DEFAULT_LOCATOR_API_PREFERENCE

    fun setLocatorApiPreference(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LOCATOR_API_PREFERENCE, value).apply()
    }

    fun getMapProvider(context: Context): String =
        prefs(context).getString(KEY_MAP_PROVIDER, DEFAULT_MAP_PROVIDER) ?: DEFAULT_MAP_PROVIDER

    fun setMapProvider(context: Context, provider: String) {
        prefs(context).edit().putString(KEY_MAP_PROVIDER, provider).apply()
    }

    fun getGlassesSerial(context: Context): String =
        prefs(context).getString(KEY_GLASSES_SERIAL, "") ?: ""

    fun setGlassesSerial(context: Context, serial: String) {
        prefs(context).edit().putString(KEY_GLASSES_SERIAL, serial).apply()
    }

    fun getGlassesBrightness(context: Context): Int =
        prefs(context).getInt(KEY_GLASSES_BRIGHTNESS, 8)

    fun setGlassesBrightness(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_GLASSES_BRIGHTNESS, value.coerceIn(0, 15)).apply()
    }

    /** Int-typed accessor for the CH_SETTINGS contract. Clamped to 0..15. */
    fun getGlassesBrightnessInt(context: Context): Int =
        getGlassesBrightness(context).coerceIn(0, 15)

    fun getGlassesScreenTimeout(context: Context): String =
        prefs(context).getString(KEY_GLASSES_SCREEN_TIMEOUT, "") ?: ""

    fun setGlassesScreenTimeout(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GLASSES_SCREEN_TIMEOUT, value).apply()
    }

    /** Int-typed accessor for the CH_SETTINGS contract. 0 = never, else clamped to 5..86400. */
    fun getGlassesScreenTimeoutSec(context: Context): Int {
        val raw = getGlassesScreenTimeout(context).toIntOrNull() ?: 300
        return clampTimeoutSec(raw)
    }

    fun setGlassesScreenTimeoutSec(context: Context, value: Int) {
        val clamped = clampTimeoutSec(value)
        prefs(context).edit().putString(KEY_GLASSES_SCREEN_TIMEOUT, clamped.toString()).apply()
    }

    private fun clampTimeoutSec(v: Int): Int {
        if (v <= 0) return 0
        return v.coerceIn(5, 86400)
    }

    fun getGlassesPowerTimeout(context: Context): String =
        prefs(context).getString(KEY_GLASSES_POWER_TIMEOUT, "") ?: ""

    fun setGlassesPowerTimeout(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GLASSES_POWER_TIMEOUT, value).apply()
    }

    /** Int-typed accessor for the CH_SETTINGS contract. 0 = never, else clamped to 1..1440. */
    fun getGlassesPowerTimeoutMin(context: Context): Int {
        val raw = getGlassesPowerTimeout(context).toIntOrNull() ?: 60
        return clampPowerTimeoutMin(raw)
    }

    fun setGlassesPowerTimeoutMin(context: Context, value: Int) {
        val clamped = clampPowerTimeoutMin(value)
        prefs(context).edit().putString(KEY_GLASSES_POWER_TIMEOUT, clamped.toString()).apply()
    }

    private fun clampPowerTimeoutMin(v: Int): Int {
        if (v <= 0) return 0
        return v.coerceIn(1, 1440)
    }

    fun getGlassesNotificationSound(context: Context): String =
        prefs(context).getString(KEY_GLASSES_NOTIFICATION_SOUND, "") ?: ""

    fun setGlassesNotificationSound(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GLASSES_NOTIFICATION_SOUND, value).apply()
    }

    fun getGlassesNotificationDuration(context: Context): String =
        prefs(context).getString(KEY_GLASSES_NOTIFICATION_DURATION, "") ?: ""

    fun setGlassesNotificationDuration(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GLASSES_NOTIFICATION_DURATION, value).apply()
    }


    fun getGlassesWakewordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GLASSES_WAKEWORD_ENABLED, false)

    fun setGlassesWakewordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLASSES_WAKEWORD_ENABLED, enabled).apply()
    }

    fun getGlassesMac(context: Context): String =
        prefs(context).getString(KEY_GLASSES_MAC, "") ?: ""

    fun setGlassesMac(context: Context, mac: String) {
        prefs(context).edit().putString(KEY_GLASSES_MAC, mac).apply()
    }

    fun getGlassesDeviceName(context: Context): String =
        prefs(context).getString(KEY_GLASSES_DEVICE_NAME, "") ?: ""

    fun setGlassesDeviceName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_GLASSES_DEVICE_NAME, name).apply()
    }

    fun getGlassesAdbEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GLASSES_ADB_ENABLED, false)

    fun setGlassesAdbEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLASSES_ADB_ENABLED, enabled).apply()
    }

    fun getDisplayPositionY(context: Context): Float =
        prefs(context).getFloat(KEY_DISPLAY_POSITION_Y, 0.5f)

    fun setDisplayPositionY(context: Context, y: Float) {
        prefs(context).edit().putFloat(KEY_DISPLAY_POSITION_Y, y).apply()
    }

    fun getAudioBitrate(context: Context): Int =
        prefs(context).getInt(KEY_AUDIO_BITRATE, 64000)

    fun setAudioBitrate(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_AUDIO_BITRATE, value).apply()
    }

    fun getAudioRelayDesired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_RELAY_DESIRED, false)

    fun setAudioRelayDesired(context: Context, desired: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_RELAY_DESIRED, desired).apply()
    }

    fun getVideoResolution(context: Context): String =
        prefs(context).getString(KEY_VIDEO_RESOLUTION, "720p") ?: "720p"

    fun setVideoResolution(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VIDEO_RESOLUTION, value).apply()
    }

    fun getVideoFps(context: Context): Int =
        prefs(context).getInt(KEY_VIDEO_FPS, 24)

    fun setVideoFps(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_VIDEO_FPS, value).apply()
    }

    fun getVideoPreset(context: Context): String =
        prefs(context).getString(KEY_VIDEO_PRESET, "ultrafast") ?: "ultrafast"

    fun setVideoPreset(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VIDEO_PRESET, value).apply()
    }

    fun getVideoProfile(context: Context): String =
        prefs(context).getString(KEY_VIDEO_PROFILE, "baseline") ?: "baseline"

    fun setVideoProfile(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VIDEO_PROFILE, value).apply()
    }

    fun getVideoKeyframeInterval(context: Context): Int =
        prefs(context).getInt(KEY_VIDEO_KEYFRAME_INTERVAL, 2)

    fun setVideoKeyframeInterval(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_VIDEO_KEYFRAME_INTERVAL, value).apply()
    }

    fun getTranslationFromLanguage(context: Context): String =
        prefs(context).getString(KEY_TRANSLATION_FROM_LANGUAGE, "") ?: ""

    fun setTranslationFromLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TRANSLATION_FROM_LANGUAGE, value).apply()
    }

    fun getTranslationToLanguage(context: Context): String =
        prefs(context).getString(KEY_TRANSLATION_TO_LANGUAGE, "") ?: ""

    fun setTranslationToLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TRANSLATION_TO_LANGUAGE, value).apply()
    }

    fun getDebugPipeline(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG_PIPELINE, false)

    fun setDebugPipeline(context: Context, enabled: Boolean) {
        debugPipeline = enabled
        prefs(context).edit().putBoolean(KEY_DEBUG_PIPELINE, enabled).apply()
    }

    fun getTeleprompterFontSize(context: Context): Int =
        prefs(context).getInt(KEY_TELEPROMPTER_FONT_SIZE, 22)

    fun setTeleprompterFontSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_TELEPROMPTER_FONT_SIZE, size).apply()
    }

    fun getTeleprompterLang(context: Context): String =
        prefs(context).getString("teleprompter_lang", "auto") ?: "auto"

    fun setTeleprompterLang(context: Context, lang: String) {
        prefs(context).edit().putString("teleprompter_lang", lang).apply()
    }

    fun getTranslationFontSize(context: Context): Int =
        prefs(context).getInt(KEY_TRANSLATION_FONT_SIZE, 14)

    fun setTranslationFontSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_TRANSLATION_FONT_SIZE, size).apply()
    }

    fun getTranslationAudioSource(context: Context): String =
        prefs(context).getString(KEY_TRANSLATION_AUDIO_SOURCE, "glasses") ?: "glasses"

    fun setTranslationAudioSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_TRANSLATION_AUDIO_SOURCE, source).apply()
    }

    fun getAssistantWearerLanguage(context: Context): String =
        prefs(context).getString(KEY_ASSISTANT_WEARER_LANGUAGE, "") ?: ""

    fun setAssistantWearerLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ASSISTANT_WEARER_LANGUAGE, value).apply()
    }

    fun getAssistantInterlocutorLanguage(context: Context): String =
        prefs(context).getString(KEY_ASSISTANT_INTERLOCUTOR_LANGUAGE, "") ?: ""

    fun setAssistantInterlocutorLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ASSISTANT_INTERLOCUTOR_LANGUAGE, value).apply()
    }

    fun getAssistantInterlocutorSource(context: Context): String =
        prefs(context).getString(KEY_ASSISTANT_INTERLOCUTOR_SOURCE, "glasses") ?: "glasses"

    fun setAssistantInterlocutorSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_ASSISTANT_INTERLOCUTOR_SOURCE, source).apply()
    }

    /** Fact-check model alias sent to the orchestrator. Defaults to the fastest
     *  ("haiku"); never returns blank so no null/empty model propagates. */
    fun getAssistantModel(context: Context): String {
        val v = prefs(context).getString(KEY_ASSISTANT_MODEL, DEFAULT_ASSISTANT_MODEL)
        return if (v.isNullOrBlank()) DEFAULT_ASSISTANT_MODEL else v
    }

    fun setAssistantModel(context: Context, model: String) {
        val v = if (model.isBlank()) DEFAULT_ASSISTANT_MODEL else model
        prefs(context).edit().putString(KEY_ASSISTANT_MODEL, v).apply()
    }

    fun getAzureSpeechKey(context: Context): String =
        prefs(context).getString(KEY_AZURE_SPEECH_KEY, DEFAULT_AZURE_SPEECH_KEY) ?: DEFAULT_AZURE_SPEECH_KEY

    fun setAzureSpeechKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_AZURE_SPEECH_KEY, key).apply()
    }

    fun getAzureSpeechRegion(context: Context): String =
        prefs(context).getString(KEY_AZURE_SPEECH_REGION, DEFAULT_AZURE_SPEECH_REGION) ?: DEFAULT_AZURE_SPEECH_REGION

    fun setAzureSpeechRegion(context: Context, region: String) {
        prefs(context).edit().putString(KEY_AZURE_SPEECH_REGION, region).apply()
    }

    /**
     * Permission mode for new remote-control sessions. Returns null if unset --
     * per permission-mode-contract.md section 5, the phone MUST NOT hardcode a
     * client-side default. When unset, omit the field from the start request and
     * let the orchestrator apply its canonical default.
     */
    fun getDefaultPermissionMode(context: Context): String? =
        prefs(context).getString(KEY_RC_DEFAULT_PERMISSION_MODE, null)

    /**
     * Persist permission mode. Pass null to clear. Rejects values not in
     * ALLOWED_PERMISSION_MODES (orchestrator-side names only).
     */
    fun setDefaultPermissionMode(context: Context, value: String?) {
        if (value != null && value !in ALLOWED_PERMISSION_MODES) {
            throw IllegalArgumentException("Invalid permission mode: $value")
        }
        val editor = prefs(context).edit()
        if (value == null) {
            editor.remove(KEY_RC_DEFAULT_PERMISSION_MODE)
        } else {
            editor.putString(KEY_RC_DEFAULT_PERMISSION_MODE, value)
        }
        editor.apply()
    }

    fun getTranslationProvider(context: Context): String =
        prefs(context).getString(KEY_TRANSLATION_PROVIDER, DEFAULT_TRANSLATION_PROVIDER) ?: DEFAULT_TRANSLATION_PROVIDER

    fun setTranslationProvider(context: Context, provider: String) {
        prefs(context).edit().putString(KEY_TRANSLATION_PROVIDER, provider).apply()
    }

    fun getTranslationTwoWay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRANSLATION_TWO_WAY, false)

    fun setTranslationTwoWay(context: Context, twoWay: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRANSLATION_TWO_WAY, twoWay).apply()
    }

    fun getPinnedChatIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PINNED_CHAT_IDS, emptySet()) ?: emptySet()

    fun getUserSystemPrompt(context: Context): String =
        prefs(context).getString(KEY_USER_SYSTEM_PROMPT, "") ?: ""

    fun setUserSystemPrompt(context: Context, prompt: String) {
        prefs(context).edit().putString(KEY_USER_SYSTEM_PROMPT, prompt).apply()
    }

    fun togglePinChat(context: Context, chatId: String): Boolean {
        val current = getPinnedChatIds(context).toMutableSet()
        val pinned = if (current.contains(chatId)) {
            current.remove(chatId)
            false
        } else {
            current.add(chatId)
            true
        }
        prefs(context).edit().putStringSet(KEY_PINNED_CHAT_IDS, current).apply()
        return pinned
    }

    fun getGlassesChatFontSize(context: Context): String =
        prefs(context).getString(KEY_GLASSES_CHAT_FONT_SIZE, "14") ?: "14"

    fun setGlassesChatFontSize(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GLASSES_CHAT_FONT_SIZE, value).apply()
    }

    fun getMouseSensitivityX(context: Context): Int =
        prefs(context).getInt(KEY_MOUSE_SENSITIVITY_X, 1800)

    fun setMouseSensitivityX(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MOUSE_SENSITIVITY_X, value).apply()
    }

    fun getMouseSensitivityY(context: Context): Int =
        prefs(context).getInt(KEY_MOUSE_SENSITIVITY_Y, 4200)

    fun setMouseSensitivityY(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MOUSE_SENSITIVITY_Y, value).apply()
    }

    // --- Weather widget ---

    fun isWeatherEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEATHER_ENABLED, true)

    fun setWeatherEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEATHER_ENABLED, enabled).apply()
    }

    fun getWeatherRefreshMin(context: Context): Int =
        prefs(context).getInt(KEY_WEATHER_REFRESH_MIN, 60).coerceIn(15, 1440)

    fun setWeatherRefreshMin(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_WEATHER_REFRESH_MIN, value.coerceIn(15, 1440)).apply()
    }

    fun getLastWeatherIcon(context: Context): String =
        prefs(context).getString(KEY_WEATHER_LAST_ICON, "") ?: ""

    fun getLastWeatherTemp(context: Context): Int =
        prefs(context).getInt(KEY_WEATHER_LAST_TEMP, 0)

    fun getLastWeatherLocation(context: Context): String =
        prefs(context).getString(KEY_WEATHER_LAST_LOCATION, "") ?: ""

    fun getLastWeatherFetchMs(context: Context): Long =
        prefs(context).getLong(KEY_WEATHER_LAST_FETCH_MS, 0L)

    fun setLastWeather(context: Context, icon: String, tempC: Int, location: String) {
        prefs(context).edit()
            .putString(KEY_WEATHER_LAST_ICON, icon)
            .putInt(KEY_WEATHER_LAST_TEMP, tempC)
            .putString(KEY_WEATHER_LAST_LOCATION, location)
            .putLong(KEY_WEATHER_LAST_FETCH_MS, System.currentTimeMillis())
            .apply()
    }

    fun setLastKnownLatLon(context: Context, lat: Double, lon: Double) {
        prefs(context).edit()
            .putLong(KEY_LAST_KNOWN_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LAST_KNOWN_LON, java.lang.Double.doubleToRawLongBits(lon))
            .apply()
    }

    fun getLastKnownLatLon(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (!p.contains(KEY_LAST_KNOWN_LAT) || !p.contains(KEY_LAST_KNOWN_LON)) return null
        val lat = java.lang.Double.longBitsToDouble(p.getLong(KEY_LAST_KNOWN_LAT, 0L))
        val lon = java.lang.Double.longBitsToDouble(p.getLong(KEY_LAST_KNOWN_LON, 0L))
        return Pair(lat, lon)
    }

    // --- Lone mode trusted devices (MACs, uppercased) ---

    fun getLoneTrusted(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LONE_TRUSTED, emptySet()) ?: emptySet()

    fun setLoneTrusted(context: Context, macs: Set<String>) {
        // Store a fresh copy: SharedPreferences must not be handed a mutable set it keeps a ref to.
        prefs(context).edit().putStringSet(KEY_LONE_TRUSTED, HashSet(macs)).apply()
    }

}
