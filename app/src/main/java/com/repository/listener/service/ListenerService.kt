package com.repository.listener.service

import android.Manifest
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.repository.listener.MainActivity
import com.repository.listener.audio.OpenWakeWordEngine
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.AssetFileDescriptor
import android.location.LocationManager
import android.media.MediaPlayer
import com.repository.listener.alarm.AlarmItem
import com.repository.listener.alarm.AlarmStore
import com.repository.listener.rc.RcDumpEntry
import com.repository.listener.rc.RcLiveSession
import com.repository.listener.rc.RcLiveSessionMerge
import com.repository.listener.ui.folderNameFromWorkDir
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.repository.listener.audio.AudioRecorder
import com.repository.listener.audio.GlassesAudioArchiver
import com.repository.listener.audio.SpeakerVerifier
import com.repository.listener.audio.AudioDucker
import com.repository.listener.audio.TtsPlayer
import com.repository.listener.audio.VadEngine
import com.repository.listener.audio.PipelineTracer
import com.repository.listener.audio.WakeWordDetector

import com.repository.listener.bt.GlassesHealthMonitor
import com.repository.listener.bt.LocalTranscriptWire
import com.repository.listener.bt.PhoneBtHost
import com.repository.listener.bt.PhoneHidMouseBridge
import com.repository.listener.bt.decodeGlassesMouseReport
import com.repository.listener.bt.encodeStreamMouseReport
import com.repository.listener.capture.AudioToolRecorder
import com.repository.listener.capture.CameraCapturer
import com.repository.listener.capture.LocationProvider
import com.repository.listener.capture.ScreenCapturer
import com.repository.listener.capture.SystemAudioCapturer
import com.repository.listener.config.AppConfig
import com.repository.listener.scanner.NetworkScanner
import com.repository.listener.network.AzureTranslationSession
import com.repository.listener.network.ChatHistoryClient
import com.repository.listener.network.DesktopRelayLocator
import com.repository.listener.network.LanRelayClient
import com.repository.listener.network.OrchestratorClient
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.network.WebRTCClient
import com.repository.listener.network.TranscriptionClient
import com.repository.listener.network.TranscriberStreamClient
import com.repository.listener.network.WeatherScheduler
import com.repository.listener.network.WeatherWorker
import com.repository.listener.audio.SpeechPositionTracker
import com.repository.listener.adb.AdbResultWriter
import com.repository.listener.sync.GlassesSyncClient
import com.repository.navigation.NavigationManager
import com.repository.navigation.provider.MapProviders
import com.repository.navigation.model.TransportMode
import com.yandex.mapkit.geometry.Point
import com.repository.listener.ui.GlassesFragment
import com.repository.listener.ui.GlassesSettingsFragment
import com.repository.listener.ui.OverlayWidget
import com.repository.listener.ui.ConfigFragment
import com.repository.listener.notification.NotificationHistory
import com.repository.listener.notification.TelegramNotificationListener
import com.repository.listener.util.AiContextBuilder
import com.repository.listener.util.LanguageUtils
import com.repository.listener.util.LogCollector
import com.repository.listener.util.ScreenStateReceiver
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ListenerService : LifecycleService(),
    AudioRecorder.ChunkListener,
    WakeWordDetector.WakeWordListener,
    OrchestratorClient.MessageListener,
    ScreenStateReceiver.ScreenStateListener,
    TtsPlayer.TtsListener,
    PhoneBtHost.GlassesListener,
    SystemAudioCapturer.Listener {

    companion object {
        private const val TAG = "ListenerService"
        // Backoff before re-accept()ing the RFCOMM audio socket after a client is lost.
        // Gives the BT stack time to release the channel/MCB (prevents the glasses'
        // next connect hitting "already opened state:2") and matches the glasses' settle
        // delay, while preventing a tight re-accept spin.
        private const val AUDIO_REACCEPT_BACKOFF_MS = 400L
        private const val MIC_WATCHDOG_INTERVAL_MS = 10_000L
        private const val MIC_STALL_THRESHOLD_MS = 5_000L
        const val ACTION_START = "com.repository.listener.START"
        const val ACTION_STOP = "com.repository.listener.STOP"
        const val ACTION_SETUP_PROJECTION = "com.repository.listener.SETUP_PROJECTION"
        const val ACTION_REQUEST_PROJECTION = "com.repository.listener.REQUEST_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "listener_service_v3"
        const val ACTION_STATE_UPDATE = "com.repository.listener.STATE_UPDATE"
        const val ACTION_CHAT_MESSAGE = "com.repository.listener.CHAT_MESSAGE"
        const val ACTION_STREAMING_TEXT = "com.repository.listener.STREAMING_TEXT"
        const val ACTION_TOOL_STATUS = "com.repository.listener.TOOL_STATUS"
        const val ACTION_SESSION_RESET = "com.repository.listener.SESSION_RESET"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_CHAT_MESSAGE = "chat_message"
        const val EXTRA_STREAMING_TEXT = "streaming_text"
        const val EXTRA_TOOL_STATUS = "tool_status"

        const val ACTION_GLASSES_STATE = "com.repository.listener.GLASSES_STATE"
        const val ACTION_GLASSES_THROUGHPUT = "com.repository.listener.GLASSES_THROUGHPUT"
        const val ACTION_GLASSES_RETRY_COUNTDOWN = "com.repository.listener.GLASSES_RETRY_COUNTDOWN"
        const val ACTION_GLASSES_BATTERY = "com.repository.listener.GLASSES_BATTERY"
        const val ACTION_RECORDING_STATE_UPDATED = "com.repository.listener.RECORDING_STATE_UPDATED"
        const val EXTRA_RETRY_SECONDS = "retry_seconds"
        const val EXTRA_GLASSES_CONNECTED = "glasses_connected"
        const val EXTRA_GLASSES_CONNECTING = "glasses_connecting"
        const val EXTRA_GLASSES_DEVICE_NAME = "glasses_device_name"
        const val EXTRA_GLASSES_MAC = "glasses_mac"
        const val EXTRA_BATTERY_LEVEL = "battery_level"
        const val EXTRA_BATTERY_CHARGING = "battery_charging"
        const val EXTRA_TX_KBPS = "tx_kbps"
        const val EXTRA_RX_KBPS = "rx_kbps"
        const val EXTRA_TX_TOTAL = "tx_total"
        const val EXTRA_RX_TOTAL = "rx_total"

        const val ACTION_ADB_DISPATCH = "com.repository.listener.ADB_DISPATCH"

        const val ACTION_RECORDING_RESULT = "com.repository.listener.RECORDING_RESULT"
        const val ACTION_AR_STREAM_RESULT = "com.repository.listener.AR_STREAM_RESULT"
        const val ACTION_TELEPROMPTER_STATE = "com.repository.listener.TELEPROMPTER_STATE"

        const val ACTION_TRANSLATION_STATE = "com.repository.listener.TRANSLATION_STATE"
        const val EXTRA_TRANSLATION_ACTIVE = "translation_active"
        const val ACTION_COPILOT_STATE = "com.repository.listener.COPILOT_STATE"
        const val EXTRA_COPILOT_ACTIVE = "copilot_active"
        const val EXTRA_COPILOT_STATUS = "copilot_status"

        // Lone mode: glasses push the merged foreign-device list here for the modal.
        const val ACTION_LONE_DEVICES = "com.repository.listener.LONE_DEVICES"
        const val EXTRA_LONE_DEVICES = "lone_devices_json"

        const val ACTION_MOUSE_HID_STATUS = "com.repository.listener.MOUSE_HID_STATUS"
        const val EXTRA_MOUSE_HID_CONNECTED = "hid_connected"
        const val EXTRA_MOUSE_HID_DEVICE_NAME = "hid_device_name"

        const val ACTION_START_GLASSES_SYNC = "com.repository.listener.START_GLASSES_SYNC"
        const val ACTION_GLASSES_SYNC_STATUS = "com.repository.listener.GLASSES_SYNC_STATUS"
        const val EXTRA_SYNC_STATE = "sync_state"
        const val EXTRA_SYNC_CURRENT = "sync_current"
        const val EXTRA_SYNC_TOTAL = "sync_total"
        const val EXTRA_SYNC_MESSAGE = "sync_message"

        const val ACTION_START_GLASSES_DELETE = "com.repository.listener.START_GLASSES_DELETE"
        const val ACTION_GLASSES_DELETE_STATUS = "com.repository.listener.GLASSES_DELETE_STATUS"
        const val EXTRA_DELETE_FILENAMES = "delete_filenames"

        const val ACTION_STREAM_REQUEST = "com.repository.listener.STREAM_REQUEST"
        const val ACTION_STREAM_STOP = "com.repository.listener.STREAM_STOP"
        const val ACTION_STREAM_SWITCH_MONITOR = "com.repository.listener.STREAM_SWITCH_MONITOR"
        const val ACTION_STREAM_ACK = "com.repository.listener.STREAM_ACK"
        const val ACTION_STREAM_ENDED = "com.repository.listener.STREAM_ENDED"
        const val ACTION_AUDIO_RELAY_START = "com.repository.listener.AUDIO_RELAY_START"
        const val ACTION_AUDIO_RELAY_STOP = "com.repository.listener.AUDIO_RELAY_STOP"
        const val ACTION_AUDIO_RELAY_ACK = "com.repository.listener.AUDIO_RELAY_ACK"
        const val ACTION_AUDIO_RELAY_CONFIG = "com.repository.listener.AUDIO_RELAY_CONFIG"
        const val ACTION_AUDIO_RELAY_ERROR = "com.repository.listener.AUDIO_RELAY_ERROR"
        const val ACTION_STREAM_ERROR = "com.repository.listener.STREAM_ERROR"
        const val EXTRA_ERROR_REASON = "error_reason"
        const val EXTRA_AUDIO_SAMPLE_RATE = "audio_sample_rate"
        const val EXTRA_AUDIO_CHANNELS = "audio_channels"
        const val EXTRA_AUDIO_BITRATE = "audio_bitrate"
        const val EXTRA_AUDIO_FRAME_SIZE = "audio_frame_size"
        const val EXTRA_AUDIO_FRAME_DURATION_MS = "audio_frame_duration_ms"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_STREAM_WIDTH = "stream_width"
        const val EXTRA_STREAM_HEIGHT = "stream_height"
        const val EXTRA_STREAM_FPS = "stream_fps"
        const val EXTRA_STREAM_MONITOR_COUNT = "stream_monitor_count"
        const val EXTRA_TARGET_DEVICE_ID = "target_device_id"
        const val EXTRA_MONITOR = "monitor"

        // Todo tab (fragment -> service -> orchestrator -> service -> fragment)
        const val ACTION_TODO_LIST_REQ = "com.repository.listener.TODO_LIST_REQ"
        const val ACTION_TODO_TOGGLE = "com.repository.listener.TODO_TOGGLE"
        const val ACTION_TODO_ADD = "com.repository.listener.TODO_ADD"
        const val ACTION_TODO_DELETE = "com.repository.listener.TODO_DELETE"
        const val ACTION_TODO_MOVE = "com.repository.listener.TODO_MOVE"
        const val ACTION_TODO_RESULT = "com.repository.listener.TODO_RESULT"
        const val EXTRA_TODO_DATA = "todo_data"

        // Jobs tab (fragment -> service -> orchestrator -> service -> fragment)
        const val ACTION_JOB_LIST_REQ = "com.repository.listener.JOB_LIST_REQ"
        const val ACTION_JOB_CREATE = "com.repository.listener.JOB_CREATE"
        const val ACTION_JOB_UPDATE = "com.repository.listener.JOB_UPDATE"
        const val ACTION_JOB_DELETE = "com.repository.listener.JOB_DELETE"
        const val ACTION_JOB_RESULT = "com.repository.listener.JOB_RESULT"
        const val ACTION_JOB_NOTIFICATION = "com.repository.listener.JOB_NOTIFICATION"
        const val EXTRA_JOB_DATA = "job_data"

        // Telegram saved messages (fragment -> service -> orchestrator -> service -> fragment)
        const val ACTION_TELEGRAM_SAVED_REQ = "com.repository.listener.TELEGRAM_SAVED_REQ"
        const val ACTION_TELEGRAM_SAVED_RESULT = "com.repository.listener.TELEGRAM_SAVED_RESULT"
        const val ACTION_TELEGRAM_SAVED_ERROR = "com.repository.listener.TELEGRAM_SAVED_ERROR"
        const val EXTRA_TELEGRAM_DATA = "telegram_data"

        // Alarm (local, UI refresh)
        const val ACTION_ALARM_CHANGED = "com.repository.listener.ALARM_CHANGED"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        // Remote Control (service -> RemoteControlActivity)
        const val ACTION_RC_SESSION_START = "com.repository.listener.RC_SESSION_START"
        const val ACTION_RC_SESSION_END = "com.repository.listener.RC_SESSION_END"
        const val ACTION_RC_MESSAGE = "com.repository.listener.RC_MESSAGE"
        const val ACTION_RC_PERMISSION_REQUEST = "com.repository.listener.RC_PERMISSION_REQUEST"
        const val ACTION_RC_TOOL_STATUS = "com.repository.listener.RC_TOOL_STATUS"
        const val ACTION_RC_PLAN_UPDATE = "com.repository.listener.RC_PLAN_UPDATE"
        const val ACTION_RC_AGENT_STATUS = "com.repository.listener.RC_AGENT_STATUS"
        const val ACTION_RC_THINKING = "com.repository.listener.RC_THINKING"
        const val ACTION_RC_THINKING_END = "com.repository.listener.RC_THINKING_END"
        const val ACTION_RC_MODE_CHANGE = "com.repository.listener.RC_MODE_CHANGE"
        const val ACTION_RC_USER_INPUT = "com.repository.listener.RC_USER_INPUT"
        const val ACTION_RC_TRANSCRIPT = "com.repository.listener.RC_TRANSCRIPT"
        const val ACTION_RC_ERROR = "com.repository.listener.RC_ERROR"
        // Outbound rc_user_message lifecycle update (sending/queued/retrying/
        // delivered/failed) -- carries EXTRA_RC_SESSION_ID, EXTRA_RC_REQUEST_ID,
        // EXTRA_RC_USER_MSG_STATUS, EXTRA_RC_USER_MSG_ATTEMPT, EXTRA_RC_USER_MSG_NEXT_RETRY_AT.
        const val ACTION_RC_USER_MSG_STATUS = "com.repository.listener.RC_USER_MSG_STATUS"
        // Inbound: activity asks the service to re-broadcast the latest cached
        // rc_user_message status for a session. Used on RemoteControlActivity
        // onResume to re-sync the UI after the activity was paused/killed
        // and missed live status broadcasts.
        const val ACTION_RC_USER_MSG_STATUS_QUERY = "com.repository.listener.RC_USER_MSG_STATUS_QUERY"
        const val EXTRA_RC_REQUEST_ID = "rc_request_id"
        const val EXTRA_RC_USER_MSG_STATUS = "rc_user_msg_status"
        const val EXTRA_RC_USER_MSG_ATTEMPT = "rc_user_msg_attempt"
        const val EXTRA_RC_USER_MSG_NEXT_RETRY_AT = "rc_user_msg_next_retry_at"

        const val EXTRA_RC_SESSION_ID = "rc_session_id"
        const val EXTRA_RC_DATA = "rc_data"

        // Remote Control inbound (RemoteControlActivity -> service -> orchestrator)
        const val ACTION_RC_PERMISSION_RESP = "com.repository.listener.RC_PERMISSION_RESP"
        const val ACTION_RC_USER_RESP = "com.repository.listener.RC_USER_RESP"
        const val ACTION_RC_MODE_REQ = "com.repository.listener.RC_MODE_REQ"
        const val ACTION_RC_TRANSCRIPT_REQ = "com.repository.listener.RC_TRANSCRIPT_REQ"
        const val ACTION_RC_USER_MSG = "com.repository.listener.RC_USER_MSG"
        const val ACTION_RC_INTERRUPT = "com.repository.listener.RC_INTERRUPT"
        const val ACTION_RC_REVIVE = "com.repository.listener.RC_REVIVE"
        const val ACTION_RC_SETTING_CHANGE = "com.repository.listener.RC_SETTING_CHANGE"
        // Sent by ChatsListFragment when the user opens an RC row, so the service
        // can clear the unread flag on rcDumpState[sessionId] and rebroadcast it.
        const val ACTION_RC_MARK_READ = "com.repository.listener.RC_MARK_READ"
        // Sent by the service after the unread flag flips (turn-end -> true,
        // or markRcRead -> false). Carries EXTRA_RC_SESSION_ID and a boolean
        // extra "rc_unread". The fragment uses this to refresh dot color and
        // folder-chip badge counts without re-deriving from rcMessage payloads.
        const val ACTION_RC_UNREAD_CHANGED = "com.repository.listener.RC_UNREAD_CHANGED"
        const val EXTRA_RC_UNREAD = "rc_unread"

        // Weather widget: UI toggled off -> service relays a hide frame to glasses.
        const val ACTION_WEATHER_HIDE = "com.repository.listener.WEATHER_HIDE"

        // Telegram push notifications (TelegramNotificationListener -> service -> glasses toast)
        const val ACTION_TELEGRAM_NOTIF = "com.repository.listener.TELEGRAM_NOTIF"
        const val EXTRA_TG_SENDER = "tg_sender"
        const val EXTRA_TG_TEXT = "tg_text"
        const val EXTRA_TG_CHAT = "tg_chat"
        const val EXTRA_TG_TIMESTAMP = "tg_timestamp"
        const val EXTRA_TG_REPLIABLE = "tg_repliable"
        const val EXTRA_TG_SBN_KEY = "tg_sbn_key"

        private const val WAKE_COMPARISON_WINDOW_MS = 1000L

        @Volatile
        var hasProjection = false

        // Chat responding state -- readable by fragments on resume
        const val ACTION_CHAT_RESPONDING = "com.repository.listener.CHAT_RESPONDING"
        const val EXTRA_RESPONDING_CONVERSATION_ID = "responding_conversation_id"
        const val EXTRA_IS_RESPONDING = "is_responding"
        val respondingChatIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val thinkingRcStartTimes: java.util.concurrent.ConcurrentHashMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

        /**
         * Service-level mirror of RC session state. Keyed by sessionId. Feeds both the
         * chatlist_dump ADB test hook and the CH_RC_STATE_PUSH snapshot the glasses render, so
         * dumps and pushes work even when no fragment is resumed.
         *
         * Two writers, and their provenance is tracked per entry (see RcDumpEntry.discovered):
         *  - orchestrator WebSocket events (onRcSessionStart/End/Message/Thinking): rich, but only
         *    for sessions that WS actually announced, and silent while the socket is down;
         *  - the orchestrator REST session list, reconciled by [reconcileLiveRcSessions]: the only
         *    source that sees a session started from this app's own RC UI, but it knows nothing
         *    about turning, unread or lastSeq.
         *
         * The entry type lives in the rc package so the merge is plain-JVM testable.
         */
        val rcDumpState: MutableMap<String, RcDumpEntry> = java.util.concurrent.ConcurrentHashMap()

        /**
         * In-process cache of the latest RC transcript JSON per session.
         * Populated by onRcTranscript and read by ChatsListFragment /
         * RemoteControlActivity instead of round-tripping the (potentially
         * megabyte-sized) payload through Intent extras, which would blow
         * past the Binder transaction limit and get the receiver process
         * killed with "Can't deliver broadcast".
         */
        val rcTranscriptCache: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

        // Current glasses state -- readable by fragments on resume
        @Volatile var glassesConnected = false
        @Volatile var glassesConnecting = false

        /**
         * Static handle to the running service's PhoneBtHost so Activities
         * (e.g. GlassesSettingsActivity) can fire a CH_SETTINGS push without
         * binding to the service. Populated in onCreate, cleared in onDestroy.
         */
        @Volatile var phoneBtHostInstance: PhoneBtHost? = null

        /** True iff glasses are currently BT-connected. */
        fun isBtConnected(): Boolean = glassesConnected

        @Volatile var glassesDeviceName: String? = null
        @Volatile var glassesDeviceMac: String? = null
        @Volatile var glassesBatteryLevel: Int = -1
        @Volatile var glassesBatteryCharging: Boolean = false

        // Recording state mirror -- updated from glasses status frames; drives the
        // AudioRecordingListFragment record/always-record buttons.
        @Volatile var alwaysRecordEnabled: Boolean = true
        @Volatile var onDemandActive: Boolean = false
        @Volatile var glassesRecordingActive: Boolean = false
        @Volatile var glassesWornMirror: Boolean = true

        /** Static handle to the running service's GlassesSyncClient so static helpers
         *  (e.g. requestImmediateSync) can nudge a pull cycle without binding. */
        @Volatile var glassesSyncClientInstance: GlassesSyncClient? = null

        /** Triggers an immediate WiFi P2P sync pull. Called when the user presses Stop
         *  on the on-demand record toggle so the just-closed file appears in the list
         *  without waiting for the periodic sync timer. */
        @JvmStatic
        fun requestImmediateSync() {
            try {
                glassesSyncClientInstance?.forceSync()
            } catch (e: Exception) {
                LogCollector.w(TAG, "requestImmediateSync failed: ${e.message}")
            }
        }

        /** Static handle to the running service's sideload LAN server so the settings UI
         *  can start/stop it when the enable_sideloading toggle flips without binding. */
        @Volatile var sideloadHttpServerInstance: com.repository.listener.sync.SideloadHttpServer? = null

        /** Serializes persist+apply so the persisted flag and the running server can never
         *  diverge under concurrent toggles (UI thread vs ADB binder thread). */
        private val sideloadStateLock = Any()

        /** Persist the enable_sideloading flag AND apply it to the LAN HTTP server lifecycle
         *  as one atomic step. The server start()/stop() act on the value that was just
         *  persisted under the same lock, so a racing toggle cannot leave runtime and
         *  persisted state disagreeing. Idempotent: start()/stop() are no-ops when already
         *  in the target state. */
        @JvmStatic
        fun applySideloadingState(context: Context, enabled: Boolean) {
            synchronized(sideloadStateLock) {
                try {
                    AppConfig.setSideloadingEnabled(context, enabled)
                    val server = sideloadHttpServerInstance ?: run {
                        LogCollector.w(TAG, "applySideloadingState: server not ready")
                        return
                    }
                    if (enabled) server.start() else server.stop()
                } catch (e: Exception) {
                    LogCollector.w(TAG, "applySideloadingState failed: ${e.message}")
                }
            }
        }

        // Current orchestrator state -- readable by fragments on resume
        @Volatile var orchestratorConnected = false

        // Direct callback for high-throughput binary video frames (no broadcast overhead)
        @Volatile var streamFrameListener: ((ByteArray) -> Unit)? = null

        // Direct callback for mouse events (no broadcast overhead)
        @Volatile var mouseEventListener: ((ByteArray) -> Unit)? = null

        /**
         * Live AR stream uplink tap, set by ArStreamActivity while a session is open.
         * Receives the same 16 kHz mono PCM16 chunks the wake-word pipeline consumes.
         */
        @Volatile var arStreamMicSink: ((ShortArray) -> Unit)? = null

        // Direct callback for keyboard events (no broadcast overhead)
        @Volatile var keyboardEventListener: ((ByteArray) -> Unit)? = null

        // Audio relay active state (readable by DesktopFragment for UI)
        @Volatile var audioRelayActive = false

        /**
         * Remote input has claimed the radio and the relay is being torn down for it.
         *
         * Teardown is asynchronous (peer connection close plus ICE unwind), so
         * [audioRelayActive] stays true for many milliseconds after the decision. Paths
         * that defer to the relay must consult this too, or they spend that whole window
         * refusing to serve the input the teardown was performed FOR -- which is the
         * window the user is actively turning the bezel in.
         *
         * Cleared when the relay actually stops, or when it is started again.
         */
        @Volatile var audioRelayYieldedToInput = false

        @Volatile var audioArchiver: GlassesAudioArchiver? = null
    }

    enum class State { IDLE, ACTIVATING, LISTENING, FINISHING, RESPONDING }

    @Volatile
    private var state = State.IDLE
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var vadEngine: VadEngine
    private lateinit var speakerVerifier: SpeakerVerifier
    private lateinit var orchestratorClient: OrchestratorClient
    private lateinit var transcriptionClient: TranscriptionClient
    private var transcriberStreamClient: TranscriberStreamClient? = null
    private var transcriberUrl: String? = null
    private var reidAnalyticsClient: ReidAnalyticsClient? = null
    @Volatile
    private var webRTCClient: WebRTCClient? = null
    // Set in onDestroy so callbacks already queued on the main looper (posted from
    // the OkHttp/WebRTC threads) don't resurrect a dead service by building a new
    // PeerConnectionFactory or re-arming relay state.
    @Volatile
    private var serviceDestroyed = false
    @Volatile
    private var audioRelayRetryCount = 0
    private val audioRelayRetryDelays = longArrayOf(2_000, 5_000, 15_000)
    // Steady-state interval once the backoff ladder is exhausted, so a relay that keeps
    // failing still recovers on its own when the desktop comes back.
    private val AUDIO_RELAY_BACKSTOP_DELAY_MS = 60_000L
    // Incremented per start attempt so late errors can be attributed to their session.
    @Volatile
    private var audioRelaySessionEpoch = 0
    @Volatile
    private var audioRelayRetryRunnable: Runnable? = null
    // The stream id of the offer we are currently answering. A WebRTC disconnect
    // for any other (stale) stream is ignored so peer-swap closes don't trigger
    // spurious retries.
    @Volatile
    private var currentAudioStreamId = -1
    // Coalescing guard: true while a start attempt is in flight (from any driver)
    // so concurrent starts don't stampede. Cleared on connect, failure, or window.
    @Volatile
    private var audioRelayStartInFlight = false
    @Volatile
    private var audioRelayStartWatchdog: Runnable? = null
    // Must exceed a realistic worst-case start (LAN connect ~4s + ICE_GATHER_TIMEOUT_MS
    // 10s in WebRTCClient); a shorter window drops the guard mid-attempt and lets a
    // second client in to tear down the first.
    private val AUDIO_RELAY_START_WINDOW_MS = 25_000L

    // LAN-direct audio relay: when the phone and PC are on the same network the
    // audio-relay signaling connects straight to the desktop's local server
    // (discovered via mDNS), skipping the cloud orchestrator hop. When no PC is
    // discovered / reachable it transparently falls back to the cloud path.
    @Volatile
    private var desktopRelayLocator: DesktopRelayLocator? = null
    @Volatile
    private var lanRelayClient: LanRelayClient? = null
    // True while the current audio-relay session is signaling over the LAN
    // channel. Determines whether WebRTC answer/ICE go to lanRelayClient or the
    // cloud orchestratorClient.
    @Volatile
    private var audioRelayViaLan = false
    // Transport the current offer arrived on, so its answer is routed back the same way
    // even if the active transport flips during the up-to-10s ICE gather.
    @Volatile
    private var audioRelayAnswerStreamId = -1
    @Volatile
    private var audioRelayAnswerViaLan = false
    // Watches VPN up/down so a toggle migrates the session to the transport that still works.
    private var audioRelayVpnCallback: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var audioRelayNetworkRecheck: Runnable? = null
    private var audioRelayVpnDeferrals = 0
    private val MAX_VPN_RECHECK_DEFERRALS = 12

    // Held while WebRTC audio is streaming. Without it Android WiFi power-save
    // duty-cycles the radio (especially on 2.4 GHz), adding periodic 100-300ms
    // latency spikes to the UDP media flow that are audible as regular hiccups.
    private var audioRelayWifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireAudioRelayWifiLock() {
        try {
            if (audioRelayWifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else
                    @Suppress("DEPRECATION") android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                audioRelayWifiLock = wm.createWifiLock(mode, "listener:audioRelay")
            }
            audioRelayWifiLock?.takeIf { !it.isHeld }?.let {
                it.acquire()
                LogCollector.i(TAG, "Audio relay: WiFi low-latency lock acquired")
            }
        } catch (e: Exception) {
            LogCollector.w(TAG, "Audio relay: WiFi lock acquire failed: ${e.message}")
        }
    }

    private fun releaseAudioRelayWifiLock() {
        try {
            audioRelayWifiLock?.takeIf { it.isHeld }?.let {
                it.release()
                LogCollector.i(TAG, "Audio relay: WiFi low-latency lock released")
            }
        } catch (e: Exception) {
            LogCollector.w(TAG, "Audio relay: WiFi lock release failed: ${e.message}")
        }
    }
    private lateinit var screenCapturer: ScreenCapturer
    private lateinit var cameraCapturer: CameraCapturer
    private lateinit var locationProvider: LocationProvider
    private lateinit var audioToolRecorder: AudioToolRecorder
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var audioDucker: AudioDucker
    private lateinit var screenStateReceiver: ScreenStateReceiver
    private lateinit var overlay: OverlayWidget
    private var activatePlayer: MediaPlayer? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Safety timeout: if TTS playback doesn't start within this window after receiving
    // a success response, dismiss the overlay (e.g., orchestrator returned no TTS audio)
    private val ttsTimeoutMs = 120_000L
    private val ttsTimeoutRunnable = Runnable {
        if (state == State.RESPONDING) {
            LogCollector.w(TAG, "TTS timeout - no audio received, dismissing")
            dismiss()
        }
    }

    private val audioBuffer = mutableListOf<ShortArray>()
    @Volatile
    private var lastSpeechTime = 0L
    @Volatile
    private var recordingStartTime = 0L
    @Volatile
    private var speechDetected = false
    @Volatile
    private var requestSentTimestamp: Long = 0
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prevBars = FloatArray(28)

    // --- Lone mode (foreign-device proximity alarm) ---
    @Volatile private var loneActive = false
    private var loneScanJob: kotlinx.coroutines.Job? = null
    private val loneScanner by lazy { com.repository.listener.scanner.BluetoothScanner(this) }

    private fun startLoneMode() {
        if (loneActive) return
        loneActive = true
        val trusted = AppConfig.getLoneTrusted(this).toMutableSet()
        val glassesMac = glassesDeviceMac
        if (!glassesMac.isNullOrBlank()) trusted.add(glassesMac.uppercase())
        // The phone's own paired/bonded devices are trusted by default (earbuds, watch, car, etc.).
        try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.forEach {
                it.address?.let { a -> trusted.add(a.uppercase()) }
            }
        } catch (_: Exception) {}
        AppConfig.setLoneTrusted(this, trusted)
        // Pair names: our phone+glasses advertise rotating BLE MACs that differ from their bond
        // MAC, so the glasses also auto-trust any device matching these names (keeps the pair
        // exempt as addresses rotate).
        val pairNames = org.json.JSONArray()
        try { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name?.let { pairNames.put(it) } } catch (_: Exception) {}
        AppConfig.getGlassesDeviceName(this).takeIf { it.isNotBlank() }?.let { pairNames.put(it) }
        val startJson = JSONObject().apply {
            put("trusted", org.json.JSONArray(trusted.toList()))
            if (!glassesMac.isNullOrBlank()) put("glasses_mac", glassesMac)
            put("trusted_names", pairNames)
        }.toString()
        phoneBtHost.sendLoneStart(startJson)
        val previousScan = loneScanJob
        loneScanJob = serviceScope.launch(Dispatchers.IO) {
            // Ensure any prior (cancelled) 8s scan has fully finished before we scan again, so two
            // scan() calls never run concurrently on the shared loneScanner.
            try { previousScan?.cancel(); previousScan?.join() } catch (_: Exception) {}
            while (loneActive) {
                try {
                    val devices = loneScanner.scan(8_000)
                    if (loneActive && devices.isNotEmpty()) {
                        val arr = org.json.JSONArray()
                        for (d in devices) {
                            arr.put(JSONObject().apply {
                                put("address", d.address)
                                put("name", d.name)
                                put("rssi", d.rssi)
                            })
                        }
                        phoneBtHost.sendLoneDevices(JSONObject().put("devices", arr).toString())
                    }
                } catch (e: Exception) {
                    LogCollector.w(TAG, "Lone scan failed: ${e.message}")
                }
                delay(2_000)
            }
        }
        LogCollector.i(TAG, "Lone mode started (trusted=${trusted.size}, glassesMac=$glassesMac)")
    }

    private fun stopLoneMode() {
        // Always tell the glasses to stop, even if this phone process never started lone mode
        // itself (it may have restarted while the glasses stayed active standalone). The glasses
        // are the source of truth; the in-memory loneActive flag is only for the phone scan loop.
        loneActive = false
        loneScanJob?.cancel()
        loneScanJob = null
        try { phoneBtHost.sendLoneStop() } catch (_: Exception) {}
        LogCollector.i(TAG, "Lone mode stopped")
    }

    /** Toggle a device's trust from the modal/ADB: persist + propagate to glasses. */
    fun setLoneTrust(address: String, trusted: Boolean) {
        if (address.isBlank()) return
        val set = AppConfig.getLoneTrusted(this).toMutableSet()
        if (trusted) set.add(address.uppercase()) else set.remove(address.uppercase())
        AppConfig.setLoneTrusted(this, set)
        phoneBtHost.sendLoneTrustUpdate(
            JSONObject().put("address", address).put("trusted", trusted).toString()
        )
        LogCollector.i(TAG, "Lone trust: $address -> $trusted")
    }
    private var vadChunkCount = 0

    private lateinit var phoneBtHost: PhoneBtHost
    private var phoneHidMouse: PhoneHidMouseBridge? = null
    private lateinit var healthMonitor: GlassesHealthMonitor
    private val glassesRequestIds: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    // Telegram notification listener health check (every 60s).
    // Intentional overlap with TelegramNotificationListener's own rebind logic -- defense-in-depth
    // because the listener's handler may be dead if Android fully killed the service.
    // Uses probeAlive() to detect MIUI silent kills where onListenerDisconnected() is never called.
    private val notifListenerHealthCheck = object : Runnable {
        override fun run() {
            if (!TelegramNotificationListener.probeAlive()) {
                LogCollector.i(TAG, "Notification listener dead, requesting rebind")
                try {
                    android.service.notification.NotificationListenerService.requestRebind(
                        android.content.ComponentName(this@ListenerService, TelegramNotificationListener::class.java)
                    )
                } catch (e: Exception) {
                    LogCollector.i(TAG, "Notification listener rebind failed: ${e.message}")
                }
            }
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    // Telegram chat state
    @Volatile private var currentGlassesTgChatTitle: String? = null
    @Volatile private var telegramVoiceMode = false
    @Volatile private var telegramVoiceChatId: String? = null
    // Notification voice reply: when non-null, the active telegram-voice capture
    // session is replying to a notification (not a TG chat). Holds the notifId
    // used to look up the stored RemoteInput reply action on finalize.
    @Volatile private var notifReplyId: String? = null
    // notifId -> (sbnKey, sender, chat), so CH_NOTIF_REPLY_SEND can resolve the
    // right NotificationReplyStore entry and surface context on failure.
    // Populated when a repliable notification is sent to the glasses.
    private data class NotifReplyRef(val sbnKey: String, val sender: String, val chat: String)
    private val notifIdToReplyRef = java.util.concurrent.ConcurrentHashMap<String, NotifReplyRef>()
    // Continuation-TTS for merged same-sender messages. The merge is UI-only, but
    // each absorbed message is spoken out-of-band: we request TTS under a synthetic
    // contId (so the queue's state machine for the real notifId is untouched), then
    // map the returned audio back to the real on-screen notifId to play it on the
    // existing overlay. Entry removed when its audio arrives.
    private val appendTtsContToNotif = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val telegramVoiceAudioBuffer = java.util.Collections.synchronizedList(mutableListOf<ShortArray>())
    @Volatile private var telegramVoiceSpeechDetected = false
    @Volatile private var telegramVoiceLastSpeechTime = 0L
    // No-speech watchdog: a hands-free reply where the user never speaks would
    // hang forever (VAD end-of-speech only fires after speech was detected). If no
    // speech is seen within NO_SPEECH_TIMEOUT_MS of arming, finalize anyway (the
    // empty transcript path tells the glasses to cancel the reply).
    private var telegramVoiceNoSpeechWatchdog: Runnable? = null
    // finishTelegramVoiceRecording() is reachable from THREE threads (the VAD
    // audio-callback thread, the no-speech watchdog on mainHandler, and the BT
    // stop/cancel callbacks on the RFCOMM read-loop thread). Without a latch two
    // near-simultaneous calls each pass the guards and BOTH emit a final --
    // typically one blank + one real -- which is what produced the stray blank
    // tg_voice that wedged the glasses "Thinking..." spinner. This CAS gate lets
    // exactly ONE caller finalize per session; it is reset when a session arms.
    private val telegramVoiceFinalizing = java.util.concurrent.atomic.AtomicBoolean(false)

    // Notification voice-reply via Azure Speech. When notifReplyAzure is true, the
    // active notif reply streams glasses-mic PCM into a dedicated Azure session
    // (azureNotifReplySession) instead of the transcriber stream. Azure owns its own
    // segmentation, so the local Silero VAD end-of-speech path is skipped on this
    // route. notifReplyAccum collects each Azure `recognized` segment so a multi-
    // sentence reply (separated by a pause -> multiple finals) is emitted as ONE
    // consolidated tg_voice final. notifReplyQuietRunnable derives end-of-reply
    // (Azure has no "human is done" event) by firing a fixed quiet window after the
    // last segment. The transcriber path stays intact for tg-chat voice and the
    // Azure-unconfigured fallback.
    @Volatile private var azureNotifReplySession: AzureTranslationSession? = null
    @Volatile private var notifReplyAzure: Boolean = false
    private val notifReplyAccum = StringBuilder()
    private var notifReplyQuietRunnable: Runnable? = null
    private val NOTIF_REPLY_QUIET_MS = 2200L
    // Rolling inactivity backstop. The quiet timer (NOTIF_REPLY_QUIET_MS) drives the
    // NORMAL fast end-of-reply, but it only arms AFTER a FINAL segment and assumes
    // Azure keeps firing events. Two ways that breaks and the reply hangs:
    //  - silent hold: user holds and never speaks (no recognizing/recognized/canceled).
    //  - mid-reply starvation: the audio socket dies after some partials, the glasses
    //    mic stops, Azure stops getting PCM, so no further onFinal arms the quiet timer
    //    and the reply hangs (observed 71s).
    // This single rolling timer covers BOTH: armed at session start (silent-hold cover)
    // and RE-ARMED on EVERY Azure event (onPartial AND onFinal). As long as recognition
    // progresses it keeps pushing out; when events STOP it fires after the inactivity
    // window and force-finalizes with whatever notifReplyAccum holds (partial reply >
    // hang; blank accum -> glasses cancel). It is LONGER than the quiet timer so it
    // never pre-empts a normal end-of-reply, but bounded so a stalled reply ends in a
    // few seconds, not 71s. Cancelled on every teardown.
    private var notifReplyInactivityRunnable: Runnable? = null
    private val NOTIF_REPLY_INACTIVITY_MS = 8000L

    // ADB chat command tracking: maps orchestrator requestId -> pending ADB command
    private class PendingChatCommand(
        val commandId: String,
        val type: String,
        val startTimeMs: Long,
        val streamedText: java.util.concurrent.atomic.AtomicReference<String> = java.util.concurrent.atomic.AtomicReference(""),
        val toolCalls: java.util.concurrent.CopyOnWriteArrayList<JSONObject> = java.util.concurrent.CopyOnWriteArrayList(),
        @Volatile var timeoutRunnable: Runnable? = null
    )
    private val pendingAdbChatCommands = java.util.concurrent.ConcurrentHashMap<String, PendingChatCommand>()
    private val ADB_CHAT_TIMEOUT_MS = 600_000L

    // ADB job command tracking: resolves when onJobResult() fires
    private class PendingAdbJobCommand(
        val commandId: String,
        val type: String,
        val startTimeMs: Long,
        @Volatile var timeoutRunnable: Runnable? = null
    )
    private val pendingAdbJobCommands = java.util.concurrent.ConcurrentHashMap<String, PendingAdbJobCommand>()
    private val ADB_JOB_TIMEOUT_MS = 30_000L

    // Journey tool: same-turn rejection (prepare must be in a different turn than start)
    @Volatile
    private var lastPrepareRequestId: String? = null

    // Throughput tracking
    private val throughputHandler = Handler(Looper.getMainLooper())
    private var throughputRunning = false
    private var totalTxBytes = 0L
    private var totalRxBytes = 0L
    private val throughputRunnable = object : Runnable {
        override fun run() {
            if (!throughputRunning) return
            val txDelta = phoneBtHost.getAndResetTxBytes()
            val rxDelta = phoneBtHost.getAndResetRxBytes()
            totalTxBytes += txDelta
            totalRxBytes += rxDelta
            val txKbps = txDelta / 1024f
            val rxKbps = rxDelta / 1024f
            sendBroadcast(Intent(ACTION_GLASSES_THROUGHPUT).apply {
                setPackage(packageName)
                putExtra(EXTRA_TX_KBPS, txKbps)
                putExtra(EXTRA_RX_KBPS, rxKbps)
                putExtra(EXTRA_TX_TOTAL, totalTxBytes)
                putExtra(EXTRA_RX_TOTAL, totalRxBytes)
            })
            throughputHandler.postDelayed(this, 1000)
        }
    }

    // Wake lock for screen-off survival
    private var wakeLock: PowerManager.WakeLock? = null

    // File sync -- owns the glasses -> phone sync state machine.
    private lateinit var glassesSyncClient: GlassesSyncClient

    // Sideload -- forwards desktop deploy ops to the glasses over WiFi-Direct, and the
    // LAN HTTP server that the desktop targets (active only while enable_sideloading is on).
    private lateinit var sideloadForwarder: com.repository.listener.sync.SideloadForwarder
    private lateinit var sideloadHttpServer: com.repository.listener.sync.SideloadHttpServer

    // Weather widget cache push -- fires when WeatherWorker updates the cached frame,
    // or when the UI toggles the widget off (hide frame on glasses).
    private val weatherUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!phoneBtHost.isConnected) return
            if (intent.action == ACTION_WEATHER_HIDE) {
                phoneBtHost.sendWeather("", "0", "")
                return
            }
            if (!AppConfig.isWeatherEnabled(this@ListenerService)) return
            val icon = AppConfig.getLastWeatherIcon(this@ListenerService)
            val temp = AppConfig.getLastWeatherTemp(this@ListenerService)
            val loc = AppConfig.getLastWeatherLocation(this@ListenerService)
            phoneBtHost.sendWeather(icon, temp.toString(), loc)
        }
    }

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LogCollector.i("GlassesFileSync", "syncReceiver fired (user or ADB) -- forceSync (connected=$glassesConnected)")
            // If RFCOMM is down (glasses off-head, audio profiles never came up,
            // BLE wake link flapped, etc.) the click would otherwise be wasted:
            // forceSync() sets dirty=true and waits for onBtConnected, but no
            // reconnect attempt is in flight. Kick one off so the user's button
            // press actually causes the link to come up. Idempotent when already
            // connected -- rfcommClient.requestImmediateReconnect short-circuits.
            if (!glassesConnected) {
                phoneBtHost.wakeAndReconnect("sync_button")
            }
            glassesSyncClient.forceSync()
        }
    }

    private val deleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val filenames = intent.getStringArrayListExtra(EXTRA_DELETE_FILENAMES) ?: return
            for (f in filenames) glassesSyncClient.requestDeleteByRelPath(f)
            sendBroadcast(Intent(ACTION_GLASSES_DELETE_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_SYNC_STATE, "QUEUED")
                putExtra(EXTRA_SYNC_MESSAGE, "${filenames.size} delete(s) queued")
                putExtra(EXTRA_SYNC_CURRENT, 0)
                putExtra(EXTRA_SYNC_TOTAL, filenames.size)
            })
        }
    }

    private val streamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STREAM_REQUEST -> {
                    val targetDeviceId = intent.getStringExtra(EXTRA_TARGET_DEVICE_ID) ?: "desktop-listener"
                    val monitor = intent.getIntExtra(EXTRA_MONITOR, 0)
                    val resolution = AppConfig.getVideoResolution(context)
                    val fps = AppConfig.getVideoFps(context)
                    val preset = AppConfig.getVideoPreset(context)
                    val profile = AppConfig.getVideoProfile(context)
                    val keyframeInterval = AppConfig.getVideoKeyframeInterval(context)
                    LogCollector.i(TAG, "Stream request for $targetDeviceId monitor=$monitor $resolution ${fps}fps $preset $profile kf=${keyframeInterval}s")
                    Thread {
                        orchestratorClient.sendStreamRequest(
                            targetDeviceId, resolution, monitor, fps, preset, profile, keyframeInterval
                        )
                    }.start()
                }
                ACTION_STREAM_STOP -> {
                    val streamId = intent.getIntExtra(EXTRA_STREAM_ID, 0)
                    LogCollector.i(TAG, "Stream stop for streamId=$streamId")
                    orchestratorClient.sendStreamStop(streamId)
                }
                ACTION_STREAM_SWITCH_MONITOR -> {
                    val streamId = intent.getIntExtra(EXTRA_STREAM_ID, 0)
                    val monitor = intent.getIntExtra(EXTRA_MONITOR, 0)
                    LogCollector.i(TAG, "Stream switch monitor=$monitor for streamId=$streamId")
                    orchestratorClient.sendStreamSwitchMonitor(streamId, monitor)
                }
                ACTION_AUDIO_RELAY_START -> {
                    val bitrate = intent.getIntExtra("bitrate", 64000)
                    LogCollector.i(TAG, "Audio relay start bitrate=$bitrate")
                    startAudioRelay(bitrate)
                }
                ACTION_AUDIO_RELAY_STOP -> {
                    LogCollector.i(TAG, "Audio relay stop")
                    stopAudioRelay()
                }
            }
        }
    }

    private val notificationQueue = com.repository.listener.notification.NotificationQueue()
    private val notificationHistory = NotificationHistory()
    @Volatile private var cachedTodos: String? = null

    private fun findTodoCompleted(id: String): Boolean? {
        val json = cachedTodos ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("id") == id }
                ?.optBoolean("completed", false)
        } catch (_: Exception) { null }
    }

    private val telegramNotifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_TELEGRAM_NOTIF) return
            val sender = intent.getStringExtra(EXTRA_TG_SENDER) ?: return
            val text = intent.getStringExtra(EXTRA_TG_TEXT) ?: return
            val chat = intent.getStringExtra(EXTRA_TG_CHAT) ?: ""
            val repliable = intent.getBooleanExtra(EXTRA_TG_REPLIABLE, false)
            val sbnKey = intent.getStringExtra(EXTRA_TG_SBN_KEY)
            val notifId = java.util.UUID.randomUUID().toString().take(8)
            val timestamp = intent.getLongExtra(EXTRA_TG_TIMESTAMP, System.currentTimeMillis())

            // Suppress notifications for the chat currently open on glasses
            val openChat = currentGlassesTgChatTitle
            if (openChat != null && (sender == openChat || chat == openChat)) {
                LogCollector.i(TAG, "Suppressed TG notification for open chat: $openChat")
                return
            }

            val displayText = if (chat.isNotEmpty()) "$sender ($chat): $text" else "$sender: $text"
            LogCollector.i(TAG, "Telegram notification: notifId=$notifId ${displayText.take(80)} repliable=$repliable")
            notificationQueue.enqueue(notifId, sender, text, chat, repliable, sbnKey, timestamp)
            notificationHistory.add(sender, text, chat, timestamp)
        }
    }

    private val todoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TODO_LIST_REQ -> {
                    LogCollector.i(TAG, "Todo list request from UI")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoList() }
                }
                ACTION_TODO_TOGGLE -> {
                    val id = intent.getStringExtra("todo_id") ?: return
                    val completed = findTodoCompleted(id) ?: return
                    LogCollector.i(TAG, "Todo toggle from UI: id=$id completed=$completed -> ${!completed}")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoUpdate(id, !completed) }
                }
                ACTION_TODO_ADD -> {
                    val text = intent.getStringExtra("todo_text") ?: return
                    LogCollector.i(TAG, "Todo add from UI: ${text.take(40)}")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoCreate(text) }
                }
                ACTION_TODO_DELETE -> {
                    val id = intent.getStringExtra("todo_id") ?: return
                    LogCollector.i(TAG, "Todo delete from UI: id=$id")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoDelete(id) }
                }
                ACTION_TODO_MOVE -> {
                    val id = intent.getStringExtra("todo_id") ?: return
                    val position = intent.getIntExtra("todo_position", -1)
                    if (position < 0) return
                    LogCollector.i(TAG, "Todo move from UI: id=$id position=$position")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoMove(id, position) }
                }
                ACTION_TELEGRAM_SAVED_REQ -> {
                    LogCollector.i(TAG, "Telegram saved request from UI")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTelegramSaved() }
                }
            }
        }
    }

    private val rcInboundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getStringExtra(EXTRA_RC_SESSION_ID) ?: return
            when (intent.action) {
                ACTION_RC_PERMISSION_RESP -> {
                    val requestId = intent.getStringExtra("rc_request_id") ?: return
                    val approved = intent.getBooleanExtra("rc_approved", false)
                    val modeChange = intent.getStringExtra("rc_mode_change")
                    val reason = intent.getStringExtra("rc_reason")
                    LogCollector.i(TAG, "RC permission response from UI: session=$sessionId approved=$approved${if (reason != null) " reason=$reason" else ""}")
                    // Answered on the phone: the mirrored row still shows its options (the
                    // projection is append-only) so the glasses could still confirm one. Dropping
                    // the pending entry is what makes that confirm a no-op instead of a second,
                    // contradictory answer.
                    rcPrompts.resolve(sessionId, requestId)
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.sendRcPermissionResponse(sessionId, requestId, approved, modeChange, reason)
                    }
                }
                ACTION_RC_USER_RESP -> {
                    val requestId = intent.getStringExtra("rc_request_id") ?: return
                    val text = intent.getStringExtra("rc_text") ?: return
                    LogCollector.i(TAG, "RC user response from UI: session=$sessionId text=${text.take(40)}")
                    // Optimistically mark this session as turning so the notification
                    // counter flips immediately; the orchestrator's THINKING/MESSAGE
                    // events will keep it accurate from there.
                    markRcTurning(sessionId, true)
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.sendRcUserResponse(sessionId, requestId, text)
                    }
                }
                ACTION_RC_MODE_REQ -> {
                    val mode = intent.getStringExtra("rc_mode") ?: return
                    LogCollector.i(TAG, "RC mode change from UI: session=$sessionId mode=$mode")
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.sendRcModeChange(sessionId, mode)
                    }
                }
                ACTION_RC_TRANSCRIPT_REQ -> {
                    LogCollector.i(TAG, "RC transcript catch-up request from UI: session=$sessionId")
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.requestRcTranscript(sessionId)
                    }
                }
                ACTION_RC_USER_MSG -> {
                    val text = intent.getStringExtra(EXTRA_RC_DATA) ?: return
                    LogCollector.i(TAG, "RC proactive user message from UI: session=$sessionId text=${text.take(40)}")
                    markRcTurning(sessionId, true)
                    noteRcUserMessage(sessionId, text)
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.sendRcUserMessage(sessionId, text)
                    }
                }
                ACTION_RC_USER_MSG_STATUS_QUERY -> {
                    handleRcUserMsgStatusQuery(sessionId)
                }
                ACTION_RC_INTERRUPT -> {
                    LogCollector.i(TAG, "RC interrupt from UI: session=$sessionId")
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.sendRcInterrupt(sessionId)
                    }
                }
                ACTION_RC_REVIVE -> {
                    val reviveWorkDir = intent.getStringExtra(EXTRA_RC_DATA) ?: ""
                    LogCollector.i(TAG, "RC revive from UI: session=$sessionId workDir=$reviveWorkDir")
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.sendRcRevive(sessionId, reviveWorkDir)
                    }
                }
                ACTION_RC_SETTING_CHANGE -> {
                    val setting = intent.getStringExtra("rc_setting") ?: return
                    val value = intent.getStringExtra("rc_value") ?: return
                    LogCollector.i(TAG, "RC setting change from UI: session=$sessionId $setting=$value")
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.sendRcSettingChange(sessionId, setting, value)
                    }
                }
                ACTION_RC_MARK_READ -> {
                    markRcRead(sessionId)
                }
            }
        }
    }

    /**
     * Escape non-ASCII characters to \\uXXXX for safe passage through CXR JNI.
     * The CXR native library crashes on certain multi-byte Modified UTF-8 sequences.
     * ASCII-only strings cannot trigger this.
     */
    private fun escapeForJni(input: String): String {
        var hasNonAscii = false
        for (c in input) { if (c.code > 127 || c == '\u0000') { hasNonAscii = true; break } }
        if (!hasNonAscii) return input
        val sb = StringBuilder(input.length * 2)
        for (c in input) {
            if (c.code > 127 || c == '\u0000') {
                sb.append("\\u").append(String.format("%04x", c.code))
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private var alarmRingtone: android.media.Ringtone? = null

    private fun startAlarmRingtone() {
        stopAlarmRingtone()
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = android.media.RingtoneManager.getRingtone(this, uri)
            ringtone?.audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.isLooping = true
            ringtone?.play()
            alarmRingtone = ringtone
            LogCollector.i(TAG, "Alarm ringtone started")
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to play alarm ringtone: ${e.message}")
        }
    }

    private fun stopAlarmRingtone() {
        alarmRingtone?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
        }
        alarmRingtone = null
    }

    private val alarmChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ALARM_CHANGED -> {
                    if (phoneBtHost.isConnected) {
                        serviceScope.launch(Dispatchers.IO) { sendAlarmListToGlasses() }
                    }
                }
                com.repository.listener.alarm.AlarmReceiver.ACTION_ALARM_FIRED -> {
                    val action = intent.getStringExtra("action") ?: ""
                    val alarmId = intent.getIntExtra("alarm_id", 0)

                    if (action == "dismiss" || action == "snooze") {
                        LogCollector.i(TAG, "Alarm $action: stopping ringtone")
                        stopAlarmRingtone()
                        return
                    }

                    // Fire: launch overlay + start ringtone + TTS
                    val timeStr = intent.getStringExtra("time") ?: "Alarm"
                    val title = intent.getStringExtra("title") ?: ""

                    // Launch AlarmActivity from foreground service (better MIUI compat than BroadcastReceiver)
                    try {
                        startActivity(Intent(this@ListenerService, com.repository.listener.alarm.AlarmActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(com.repository.listener.alarm.AlarmActivity.EXTRA_ALARM_ID, alarmId)
                            putExtra(com.repository.listener.alarm.AlarmActivity.EXTRA_TIME, timeStr)
                            putExtra(com.repository.listener.alarm.AlarmActivity.EXTRA_TITLE, title)
                        })
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Failed to launch AlarmActivity: ${e.message}")
                    }

                    startAlarmRingtone()

                    if (phoneBtHost.isConnected) {
                        val sender = "Alarm: $timeStr"
                        val text = title.ifEmpty { timeStr }
                        val notifId = "alarm_$alarmId"
                        notificationQueue.enqueue(notifId, sender, text, "")
                        LogCollector.i(TAG, "Alarm TTS queued: $notifId sender=$sender text=$text")
                    }
                }
            }
        }
    }

    private val webRTCListener = object : WebRTCClient.Listener {
        override fun onWebRTCAnswer(streamId: Int, sdp: String) {
            // Route by the transport this stream's OFFER arrived on, not by the current
            // flag: ICE gathering can take up to 10s, and a transport switch in that
            // window would otherwise send this answer to the wrong peer, so the offering
            // side never gets it and the session dies.
            // Drop an answer for a stream we already abandoned. ICE gathering can take
            // ~10s, so an answer for a torn-down stream can still surface; sending it
            // would set up a peer on the desktop for a session this phone no longer has.
            if (streamId != currentAudioStreamId) {
                LogCollector.i(TAG, "Dropping answer for stale stream $streamId (current=$currentAudioStreamId)")
                return
            }
            val viaLan = if (streamId == audioRelayAnswerStreamId) {
                audioRelayAnswerViaLan
            } else {
                audioRelayViaLan
            }
            if (viaLan) {
                lanRelayClient?.sendWebRTCAnswer(streamId, sdp)
            } else {
                orchestratorClient.sendWebRTCAnswer(streamId, sdp)
            }
        }
        override fun onWebRTCAudioConnected(streamId: Int, peerSeq: Long) {
            // Same staleness rule as the disconnect path: a connect posted by a peer we
            // have already replaced must not mark the session active or clear the retry
            // chain, or a dead session looks healthy and nothing ever recovers it.
            if (serviceDestroyed) return
            val livePeerSeq = webRTCClient?.currentPeerSeq ?: -1L
            if (peerSeq != livePeerSeq) {
                LogCollector.i(TAG, "WebRTC connect for stale peer $peerSeq (live=$livePeerSeq), ignoring")
                return
            }
            LogCollector.i(TAG, "WebRTC audio connected for stream $streamId")
            audioRelayActive = true
            // A relay is up again, so the previous input claim is spent. Leaving it set
            // would permanently disable the deferral for every later relay session.
            audioRelayYieldedToInput = false
            audioRelayRetryCount = 0
            audioRelayRetryRunnable?.let { mainHandler.removeCallbacks(it) }
            audioRelayRetryRunnable = null
            clearAudioRelayStartInFlight()
            acquireAudioRelayWifiLock()
            sendBroadcast(Intent(ACTION_AUDIO_RELAY_ACK).apply {
                setPackage(packageName)
            })
        }
        override fun onWebRTCDisconnected(streamId: Int, peerSeq: Long) {
            // Ignore disconnects from a peer we have already replaced -- retrying on
            // such a stale close is what spawned the reconnect storm.
            // Guard on the peer sequence, not the stream id: ids restart low on each
            // transport, so a replaced peer's disconnect can carry the same id as the
            // session that replaced it and would tear down a healthy connection.
            val livePeerSeq = webRTCClient?.currentPeerSeq ?: -1L
            if (peerSeq != livePeerSeq) {
                LogCollector.i(TAG, "WebRTC disconnect for stale peer $peerSeq (live=$livePeerSeq), ignoring")
                return
            }
            LogCollector.i(TAG, "WebRTC audio disconnected for stream $streamId")
            audioRelayActive = false
            clearAudioRelayStartInFlight()
            releaseAudioRelayWifiLock()
            scheduleAudioRelayRetry()
        }
    }

    private val jobReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_JOB_LIST_REQ -> {
                    LogCollector.i(TAG, "Job list request from UI")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendJobList() }
                }
                ACTION_JOB_CREATE -> {
                    val name = intent.getStringExtra("job_name") ?: return
                    val prompt = intent.getStringExtra("job_prompt") ?: return
                    val scheduledAt = intent.getStringExtra("job_scheduled_at") ?: return
                    LogCollector.i(TAG, "Job create from UI: ${name.take(40)}")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendJobCreate(name, prompt, scheduledAt) }
                }
                ACTION_JOB_UPDATE -> {
                    val id = intent.getStringExtra("job_id") ?: return
                    val name = intent.getStringExtra("job_name")
                    val prompt = intent.getStringExtra("job_prompt")
                    val scheduledAt = intent.getStringExtra("job_scheduled_at")
                    LogCollector.i(TAG, "Job update from UI: id=$id")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendJobUpdate(id, name, prompt, scheduledAt) }
                }
                ACTION_JOB_DELETE -> {
                    val id = intent.getStringExtra("job_id") ?: return
                    LogCollector.i(TAG, "Job delete from UI: id=$id")
                    serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendJobDelete(id) }
                }
            }
        }
    }

    private fun setupMouseEventCallback() {
        // Mouse events are wired when stream_connect arrives
    }

    private fun applyMapTransmitGate() {
        val want = glassesWorn && glassesScreenOn
        if (want == lastMapTransmit) return
        lastMapTransmit = want
        LogCollector.i(TAG, "Map transmit gate: enabled=$want (worn=$glassesWorn screenOn=$glassesScreenOn)")
        com.repository.navigation.NavigationManager.getInstance(this)
            .setMapTransmitEnabled(want)
    }

    // Wake word amplitude conflict resolution (phone mic)
    private val recentRmsValues = FloatArray(15) // ~500ms rolling window
    private var rmsWriteIndex = 0
    @Volatile private var phoneWakeAmplitude = 0f
    @Volatile private var phoneWakeTimestamp = 0L

    // Glasses audio stream processing
    // GlassesAudioState now lives in TranscriptDelivery.kt (same package) so the
    // transcript-delivery logic that drives it is JVM-testable.
    @Volatile private var glassesAudioState = GlassesAudioState.IDLE
    // Model used by the last glasses orchestrator send, so a failed send can arm
    // the reconnect retry with the same model it was built for.
    @Volatile private var lastGlassesSendModel: String = ""
    /**
     * Who is recognising the current glasses session, set from CH_STT_MODE before
     * the session opens. Defaults to remote so an older glasses build, or a
     * reconnect that lands before the announcement, behaves exactly as today --
     * failing the other way would leave nobody transcribing.
     */
    @Volatile private var glassesSttGate: GlassesSttGate = GlassesSttGate.default()
    // Glasses on-head state, relayed over CH_WEAR_STATE. Default true so a missing
    // signal (older glasses build, BT reconnect before first broadcast) preserves
    // prior behaviour (allow ducking).
    @Volatile private var glassesWorn = true
    @Volatile private var glassesScreenOn = true
    @Volatile private var lastMapTransmit = true
    private var glassesVadEngine: VadEngine? = null
    @Volatile private var glassesIdleLastSpeechTime = 0L

    // Raw RFCOMM audio server (bypasses CXR-S for audio data)
    private val AUDIO_SOCKET_UUID = java.util.UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private var btAudioServerSocket: android.bluetooth.BluetoothServerSocket? = null
    @Volatile private var btAudioClientSocket: android.bluetooth.BluetoothSocket? = null
    private var btAudioThread: Thread? = null

    // TCP server for streaming glasses audio to PC (training data collection)
    private var glassesAudioStreamServer: java.net.ServerSocket? = null
    @Volatile private var glassesAudioStreamOut: java.io.OutputStream? = null
    private var glassesAudioArchiver: GlassesAudioArchiver? = null
    private var backgroundTranscriber: com.repository.listener.audio.BackgroundTranscriber? = null
    private val glassesAudioBuffer = mutableListOf<ShortArray>()
    @Volatile private var speakerVerifyBuffering = false
    private var speakerVerifyRunnable: Runnable? = null
    @Volatile private var lastGlassesAudioTimestamp = 0L
    private var micWatchdogRunnable: Runnable? = null
    @Volatile private var glassesLastSpeechTime = 0L
    @Volatile private var glassesRecordingStartTime = 0L
    @Volatile private var glassesSpeechDetected = false
    private var glassesVadChunkCount = 0
    private var glassesNoSpeechWatchdog: Runnable? = null
    private var glassesAudioChunkCount = 0L
    private var confirmRunnable: Runnable? = null
    private var pendingGlassesStreamText = ""
    private var pendingGlassesChunks: List<ShortArray> = emptyList()

    // Prompt-level speaker verification (runs during LISTENING in parallel with transcription)
    @Volatile private var promptSpeakerVerified = false
    @Volatile private var promptSpeakerSimilarity = 0f
    private var promptVerificationJob: kotlinx.coroutines.Job? = null

    // Pending glasses request to retry after orchestrator reconnection
    @Volatile private var pendingGlassesRetry: Pair<String, String?>? = null  // (text, model)

    // Latency instrumentation: first-stream + first-tts arrival per requestId.
    // Send-time is held inside OrchestratorClient.requestSentAtMs.
    private val chatFirstStreamLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val chatFirstTtsLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Latest non-terminal rc_user_message status per session. The UI's
     *  RemoteControlActivity reads this on resume (via ACTION_RC_USER_MSG_STATUS_QUERY)
     *  to recover from being paused/killed while a status broadcast fired. */
    private data class RcUserMsgStatusSnapshot(
        val requestId: String, val status: String, val attempt: Int, val nextRetryAtMs: Long,
    )
    private val rcUserMsgStatusBySession = java.util.concurrent.ConcurrentHashMap<String, RcUserMsgStatusSnapshot>()

    // Cache last glasses photo for auto-attach to next voice request
    @Volatile private var lastGlassesPhotoBase64: String? = null
    @Volatile private var lastGlassesPhotoTimestamp: Long = 0
    private var pendingPhotoDeferred: kotlinx.coroutines.CompletableDeferred<String?>? = null

    // No automatic timeout -- queries run until the orchestrator responds or the user cancels

    private fun setGlassesState(newState: GlassesAudioState, reason: String = "") {
        val oldState = glassesAudioState
        if (oldState == newState) return
        LogCollector.i(TAG, "GlassesState: $oldState -> $newState ($reason)")

        // Exit actions
        when (oldState) {
            GlassesAudioState.LISTENING -> {
                transcriberStreamClient?.release()
                glassesNoSpeechWatchdog?.let { mainHandler.removeCallbacks(it) }
                glassesNoSpeechWatchdog = null
            }
            GlassesAudioState.CONFIRMING -> {
                confirmRunnable?.let { mainHandler.removeCallbacks(it) }
                confirmRunnable = null
            }
            GlassesAudioState.RESPONDING -> {
                // (no response timeout)
            }
            else -> {}
        }

        glassesAudioState = newState

        // Entry actions
        when (newState) {
            GlassesAudioState.IDLE -> {
                glassesRequestIds.clear()
                pendingGlassesRetry = null
                pendingGlassesStreamText = ""
                pendingGlassesChunks = emptyList()
                synchronized(glassesAudioBuffer) { glassesAudioBuffer.clear() }
                stopPromptSpeakerVerification()
            }
            GlassesAudioState.LISTENING -> {
                startTranscriberStream(true)
                startPromptSpeakerVerification(true)
                // The watchdog fires on the absence of PHONE VAD activity. When the glasses
                // recognise the speech themselves the phone is never fed audio, so that
                // absence is the normal case, not a silent wearer -- arming it here dismissed
                // the session mid-utterance and the wearer's words were thrown away.
                if (glassesSttGate.shouldArmNoSpeechWatchdog()) {
                    val watchdog = Runnable {
                        if (glassesAudioState == GlassesAudioState.LISTENING && !glassesSpeechDetected) {
                            LogCollector.i(TAG, "Glasses no-speech watchdog: no VAD activity in ${AppConfig.NO_SPEECH_TIMEOUT_MS}ms, dismissing")
                            setGlassesState(GlassesAudioState.IDLE, "no speech watchdog")
                            phoneBtHost.sendDismissSession()
                        }
                    }
                    glassesNoSpeechWatchdog = watchdog
                    mainHandler.postDelayed(watchdog, AppConfig.NO_SPEECH_TIMEOUT_MS)
                } else {
                    LogCollector.i(TAG, "Glasses no-speech watchdog not armed: glasses own the endpointing")
                }
            }
            GlassesAudioState.RESPONDING -> {
                // (no response timeout)
                // (no response timeout)
            }
            else -> {}
        }
    }

    // --- Prompt-level speaker verification ---

    private fun startPromptSpeakerVerification(isGlasses: Boolean) {
        promptVerificationJob?.cancel()
        promptSpeakerVerified = false
        promptSpeakerSimilarity = 0f

        if (!AppConfig.PROMPT_SPEAKER_VERIFICATION_ENABLED || !AppConfig.SPEAKER_VERIFICATION_ENABLED) {
            promptSpeakerVerified = true
            return
        }
        if (!::speakerVerifier.isInitialized) {
            promptSpeakerVerified = true
            return
        }

        val source = if (isGlasses) "glasses" else "phone"
        promptVerificationJob = serviceScope.launch(Dispatchers.Default) {
            delay(1500) // wait for audio to accumulate
            for (attempt in 1..3) {
                if (!isActive) return@launch
                // Check we're still in LISTENING
                if (isGlasses) {
                    if (glassesAudioState != GlassesAudioState.LISTENING) return@launch
                } else {
                    if (state != State.LISTENING) return@launch
                }

                val audio = copyBufferToFloat(isGlasses)
                if (audio.size < 16000) { // < 1s, too short
                    delay(2000)
                    continue
                }

                try {
                    val (verified, similarity) = speakerVerifier.verify(audio)
                    promptSpeakerSimilarity = similarity
                    if (verified) {
                        promptSpeakerVerified = true
                        LogCollector.i(TAG, "Prompt speaker VERIFIED: similarity=${"%.4f".format(similarity)} source=$source attempt=$attempt")
                        PipelineTracer.onSpeakerAccepted(similarity, AppConfig.SPEAKER_SIMILARITY_THRESHOLD, "$source-prompt")
                        return@launch
                    }
                    LogCollector.d(TAG, "Prompt speaker not verified: similarity=${"%.4f".format(similarity)} source=$source attempt=$attempt")
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Prompt speaker verification error: ${e.message}")
                }
                delay(2000)
            }
            // All attempts exhausted
            LogCollector.i(TAG, "Prompt speaker verification exhausted ($source): similarity=${"%.4f".format(promptSpeakerSimilarity)}")
        }
    }

    private fun copyBufferToFloat(isGlasses: Boolean): FloatArray {
        val chunks: List<ShortArray> = if (isGlasses) {
            synchronized(glassesAudioBuffer) { glassesAudioBuffer.toList() }
        } else {
            synchronized(audioBuffer) { audioBuffer.toList() }
        }
        val totalSamples = chunks.sumOf { it.size }
        val maxSamples = 80000 // 5s at 16kHz
        val capSamples = minOf(totalSamples, maxSamples)
        if (capSamples == 0) return FloatArray(0)

        val audio = FloatArray(capSamples)
        // Take from the END of the buffer (most recent speech)
        var remaining = capSamples
        var dst = capSamples - 1
        for (i in chunks.indices.reversed()) {
            if (remaining <= 0) break
            val chunk = chunks[i]
            val take = minOf(chunk.size, remaining)
            for (j in (chunk.size - 1) downTo (chunk.size - take)) {
                audio[dst--] = chunk[j] / 32768f
            }
            remaining -= take
        }
        return audio
    }

    private fun stopPromptSpeakerVerification() {
        promptVerificationJob?.cancel()
        promptVerificationJob = null
        promptSpeakerVerified = false
        promptSpeakerSimilarity = 0f
    }

    // Glasses RMS tracking for conflict resolution
    private val glassesRmsValues = FloatArray(15)
    private var glassesRmsWriteIndex = 0
    private var glassesChunkCount = 0
    @Volatile private var glassesWakeAmplitude = 0f
    @Volatile private var glassesWakeTimestamp = 0L
    @Volatile private var isGlassesScreenOn = false
    private var screenHeartbeatWatchdog: Runnable? = null
    @Volatile private var glassesAudioRouted = false

    // Translation mode state
    @Volatile private var translationMode = false
    // NLLB codes are only used by the default (orchestrator) translation provider.
    // Still parsed on the Azure path so a mid-session provider switch back to default
    // has the right codes ready without re-parsing the start_translation params.
    private var translationFromNllb = "eng_Latn"
    private var translationToNllb = "rus_Cyrl"
    private var translationFromLang = ""
    private var translationToLang = ""
    private val translationSegmentId = AtomicInteger(0)
    private var translationFontSize = 14
    @Volatile private var translationAudioSource = "glasses"  // "glasses" or "system"
    // When translationAudioSource == "system", which producer currently feeds the session:
    // "playback" = phone MediaProjection (media/A2DP), "call" = glasses HFP call downlink.
    // Flipped by the glasses CH_CALL_STATE relay so "System Audio" also covers phone calls.
    // The inactive producer early-returns, so only one feeds pushPcm at a time.
    @Volatile private var systemSubSource = "playback"        // "playback" or "call"
    @Volatile private var translationProvider = "default"     // "default" or "azure"
    @Volatile private var translationTwoWay = false           // bidirectional dual-mic mode (Azure only)
    private var azureTranslationSession: AzureTranslationSession? = null
    // Two-way uses TWO parallel one-way Azure sessions, each fed by a separate
    // glasses microphone, so source-lang and target-lang audio never mix in the
    // same recognizer. azureTranslationSession holds the FRONT-mic session
    // (source -> target, displayed on the glasses UI for the wearer to read).
    // The INWARD-mic session (target -> source, displayed on the phone screen
    // for the other person to read) lives here. Fed by CH_AUDIO_DATA_INWARD.
    private var azurePhoneMicSession: AzureTranslationSession? = null

    // Opus decoder for the far-party call downlink (CH_AUDIO_DATA_CALL). The glasses
    // Opus-compress that channel to avoid saturating RFCOMM, so it arrives as
    // Base64 of concatenated 2-byte-LE-length Opus frames (same wire format as the
    // dedicated MicStream socket). Long-lived: created lazily on the first call
    // chunk and reused across the session -- a per-chunk MediaCodec create/start
    // would be far too heavy and lose codec state. Guarded by callOpusDecoderLock
    // because call chunks arrive on the BT read thread while stop/sub-source teardown
    // runs on another thread; MediaCodec is not thread-safe and decode() on a
    // released codec throws.
    private var callOpusDecoder: com.repository.listener.audio.OpusStreamDecoder? = null
    private val callOpusDecoderLock = Any()

    // --- Copilot (real-time conversational fact-check) ---
    // Two parallel recognition-only Azure sessions transcribe both speakers in
    // their own language. The wearer session is fed by the inward mic
    // (CH_AUDIO_DATA_INWARD); the interlocutor session is fed by the glasses
    // front beam (CH_AUDIO_DATA) or system audio. Finalized segments accumulate
    // in rolling buffers and are batched to the orchestrator every 5-10s.
    @Volatile private var copilotMode = false
    @Volatile private var copilotInterlocutorSource = "glasses"  // "glasses" or "system"
    // Fact-check model alias sent with each batch. Never blank (defaults to the
    // fastest, "haiku"); resolved from config at start.
    @Volatile private var copilotModel = "haiku"
    // Serializes start/stop so a UI-initiated and glasses-initiated start can't
    // race into half-built state. Azure start/stop block for seconds, so the
    // actual work runs on serviceScope(IO) -- never the main thread (ANR).
    private val copilotLock = Any()
    // Bumped on every start/stop. Async work (Azure init, batch flush, late
    // results) captures the generation at entry and bails if it changed -- so a
    // stop or restart cleanly invalidates everything in flight.
    @Volatile private var copilotGeneration = 0
    @Volatile private var copilotWearerSession: AzureTranslationSession? = null
    @Volatile private var copilotInterlocutorSession: AzureTranslationSession? = null
    private val copilotWearerBuffer = StringBuilder()
    private val copilotInterlocutorBuffer = StringBuilder()
    private val copilotBufferLock = Any()
    // Cards currently shown on the glasses HUD: id -> text. Insertion-ordered so
    // "oldest" is well-defined for FIFO eviction. The id list is sent with each
    // batch so the AI can decide which to dismiss. ALL reads/mutations are guarded
    // by copilotCardLock (touched from the WS callback thread and the batch
    // Handler), as is copilotCardTimeouts.
    private val copilotActiveCards = LinkedHashMap<String, String>()
    // Per-card 5-minute expiry runnables, keyed by card id, so each can be
    // cancelled when the card is removed for ANY reason (no leaked callbacks).
    private val copilotCardTimeouts = HashMap<String, Runnable>()
    private val copilotCardLock = Any()
    // Max cards on the HUD; a 6th evicts the oldest (FIFO). Each card auto-expires
    // after this TTL. Mirrors the glasses overlay backstop.
    private val COPILOT_MAX_CARDS = 5
    private val COPILOT_CARD_TTL_MS = 5 * 60 * 1000L
    private var copilotBatchHandler: android.os.Handler? = null
    private var copilotBatchRunnable: Runnable? = null
    @Volatile private var copilotLastBatchAtMs = 0L
    // Updated by BOTH speakers (fallback / MAX-cap liveness signal).
    @Volatile private var copilotLastContentAtMs = 0L
    // Updated ONLY when the interlocutor (wearer == false) finishes a segment.
    // The batch trigger is driven by the interlocutor going quiet, since the
    // copilot's job is to help the wearer RESPOND to the interlocutor. Wearer
    // speech is still buffered as context but never triggers a send by itself.
    @Volatile private var copilotInterlocutorLastAtMs = 0L
    // Set true when interlocutor content arrives, cleared at flush. Guards the
    // interlocutor-silence trigger so wearer-only chatter does not fire a batch.
    @Volatile private var copilotInterlocutorNew = false
    private val COPILOT_BATCH_MIN_MS = 5_000L
    private val COPILOT_BATCH_MAX_MS = 10_000L
    private val COPILOT_BATCH_TICK_MS = 1_000L
    // Single in-flight batch with back-pressure: at most one batch awaiting an AI
    // response at a time. New speech keeps accumulating in the buffers; the next
    // eligible tick after the response (or the safety timeout) sends one merged batch.
    @Volatile private var copilotInFlight = false
    @Volatile private var copilotInFlightRequestId: String? = null
    @Volatile private var copilotInFlightSince = 0L
    // Id of the PENDING (spinner) card drawn on the glasses for the in-flight
    // batch. A single field is sufficient because only one batch is ever in
    // flight (copilotInFlight back-pressure). It is resolved into the real card,
    // or cancelled, when the result returns -- and cancelled on every other exit
    // path (WS down, in-flight timeout, stop) so the HUD never keeps an orphan
    // spinner.
    @Volatile private var copilotPendingId: String? = null
    private val COPILOT_INFLIGHT_TIMEOUT_MS = 20_000L

    // Debug counters for the translation mic path -- aggregated and logged
    // once per second from the audio callback. Used to disambiguate whether a
    // silent translation tab is caused by no audio reaching us at all vs.
    // audio reaching us but Azure dropping it.
    @Volatile private var translationMicChunkCount: Long = 0L
    @Volatile private var translationMicByteCount: Long = 0L
    @Volatile private var translationMicLastLogMs: Long = 0L
    @Volatile private var translationMicLastWarnMs: Long = 0L

    // VAD-based gating for the Azure push path. Azure's internal segmenter
    // gets confused by long stretches of background-noise-only audio between
    // utterances and stops emitting recognition events; gating the push by
    // our own VAD keeps Azure fed with utterance-bounded audio only.
    //
    // Lookback: keep ~200ms of pre-trigger audio so we don't clip the start
    // of an utterance once VAD crosses the threshold.
    // Hangover: keep pushing for 600ms after VAD drops below threshold so
    // utterance-final phonemes (esp. unvoiced fricatives) are not chopped.
    private val azureLookbackBytes = ArrayDeque<ByteArray>()
    private var azureLookbackTotal = 0
    private val AZURE_LOOKBACK_MAX_BYTES = 16000 * 2 * 200 / 1000  // 200ms @ 16kHz mono 16-bit
    private val AZURE_HANGOVER_MS = 600L
    @Volatile private var azureSpeechActive = false
    @Volatile private var azureLastSpeechMs = 0L
    @Volatile private var azureGatedBytesPushed = 0L
    @Volatile private var azureGatedBytesDropped = 0L
    private var systemAudioVadEngine: VadEngine? = null

    /** Map a short language code (e.g. "en", "ru") to Azure BCP-47 (e.g. "en-US", "ru-RU"). */
    private fun toAzureBcp47(code: String): String {
        val c = code.trim().lowercase()
        if (c.contains("-")) return c
        return when (c) {
            "en" -> "en-US"
            "ru" -> "ru-RU"
            "zh" -> "zh-CN"
            "ja" -> "ja-JP"
            "ko" -> "ko-KR"
            "fr" -> "fr-FR"
            "de" -> "de-DE"
            "es" -> "es-ES"
            "it" -> "it-IT"
            "pt" -> "pt-PT"
            "ar" -> "ar-SA"
            "hi" -> "hi-IN"
            "tr" -> "tr-TR"
            "vi" -> "vi-VN"
            "th" -> "th-TH"
            "id" -> "id-ID"
            "nl" -> "nl-NL"
            "pl" -> "pl-PL"
            "uk" -> "uk-UA"
            "cs" -> "cs-CZ"
            "sv" -> "sv-SE"
            "el" -> "el-GR"
            "he" -> "he-IL"
            "ro" -> "ro-RO"
            "hu" -> "hu-HU"
            else -> c
        }
    }

    /**
     * Azure target codes use the short form (e.g. "ru", "en", "zh-Hans").
     * Explicit allowlist mapped from the dialog's 24-language list to Azure's supported
     * translation targets. See:
     * https://learn.microsoft.com/azure/cognitive-services/speech-service/language-support
     * Unknown codes log a warning and fall back to "en".
     */
    private fun toAzureTargetCode(code: String): String {
        val c = code.trim().lowercase()
        return when (c) {
            "en", "en-us", "en-gb" -> "en"
            "ru", "ru-ru" -> "ru"
            "zh", "zh-cn", "zh-hans" -> "zh-Hans"
            "zh-tw", "zh-hant" -> "zh-Hant"
            "ja", "ja-jp" -> "ja"
            "ko", "ko-kr" -> "ko"
            "fr", "fr-fr" -> "fr"
            "de", "de-de" -> "de"
            "es", "es-es", "es-mx" -> "es"
            "it", "it-it" -> "it"
            "pt", "pt-br" -> "pt"
            "pt-pt" -> "pt-pt"
            "ar", "ar-sa" -> "ar"
            "hi", "hi-in" -> "hi"
            "tr", "tr-tr" -> "tr"
            "vi", "vi-vn" -> "vi"
            "th", "th-th" -> "th"
            "id", "id-id" -> "id"
            "nl", "nl-nl" -> "nl"
            "pl", "pl-pl" -> "pl"
            "sv", "sv-se" -> "sv"
            "fi", "fi-fi" -> "fi"
            "da", "da-dk" -> "da"
            "no", "nb", "nb-no" -> "nb"
            "cs", "cs-cz" -> "cs"
            "el", "el-gr" -> "el"
            "he", "he-il" -> "he"
            "hu", "hu-hu" -> "hu"
            "ro", "ro-ro" -> "ro"
            "sk", "sk-sk" -> "sk"
            "uk", "uk-ua" -> "uk"
            else -> {
                LogCollector.w(TAG, "Unknown Azure translation target code: '$code', falling back to 'en'")
                "en"
            }
        }
    }
    private val transcribeStreamListener = object : OrchestratorClient.TranscribeStreamListener {
        override fun onTranscription(segId: Int, text: String, translation: String, partial: Boolean) {
            phoneBtHost.sendTranslationResultToApp(
                segId,
                text = text,
                translation = translation.ifEmpty { null },
                partial = partial
            )
            LogCollector.i(TAG, "Stream transcription (segId=$segId, partial=$partial): ${text.take(40)} | ${translation.take(40)}")
        }

        override fun onFinal(segId: Int, text: String, translation: String) {
            phoneBtHost.sendTranslationResultToApp(segId, text = text, translation = translation, partial = false)
            LogCollector.i(TAG, "Stream final (segId=$segId): ${text.take(40)} -> ${translation.take(40)}")
        }

        override fun onStreamError(message: String) {
            LogCollector.e(TAG, "Transcribe stream error: $message, reconnecting...")
            orchestratorClient.disconnectTranscribeStream()
            if (translationMode) {
                reconnectTranscribeStream()
            }
        }
    }

    // System audio capture (phone's own audio output)
    private var systemAudioCapturer: SystemAudioCapturer? = null

    // Phone call state -- pause mic during calls to prevent echo
    @Volatile private var isInPhoneCall = false
    private var telephonyManager: TelephonyManager? = null
    private var callStateCallback: Any? = null

    private fun resetAzureVadGate() {
        azureLookbackBytes.clear()
        azureLookbackTotal = 0
        azureSpeechActive = false
        azureLastSpeechMs = 0L
        azureGatedBytesPushed = 0
        azureGatedBytesDropped = 0
    }

    // --- Glasses-initiated commands (via CH_GLASSES_COMMAND BT channel) ---

    private fun handleGlassesCommand(type: String, params: JSONObject) {
        when (type) {
            "request_start_translation" -> handleGlassesTranslationStart(params)
            "request_stop_translation" -> handleGlassesTranslationStop()
            "request_start_assistant" -> handleGlassesCopilotStart(params)
            "request_stop_assistant" -> handleGlassesCopilotStop()
            else -> LogCollector.w(TAG, "Unknown glasses command: $type")
        }
    }

    private fun handleGlassesCopilotStart(params: JSONObject) {
        if (copilotMode) {
            LogCollector.i(TAG, "Glasses requested copilot start but already running, ignoring")
            return
        }
        if (!phoneBtHost.isConnected) {
            LogCollector.w(TAG, "Glasses requested copilot start but BT not connected")
            return
        }
        // Honor the params sent by the glasses -- these are the glasses-side
        // last-used copilot cache (raw BCP-47 codes / source / model), so we
        // pass them straight through. Fall back per field to a sane default; the
        // model falls back to the phone AppConfig default. We deliberately do NOT
        // write these codes into phone AppConfig: its copilot-language prefs
        // store dialog labels like "English (en-US)", and writing raw codes would
        // corrupt label matching in the picker.
        val wearerLang = params.optString("wearer_lang", "").ifBlank { "en-US" }
        val interlocutorLang = params.optString("interlocutor_lang", "").ifBlank { "en-US" }
        val source = params.optString("interlocutor_source", "").ifBlank { "glasses" }
        val model = params.optString("model", "").ifBlank { AppConfig.getAssistantModel(this) }
        val commandId = "glasses_copilot_${System.currentTimeMillis()}"
        val adbParams = JSONObject().apply {
            put("wearer_lang", wearerLang)
            put("interlocutor_lang", interlocutorLang)
            put("interlocutor_source", source)
            put("model", model)
        }
        handleAdbCommand(commandId, "start_assistant", adbParams.toString())
        LogCollector.i(TAG, "Glasses-initiated copilot start dispatched: wearer=$wearerLang interlocutor=$interlocutorLang source=$source model=$model")
    }

    private fun handleGlassesCopilotStop() {
        if (!copilotMode) {
            LogCollector.i(TAG, "Glasses requested copilot stop but not running, ignoring")
            return
        }
        val commandId = "glasses_copilot_stop_${System.currentTimeMillis()}"
        handleAdbCommand(commandId, "stop_assistant", "{}")
        LogCollector.i(TAG, "Glasses-initiated copilot stop dispatched")
    }

    private fun handleGlassesTranslationStart(params: JSONObject) {
        if (translationMode) {
            LogCollector.i(TAG, "Glasses requested translation start but already translating, ignoring")
            return
        }
        if (!phoneBtHost.isConnected) {
            LogCollector.w(TAG, "Glasses requested translation start but BT not connected")
            return
        }
        val fromLang = params.optString("from_language", AppConfig.getTranslationFromLanguage(this))
        val toLang = params.optString("to_language", AppConfig.getTranslationToLanguage(this))
        if (fromLang.isEmpty() || toLang.isEmpty()) {
            LogCollector.w(TAG, "Glasses requested translation start but no language config available")
            return
        }
        val fontSize = params.optInt("font_size", AppConfig.getTranslationFontSize(this))
        val audioSource = params.optString("audio_source", AppConfig.getTranslationAudioSource(this))
        // Phone is the source of truth for provider -- glasses may have stale cached value
        val requestedProvider = AppConfig.getTranslationProvider(this)
        val twoWay = params.optBoolean("two_way", AppConfig.getTranslationTwoWay(this))

        // Persist config (except provider which stays as phone's setting)
        AppConfig.setTranslationFromLanguage(this, fromLang)
        AppConfig.setTranslationToLanguage(this, toLang)
        AppConfig.setTranslationFontSize(this, fontSize)
        AppConfig.setTranslationAudioSource(this, audioSource)
        AppConfig.setTranslationTwoWay(this, twoWay)

        // Reuse the ADB dispatch path
        val commandId = "glasses_trans_${System.currentTimeMillis()}"
        val adbParams = JSONObject().apply {
            put("from_language", fromLang)
            put("to_language", toLang)
            put("from_nllb", LanguageUtils.resolveNllb(params.optString("from_nllb", ""), fromLang))
            put("to_nllb", LanguageUtils.resolveNllb(params.optString("to_nllb", ""), toLang))
            put("font_size", fontSize)
            put("audio_source", audioSource)
            put("provider", requestedProvider)
            put("two_way", twoWay)
        }
        handleAdbCommand(commandId, "start_translation", adbParams.toString())
        LogCollector.i(TAG, "Glasses-initiated translation start dispatched: $fromLang -> $toLang")
    }

    private fun handleGlassesTranslationStop() {
        if (!translationMode) {
            LogCollector.i(TAG, "Glasses requested translation stop but not translating, ignoring")
            return
        }
        val commandId = "glasses_trans_stop_${System.currentTimeMillis()}"
        handleAdbCommand(commandId, "stop_translation", "{}")
        LogCollector.i(TAG, "Glasses-initiated translation stop dispatched")
    }

    private fun startAzureTranslationSession(): Boolean {
        try {
            stopAzureTranslationSession()
            resetAzureVadGate()
            val key = AppConfig.getAzureSpeechKey(this)
            val region = AppConfig.getAzureSpeechRegion(this)
            if (key.isBlank() || region.isBlank()) {
                throw IllegalStateException("Azure key/region not configured")
            }
            val fromBcp = toAzureBcp47(translationFromLang)
            val toBcp = toAzureBcp47(translationToLang)
            val fromTgt = toAzureTargetCode(translationFromLang)
            val toTgt = toAzureTargetCode(translationToLang)
            val session = AzureTranslationSession(key, region)
            val segIdRef = translationSegmentId

            if (translationTwoWay) {
                // Two-way: TWO parallel one-way sessions, both fed by glasses mics.
                //   - FRONT mic (CH_AUDIO_DATA) captures the OTHER PERSON speaking
                //     the SOURCE language -> translate source->target -> show on
                //     GLASSES UI (wearer reads the translation).
                //   - INWARD mic (CH_AUDIO_DATA_INWARD) captures the WEARER speaking
                //     the TARGET language -> translate target->source -> show on
                //     PHONE screen (other person reads the translation).
                // `session` is the front-mic recognizer (results -> glasses).
                // `phoneSession` is the inward-mic recognizer (results -> phone).
                // Phone-display side uses its own segment counter so partial
                // updates can find/replace their predecessor and finals can
                // start fresh segments.
                session.start(
                    fromLang = fromBcp,
                    toLang = toTgt,
                    onPartial = { src, tgt ->
                        serviceScope.launch {
                            if (azureTranslationSession !== session) return@launch
                            val segId = segIdRef.get()
                            phoneBtHost.sendTranslationResultToApp(segId, text = src, translation = tgt.ifEmpty { null }, partial = true)
                            LogCollector.i(TAG, "2way[front-mic] partial (segId=$segId): ${src.take(40)} | ${tgt.take(40)}")
                        }
                    },
                    onFinal = { src, tgt ->
                        serviceScope.launch {
                            if (azureTranslationSession !== session) return@launch
                            val segId = segIdRef.getAndIncrement()
                            phoneBtHost.sendTranslationResultToApp(segId, text = src, translation = tgt, partial = false)
                            LogCollector.i(TAG, "2way[front-mic] FINAL (segId=$segId): ${src.take(40)} -> ${tgt.take(40)}")
                        }
                    }
                )
                azureTranslationSession = session

                // Inward-mic recognizer: target -> source, results to phone screen.
                val phoneSegIdRef = java.util.concurrent.atomic.AtomicInteger(1)
                val phoneSession = AzureTranslationSession(key, region)
                phoneSession.start(
                    fromLang = toBcp,
                    toLang = fromTgt,
                    onPartial = { src, tgt ->
                        serviceScope.launch {
                            if (azurePhoneMicSession !== phoneSession) return@launch
                            val segId = phoneSegIdRef.get()
                            broadcastTwoWayPhoneResult(segId, tgt.ifEmpty { src }, partial = true)
                            LogCollector.i(TAG, "2way[inward-mic] partial (seg=$segId): ${src.take(40)} | ${tgt.take(40)}")
                        }
                    },
                    onFinal = { src, tgt ->
                        serviceScope.launch {
                            if (azurePhoneMicSession !== phoneSession) return@launch
                            val segId = phoneSegIdRef.getAndIncrement()
                            broadcastTwoWayPhoneResult(segId, tgt.ifEmpty { src }, partial = false)
                            LogCollector.i(TAG, "2way[inward-mic] FINAL (seg=$segId): ${src.take(40)} -> ${tgt.take(40)}")
                        }
                    }
                )
                azurePhoneMicSession = phoneSession
                LogCollector.i(TAG, "Azure TWO-WAY dual-mic sessions started: front=$fromBcp->$toTgt (glasses UI), inward=$toBcp->$fromTgt (phone screen)")
            } else {
                session.start(
                    fromLang = fromBcp,
                    toLang = toTgt,
                    onPartial = { src, tgt ->
                        serviceScope.launch {
                            if (azureTranslationSession !== session) return@launch
                            val segId = segIdRef.get()
                            phoneBtHost.sendTranslationResultToApp(segId, text = src, translation = tgt.ifEmpty { null }, partial = true)
                            LogCollector.i(TAG, "Azure partial (segId=$segId): ${src.take(40)} | ${tgt.take(40)}")
                        }
                    },
                    onFinal = { src, tgt ->
                        serviceScope.launch {
                            if (azureTranslationSession !== session) return@launch
                            val segId = segIdRef.getAndIncrement()
                            phoneBtHost.sendTranslationResultToApp(segId, text = src, translation = tgt, partial = false)
                            LogCollector.i(TAG, "Azure final (segId=$segId): ${src.take(40)} -> ${tgt.take(40)}")
                        }
                    }
                )
                azureTranslationSession = session
                LogCollector.i(TAG, "Azure translation session started: $fromBcp -> $toTgt")
            }
            return true
        } catch (t: Throwable) {
            LogCollector.e(TAG, "Azure init failed, falling back to default: ${t.message}")
            try { stopAzureTranslationSession() } catch (_: Throwable) {}
            // Notify the glasses/phone UI so it doesn't sit waiting on a dead pipe.
            try {
                phoneBtHost.sendTranslationResultToApp(
                    0,
                    text = "[azure init failed: ${t.message ?: "unknown"}]",
                    translation = null,
                    partial = false
                )
            } catch (_: Throwable) {}
            // Bring the default transcribe stream up so translation still works.
            try {
                orchestratorClient.setTranscribeStreamListener(transcribeStreamListener)
                orchestratorClient.connectTranscribeStream(
                    translationFromLang, translationToLang,
                    translationFromNllb, translationToNllb,
                    translate = true
                )
            } catch (t2: Throwable) {
                LogCollector.e(TAG, "Default transcribe fallback also failed: ${t2.message}")
            }
            return false
        }
    }

    private fun broadcastTranslationState(active: Boolean) {
        sendBroadcast(Intent(ACTION_TRANSLATION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_TRANSLATION_ACTIVE, active)
        })
    }

    /**
     * Push the authoritative desired translation state to the glasses so they reconcile
     * their front-mic recorder. The phone owns translationMode; this is the self-healing
     * net for a dropped edge-triggered start_translation/stop_translation command. Called
     * on every translationMode change, on glasses (re)connect, and periodically by the mic
     * watchdog while translation is active (no idle chatter -- an active session already
     * keeps the RFCOMM link hot). No-op if the glasses are not connected.
     */
    private fun pushTranslationState() {
        if (!phoneBtHost.isConnected) return
        phoneBtHost.sendTranslationState(
            active = translationMode,
            fromLang = translationFromLang,
            toLang = translationToLang,
            fromNllb = translationFromNllb,
            toNllb = translationToNllb,
            fontSize = translationFontSize,
            twoWay = translationTwoWay,
            // Tells the glasses whether the phone wants the far-party call downlink: only
            // when translation is active with the "system" audio source. The glasses gate
            // their c6 call-audio tap (and front-beam suspension) on this, so a "glasses"
            // source translation keeps its front beam during a call.
            wantsCallAudio = translationMode && translationAudioSource == "system"
        )
    }

    private fun stopAzureTranslationSession() {
        azureTranslationSession?.let {
            try { it.stop() } catch (t: Throwable) { LogCollector.w(TAG, "Azure stop error: ${t.message}") }
        }
        azureTranslationSession = null
        azurePhoneMicSession?.let {
            try { it.stop() } catch (t: Throwable) { LogCollector.w(TAG, "Azure phone-mic stop error: ${t.message}") }
        }
        azurePhoneMicSession = null
        releaseCallOpusDecoder()
        resetAzureVadGate()
    }

    // --- Copilot (real-time conversational fact-check) ---

    /**
     * Start the Copilot: two recognition-only Azure sessions (wearer + interlocutor),
     * the glasses dual-mic capture, optional system-audio capture, and the batch timer.
     * Returns false if Azure is unconfigured or a session fails to start.
     */
    /**
     * Starts the copilot. MUST run off the main thread: the two Azure
     * recognizers each block up to ~10s on startContinuousRecognitionAsync().get().
     * Serialized via copilotLock so a UI-initiated and glasses-initiated start
     * can't interleave; generation-guarded so a concurrent stop/restart cleanly
     * invalidates this attempt.
     */
    private suspend fun startCopilot(
        commandId: String,
        wearerLangBcp47: String,
        interlocutorLangBcp47: String,
        params: JSONObject
    ): Boolean {
        // Tear down any prior session first (also blocking -> stays off main).
        stopCopilot(commandId, silent = true)

        val key = AppConfig.getAzureSpeechKey(this)
        val region = AppConfig.getAzureSpeechRegion(this)
        if (key.isBlank() || region.isBlank()) {
            LogCollector.e(TAG, "[COPILOT] Azure key/region not configured")
            broadcastCopilotState(false, "Azure not configured")
            return false
        }

        val gen: Int
        synchronized(copilotLock) {
            gen = ++copilotGeneration
            copilotInterlocutorSource = params.optString("interlocutor_source", "glasses")
            copilotModel = params.optString("model", "").ifBlank { AppConfig.getAssistantModel(this) }
            synchronized(copilotBufferLock) {
                copilotWearerBuffer.setLength(0)
                copilotInterlocutorBuffer.setLength(0)
            }
        }
        broadcastCopilotState(false, "Connecting...")
        orchestratorClient.sendAssistantNew()

        try {
            // Start both recognizers in parallel -- each blocks up to ~10s, so
            // sequential would be ~20s. awaitAll halves the worst case.
            val wearerDeferred = serviceScope.async(Dispatchers.IO) {
                AzureTranslationSession(key, region).also {
                    it.startRecognition(
                        recognitionLang = wearerLangBcp47,
                        onPartial = { },
                        onFinal = { text -> appendCopilotSpeech(gen, wearer = true, text = text) }
                    )
                }
            }
            val interlocutorDeferred = serviceScope.async(Dispatchers.IO) {
                AzureTranslationSession(key, region).also {
                    it.startRecognition(
                        recognitionLang = interlocutorLangBcp47,
                        onPartial = { },
                        onFinal = { text -> appendCopilotSpeech(gen, wearer = false, text = text) }
                    )
                }
            }
            val (wearerSession, interlocutorSession) = awaitAll(wearerDeferred, interlocutorDeferred)

            // A stop/restart happened while we were initializing -- discard.
            synchronized(copilotLock) {
                if (gen != copilotGeneration) {
                    LogCollector.w(TAG, "[COPILOT] start gen=$gen superseded by ${copilotGeneration}, discarding")
                    try { wearerSession.stop() } catch (_: Throwable) {}
                    try { interlocutorSession.stop() } catch (_: Throwable) {}
                    copilotInFlight = false
                    copilotInFlightRequestId = null
                    copilotInFlightSince = 0L
                    return false
                }
                copilotWearerSession = wearerSession
                copilotInterlocutorSession = interlocutorSession
                copilotMode = true
            }

            // Tell glasses to begin dual-mic capture (front beam + inward mic).
            phoneBtHost.sendCommand("start_assistant", commandId, params.toString())
            if (copilotInterlocutorSource == "system") startSystemAudioCapture()
            startCopilotBatchTimer()
            broadcastCopilotState(true, null)
            LogCollector.i(TAG, "[COPILOT] started gen=$gen: wearer=$wearerLangBcp47 interlocutor=$interlocutorLangBcp47 source=$copilotInterlocutorSource model=$copilotModel")
            return true
        } catch (t: Throwable) {
            LogCollector.e(TAG, "[COPILOT] start failed: ${t.message}")
            stopCopilot(commandId, silent = true)
            broadcastCopilotState(false, "Start failed")
            return false
        }
    }

    /** Append a finalized Azure segment to the right buffer, ignoring late
     *  callbacks from a superseded session generation. */
    private fun appendCopilotSpeech(gen: Int, wearer: Boolean, text: String) {
        if (gen != copilotGeneration || !copilotMode) return
        if (text.isBlank()) return
        synchronized(copilotBufferLock) {
            if (wearer) copilotWearerBuffer.append(text.trim()).append(' ')
            else copilotInterlocutorBuffer.append(text.trim()).append(' ')
        }
        val now = System.currentTimeMillis()
        // Liveness signal updated by both speakers (fallback only).
        copilotLastContentAtMs = now
        // The interlocutor finishing a thought is what arms the send: mark its
        // quiet-start clock and flag new interlocutor content for the trigger.
        // Wearer speech rides along as context but does NOT arm the trigger.
        if (!wearer) {
            copilotInterlocutorLastAtMs = now
            copilotInterlocutorNew = true
        }
    }

    /** Stops the copilot. Blocking (Azure .get) -> caller must be off main. */
    private fun stopCopilot(commandId: String, silent: Boolean = false) {
        val wearerSession: AzureTranslationSession?
        val interlocutorSession: AzureTranslationSession?
        val wasSystem: Boolean
        // Snapshot any outstanding pending (spinner) id so it can be cancelled on
        // the glasses AFTER the lock is released (never hold the lock across a BT
        // send). Cleared inside the lock so a late result can't double-handle it.
        val orphanPendingId: String?
        synchronized(copilotLock) {
            copilotGeneration++  // invalidate any in-flight start/flush/result
            copilotMode = false
            wearerSession = copilotWearerSession
            interlocutorSession = copilotInterlocutorSession
            copilotWearerSession = null
            copilotInterlocutorSession = null
            wasSystem = copilotInterlocutorSource == "system"
            copilotInterlocutorSource = "glasses"
            synchronized(copilotBufferLock) {
                copilotWearerBuffer.setLength(0)
                copilotInterlocutorBuffer.setLength(0)
            }
            copilotInFlight = false
            copilotInFlightRequestId = null
            copilotInFlightSince = 0L
            copilotInterlocutorLastAtMs = 0L
            copilotInterlocutorNew = false
            orphanPendingId = copilotPendingId
            copilotPendingId = null
        }
        // Best-effort: clear any orphan spinner left on the HUD.
        if (orphanPendingId != null && phoneBtHost.isConnected) {
            phoneBtHost.sendCommand(
                "assistant_pending_cancel",
                java.util.UUID.randomUUID().toString().take(8),
                JSONObject().put("id", orphanPendingId).toString()
            )
        }
        // Cancel all pending card-expiry runnables + clear the cache. Done before
        // stopCopilotBatchTimer() nulls copilotBatchHandler so removeCallbacks runs.
        clearCopilotCards()
        stopCopilotBatchTimer()
        try { wearerSession?.stop() } catch (t: Throwable) { LogCollector.w(TAG, "[COPILOT] wearer stop error: ${t.message}") }
        try { interlocutorSession?.stop() } catch (t: Throwable) { LogCollector.w(TAG, "[COPILOT] interlocutor stop error: ${t.message}") }
        if (wasSystem) stopSystemAudioCapture()
        if (!silent && phoneBtHost.isConnected) {
            phoneBtHost.sendCommand("stop_assistant", commandId, "{}")
        }
        if (!silent) {
            broadcastCopilotState(false, null)
            LogCollector.i(TAG, "[COPILOT] stopped")
        }
    }

    private fun broadcastCopilotState(active: Boolean, status: String?) {
        sendBroadcast(Intent(ACTION_COPILOT_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_COPILOT_ACTIVE, active)
            if (status != null) putExtra(EXTRA_COPILOT_STATUS, status)
        })
    }

    private fun startCopilotBatchTimer() {
        stopCopilotBatchTimer()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        copilotBatchHandler = handler
        copilotLastBatchAtMs = System.currentTimeMillis()
        copilotLastContentAtMs = 0L
        copilotInterlocutorLastAtMs = 0L
        copilotInterlocutorNew = false
        val runnable = object : Runnable {
            override fun run() {
                if (!copilotMode) return
                val now = System.currentTimeMillis()
                // Safety: if the in-flight batch never got a response within the
                // timeout, treat it as lost so the accumulated buffer can be sent.
                // Cancel its orphan spinner on the HUD as well (off-main BT send).
                if (copilotInFlight && now - copilotInFlightSince > COPILOT_INFLIGHT_TIMEOUT_MS) {
                    // BUG C fix: claim this in-flight batch atomically under
                    // copilotLock. The result path makes the identical claim, so
                    // exactly ONE of {timeout, result} wins and sends the single
                    // terminal (cancel here, resolve/cancel there). Re-check
                    // copilotInFlight + the deadline INSIDE the lock so a result
                    // that cleared the slot a moment ago makes this a no-op.
                    val staleId: String? = synchronized(copilotLock) {
                        if (copilotInFlight && now - copilotInFlightSince > COPILOT_INFLIGHT_TIMEOUT_MS) {
                            copilotInFlight = false
                            copilotInFlightRequestId = null
                            val id = copilotPendingId
                            copilotPendingId = null
                            id
                        } else null
                    }
                    if (staleId != null) {
                        LogCollector.w(TAG, "[COPILOT] in-flight batch timed out, allowing next send")
                        // The timer runs on the main looper -- bounce the orphan-spinner
                        // cancel onto IO so a BT send never blocks the UI thread.
                        serviceScope.launch(Dispatchers.IO) { cancelCopilotPending(staleId) }
                    }
                }
                val sinceBatch = now - copilotLastBatchAtMs
                val hasContent: Boolean
                synchronized(copilotBufferLock) {
                    hasContent = copilotWearerBuffer.isNotBlank() || copilotInterlocutorBuffer.isNotBlank()
                }
                // Interlocutor-silence trigger: the card should compute the moment
                // the INTERLOCUTOR finishes (their objection/question), so it lands
                // while the wearer stalls. Fire when there is NEW interlocutor
                // content since the last batch AND the interlocutor has been quiet
                // ~800ms AND at least MIN elapsed. Wearer-only chatter never sets
                // copilotInterlocutorNew, so it does not trigger -- but it stays in
                // the buffer and rides along the next interlocutor-armed flush.
                //
                // MAX-cap fallback: if MAX elapsed and ANYTHING is buffered, flush
                // regardless of who spoke, so a long wearer monologue with no
                // interlocutor still gets its context in. Gate on !copilotInFlight
                // so at most one batch awaits an AI response.
                val interlocutorQuiet = copilotInterlocutorLastAtMs in 1..(now - 800)
                val interlocutorTrigger = copilotInterlocutorNew && interlocutorQuiet &&
                    sinceBatch >= COPILOT_BATCH_MIN_MS
                val maxCapTrigger = hasContent && sinceBatch >= COPILOT_BATCH_MAX_MS
                val shouldFire = hasContent && !copilotInFlight &&
                    (interlocutorTrigger || maxCapTrigger)
                if (shouldFire) {
                    flushCopilotBatch()
                    copilotLastBatchAtMs = now
                }
                // NOTE: when a tick is blocked solely by copilotInFlight, we
                // deliberately leave copilotLastBatchAtMs untouched so sinceBatch
                // keeps growing and the next unblocked tick flushes immediately.
                copilotBatchHandler?.postDelayed(this, COPILOT_BATCH_TICK_MS)
            }
        }
        copilotBatchRunnable = runnable
        handler.postDelayed(runnable, COPILOT_BATCH_TICK_MS)
    }

    private fun stopCopilotBatchTimer() {
        copilotBatchRunnable?.let { copilotBatchHandler?.removeCallbacks(it) }
        copilotBatchRunnable = null
        copilotBatchHandler = null
    }

    private fun flushCopilotBatch() {
        val wearerText: String
        val interlocutorText: String
        synchronized(copilotBufferLock) {
            wearerText = copilotWearerBuffer.toString().trim()
            interlocutorText = copilotInterlocutorBuffer.toString().trim()
            copilotWearerBuffer.setLength(0)
            copilotInterlocutorBuffer.setLength(0)
        }
        // Consume the interlocutor-new flag for this flush regardless of outcome:
        // whatever interlocutor content existed is now drained from the buffer.
        copilotInterlocutorNew = false
        if (wearerText.isEmpty() && interlocutorText.isEmpty()) return
        // Copilot batches are ephemeral: if the WS is down, drop this batch
        // rather than queueing it -- a stale batch replayed minutes later would
        // produce cards for a conversation that already moved on.
        if (!orchestratorClient.isConnected) {
            LogCollector.w(TAG, "[COPILOT] batch dropped (orchestrator WS down)")
            return
        }
        val gen = copilotGeneration
        // The phone caches id->note only; heard is not retained, so send "".
        val activeCards = synchronized(copilotCardLock) {
            copilotActiveCards.entries.map { Triple(it.key, "", it.value) }
        }
        val model = copilotModel.ifBlank { AppConfig.getAssistantModel(this) }
        // Mark in-flight SYNCHRONOUSLY on the main thread before launching the IO
        // send, so a subsequent 1s tick sees the flag and back-pressures. The
        // requestId is generated here (not inside sendAssistantBatch) and recorded
        // synchronously, so a result can never arrive before the marker is set. The
        // buffer snapshot above already happened, so accumulation continues correctly.
        val requestId = java.util.UUID.randomUUID().toString()
        // Pending (spinner) id tied to this batch's requestId. Drawn on the HUD
        // only AFTER the ws send is committed, then resolved/cancelled when the
        // result returns (or cancelled on any other exit path).
        val pendingId = "pending_" + java.util.UUID.randomUUID().toString().take(8)
        // Claim the single in-flight slot atomically under copilotLock so the
        // result path and the in-flight-timeout path serialize against this set.
        // @Volatile gives visibility, NOT atomicity -- only the lock makes the
        // "no batch in flight -> mark this batch in flight" a single step.
        synchronized(copilotLock) {
            copilotInFlightRequestId = requestId
            copilotPendingId = pendingId
            copilotInFlight = true
            copilotInFlightSince = System.currentTimeMillis()
        }
        // ws.send synchronizes on the send queue -- keep it off the main looper
        // (this runs from the main-looper batch timer). The pending-card BT send
        // also runs here, off-main.
        serviceScope.launch(Dispatchers.IO) {
            // Bail before sending if a stop/restart superseded this batch. Read the
            // claim/clear atomically under copilotLock so it can't interleave with
            // the result/timeout/stop transitions that also take the lock.
            val abort = synchronized(copilotLock) {
                if (gen != copilotGeneration || !copilotMode) {
                    // Only undo our own claim; a newer batch may have replaced it.
                    if (copilotInFlightRequestId == requestId) {
                        copilotInFlight = false
                        copilotInFlightRequestId = null
                    }
                    if (copilotPendingId == pendingId) copilotPendingId = null
                    true
                } else false
            }
            if (abort) return@launch
            orchestratorClient.sendAssistantBatch(requestId, wearerText, interlocutorText, activeCards, model)
            LogCollector.i(TAG, "[COPILOT] batch sent (req=$requestId, model=$model) wearer='${wearerText.take(40)}' interlocutor='${interlocutorText.take(40)}' activeCards=${activeCards.size}")
            // Draw the pending placeholder so the wearer prepares for an answer.
            // BUG A fix: read the draw-guard UNDER copilotLock immediately before
            // sending. Only draw if this batch's pendingId is still the live one
            // (not yet resolved/cancelled by a result, nor torn down by stop). The
            // result/stop/timeout paths all clear copilotPendingId under the same
            // lock and always emit their terminal (resolve/cancel) for that id, so
            // the draw is suppressed once a terminal has fired -- no draw-after-
            // terminal orphan. Capture the decision under lock, send OUTSIDE it.
            val drawPending = synchronized(copilotLock) {
                gen == copilotGeneration && copilotMode && copilotPendingId == pendingId
            }
            if (drawPending && phoneBtHost.isConnected) {
                phoneBtHost.sendCommand(
                    "assistant_pending",
                    java.util.UUID.randomUUID().toString().take(8),
                    JSONObject().put("id", pendingId).toString()
                )
            }
        }
    }

    /**
     * Handle a fact-check result from the orchestrator: draw new cards on the
     * glasses HUD and dismiss the ones the wearer already used. Card ids are
     * assigned server-side. The local active-card map is only mutated when the
     * corresponding BT send succeeds, so it never diverges from the HUD.
     */
    private fun onCopilotResultReceived(requestId: String, cards: org.json.JSONArray, dismiss: org.json.JSONArray) {
        // BUG C fix: claim the in-flight batch atomically under copilotLock. The
        // match-test + clear must be one step so the in-flight-timeout path (which
        // makes the identical claim) cannot also fire -- exactly ONE winner owns
        // the spinner and performs its single terminal. @Volatile gives visibility
        // but not atomicity; only the lock serializes check-then-clear.
        val pendingId: String? = synchronized(copilotLock) {
            if (requestId == copilotInFlightRequestId) {
                copilotInFlight = false
                copilotInFlightRequestId = null
                val id = copilotPendingId
                copilotPendingId = null
                id
            } else {
                // A late/non-matching result (already claimed by timeout, or for a
                // superseded batch): it owns no live spinner.
                null
            }
        }
        if (!copilotMode) return
        if (!phoneBtHost.isConnected) {
            // BUG B fix: do NOT silently discard the spinner. We already cleared
            // copilotPendingId above (atomic claim), so stopCopilot's orphan
            // cleanup would see null and never cancel it -> permanent spinner once
            // BT returns. Best-effort cancel now (no-ops while BT is down) so the
            // terminal is paired with the set; the glasses-side 5-min TTL is the
            // ultimate backstop if this no-op cancel never reaches the HUD.
            if (pendingId != null) cancelCopilotPending(pendingId)
            LogCollector.w(TAG, "[COPILOT] result dropped (glasses BT down); spinner $pendingId cancel attempted, glasses TTL is backstop")
            return
        }
        // Server-driven dismissals.
        for (i in 0 until dismiss.length()) {
            val id = dismiss.optString(i, "")
            if (id.isNotEmpty()) removeCopilotCard(id, tellGlasses = true)
        }
        // Pending placeholder round-trip: the FIRST card morphs the spinner in
        // place; empty result cancels the spinner. Additional cards take the
        // normal show-card path. "why" is relayed on every card payload.
        var firstHandled = false
        for (i in 0 until cards.length()) {
            val card = cards.optJSONObject(i) ?: continue
            val id = card.optString("id", "")
            val kind = card.optString("kind", "note")
            val heard = card.optString("heard", "")
            val note = card.optString("note", "")
            val why = card.optString("why", "")
            if (id.isEmpty() || note.isBlank()) continue
            if (!firstHandled && pendingId != null) {
                // Morph the spinner into this card without a duplicate show-card.
                val resolve = JSONObject()
                    .put("id", pendingId)
                    .put("real_id", id)
                    .put("kind", kind)
                    .put("heard", heard)
                    .put("note", note)
                    .put("why", why)
                phoneBtHost.sendCommand("assistant_resolve", java.util.UUID.randomUUID().toString().take(8), resolve.toString())
                addCopilotCard(id, note)
                firstHandled = true
            } else {
                val payload = JSONObject()
                    .put("id", id).put("kind", kind).put("heard", heard).put("note", note).put("why", why)
                phoneBtHost.sendCommand("assistant_show_card", java.util.UUID.randomUUID().toString().take(8), payload.toString())
                addCopilotCard(id, note)
            }
        }
        // No card morphed the spinner (empty result, or every card was invalid):
        // cancel it so the placeholder disappears.
        if (!firstHandled && pendingId != null) {
            cancelCopilotPending(pendingId)
        }
        val active = synchronized(copilotCardLock) { copilotActiveCards.size }
        LogCollector.i(TAG, "[COPILOT] result: +${cards.length()} cards, -${dismiss.length()} dismissed, active=$active")
    }

    /** Cancel a pending (spinner) placeholder on the glasses HUD. Best-effort:
     *  no-op when BT is disconnected. Must be called off the main thread. */
    private fun cancelCopilotPending(pendingId: String) {
        if (!phoneBtHost.isConnected) return
        phoneBtHost.sendCommand(
            "assistant_pending_cancel",
            java.util.UUID.randomUUID().toString().take(8),
            JSONObject().put("id", pendingId).toString()
        )
    }

    /**
     * Add a card to the active cache (insertion-ordered), schedule its 5-minute
     * expiry, then FIFO-evict the oldest entries while over the cap. Re-adding an
     * existing id refreshes its TTL and moves it to newest. All cache + timeout
     * mutations are under copilotCardLock.
     */
    private fun addCopilotCard(id: String, note: String) {
        synchronized(copilotCardLock) {
            // Refresh: drop any prior position + pending timeout for this id.
            copilotActiveCards.remove(id)
            copilotCardTimeouts.remove(id)?.let { copilotBatchHandler?.removeCallbacks(it) }
            copilotActiveCards[id] = note

            val expiry = Runnable {
                // On fire: if still present, dismiss on glasses + remove from cache.
                if (copilotMode && phoneBtHost.isConnected) {
                    synchronized(copilotCardLock) {
                        if (!copilotActiveCards.containsKey(id)) return@Runnable
                    }
                    val payload = JSONObject().put("id", id)
                    phoneBtHost.sendCommand("assistant_dismiss_card", java.util.UUID.randomUUID().toString().take(8), payload.toString())
                }
                removeCopilotCard(id, tellGlasses = false)
                LogCollector.i(TAG, "[COPILOT] card $id expired (TTL)")
            }
            copilotCardTimeouts[id] = expiry
            copilotBatchHandler?.postDelayed(expiry, COPILOT_CARD_TTL_MS)

            // FIFO eviction: oldest is the first key in insertion order.
            while (copilotActiveCards.size > COPILOT_MAX_CARDS) {
                val oldestId = copilotActiveCards.keys.firstOrNull() ?: break
                copilotActiveCards.remove(oldestId)
                copilotCardTimeouts.remove(oldestId)?.let { copilotBatchHandler?.removeCallbacks(it) }
                if (phoneBtHost.isConnected) {
                    val payload = JSONObject().put("id", oldestId)
                    phoneBtHost.sendCommand("assistant_dismiss_card", java.util.UUID.randomUUID().toString().take(8), payload.toString())
                }
                LogCollector.i(TAG, "[COPILOT] card $oldestId evicted (FIFO cap=$COPILOT_MAX_CARDS)")
            }
        }
    }

    /**
     * Remove a card from the cache and cancel its pending TTL runnable. When
     * tellGlasses is true, also command the glasses to dismiss it. No-op if the id
     * is not present (so duplicate dismiss paths are safe).
     */
    private fun removeCopilotCard(id: String, tellGlasses: Boolean) {
        val existed: Boolean
        synchronized(copilotCardLock) {
            existed = copilotActiveCards.remove(id) != null
            copilotCardTimeouts.remove(id)?.let { copilotBatchHandler?.removeCallbacks(it) }
        }
        if (existed && tellGlasses && phoneBtHost.isConnected) {
            val payload = JSONObject().put("id", id)
            phoneBtHost.sendCommand("assistant_dismiss_card", java.util.UUID.randomUUID().toString().take(8), payload.toString())
        }
    }

    /** Cancel every pending card-expiry runnable and clear the cache. */
    private fun clearCopilotCards() {
        synchronized(copilotCardLock) {
            for (r in copilotCardTimeouts.values) copilotBatchHandler?.removeCallbacks(r)
            copilotCardTimeouts.clear()
            copilotActiveCards.clear()
        }
    }

    /**
     * Starts a dedicated Azure Speech session for a notification voice reply.
     * Uses transcription-only continuous recognition ([AzureTranslationSession.startRecognition],
     * a plain SpeechRecognizer) -- the same clean path the Copilot/Assistant feature
     * uses. fromLang is the user's configured STT language; there is no translation
     * round-trip (notif-reply only needs the recognized source text).
     *
     * Returns true if the recognizer started, false if Azure is unconfigured.
     * Throws if the SDK fails to start (caller falls back to the transcriber path).
     */
    private fun startAzureNotifReplySession(): Boolean {
        stopAzureNotifReplySession()
        val key = AppConfig.getAzureSpeechKey(this)
        val region = AppConfig.getAzureSpeechRegion(this)
        if (key.isBlank() || region.isBlank()) {
            LogCollector.w(TAG, "[NREPLY] Azure key/region blank -- using transcriber fallback")
            return false
        }
        val sttLang = AppConfig.getSttLanguage(this)
        val fromBcp = toAzureBcp47(sttLang)
        notifReplyAccum.setLength(0)
        // Re-open the single-finalize gate for this session (mirrors what
        // armTelegramVoiceNoSpeechWatchdog does for the transcriber path). The
        // Azure path doesn't arm that watchdog, so reset the latch here so a
        // second reply can finalize exactly once.
        telegramVoiceFinalizing.set(false)
        val session = AzureTranslationSession(key, region)
        // Store BEFORE start so the identity guard in the callbacks resolves.
        azureNotifReplySession = session
        // Route onChunk PCM into the Azure session IMMEDIATELY, before start()
        // blocks on Azure SDK init (~1-2s). The session/client buffers PCM during
        // init and flushes it in FIFO order once the recognizer is live, so the
        // user's first words aren't dropped into the fallback batch buffer.
        notifReplyAzure = true
        try {
            session.startRecognition(
            recognitionLang = fromBcp,
            onPartial = { src ->
                mainHandler.post {
                    if (azureNotifReplySession !== session) return@post
                    // Recognition is progressing -- push the inactivity safety net out.
                    armNotifReplyInactivity(session)
                    // User is still talking -- cancel any pending end-of-reply timer.
                    cancelNotifReplyQuietTimer()
                    val live = (notifReplyAccum.toString() + src).trim()
                    phoneBtHost.sendGlassesPartialText(live)
                }
            },
            onFinal = { src ->
                mainHandler.post {
                    if (azureNotifReplySession !== session) return@post
                    // Recognition is progressing -- push the inactivity safety net out.
                    armNotifReplyInactivity(session)
                    val seg = src.trim()
                    if (seg.isNotEmpty()) {
                        if (notifReplyAccum.isNotEmpty()) notifReplyAccum.append(' ')
                        notifReplyAccum.append(seg)
                    }
                    phoneBtHost.sendGlassesPartialText(notifReplyAccum.toString().trim())
                    LogCollector.i(TAG, "[NREPLY] Azure final segment: '${seg.take(40)}' accumLen=${notifReplyAccum.length}")
                    // End-of-reply is derived: arm a quiet window after this segment.
                    armNotifReplyQuietTimer(session)
                }
            }
            )
        } catch (t: Throwable) {
            // start() failed late: undo the early routing flip and null the session
            // so onChunk routes to the transcriber fallback. Rethrow so the caller
            // engages its transcriber-stream path.
            notifReplyAzure = false
            if (azureNotifReplySession === session) azureNotifReplySession = null
            try { session.stop() } catch (_: Throwable) {}
            LogCollector.w(TAG, "[NREPLY] Azure notif-reply start failed: ${t.message}")
            throw t
        }
        LogCollector.i(TAG, "[NREPLY] Azure notif-reply session started (transcription-only): $fromBcp")
        // Rolling inactivity backstop: armed at session start so a silent hold (no
        // recognizing/recognized ever) still finalizes; re-armed on every Azure event
        // so a mid-reply PCM starvation (audio socket died) also finalizes. Either way
        // the reply can't hang.
        armNotifReplyInactivity(session)
        return true
    }

    // Quiet timer is identity-guarded: a preempt that arms a NEW session within the
    // window must not let a stale runnable finalize the new one.
    private fun armNotifReplyQuietTimer(session: AzureTranslationSession) {
        cancelNotifReplyQuietTimer()
        val r = Runnable {
            if (azureNotifReplySession !== session) return@Runnable
            finalizeAzureNotifReply()
        }
        notifReplyQuietRunnable = r
        mainHandler.postDelayed(r, NOTIF_REPLY_QUIET_MS)
    }

    private fun cancelNotifReplyQuietTimer() {
        notifReplyQuietRunnable?.let { mainHandler.removeCallbacks(it) }
        notifReplyQuietRunnable = null
    }

    // Rolling inactivity safety net: each call cancels the prior runnable and re-arms a
    // fresh NOTIF_REPLY_INACTIVITY_MS window. Called at session start and on EVERY Azure
    // event, so it only fires after the recognizer has gone quiet for the whole window
    // (silent hold, or PCM starvation from a dead audio socket). On fire it force-
    // finalizes with whatever notifReplyAccum holds -- a partial reply beats a hang;
    // empty accum -> blank (glasses treats blank as cancel). The single-finalize CAS in
    // finalizeAzureNotifReply() guarantees this and the quiet timer can't double-emit.
    private fun armNotifReplyInactivity(session: AzureTranslationSession) {
        cancelNotifReplyInactivity()
        val r = Runnable {
            if (azureNotifReplySession !== session) return@Runnable
            LogCollector.i(TAG, "[NREPLY] inactivity backstop fired (${NOTIF_REPLY_INACTIVITY_MS}ms no Azure events) -- force-finalizing accumLen=${notifReplyAccum.length}")
            finalizeAzureNotifReply()
        }
        notifReplyInactivityRunnable = r
        mainHandler.postDelayed(r, NOTIF_REPLY_INACTIVITY_MS)
    }

    private fun cancelNotifReplyInactivity() {
        notifReplyInactivityRunnable?.let { mainHandler.removeCallbacks(it) }
        notifReplyInactivityRunnable = null
    }

    /**
     * End-of-reply: emits ONE consolidated tg_voice final from the accumulated
     * Azure segments and tears the session down. Reuses the telegramVoiceFinalizing
     * CAS latch so it cannot double-emit against the transcriber fallback path.
     */
    private fun finalizeAzureNotifReply() {
        if (!telegramVoiceFinalizing.compareAndSet(false, true)) {
            LogCollector.i(TAG, "[NREPLY] finalize already in progress, ignoring quiet-timer fire")
            return
        }
        cancelNotifReplyQuietTimer()
        cancelNotifReplyInactivity()
        val finalText = notifReplyAccum.toString().trim()
        stopAzureNotifReplySession()
        // Empty accum -> send "" (glasses treats blank as cancel so the reply
        // overlay doesn't hang in SENDING).
        LogCollector.i(TAG, "[NREPLY] end-of-reply, emitting consolidated final len=${finalText.length}: '${finalText.take(80)}'")
        phoneBtHost.sendGlassesUserText("tg_voice", finalText)
        // Mirror the fields finishTelegramVoiceRecording() clears.
        telegramVoiceMode = false
        notifReplyId = null
    }

    /**
     * Tears down the Azure notif-reply session and clears its derived state. Safe
     * to call when no session is active. Does NOT touch telegramVoiceFinalizing
     * (that latch is owned by the finalize path) nor emit any glasses text.
     *
     * Threading: callable off the main thread (BT callbacks, onGlassesDisconnected,
     * onDestroy). The recognizer teardown (session.stop() -- which releases the JNI
     * handle) runs SYNCHRONOUSLY so it completes even during onDestroy without
     * relying on the looper draining. The non-thread-safe field mutations
     * (StringBuilder, runnable handle, flag) are POSTED onto mainHandler so they
     * serialize with the Azure onPartial/onFinal callbacks and the quiet/backstop
     * runnables (all of which run on mainHandler). mainHandler.removeCallbacks is
     * itself thread-safe, but we cancel inside the posted body too to preserve FIFO
     * ordering against any callback already queued ahead of this stop.
     */
    private fun stopAzureNotifReplySession() {
        // Synchronous recognizer release (important; must not depend on looper drain).
        val stopped = azureNotifReplySession
        stopped?.let {
            try { it.stop() } catch (t: Throwable) { LogCollector.w(TAG, "[NREPLY] Azure stop error: ${t.message}") }
        }
        if (azureNotifReplySession === stopped) azureNotifReplySession = null
        // Serialize StringBuilder / runnable / flag clearing on the main thread.
        // Guard: if a NEW session was installed before this posted body runs (a
        // preempt that stops the old then starts a fresh one), do NOT wipe the new
        // session's accum / cancel its backstop. Only clear when no session is live.
        mainHandler.post {
            if (azureNotifReplySession != null) return@post
            cancelNotifReplyQuietTimer()
            cancelNotifReplyInactivity()
            notifReplyAzure = false
            notifReplyAccum.setLength(0)
        }
    }

    /**
     * Two-way result router. Detected source language decides which surface
     * gets the translated text:
     *  - detected == fromBcp (the OTHER person spoke source) -> translate to
     *    target -> show on glasses (wearer reads it).
     *  - detected == toBcp (the wearer spoke target) -> translate to source
     *    -> broadcast to TwoWayTranslationActivity for the other person to read.
     *  - anything else -> default to glasses (best-effort).
     */
    private fun routeTwoWayResult(
        src: String,
        translations: Map<String, String>,
        detected: String,
        fromBcp: String,
        toBcp: String,
        fromTgt: String,
        toTgt: String,
        partial: Boolean
    ) {
        // Azure's detected language code may differ in case / region. Compare loosely.
        fun matches(a: String, b: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            if (a.equals(b, ignoreCase = true)) return true
            // "ru" vs "ru-RU" -- match on the prefix.
            return a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
        }

        val toGlasses: Boolean
        val tgtText: String
        when {
            matches(detected, fromBcp) -> {
                toGlasses = true
                tgtText = translations[toTgt].orEmpty()
            }
            matches(detected, toBcp) -> {
                toGlasses = false
                tgtText = translations[fromTgt].orEmpty()
            }
            else -> {
                toGlasses = true
                tgtText = translations[toTgt].orEmpty().ifEmpty { translations[fromTgt].orEmpty() }
            }
        }

        if (toGlasses) {
            val segId = if (partial) translationSegmentId.get() else translationSegmentId.getAndIncrement()
            phoneBtHost.sendTranslationResultToApp(
                segId,
                text = src,
                translation = tgtText.ifEmpty { null },
                partial = partial
            )
            LogCollector.i(TAG, "2way->glasses (det=$detected, partial=$partial): ${src.take(40)} | ${tgtText.take(40)}")
        } else {
            val intent = Intent(com.repository.listener.ui.TwoWayTranslationActivity.ACTION_TWO_WAY_PHONE_RESULT).apply {
                setPackage(packageName)
                putExtra(com.repository.listener.ui.TwoWayTranslationActivity.EXTRA_TEXT, tgtText.ifEmpty { src })
                putExtra(com.repository.listener.ui.TwoWayTranslationActivity.EXTRA_PARTIAL, partial)
            }
            sendBroadcast(intent)
            LogCollector.i(TAG, "2way->phone (det=$detected, partial=$partial): ${src.take(40)} | ${tgtText.take(40)}")
        }
    }

    /**
     * Forward a slice of the live mic to the TwoWayTranslationActivity for its
     * spectrogram visualization. Cheap broadcast, only emitted while two-way
     * mode is active.
     */
    private fun broadcastTwoWayPhoneResult(segId: Int, text: String, partial: Boolean) {
        val intent = Intent(com.repository.listener.ui.TwoWayTranslationActivity.ACTION_TWO_WAY_PHONE_RESULT).apply {
            setPackage(packageName)
            putExtra(com.repository.listener.ui.TwoWayTranslationActivity.EXTRA_SEG_ID, segId)
            putExtra(com.repository.listener.ui.TwoWayTranslationActivity.EXTRA_TEXT, text)
            putExtra(com.repository.listener.ui.TwoWayTranslationActivity.EXTRA_PARTIAL, partial)
        }
        sendBroadcast(intent)
    }

    private fun broadcastTwoWayAudioLevel(samples: ShortArray) {
        if (!translationTwoWay || !translationMode) return
        val intent = Intent(com.repository.listener.ui.TwoWayTranslationActivity.ACTION_TWO_WAY_AUDIO_LEVEL).apply {
            setPackage(packageName)
            putExtra(com.repository.listener.ui.TwoWayTranslationActivity.EXTRA_PCM, samples)
        }
        sendBroadcast(intent)
    }

    private fun reconnectTranscribeStream() {
        orchestratorClient.setTranscribeStreamListener(transcribeStreamListener)
        orchestratorClient.connectTranscribeStream(
            translationFromLang, translationToLang,
            translationFromNllb, translationToNllb,
            translate = translationMode
        )
        LogCollector.i(TAG, "Transcribe stream reconnecting (translate=$translationMode)")
    }


    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Map provider switch (idle-only): swap the active provider only when no
            // journey is in flight, so we never tear down the provider under an
            // active or restoring journey. Runs independently of the glasses push
            // below (which early-returns when there is no settingsJson extra).
            //
            // This receiver is registered without a Handler, so onReceive already runs
            // on the main thread -- but the switch path calls YandexMapProvider.init()
            // -> MapKitFactory.initialize() and onProcessStart() -> MapKitFactory.onStart(),
            // which REQUIRE the main thread. Marshal explicitly so we never crash if the
            // delivery thread ever changes; run inline when already on main to avoid
            // reordering relative to the glasses push below.
            val switchBlock = Runnable {
                val desiredProvider = AppConfig.getMapProvider(this@ListenerService)
                val before = MapProviders.active.id
                if (desiredProvider != before) {
                    val nav = NavigationManager.getInstance(applicationContext)
                    if (nav.canSwitchProvider()) {
                        // switchTo may keep the current provider (N9: Google without a key).
                        val after = MapProviders.switchTo(applicationContext, desiredProvider)
                        if (after != before) {
                            // Rebind the singleton's LocationEngine so routing/minimap/map
                            // all follow the new provider (no split-brain). Idle-only, so
                            // the visible NavigationFragment will rebuild its InteractiveMap
                            // from the new provider in onResume.
                            nav.rebindProvider()
                            LogCollector.i(TAG, "Map provider switched to $after")
                        } else {
                            LogCollector.i(TAG, "Map provider switch to $desiredProvider refused; staying on $before")
                        }
                    } else {
                        LogCollector.i(TAG, "Map provider switch deferred: journey in flight (idle-only)")
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                switchBlock.run()
            } else {
                Handler(Looper.getMainLooper()).post(switchBlock)
            }

            val json = intent.getStringExtra("settingsJson") ?: return
            LogCollector.i(TAG, "Settings changed, pushing to glasses")
            if (phoneBtHost.isConnected) {
                phoneBtHost.sendSettings(json)
            }
        }
    }


    /** Remote input bridge (watch -> dedicated glasses input socket). */
    private var watchInputBridge: com.repository.listener.wear.WatchInputBridge? = null

    /**
     * Pushes a status bitfield back to the watch.
     *
     * Advisory only and deliberately unauthenticated: this side holds no HMAC key
     * by design, so it cannot sign. The watch treats status as advisory and never
     * lets it assert health over a locally observed failure.
     */
    private fun sendWatchStatus(bits: ByteArray) {
        try {
            val nodeClient = com.google.android.gms.wearable.Wearable.getNodeClient(this)
            nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                val messageClient = com.google.android.gms.wearable.Wearable.getMessageClient(this)
                for (node in nodes) {
                    messageClient.sendMessage(
                        node.id,
                        com.repository.listener.protocol.RemoteInputProtocol.PATH_STATUS,
                        bits,
                    )
                }
            }
        } catch (e: Exception) {
            LogCollector.w(TAG, "watch status push failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogCollector.i(TAG, "Service creating (photo-auto-attach v2)")

        // Initialize pipeline debugging
        AppConfig.debugPipeline = AppConfig.getDebugPipeline(this)
        PipelineTracer.reset()
        LogCollector.i(TAG, "Pipeline debug mode: ${AppConfig.debugPipeline}")

        // Initialize glasses audio archiver
        glassesAudioArchiver = GlassesAudioArchiver(this)
        audioArchiver = glassesAudioArchiver
        backgroundTranscriber = com.repository.listener.audio.BackgroundTranscriber(this, glassesAudioArchiver!!).also {
            it.start(serviceScope)
        }

        // Start foreground with microphone type only. mediaProjection type is added later
        // in ACTION_SETUP_PROJECTION after user grants consent (Android 14+ requires consent
        // BEFORE startForeground with MEDIA_PROJECTION type).
        // Falls back to dataSync type on SecurityException
        // (happens after reinstall when RECORD_AUDIO is revoked).
        // connectedDevice omitted -- requires BLUETOOTH_CONNECT at call time.
        // Must use ServiceCompat.startForeground with explicit type in fallback too, because
        // bare startForeground() defaults to ALL manifest-declared types on Android 14+.
        try {
            // LOCATION bit is required so our WifiDirectJoiner can call WifiP2pManager.connect
            // from a background service (appops FINE_LOCATION is UID-locked to "foreground"
            // mode on Android 12+; FGS_LOCATION promotes it to allowed during the FGS).
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Initializing..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: SecurityException) {
            LogCollector.w(TAG, "Microphone FGS type rejected, falling back to dataSync|location")
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Initializing... (mic permission needed)"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        }

        // Partial wake lock -- keeps CPU alive for BT + HTTP server while screen is off
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Listener::BtService")
        wakeLock?.acquire()

        // Initialize audio components
        audioRecorder = AudioRecorder()
        audioRecorder.setChunkListener(this)

        wakeWordDetector = WakeWordDetector(this)
        wakeWordDetector.setListener(this)
        wakeWordDetector.initialize()
        LogCollector.i(TAG, "Glasses audio source: WakeWordDetector disabled (on-glasses WakeWordPipeline is authoritative); phone-mic source stays active")

        this.transcriberUrl = AppConfig.getOrchestratorHttpUrl(this)

        vadEngine = VadEngine(this)
        vadEngine.initialize()
        wakeWordDetector.setVadEngine(vadEngine)

        // Initialize speaker verification gate
        speakerVerifier = SpeakerVerifier(this)
        try {
            speakerVerifier.initialize()
            wakeWordDetector.setSpeakerVerifier(speakerVerifier)
            LogCollector.i(TAG, "Speaker verifier initialized and attached to wake word detector")
        } catch (e: Exception) {
            LogCollector.e(TAG, "Speaker verifier init failed (wake word will work without it): ${e.message}")
        }

        // Glasses VAD engine for speech detection during LISTENING state
        try {
            glassesVadEngine = VadEngine(this).also { it.initialize() }
            LogCollector.i(TAG, "Glasses VAD engine initialized")
        } catch (e: Exception) {
            LogCollector.e(TAG, "Glasses VAD engine init failed: ${e.message}")
        }

        // Start RFCOMM audio server for direct glasses audio (bypasses CXR-S latency)
        startBtAudioServer()
        startGlassesAudioStreamServer()

        // Initialize network components
        val orchestratorUrl = AppConfig.getOrchestratorUrl(this)
        val orchestratorHttpUrl = AppConfig.getOrchestratorHttpUrl(this)
        val apiKey = AppConfig.getApiKey(this)
        val deviceId = AppConfig.getDeviceId(this)

        orchestratorClient = OrchestratorClient(orchestratorUrl, deviceId, apiKey)
        orchestratorClient.setListener(this)

        // Discover the desktop's LAN-direct relay server (mDNS) so audio relay can
        // skip the cloud hop when phone and PC share a network. Best-effort; the
        // audio-relay path falls back to the cloud when nothing is discovered.
        desktopRelayLocator = DesktopRelayLocator(this).also { it.start() }

        registerAudioRelayVpnWatcher()

        // WebRTCClient initialized lazily on first webrtc_offer via getOrCreateWebRTCClient()

        setupMouseEventCallback()

        transcriptionClient = TranscriptionClient(orchestratorHttpUrl, apiKey)
        LogCollector.i(TAG, "Transcriber stream URL: $transcriberUrl")
        reidAnalyticsClient = ReidAnalyticsClient(
            AppConfig.getOrchestratorHttpUrl(this),
            AppConfig.getApiKey(this)
        )

        // Initialize capture components
        screenCapturer = ScreenCapturer(this)
        cameraCapturer = CameraCapturer(this, this)
        // The map SDK engine is NOT started here anymore. It is reference-counted via
        // MapProviders.acquireEngine/releaseEngine and started only while a consumer needs it
        // (visible map, active/preparing journey, minimap renderer, one-shot geocode/search/
        // suggest). Keeping the Yandex engine running process-wide kept its background threads
        // alive even when nothing map-related was on screen, and crashed the process on this
        // hardware (YMK_#Global SIGTRAP) -- which reset the in-memory glasses-connected flag.
        locationProvider = LocationProvider(this)
        locationProvider.startBackgroundUpdates()
        audioToolRecorder = AudioToolRecorder(this)

        // Initialize TTS player
        ttsPlayer = TtsPlayer(this)
        ttsPlayer.setListener(this)
        audioDucker = AudioDucker(this)

        // Initialize BT host for glasses relay
        phoneBtHost = PhoneBtHost(this)
        phoneBtHostInstance = phoneBtHost
        phoneBtHost.listener = this

        // Remote input from the watch. The bridge forwards watch-signed frames
        // verbatim onto the dedicated input socket; it holds no HMAC key, because
        // the watch signs and the glasses verify. Published statically so the
        // exported WearableListenerService can reach it -- GMS binds that service
        // into this process on demand, and it cannot start a foreground service
        // from the background to revive us.
        watchInputBridge = com.repository.listener.wear.WatchInputBridge(
            inputClient = phoneBtHost.inputRfcommClient,
            statusSender = { bits -> sendWatchStatus(bits) },
            // Remote input outranks the desktop audio relay, by explicit user
            // decision: a bezel the user is actively turning doing nothing is worse
            // than audio that stops. Every other feature keeps the opposite priority.
            stopAudioRelay = { reason -> stopAudioRelayForRemoteInput(reason) },
        )
        com.repository.listener.wear.WatchMessageListenerService.bridge = watchInputBridge
        // The glasses' sink state arrives on the DEDICATED input socket, because that
        // is the socket they publish it on. Both channels report the same
        // `sinkAttached` bit and both feed the one setter, so the watch can never be
        // shown two contradictory answers.
        phoneBtHost.inputRfcommClient.onSinkState = { attached ->
            watchInputBridge?.setGlassesSinkAttached(attached)
        }
        // `sessionOpen` is NOT discarded here, and the underscore that used to stand in
        // for it was the last mile of a permanent deadlock. The glasses hold the live
        // session in memory while persisting only the replay high-water mark, so after
        // a glasses restart they correctly reject every frame for a session they hold
        // no OPEN for -- and the watch cannot discover that on its own, because its own
        // keepalive keeps succeeding and so its silence-based re-announce trigger never
        // fires. The glasses were already reporting the fact; the phone parsed it and
        // threw it away one line short of the watch.
        phoneBtHost.inputRfcommClient.onRouterStatus = { sessionOpen, sinkAttached, _ ->
            watchInputBridge?.setGlassesSessionOpen(sessionOpen)
            watchInputBridge?.setGlassesSinkAttached(sinkAttached)
        }
        // The glasses' UI received input and REFUSED it. Forwarded so the watch can say
        // WHY nothing is happening: without this every refusal ended in a log line on the
        // glasses' internal flash while the watch went on showing "Connected".
        phoneBtHost.inputRfcommClient.onRefusal = { reason, refusedTotal ->
            watchInputBridge?.setGlassesRefusal(reason, refusedTotal)
        }
        // The glasses' own hold threshold, relayed so the watch's press timer matches the
        // physical touchpad instead of a frozen copy of the number that can drift from it.
        phoneBtHost.inputRfcommClient.onHoldThreshold = { holdMs ->
            watchInputBridge?.setGlassesHoldMs(holdMs)
        }
        // Opaque glasses state, copied to the watch. Do NOT interpret the bits here.
        phoneBtHost.inputRfcommClient.onDeviceState = { bits ->
            watchInputBridge?.setGlassesDeviceState(bits)
        }
        phoneBtHost.onGlassesCommandResult = { requestId, result ->
            onGlassesCommandResult(requestId, result)
        }
        phoneBtHost.onRetryCountdown = { seconds ->
            sendBroadcast(Intent(ACTION_GLASSES_RETRY_COUNTDOWN).apply {
                setPackage(packageName)
                putExtra(EXTRA_RETRY_SECONDS, seconds)
            })
        }
        phoneBtHost.onBatteryChanged = { level, isCharging ->
            glassesBatteryLevel = level
            glassesBatteryCharging = isCharging
            sendBroadcast(Intent(ACTION_GLASSES_BATTERY).apply {
                setPackage(packageName)
                putExtra(EXTRA_BATTERY_LEVEL, level)
                putExtra(EXTRA_BATTERY_CHARGING, isCharging)
            })
        }
        phoneBtHost.onRecordingStateChanged = { batteryPct, always, onDemand, recording, worn ->
            if (batteryPct >= 0) glassesBatteryLevel = batteryPct
            alwaysRecordEnabled = always
            onDemandActive = onDemand
            glassesRecordingActive = recording
            glassesWornMirror = worn
            sendBroadcast(Intent(ACTION_RECORDING_STATE_UPDATED).apply {
                setPackage(packageName)
            })
        }
        phoneBtHost.onGlassesSideloadingState = { enabled ->
            // Glasses is authoritative. Mirror its reported state into the phone: persist +
            // start/stop the LAN server. applySideloadingState does NOT push back to the glasses,
            // so this is a one-way reflect with no echo loop. Only re-apply when it actually
            // differs from what the phone already has.
            if (AppConfig.getSideloadingEnabled(this) != enabled) {
                LogCollector.i(TAG, "Mirroring glasses sideloading state -> $enabled")
                applySideloadingState(this, enabled)
            }
        }
        phoneBtHost.onChatListRequested = { handleGlassesChatListRequest() }
        phoneBtHost.onSwitchChatRequested = { id -> handleGlassesSwitchChat(id) }
        phoneBtHost.onNewChatRequested = {
            LogCollector.i(TAG, "New chat requested by glasses, resetting orchestrator session")
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendNewChat() }
            sendBroadcast(Intent(ACTION_SESSION_RESET).apply { setPackage(packageName) })
        }
        phoneBtHost.onTodoListRequested = {
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoList() }
        }
        phoneBtHost.onTodoToggle = { id ->
            val completed = findTodoCompleted(id)
            if (completed != null) {
                LogCollector.i(TAG, "Glasses todo toggle: id=$id completed=$completed -> ${!completed}")
                serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoUpdate(id, !completed) }
            } else {
                LogCollector.w(TAG, "Glasses todo toggle: id=$id not found in cache, requesting list")
                serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoList() }
            }
        }
        phoneBtHost.onTodoAdd = { text ->
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoCreate(text) }
        }
        phoneBtHost.onTodoRemove = { id ->
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTodoDelete(id) }
        }
        phoneBtHost.onAlarmListRequested = {
            serviceScope.launch(Dispatchers.IO) { sendAlarmListToGlasses() }
        }
        phoneBtHost.onJobListRequested = {
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendJobList() }
        }
        phoneBtHost.onTelegramSavedRequested = {
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTelegramSaved() }
        }
        phoneBtHost.onTelegramChatListRequested = { limit ->
            android.util.Log.d("TG_DEBUG", "Phone: BT chat list request received (limit=$limit), sending to orchestrator")
            serviceScope.launch(Dispatchers.IO) {
                val sent = orchestratorClient.sendTelegramChatList(limit)
                android.util.Log.d("TG_DEBUG", "Phone: orchestrator.sendTelegramChatList sent=$sent")
            }
        }
        phoneBtHost.onTelegramMessagesRequested = { chatId, limit, topicId, offsetId ->
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTelegramMessages(chatId, limit, topicId, offsetId) }
        }
        phoneBtHost.onRcMessagesReq = { args -> rcBridge.handleMessagesReq(args) }
        phoneBtHost.onRcSendReq = { args -> rcBridge.handleSendReq(args) }
        phoneBtHost.onRcAnswerReq = { args -> rcBridge.handleAnswerReq(args) }
        phoneBtHost.onTelegramSendRequested = { chatId, text, topicId ->
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTelegramSendMessage(chatId, text, topicId) }
        }
        phoneBtHost.onTelegramTopicsRequested = { chatId ->
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTelegramTopics(chatId) }
        }
        phoneBtHost.onTelegramSubscribeRequested = {
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTelegramSubscribe() }
        }
        phoneBtHost.onTelegramUnsubscribeRequested = {
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTelegramUnsubscribe() }
        }
        phoneBtHost.onTelegramChatOpened = { chatId, chatTitle ->
            currentGlassesTgChatTitle = chatTitle
            LogCollector.i(TAG, "Telegram chat opened: chatId=$chatId title=$chatTitle")
        }
        phoneBtHost.onTelegramChatClosed = {
            currentGlassesTgChatTitle = null
            LogCollector.i(TAG, "Telegram chat closed")
        }
        phoneBtHost.onTelegramVoiceStart = start@{ chatId ->
            // Defensive mutual exclusion: a notif reply is in progress when notifReplyId
            // is set. Do not stomp it with a tg-chat voice session.
            if (notifReplyId != null) {
                LogCollector.w(TAG, "Telegram voice start ignored: notif reply in progress (notifId=$notifReplyId), chatId=$chatId")
                return@start
            }
            telegramVoiceMode = true
            telegramVoiceChatId = chatId
            telegramVoiceSpeechDetected = false
            telegramVoiceLastSpeechTime = System.currentTimeMillis()
            synchronized(telegramVoiceAudioBuffer) { telegramVoiceAudioBuffer.clear() }
            armTelegramVoiceNoSpeechWatchdog()
            // Pass null so startTranscriberStream falls through to the user's
            // configured STT language (AppConfig.getSttLanguage). Passing "auto"
            // here was a footgun: TranscriberStreamClient strips the field for
            // "auto", and the orchestrator defaults missing sourceLang to "en",
            // so Russian voice messages were silently transcribed as English.
            startTranscriberStream(true)
            LogCollector.i(TAG, "Telegram voice mode started: chatId=$chatId")
        }
        phoneBtHost.onTelegramVoiceStop = {
            clearTelegramVoiceNoSpeechWatchdog()
            telegramVoiceMode = false
            telegramVoiceChatId = null
            stopTranscriberStream()
            synchronized(telegramVoiceAudioBuffer) { telegramVoiceAudioBuffer.clear() }
            LogCollector.i(TAG, "Telegram voice mode stopped")
        }
        phoneBtHost.onNotifReplyStart = start@{ notifId ->
            // Defensive mutual exclusion: a tg-chat voice session is active when
            // telegramVoiceMode is true and it is NOT a notif reply. A real tg-chat
            // voice session sets telegramVoiceChatId; a notif reply sets notifReplyId.
            // Reject starting a notif reply while a different session is in progress
            // rather than stomping the in-progress tg-chat voice capture/STT stream.
            if (telegramVoiceMode && notifReplyId == null && telegramVoiceChatId != null) {
                LogCollector.w(TAG, "Notif reply start ignored: tg-chat voice session active (chatId=$telegramVoiceChatId), notifId=$notifId")
                return@start
            }
            // A new notif reply preempts any prior Azure notif-reply session that
            // never finalized (e.g. glasses reconnect mid-reply). Tear it down so
            // the identity guard on its late callbacks short-circuits.
            stopAzureNotifReplySession()
            // Reuse the telegram-voice capture/STT path. The only difference is
            // there is no chatId; notifReplyId marks this as a notification reply.
            notifReplyId = notifId
            telegramVoiceMode = true
            telegramVoiceChatId = null
            telegramVoiceSpeechDetected = false
            telegramVoiceLastSpeechTime = System.currentTimeMillis()
            synchronized(telegramVoiceAudioBuffer) { telegramVoiceAudioBuffer.clear() }
            // Prefer the Azure Speech path: it streams fast interim partials and owns
            // its own segmentation/endpointing (no double-VAD cutoff). Fall back to
            // the transcriber stream if Azure is unconfigured or fails to start.
            val azureUp = try {
                startAzureNotifReplySession()
            } catch (t: Throwable) {
                LogCollector.w(TAG, "[NREPLY] Azure notif-reply start threw, falling back to transcriber: ${t.message}")
                false
            }
            if (azureUp) {
                notifReplyAzure = true
                LogCollector.i(TAG, "[NREPLY] phone Reply START: notifId=$notifId (Azure session up)")
            } else {
                notifReplyAzure = false
                startTranscriberStream(true)
                armTelegramVoiceNoSpeechWatchdog()
                LogCollector.i(TAG, "[NREPLY] phone Reply START: notifId=$notifId (transcriber stream up)")
            }
        }
        // Which recogniser owns the coming session. Arrives BEFORE any audio, so
        // startTranscriberStream and the VAD feed can decline for the whole
        // session rather than deciding per chunk.
        phoneBtHost.onGlassesSttMode = { local ->
            glassesSttGate = GlassesSttGate(localMode = local)
            LogCollector.i(TAG, "glasses STT gate: local=$local")
            // The five consequences spelled out. They are NOT all the same value
            // (pcmBuffer stays true in local mode, and that buffer is the entire
            // failure fallback), so a bare local=true does not tell you what the
            // phone will actually do next.
            val g = glassesSttGate
            LogCollector.i(
                TAG,
                "[STT] gate local=$local openStream=${g.shouldOpenTranscriberStream()} " +
                    "feedVad=${g.shouldFeedPhoneVad()} armWatchdog=${g.shouldArmNoSpeechWatchdog()} " +
                    "bufferPcm=${g.shouldBufferPcm()} forwardPartials=${g.shouldForwardPartials()}"
            )
        }

        // A final from the on-glasses recogniser. It re-enters the SAME delivery
        // tails the remote transcriber feeds, so requestIds, orchestrator
        // submission and the cancel contract are identical on both paths.
        phoneBtHost.onGlassesLocalTranscript = { tag, ok, text ->
            if (!ok) {
                // Local recognition could not do it. The PCM was buffered
                // throughout precisely for this: transcribe it the old way rather
                // than losing the utterance.
                LogCollector.w(TAG, "glasses local STT failed for tag=$tag; falling back to remote")
                LogCollector.i(TAG, "[STT] FALLBACK TO REMOTE tag=$tag: glasses reported fail, batch-transcribing buffered PCM")
                fallbackToRemoteTranscription(tag)
            } else when (tag) {
                LocalTranscriptWire.TAG_TG_VOICE ->
                    // Includes the empty-text case, which is the wearer
                    // cancelling; deliverTelegramVoiceTranscript owns that contract.
                    deliverTelegramVoiceTranscript(text, notifReplyId)
                else -> serviceScope.launch {
                    deliverGlassesTranscript(
                        text, hadStreamPreview = false, source = TranscriptSource.LOCAL
                    )
                }
            }
        }

        phoneBtHost.onNotifReplyCancel = { notifId ->
            LogCollector.i(TAG, "Notif reply voice cancel: notifId=$notifId")
            clearTelegramVoiceNoSpeechWatchdog()
            stopAzureNotifReplySession()
            telegramVoiceMode = false
            notifReplyId = null
            // User aborted: the SEND that consumes the ref will never come, so prune now.
            notifIdToReplyRef.remove(notifId)
            stopTranscriberStream()
            synchronized(telegramVoiceAudioBuffer) { telegramVoiceAudioBuffer.clear() }
        }
        phoneBtHost.onNotifReplySend = { notifId, text ->
            LogCollector.i(TAG, "Notif reply send: notifId=$notifId text=${text.take(40)}")
            fireNotificationReply(notifId, text)
            stopAzureNotifReplySession()
            telegramVoiceMode = false
            notifReplyId = null
        }
        phoneBtHost.onSpeakerVerifyRequested = {
            handleSpeakerVerifyForTelegram()
        }
        phoneBtHost.onSpeakerVerifyAudio = { base64Audio ->
            handleSpeakerVerifyWithAudio(base64Audio)
        }
        phoneBtHost.onNotificationDone = { notifId ->
            LogCollector.i(TAG, "Glasses notification done: $notifId")
            // The notification is gone from the glasses, so the user can no longer
            // voice-reply to it. Drop any stored reply ref to bound map growth.
            notifIdToReplyRef.remove(notifId)
            notificationQueue.onGlassesDone(notifId)
        }
        phoneBtHost.onAudioDuck = { duck ->
            // Glasses are the authoritative source of whether to duck. If we already
            // know the user isn't wearing them, ignore any residual duck=true signal
            // so phone media volume is left alone.
            if (duck && !glassesWorn) {
                LogCollector.i(TAG, "AudioDuck ignored: glasses off-head")
            } else {
                audioDucker.setDucked(duck)
            }
        }
        phoneBtHost.onWearState = { worn ->
            val prev = glassesWorn
            glassesWorn = worn
            LogCollector.i(TAG, "Glasses wear state: worn=$worn (prev=$prev)")
            // Going off-head mid-playback: forcibly restore phone media volume.
            if (prev && !worn) audioDucker.setDucked(false)
            applyMapTransmitGate()
        }
        phoneBtHost.onScreenState = { on ->
            glassesScreenOn = on
            LogCollector.i(TAG, "Glasses screen state: on=$on")
            applyMapTransmitGate()
        }
        phoneBtHost.onGlassesCommand = { type, params -> handleGlassesCommand(type, params) }
        phoneBtHost.onGlassesInwardAudioData = { b64 -> onGlassesInwardAudioData(b64) }
        phoneBtHost.onGlassesCallAudioData = { b64 -> onGlassesCallAudioData(b64) }
        phoneBtHost.onGlassesCallState = { scoActive -> onGlassesCallState(scoActive) }

        // Wire notification queue callbacks
        notificationQueue.callback = object : com.repository.listener.notification.NotificationQueue.Callback {
            override fun onRequestTts(notifId: String, ttsSender: String, text: String, chat: String) {
                val notifSound = AppConfig.getGlassesNotificationSound(this@ListenerService)
                if (orchestratorClient.isConnected && notifSound != "false") {
                    LogCollector.i(TAG, "[NotifQ] callback: requesting TTS for $notifId (ttsSender=${ttsSender.ifEmpty { "(omitted)" }})")
                    // Send from IO thread -- OkHttp WebSocket drops messages sent from main handler thread
                    serviceScope.launch(Dispatchers.IO) {
                        orchestratorClient.sendNotificationTts(notifId, ttsSender, text, chat)
                    }
                } else {
                    LogCollector.i(TAG, "[NotifQ] callback: no TTS available for $notifId")
                    notificationQueue.onTtsAudio(notifId, "", true)
                }
            }
            override fun onSendToGlasses(notifId: String, sender: String, text: String, chat: String, repliable: Boolean, sbnKey: String?, audioBase64: String?) {
                // Remember which notification this notifId maps to so a later
                // CH_NOTIF_REPLY_SEND can resolve the stored RemoteInput action.
                if (repliable && sbnKey != null) notifIdToReplyRef[notifId] = NotifReplyRef(sbnKey, sender, chat)
                if (!phoneBtHost.isConnected) {
                    LogCollector.i(TAG, "[NotifQ] callback: glasses disconnected, waking RFCOMM for $notifId")
                    phoneBtHost.wakeAndReconnect("notif:$notifId")
                    // Retry after giving RFCOMM time to connect
                    mainHandler.postDelayed({
                        if (phoneBtHost.isConnected) {
                            LogCollector.i(TAG, "[NotifQ] callback: RFCOMM reconnected, sending $notifId")
                            phoneBtHost.sendNotification(notifId, sender, text, chat, repliable)
                            if (!audioBase64.isNullOrEmpty()) {
                                phoneBtHost.sendNotificationTtsAudio(notifId, audioBase64, true)
                            }
                        } else {
                            LogCollector.i(TAG, "[NotifQ] callback: RFCOMM still down, skipping $notifId")
                            notificationQueue.onGlassesDone(notifId)
                        }
                    }, 3000)
                    return
                }
                LogCollector.i(TAG, "[NotifQ] callback: sending to glasses $notifId (hasAudio=${audioBase64 != null}, worn=$glassesWorn, repliable=$repliable)")
                phoneBtHost.sendNotification(notifId, sender, text, chat, repliable)
                if (!audioBase64.isNullOrEmpty()) {
                    phoneBtHost.sendNotificationTtsAudio(notifId, audioBase64, true)
                }
            }
            override fun onSetGlassesText(notifId: String, fullText: String) {
                if (!phoneBtHost.isConnected) {
                    LogCollector.i(TAG, "[NotifQ] callback: glasses disconnected, dropping set-text for $notifId")
                    return
                }
                LogCollector.i(TAG, "[NotifQ] callback: setting glasses text $notifId (${fullText.length} chars)")
                phoneBtHost.sendNotificationSetText(notifId, fullText)
            }
            override fun onRequestAppendTts(notifId: String, text: String, chat: String) {
                val notifSound = AppConfig.getGlassesNotificationSound(this@ListenerService)
                if (!orchestratorClient.isConnected || notifSound == "false") {
                    LogCollector.i(TAG, "[NotifQ] callback: no TTS for merged append into $notifId")
                    return
                }
                // Don't speak a merged message over an ACTIVE voice reply: the user is
                // recording into the glasses mic, and notification TTS would talk over
                // the reply and pollute its STT. The text is still merged into the
                // overlay visually; it just isn't spoken while a reply is in progress.
                if (notifReplyId != null) {
                    LogCollector.i(TAG, "[NotifQ] callback: skipping continuation TTS into $notifId -- voice reply active (${notifReplyId})")
                    return
                }
                // Synthetic id so the orchestrator's audio response does NOT collide with
                // the real notifId's in-flight queue state. Map it back to play on the
                // existing overlay. Empty ttsSender = continuation (no "X wrote:" prefix).
                // Keep it SHORT: the TTS audio reply carries the id in a fixed 36-byte
                // field (OrchestratorClient binary frame), so it must fit well under 36.
                val contId = "cont_" + java.util.UUID.randomUUID().toString().take(8)
                appendTtsContToNotif[contId] = notifId
                LogCollector.i(TAG, "[NotifQ] callback: requesting continuation TTS for merged append into $notifId (contId=$contId)")
                serviceScope.launch(Dispatchers.IO) {
                    orchestratorClient.sendNotificationTts(contId, "", text, chat)
                }
            }
            override fun log(message: String) {
                LogCollector.i(TAG, message)
            }
        }

        phoneBtHost.onReidFace = { trackingId, imageBase64 ->
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT)
                    LogCollector.i(TAG, "REID_HTTP_REQ tid=$trackingId imgBytes=${imageBytes.size}")
                    val result = reidAnalyticsClient?.searchByPhoto(imageBytes)
                    if (result != null) {
                        val persons = result.optJSONArray("persons")
                        if (persons != null && persons.length() > 0) {
                            val top = persons.getJSONObject(0)
                            val personUid = top.optString("id", "")
                            val rawName = if (top.isNull("display_name")) "" else top.optString("display_name", "")
                            var displayName = if (rawName == "null" || rawName == "Unknown Person" || rawName.isBlank()) "" else rawName
                            val score = top.optDouble("similarity", 0.0).toFloat()

                            // If no display_name, fetch enriched name from person detail (extractBestName)
                            if (displayName.isBlank() && personUid.isNotBlank()) {
                                try {
                                    val personDetail = reidAnalyticsClient?.getPerson(personUid)
                                    val enrichedName = personDetail?.optString("display_name", "")
                                        ?.takeIf { it.isNotEmpty() && it != "null" && it != "Unknown Person" }
                                    if (enrichedName != null) displayName = enrichedName
                                } catch (_: Exception) {}
                            }

                            LogCollector.i(TAG, "REID_HTTP_RESP tid=$trackingId persons=${persons.length()} topId=$personUid topName=$displayName topScore=$score")
                            LogCollector.i(TAG, "REID_BT_SEND tid=$trackingId recognized=true uid=$personUid")
                            phoneBtHost.sendReidResult(trackingId, true, personUid, displayName, score)

                            // Send best sighting thumbnail to glasses (async, don't block)
                            if (personUid.isNotBlank()) {
                                serviceScope.launch(Dispatchers.IO) {
                                    try {
                                        val imgBytes = reidAnalyticsClient?.getPersonImage(personUid)
                                        if (imgBytes != null) {
                                            val b64 = android.util.Base64.encodeToString(imgBytes, android.util.Base64.NO_WRAP)
                                            phoneBtHost.sendReidBestThumb(personUid, b64)
                                        }
                                    } catch (e: Exception) {
                                        LogCollector.e(TAG, "Failed to send best thumb: ${e.message}")
                                    }
                                }
                            }

                            // Create sighting with GPS data
                            android.util.Log.d(TAG, "REID_SIGHTING_START requesting location for personId=$personUid")
                            locationProvider.getCurrentLocation { locationJson ->
                                android.util.Log.d(TAG, "REID_SIGHTING_LOC_CB locationJson=$locationJson")
                                serviceScope.launch(Dispatchers.IO) {
                                    try {
                                        val lat = locationJson?.optDouble("lat", 0.0) ?: 0.0
                                        val lng = locationJson?.optDouble("lng", 0.0) ?: 0.0
                                        val accuracy = locationJson?.optDouble("accuracy", 0.0) ?: 0.0
                                        val headingRaw = locationJson?.optDouble("heading", Double.NaN) ?: Double.NaN
                                        val heading = if (!headingRaw.isNaN()) headingRaw.toFloat() else null
                                        android.util.Log.d(TAG, "REID_SIGHTING_COORDS lat=$lat lng=$lng acc=$accuracy heading=$heading")
                                        if (lat != 0.0 && lng != 0.0) {
                                            LogCollector.i(TAG, "REID_SIGHTING personId=$personUid lat=$lat lng=$lng heading=$heading score=$score")
                                            reidAnalyticsClient?.createSighting(
                                                personUid, "glasses-camera", score, lat, lng, accuracy, heading
                                            )
                                        } else {
                                            android.util.Log.w(TAG, "REID_SIGHTING_SKIP lat/lng are zero, no sighting created")
                                            LogCollector.w(TAG, "REID_SIGHTING_SKIP personId=$personUid location unavailable")
                                        }
                                    } catch (e: Exception) {
                                        LogCollector.e(TAG, "Create sighting error: ${e.message}")
                                    }
                                }
                            }
                        } else {
                            LogCollector.i(TAG, "REID_HTTP_RESP tid=$trackingId result=no_match")
                            phoneBtHost.sendReidResult(trackingId, false, "", "", 0f)
                        }
                    } else {
                        LogCollector.i(TAG, "REID_HTTP_RESP tid=$trackingId result=null")
                        phoneBtHost.sendReidResult(trackingId, false, "", "", 0f)
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Reid face relay error tid=$trackingId: ${e.message}")
                    phoneBtHost.sendReidResult(trackingId, false, "", "", 0f)
                }
            }
        }

        phoneBtHost.onReidPersonRequested = { personUid ->
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val person = reidAnalyticsClient?.getPerson(personUid)
                    if (person != null) {
                        val compact = buildGlassesPersonIntel(personUid, person)
                        phoneBtHost.sendReidPersonResponse(personUid, compact.toString())
                    } else {
                        phoneBtHost.sendReidPersonResponse(personUid, """{"error":"not_found"}""")
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Reid person lookup error: ${e.message}")
                    phoneBtHost.sendReidPersonResponse(personUid, """{"error":"${e.message?.replace("\"", "'")}"}""")
                }
            }
        }
        phoneBtHost.startScanning()

        // Wire NavigationManager BT bridge for glasses minimap
        val navManager = NavigationManager.getInstance(this)
        navManager.sendMapBitmap = { bytes -> phoneBtHost.sendMapBitmapBytes(bytes) }
        navManager.sendMapArrow = { x, y, h -> phoneBtHost.sendMapArrow(x, y, h) }
        navManager.sendDeviceCommand = { type, params -> phoneBtHost.sendDeviceCommand(type, params) }
        applyMapTransmitGate()
        navManager.onBtReady()

        // Initialize glasses health monitor
        healthMonitor = GlassesHealthMonitor(phoneBtHost) {
            LogCollector.i(TAG, "Glasses listener unresponsive -- waiting for glasses self-recovery")
        }
        phoneBtHost.onHealthPong = { healthMonitor.onPongReceived() }

        // Listen for settings changes from ConfigFragment
        registerReceiver(
            settingsReceiver,
            IntentFilter(ConfigFragment.ACTION_SETTINGS_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )

        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    LogCollector.i(TAG, "Entering pair mode")
                    phoneBtHost.enterPairMode()
                }
            },
            IntentFilter(GlassesSettingsFragment.ACTION_ENTER_PAIR_MODE),
            Context.RECEIVER_NOT_EXPORTED
        )

        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    LogCollector.i(TAG, "Exiting pair mode")
                    phoneBtHost.exitPairMode()
                }
            },
            IntentFilter(GlassesSettingsFragment.ACTION_EXIT_PAIR_MODE),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Photo processing queue: denoises each photo after it lands via sync.

        // Initialize glasses sync client (FSM over listener_sync channel + WiFi Direct pull).
        glassesSyncClient = GlassesSyncClient(
            context = this,
            send = { msgType, sessionId, payload ->
                phoneBtHost.sendSync(msgType, sessionId, *payload)
            },
        ).apply {
            remoteLog = { LogCollector.i("GlassesFileSync", it) }
            addListener(object : GlassesSyncClient.ProgressListener {
                override fun onSyncProgress(state: String, current: Int, total: Int, message: String) {
                    sendBroadcast(Intent(ACTION_GLASSES_SYNC_STATUS).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_SYNC_STATE, state)
                        putExtra(EXTRA_SYNC_MESSAGE, message)
                        putExtra(EXTRA_SYNC_CURRENT, current)
                        putExtra(EXTRA_SYNC_TOTAL, total)
                    })
                }
                override fun onSyncComplete(pulled: Int, deleted: Int) {
                    sendBroadcast(Intent(ACTION_GLASSES_SYNC_STATUS).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_SYNC_STATE, "COMPLETE")
                        putExtra(EXTRA_SYNC_MESSAGE, "pulled $pulled, deleted $deleted")
                        putExtra(EXTRA_SYNC_CURRENT, pulled + deleted)
                        putExtra(EXTRA_SYNC_TOTAL, pulled + deleted)
                    })
                    // Post-pull ingestion: pulled .opus files land under rootDir/audio-archive/.
                    // Move them to the phone-side archive dir (getExternalFilesDir("glasses-archive"))
                    // so later ASR/analysis passes can find them independent of the sync dir.
                    try {
                        ingestGlassesAudioArchive()
                    } catch (e: Exception) {
                        LogCollector.w("GlassesFileSync", "ingestGlassesAudioArchive failed: ${e.message}")
                    }
                }
                override fun onFilePulled(absPath: String) {
                    // Files live in the app's private external dir only; no gallery publish.
                }
                override fun onSyncError(msg: String) {
                    sendBroadcast(Intent(ACTION_GLASSES_SYNC_STATUS).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_SYNC_STATE, "ERROR")
                        putExtra(EXTRA_SYNC_MESSAGE, msg)
                        putExtra(EXTRA_SYNC_CURRENT, 0)
                        putExtra(EXTRA_SYNC_TOTAL, 0)
                    })
                }
            })
        }
        glassesSyncClientInstance = glassesSyncClient
        // Route incoming CH_SYNC frames into the FSM.
        phoneBtHost.onSyncMessage = { msgType, sessionId, payload ->
            glassesSyncClient.onMessage(msgType, sessionId, payload)
        }

        // Sideload: forwarder (BT handshake + WiFi-Direct join + HTTP to glasses) and the
        // LAN HTTP server that the desktop deploy script targets. The server is started
        // only while enable_sideloading is on (see applySideloadingState).
        sideloadForwarder = com.repository.listener.sync.SideloadForwarder(
            context = this,
            sendBtFrame = { json -> phoneBtHost.sendSideloadFrame(json) },
            isBtConnected = { phoneBtHost.isConnected },
        ).apply {
            remoteLog = { LogCollector.i("GlassesFileSync", it) }
        }
        phoneBtHost.onSideloadReply = { json -> sideloadForwarder.onBtReply(json) }
        phoneBtHost.onSideloadBtDisconnected = { sideloadForwarder.invalidateSession() }
        sideloadHttpServer = com.repository.listener.sync.SideloadHttpServer(
            forwarder = sideloadForwarder,
            isBtConnected = { phoneBtHost.isConnected },
            lanNetwork = { primaryWifiNetwork() },
        ).apply {
            remoteLog = { LogCollector.i("GlassesFileSync", it) }
        }
        sideloadHttpServerInstance = sideloadHttpServer
        // Restore the LAN server lifecycle from the persisted toggle.
        if (AppConfig.getSideloadingEnabled(this)) {
            sideloadHttpServer.start()
        }

        // Route glasses-side wake events (WakeWordPipeline on glasses fired) into the
        // phone's session activation flow. This is the sole activation path for
        // glasses wake-ups -- the phone does NOT run WakeWordDetector against the
        // glasses audio stream. Local phone-mic wakes still go through the
        // WakeWordDetector.WakeWordListener callback (onWakeWordDetected).
        phoneBtHost.onGlassesWakeEvent = { confidence, epochNanos ->
            mainHandler.post { handleGlassesWakeEvent(confidence, epochNanos) }
        }

        // Register sync trigger receiver
        registerReceiver(
            syncReceiver,
            IntentFilter(ACTION_START_GLASSES_SYNC),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register delete trigger receiver
        registerReceiver(
            deleteReceiver,
            IntentFilter(ACTION_START_GLASSES_DELETE),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register stream control receiver (DesktopFragment -> ListenerService)
        registerReceiver(
            streamReceiver,
            IntentFilter().apply {
                addAction(ACTION_STREAM_REQUEST)
                addAction(ACTION_STREAM_STOP)
                addAction(ACTION_STREAM_SWITCH_MONITOR)
                addAction(ACTION_AUDIO_RELAY_START)
                addAction(ACTION_AUDIO_RELAY_STOP)
                addAction(ACTION_AUDIO_RELAY_CONFIG)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register Telegram push notification receiver (TelegramNotificationListener -> ListenerService)
        registerReceiver(
            telegramNotifReceiver,
            IntentFilter(ACTION_TELEGRAM_NOTIF),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Start periodic health check for notification listener (MIUI/POCO can unbind it silently)
        mainHandler.postDelayed(notifListenerHealthCheck, 60_000L)

        // Register todo/telegram receiver (TodoFragment -> ListenerService)
        registerReceiver(
            todoReceiver,
            IntentFilter().apply {
                addAction(ACTION_TODO_LIST_REQ)
                addAction(ACTION_TODO_TOGGLE)
                addAction(ACTION_TODO_ADD)
                addAction(ACTION_TODO_DELETE)
                addAction(ACTION_TODO_MOVE)
                addAction(ACTION_TELEGRAM_SAVED_REQ)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register alarm change receiver (proactive glasses sync + TTS on fire)
        registerReceiver(
            alarmChangedReceiver,
            IntentFilter().apply {
                addAction(ACTION_ALARM_CHANGED)
                addAction(com.repository.listener.alarm.AlarmReceiver.ACTION_ALARM_FIRED)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register job receiver (JobsFragment -> ListenerService)
        registerReceiver(
            jobReceiver,
            IntentFilter().apply {
                addAction(ACTION_JOB_LIST_REQ)
                addAction(ACTION_JOB_CREATE)
                addAction(ACTION_JOB_UPDATE)
                addAction(ACTION_JOB_DELETE)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register RC inbound receiver (RemoteControlActivity -> ListenerService)
        registerReceiver(
            rcInboundReceiver,
            IntentFilter().apply {
                addAction(ACTION_RC_PERMISSION_RESP)
                addAction(ACTION_RC_USER_RESP)
                addAction(ACTION_RC_MODE_REQ)
                addAction(ACTION_RC_TRANSCRIPT_REQ)
                addAction(ACTION_RC_USER_MSG)
                addAction(ACTION_RC_USER_MSG_STATUS_QUERY)
                addAction(ACTION_RC_INTERRUPT)
                addAction(ACTION_RC_REVIVE)
                addAction(ACTION_RC_SETTING_CHANGE)
                addAction(ACTION_RC_MARK_READ)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        // Initialize overlay
        overlay = OverlayWidget(this)
        overlay.onCancelClicked = { cancelByTouch() }

        // Initialize activation sound
        try {
            val afd: AssetFileDescriptor = assets.openFd("activate.mp3")
            activatePlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to load activation sound: ${e.message}")
        }

        // Register screen state receiver
        screenStateReceiver = ScreenStateReceiver()
        screenStateReceiver.setListener(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)

        // Weather widget: periodic fetch + cache-hit relay to glasses, plus UI hide action.
        registerReceiver(
            weatherUpdatedReceiver,
            IntentFilter().apply {
                addAction(WeatherWorker.ACTION_WEATHER_UPDATED)
                addAction(ACTION_WEATHER_HIDE)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        if (AppConfig.isWeatherEnabled(this)) {
            WeatherScheduler.schedule(this)
        }

        // Connect to orchestrator
        orchestratorClient.connect()

        // Start listening for wake word
        audioRecorder.start()

        // Register phone call state listener to pause mic during calls
        registerCallStateListener()

        LogCollector.i(TAG, "Service created and listening")
        setIdleText("Waiting for command")
        broadcastState("IDLE", "Service started - listening for wake word")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                LogCollector.i(TAG, "ACTION_START received")
            }
            ACTION_STOP -> {
                LogCollector.i(TAG, "ACTION_STOP received")
                stopSelf()
            }
            ACTION_SETUP_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    // Upgrade FGS to include MEDIA_PROJECTION BEFORE calling getMediaProjection().
                    // Android 14+: consent granted -> upgrade FGS type -> then obtain projection.
                    try {
                        ServiceCompat.startForeground(
                            this, NOTIFICATION_ID, buildNotification("Listening..."),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                        LogCollector.i(TAG, "FGS upgraded to MICROPHONE + MEDIA_PROJECTION")
                    } catch (e: SecurityException) {
                        LogCollector.e(TAG, "Failed to upgrade FGS with MEDIA_PROJECTION: ${e.message}")
                    }
                    screenCapturer.onProjectionStopped = {
                        hasProjection = false
                        stopSystemAudioCapture()
                        LogCollector.w(TAG, "MediaProjection revoked -- system audio capture stopped")
                    }
                    screenCapturer.setup(resultCode, resultData)
                    hasProjection = true
                    LogCollector.i(TAG, "Screen projection set up via ACTION_SETUP_PROJECTION")
                    if (translationMode && translationAudioSource == "system") {
                        LogCollector.i(TAG, "Translation active with system source -- starting system audio capture after delay")
                        startSystemAudioCaptureWithRetry(retries = 3, delayMs = 1000)
                    }
                    if (copilotMode && copilotInterlocutorSource == "system") {
                        LogCollector.i(TAG, "Copilot active with system source -- starting system audio capture")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (copilotMode && copilotInterlocutorSource == "system") startSystemAudioCapture()
                        }, 1000)
                    }
                }
            }
            ACTION_ADB_DISPATCH -> {
                val commandId = intent.getStringExtra("command_id") ?: return START_STICKY
                val type = intent.getStringExtra("type") ?: return START_STICKY
                val params = intent.getStringExtra("params") ?: "{}"
                handleAdbCommand(commandId, type, params)
            }
        }

        return START_STICKY
    }

    // --- AudioRecorder.ChunkListener ---

    private var phoneChunkDbgCount = 0L
    override fun onChunk(samples: ShortArray) {
        phoneChunkDbgCount++
        if (phoneChunkDbgCount % 500 == 1L) {
            LogCollector.i(TAG, "onChunk #$phoneChunkDbgCount: state=$state bt=${phoneBtHost.isConnected} screenOn=$isGlassesScreenOn")
        }
        // Live AR stream uplink. AudioRecorder is a singleton with one ChunkListener slot, and a
        // second AudioRecord on MIC would both contend and defeat the AEC attached to this
        // session -- so the stream taps the existing chunks rather than opening its own.
        arStreamMicSink?.invoke(samples)
        // Two-way mode: phone mic is NOT used anymore -- the glasses inward mic
        // (CH_AUDIO_DATA_INWARD) replaces it. Audio arrives via BT and is fed to
        // azurePhoneMicSession in onGlassesInwardAudioData().
        // Phone pipeline (always runs)
        when (state) {
            State.IDLE -> {
                trackIdleAmplitude(samples)
                // Feed phone mic to wake word detector when:
                // - glasses not connected, OR
                // - glasses connected but screen off (glasses mic unreliable when screen off)
                if (!phoneBtHost.isConnected || !isGlassesScreenOn) {
                    wakeWordDetector.source = "phone"
                    wakeWordDetector.feedAudio(samples)
                } else if (rmsWriteIndex % 500 == 0) {
                    LogCollector.i(TAG, "Phone wake skip: bt=${phoneBtHost.isConnected} screenOn=$isGlassesScreenOn")
                }
            }
            State.ACTIVATING -> {
                // Brief transitional state, feed to wake word detector in cancel mode
                wakeWordDetector.feedAudio(samples)
            }
            State.LISTENING -> {
                // Buffer the audio
                synchronized(audioBuffer) {
                    audioBuffer.add(samples.copyOf())
                }

                // Feed to transcriber stream for server-side transcription
                transcriberStreamClient?.feedAudio(samples)

                // Update spectrogram bars from audio chunk
                updateSpectrogram(samples)

                // Feed to wake word detector for cancel detection
                wakeWordDetector.feedAudio(samples)

                // Run VAD
                val floatSamples = FloatArray(samples.size) { samples[it] / 32768f }
                val prob = vadEngine.processChunk(floatSamples)

                // Debug: log VAD probability every ~0.5s (every 16 chunks at 512 samples / 16kHz)
                vadChunkCount++
                if (vadChunkCount % 16 == 0) {
                    val now2 = System.currentTimeMillis()
                    val silence2 = now2 - lastSpeechTime
                    LogCollector.d(TAG, "VAD prob=%.3f threshold=%.2f silence=%dms samples=%d".format(
                        prob, AppConfig.VAD_THRESHOLD, silence2, samples.size))
                }

                if (prob > AppConfig.VAD_THRESHOLD) {
                    lastSpeechTime = System.currentTimeMillis()
                    speechDetected = true
                }

                val now = System.currentTimeMillis()
                val elapsed = now - recordingStartTime
                val silence = now - lastSpeechTime

                // End recording on silence (only after minimum recording time)
                if (silence >= AppConfig.SILENCE_THRESHOLD_MS && elapsed > AppConfig.MIN_RECORDING_MS) {
                    LogCollector.i(TAG, "FINISH: silence=${silence}ms elapsed=${elapsed}ms lastProb=%.3f".format(prob))
                    finishRecording()
                }

                // End recording on max duration
                if (elapsed >= AppConfig.MAX_RECORDING_SECONDS * 1000L) {
                    LogCollector.i(TAG, "Max recording duration reached (${elapsed}ms)")
                    finishRecording()
                }
            }
            State.FINISHING, State.RESPONDING -> {
                // Feed audio for TTS interrupt detection
                wakeWordDetector.feedAudio(samples)
            }
        }

        // Glasses audio comes via BT data channel (CXR-S or RFCOMM), not phone mic.
        // Phone mic path for glasses audio is disabled -- glasses have their own mic.
    }

    /**
     * Process glasses mic audio received via BT data channel (glasses-side AudioRecord -> Base64 PCM).
     * Glasses apply 24x gain before BT transmission -- Rokid mic array delivers ~1% full-scale signal,
     * and VAD needs at least ~0.3 normalized amplitude to reliably detect speech.
     */
    private fun startBtAudioServer() {
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                LogCollector.e(TAG, "BT audio server: no adapter")
                return
            }
            btAudioServerSocket = adapter.listenUsingRfcommWithServiceRecord("AudioStream", AUDIO_SOCKET_UUID)
            LogCollector.i(TAG, "BT audio server listening on RFCOMM")

            btAudioThread = Thread({
                // Per-accept decoder handle, hoisted out of the try so the catch path can
                // release() it on an IO error before re-accepting (no JNI/MediaCodec leak
                // across reconnects). Always nulled after release so a second release is a
                // no-op.
                var opusDecoder: com.repository.listener.audio.OpusStreamDecoder? = null
                while (true) {
                    try {
                        val socket = btAudioServerSocket?.accept() ?: break
                        // Defensive: release any decoder left over from a previous accept
                        // (e.g. an exception path that didn't reach the release below) so a
                        // fresh connection always starts from a clean codec + framing state.
                        opusDecoder?.let { try { it.release() } catch (_: Exception) {} }
                        opusDecoder = null
                        btAudioClientSocket = socket
                        LogCollector.i(TAG, "BT audio client connected: ${socket.remoteDevice?.name}")
                        // NOTE: the audio accept path must NOT drive status/relay channel
                        // reconnection. Kicking the relay reconnect here caused radio churn on
                        // the same ACL link that knocked over the freshly-accepted audio socket
                        // (read -> -1 on both ends), producing a tight re-accept storm where 0
                        // audio bytes ever flowed. The status/relay channel has its own
                        // independent reconnect triggers (BLE wake events, bonding, the 5s
                        // watchdog, every outbound send), so it recovers on its own after a
                        // glasses reboot without being driven here.
                        val input = socket.inputStream

                        // Drain stale data in kernel BT buffer (accumulated before phone started reading)
                        var totalDrained = 0L
                        val drainBuf = ByteArray(8192)
                        while (input.available() > 0) {
                            val n = input.read(drainBuf)
                            if (n <= 0) break
                            totalDrained += n
                        }
                        LogCollector.i(TAG, "BT audio drained ${totalDrained} bytes of stale data")

                        // Fresh Opus decoder for this connection. A new instance per accept
                        // guarantees the codec starts from a clean state; combined with the
                        // stale-byte drain above and the per-accept frame-parse locals below
                        // (rfcommFrameCount / consecutiveDecodeNulls / lastDesyncLogMs all
                        // reset here), a reconnect always begins on a clean framing boundary.
                        val decoder = com.repository.listener.audio.OpusStreamDecoder()
                        opusDecoder = decoder
                        if (!decoder.initialize()) {
                            LogCollector.e(TAG, "RFCOMM Opus decoder init failed; audio frames will not be decoded")
                        }

                        // Read loop: read length-prefixed Opus frames, decode to PCM
                        val dis = java.io.DataInputStream(java.io.BufferedInputStream(input))
                        var rfcommFrameCount = 0L
                        var rfcommTotalBytes = 0L
                        var consecutiveDecodeNulls = 0
                        var lastDesyncLogMs = 0L
                        while (true) {
                            // Read 2-byte LE frame length
                            val lo = dis.read()
                            if (lo < 0) break
                            val hi = dis.read()
                            if (hi < 0) break
                            val frameLen = (hi shl 8) or lo

                            // Validate frame length before trusting it. A valid opus frame at
                            // 16kHz/20ms is small (tens to low-hundreds of bytes); > 4096 is
                            // never legit -- the byte stream is misaligned, and no amount of
                            // skipping/reinit realigns a mid-stream desync, so the fastest
                            // recovery is to break and force a clean socket reconnect (the
                            // glasses re-handshake re-aligns the framing). This is NOT the
                            // restart_audio path; it just drops the corrupted client socket.
                            if (frameLen == 0 || frameLen > 4096) {
                                LogCollector.e(TAG, "[Audio] desync: bad frameLen=$frameLen, resetting decoder and reconnecting")
                                decoder.reinitialize()
                                break
                            }

                            // The glasses encoder runs libopus with DTX enabled: during
                            // silence it legally emits 1-2 byte frames (TOC-only). Those are
                            // VALID framing -- consume the payload to stay aligned, count it
                            // as liveness, and skip the decode (it carries no audio). Tearing
                            // the socket down here (the old frameLen<3 check) caused a
                            // teardown/reconnect storm during every silent stretch.
                            if (frameLen < 3) {
                                dis.readFully(ByteArray(frameLen))
                                rfcommFrameCount++
                                rfcommTotalBytes += 2 + frameLen
                                lastGlassesAudioTimestamp = android.os.SystemClock.elapsedRealtime()
                                continue
                            }

                            // Read Opus frame
                            val opusFrame = ByteArray(frameLen)
                            dis.readFully(opusFrame)
                            rfcommFrameCount++
                            rfcommTotalBytes += 2 + frameLen
                            // FIX 3a: liveness is "audio bytes arriving", not "decode
                            // succeeded". Update on receipt, before decode, so a run of
                            // decode-nulls (stateful codec wedge) cannot masquerade as a
                            // dead mic and trigger the stall watchdog's restart_audio.
                            lastGlassesAudioTimestamp = android.os.SystemClock.elapsedRealtime()

                            // Decode to PCM
                            val pcmSamples = decoder.decode(opusFrame)
                            if (pcmSamples != null && pcmSamples.isNotEmpty()) {
                                feedGlassesAudioFromMic(pcmSamples)
                                consecutiveDecodeNulls = 0
                            } else {
                                // FIX 2: a stateful MediaCodec can wedge and return null
                                // for every following frame even once bytes realign. After
                                // a run of nulls, reinit the decoder in place (no socket
                                // teardown) so it can resume producing PCM.
                                consecutiveDecodeNulls++
                                if (consecutiveDecodeNulls >= 10) {
                                    val nowMs = android.os.SystemClock.elapsedRealtime()
                                    if (nowMs - lastDesyncLogMs > 1000L) {
                                        LogCollector.w(TAG, "[Audio] decoder wedged ($consecutiveDecodeNulls consecutive nulls), reinitializing")
                                        lastDesyncLogMs = nowMs
                                    }
                                    decoder.reinitialize()
                                    consecutiveDecodeNulls = 0
                                }
                            }

                            if (rfcommFrameCount <= 10 || rfcommFrameCount % 200 == 0L) {
                                val decodeStatus = when {
                                    pcmSamples == null -> "null"
                                    pcmSamples.isEmpty() -> "empty"
                                    else -> "${pcmSamples.size}samples"
                                }
                                val tpActive = teleprompterTrackingActive
                                val tsClient = transcriberStreamClient
                                val tsActive = tsClient?.isActive() ?: false
                                LogCollector.d(TAG, "RFCOMM Opus #$rfcommFrameCount: ${frameLen}B decode=$decodeStatus tp=$tpActive ts=${if (tsClient != null) "yes(active=$tsActive)" else "null"} total=$rfcommTotalBytes")
                            }
                        }
                        decoder.release()
                        opusDecoder = null
                        LogCollector.i(TAG, "BT audio client disconnected")
                        // Fully close the dead client socket so the RFCOMM channel/MCB is
                        // released; otherwise the glasses' next connect hits "already opened
                        // state:2". Then back off briefly before re-accept to avoid a tight spin.
                        try { socket.close() } catch (_: Exception) {}
                        btAudioClientSocket = null
                        if (btAudioServerSocket == null) break // server stopped
                        try { Thread.sleep(AUDIO_REACCEPT_BACKOFF_MS) } catch (_: InterruptedException) {}
                    } catch (e: Exception) {
                        // Read==-1 / IO error: release the decoder for this dead connection so
                        // the next accept builds a fresh one (no JNI/MediaCodec leak), then fully
                        // close the dead client socket to release the RFCOMM channel/MCB before
                        // looping back to accept(), then back off so we can't tight-spin
                        // re-accepting between glasses reconnect attempts.
                        opusDecoder?.let { try { it.release() } catch (_: Exception) {} }
                        opusDecoder = null
                        try { btAudioClientSocket?.close() } catch (_: Exception) {}
                        btAudioClientSocket = null
                        if (btAudioServerSocket == null) break // server stopped
                        LogCollector.i(TAG, "BT audio client lost: ${e.message}, waiting for reconnect...")
                        try { Thread.sleep(AUDIO_REACCEPT_BACKOFF_MS) } catch (_: InterruptedException) {}
                        // Continue accept loop to wait for glasses reconnection
                    }
                }
            }, "BtAudioServer").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "BT audio server start failed: ${e.message}")
        }
    }

    private fun stopBtAudioServer() {
        try { btAudioClientSocket?.close() } catch (_: Exception) {}
        try { btAudioServerSocket?.close() } catch (_: Exception) {}
        btAudioServerSocket = null
        btAudioClientSocket = null
        btAudioThread = null
    }

    private fun startGlassesAudioStreamServer() {
        Thread({
            try {
                val server = java.net.ServerSocket(5050)
                glassesAudioStreamServer = server
                LogCollector.i(TAG, "Glasses audio stream server listening on port 5050")
                while (true) {
                    val client = server.accept()
                    client.tcpNoDelay = true
                    LogCollector.i(TAG, "Glasses audio stream client connected: ${client.inetAddress.hostAddress}")
                    glassesAudioStreamOut = client.getOutputStream()
                    try {
                        // Block until client disconnects
                        while (client.getInputStream().read() != -1) { /* keep alive */ }
                    } catch (_: Exception) {}
                    glassesAudioStreamOut = null
                    LogCollector.i(TAG, "Glasses audio stream client disconnected")
                }
            } catch (e: Exception) {
                if (glassesAudioStreamServer != null) {
                    LogCollector.e(TAG, "Glasses audio stream server error: ${e.message}")
                }
            }
        }, "GlassesAudioStream").apply { isDaemon = true; start() }
    }

    private fun stopGlassesAudioStreamServer() {
        glassesAudioStreamOut = null
        try { glassesAudioStreamServer?.close() } catch (_: Exception) {}
        glassesAudioStreamServer = null
    }

    private fun feedGlassesAudioFromMic(samples: ShortArray) {
        lastGlassesAudioTimestamp = android.os.SystemClock.elapsedRealtime()

        // Archive audio for recordings feature (receives all audio regardless of state)
        glassesAudioArchiver?.onAudioChunk(samples)

        // Stream to PC if connected (for training data collection)
        val streamOut = glassesAudioStreamOut
        if (streamOut != null) {
            try {
                val pcm = java.nio.ByteBuffer.allocate(samples.size * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                pcm.asShortBuffer().put(samples)
                streamOut.write(pcm.array())
                streamOut.flush()
            } catch (_: Exception) {
                glassesAudioStreamOut = null
            }
        }

        // Teleprompter speech tracking: feed audio to transcriber for position matching
        if (teleprompterTrackingActive) {
            val client = transcriberStreamClient
            if (client != null) {
                client.feedAudio(samples)
            } else if (tpFeedLogCount++ % 200 == 0L) {
                LogCollector.e(TAG, "TP feed: transcriberStreamClient is null!")
            }
            return
        }

        // Telegram voice mode: route audio to transcriber for VOSK partials + accumulate
        if (telegramVoiceMode) {
            if (notifReplyAzure) {
                // Notification reply on the Azure path: stream PCM straight to the
                // Azure recognizer, which owns segmentation/endpointing. SKIP the
                // local Silero VAD end-of-speech (double-VADing cut utterances off
                // before Azure could finalize them -- mirror the translation path).
                val pcmBytes = java.nio.ByteBuffer.allocate(samples.size * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .also { for (s in samples) it.putShort(s) }
                    .array()
                azureNotifReplySession?.pushPcm(pcmBytes)
            } else {
                // Buffered on BOTH paths: in local mode this buffer is what the
                // failure fallback batch-transcribes, so the gate answers true
                // for local as well as remote. It is consulted rather than
                // assumed so that a future change which stops buffering has to
                // go through the gate -- and through the test that pins it.
                if (glassesSttGate.shouldBufferPcm()) {
                    synchronized(telegramVoiceAudioBuffer) {
                        telegramVoiceAudioBuffer.add(samples.copyOf())
                    }
                }
                // In local mode the glasses own endpointing as well as
                // recognition. Running the phone's VAD too would finalize the
                // session here AND let the glasses deliver their own final,
                // sending the same utterance twice.
                if (!glassesSttGate.shouldFeedPhoneVad()) return
                transcriberStreamClient?.feedAudio(samples)

                // VAD for end-of-speech detection
                val floatSamples = FloatArray(samples.size) { samples[it] / 32768f }
                val prob = glassesVadEngine?.processChunk(floatSamples) ?: 0f
                if (prob > AppConfig.VAD_THRESHOLD) {
                    telegramVoiceLastSpeechTime = System.currentTimeMillis()
                    telegramVoiceSpeechDetected = true
                }

                val now = System.currentTimeMillis()
                val silence = now - telegramVoiceLastSpeechTime
                if (telegramVoiceSpeechDetected && silence >= AppConfig.SILENCE_THRESHOLD_MS) {
                    LogCollector.i(TAG, "Telegram voice: end of speech detected (silence=${silence}ms)")
                    finishTelegramVoiceRecording()
                }
            }
            return
        }

        // Translation mode: bypass all VAD/wake-word/state-machine processing.
        // VAD is server-side on the transcriber.
        if (translationMode) {
            if (translationAudioSource != "glasses") return
            val pcmBytes = java.nio.ByteBuffer.allocate(samples.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .also { for (s in samples) it.putShort(s) }
                .array()
            // Debug: count mic chunks reaching the translation path so we can
            // distinguish "mic dead" from "Azure dead" when the tab is empty.
            translationMicChunkCount++
            translationMicByteCount += pcmBytes.size.toLong()
            val now = System.currentTimeMillis()
            if (translationMicLastLogMs == 0L) translationMicLastLogMs = now
            if (now - translationMicLastLogMs >= 1000L) {
                var maxAmp = 0
                for (s in samples) {
                    val a = if (s < 0) -s.toInt() else s.toInt()
                    if (a > maxAmp) maxAmp = a
                }
                LogCollector.i(TAG, "translation mic[1s]: chunks=$translationMicChunkCount bytes=$translationMicByteCount maxAmp=$maxAmp provider=$translationProvider azureSessionAlive=${azureTranslationSession?.isRunning ?: false} gate(pushed=$azureGatedBytesPushed dropped=$azureGatedBytesDropped speech=$azureSpeechActive)")
                translationMicChunkCount = 0
                translationMicByteCount = 0
                translationMicLastLogMs = now
                azureGatedBytesPushed = 0
                azureGatedBytesDropped = 0
            }
            if (translationProvider == "azure") {
                val sess = azureTranslationSession
                if (sess == null || !sess.isRunning) {
                    if (now - translationMicLastWarnMs >= 1000L) {
                        LogCollector.w(TAG, "translation mic: provider=azure but session is null/not-running -- audio dropped")
                        translationMicLastWarnMs = now
                    }
                } else {
                    // Stream PCM straight to Azure -- its internal segmenter
                    // (Speech_SegmentationSilenceTimeoutMs=1500ms) handles
                    // start/end-of-speech far better than our local Silero VAD,
                    // and double-VADing was cutting utterances off before Azure
                    // could finalize them.
                    sess.pushPcm(pcmBytes)
                    azureGatedBytesPushed += pcmBytes.size
                    // In two-way mode the spectrogram shows inward mic audio
                    // (broadcastTwoWayAudioLevel called from onGlassesInwardAudioData).
                    if (!translationTwoWay) broadcastTwoWayAudioLevel(samples)
                }
            } else {
                orchestratorClient.sendTranscribeAudioFrame(pcmBytes)
            }
            return
        }

        // Log glasses audio stats (every ~1s = every 8 chunks at 2048 samples/16kHz)
        glassesAudioChunkCount++
        if (glassesAudioChunkCount % 8 == 0L) {
            var maxAmp = 0
            var sumSq = 0.0
            var clipped = 0
            for (s in samples) {
                val abs = kotlin.math.abs(s.toInt())
                if (abs > maxAmp) maxAmp = abs
                if (abs >= 32000) clipped++
                val norm = s.toFloat() / 32768f
                sumSq += norm * norm
            }
            val rms = kotlin.math.sqrt(sumSq / samples.size).toFloat()
            LogCollector.d(TAG, "GLASSES MIC: rms=%.4f maxAmp=%d clipped=%d state=%s chunks=%d".format(
                rms, maxAmp, clipped, glassesAudioState.name, glassesAudioChunkCount))
        }

        when (glassesAudioState) {
            GlassesAudioState.IDLE -> {
                trackGlassesIdleAmplitude(samples)
                if (speakerVerifyBuffering) {
                    // Buffer audio for Telegram speaker verification; wake word
                    // detection against glasses audio is disabled (Phase 2:
                    // on-glasses WakeWordPipeline is authoritative).
                    synchronized(glassesAudioBuffer) {
                        glassesAudioBuffer.add(samples.copyOf())
                    }
                }
                // else: drop the chunk. The phone no longer runs WakeWordDetector
                // against glasses audio -- the glasses side runs its own detector
                // and fires CH_WAKE_EVENT, which handleGlassesWakeEvent consumes.
                // Feeding the detector here would be wasted work and could cause
                // spurious double-wake races with the authoritative glasses side.
            }
            GlassesAudioState.LISTENING -> {
                // Buffered on BOTH paths. In local mode this buffer is the
                // fallback: on a local failure it is what gets batch-transcribed,
                // so the gate answers true for local as well as remote.
                if (glassesSttGate.shouldBufferPcm()) {
                    synchronized(glassesAudioBuffer) {
                        glassesAudioBuffer.add(samples.copyOf())
                    }
                }
                // In local mode the glasses own both recognition AND endpointing.
                // Feeding the phone's VAD here would give a second opinion about
                // when the wearer stopped, and it would finalize a session the
                // glasses still own.
                if (!glassesSttGate.shouldFeedPhoneVad()) return
                // Feed to transcriber stream for server-side live transcription
                transcriberStreamClient?.feedAudio(samples)
                if (glassesAudioState != GlassesAudioState.LISTENING) return

                val floatSamples = FloatArray(samples.size) { samples[it] / 32768f }
                val prob = glassesVadEngine?.processChunk(floatSamples) ?: 0f

                glassesVadChunkCount++
                if (glassesVadChunkCount % 16 == 0) {
                    val silence = System.currentTimeMillis() - glassesLastSpeechTime
                    LogCollector.d(TAG, "GLASSES VAD prob=%.3f silence=%dms".format(prob, silence))
                }

                if (prob > AppConfig.VAD_THRESHOLD) {
                    glassesLastSpeechTime = System.currentTimeMillis()
                    if (!glassesSpeechDetected) {
                        glassesSpeechDetected = true
                        // Speech detected -- cancel no-speech watchdog
                        glassesNoSpeechWatchdog?.let { mainHandler.removeCallbacks(it) }
                        glassesNoSpeechWatchdog = null
                    }
                }

                val now = System.currentTimeMillis()
                val elapsed = now - glassesRecordingStartTime
                val silence = now - glassesLastSpeechTime

                if (silence >= AppConfig.SILENCE_THRESHOLD_MS && elapsed > AppConfig.MIN_RECORDING_MS) {
                    LogCollector.i(TAG, "GLASSES FINISH: silence=${silence}ms elapsed=${elapsed}ms")
                    startGlassesConfirmDelay()
                }
                if (elapsed >= AppConfig.MAX_RECORDING_SECONDS * 1000L) {
                    LogCollector.i(TAG, "Glasses max recording duration reached")
                    startGlassesConfirmDelay()
                }
            }
            GlassesAudioState.CONFIRMING, GlassesAudioState.SENDING, GlassesAudioState.RESPONDING -> {
                // Word-based cancel/interrupt detection removed
            }
        }
    }

    // --- WakeWordDetector.WakeWordListener ---

    /**
     * Delegate a phone-side wake event to the glasses: activate glasses, reset their
     * audio buffer/VAD/timestamps and put them into LISTENING. Caller stays IDLE.
     * Only valid when phoneBtHost.isConnected.
     */
    private fun delegatePhoneWakeToGlasses(reason: String) {
        phoneBtHost.activateWithEnsure(healthMonitor)
        synchronized(glassesAudioBuffer) { glassesAudioBuffer.clear() }
        glassesVadEngine?.reset()
        val now = System.currentTimeMillis()
        glassesLastSpeechTime = now
        glassesRecordingStartTime = now
        glassesSpeechDetected = false
        glassesVadChunkCount = 0
        setGlassesState(GlassesAudioState.LISTENING, "wake word ($reason)")
        LogCollector.i(TAG, ">>> CONVERSATION STARTED via GLASSES MIC ($reason)")
        resetWakeConflictState()
    }

    /**
     * Phone-side handler for CH_WAKE_EVENT from glasses. This is the sole
     * activation entrypoint for glasses wake-ups -- the phone does not run a
     * WakeWordDetector against the glasses audio stream. The glasses run
     * WakeWordPipeline locally and fire CH_WAKE_EVENT; we translate that into
     * the same session-start flow a phone-local wake would trigger.
     */
    private fun handleGlassesWakeEvent(confidence: Float, epochNanos: Long) {
        // Gate: ignore if no orchestrator (same rule as onWakeWordDetected).
        if (!orchestratorClient.isConnected) {
            LogCollector.i(TAG, "Glasses wake event ignored - orchestrator not connected (conf=$confidence)")
            return
        }
        // De-dup: if either side has already moved past IDLE the session is
        // live and we'd double-activate; skip. Concurrent phone-mic wake + glasses
        // wake is possible when both users are near the phone and the phone mic
        // picks up the hotword before the glasses event arrives.
        if (state != State.IDLE) {
            LogCollector.i(TAG, "Glasses wake event ignored - phone state=$state (conf=$confidence)")
            return
        }
        if (glassesAudioState != GlassesAudioState.IDLE) {
            LogCollector.i(TAG, "Glasses wake event ignored - glasses already in $glassesAudioState (conf=$confidence)")
            return
        }
        if (!phoneBtHost.isConnected) {
            LogCollector.i(TAG, "Glasses wake event arrived but BT disconnected; dropping (conf=$confidence)")
            return
        }
        LogCollector.i(TAG, "Glasses wake event: conf=$confidence epochNanos=$epochNanos")
        delegatePhoneWakeToGlasses("glasses-side detector conf=${"%.2f".format(confidence)}")
    }

    /**
     * Called from the sync ProgressListener.onSyncComplete callback.
     *
     * GlassesSyncClient writes pulled files into its rootDir (getExternalFilesDir(PICTURES)/Repository).
     * LocalOpusWriter on glasses files them under `audio-archive/<yyyy-MM-dd>/rec_<epoch>.opus`, so after
     * a pull we find those same paths under the phone-side rootDir. Move every completed .opus file into
     * getExternalFilesDir("glasses-archive")/<yyyy-MM-dd>/rec_<epoch>.opus and delete the source so
     * LocalManifest won't re-emit it on the next handshake. If a destination file already exists (re-pull
     * races, etc.) we overwrite it. Errors per file are swallowed so one bad file doesn't block the rest.
     *
     * No decoding happens here -- that's a future ASR/archival concern.
     */
    private fun ingestGlassesAudioArchive() {
        val syncRoot = File(
            getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: filesDir,
            "Repository"
        )
        val archiveSrcRoot = File(syncRoot, "audio-archive")
        if (!archiveSrcRoot.isDirectory) {
            LogCollector.d("GlassesFileSync", "ingestGlassesAudioArchive: no audio-archive dir (${archiveSrcRoot.absolutePath})")
            return
        }
        val archiveDstRoot = getExternalFilesDir("glasses-archive")
        if (archiveDstRoot == null) {
            LogCollector.w("GlassesFileSync", "ingestGlassesAudioArchive: getExternalFilesDir(\"glasses-archive\") returned null")
            return
        }
        if (!archiveDstRoot.exists()) archiveDstRoot.mkdirs()
        val audioFileManager = com.repository.listener.audio.AudioFileManager(this)
        var moved = 0
        var failed = 0
        var transcoded = 0
        var transcodeFailed = 0
        // Backfill: any .opus already sitting in glasses-archive/ from previous syncs that doesn't yet
        // have a corresponding glasses_<epoch>.aac in audio_recordings/ (e.g. earlier deploys without
        // the transcode step) gets transcoded now. ingestOpusFromGlasses is idempotent.
        archiveDstRoot.listFiles()?.forEach { dayDir ->
            if (!dayDir.isDirectory) return@forEach
            dayDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".opus")) {
                    if (audioFileManager.ingestOpusFromGlasses(f)) transcoded++ else transcodeFailed++
                }
            }
        }
        val dayDirs = archiveSrcRoot.listFiles() ?: run {
            if (transcoded > 0 || transcodeFailed > 0) {
                LogCollector.i("GlassesFileSync", "ingestGlassesAudioArchive: backfill transcoded=$transcoded transcodeFailed=$transcodeFailed (no fresh syncRoot)")
            }
            return
        }
        for (dayDir in dayDirs) {
            if (!dayDir.isDirectory) continue
            val dstDayDir = File(archiveDstRoot, dayDir.name)
            if (!dstDayDir.exists()) dstDayDir.mkdirs()
            val files = dayDir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".opus")) continue
                val dst = File(dstDayDir, f.name)
                try {
                    if (dst.exists()) dst.delete()
                    if (f.renameTo(dst)) {
                        moved++
                    } else {
                        // renameTo can fail across filesystems; fall back to copy+delete.
                        f.inputStream().use { ins ->
                            dst.outputStream().use { outs -> ins.copyTo(outs) }
                        }
                        if (!f.delete()) {
                            LogCollector.w("GlassesFileSync", "ingestGlassesAudioArchive: copy ok but source delete failed: ${f.absolutePath}")
                        }
                        moved++
                    }
                    // Surface the recording in the phone's audio_recordings list. Transcode opus to the
                    // same ADTS AAC format phone-recorded sessions use, so listRecordings() picks it up
                    // without UI changes. The original .opus stays in glasses-archive as backup.
                    if (audioFileManager.ingestOpusFromGlasses(dst)) {
                        transcoded++
                    } else {
                        transcodeFailed++
                    }
                } catch (e: Exception) {
                    failed++
                    LogCollector.w("GlassesFileSync", "ingestGlassesAudioArchive: ${f.name} failed: ${e.message}")
                }
            }
            // Best-effort cleanup of empty day dirs.
            val remaining = dayDir.listFiles()
            if (remaining == null || remaining.isEmpty()) {
                dayDir.delete()
            }
        }
        if (moved > 0 || failed > 0 || transcoded > 0 || transcodeFailed > 0) {
            LogCollector.i("GlassesFileSync", "ingestGlassesAudioArchive: moved=$moved failed=$failed transcoded=$transcoded transcodeFailed=$transcodeFailed dst=${archiveDstRoot.absolutePath}")
        }
    }

    override fun onWakeWordDetected() {
        if (state != State.IDLE) return

        // Gate: don't activate without orchestrator WebSocket connection
        if (!orchestratorClient.isConnected) {
            PipelineTracer.onOrchestratorGated()
            LogCollector.i(TAG, "Wake word ignored - orchestrator not connected")
            return
        }

        LogCollector.i(TAG, "Wake word detected")

        // Compute amplitude for wake conflict resolution
        phoneWakeAmplitude = computeAvgRms()
        phoneWakeTimestamp = System.currentTimeMillis()
        LogCollector.i(TAG, "Phone wake amplitude: $phoneWakeAmplitude")

        // Rule 1: glasses screen ON + connected = delegate to glasses entirely
        if (isGlassesScreenOn && phoneBtHost.isConnected) {
            LogCollector.i(TAG, "Glasses screen ON - delegating to glasses")
            delegatePhoneWakeToGlasses("phone wake, screen ON")
            return  // phone stays IDLE, no overlay, no sound
        }

        // Rule 1.5: phone AI triggering disabled by user
        if (!AppConfig.getPhoneAiTriggerEnabled(this)) {
            if (phoneBtHost.isConnected) {
                LogCollector.i(TAG, "Phone AI trigger disabled - delegating to glasses")
                delegatePhoneWakeToGlasses("phone trigger disabled")
            } else {
                LogCollector.i(TAG, "Phone AI trigger disabled and glasses offline - ignoring wake word")
                resetWakeConflictState()
            }
            return
        }

        // Rule 2: glasses screen OFF - phone takes the session
        state = State.ACTIVATING

        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
        vadEngine.reset()
        wakeWordDetector.setMode(WakeWordDetector.Mode.CANCEL)
        wakeWordDetector.reset()

        val now = System.currentTimeMillis()
        lastSpeechTime = now
        recordingStartTime = now
        speechDetected = false
        vadChunkCount = 0

        state = State.LISTENING
        startTranscriberStream(false)
        startPromptSpeakerVerification(false)
        LogCollector.i(TAG, ">>> CONVERSATION STARTED via PHONE MIC (amp=$phoneWakeAmplitude)")
        overlay.show()
        activatePlayer?.let {
            if (it.isPlaying) it.stop()
            it.seekTo(0)
            it.start()
        }
        updateNotification("Recording...")
        broadcastState("LISTENING", "Wake word detected - recording")

        // Check if glasses also detected wake word within conflict window
        if (glassesWakeTimestamp > 0 &&
            phoneWakeTimestamp - glassesWakeTimestamp <= WAKE_COMPARISON_WINDOW_MS) {
            resolveWakeConflict()
        }
    }

    // --- Transcriber stream helpers ---

    private fun startTranscriberStream(isGlasses: Boolean, sourceLang: String? = null) {
        // The glasses announced they are recognising this session themselves, so
        // opening the stream would transcribe the same sentence twice and race
        // two finals to deliver it.
        if (isGlasses && !glassesSttGate.shouldOpenTranscriberStream()) {
            LogCollector.i(TAG, "glasses STT is local; not opening a transcriber stream")
            return
        }
        val url = transcriberUrl ?: return
        val key = AppConfig.getApiKey(this)
        val sttProvider = AppConfig.getSttProvider(this)
        val sttLanguage = sourceLang ?: AppConfig.getSttLanguage(this)
        transcriberStreamClient?.release()
        transcriberStreamClient = TranscriberStreamClient(url, key).also { client ->
            client.start(object : TranscriberStreamClient.Listener {
                override fun onPartial(text: String) {
                    if (!isGlasses) return
                    // Local mode is finals-only: a partial there would be a full
                    // re-decode and the live preview would visibly jump.
                    if (!glassesSttGate.shouldForwardPartials()) return
                    val forward = glassesAudioState == GlassesAudioState.LISTENING || telegramVoiceMode
                    LogCollector.i(TAG, "[PARTIAL] onPartial len=${text.length} forward=$forward tgVoice=$telegramVoiceMode state=$glassesAudioState text='${text.take(40)}'")
                    if (forward) {
                        phoneBtHost.sendGlassesPartialText(text)
                    }
                }
                override fun onFinal(text: String) {
                    LogCollector.i(TAG, "Transcriber stream final: '$text'")
                }
                override fun onError(message: String) {
                    LogCollector.e(TAG, "Transcriber stream error: $message")
                }
            }, sttLanguage, sttProvider)
        }
    }

    private fun stopTranscriberStream() {
        transcriberStreamClient?.stop()
    }

    // --- Finish recording ---

    private fun finishRecording() {
        if (state == State.FINISHING) return
        state = State.FINISHING

        wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
        wakeWordDetector.reset()
        stopTranscriberStream()

        // Save chunks for fallback before clearing
        val savedChunks: List<ShortArray>
        synchronized(audioBuffer) {
            savedChunks = audioBuffer.toList()
            audioBuffer.clear()
        }

        if (!speechDetected) {
            LogCollector.i(TAG, "No speech detected by VAD, skipping transcription")
            mainHandler.post { dismiss() }
            return
        }

        // Gate on prompt-level speaker verification
        promptVerificationJob?.cancel()
        promptVerificationJob = null
        if (AppConfig.PROMPT_SPEAKER_VERIFICATION_ENABLED && AppConfig.SPEAKER_VERIFICATION_ENABLED
            && ::speakerVerifier.isInitialized && !promptSpeakerVerified) {
            LogCollector.i(TAG, "Prompt speaker NOT verified (similarity=${"%.4f".format(promptSpeakerSimilarity)}) - cancelling")
            PipelineTracer.onSpeakerRejected(promptSpeakerSimilarity, AppConfig.SPEAKER_SIMILARITY_THRESHOLD, "phone-prompt")
            mainHandler.post { dismiss() }
            return
        }

        // Use transcriber stream's final text (already transcribed server-side in real-time)
        val streamText = transcriberStreamClient?.getLastFinalText() ?: ""

        overlay.startFinishing()
        updateNotification("Processing...")
        broadcastState("FINISHING", "Processing...")

        serviceScope.launch {
            try {
                // Fall back to batch transcription if stream produced nothing
                val rawText = streamText.ifBlank {
                    LogCollector.i(TAG, "Stream text empty, falling back to batch transcription")
                    val totalSamples = savedChunks.sumOf { it.size }
                    val pcmBytes = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (chunk in savedChunks) {
                        for (s in chunk) pcmBytes.putShort(s)
                    }
                    run {
                        phoneBtHost.setActiveSession("transcription_pending")
                        try {
                            transcriptionClient.transcribe(pcmBytes.array(), AppConfig.getSttProvider(this@ListenerService), AppConfig.getSttLanguage(this@ListenerService)) ?: ""
                        } finally {
                            phoneBtHost.clearActiveSession("transcription_pending")
                        }
                    }
                }
                val text = stripWakeWords(rawText)
                if (text.isNotBlank()) {
                    LogCollector.i(TAG, "Sending transcribed text: $text")
                    broadcastChatMessage("phone", "USER", text)
                    val model = AppConfig.getModel(this@ListenerService)
                    val userSystemPrompt = AiContextBuilder.build(
                        AppConfig.getUserSystemPrompt(this@ListenerService).ifBlank { null },
                        notificationHistory, cachedTodos
                    )
                    requestSentTimestamp = System.currentTimeMillis()
                    val sent = orchestratorClient.sendRequest(text, model = model, userSystemPrompt = userSystemPrompt)

                    if (sent) {
                        if (state != State.FINISHING && state != State.RESPONDING) {
                            return@launch // aborted by touch while sending
                        }
                        // Transition to RESPONDING (may already be set by onResponse
                        // if the server responded before this coroutine resumed)
                        state = State.RESPONDING
                        wakeWordDetector.setMode(WakeWordDetector.Mode.TTS_INTERRUPT)
                        wakeWordDetector.reset()
                        updateNotification("Waiting for response...")
                        broadcastState("RESPONDING", "Waiting for response")
                        LogCollector.i(TAG, "State -> RESPONDING")
                    } else {
                        LogCollector.e(TAG, "Failed to send request to orchestrator after retries")
                        broadcastChatMessage("phone", "SYSTEM", "Connection lost - session reset")
                        sendBroadcast(Intent(ACTION_SESSION_RESET).apply {
                            setPackage(packageName)
                        })
                        mainHandler.post { dismiss() }
                    }
                } else {
                    LogCollector.i(TAG, "Transcription empty after wake word filter (raw='$rawText')")
                    mainHandler.post { dismiss() }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Transcription/send error: ${e.message}")
                mainHandler.post { dismiss() }
            }
        }
    }

    private fun cancelByTouch() {
        when (state) {
            State.ACTIVATING, State.LISTENING -> {
                LogCollector.i(TAG, "Cancel by touch during recording")
                stopPromptSpeakerVerification()
                state = State.IDLE
                overlay.hide()
                synchronized(audioBuffer) {
                    audioBuffer.clear()
                }
                wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
                wakeWordDetector.reset()
                setIdleText("Waiting for command")
                broadcastState("IDLE", "Cancelled by touch")
            }
            State.FINISHING, State.RESPONDING -> {
                LogCollector.i(TAG, "Abort by touch during finishing/responding")
                val requestId = orchestratorClient.lastRequestId
                if (requestId != null) {
                    orchestratorClient.sendAbort(requestId)
                }
                dismiss()
            }
            else -> {}
        }
    }

    private fun dismiss() {
        if (state == State.IDLE) return
        LogCollector.i(TAG, "Dismissing, back to idle")

        mainHandler.removeCallbacks(ttsTimeoutRunnable)
        stopPromptSpeakerVerification()
        ttsPlayer.interrupt()
        stopTranscriberStream()
        state = State.IDLE
        overlay.hide()
        wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
        wakeWordDetector.reset()
        setIdleText("Waiting for command")
        broadcastState("IDLE", "Waiting for command")
    }


    // --- Wake word amplitude conflict resolution ---

    private fun trackIdleAmplitude(samples: ShortArray) {
        var sumSq = 0.0
        for (s in samples) {
            val f = s.toDouble()
            sumSq += f * f
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size).toFloat()
        recentRmsValues[rmsWriteIndex % recentRmsValues.size] = rms
        rmsWriteIndex++
    }

    private fun computeAvgRms(): Float {
        val count = minOf(rmsWriteIndex, recentRmsValues.size)
        if (count == 0) return 0f
        var sum = 0f
        for (i in 0 until count) sum += recentRmsValues[i]
        return sum / count
    }

    private fun resolveWakeConflict() {
        LogCollector.i(TAG, "Wake conflict: phone=$phoneWakeAmplitude glasses=$glassesWakeAmplitude")
        val glassesWin = glassesWakeAmplitude > phoneWakeAmplitude
        if (!glassesWin) {
            LogCollector.i(TAG, "Phone wins - dismissing glasses session")
            setGlassesState(GlassesAudioState.IDLE, "phone wins conflict")
            phoneBtHost.sendDismissSession()
        } else {
            LogCollector.i(TAG, "Glasses win - dismissing phone session")
            state = State.IDLE
            overlay.hide()
            synchronized(audioBuffer) { audioBuffer.clear() }
            wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
            wakeWordDetector.reset()
            setIdleText("Waiting for command")
            broadcastState("IDLE", "Glasses closer - session moved to glasses")
        }
        resetWakeConflictState()
    }

    private fun resetWakeConflictState() {
        phoneWakeAmplitude = 0f
        phoneWakeTimestamp = 0L
        glassesWakeAmplitude = 0f
        glassesWakeTimestamp = 0L
    }

    // --- Permission helpers ---

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-S uses location permission for BT scanning
        }
    }

    /**
     * Block until location permission is granted AND location services are enabled.
     * Also checks Bluetooth permissions and includes them in the prompt if missing.
     * Shows an overlay prompt if anything is missing, hides it once resolved.
     */
    private suspend fun awaitLocationEnabled() {
        if (hasLocationPermission() && isLocationEnabled() && hasBluetoothScanPermission()) return

        fun buildMessage(): String {
            val missing = mutableListOf<String>()
            if (!hasLocationPermission()) missing.add("Location permission")
            if (!isLocationEnabled()) missing.add("GPS")
            if (!hasBluetoothScanPermission()) missing.add("Bluetooth permission")
            return "Enable: ${missing.joinToString(", ")}"
        }

        val message = buildMessage()
        LogCollector.i(TAG, "Waiting for permissions: $message")
        mainHandler.post {
            overlay.showMessage(message)
            updateNotification(message)
        }

        while (!hasLocationPermission() || !isLocationEnabled() || !hasBluetoothScanPermission()) {
            delay(2000)
            // Update message in case some permissions were granted while waiting
            val updated = buildMessage()
            mainHandler.post { overlay.showMessage(updated) }
        }

        LogCollector.i(TAG, "All permissions available, proceeding")
        mainHandler.post {
            overlay.hideMessage()
            updateNotification("Scanning network...")
        }
    }

    // --- OrchestratorClient.MessageListener ---

    override fun onDeviceCommand(requestId: String, command: JSONObject) {
        val commandType = command.optString("type", "")
        val params = command.optJSONObject("params")
        val deviceType = command.optString("deviceType", if (requestId in glassesRequestIds) "glasses" else "phone")
        val isGlasses = deviceType == "glasses" && phoneBtHost.isConnected
        LogCollector.i(TAG, "Device command: $commandType (requestId=$requestId, device=$deviceType)")

        // Commands that target glasses hardware: relay to glasses, wait for BT response
        val glassesOnlyCommands = setOf("record_video", "record_ar_screen", "capture_raw", "clear_raw", "start_translation", "stop_translation", "list_storage", "diag_ar", "diag_screen_record", "diag_screen_record_v2", "diag_screenrecord_cmd", "diag_wake_screenrecord", "diag_mix_record_trace", "stop_recording")
        if (isGlasses && commandType in glassesOnlyCommands) {
            relayCommandToGlasses(requestId, command)
            return
        }

        when (commandType) {
            "get_geolocation" -> {
                locationProvider.getCurrentLocation { locationData ->
                    if (locationData != null) {
                        orchestratorClient.sendDeviceResponse(
                            requestId, "get_geolocation", data = locationData
                        )
                    } else {
                        orchestratorClient.sendDeviceResponse(
                            requestId, "get_geolocation",
                            text = "Failed to get location"
                        )
                    }
                }
            }
            "take_photo" -> {
                if (isGlasses) {
                    relayCommandToGlasses(requestId, command)
                } else {
                    cameraCapturer.capture { base64 ->
                        orchestratorClient.sendDeviceResponse(
                            requestId, "take_photo", imageBase64 = base64
                        )
                    }
                }
            }
            "record_audio" -> {
                if (isGlasses) {
                    relayCommandToGlasses(requestId, command)
                } else {
                    val duration = params?.optInt("duration_seconds", 10) ?: 10
                    audioToolRecorder.record(duration) { result ->
                        if (result != null) {
                            orchestratorClient.sendDeviceResponse(
                                requestId, "record_audio", data = result
                            )
                        } else {
                            orchestratorClient.sendDeviceResponse(
                                requestId, "record_audio",
                                text = "Audio recording failed"
                            )
                        }
                    }
                }
            }
            "record_video", "record_ar_screen" -> {
                relayCommandToGlasses(requestId, command)
            }
            "start_translation" -> {
                relayCommandToGlasses(requestId, command)
                val params = command.optJSONObject("params") ?: JSONObject()
                translationFromLang = params.optString("from_language", "en")
                translationToLang = params.optString("to_language", "ru")
                translationFromNllb = LanguageUtils.resolveNllb(params.optString("from_nllb", ""), translationFromLang)
                translationToNllb = LanguageUtils.resolveNllb(params.optString("to_nllb", ""), translationToLang)
                translationFontSize = params.optInt("font_size", 14)
                translationAudioSource = params.optString("audio_source", "glasses")
                val requestedProvider = params.optString("provider", "default")
                translationTwoWay = params.optBoolean("two_way", false)
                AppConfig.setTranslationTwoWay(this, translationTwoWay)
                // Default to "default" until the chosen provider is actually up.
                // Audio frames check translationProvider, so flipping this flag
                // before the session exists drops audio into a null session.
                translationProvider = "default"
                translationSegmentId.set(1)
                translationMode = true
                broadcastTranslationState(true)
                systemAudioVadEngine = VadEngine(this).also { it.initialize() }
                glassesVadEngine?.reset()
                phoneBtHost.sendTranslationConfig(translationFromLang, translationToLang)
                phoneBtHost.sendTranslationConfigToApp(translationFromLang, translationToLang, translationFontSize, translationTwoWay)
                if (requestedProvider == "azure") {
                    // Only promote the provider flag after start succeeded.
                    if (startAzureTranslationSession()) {
                        translationProvider = "azure"
                    }
                } else {
                    translationProvider = requestedProvider
                    if (translationProvider.isNotEmpty() && translationProvider != "default") {
                        LogCollector.w(TAG, "Unknown translation provider: $translationProvider, falling back to default")
                        translationProvider = "default"
                    }
                    orchestratorClient.setTranscribeStreamListener(transcribeStreamListener)
                    orchestratorClient.connectTranscribeStream(
                        translationFromLang, translationToLang,
                        translationFromNllb, translationToNllb,
                        translate = true
                    )
                }
                if (translationAudioSource == "system") startSystemAudioCapture()
                pushTranslationState()
            }
            "switch_audio_source" -> {
                val params = command.optJSONObject("params") ?: JSONObject()
                val newSource = params.optString("audio_source", "glasses")
                translationAudioSource = newSource
                if (newSource == "system") startSystemAudioCapture() else stopSystemAudioCapture()
                if (translationProvider == "azure") {
                    azureTranslationSession?.switchAudioSource(newSource)
                }
                // Re-assert desired state so the glasses learn the source change (gates
                // whether they tap the far-party call downlink).
                pushTranslationState()
                LogCollector.i(TAG, "Translation audio source switched to: $newSource")
            }
            "stop_translation" -> {
                translationMode = false
                broadcastTranslationState(false)
                translationAudioSource = "glasses"
                systemSubSource = "playback"
                translationTwoWay = false
                glassesVadEngine?.reset()
                systemAudioVadEngine?.release()
                systemAudioVadEngine = null
                if (translationProvider == "azure") {
                    stopAzureTranslationSession()
                } else {
                    if (translationProvider.isNotEmpty() && translationProvider != "default") {
                        LogCollector.w(TAG, "Unknown translation provider: $translationProvider, falling back to default")
                    }
                    orchestratorClient.disconnectTranscribeStream()
                }
                translationProvider = "default"
                stopSystemAudioCapture()
                relayCommandToGlasses(requestId, command)
                pushTranslationState()
            }
            "capture_image" -> {
                if (isGlasses) {
                    relayCommandToGlasses(requestId, command)
                } else {
                    cameraCapturer.capture { base64 ->
                        orchestratorClient.sendDeviceResponse(
                            requestId, "capture_image", imageBase64 = base64
                        )
                    }
                }
            }
            "identify_person" -> {
                if (!phoneBtHost.isConnected) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "identify_person",
                        text = "Glasses not connected -- cannot capture photo for identification"
                    )
                    return
                }
                // Send tool status to glasses so TOOL message exists before thumbnail arrives
                phoneBtHost.sendToolStatus(requestId, "identify_person", "calling")
                val subRequestId = java.util.UUID.randomUUID().toString()
                pendingGlassesCallbacks[subRequestId] = { result ->
                    val imageBase64 = result.optString("imageBase64", "")
                    if (imageBase64.isEmpty()) {
                        phoneBtHost.sendToolStatus(requestId, "identify_person", "complete")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "identify_person",
                            text = "Failed to capture photo from glasses"
                        )
                    } else {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT)
                                val reidResult = reidAnalyticsClient?.searchByPhoto(imageBytes, threshold = 0.4f, limit = 1)
                                phoneBtHost.sendToolStatus(requestId, "identify_person", "complete")
                                if (reidResult != null) {
                                    orchestratorClient.sendDeviceResponse(
                                        requestId, "identify_person", data = reidResult
                                    )
                                } else {
                                    orchestratorClient.sendDeviceResponse(
                                        requestId, "identify_person",
                                        text = "ReID service unavailable or no face detected"
                                    )
                                }
                            } catch (e: Exception) {
                                LogCollector.e(TAG, "identify_person failed: ${e.message}")
                                phoneBtHost.sendToolStatus(requestId, "identify_person", "error")
                                orchestratorClient.sendDeviceResponse(
                                    requestId, "identify_person",
                                    text = "Identification failed: ${e.message}"
                                )
                            }
                        }
                    }
                }
                // Pass orchestrator requestId so glasses can attach thumbnail to correct TOOL message
                phoneBtHost.sendCommand("identify_capture", subRequestId, JSONObject().apply {
                    put("orchestratorRequestId", requestId)
                }.toString())
            }
            "lookup_person_info" -> {
                if (!com.repository.listener.BuildConfig.ENABLE_REID_OSINT) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "lookup_person_info",
                        text = "OSINT lookups are disabled"
                    )
                    return
                }
                val personId = params?.optString("person_id", "") ?: ""
                if (personId.isEmpty()) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "lookup_person_info",
                        text = "Missing person_id parameter"
                    )
                    return
                }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = reidAnalyticsClient?.searchPersonInfo(personId, "photo", "")
                        if (result != null) {
                            orchestratorClient.sendDeviceResponse(
                                requestId, "lookup_person_info", data = result
                            )
                        } else {
                            orchestratorClient.sendDeviceResponse(
                                requestId, "lookup_person_info",
                                text = "OSINT lookup failed or service unavailable"
                            )
                        }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "lookup_person_info failed: ${e.message}")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "lookup_person_info",
                            text = "OSINT lookup failed: ${e.message}"
                        )
                    }
                }
            }
            "setup_alarm" -> {
                val hour = params?.optInt("hour", -1) ?: -1
                val minutes = params?.optInt("minutes", -1) ?: -1
                if (hour < 0 || hour > 23 || minutes < 0 || minutes > 59) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "setup_alarm",
                        text = "Invalid time: hour must be 0-23, minutes must be 0-59"
                    )
                    return
                }
                val title = params?.optString("title", "") ?: ""
                try {
                    val triggerTime = AlarmStore.computeNextTrigger(hour, minutes)
                    val alarm = AlarmItem(
                        id = 0,
                        hour = hour,
                        minute = minutes,
                        title = title,
                        enabled = true,
                        triggerTimeMillis = triggerTime,
                        createdAt = System.currentTimeMillis()
                    )
                    val saved = AlarmStore.save(this, alarm)
                    val timeStr = String.format("%02d:%02d", hour, minutes)
                    val data = JSONObject().apply {
                        put("alarm_id", saved.id)
                        put("time", timeStr)
                        if (title.isNotEmpty()) put("title", title)
                        put("status", "created")
                    }
                    LogCollector.i(TAG, "Alarm set via AlarmManager: $timeStr $title")
                    sendBroadcast(Intent(ACTION_ALARM_CHANGED).apply { setPackage(packageName) })
                    orchestratorClient.sendDeviceResponse(requestId, "setup_alarm", data = data)
                } catch (e: Exception) {
                    LogCollector.e(TAG, "setup_alarm failed: ${e.message}")
                    orchestratorClient.sendDeviceResponse(
                        requestId, "setup_alarm",
                        text = "Failed to set alarm: ${e.message}"
                    )
                }
            }
            "delete_alarm" -> {
                val hour = params?.optInt("hour", -1) ?: -1
                val minutes = params?.optInt("minutes", -1) ?: -1
                val title = params?.optString("title", null)
                val hasTime = hour in 0..23 && minutes in 0..59
                val hasTitle = !title.isNullOrEmpty()
                if (!hasTime && !hasTitle) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "delete_alarm",
                        text = "At least one of hour+minutes or title must be provided"
                    )
                    return
                }
                try {
                    val alarm = when {
                        hasTime -> AlarmStore.findByTime(this, hour, minutes)
                        hasTitle -> AlarmStore.findByTitle(this, title!!)
                        else -> null
                    }
                    if (alarm == null) {
                        orchestratorClient.sendDeviceResponse(
                            requestId, "delete_alarm",
                            text = "No matching alarm found"
                        )
                        return
                    }
                    AlarmStore.delete(this, alarm.id)
                    val data = JSONObject().apply {
                        put("alarm_id", alarm.id)
                        if (hasTime) put("time", String.format("%02d:%02d", hour, minutes))
                        if (hasTitle) put("title", title)
                        put("status", "deleted")
                    }
                    LogCollector.i(TAG, "Alarm deleted: id=${alarm.id}")
                    sendBroadcast(Intent(ACTION_ALARM_CHANGED).apply { setPackage(packageName) })
                    orchestratorClient.sendDeviceResponse(requestId, "delete_alarm", data = data)
                } catch (e: Exception) {
                    LogCollector.e(TAG, "delete_alarm failed: ${e.message}")
                    orchestratorClient.sendDeviceResponse(
                        requestId, "delete_alarm",
                        text = "Failed to delete alarm: ${e.message}"
                    )
                }
            }
            "capture_screen" -> {
                if (!hasProjection) {
                    LogCollector.w(TAG, "Screen capture requested but no projection token available")
                    orchestratorClient.sendDeviceResponse(
                        requestId, "capture_screen",
                        text = "Screen capture not available - permission not granted"
                    )
                } else {
                    screenCapturer.capture { base64 ->
                        orchestratorClient.sendDeviceResponse(
                            requestId, "capture_screen", screenBase64 = base64
                        )
                    }
                }
            }
            "confirm" -> {
                orchestratorClient.sendDeviceResponse(requestId, "confirm", text = "yes")
            }
            "choose" -> {
                val options = command.optJSONArray("options")
                val firstOption = if (options != null && options.length() > 0) {
                    options.optString(0, "")
                } else {
                    ""
                }
                orchestratorClient.sendDeviceResponse(requestId, "choose", text = firstOption)
            }
            "network_scan" -> {
                val scanConfig = command.optJSONObject("scanConfig")
                LogCollector.i(TAG, "Starting network scan")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        awaitLocationEnabled()

                        val scanner = NetworkScanner(this@ListenerService)
                        val result = scanner.scan(scanConfig)
                        val resultJson = result.toJson().toString()
                        LogCollector.i(TAG, "Network scan complete, sending ${resultJson.length} bytes")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "network_scan", text = resultJson
                        )
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Network scan failed: ${e.message}")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "network_scan",
                            text = """{"error": "${e.message}"}"""
                        )
                    }
                }
            }
            "prepare_journey" -> {
                val transportType = params?.optString("transport_type", "") ?: ""
                if (transportType.isEmpty()) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "prepare_journey",
                        text = "Missing required parameter: transport_type (walking, car, bus, bicycle)"
                    )
                    return
                }
                val mode = parseTransportType(transportType)
                if (mode == null) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "prepare_journey",
                        text = "Invalid transport_type: $transportType. Use: walking, car, bus, bicycle"
                    )
                    return
                }
                resolveDestination(params) { destPoint ->
                    if (destPoint == null) {
                        orchestratorClient.sendDeviceResponse(
                            requestId, "prepare_journey",
                            text = "Could not resolve destination. Provide address or toLat+toLng."
                        )
                        return@resolveDestination
                    }
                    locationProvider.getCurrentLocation { locationJson ->
                        if (locationJson == null) {
                            orchestratorClient.sendDeviceResponse(
                                requestId, "prepare_journey",
                                text = "Could not get current location"
                            )
                            return@getCurrentLocation
                        }
                        val fromLat = locationJson.getDouble("lat")
                        val fromLng = locationJson.getDouble("lng")
                        val navManager = NavigationManager.getInstance(applicationContext)
                        navManager.prepareJourney(Point(fromLat, fromLng), destPoint, mode) { route ->
                            if (route == null) {
                                orchestratorClient.sendDeviceResponse(
                                    requestId, "prepare_journey",
                                    text = "Failed to build route"
                                )
                                return@prepareJourney
                            }
                            lastPrepareRequestId = requestId
                            val methodId = "${mode.name.lowercase()}_0"
                            val data = JSONObject().apply {
                                put("methodId", methodId)
                                put("mode", mode.name)
                                put("etaSeconds", route.etaSeconds)
                                put("etaFormatted", route.etaFormatted)
                                put("distanceMeters", route.distanceMeters)
                                put("distanceFormatted", route.distanceFormatted)
                                put("description", "${mode.name}: ${route.distanceFormatted}, ${route.etaFormatted}")
                                put("steps", navManager.routeInstructionsToJson(route))
                            }
                            orchestratorClient.sendDeviceResponse(
                                requestId, "prepare_journey", data = data
                            )
                        }
                    }
                }
            }
            "start_journey" -> {
                val navManager = NavigationManager.getInstance(applicationContext)
                if (navManager.getState() != NavigationManager.State.PLANNING) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "start_journey",
                        text = "No journey prepared. Call prepare_journey first."
                    )
                    return
                }
                if (requestId == lastPrepareRequestId) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "start_journey",
                        text = "You must present the journey details to the user and wait for their confirmation before starting. Do not call start_journey in the same turn as prepare_journey."
                    )
                    return
                }
                navManager.startPreparedJourney(
                    onError = { errorMsg ->
                        orchestratorClient.sendDeviceResponse(
                            requestId, "start_journey", text = errorMsg
                        )
                    }
                ) { sessionId, etaSeconds ->
                    val data = JSONObject().apply {
                        put("sessionId", sessionId)
                        put("etaSeconds", etaSeconds)
                    }
                    orchestratorClient.sendDeviceResponse(
                        requestId, "start_journey", data = data
                    )
                }
            }
            "stop_journey" -> {
                val navManager = NavigationManager.getInstance(applicationContext)
                navManager.stopJourney()
                orchestratorClient.sendDeviceResponse(
                    requestId, "stop_journey", text = "Navigation stopped"
                )
            }
            "modify_journey" -> {
                val navManager = NavigationManager.getInstance(applicationContext)
                if (navManager.getState() != NavigationManager.State.ACTIVE) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "modify_journey",
                        text = "No active journey to modify. Start a journey first."
                    )
                    return
                }
                resolveDestination(params) { waypointPoint ->
                    if (waypointPoint == null) {
                        orchestratorClient.sendDeviceResponse(
                            requestId, "modify_journey",
                            text = "Could not resolve waypoint. Provide address or waypointLat+waypointLng."
                        )
                        return@resolveDestination
                    }
                    navManager.modifyJourney(waypointPoint.latitude, waypointPoint.longitude) { newEta ->
                        val data = JSONObject().put("etaSeconds", newEta)
                        orchestratorClient.sendDeviceResponse(
                            requestId, "modify_journey", data = data
                        )
                    }
                }
            }
            "get_journey" -> {
                val navManager = NavigationManager.getInstance(applicationContext)
                val data = navManager.getJourneyInfo()
                orchestratorClient.sendDeviceResponse(
                    requestId, "get_journey", data = data
                )
            }
            "get_eta" -> {
                val navManager = NavigationManager.getInstance(applicationContext)
                val (etaSeconds, etaFormatted) = navManager.getCurrentEta()
                val data = JSONObject().apply {
                    put("etaSeconds", etaSeconds)
                    put("etaFormatted", etaFormatted)
                }
                orchestratorClient.sendDeviceResponse(
                    requestId, "get_eta", data = data
                )
            }
            "get_eta_to_address" -> {
                val transportType = params?.optString("transport_type", "") ?: ""
                if (transportType.isEmpty()) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "get_eta_to_address",
                        text = "Missing required parameter: transport_type (walking, car, bus, bicycle)"
                    )
                    return
                }
                val mode = parseTransportType(transportType)
                if (mode == null) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "get_eta_to_address",
                        text = "Invalid transport_type: $transportType. Use: walking, car, bus, bicycle"
                    )
                    return
                }
                resolveDestination(params) { destPoint ->
                    if (destPoint == null) {
                        orchestratorClient.sendDeviceResponse(
                            requestId, "get_eta_to_address",
                            text = "Could not resolve destination. Provide address or toLat+toLng."
                        )
                        return@resolveDestination
                    }
                    locationProvider.getCurrentLocation { locationJson ->
                        if (locationJson == null) {
                            orchestratorClient.sendDeviceResponse(
                                requestId, "get_eta_to_address",
                                text = "Could not get current location"
                            )
                            return@getCurrentLocation
                        }
                        val fromLat = locationJson.getDouble("lat")
                        val fromLng = locationJson.getDouble("lng")
                        MapProviders.active.routing.queryMode(Point(fromLat, fromLng), destPoint, mode) { method ->
                            if (method == null) {
                                orchestratorClient.sendDeviceResponse(
                                    requestId, "get_eta_to_address",
                                    text = "Failed to get ETA for mode $transportType"
                                )
                                return@queryMode
                            }
                            val data = JSONObject().apply {
                                put("etaSeconds", method.etaSeconds)
                                put("etaFormatted", method.etaFormatted)
                                put("distanceMeters", method.distanceMeters)
                                put("description", method.description)
                            }
                            orchestratorClient.sendDeviceResponse(
                                requestId, "get_eta_to_address", data = data
                            )
                        }
                    }
                }
            }
            "search_places" -> {
                val query = params?.optString("query", "") ?: ""
                if (query.isEmpty()) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "search_places",
                        text = "Missing required parameter: query"
                    )
                    return
                }
                val radius = params?.optDouble("radius", 1000.0) ?: 1000.0
                val paramLat = params?.optDouble("lat", Double.NaN) ?: Double.NaN
                val paramLng = params?.optDouble("lng", Double.NaN) ?: Double.NaN
                val area = params?.optString("area", "") ?: ""

                val doSearch = { center: Point ->
                    MapProviders.active.placeSearch.search(query, center, radius) { result ->
                        orchestratorClient.sendDeviceResponse(
                            requestId, "search_places", data = result
                        )
                    }
                }

                when {
                    !paramLat.isNaN() && !paramLng.isNaN() -> doSearch(Point(paramLat, paramLng))
                    area.isNotEmpty() -> {
                        MapProviders.active.geocoder.geocode(area) { point ->
                            if (point == null) {
                                orchestratorClient.sendDeviceResponse(
                                    requestId, "search_places",
                                    text = "Could not geocode area: $area"
                                )
                                return@geocode
                            }
                            doSearch(point)
                        }
                    }
                    else -> {
                        locationProvider.getCurrentLocation { locationJson ->
                            if (locationJson == null) {
                                orchestratorClient.sendDeviceResponse(
                                    requestId, "search_places",
                                    text = "Could not get current location"
                                )
                                return@getCurrentLocation
                            }
                            val lat = locationJson.getDouble("lat")
                            val lng = locationJson.getDouble("lng")
                            doSearch(Point(lat, lng))
                        }
                    }
                }
            }
            // --- Remote sideload commands (orchestrator -> phone -> glasses) ---

            "sideload_open" -> {
                if (!AppConfig.getSideloadingEnabled(this)) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "sideload_open",
                        data = JSONObject().put("ok", false).put("error", "sideloading disabled")
                    )
                    return
                }
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        sideloadForwarder.open()
                        orchestratorClient.sendDeviceResponse(
                            requestId, "sideload_open",
                            data = JSONObject().put("ok", true)
                        )
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "sideload_open failed: ${e.message}")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "sideload_open",
                            data = JSONObject().put("ok", false).put("error", e.message ?: "unknown error")
                        )
                    }
                }
            }

            "sideload_upload" -> {
                // downloadUrl is injected by the orchestrator onto the command itself,
                // not into params, so read either. Taking only params meant every
                // upload over this route was refused as "missing downloadUrl".
                val downloadUrl = params?.optString("downloadUrl", "")
                    ?.ifEmpty { command.optString("downloadUrl", "") } ?: ""
                val fileName = params?.optString("fileName", "")
                    ?.ifEmpty { command.optString("fileName", "") } ?: ""
                if (downloadUrl.isEmpty() || fileName.isEmpty()) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "sideload_upload",
                        data = JSONObject().put("ok", false).put("error", "missing downloadUrl or fileName")
                    )
                    return
                }
                serviceScope.launch(Dispatchers.IO) {
                    var tempFile: File? = null
                    try {
                        // 1) Download file from orchestrator BEFORE opening the sideload
                        //    session. Once forwarder.open() binds the process to p2p0,
                        //    all sockets route over WiFi-Direct (no internet).
                        val orchHttpUrl = AppConfig.getOrchestratorHttpUrl(this@ListenerService)
                        val orchApiKey = AppConfig.getApiKey(this@ListenerService)
                        // When a sideload session is already open, bindProcessToNetwork(p2p0)
                        // is process-wide -- a plain OkHttpClient would route over WiFi-Direct
                        // (no internet). Pin the download to a real internet network.
                        val internetSf = internetNetwork()?.socketFactory
                            ?: javax.net.SocketFactory.getDefault()
                        val dlClient = okhttp3.OkHttpClient.Builder()
                            .socketFactory(internetSf)
                            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val fullUrl = if (downloadUrl.startsWith("http")) downloadUrl
                            else "$orchHttpUrl$downloadUrl"
                        val dlReq = okhttp3.Request.Builder()
                            .url(fullUrl)
                            .header("x-api-key", orchApiKey)
                            .get()
                            .build()
                        LogCollector.i(TAG, "sideload_upload: downloading $fileName from orchestrator")
                        dlClient.newCall(dlReq).execute().use { dlResp ->
                            if (!dlResp.isSuccessful) {
                                throw Exception("orchestrator download failed: HTTP ${dlResp.code}")
                            }
                            val dlBody = dlResp.body
                                ?: throw Exception("orchestrator returned empty body")
                            tempFile = File(cacheDir, "sideload-dl-${System.currentTimeMillis()}")
                            dlBody.byteStream().use { input ->
                                tempFile!!.outputStream().use { output ->
                                    input.copyTo(output, bufferSize = 64 * 1024)
                                }
                            }
                        }
                        LogCollector.i(TAG, "sideload_upload: downloaded ${tempFile!!.length()} bytes to temp")

                        // 2) Open sideload session if not already open (now safe --
                        //    the file is local, p2p binding won't block the download)
                        if (!sideloadForwarder.isSessionOpen) {
                            sideloadForwarder.open()
                        }

                        // 3) Upload the local temp file to glasses via WiFi-Direct
                        val glassesResp = sideloadForwarder.upload(
                            fileName,
                            java.io.FileInputStream(tempFile!!),
                            tempFile!!.length()
                        )
                        val glassesJson = try { JSONObject(glassesResp) } catch (_: Exception) {
                            JSONObject().put("raw", glassesResp)
                        }
                        LogCollector.i(TAG, "sideload_upload: upload complete for $fileName")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "sideload_upload", data = glassesJson
                        )
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "sideload_upload failed: ${e.message}")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "sideload_upload",
                            data = JSONObject().put("ok", false).put("error", e.message ?: "unknown error")
                        )
                    } finally {
                        tempFile?.delete()
                    }
                }
            }

            "sideload_exec" -> {
                val cmd = params?.optString("cmd", "") ?: ""
                if (cmd.isEmpty()) {
                    orchestratorClient.sendDeviceResponse(
                        requestId, "sideload_exec",
                        data = JSONObject().put("ok", false).put("error", "missing cmd")
                    )
                    return
                }
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        if (!sideloadForwarder.isSessionOpen) {
                            sideloadForwarder.open()
                        }

                        // Start async exec on glasses
                        val startResp = sideloadForwarder.execStart(
                            JSONObject().put("cmd", cmd).toString()
                        )
                        val startJson = JSONObject(startResp)
                        val jobId = startJson.getString("job")
                        LogCollector.i(TAG, "sideload_exec: started job=$jobId cmd=${cmd.take(80)}")

                        // Poll until completion, collecting incremental output
                        val allStdout = StringBuilder()
                        val allStderr = StringBuilder()
                        var stdoutFrom = 0
                        var stderrFrom = 0
                        var rc = -1
                        var truncated = false

                        while (true) {
                            val pollJson = JSONObject().apply {
                                put("job", jobId)
                                put("stdoutFrom", stdoutFrom)
                                put("stderrFrom", stderrFrom)
                            }.toString()
                            val pollResp = sideloadForwarder.execPoll(pollJson)
                            val poll = JSONObject(pollResp)

                            val outB64 = poll.optString("stdoutB64", "")
                            val errB64 = poll.optString("stderrB64", "")
                            if (outB64.isNotEmpty()) {
                                allStdout.append(outB64)
                                stdoutFrom = poll.optInt("stdoutTotal", stdoutFrom)
                            }
                            if (errB64.isNotEmpty()) {
                                allStderr.append(errB64)
                                stderrFrom = poll.optInt("stderrTotal", stderrFrom)
                            }
                            truncated = poll.optBoolean("truncated", false)

                            if (!poll.optBoolean("running", true)) {
                                rc = poll.optInt("rc", -1)
                                break
                            }

                            // Brief pause before next poll to avoid busy-spinning
                            kotlinx.coroutines.delay(500)
                        }

                        LogCollector.i(TAG, "sideload_exec: job=$jobId finished rc=$rc")
                        val result = JSONObject().apply {
                            put("rc", rc)
                            put("stdoutB64", allStdout.toString())
                            put("stderrB64", allStderr.toString())
                            put("truncated", truncated)
                        }
                        orchestratorClient.sendDeviceResponse(
                            requestId, "sideload_exec", data = result
                        )
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "sideload_exec failed: ${e.message}")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "sideload_exec",
                            data = JSONObject().put("ok", false).put("error", e.message ?: "unknown error")
                        )
                    }
                }
            }

            "sideload_close" -> {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        sideloadForwarder.close()
                        orchestratorClient.sendDeviceResponse(
                            requestId, "sideload_close",
                            data = JSONObject().put("ok", true)
                        )
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "sideload_close failed: ${e.message}")
                        orchestratorClient.sendDeviceResponse(
                            requestId, "sideload_close",
                            data = JSONObject().put("ok", false).put("error", e.message ?: "unknown error")
                        )
                    }
                }
            }

            else -> {
                LogCollector.w(TAG, "Unknown device command type: $commandType")
            }
        }
    }

    // --- Navigation helpers ---

    private fun parseTransportType(type: String): TransportMode? {
        return when (type.lowercase()) {
            "walking" -> TransportMode.WALKING
            "car" -> TransportMode.DRIVING
            "bus" -> TransportMode.TRANSIT
            "bicycle" -> TransportMode.BICYCLE
            else -> null
        }
    }

    private fun resolveDestination(params: JSONObject?, callback: (Point?) -> Unit) {
        val address = params?.optString("address", "")?.takeIf { it.isNotEmpty() }
        val toLat = params?.optDouble("toLat", Double.NaN) ?: Double.NaN
        val toLng = params?.optDouble("toLng", Double.NaN) ?: Double.NaN
        // Also accept waypointLat/waypointLng for modify_journey
        val wpLat = params?.optDouble("waypointLat", Double.NaN) ?: Double.NaN
        val wpLng = params?.optDouble("waypointLng", Double.NaN) ?: Double.NaN

        when {
            !toLat.isNaN() && !toLng.isNaN() -> callback(Point(toLat, toLng))
            !wpLat.isNaN() && !wpLng.isNaN() -> callback(Point(wpLat, wpLng))
            address != null -> {
                MapProviders.active.geocoder.geocode(address) { point ->
                    callback(point)
                }
            }
            else -> callback(null)
        }
    }

    private val pendingGlassesCallbacks = java.util.concurrent.ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val persistentGlassesCallbacks = java.util.concurrent.ConcurrentHashMap<String, (JSONObject) -> Unit>()

    private fun relayCommandToGlasses(requestId: String, command: JSONObject) {
        val commandType = command.optString("type", "")
        val params = command.optJSONObject("params")?.toString() ?: "{}"
        LogCollector.i(TAG, "Relaying command $commandType to glasses (requestId=$requestId)")

        pendingGlassesCallbacks[requestId] = { result ->
            LogCollector.i(TAG, "Glasses command result received for $requestId")
            val imageBase64 = result.optString("imageBase64", "").takeIf { it.isNotEmpty() }
            val text = result.optString("text", "").takeIf { it.isNotEmpty() }

            // Strip known top-level fields, pass remaining as data
            val data = JSONObject(result.toString())
            data.remove("imageBase64")
            data.remove("text")

            orchestratorClient.sendDeviceResponse(
                requestId, commandType,
                imageBase64 = imageBase64,
                text = text,
                data = if (data.length() > 0) data else null
            )
        }

        phoneBtHost.sendCommand(commandType, requestId, params)
    }

    // --- Glasses chat list relay ---

    private fun handleGlassesChatListRequest() {
        LogCollector.i(TAG, "Handling chat list request from glasses")
        val wsUrl = AppConfig.getOrchestratorUrl(this)
        val apiKey = AppConfig.getApiKey(this)
        val client = ChatHistoryClient(wsUrl, apiKey)

        client.listChats(limit = 20) { result ->
            result.onSuccess { response ->
                val arr = org.json.JSONArray()
                for (chat in response.conversations) {
                    arr.put(JSONObject().apply {
                        put("id", chat.id)
                        put("title", chat.firstUserMessage ?: "(no messages)")
                        put("relativeTime", formatRelativeTime(chat.lastActivityAt))
                        put("turnCount", chat.turnCount)
                        put("isActive", !chat.closed)
                        put("deviceType", chat.deviceType)
                    })
                }
                phoneBtHost.sendChatListResponse(arr.toString())
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Chat list fetch failed: ${err.message}")
                phoneBtHost.sendChatListResponse("[]")
            }
        }
    }

    private fun handleGlassesSwitchChat(conversationId: String) {
        LogCollector.i(TAG, "Switching to conversation: $conversationId")
        val wsUrl = AppConfig.getOrchestratorUrl(this)
        val apiKey = AppConfig.getApiKey(this)
        val client = ChatHistoryClient(wsUrl, apiKey)

        client.getChatDetail(conversationId) { result ->
            result.onSuccess { detail ->
                val turnsArr = org.json.JSONArray()
                for (turn in detail.turns) {
                    turnsArr.put(JSONObject().apply {
                        put("requestId", turn.requestId)
                        if (turn.userText != null) put("userText", turn.userText)
                        if (turn.responseText != null) put("responseText", turn.responseText)
                        if (turn.toolCalls.isNotEmpty()) {
                            val tcArr = org.json.JSONArray()
                            for (tc in turn.toolCalls) {
                                tcArr.put(JSONObject().apply {
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    if (tc.arguments != null) put("arguments", tc.arguments)
                                })
                            }
                            put("toolCalls", tcArr)
                        }
                    })
                }
                phoneBtHost.sendChatHistory(conversationId, turnsArr.toString())
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Chat detail fetch failed: ${err.message}")
                phoneBtHost.sendChatHistory(conversationId, "[]")
            }
        }
    }

    private fun formatRelativeTime(isoTimestamp: String): String {
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val date = fmt.parse(isoTimestamp) ?: return isoTimestamp
            val diffMs = System.currentTimeMillis() - date.time
            val diffMin = diffMs / 60_000
            val diffHour = diffMs / 3_600_000
            val diffDay = diffMs / 86_400_000
            when {
                diffMin < 1 -> "now"
                diffMin < 60 -> "${diffMin}m"
                diffHour < 24 -> "${diffHour}h"
                diffDay < 7 -> "${diffDay}d"
                else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(date)
            }
        } catch (_: Exception) { isoTimestamp }
    }

    fun onGlassesCommandResult(requestId: String, result: JSONObject) {
        val callback = pendingGlassesCallbacks.remove(requestId)
        if (callback != null) {
            callback(result)
        } else {
            // Check persistent callbacks (teleprompter sends multiple state updates)
            val persistent = persistentGlassesCallbacks[requestId]
            if (persistent != null) {
                persistent(result)
            } else {
                LogCollector.w(TAG, "No pending callback for glasses command result: $requestId")
            }
        }
    }

    // --- ADB Chat Detail Helper ---

    private fun fetchChatDetailForAdb(client: ChatHistoryClient, conversationId: String, commandId: String, type: String) {
        client.getChatDetail(conversationId) { result ->
            result.onSuccess { detail ->
                val turnsArr = org.json.JSONArray()
                for (turn in detail.turns) {
                    turnsArr.put(JSONObject().apply {
                        put("request_id", turn.requestId)
                        if (turn.userText != null) put("user_text", turn.userText)
                        if (turn.responseText != null) put("assistant_text", turn.responseText)
                        if (turn.responseStatus != null) put("response_status", turn.responseStatus)
                        put("timestamp", turn.ts)
                    })
                }
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().apply {
                        put("conversation_id", conversationId)
                        put("turn_count", detail.turnCount)
                        put("device_type", detail.deviceType)
                        put("turns", turnsArr)
                    })
            }
            result.onFailure { err ->
                AdbResultWriter.writeError(this, commandId, type,
                    "Failed to get chat detail: ${err.message}")
            }
        }
    }

    // --- ADB Command Dispatch ---

    /** Carries the glasses' start_ar_stream reply (WiFi-Direct details + ports) to ArStreamActivity. */
    private fun broadcastArStreamResult(commandId: String, resultJson: String) {
        sendBroadcast(Intent(ACTION_AR_STREAM_RESULT).apply {
            setPackage(packageName)
            putExtra("command_id", commandId)
            putExtra("result", resultJson)
        })
    }

    private fun broadcastRecordingResult(commandId: String, success: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_RECORDING_RESULT).apply {
            setPackage(packageName)
            putExtra("command_id", commandId)
            putExtra("success", success)
            putExtra("message", message)
        })
    }

    // Teleprompter speech tracking state
    @Volatile private var teleprompterTrackingActive = false
    private var tpFeedLogCount = 0L
    private var speechPositionTracker: SpeechPositionTracker? = null
    private var activeTeleprompterCommandId: String? = null
    private val lastSentWordIndex = java.util.concurrent.atomic.AtomicInteger(-1)

    private fun startTeleprompterTracking(commandId: String, text: String, lang: String) {
        val url = transcriberUrl ?: return
        val key = AppConfig.getApiKey(this)

        speechPositionTracker = SpeechPositionTracker().also { it.setText(text) }
        activeTeleprompterCommandId = commandId
        lastSentWordIndex.set(-1)

        transcriberStreamClient?.release()
        transcriberStreamClient = TranscriberStreamClient(url, key).also { client ->
            client.start(object : TranscriberStreamClient.Listener {
                override fun onPartial(text: String) {
                    val tracker = speechPositionTracker ?: return
                    val newIndex = tracker.feedRecognition(text) ?: return
                    val lastSent = lastSentWordIndex.get()
                    if (newIndex > lastSent) {
                        lastSentWordIndex.set(newIndex)
                        val cmdId = activeTeleprompterCommandId ?: return
                        val params = org.json.JSONObject().apply {
                            put("action", "set_position")
                            put("original_command_id", cmdId)
                            put("word_index", newIndex)
                        }
                        phoneBtHost.sendCommand("teleprompter_control", cmdId, params.toString())
                    }
                }
                override fun onFinal(text: String) {
                    LogCollector.i(TAG, "Teleprompter transcriber final: '${text.take(60)}'")
                }
                override fun onError(message: String) {
                    LogCollector.e(TAG, "Teleprompter transcriber error: $message")
                }
            }, sourceLang = lang)
        }

        teleprompterTrackingActive = true
        LogCollector.i(TAG, "Teleprompter speech tracking started (lang=$lang, words=${speechPositionTracker?.getWordCount()})")
    }

    private fun stopTeleprompterTracking() {
        if (!teleprompterTrackingActive) return
        teleprompterTrackingActive = false
        transcriberStreamClient?.release()
        transcriberStreamClient = null
        speechPositionTracker = null
        activeTeleprompterCommandId = null
        lastSentWordIndex.set(-1)
        LogCollector.i(TAG, "Teleprompter speech tracking stopped")
    }

    private fun broadcastTeleprompterState(commandId: String, state: String, progress: Float, speed: Int) {
        sendBroadcast(Intent(ACTION_TELEPROMPTER_STATE).apply {
            setPackage(packageName)
            putExtra("command_id", commandId)
            putExtra("state", state)
            putExtra("progress", progress)
            putExtra("speed", speed)
        })
    }

    /**
     * The phone's primary WiFi network (wlan0), or null if not on WiFi. Selected by transport
     * (TRANSPORT_WIFI) and interface name (not "p2p*"), so it is never the VPN tun (which lacks
     * TRANSPORT_WIFI) nor the WiFi-Direct p2p network the forwarder uses. No subnet is hardcoded;
     * whatever WiFi the phone is on is used. The sideload HTTP server binds each LAN client
     * socket to this so replies bypass an active VPN killswitch.
     */
    private fun primaryWifiNetwork(): android.net.Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                // Must be real WiFi, never the VPN tun. NOT_VPN excludes any tun that
                // surfaces an underlying WiFi transport; the iface filter pins wlan* and
                // rejects the WiFi-Direct p2p* net the forwarder uses for glasses traffic.
                if (!c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
                if (!c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@firstOrNull false
                val iface = cm.getLinkProperties(n)?.interfaceName ?: return@firstOrNull false
                iface.startsWith("wlan")
            }
        } catch (e: Exception) {
            LogCollector.w(TAG, "primaryWifiNetwork failed: ${e.message}")
            null
        }
    }

    /**
     * Any network with internet connectivity that is NOT a VPN and NOT a WiFi-Direct p2p
     * interface. Works on both WiFi (wlan) and cellular. Used by sideload_upload to pin the
     * orchestrator download to the real internet when the process is bound to p2p0 for
     * glasses traffic.
     */
    private fun internetNetwork(): android.net.Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                if (!c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@firstOrNull false
                if (!c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@firstOrNull false
                val iface = cm.getLinkProperties(n)?.interfaceName ?: return@firstOrNull false
                !iface.startsWith("p2p")
            }
        } catch (e: Exception) {
            LogCollector.w(TAG, "internetNetwork failed: ${e.message}")
            null
        }
    }

    private fun handleAdbCommand(commandId: String, type: String, paramsStr: String) {
        LogCollector.i(TAG, "ADB command dispatch: type=$type, id=$commandId")

        when (type) {
            "status" -> {
                val data = JSONObject().apply {
                    put("glasses_connected", phoneBtHost.isReachable)
                    put("glasses_phase", phoneBtHost.glassesConnectionPhase())
                    put("service_state", state.name)
                    put("orchestrator_connected", orchestratorClient.isConnected)
                }
                AdbResultWriter.writeSuccess(this, commandId, type, data)
            }
            "sideload_toggle" -> {
                val p = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val enabled = p.optBoolean("enabled", true)
                applySideloadingState(this, enabled)
                if (phoneBtHost.isConnected) {
                    phoneBtHost.sendSettings(JSONObject().put("enable_sideloading", enabled).toString())
                }
                val data = JSONObject().apply {
                    put("enabled", enabled)
                    put("lan_server_up", sideloadHttpServerInstance?.isRunning ?: false)
                    put("glasses_bt_connected", phoneBtHost.isConnected)
                }
                AdbResultWriter.writeSuccess(this, commandId, type, data)
            }
            "start_lone" -> {
                startLoneMode()
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().put("active", true))
            }
            "stop_lone" -> {
                stopLoneMode()
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().put("active", false))
            }
            "lone_trust" -> {
                val p = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val addr = p.optString("address")
                val tr = p.optBoolean("trusted", false)
                setLoneTrust(addr, tr)
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().put("address", addr).put("trusted", tr))
            }

            "audio_relay" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val action = params.optString("action", "status")
                when (action) {
                    "start" -> {
                        if (!orchestratorConnected) {
                            AdbResultWriter.writeError(this, commandId, type, "Orchestrator not connected")
                            return
                        }
                        val bitrate = params.optInt("bitrate", 64000)
                        startAudioRelay(bitrate)
                        AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                            put("action", "start")
                            put("bitrate", bitrate)
                        })
                    }
                    "stop" -> {
                        stopAudioRelay()
                        AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                            put("action", "stop")
                        })
                    }
                    else -> {
                        AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                            put("audio_relay_active", audioRelayActive)
                            put("orchestrator_connected", orchestratorConnected)
                        })
                    }
                }
            }

            "glasses_record_toggle" -> {
                // Programmatic equivalent of AudioRecordingListFragment.btnRecordToggle. Drives
                // the same phoneBtHost.sendSettings({on_demand_recording_active}) path the UI
                // button uses, so dev/test harnesses can toggle without coordinate taps. If the
                // RFCOMM link is dead, kick a reconnect first and wait briefly -- without this
                // the queued frame would just sit in outboundQueue indefinitely. On stop, also
                // calls requestImmediateSync() so the file lands on the phone right away.
                // Params: { "on_demand_recording_active": true|false } (optional; defaults to toggle).
                val params = if (paramsStr.isNotBlank()) try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() } else JSONObject()
                val newOnDemand = if (params.has("on_demand_recording_active")) {
                    params.getBoolean("on_demand_recording_active")
                } else {
                    !onDemandActive
                }
                if (!phoneBtHost.isConnected) {
                    phoneBtHost.wakeAndReconnect("adb:glasses_record_toggle")
                    val deadline = System.currentTimeMillis() + 8000
                    while (!phoneBtHost.isConnected && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                    }
                    if (!phoneBtHost.isConnected) {
                        AdbResultWriter.writeError(this, commandId, type, "Glasses BT reconnect timed out")
                        return
                    }
                }
                val json = JSONObject().put("on_demand_recording_active", newOnDemand).toString()
                onDemandActive = newOnDemand
                phoneBtHost.sendSettings(json)
                if (!newOnDemand) requestImmediateSync()
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                    put("on_demand_recording_active", newOnDemand)
                    put("always_record_enabled", alwaysRecordEnabled)
                })
            }
            "glasses_always_record_toggle" -> {
                // Programmatic equivalent of AudioRecordingListFragment.btnAlwaysRecord.
                // Params: { "always_record_enabled": true|false } (optional; defaults to toggle).
                if (!phoneBtHost.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type, "Glasses not connected via Bluetooth")
                    return
                }
                val params = if (paramsStr.isNotBlank()) try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() } else JSONObject()
                val newAlways = if (params.has("always_record_enabled")) {
                    params.getBoolean("always_record_enabled")
                } else {
                    !alwaysRecordEnabled
                }
                val json = JSONObject().put("always_record_enabled", newAlways).toString()
                alwaysRecordEnabled = newAlways
                phoneBtHost.sendSettings(json)
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                    put("on_demand_recording_active", onDemandActive)
                    put("always_record_enabled", newAlways)
                })
            }

            "record_ar_screen", "record_video", "capture_raw", "clear_raw", "list_storage", "diag_ar", "diag_screen_record", "diag_screen_record_v2", "diag_screenrecord_cmd", "diag_wake_screenrecord", "diag_mix_record_trace", "nav_goal_status" -> {
                LogCollector.i(TAG, "ADB glasses command: bt=${phoneBtHost.isConnected}, type=$type, id=$commandId")
                if (!phoneBtHost.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Glasses not connected via Bluetooth")
                    if (type in setOf("record_ar_screen", "record_video")) {
                        broadcastRecordingResult(commandId, false, "Glasses not connected")
                    }
                    return
                }

                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }

                pendingGlassesCallbacks[commandId] = { result ->
                    LogCollector.i(TAG, "ADB glasses command result for $commandId")
                    val success = result.optBoolean("success", false)
                    if (success) {
                        AdbResultWriter.writeSuccess(this, commandId, type, result)
                        if (type in setOf("record_ar_screen", "record_video")) {
                            broadcastRecordingResult(commandId, true, "Recording saved")
                        }
                    } else {
                        val error = result.optString("error", "Glasses command failed")
                        AdbResultWriter.writeError(this, commandId, type, error)
                        if (type in setOf("record_ar_screen", "record_video")) {
                            broadcastRecordingResult(commandId, false, error)
                        }
                    }
                }

                LogCollector.i(TAG, "Sending command to glasses via BT: $type ($commandId)")
                phoneBtHost.sendCommand(type, commandId, params.toString())
                LogCollector.i(TAG, "phoneBtHost.sendCommand() returned for $commandId")

                // Notify UI that command was sent (not yet confirmed by glasses)
                if (type in setOf("record_ar_screen", "record_video")) {
                    sendBroadcast(Intent(ACTION_RECORDING_RESULT).apply {
                        setPackage(packageName)
                        putExtra("command_id", commandId)
                        putExtra("started", true)
                    })
                }
            }

            "stop_recording" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val originalId = params.optString("original_command_id", "")
                if (originalId.isEmpty()) {
                    AdbResultWriter.writeError(this, commandId, type, "Missing original_command_id")
                    broadcastRecordingResult(originalId.ifEmpty { commandId }, false, "Missing original_command_id")
                    return
                }
                if (!phoneBtHost.isConnected) {
                    LogCollector.w(TAG, "Stop recording: BT not connected")
                    AdbResultWriter.writeError(this, commandId, type, "Glasses not connected")
                    broadcastRecordingResult(originalId, false, "Glasses not connected -- cannot stop")
                    // Remove stale callback so UI can be reused
                    pendingGlassesCallbacks.remove(originalId)
                    return
                }
                LogCollector.i(TAG, "Stop recording requested for $originalId")
                phoneBtHost.sendCommand("stop_recording", originalId, "{}")
            }

            "start_translation" -> {
                if (!phoneBtHost.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Glasses not connected via Bluetooth")
                    return
                }
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                translationFromLang = params.optString("from_language", "en")
                translationToLang = params.optString("to_language", "ru")
                translationFromNllb = LanguageUtils.resolveNllb(params.optString("from_nllb", ""), translationFromLang)
                translationToNllb = LanguageUtils.resolveNllb(params.optString("to_nllb", ""), translationToLang)
                translationFontSize = params.optInt("font_size", 14)
                translationAudioSource = params.optString("audio_source", "glasses")
                val requestedProvider = params.optString("provider", "default")
                translationTwoWay = params.optBoolean("two_way", false)
                AppConfig.setTranslationTwoWay(this, translationTwoWay)
                translationProvider = "default"
                translationSegmentId.set(1)
                translationMode = true
                broadcastTranslationState(true)
                systemAudioVadEngine = VadEngine(this).also { it.initialize() }
                glassesVadEngine?.reset()
                if (requestedProvider == "azure") {
                    if (startAzureTranslationSession()) {
                        translationProvider = "azure"
                    }
                } else {
                    translationProvider = requestedProvider
                    if (translationProvider.isNotEmpty() && translationProvider != "default") {
                        LogCollector.w(TAG, "Unknown translation provider: $translationProvider, falling back to default")
                        translationProvider = "default"
                    }
                    orchestratorClient.setTranscribeStreamListener(transcribeStreamListener)
                    orchestratorClient.connectTranscribeStream(
                        translationFromLang, translationToLang,
                        translationFromNllb, translationToNllb,
                        translate = true
                    )
                }
                phoneBtHost.sendTranslationConfig(translationFromLang, translationToLang)
                phoneBtHost.sendTranslationConfigToApp(translationFromLang, translationToLang, translationFontSize, translationTwoWay)
                phoneBtHost.sendCommand("start_translation", commandId, params.toString())
                if (translationAudioSource == "system") startSystemAudioCapture()
                pushTranslationState()
                LogCollector.i(TAG, "Translation started: $translationFromLang -> $translationToLang (fontSize=$translationFontSize, audioSource=$translationAudioSource, provider=$translationProvider)")
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().put("message", "Translation started"))
            }

            "switch_audio_source" -> {
                if (!translationMode) {
                    AdbResultWriter.writeError(this, commandId, type, "Not in translation mode")
                    return
                }
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val newSource = params.optString("audio_source", "glasses")
                translationAudioSource = newSource
                if (newSource == "system") {
                    startSystemAudioCapture()
                } else {
                    stopSystemAudioCapture()
                }
                if (translationProvider == "azure") {
                    azureTranslationSession?.switchAudioSource(newSource)
                }
                // Re-assert desired state so the glasses learn the source change (gates
                // whether they tap the far-party call downlink).
                pushTranslationState()
                LogCollector.i(TAG, "Translation audio source switched to: $newSource")
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().put("audio_source", newSource))
            }

            "stop_translation" -> {
                translationMode = false
                broadcastTranslationState(false)
                translationAudioSource = "glasses"
                systemSubSource = "playback"
                translationTwoWay = false
                glassesVadEngine?.reset()
                systemAudioVadEngine?.release()
                systemAudioVadEngine = null
                if (translationProvider == "azure") {
                    stopAzureTranslationSession()
                } else {
                    if (translationProvider.isNotEmpty() && translationProvider != "default") {
                        LogCollector.w(TAG, "Unknown translation provider: $translationProvider, falling back to default")
                    }
                    orchestratorClient.disconnectTranscribeStream()
                }
                translationProvider = "default"
                stopSystemAudioCapture()
                if (phoneBtHost.isConnected) {
                    phoneBtHost.sendCommand("stop_translation", commandId, "{}")
                }
                pushTranslationState()
                LogCollector.i(TAG, "Translation stopped")
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().put("message", "Translation stopped"))
            }

            "start_assistant" -> {
                if (!phoneBtHost.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Glasses not connected via Bluetooth")
                    return
                }
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val wearerLang = params.optString("wearer_lang", "en-US")
                val interlocutorLang = params.optString("interlocutor_lang", "en-US")
                // Azure init blocks for seconds -- never on the main thread. Ack
                // the ADB command immediately; report the real outcome via the
                // ACTION_COPILOT_STATE broadcast once start completes/fails.
                serviceScope.launch(Dispatchers.IO) {
                    val ok = startCopilot(commandId, wearerLang, interlocutorLang, params)
                    if (ok) {
                        AdbResultWriter.writeSuccess(this@ListenerService, commandId, type,
                            JSONObject().put("message", "Copilot started"))
                    } else {
                        AdbResultWriter.writeError(this@ListenerService, commandId, type, "Failed to start copilot")
                    }
                }
            }

            "stop_assistant" -> {
                serviceScope.launch(Dispatchers.IO) { stopCopilot(commandId) }
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().put("message", "Copilot stopped"))
            }

            // DEBUG-ONLY: inject scripted copilot speech, bypassing the mics.
            // Feeds text into the SAME buffers the Azure recognizers write to,
            // so the normal batch timer flushes it to the REAL orchestrator and
            // the real pending->resolve card flow drives the glasses overlay.
            // Used only for e2e recordings of the copilot UI with mocked speech.
            "copilot_inject" -> {
                if (!copilotMode) {
                    AdbResultWriter.writeError(this, commandId, type, "Copilot not active")
                    return
                }
                val p = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val wearerText = p.optString("wearer", "")
                val interlocutorText = p.optString("interlocutor", "")
                val gen = copilotGeneration
                if (wearerText.isNotBlank()) appendCopilotSpeech(gen, wearer = true, text = wearerText)
                if (interlocutorText.isNotBlank()) appendCopilotSpeech(gen, wearer = false, text = interlocutorText)
                LogCollector.i(TAG, "[COPILOT] debug inject: wearer='${wearerText}' interlocutor='${interlocutorText}'")
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().put("injected", true)
                        .put("wearer", wearerText).put("interlocutor", interlocutorText))
            }

            "sync_files" -> {
                if (!phoneBtHost.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Glasses not connected via Bluetooth")
                    return
                }
                glassesSyncClient.forceSync()
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().put("message", "Sync handshake requested"))
            }

            "pull_glasses_log", "pull_glasses_file" -> {
                // These used the legacy LocalOnlyHotspot HTTP server on port 8848 that served
                // arbitrary paths. The new filesync APK only serves media files from
                // DCIM/Repository/ by stable id, which doesn't cover arbitrary paths like
                // /sdcard/Download/glasses-client.log. Use the WiFi P2P pull script
                // (test/adb/pull_glasses_log.sh) against the glasses' own HTTP server.
                AdbResultWriter.writeError(this, commandId, type,
                    "not supported after filesync migration; use the WiFi P2P pull script against the glasses HTTP server (port 8848)")
            }

            "start_mouse" -> {
                if (!phoneBtHost.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type, "Glasses not connected")
                    return
                }
                val mouseParams = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val deviceAddress = mouseParams.optString("device_address", "")
                // When a video-stream mouse channel is live, glasses input is routed to the
                // PC over the stream -- no Bluetooth-HID target needed. Only require/create the
                // HID bridge when there is no active stream (standalone HID mode).
                if (mouseEventListener == null) {
                    val targetDevice = if (deviceAddress.isNotEmpty()) {
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(deviceAddress)
                    } else null
                    if (targetDevice == null) {
                        AdbResultWriter.writeError(this, commandId, type, "No target device address")
                        return
                    }
                    // Stop previous bridge if any
                    phoneHidMouse?.stop()
                    phoneHidMouse = PhoneHidMouseBridge(this).apply {
                        listener = object : PhoneHidMouseBridge.Listener {
                            override fun onRegistered(success: Boolean) { broadcastMouseHidStatus() }
                            override fun onConnected(device: android.bluetooth.BluetoothDevice) { broadcastMouseHidStatus() }
                            override fun onDisconnected() { broadcastMouseHidStatus() }
                        }
                        start(targetDevice)
                    }
                }
                // Route RFCOMM mouse reports: to the stream when streaming, else to BT-HID.
                wireGlassesMouseRouting()
                // Forward start_mouse to glasses
                phoneBtHost.sendCommand(type, commandId, paramsStr)
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().put("message", "$type sent"))
            }

            "stop_mouse" -> {
                // Unhook RFCOMM mouse forwarding
                phoneBtHost.onMouseReport = null
                // Stop HID bridge
                phoneHidMouse?.stop()
                phoneHidMouse = null
                broadcastMouseHidStatus()
                if (!phoneBtHost.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type, "Glasses not connected")
                    return
                }
                // Forward stop_mouse to glasses
                phoneBtHost.sendCommand(type, commandId, paramsStr)
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().put("message", "$type sent"))
            }

            "start_ar_stream" -> {
                if (!phoneBtHost.isConnected) {
                    broadcastArStreamResult(commandId, JSONObject().apply {
                        put("success", false)
                        put("error", "Glasses not connected")
                    }.toString())
                    return
                }
                // One-shot: the glasses reply carries the WiFi-Direct details and ports the
                // ArStreamActivity needs to connect.
                persistentGlassesCallbacks[commandId] = { result ->
                    persistentGlassesCallbacks.remove(commandId)
                    broadcastArStreamResult(commandId, result.toString())
                }
                phoneBtHost.sendCommand("start_ar_stream", commandId, "{}")
            }
            "stop_ar_stream" -> {
                if (phoneBtHost.isConnected) {
                    phoneBtHost.sendCommand("stop_ar_stream", commandId, "{}")
                }
            }
            "start_teleprompter" -> {
                if (!phoneBtHost.isConnected) {
                    broadcastTeleprompterState(commandId, "stopped", 0f, 3)
                    return
                }
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }

                // Register persistent callback -- glasses will send multiple state updates
                persistentGlassesCallbacks[commandId] = { result ->
                    val state = result.optString("state", "")
                    val progress = result.optDouble("progress", 0.0).toFloat()
                    val speed = result.optInt("speed", 3)
                    LogCollector.i(TAG, "Teleprompter state from glasses: state=$state progress=$progress speed=$speed")
                    broadcastTeleprompterState(commandId, state, progress, speed)
                    if (state in setOf("stopped", "finished")) {
                        persistentGlassesCallbacks.remove(commandId)
                        stopTeleprompterTracking()
                    }
                }

                phoneBtHost.sendCommand("start_teleprompter", commandId, params.toString())
                broadcastTeleprompterState(commandId, "started", 0f, params.optInt("speed", 3))

                // Start speech tracking if text is available
                val text = params.optString("text", "")
                val lang = params.optString("lang", "ru")
                if (text.isNotBlank()) {
                    startTeleprompterTracking(commandId, text, lang)
                }
            }

            "teleprompter_control" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val action = params.optString("action", "")
                val originalCommandId = params.optString("original_command_id", "")

                if (action == "stop") {
                    persistentGlassesCallbacks.remove(originalCommandId)
                    broadcastTeleprompterState(originalCommandId, "stopped", 0f, 0)
                    stopTeleprompterTracking()
                }

                if (phoneBtHost.isConnected) {
                    phoneBtHost.sendCommand("teleprompter_control", originalCommandId, params.toString())
                }
            }

            "pipeline_diag" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val toggleDebug = params.optBoolean("toggle_debug", false)
                if (toggleDebug) {
                    val newValue = !AppConfig.debugPipeline
                    AppConfig.setDebugPipeline(this, newValue)
                    LogCollector.i(TAG, "Pipeline debug mode toggled to: $newValue")
                }
                val summary = PipelineTracer.getDiagnosticSummary()
                LogCollector.i(TAG, summary)
                fun sourceJson(c: PipelineTracer.SourceCounters) = JSONObject().apply {
                    put("total_chunks", c.totalChunks.get())
                    put("rms_gated", c.rmsGatedChunks.get())
                    put("vad_passed", c.vadPassedChunks.get())
                    put("vad_hangover", c.vadHangoverChunks.get())
                    put("vad_gated", c.vadGatedChunks.get())
                    put("vad_bypassed", c.vadBypassedChunks.get())
                    put("oww_fed", c.owwFedChunks.get())
                    put("oww_results", c.owwResults.get())
                    put("boundary_rejections", c.boundaryRejections.get())
                    put("cooldown_rejections", c.cooldownRejections.get())
                    put("speaker_rejections", c.speakerRejections.get())
                    put("speaker_acceptances", c.speakerAcceptances.get())
                    put("successful_detections", c.successfulDetections.get())
                    put("orchestrator_gated", c.orchestratorGated.get())
                }
                val data = JSONObject().apply {
                    put("summary", summary)
                    put("debug_enabled", AppConfig.debugPipeline)
                    put("phone", sourceJson(PipelineTracer.phone))
                    put("glasses", sourceJson(PipelineTracer.glasses))
                }
                AdbResultWriter.writeSuccess(this, commandId, type, data)
            }

            "ws_size_test" -> {
                if (!orchestratorClient.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type, "Not connected to orchestrator")
                } else {
                    orchestratorClient.sendRaw("""{"type":"ws_size_test"}""")
                    AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().put("message", "Size test triggered"))
                }
            }

            "dump_audio" -> {
                val outFile = java.io.File(filesDir, "dump_audio.wav")
                wakeWordDetector.dumpRollingBuffer(outFile)
                val data = JSONObject().apply {
                    put("file", outFile.absolutePath)
                    put("size", outFile.length())
                }
                AdbResultWriter.writeSuccess(this, commandId, type, data)
            }

"test_oww" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val wavPath = params.optString("wav_file", "")
                if (wavPath.isEmpty()) {
                    AdbResultWriter.writeError(this, commandId, type, "Missing 'wav_file' param")
                    return
                }
                val wavFile = java.io.File(wavPath)
                if (!wavFile.exists()) {
                    AdbResultWriter.writeError(this, commandId, type, "File not found: $wavPath")
                    return
                }
                val engine = wakeWordDetector.owwEngine
                if (engine == null) {
                    AdbResultWriter.writeError(this, commandId, type, "OWW engine not initialized")
                    return
                }
                AdbResultWriter.writePending(this, commandId, type)

                // Pause live detection so test thread has exclusive engine access
                wakeWordDetector.setMode(WakeWordDetector.Mode.CANCEL)

                Thread {
                    try {
                        val rawBytes = wavFile.readBytes()
                        val headerSize = 44
                        val pcmBytes = rawBytes.copyOfRange(headerSize, rawBytes.size)
                        val samples = ShortArray(pcmBytes.size / 2)
                        java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(samples)

                        val result = engine.testFile(samples)
                        val maxScore = result["max_score"] as Float
                        val aboveThreshold = result["frames_above_threshold"] as Int
                        @Suppress("UNCHECKED_CAST")
                        val highScores = (result["high_scores"] as List<Float>).map { "%.4f".format(it) }

                        val data = JSONObject().apply {
                            put("wav_file", wavPath)
                            put("samples", samples.size)
                            put("duration_ms", samples.size * 1000 / 16000)
                            put("max_score", "%.4f".format(maxScore))
                            put("frames_above_threshold", aboveThreshold)
                            put("threshold", AppConfig.OWW_THRESHOLD.toDouble())
                            put("total_frames", result["total_frames"] as Int)
                            put("high_scores", org.json.JSONArray(highScores))
                            put("detected", aboveThreshold > 0)
                        }
                        LogCollector.i(TAG, "test_oww: max=${"%.4f".format(maxScore)} above=${aboveThreshold} file=$wavPath")
                        AdbResultWriter.writeSuccess(this@ListenerService, commandId, type, data)
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "test_oww failed: ${e.message}")
                        AdbResultWriter.writeError(this@ListenerService, commandId, type, e.message ?: "Unknown error")
                    } finally {
                        wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
                    }
                }.start()
            }

            "test_streaming" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val pcmPath = params.optString("pcm_file", "")
                if (pcmPath.isEmpty()) {
                    AdbResultWriter.writeError(this, commandId, type, "Missing 'pcm_file' param")
                    return
                }
                val pcmFile = java.io.File(pcmPath)
                if (!pcmFile.exists()) {
                    AdbResultWriter.writeError(this, commandId, type, "File not found: $pcmPath")
                    return
                }
                val direct = params.optBoolean("direct", false)
                val pipeline = params.optBoolean("pipeline", false)
                val wsUrl = if (direct) {
                    val orchUrl = AppConfig.getOrchestratorUrl(this)
                    val host = android.net.Uri.parse(orchUrl.replace("ws://", "http://")).host
                        ?: "127.0.0.1"
                    "ws://$host:10003/ws/stream"
                } else {
                    AppConfig.getOrchestratorUrl(this).replace("/ws/device", "/ws/transcribe")
                }
                val mode = if (pipeline) "pipeline" else if (direct) "direct" else "relay"
                LogCollector.i(TAG, "ADB test_streaming: mode=$mode url=$wsUrl pcm=$pcmPath (${pcmFile.length()} bytes)")
                AdbResultWriter.writePending(this, commandId, type)
                Thread {
                    com.repository.listener.adb.StreamingTestRunner(
                        context = this,
                        commandId = commandId,
                        pcmFile = pcmFile,
                        wsUrl = wsUrl,
                        orchestratorClient = if (pipeline) orchestratorClient else null,
                        sourceLang = params.optString("source_lang", "en"),
                        targetLang = params.optString("target_lang", "ru"),
                        sourceNllb = params.optString("source_nllb", "eng_Latn"),
                        targetNllb = params.optString("target_nllb", "rus_Cyrl")
                    ).run()
                }.start()
            }

            "test_notif" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val sender = params.optString("sender", "Test Sender")
                val text = params.optString("text", "This is a test notification")
                val chat = params.optString("chat", "")
                // Mimic a real Telegram notification: repliable=true makes the glasses
                // show the hold-to-reply affordance. Test replies are echoed (no real
                // RemoteInput), handled by the "testrepl-" prefix in fireNotificationReply.
                val repliable = params.optBoolean("repliable", false)

                val displayText = if (chat.isNotEmpty()) "$sender ($chat): $text" else "$sender: $text"
                LogCollector.i(TAG, "Test notification: $displayText repliable=$repliable")

                val notifId = (if (repliable) "testrepl-" else "") + java.util.UUID.randomUUID().toString().take(8)
                notificationQueue.enqueue(notifId, sender, text, chat, repliable)
                notificationHistory.add(sender, text, chat, System.currentTimeMillis())
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().put("message", "Notification queued: notifId=$notifId $displayText repliable=$repliable"))
            }

            "notif_queue_status" -> {
                val snap = notificationQueue.snapshot()
                val data = JSONObject().apply {
                    put("listener_connected", TelegramNotificationListener.isConnected)
                    put("queue_size", snap.queueSize)
                    put("is_processing", snap.isProcessing)
                    put("current_notif_id", snap.currentNotifId ?: "")
                    put("current_sender", snap.currentSender ?: "")
                    val arr = org.json.JSONArray()
                    for ((nid, s, t) in snap.queued) {
                        arr.put(JSONObject().apply {
                            put("notifId", nid)
                            put("sender", s)
                            put("text", t)
                        })
                    }
                    put("queued", arr)
                }
                AdbResultWriter.writeSuccess(this, commandId, type, data)
            }

            "notif_queue_clear" -> {
                notificationQueue.clear("adb_test")
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().put("message", "Notification queue cleared"))
            }

            // --- Chat testing commands (ADB-driven conversation flow) ---

            "chat_status" -> {
                val data = JSONObject().apply {
                    put("orchestrator_connected", orchestratorClient.isConnected)
                    put("last_request_id", orchestratorClient.lastRequestId ?: "")
                    put("pending_chat_commands", pendingAdbChatCommands.size)
                    put("glasses_connected", phoneBtHost.isConnected)
                    put("service_state", state.name)
                    put("glasses_audio_state", glassesAudioState.name)
                }
                AdbResultWriter.writeSuccess(this, commandId, type, data)
            }

            "chat_send" -> {
                if (!orchestratorClient.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type, "Orchestrator not connected")
                    return
                }
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val text = params.optString("text", "")
                if (text.isEmpty()) {
                    AdbResultWriter.writeError(this, commandId, type, "Missing 'text' param")
                    return
                }
                val deviceType = params.optString("device_type", "glasses")
                val model = params.optString("model", "").ifBlank { AppConfig.getModel(this) }
                val userSystemPrompt = AiContextBuilder.build(
                    AppConfig.getUserSystemPrompt(this).ifBlank { null },
                    notificationHistory, cachedTodos
                )

                val attachPhoto = params.optBoolean("attach_photo", false)
                LogCollector.i(TAG, "ADB chat_send: '${text.take(80)}' (deviceType=$deviceType, attachPhoto=$attachPhoto)")

                serviceScope.launch(Dispatchers.IO) {
                    // Fetch photo from glasses if requested
                    var photoBase64: String? = null
                    if (attachPhoto) {
                        LogCollector.i(TAG, "ADB chat_send: requesting DCIM photo from glasses")
                        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
                        pendingPhotoDeferred = deferred
                        phoneBtHost.sendCommand("fetch_dcim_photo", "adb_photo", """{"max_age_ms":999999999}""")
                        photoBase64 = kotlinx.coroutines.withTimeoutOrNull(30_000L) { deferred.await() }
                        pendingPhotoDeferred = null
                        if (photoBase64 != null) {
                            LogCollector.i(TAG, "ADB chat_send: photo received (${photoBase64.length} chars)")
                        } else {
                            LogCollector.w(TAG, "ADB chat_send: no photo received from glasses within timeout")
                        }
                    }

                    // Capture requestId before sendRequest to avoid race with concurrent calls
                    // sendRequest sets lastRequestId at its top before any blocking retry loop
                    val sent = orchestratorClient.sendRequest(
                        text, imageBase64 = photoBase64, model = model, deviceType = deviceType,
                        userSystemPrompt = userSystemPrompt
                    )
                    // Read lastRequestId immediately after send returns (single-threaded on this IO dispatch)
                    val requestId = orchestratorClient.lastRequestId
                    if (!sent || requestId == null) {
                        AdbResultWriter.writeError(this@ListenerService, commandId, type,
                            if (!sent) "Failed to send request (orchestrator unreachable after retries)"
                            else "No requestId after send")
                        return@launch
                    }

                    val pending = PendingChatCommand(commandId, type, System.currentTimeMillis())
                    pendingAdbChatCommands[requestId] = pending
                    LogCollector.i(TAG, "ADB chat_send: requestId=$requestId -> commandId=$commandId")

                    // Wire into real UI flow so the conversation is observable in real-time
                    if (deviceType == "glasses") {
                        glassesRequestIds.add(requestId)
                        mainHandler.post {
                            phoneBtHost.sendGlassesUserText(requestId, text)
                            setGlassesState(GlassesAudioState.RESPONDING, "adb chat_send")
                        }
                    } else {
                        // Phone flow: broadcast user message to phone chat UI
                        mainHandler.post {
                            broadcastChatMessage(requestId, "USER", text)
                        }
                    }

                    val timeoutRunnable = Runnable {
                        val removed = pendingAdbChatCommands.remove(requestId)
                        if (removed != null) {
                            LogCollector.w(TAG, "ADB chat_send timeout: requestId=$requestId")
                            val partialText = removed.streamedText.get()
                            if (partialText.isNotEmpty()) {
                                AdbResultWriter.writeSuccess(this@ListenerService, commandId, type,
                                    JSONObject().apply {
                                        put("text", partialText)
                                        put("request_id", requestId)
                                        put("status", "timeout")
                                        put("note", "Partial response -- timed out after ${ADB_CHAT_TIMEOUT_MS / 1000}s")
                                    })
                            } else {
                                AdbResultWriter.writeError(this@ListenerService, commandId, type,
                                    "Timeout after ${ADB_CHAT_TIMEOUT_MS / 1000}s waiting for response")
                            }
                        }
                    }
                    pending.timeoutRunnable = timeoutRunnable
                    mainHandler.postDelayed(timeoutRunnable, ADB_CHAT_TIMEOUT_MS)
                }
            }

            "chat_list" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val limit = params.optInt("limit", 20)
                val wsUrl = AppConfig.getOrchestratorUrl(this)
                val apiKey = AppConfig.getApiKey(this)
                val client = ChatHistoryClient(wsUrl, apiKey)

                client.listChats(limit = limit) { result ->
                    result.onSuccess { response ->
                        val arr = org.json.JSONArray()
                        for (chat in response.conversations) {
                            arr.put(JSONObject().apply {
                                put("id", chat.id)
                                put("title", chat.firstUserMessage ?: "(no messages)")
                                put("relative_time", formatRelativeTime(chat.lastActivityAt))
                                put("turn_count", chat.turnCount)
                                put("is_active", !chat.closed)
                                put("device_type", chat.deviceType)
                                put("started_at", chat.startedAt)
                                put("last_activity_at", chat.lastActivityAt)
                            })
                        }
                        AdbResultWriter.writeSuccess(this, commandId, type,
                            JSONObject().apply {
                                put("conversations", arr)
                                put("total", response.total)
                            })
                    }
                    result.onFailure { err ->
                        AdbResultWriter.writeError(this, commandId, type,
                            "Failed to list chats: ${err.message}")
                    }
                }
            }

            "chat_get" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val conversationId = params.optString("conversation_id", "")
                val wsUrl = AppConfig.getOrchestratorUrl(this)
                val apiKey = AppConfig.getApiKey(this)
                val client = ChatHistoryClient(wsUrl, apiKey)

                if (conversationId.isEmpty()) {
                    client.listChats(limit = 1) { listResult ->
                        listResult.onSuccess { response ->
                            if (response.conversations.isEmpty()) {
                                AdbResultWriter.writeError(this, commandId, type, "No conversations found")
                                return@onSuccess
                            }
                            fetchChatDetailForAdb(client, response.conversations[0].id, commandId, type)
                        }
                        listResult.onFailure { err ->
                            AdbResultWriter.writeError(this, commandId, type,
                                "Failed to list chats: ${err.message}")
                        }
                    }
                } else {
                    fetchChatDetailForAdb(client, conversationId, commandId, type)
                }
            }

            "chat_new" -> {
                val wsUrl = AppConfig.getOrchestratorUrl(this)
                val apiKey = AppConfig.getApiKey(this)
                val deviceId = AppConfig.getDeviceId(this)
                val client = ChatHistoryClient(wsUrl, apiKey)
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val deviceType = params.optString("device_type", "glasses")

                client.createChat("", null, deviceId, deviceType) { result ->
                    result.onSuccess { response ->
                        AdbResultWriter.writeSuccess(this, commandId, type,
                            JSONObject().apply {
                                put("conversation_id", response.conversationId)
                                put("message", "New conversation created")
                            })
                    }
                    result.onFailure { err ->
                        AdbResultWriter.writeError(this, commandId, type,
                            "Failed to create chat: ${err.message}")
                    }
                }
            }

            // ── Alarm ADB commands (synchronous, local SharedPreferences) ──

            "alarm_list" -> {
                val alarms = com.repository.listener.alarm.AlarmStore.getAll(this)
                val arr = JSONArray()
                for (a in alarms) {
                    arr.put(JSONObject().apply {
                        put("id", a.id)
                        put("hour", a.hour)
                        put("minute", a.minute)
                        put("title", a.title)
                        put("enabled", a.enabled)
                        put("triggerTimeMillis", a.triggerTimeMillis)
                        put("createdAt", a.createdAt)
                    })
                }
                AdbResultWriter.writeSuccess(this, commandId, type,
                    JSONObject().apply {
                        put("alarms", arr)
                        put("count", alarms.size)
                    })
            }

            "alarm_create" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val hour = params.optInt("hour", -1)
                val minute = params.optInt("minute", -1)
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Invalid time: hour must be 0-23, minute must be 0-59")
                    return
                }
                val title = params.optString("title", "")
                try {
                    val triggerTime = com.repository.listener.alarm.AlarmStore.computeNextTrigger(hour, minute)
                    val alarm = com.repository.listener.alarm.AlarmItem(
                        id = 0, hour = hour, minute = minute, title = title,
                        enabled = true, triggerTimeMillis = triggerTime,
                        createdAt = System.currentTimeMillis()
                    )
                    val saved = com.repository.listener.alarm.AlarmStore.save(this, alarm)
                    sendBroadcast(Intent(ACTION_ALARM_CHANGED).apply { setPackage(packageName) })
                    AdbResultWriter.writeSuccess(this, commandId, type,
                        JSONObject().apply {
                            put("alarm_id", saved.id)
                            put("time", String.format("%02d:%02d", hour, minute))
                            put("title", title)
                            put("trigger_time_millis", saved.triggerTimeMillis)
                        })
                } catch (e: Exception) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Failed to create alarm: ${e.message}")
                }
            }

            "alarm_delete" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val id = params.optInt("id", -1)
                val hour = params.optInt("hour", -1)
                val minute = params.optInt("minute", -1)
                val title = params.optString("title", "")
                try {
                    val alarm = when {
                        id > 0 -> com.repository.listener.alarm.AlarmStore.getAll(this).find { it.id == id }
                        hour in 0..23 && minute in 0..59 -> com.repository.listener.alarm.AlarmStore.findByTime(this, hour, minute)
                        title.isNotEmpty() -> com.repository.listener.alarm.AlarmStore.findByTitle(this, title)
                        else -> null
                    }
                    if (alarm == null) {
                        AdbResultWriter.writeError(this, commandId, type,
                            "No matching alarm found")
                        return
                    }
                    com.repository.listener.alarm.AlarmStore.delete(this, alarm.id)
                    sendBroadcast(Intent(ACTION_ALARM_CHANGED).apply { setPackage(packageName) })
                    AdbResultWriter.writeSuccess(this, commandId, type,
                        JSONObject().apply {
                            put("alarm_id", alarm.id)
                            put("deleted", true)
                        })
                } catch (e: Exception) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Failed to delete alarm: ${e.message}")
                }
            }

            // ── Job ADB commands (async, orchestrator-backed) ──

            "job_list" -> {
                if (!orchestratorClient.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type, "Orchestrator not connected")
                    return
                }
                val pending = PendingAdbJobCommand(commandId, type, System.currentTimeMillis())
                pendingAdbJobCommands[commandId] = pending
                val timeoutRunnable = Runnable {
                    pendingAdbJobCommands.remove(commandId)?.let {
                        AdbResultWriter.writeError(this, commandId, type,
                            "Timeout after ${ADB_JOB_TIMEOUT_MS / 1000}s waiting for orchestrator")
                    }
                }
                pending.timeoutRunnable = timeoutRunnable
                mainHandler.postDelayed(timeoutRunnable, ADB_JOB_TIMEOUT_MS)
                serviceScope.launch(Dispatchers.IO) {
                    if (!orchestratorClient.sendJobList()) {
                        pendingAdbJobCommands.remove(commandId)
                        mainHandler.removeCallbacks(timeoutRunnable)
                        AdbResultWriter.writeError(this@ListenerService, commandId, type,
                            "Failed to send job_list to orchestrator")
                    }
                }
            }

            "job_create" -> {
                if (!orchestratorClient.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type, "Orchestrator not connected")
                    return
                }
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val name = params.optString("name", "")
                val prompt = params.optString("prompt", "")
                val scheduledAt = params.optString("scheduled_at", "")
                if (name.isEmpty() || prompt.isEmpty() || scheduledAt.isEmpty()) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Missing required params: name, prompt, scheduled_at")
                    return
                }
                val pending = PendingAdbJobCommand(commandId, type, System.currentTimeMillis())
                pendingAdbJobCommands[commandId] = pending
                val timeoutRunnable = Runnable {
                    pendingAdbJobCommands.remove(commandId)?.let {
                        AdbResultWriter.writeError(this, commandId, type,
                            "Timeout after ${ADB_JOB_TIMEOUT_MS / 1000}s waiting for orchestrator")
                    }
                }
                pending.timeoutRunnable = timeoutRunnable
                mainHandler.postDelayed(timeoutRunnable, ADB_JOB_TIMEOUT_MS)
                serviceScope.launch(Dispatchers.IO) {
                    if (!orchestratorClient.sendJobCreate(name, prompt, scheduledAt)) {
                        pendingAdbJobCommands.remove(commandId)
                        mainHandler.removeCallbacks(timeoutRunnable)
                        AdbResultWriter.writeError(this@ListenerService, commandId, type,
                            "Failed to send job_create to orchestrator")
                    }
                }
            }

            "job_delete" -> {
                if (!orchestratorClient.isConnected) {
                    AdbResultWriter.writeError(this, commandId, type, "Orchestrator not connected")
                    return
                }
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val id = params.optString("id", "")
                if (id.isEmpty()) {
                    AdbResultWriter.writeError(this, commandId, type, "Missing required param: id")
                    return
                }
                val pending = PendingAdbJobCommand(commandId, type, System.currentTimeMillis())
                pendingAdbJobCommands[commandId] = pending
                val timeoutRunnable = Runnable {
                    pendingAdbJobCommands.remove(commandId)?.let {
                        AdbResultWriter.writeError(this, commandId, type,
                            "Timeout after ${ADB_JOB_TIMEOUT_MS / 1000}s waiting for orchestrator")
                    }
                }
                pending.timeoutRunnable = timeoutRunnable
                mainHandler.postDelayed(timeoutRunnable, ADB_JOB_TIMEOUT_MS)
                serviceScope.launch(Dispatchers.IO) {
                    if (!orchestratorClient.sendJobDelete(id)) {
                        pendingAdbJobCommands.remove(commandId)
                        mainHandler.removeCallbacks(timeoutRunnable)
                        AdbResultWriter.writeError(this@ListenerService, commandId, type,
                            "Failed to send job_delete to orchestrator")
                    }
                }
            }

            "set_display_position" -> {
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val normalizedY = params.optDouble("normalized_y", 0.5).toFloat().coerceIn(0f, 1f)
                val maxBottomMargin = 200
                val bottomMargin = ((1.0f - normalizedY) * maxBottomMargin).toInt()

                AppConfig.setDisplayPositionY(this, normalizedY)

                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                    put("normalized_y", normalizedY.toDouble())
                    put("bottom_margin_px", bottomMargin)
                })

                // Send to glasses app (CH_SETTINGS -- the only live settings channel)
                try { phoneBtHost.sendSettings(JSONObject().apply {
                    put("settings_screen_ui_bottom_margin", bottomMargin.toString())
                }.toString()) }
                catch (e: Exception) { LogCollector.e(TAG, "Failed sendSettings: ${e.message}") }
            }

            "test_location" -> {
                // Direct location test: bypasses the orchestrator and calls
                // LocationProvider.getCurrentLocation() directly. The result
                // JSON (including the "source" field = "locator" or "fused")
                // is written to the ADB result file for autonomous testing.
                val startMs = System.currentTimeMillis()
                locationProvider.getCurrentLocation { locationJson ->
                    val elapsed = System.currentTimeMillis() - startMs
                    if (locationJson != null) {
                        val enriched = JSONObject(locationJson.toString()).apply {
                            put("elapsed_ms", elapsed)
                        }
                        AdbResultWriter.writeSuccess(this, commandId, type, enriched)
                    } else {
                        AdbResultWriter.writeError(this, commandId, type,
                            "Location resolution failed after ${elapsed}ms")
                    }
                }
            }

            "debug_dns" -> {
                // Resolve a list of hostnames from inside the app process so we
                // can see what DNS returns through whatever VPN/routing is active.
                // Params: {"hosts":["a.b.c","d.e.f"]}; defaults to a Yandex-centric set.
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val defaults = listOf(
                    "locator.api.maps.yandex.ru",
                    "api.maps.yandex.ru",
                    "yandex.ru",
                    "api.ipify.org",
                    "google.com"
                )
                val hostList = params.optJSONArray("hosts")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: defaults
                Thread {
                    val results = JSONObject()
                    for (h in hostList) {
                        try {
                            val addrs = java.net.InetAddress.getAllByName(h)
                                .map { it.hostAddress }
                            results.put(h, JSONObject().apply {
                                put("ok", true)
                                put("addresses", org.json.JSONArray(addrs))
                            })
                        } catch (e: Exception) {
                            results.put(h, JSONObject().apply {
                                put("ok", false)
                                put("error", "${e.javaClass.simpleName}: ${e.message}")
                            })
                        }
                    }
                    AdbResultWriter.writeSuccess(this, commandId, type, results)
                }.start()
            }

            "set_locator_key" -> {
                // Test-only helper to swap the Locator API key at runtime so
                // the fallback path can be exercised without rebuilding.
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val key = params.optString("key", "")
                if (key.isBlank()) {
                    AdbResultWriter.writeError(this, commandId, type, "key cannot be empty")
                } else {
                    try {
                        com.repository.listener.config.AppConfig.setLocatorApiKey(this, key)
                        AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                            put("key_length", key.length)
                        })
                    } catch (e: Exception) {
                        AdbResultWriter.writeError(this, commandId, type,
                            "Failed to save key: ${e.message}")
                    }
                }
            }

            "rc_inject_event" -> {
                // Test hook: dispatches the same broadcasts the real RC flow uses so the
                // ChatsListFragment UI code path can be exercised without a live orchestrator.
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val sessionId = params.optString("sessionId", "")
                val action = params.optString("action", "")
                if (sessionId.isEmpty() || action.isEmpty()) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "rc_inject_event requires sessionId and action")
                    return
                }
                val workDir = params.optString("workDir", "")
                val text = params.optString("text", "")
                val isFinal = params.optBoolean("isFinal", true)
                when (action) {
                    "start" -> {
                        rcDumpState[sessionId] = RcDumpEntry(workDir, "active", false)
                        if (!rcSessionTurning.containsKey(sessionId)) {
                            rcSessionTurning[sessionId] = false
                            refreshRcNotification()
                        }
                        sendBroadcast(Intent(ACTION_RC_SESSION_START).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_RC_SESSION_ID, sessionId)
                            putExtra(EXTRA_RC_DATA, JSONObject().put("workDir", workDir).toString())
                        })
                    }
                    "thinking" -> {
                        thinkingRcStartTimes[sessionId] = System.currentTimeMillis()
                        rcDumpState[sessionId]?.let { existing ->
                            rcDumpState[sessionId] = existing.copy(turning = true)
                        }
                        markRcTurning(sessionId, true)
                        sendBroadcast(Intent(ACTION_RC_THINKING).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_RC_SESSION_ID, sessionId)
                            putExtra(EXTRA_RC_DATA, text)
                        })
                    }
                    "message" -> {
                        thinkingRcStartTimes.remove(sessionId)
                        // Mirror onRcMessage: detect a true->false transition for unread.
                        // If the entry doesn't exist yet (replay path for a session this process
                        // hasn't seen), create it now with the current turning value and
                        // unread=false -- we have no previous turning state, so we cannot detect a
                        // transition on the first observation.
                        val newTurning = !isFinal
                        if (rcDumpState[sessionId] == null) {
                            // Replay path: a "message" arrived for a sessionId we never saw a
                            // "start" for. Use whatever workDir came in this rc_inject_event.
                            rcDumpState[sessionId] = RcDumpEntry(workDir = workDir, status = "active", turning = newTurning, unread = false)
                        }
                        val turnFinished = resolveRcTurnTransition(sessionId, isFinal)
                        rcDumpState[sessionId]?.let { entry ->
                            if (entry.sessionName == null && text.isNotBlank()) {
                                rcDumpState[sessionId] = entry.copy(sessionName = text.take(50))
                            }
                        }
                        val unread = rcDumpState[sessionId]?.unread ?: false
                        // The projection is fed exactly as the live path feeds it, so a test hook
                        // exercises the real mirror rather than a second copy of it that can drift.
                        if (text.isNotBlank()) rcMirror.noteAssistantText(sessionId, text)
                        if (turnFinished) {
                            rcTurnFinishRunnables.remove(sessionId)?.let { mainHandler.removeCallbacks(it) }
                            val commitRunnable = Runnable {
                                rcTurnFinishRunnables.remove(sessionId)
                                val rows = rcMirror.commitTurn(sessionId)
                                rcSeenToolCallIds.clear()
                                if (rcBridge.openSessionId == sessionId &&
                                    rcBridge.pushRows(sessionId, rows)) {
                                    markRcRead(sessionId)
                                }
                                pushRcState(force = true)
                            }
                            rcTurnFinishRunnables[sessionId] = commitRunnable
                            mainHandler.postDelayed(commitRunnable, RC_DONE_DEBOUNCE_MS)
                            sendBroadcast(Intent(ACTION_RC_UNREAD_CHANGED).apply {
                                setPackage(packageName)
                                putExtra(EXTRA_RC_SESSION_ID, sessionId)
                                putExtra(EXTRA_RC_UNREAD, true)
                            })
                        }
                        val msgData = JSONObject().apply {
                            put("text", text)
                            put("isFinal", isFinal)
                            put("unread", unread)
                        }.toString()
                        if (rcSessionTurning.containsKey(sessionId)) {
                            markRcTurning(sessionId, newTurning)
                        }
                        sendBroadcast(Intent(ACTION_RC_MESSAGE).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_RC_SESSION_ID, sessionId)
                            putExtra(EXTRA_RC_DATA, msgData)
                        })
                        // Last, exactly as the live path does it: markRcTurning above mutates the
                        // very fields the snapshot reports, so pushing earlier would send a frame
                        // that is one step stale.
                        pushRcState()
                    }
                    "end" -> {
                        thinkingRcStartTimes.remove(sessionId)
                        // Keep the entry so the ended session lingers in All view;
                        // "Only open" filter excludes status!="active".
                        // discovered=false for the same reason as the live onRcSessionEnd path:
                        // a REST-owned entry would be deleted by the next reconcile.
                        rcDumpState[sessionId]?.let { existing ->
                            rcDumpState[sessionId] = existing.copy(status = "ended", turning = false, discovered = false)
                        }
                        cancelRcDoneClear(sessionId)
                        rcSessionTurning.remove(sessionId)
                        refreshRcNotification()
                        sendBroadcast(Intent(ACTION_RC_SESSION_END).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_RC_SESSION_ID, sessionId)
                        })
                    }
                    "markRead" -> {
                        android.util.Log.i(TAG, "rc_inject_event markRead sessionId=$sessionId")
                        markRcRead(sessionId)
                    }
                    else -> {
                        AdbResultWriter.writeError(this, commandId, type,
                            "Unknown action: $action (expected start|thinking|message|end|markRead)")
                        return
                    }
                }
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                    put("sessionId", sessionId)
                    put("action", action)
                    if (action == "message") put("isFinal", isFinal)
                })
            }

            "chatlist_dump" -> {
                // Rebuild what the UI would render from the service-level rcDumpState
                // mirror plus the process-wide showOnlyOpen. Works regardless of
                // whether the ChatsListFragment is currently foreground.
                val sessions = rcDumpState.entries.map { (sid, e) ->
                    com.repository.listener.ui.ChatListItem.RemoteControlSession(
                        sessionId = sid,
                        workDir = e.workDir,
                        status = e.status,
                        lastMessage = null,
                        startedAt = 0L,
                        sessionName = null,
                        turning = e.turning,
                        unread = e.unread
                    )
                }
                val onlyOpen = com.repository.listener.ui.ChatsListFragment.showOnlyOpen
                val filtered = if (onlyOpen) {
                    sessions.filter { it.status == "active" }
                } else {
                    sessions
                }.sortedByDescending { it.startedAt }
                val arr = org.json.JSONArray()
                for (rc in filtered) {
                    arr.put(JSONObject().apply {
                        put("type", "rc")
                        put("sessionId", rc.sessionId)
                        put("workDir", rc.workDir)
                        put("status", rc.status)
                        put("turning", rc.turning)
                        put("unread", rc.unread)
                    })
                }
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                    put("items", arr)
                    put("count", arr.length())
                    put("showOnlyOpen", onlyOpen)
                })
            }

            "chatlist_select_folder" -> {
                // Toggle between "All" and "Only open" filter.
                // Pass show_only_open=true/false.
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val onlyOpen = params.optBoolean("show_only_open", false)
                com.repository.listener.ui.ChatsListFragment.showOnlyOpen = onlyOpen
                // Ask the fragment (if attached) to rebuild immediately so the UI
                // matches the ADB-driven selection.
                com.repository.listener.ui.ChatsListFragment.refreshCallback?.invoke()
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                    put("showOnlyOpen", onlyOpen)
                })
            }

            "nav_mock_start" -> {
                // Programmatic mock journey: plans + starts a route, then drives
                // the user along its polyline via Yandex's LocationSimulator so
                // the full journey pipeline (state machine, ETA, deviation,
                // streamer, ArrivalDetector) runs end-to-end without real GPS.
                // Params: {
                //   from_lat?, from_lng?,                 // optional; current GPS if absent
                //   to_lat?, to_lng?,                     // either coords ...
                //   to_address?,                          // ... or address (geocoded)
                //   mode?: "walking"|"driving"|"bicycle"|"transit",  // default walking
                //   speed_mps?: double
                // }
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val modeStr = params.optString("mode", "walking").uppercase()
                val mode = try {
                    com.repository.navigation.model.TransportMode.valueOf(modeStr)
                } catch (_: IllegalArgumentException) {
                    AdbResultWriter.writeError(this, commandId, type,
                        "Unknown mode: $modeStr (use walking|driving|bicycle|transit)")
                    return
                }
                val speedMps = if (params.has("speed_mps")) params.optDouble("speed_mps") else null
                val navManager = NavigationManager.getInstance(applicationContext)

                fun launch(from: com.yandex.mapkit.geometry.Point,
                           to: com.yandex.mapkit.geometry.Point) {
                    mainHandler.post {
                        navManager.startMockJourney(
                            from = from,
                            to = to,
                            mode = mode,
                            speedMps = speedMps,
                            onError = { msg ->
                                AdbResultWriter.writeError(this, commandId, type, msg)
                            }
                        ) { sessionId, etaSeconds ->
                            AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                                put("session_id", sessionId)
                                put("eta_seconds", etaSeconds)
                                put("mode", mode.name)
                                put("speed_mps", speedMps ?: -1.0)
                                put("from_lat", from.latitude)
                                put("from_lng", from.longitude)
                                put("to_lat", to.latitude)
                                put("to_lng", to.longitude)
                            })
                        }
                    }
                }

                fun resolveTo(cb: (com.yandex.mapkit.geometry.Point?) -> Unit) {
                    val toLat = params.optDouble("to_lat", Double.NaN)
                    val toLng = params.optDouble("to_lng", Double.NaN)
                    if (!toLat.isNaN() && !toLng.isNaN()) {
                        cb(com.yandex.mapkit.geometry.Point(toLat, toLng))
                        return
                    }
                    val toAddress = params.optString("to_address", "")
                    if (toAddress.isBlank()) { cb(null); return }
                    mainHandler.post {
                        MapProviders.active.geocoder.geocode(toAddress) { p -> cb(p) }
                    }
                }

                fun resolveFrom(cb: (com.yandex.mapkit.geometry.Point?) -> Unit) {
                    val fromLat = params.optDouble("from_lat", Double.NaN)
                    val fromLng = params.optDouble("from_lng", Double.NaN)
                    if (!fromLat.isNaN() && !fromLng.isNaN()) {
                        cb(com.yandex.mapkit.geometry.Point(fromLat, fromLng))
                        return
                    }
                    // Single provider-neutral GPS fix with a hard timeout so the
                    // ADB command never wedges if location is unavailable.
                    mainHandler.post {
                        val done = java.util.concurrent.atomic.AtomicBoolean(false)
                        NavigationManager.getInstance(applicationContext)
                            .getLocationEngine().requestSingleFix { fix ->
                                if (fix != null && done.compareAndSet(false, true)) {
                                    cb(com.yandex.mapkit.geometry.Point(fix.lat, fix.lng))
                                }
                            }
                        mainHandler.postDelayed({
                            if (done.compareAndSet(false, true)) cb(null)
                        }, 8_000L)
                    }
                }

                resolveTo { to ->
                    if (to == null) {
                        AdbResultWriter.writeError(this, commandId, type,
                            "Could not resolve destination (need to_lat+to_lng or to_address)")
                        return@resolveTo
                    }
                    resolveFrom { from ->
                        if (from == null) {
                            AdbResultWriter.writeError(this, commandId, type,
                                "Could not resolve origin: no from_lat/from_lng and no GPS fix in 8s")
                            return@resolveFrom
                        }
                        launch(from, to)
                    }
                }
            }

            "nav_force_transmit" -> {
                // Test/dev: force map-transmit gate true regardless of
                // wear/screen state. Used when wear sensor simulation isn't
                // wired and the streamer is otherwise idle. Pass {"on":false}
                // to release the override.
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val on = params.optBoolean("on", true)
                val navManager = NavigationManager.getInstance(applicationContext)
                navManager.setMapTransmitEnabled(on)
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                    put("forced", on)
                })
            }

            "nav_set_provider" -> {
                // Test/dev: switch the map provider ("yandex"|"google") at runtime,
                // the same switch the Config UI performs. Persists to AppConfig and
                // swaps the active MapProviders entry so the next render uses it.
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val provider = params.optString("provider", "").lowercase()
                if (provider != AppConfig.MAP_PROVIDER_YANDEX && provider != AppConfig.MAP_PROVIDER_GOOGLE) {
                    AdbResultWriter.writeError(this, commandId, type, "provider must be 'yandex' or 'google'")
                } else {
                    AppConfig.setMapProvider(this, provider)
                    mainHandler.post {
                        val nav = NavigationManager.getInstance(applicationContext)
                        val before = com.repository.navigation.provider.MapProviders.active.id
                        val after = com.repository.navigation.provider.MapProviders.switchTo(applicationContext, provider)
                        if (after != before) nav.rebindProvider()
                        AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                            put("requested", provider)
                            put("active", after)
                        })
                    }
                }
            }

            "nav_set_zoom" -> {
                // Test/dev: set the minimap zoom fraction (0..1) directly, the same
                // value the glasses zoom slider would send via the nav_zoom device
                // command. Lets a test drive zoom over ADB without simulating keys.
                val params = try { JSONObject(paramsStr) } catch (_: Exception) { JSONObject() }
                val fraction = params.optDouble("fraction", Double.NaN)
                if (fraction.isNaN()) {
                    AdbResultWriter.writeError(this, commandId, type, "missing 'fraction' (0..1)")
                } else {
                    val f = fraction.toFloat().coerceIn(0f, 1f)
                    mainHandler.post {
                        NavigationManager.getInstance(applicationContext).setMinimapZoomFraction(f)
                    }
                    AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                        put("fraction", f.toDouble())
                    })
                }
            }

            "nav_test_status" -> {
                // Phone-side ground truth: returns the navigation state the
                // phone is driving (current step index, route, transit
                // sections + their endpoints, and where the simulated user
                // currently is). The test harness pairs this with the
                // glasses' nav_goal_status to validate that the displayed
                // step matches the phone's intent and switches at the right
                // location.
                val navManager = NavigationManager.getInstance(applicationContext)
                val state = navManager.getState()
                val route = navManager.getCurrentRoute()
                val session = navManager.getCurrentSession()
                val stepIdx = navManager.getCurrentStepIndex()
                val locSrc = navManager.getLocationSource()

                val data = JSONObject().apply {
                    put("state", state.name)
                    put("mock_active", navManager.isMockJourney)
                    put("current_step_index", stepIdx)
                    if (session != null) {
                        put("session_id", session.id)
                        put("transport_mode", session.transportMode.name)
                        put("from_lat", session.from.latitude)
                        put("from_lng", session.from.longitude)
                        put("to_lat", session.to.latitude)
                        put("to_lng", session.to.longitude)
                        put("created_at_ms", session.createdAt)
                        put("elapsed_ms", System.currentTimeMillis() - session.createdAt)
                    }
                    if (route != null) {
                        put("eta_seconds", route.etaSeconds)
                        put("distance_meters", route.distanceMeters)
                        put("polyline_point_count", route.polylinePoints.size)
                        put("densified_point_count", navManager.getDensifiedRoute().size)
                        put("step_count", route.transitSections.size)
                        // Per-section endpoint coords so the harness can
                        // compute distance from the current sim position to
                        // the upcoming step's end and verify the 50m
                        // confirmation gate actually fires near it.
                        val sectionsArr = org.json.JSONArray()
                        route.transitSections.forEachIndexed { i, sec ->
                            val end = sec.polylinePoints.lastOrNull()
                            sectionsArr.put(JSONObject().apply {
                                put("index", i)
                                put("type", sec.type.name)
                                put("distance_m", sec.distanceMeters)
                                put("duration_s", sec.durationSeconds)
                                sec.lineName?.let { put("line_name", it) }
                                if (end != null) {
                                    put("end_lat", end.latitude)
                                    put("end_lng", end.longitude)
                                }
                            })
                        }
                        put("sections", sectionsArr)
                    }
                    val fix = navManager.getLastKnownLocation()
                    if (fix != null) {
                        put("sim_lat", fix.lat)
                        put("sim_lng", fix.lng)
                        fix.headingDeg?.let { put("sim_heading_deg", it) }
                        fix.speedMps?.let { put("sim_speed_mps", it) }
                        put("sim_age_ms", System.currentTimeMillis() - fix.atMs)
                    }

                    if (fix != null && route != null) {
                        val slat = fix.lat; val slng = fix.lng
                        val distArr = org.json.JSONArray()
                        route.transitSections.forEachIndexed { i, sec ->
                            val end = sec.polylinePoints.lastOrNull()
                            if (end != null) {
                                val dLat = Math.toRadians(end.latitude - slat)
                                val dLng = Math.toRadians(end.longitude - slng)
                                val a = Math.sin(dLat / 2).let { it * it } +
                                    Math.cos(Math.toRadians(slat)) * Math.cos(Math.toRadians(end.latitude)) *
                                    Math.sin(dLng / 2).let { it * it }
                                val d = 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
                                distArr.put(JSONObject().apply {
                                    put("section_index", i)
                                    put("dist_m", d.toInt())
                                })
                            }
                        }
                        put("distance_to_section_ends", distArr)
                    }
                }
                AdbResultWriter.writeSuccess(this, commandId, type, data)
            }

            "nav_mock_stop" -> {
                val navManager = NavigationManager.getInstance(applicationContext)
                val wasMock = navManager.isMockJourney
                mainHandler.post { navManager.stopMockJourney() }
                AdbResultWriter.writeSuccess(this, commandId, type, JSONObject().apply {
                    put("was_mock_active", wasMock)
                })
            }

            else -> {
                AdbResultWriter.writeError(this, commandId, type,
                    "Unknown command type: $type")
            }
        }
    }

    override fun onRequestAck(requestId: String) {
        // Server confirmed receipt before doing any LLM work. Two effects:
        // 1) Clear pendingGlassesRetry so a subsequent WS heartbeat death
        //    can't redrive this request and produce a duplicate answer.
        // 2) Stamp ack-arrival into the latency log so we can split
        //    network-round-trip from AI-thinking time when the user complains
        //    about slow responses.
        val sentAt = orchestratorClient.requestSentAtMs[requestId]
        val rttMs = if (sentAt != null) System.currentTimeMillis() - sentAt else -1L
        LogCollector.i(TAG, "Chat request_ack: req=${requestId.take(8)} rtt=${rttMs}ms (network leg)")
        pendingGlassesRetry = null
    }

    override fun onResponse(requestId: String, text: String, status: String, data: JSONObject?, totalTokens: Int) {
        LogCollector.i(TAG, "Orchestrator response (status=$status tokens=$totalTokens): ${text.take(200)}")
        val sentAt = orchestratorClient.requestSentAtMs[requestId]
        if (sentAt != null) {
            val totalMs = System.currentTimeMillis() - sentAt
            LogCollector.i(TAG, "Chat total latency: req=${requestId.take(8)} sent->response=${totalMs}ms tokens=$totalTokens")
        }
        // Keep sent-time around for the trailing TTS stream so the first-TTS
        // latency can still be computed after the response frame lands.
        // Expire the entry 60s later as a safety net against leaks.
        mainHandler.postDelayed({
            orchestratorClient.requestSentAtMs.remove(requestId)
            chatFirstStreamLogged.remove(requestId)
            chatFirstTtsLogged.remove(requestId)
        }, 60_000L)

        // Check if this response is for a pending ADB chat command -- capture result
        // but do NOT return early so the normal glasses/phone UI flow also fires.
        val pendingChat = pendingAdbChatCommands.remove(requestId)
        if (pendingChat != null) {
            pendingChat.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            val elapsedMs = System.currentTimeMillis() - pendingChat.startTimeMs
            LogCollector.i(TAG, "ADB chat_send response: commandId=${pendingChat.commandId} elapsed=${elapsedMs}ms tokens=$totalTokens")
            val responseData = JSONObject().apply {
                put("text", text)
                put("request_id", requestId)
                put("status", status)
                put("total_tokens", totalTokens)
                put("elapsed_ms", elapsedMs)
                if (pendingChat.toolCalls.isNotEmpty()) {
                    val toolArr = org.json.JSONArray()
                    for (tc in pendingChat.toolCalls) toolArr.put(tc)
                    put("tool_calls", toolArr)
                }
            }
            if (status == "error") {
                AdbResultWriter.writeError(this, pendingChat.commandId, pendingChat.type,
                    text.ifEmpty { "Orchestrator returned error" })
            } else {
                AdbResultWriter.writeSuccess(this, pendingChat.commandId, pendingChat.type, responseData)
            }
            // Fall through to normal UI flow below (glasses relay or phone broadcast)
        }

        // Check if this response is for a glasses-originated request (includes ADB glasses commands)
        if (requestId in glassesRequestIds) {
            pendingGlassesRetry = null  // Request succeeded, clear retry
            // Always relay response text to glasses -- state may be IDLE if TTS from a previous
            // request finished during a multi-turn exchange. Cancellation removes the requestId
            // from glassesRequestIds, so being here means the response is still wanted.
            if (glassesAudioState == GlassesAudioState.IDLE) {
                setGlassesState(GlassesAudioState.RESPONDING, "onResponse for pending glasses request")
            }
            phoneBtHost.sendResponse(requestId, text, totalTokens)
            phoneBtHost.sendStreamingText(requestId, text)
            if (status == "error") {
                setGlassesState(GlassesAudioState.IDLE, "orchestrator error")
                phoneBtHost.sendDismissSession()
            }
            return
        }

        // Phone flow: broadcast to phone chat UI
        broadcastChatMessage(requestId, "ASSISTANT", text)
        broadcastMetadata(requestId, totalTokens)

        // For ADB phone commands, skip voice pipeline management (wake word, TTS player, dismiss)
        if (pendingChat != null) return

        ttsPlayer.interrupt()

        // TTS is only sent for success responses with text -- wait for TTS to finish.
        // For errors or empty responses, dismiss immediately.
        if (status != "success" || text.isEmpty()) {
            mainHandler.post { dismiss() }
        } else {
            // Transition to RESPONDING immediately so TTS chunks arriving on this same
            // OkHttp thread are accepted (the coroutine in finishRecording may not have
            // set the state yet when the server responds very quickly).
            if (state == State.FINISHING) {
                state = State.RESPONDING
                wakeWordDetector.setMode(WakeWordDetector.Mode.TTS_INTERRUPT)
                wakeWordDetector.reset()
                LogCollector.i(TAG, "State -> RESPONDING (from onResponse)")
            }
            // Schedule safety timeout in case TTS audio never arrives
            mainHandler.removeCallbacks(ttsTimeoutRunnable)
            mainHandler.postDelayed(ttsTimeoutRunnable, ttsTimeoutMs)
        }
    }

    override fun onAssistantResult(requestId: String, cards: org.json.JSONArray, dismiss: org.json.JSONArray) {
        serviceScope.launch { onCopilotResultReceived(requestId, cards, dismiss) }
    }

    override fun onStreamingText(requestId: String, partialText: String, isFinal: Boolean) {
        // First-token latency: time from send to first streaming frame.
        // Together with request_ack RTT this lets us distinguish a slow LLM
        // ("ack fast, first token slow") from a slow network ("ack slow,
        // first token slow by the same amount").
        if (chatFirstStreamLogged.add(requestId)) {
            val sentAt = orchestratorClient.requestSentAtMs[requestId]
            if (sentAt != null) {
                val ttftMs = System.currentTimeMillis() - sentAt
                LogCollector.i(TAG, "Chat first stream token: req=${requestId.take(8)} sent->first-token=${ttftMs}ms")
            }
        }

        // Accumulate streaming text for pending ADB chat commands
        pendingAdbChatCommands[requestId]?.streamedText?.set(partialText)

        // Relay streaming text to glasses in real-time, but don't broadcast on phone UI
        if (requestId in glassesRequestIds) {
            if (glassesAudioState == GlassesAudioState.IDLE) {
                setGlassesState(GlassesAudioState.RESPONDING, "onStreamingText for pending glasses request")
            }
            phoneBtHost.sendStreamingText(requestId, partialText)
            return
        }

        // Broadcast streaming text for phone chat tab
        val json = JSONObject().apply {
            put("requestId", requestId)
            put("partialText", partialText)
            put("isFinal", isFinal)
        }.toString()
        sendBroadcast(Intent(ACTION_STREAMING_TEXT).apply {
            setPackage(packageName)
            putExtra(EXTRA_STREAMING_TEXT, json)
        })

    }

    override fun onToolStatus(requestId: String, toolName: String, status: String, toolArgs: JSONObject?, toolCallId: String) {
        LogCollector.i(TAG, "Tool status ($requestId): $toolName -> $status")
        broadcastToolStatus(requestId, toolName, status, toolArgs, toolCallId)

        // Track tool calls for pending ADB chat commands
        pendingAdbChatCommands[requestId]?.toolCalls?.add(JSONObject().apply {
            put("tool_name", toolName)
            put("status", status)
            if (toolArgs != null) put("tool_args", toolArgs)
            put("tool_call_id", toolCallId)
        })

        // Relay to glasses if it's their request
        if (requestId in glassesRequestIds) {
            if (glassesAudioState != GlassesAudioState.SENDING && glassesAudioState != GlassesAudioState.RESPONDING) {
                return
            }
            // Reset response timeout -- orchestrator is still working
            phoneBtHost.sendToolStatus(requestId, toolName, status, toolArgs, toolCallId)
        }
    }

    override fun onTtsAudio(requestId: String, audioBase64: String, sentenceIndex: Int, totalSentences: Int, text: String, isFinal: Boolean) {
        if (chatFirstTtsLogged.add(requestId)) {
            val sentAt = orchestratorClient.requestSentAtMs[requestId]
            if (sentAt != null) {
                val ttsMs = System.currentTimeMillis() - sentAt
                LogCollector.i(TAG, "Chat first TTS frame: req=${requestId.take(8)} sent->first-tts=${ttsMs}ms")
            }
        }
        val rawBytes = try { android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT) } catch (_: Exception) { null }
        val rawSize = rawBytes?.size ?: 0
        val header = if (rawBytes != null && rawBytes.size >= 4) String(rawBytes, 0, 4, Charsets.US_ASCII) else "?"
        val format = if (header == "OggS") "Opus" else if (header == "RIFF") "WAV" else "unknown($header)"
        val wavInfo = if (header == "RIFF" && rawBytes != null && rawBytes.size > 44) {
            val bb = java.nio.ByteBuffer.wrap(rawBytes, 0, 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.position(22); val ch = bb.short; val sr = bb.int; bb.position(34); val bits = bb.short
            " sr=${sr}Hz ch=${ch} bits=${bits}"
        } else ""
        LogCollector.i(TAG, "TTS audio IN: req=$requestId sentence=${sentenceIndex+1}/$totalSentences format=$format rawBytes=$rawSize base64Len=${audioBase64.length}$wavInfo final=$isFinal text='${text.take(40)}'")

        // Relay TTS audio to glasses if it's their request
        if (requestId in glassesRequestIds) {
            if (glassesAudioState != GlassesAudioState.SENDING && glassesAudioState != GlassesAudioState.RESPONDING) {
                LogCollector.i(TAG, "Glasses TTS dropped: state=$glassesAudioState (cancelled)")
                glassesRequestIds.remove(requestId)
                return
            }
            LogCollector.i(TAG, "TTS relay to glasses: $format $rawSize bytes")
            phoneBtHost.sendTtsAudio(requestId, audioBase64, sentenceIndex, totalSentences, text, isFinal)
            if (isFinal) {
                setGlassesState(GlassesAudioState.IDLE, "TTS final")
            }
            return
        }

        if (state != State.RESPONDING) return
        LogCollector.d(TAG, "TTS audio: sentence $sentenceIndex/$totalSentences $format $rawSize bytes")
        ttsPlayer.enqueue(requestId, audioBase64, isFinal)
    }

    override fun onNotificationTtsAudio(notifId: String, audioBase64: String, isFinal: Boolean) {
        LogCollector.i(TAG, "Notification TTS audio: notifId=$notifId (${audioBase64.length} chars, final=$isFinal)")
        // Continuation TTS for a merged same-sender message: play it on the real
        // on-screen notification's overlay, bypassing the queue state machine.
        // Notification TTS streams in MULTIPLE chunks (isFinal=false until the last),
        // so PEEK with get() for every chunk and only remove the mapping on the final
        // chunk -- otherwise chunks 2..N would misroute and the continuation would be
        // truncated to the first ~250ms.
        val realNotifId = appendTtsContToNotif[notifId]
        if (realNotifId != null) {
            if (isFinal) appendTtsContToNotif.remove(notifId)
            if (audioBase64.isNotEmpty()) {
                LogCollector.i(TAG, "Continuation TTS audio for merged append -> playing on $realNotifId (final=$isFinal)")
                phoneBtHost.sendNotificationTtsAudio(realNotifId, audioBase64, isFinal)
            }
            return
        }
        notificationQueue.onTtsAudio(notifId, audioBase64, isFinal)
    }

    override fun onServerError(message: String, epoch: Int) {
        if (serviceDestroyed) return
        LogCollector.e(TAG, "Orchestrator error: $message")

        // If error is about desktop not connected, treat as audio relay error. This is
        // about the CLOUD link only -- a LAN-direct session talks to the desktop
        // straight and stays healthy, so tearing it down here would kill working audio
        // (and the reconnect that follows could leave two sessions on the desktop).
        // The orchestrator sends an identical "Target device '<id>' not connected" for
        // BOTH audio-relay and screen-stream requests, so the text cannot tell them
        // apart. Only react while OUR start is in flight and not yet established --
        // otherwise a failed screen-stream request would tear down healthy audio.
        val desktopOffline = message.contains("not connected", ignoreCase = true) &&
            message.contains("desktop", ignoreCase = true)
        // Epoch-guard as well: this may be the reply to a stop we sent for a session
        // that has since been superseded (the LAN->cloud fallback starts a new attempt
        // immediately), and acting on it would tear down that fresh attempt.
        if (desktopOffline && epoch == audioRelaySessionEpoch &&
            !audioRelayViaLan && audioRelayStartInFlight && !audioRelayActive
        ) {
            retireAudioRelaySession()
            audioRelayActive = false
            currentAudioStreamId = -1
            clearAudioRelayStartInFlight()
            audioRelayRetryRunnable?.let { mainHandler.removeCallbacks(it) }
            audioRelayRetryRunnable = null
            // Close the dead peer: retiring the stream id above means its own
            // disconnect callback is now swallowed by the stale-stream guard, so
            // nothing else would tear it down or arm a retry -- audio would stay
            // silently dead until the user toggled it, with the desktop still
            // consuming its shared capture.
            webRTCClient?.close()
            releaseAudioRelayWifiLock()
            sendBroadcast(Intent(ACTION_AUDIO_RELAY_ERROR).apply {
                setPackage(packageName)
                putExtra(EXTRA_ERROR_REASON, "desktop_offline")
            })
            scheduleAudioRelayRetry()
        }

        // Show error on glasses if in an active state
        if (glassesAudioState != GlassesAudioState.IDLE) {
            phoneBtHost.sendStreamingText("error", "Error: $message")
            setGlassesState(GlassesAudioState.IDLE, "server error")
            phoneBtHost.sendDismissSession()
        }

        // Reset phone state too
        if (state != State.IDLE) {
            mainHandler.post { dismiss() }
        }
    }

    override fun onConnected() {
        orchestratorConnected = true
        LogCollector.i(TAG, "Connected to orchestrator")
        setIdleText("Connected - Waiting for command")
        broadcastState("IDLE", "Connected to orchestrator")
        // Populate todo cache for AI context injection
        orchestratorClient.sendTodoList()
        phoneBtHost.sendSystemStatus(true)
        // The glasses dim every RC row while ws is false; the flag is not an rcDumpState mutation,
        // so it has to be pushed from the WS lifecycle itself or it would never reach them.
        pushRcState(force = true)

        // Auto-restart audio relay ONLY if it isn't already streaming. The audio
        // path (LAN-direct or cloud WebRTC) is independent of this cloud-control
        // WebSocket: a brief 2.4GHz stall can bounce this WS while the LAN audio
        // stream is perfectly healthy. Blindly restarting here would tear down a
        // working stream and trigger a reconnect storm. Only restart when audio
        // is actually down.
        // This callback runs on the OkHttp reader thread, unlike every other relay
        // entry point. Starting from here would race the retry chain on main: both
        // read audioRelayStartInFlight before either arms it, so two sessions open
        // toward the desktop and only the second is tracked -- the first is a socket
        // the phone can never release, and the desktop's capture is split in half.
        mainHandler.post {
            if (serviceDestroyed) return@post
            if (AppConfig.getAudioRelayDesired(this) && !audioRelayActive) {
                val bitrate = AppConfig.getAudioBitrate(this)
                LogCollector.i(TAG, "Audio relay: auto-restarting (desired=true, not active, ${bitrate}bps)")
                startAudioRelay(bitrate)
            }
        }

        // Retry pending glasses request after reconnect
        val retry = pendingGlassesRetry
        pendingGlassesRetry = null
        if (retry != null && (glassesAudioState == GlassesAudioState.SENDING || glassesAudioState == GlassesAudioState.RESPONDING)) {
            val (text, model) = retry
            LogCollector.i(TAG, "Retrying glasses request after reconnect: '${text.take(60)}'")
            serviceScope.launch(Dispatchers.IO) {
                val userSystemPrompt = AiContextBuilder.build(
                    AppConfig.getUserSystemPrompt(this@ListenerService).ifBlank { null },
                    notificationHistory, cachedTodos
                )
                val sent = orchestratorClient.sendRequest(text, model = model, deviceType = "glasses", userSystemPrompt = userSystemPrompt)
                if (sent) {
                    val orchRequestId = orchestratorClient.lastRequestId
                    if (orchRequestId != null) {
                        glassesRequestIds.add(orchRequestId)
                        // Reset response timeout for the retry
                        // (no response timeout)
                        // (no response timeout)
                    }
                    LogCollector.i(TAG, "Glasses retry sent successfully (requestId=${orchestratorClient.lastRequestId})")
                } else {
                    LogCollector.e(TAG, "Glasses retry failed after reconnect")
                    mainHandler.post {
                        setGlassesState(GlassesAudioState.IDLE, "retry failed")
                        phoneBtHost.sendDismissSession()
                    }
                }
            }
        } else if (retry != null) {
            // Glasses state changed (user cancelled) - discard the retry
            LogCollector.i(TAG, "Discarding pending glasses retry - state is $glassesAudioState")
        }
    }

    override fun onDisconnected() {
        orchestratorConnected = false
        LogCollector.i(TAG, "Disconnected from orchestrator")
        // Do NOT tear down the WebRTC peer here. Signalling (this WS) and
        // media (the peer connection) are independent: a transient WS blip
        // shouldn't kill an already-negotiated audio session. The peer will
        // signal its own death via WebRTCClient.Listener.onWebRTCDisconnected
        // (ICE FAILED/CLOSED), which clears audioRelayActive and triggers retry.
        setIdleText("Disconnected - Waiting for command")
        broadcastState("IDLE", "Disconnected from orchestrator")
        phoneBtHost.sendSystemStatus(false)

        // No terminating rc_message can arrive once the WS is down, so any session left turning
        // would spin forever on the glasses. Clear the flags FIRST, then push.
        for (sid in rcDumpState.keys) {
            rcDumpState.computeIfPresent(sid) { _, e -> if (e.turning) e.copy(turning = false) else e }
        }
        pushRcState(force = true)

        // Drop chat-latency book-keeping for in-flight requests that will
        // never get an onResponse now -- otherwise the maps grow unbounded
        // across long sessions where some abort/timeout/cancel skips the
        // 60s scheduled cleanup. Any new request after reconnect minted
        // fresh requestIds anyway.
        orchestratorClient.requestSentAtMs.clear()
        chatFirstStreamLogged.clear()
        chatFirstTtsLogged.clear()
        // If glasses were waiting for a response, save the request for retry after reconnect
        if (glassesAudioState == GlassesAudioState.SENDING || glassesAudioState == GlassesAudioState.RESPONDING) {
            val lastText = pendingGlassesRetry?.first
            if (lastText != null) {
                LogCollector.w(TAG, "Orchestrator lost while glasses in $glassesAudioState - will retry on reconnect: '${lastText.take(60)}'")
            } else {
                LogCollector.w(TAG, "Orchestrator lost while glasses in $glassesAudioState - no pending text to retry, dismissing")
                setGlassesState(GlassesAudioState.IDLE, "orchestrator disconnected")
                phoneBtHost.sendDismissSession()
            }
        }
    }

    override fun onBinaryFrame(data: ByteArray) {
        LogCollector.w(TAG, "Unexpected binary frame on main WS (${data.size} bytes)")
    }

    override fun onStreamAck(streamId: Int, width: Int, height: Int, fps: Int, monitorCount: Int) {
        LogCollector.i(TAG, "Stream ACK: streamId=$streamId ${width}x${height}@${fps}fps monitors=$monitorCount")
        sendBroadcast(Intent(ACTION_STREAM_ACK).apply {
            setPackage(packageName)
            putExtra(EXTRA_STREAM_ID, streamId)
            putExtra(EXTRA_STREAM_WIDTH, width)
            putExtra(EXTRA_STREAM_HEIGHT, height)
            putExtra(EXTRA_STREAM_FPS, fps)
            putExtra(EXTRA_STREAM_MONITOR_COUNT, monitorCount)
        })
    }

    override fun onStreamEnded(streamId: Int) {
        LogCollector.i(TAG, "Stream ended: streamId=$streamId")
        orchestratorClient.closeStreamConnections(streamId)
        mouseEventListener = null
        keyboardEventListener = null
        // Audio is on WebRTC (separate P2P connection), not tied to stream sessions.
        // Do NOT close WebRTC audio when video/other streams end.
        sendBroadcast(Intent(ACTION_STREAM_ENDED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STREAM_ID, streamId)
        })
    }

    /**
     * Single decision point for starting audio relay. If the desktop's local
     * relay server is discovered on the LAN (same WiFi), signals directly to it
     * over [lanRelayClient]; otherwise routes through the cloud
     * [orchestratorClient]. All six call sites (UI intent, ADB, reconnect,
     * retry) funnel through here so the LAN-vs-cloud choice lives in one place.
     */
    private fun startAudioRelay(bitrate: Int) {
        if (serviceDestroyed) return
        // Coalesce concurrent start drivers (UI intent, ADB, cloud onConnected
        // auto-restart, and the retry chain can all fire near-simultaneously on a
        // flaky link). Without this guard each caller spins up its own
        // LanRelayClient / cloud-start that tears down the others -- the reconnect
        // storm. Once a start is in flight we ignore further starts until it
        // connects (audioRelayActive) or the attempt window elapses.
        if (audioRelayActive) {
            LogCollector.i(TAG, "Audio relay start ignored: already active")
            return
        }
        if (audioRelayStartInFlight) {
            LogCollector.i(TAG, "Audio relay start ignored: attempt already in flight")
            return
        }
        armAudioRelayStartGuard()

        // A non-bypassable VPN captures this app's UDP, so WebRTC gathers no usable
        // wlan candidate and only offers loopback + TCP ones. LAN signaling still
        // connects (the WS socket is bound to wlan), the desktop still offers, but
        // ICE can never pair -- audio silently never starts. The cloud path works
        // because it relays through TURN, so prefer it while such a VPN is up.
        val endpoint = if (primaryWifiNetwork() != null && !vpnCapturesAudioRelay()) {
            desktopRelayLocator?.endpoint()
        } else {
            null
        }
        LogCollector.i(
            TAG,
            "Audio relay transport: ${if (endpoint != null) "LAN" else "cloud"} " +
                "(wifi=${primaryWifiNetwork() != null} vpnCaptures=${vpnCapturesAudioRelay()} " +
                "endpoint=${desktopRelayLocator?.endpoint() != null})"
        )
        if (endpoint != null) {
            startAudioRelayViaLan(endpoint, bitrate)
        } else {
            startAudioRelayViaCloud(bitrate)
        }
    }

    /**
     * Watch for the VPN coming up or going down. Toggling it invalidates the transport the
     * live session picked: the tun swallows LAN media (LAN session goes silent) and coming
     * back down strands the session on the slower cloud relay. WebRTC only notices via a
     * 15s ICE grace, and the retry that follows may re-pick the wrong transport -- which is
     * the flaky, half-broken audio. Detect the transition directly and migrate immediately.
     */
    private fun registerAudioRelayVpnWatcher() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    reevaluate()
                }

                override fun onLost(network: android.net.Network) {
                    reevaluate()
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    reevaluate()
                }

                private fun reevaluate() {
                    // onCapabilitiesChanged fires often (signal strength, validation);
                    // coalesce so a burst causes at most one transport re-evaluation.
                    // Runs on a ConnectivityManager thread -- hop to main, which owns
                    // the recheck field.
                    mainHandler.post { scheduleAudioRelayNetworkRecheck(500) }
                }
            }
            // Watch this app's DEFAULT network: a VPN coming up or going down swaps it,
            // which is exactly the transition that invalidates the chosen transport.
            cm.registerDefaultNetworkCallback(callback)
            audioRelayVpnCallback = callback
            LogCollector.i(TAG, "Audio relay: VPN watcher registered")
        } catch (e: Exception) {
            LogCollector.w(TAG, "Audio relay: VPN watcher registration failed: ${e.message}")
        }
    }

    /**
     * Re-pick the audio-relay transport when the VPN flips. Only acts when the transport the
     * session is actually using no longer matches what the current network state allows, so a
     * VPN toggle that doesn't affect the choice leaves working audio untouched.
     */
    /**
     * Single owner of [audioRelayNetworkRecheck]. Both the capabilities debounce and the
     * in-flight deferral go through here (main thread only) so neither silently
     * reschedules the other's pending pass at the wrong delay.
     */
    private fun scheduleAudioRelayNetworkRecheck(delayMs: Long) {
        if (serviceDestroyed) return
        audioRelayNetworkRecheck?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            audioRelayNetworkRecheck = null
            onAudioRelayVpnStateChanged()
        }
        audioRelayNetworkRecheck = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun onAudioRelayVpnStateChanged() {
        if (serviceDestroyed) return
        if (!AppConfig.getAudioRelayDesired(this)) return
        // Defer while an attempt is under way, and also while nothing is established yet
        // but a start is expected to produce a session: dropping the transition would
        // leave that session on the transport the VPN change just invalidated. After the
        // cap, stop waiting but still evaluate rather than discarding the transition.
        val startPending = audioRelayStartInFlight ||
            (!audioRelayActive && audioRelayRetryRunnable != null)
        if (startPending && audioRelayVpnDeferrals < MAX_VPN_RECHECK_DEFERRALS) {
            audioRelayVpnDeferrals++
            LogCollector.i(TAG, "Audio relay: VPN recheck deferred (#$audioRelayVpnDeferrals)")
            scheduleAudioRelayNetworkRecheck(3_000)
            return
        }
        audioRelayVpnDeferrals = 0
        val wantLan = primaryWifiNetwork() != null &&
            !vpnCapturesAudioRelay() &&
            desktopRelayLocator?.endpoint() != null
        if (wantLan == audioRelayViaLan) return
        // Nothing established and no attempt pending -> nothing to migrate. (Without
        // this we would start a relay the user never asked for, since audioRelayViaLan
        // is false whenever the relay is idle.)
        if (!audioRelayActive && !startPending) return
        LogCollector.i(
            TAG,
            "Audio relay: VPN state changed, switching transport (viaLan=$audioRelayViaLan -> $wantLan)"
        )
        // Drop the doomed session and restart on the correct transport right away rather
        // than waiting out the ICE grace period.
        stopAudioRelay()
        startAudioRelay(AppConfig.getAudioBitrate(this))
    }

    /**
     * True when a VPN is active that this app's media traffic cannot escape, which makes
     * LAN-direct WebRTC unusable (no host candidate on wlan). A bypassable VPN is fine --
     * WebRTC can still gather wlan candidates through it.
     */
    private fun vpnCapturesAudioRelay(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            // Ask about THIS app's own default network rather than scanning every network.
            // If a VPN is bypassable or excludes our UID, the app's active network is the
            // real wlan and LAN-direct still works; only when our own default IS the tun
            // is our media actually captured.
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (e: Exception) {
            LogCollector.w(TAG, "vpnCapturesAudioRelay failed: ${e.message}")
            false
        }
    }

    /**
     * Mark a start attempt in flight and arm the window watchdog. Every path that
     * begins an attempt must use this: the guard stops concurrent drivers from opening
     * a second session toward the desktop (which would split its shared capture), and
     * the watchdog is the only thing that retries an attempt which never produced a
     * peer (no peer => no disconnect callback => no retry).
     */
    private fun armAudioRelayStartGuard() {
        audioRelaySessionEpoch++
        audioRelayStartInFlight = true
        audioRelayStartWatchdog?.let { mainHandler.removeCallbacks(it) }
        val watchdog = Runnable {
            audioRelayStartInFlight = false
            audioRelayStartWatchdog = null
            if (!audioRelayActive && AppConfig.getAudioRelayDesired(this)) {
                LogCollector.w(TAG, "Audio relay: start window elapsed with no session, retrying")
                // Retire FIRST. This teardown also clears the two guards that would
                // otherwise reject a late offer, so without retiring, an offer the
                // desktop queued for this session is adopted during the retry gap and
                // the retry then opens a SECOND session alongside it -- both consuming
                // the desktop's capture, each getting half the frames.
                retireAudioRelaySession()
                // The desktop may have acked and opened a peer that never paired; it is
                // still consuming its shared capture with the socket open. Release it or
                // the retry's session runs alongside it and both get half the frames.
                releaseAudioRelaySessionOnDesktop()
                // Close our side too. Its ICE may still pair later, and that connect
                // would mark this released session active and cancel the retry -- the
                // phone would think audio is live while the desktop has already let go.
                currentAudioStreamId = -1
                webRTCClient?.close()
                releaseAudioRelayWifiLock()
                // Clear the transport too. Leaving viaLan set with a live client makes
                // the cloud-offer guard drop an in-flight offer, so that cloud session
                // is never answered nor released and keeps consuming the desktop's
                // shared capture alongside the retry -- half frame rate.
                lanRelayClient?.let { it.listener = null; it.close() }
                lanRelayClient = null
                audioRelayViaLan = false
                scheduleAudioRelayRetry()
            }
        }
        audioRelayStartWatchdog = watchdog
        mainHandler.postDelayed(watchdog, AUDIO_RELAY_START_WINDOW_MS)
    }

    /**
     * Tell the desktop to drop whatever audio-relay session it holds for us on the
     * currently selected transport. The desktop reaps on socket close, but the cloud
     * socket is the long-lived orchestrator one and a LAN socket can outlive a failed
     * handshake -- so a session that never paired would otherwise keep consuming the
     * desktop's shared capture and halve the next session's frame rate.
     */
    private fun releaseAudioRelaySessionOnDesktop() {
        val sent = if (audioRelayViaLan) {
            lanRelayClient?.sendAudioRelayStop() ?: false
        } else {
            orchestratorClient.sendAudioRelayStop("desktop-listener")
        }
        if (!sent) {
            // The desktop reaps on socket close, so this is recoverable, but a silently
            // dropped release is worth seeing when diagnosing a halved frame rate.
            LogCollector.w(TAG, "Audio relay: could not send session release (viaLan=$audioRelayViaLan)")
        }
    }

    private fun clearAudioRelayStartInFlight() {
        audioRelayStartInFlight = false
        audioRelayStartWatchdog?.let { mainHandler.removeCallbacks(it) }
        audioRelayStartWatchdog = null
    }

    private fun startAudioRelayViaCloud(bitrate: Int) {
        // Only one transport may feed the desktop's shared audio capture at a
        // time -- kill any LAN session so two peers never split the PCM stream.
        // Tell the desktop to drop it too: closing the socket alone leaves its LAN
        // peer consuming the shared capture, halving this session's frame rate.
        lanRelayClient?.let {
            it.sendAudioRelayStop()
            it.listener = null
            it.close()
        }
        lanRelayClient = null
        audioRelayViaLan = false
        LogCollector.i(TAG, "Audio relay: starting via cloud (${bitrate}bps)")
        if (!orchestratorClient.sendAudioRelayStart("desktop-listener", bitrate)) {
            // The socket died between the transport check and here. The in-flight guard
            // is already armed, so without this the relay sits dead for the full start
            // window before the watchdog notices. Release it and retry now.
            LogCollector.w(TAG, "Audio relay: cloud start could not be sent, retrying")
            clearAudioRelayStartInFlight()
            scheduleAudioRelayRetry()
        }
    }

    /**
     * True when a callback belongs to a LanRelayClient that is no longer the live one
     * (or the service is gone). Callbacks are posted from the OkHttp reader thread, so
     * by the time they run a newer client may have replaced this one -- acting on them
     * tears down a healthy session or leaves the transport flags describing a client
     * that no longer exists.
     */
    private fun isStaleLanClient(client: LanRelayClient): Boolean {
        if (serviceDestroyed || lanRelayClient !== client) {
            LogCollector.i(TAG, "Audio relay: ignoring callback from superseded LAN client")
            return true
        }
        return false
    }

    private fun startAudioRelayViaLan(endpoint: DesktopRelayLocator.Endpoint, bitrate: Int) {
        // Only one transport may feed the desktop's shared audio capture at a
        // time. A cloud session may exist from an earlier fallback -- stop it
        // server-side, otherwise its desktop peer keeps consuming PCM frames
        // (consent checks are disabled) and the LAN stream loses every other
        // frame, which sounds like constant stutter.
        orchestratorClient.sendAudioRelayStop("desktop-listener")
        // Tear down any prior LAN attempt before starting a new one. Tell the desktop
        // too: closing the socket alone leaves its old LAN peer consuming the shared
        // capture, so this new session would only get half the frames.
        lanRelayClient?.let {
            it.sendAudioRelayStop()
            it.listener = null
            it.close()
        }
        val url = "ws://${endpoint.host}:${endpoint.port}/ws/device"
        LogCollector.i(TAG, "Audio relay: starting via LAN $url (${bitrate}bps)")
        audioRelayViaLan = true
        val client = LanRelayClient(url, AppConfig.getDeviceId(this))
        client.listener = object : LanRelayClient.Listener {
            override fun onConnected() {
                // The only relay callback that was neither hopped to main nor checked
                // for staleness: a client superseded while connecting would still send
                // a start on a socket about to close, briefly giving the desktop a
                // session nothing on this side owns.
                mainHandler.post {
                    if (isStaleLanClient(client)) return@post
                    client.sendAudioRelayStart(bitrate)
                }
            }
            override fun onClosed(reason: String) {
                // Runs on an OkHttp thread. Do the whole check-and-act on the main
                // thread: clearAudioRelayStartInFlight() touches mainHandler callbacks,
                // and reading the guards off-thread races the start path.
                mainHandler.post {
                    // Only act for the client that actually died. By the time this runs a
                    // newer LanRelayClient may have been installed (e.g. a VPN-driven
                    // restart); falling back then would kill a healthy fresh attempt and
                    // clobber its watchdog.
                    if (isStaleLanClient(client)) return@post
                    // If the LAN session dropped before audio was flowing, fall back
                    // to the cloud path so the user still gets audio. Clear the
                    // in-flight guard first so the fallback start is allowed through.
                    if (audioRelayViaLan && audioRelayActive &&
                        AppConfig.getAudioRelayDesired(this@ListenerService)
                    ) {
                        // The socket of a LIVE session dropped. Waiting for the 15s ICE
                        // grace just delays recovery; the session is already gone.
                        LogCollector.w(TAG, "Audio relay: LAN socket closed under a live session ($reason)")
                        audioRelayActive = false
                        currentAudioStreamId = -1
                        webRTCClient?.close()
                        releaseAudioRelayWifiLock()
                        // Close it, do not just drop it: only close() cancels the
                        // dispatcher and shuts its executor down, so a bare null
                        // orphaned the OkHttp threads and socket on every bounce.
                        lanRelayClient?.let { c -> c.listener = null; c.close() }
                        lanRelayClient = null
                        audioRelayViaLan = false
                        scheduleAudioRelayRetry()
                    } else if (audioRelayViaLan && !audioRelayActive &&
                        AppConfig.getAudioRelayDesired(this@ListenerService)
                    ) {
                        LogCollector.w(TAG, "Audio relay: LAN closed ($reason) before active, falling back to cloud")
                        // Deliberately NOT startAudioRelay(): it would re-pick the LAN
                        // endpoint that just failed. But the fallback still needs the
                        // in-flight guard and the start watchdog -- without them a
                        // concurrent retry could launch a SECOND session toward the
                        // desktop (splitting its capture), and an attempt that produced
                        // no offer would never be retried.
                        armAudioRelayStartGuard()
                        // Read the bitrate fresh: the captured one is from when this LAN
                        // attempt started and may predate a settings change.
                        startAudioRelayViaCloud(AppConfig.getAudioBitrate(this@ListenerService))
                    }
                }
            }
            // All of these arrive on the OkHttp reader thread but touch main-thread state
            // (peer construction, mainHandler callbacks, the audio-relay guards), so hop
            // to the main thread first.
            override fun onAudioRelayAck(sampleRate: Int, channels: Int, br: Int, frameSize: Int, frameDurationMs: Int) {
                mainHandler.post {
                    if (isStaleLanClient(client)) return@post
                    this@ListenerService.onAudioRelayAck(sampleRate, channels, br, frameSize, frameDurationMs)
                }
            }
            override fun onAudioRelayError(reason: String) {
                mainHandler.post {
                    // The LAN socket IS the session, so client identity correlates
                    // exactly -- a superseded client's error is already rejected here.
                    // Pass the live epoch so the epoch check does not double-gate it.
                    if (isStaleLanClient(client)) return@post
                    handleAudioRelayError(reason, fromLan = true, epoch = audioRelaySessionEpoch)
                }
            }
            override fun onWebRTCOffer(streamId: Int, sdp: String) {
                mainHandler.post {
                    // A superseded client's offer must not be accepted: it would set
                    // audioRelayViaLan=true with lanRelayClient already null, and the
                    // answer would then be dropped. That state makes every later stop
                    // take the LAN branch, so the live CLOUD session is never released
                    // and the desktop keeps two consumers on its shared capture.
                    if (isStaleLanClient(client)) return@post
                    // LAN-path offer: bind the answer transport to LAN as we accept it.
                    audioRelayViaLan = true
                    handleWebRTCOffer(streamId, sdp)
                }
            }
        }
        lanRelayClient = client
        client.connect()
    }

    /** Single decision point for stopping audio relay (LAN or cloud). */
    /**
     * Retire the current session so anything the desktop already queued for it (an offer
     * in particular) is recognised as stale. Only arming a start bumped this, so a
     * teardown left the epoch intact and a late offer could resurrect a session the user
     * had just stopped -- with no in-flight guard and no watchdog to ever release it.
     */
    private fun retireAudioRelaySession() {
        audioRelaySessionEpoch++
    }

    private fun stopAudioRelay() {
        retireAudioRelaySession()
        // Retire the stream id BEFORE closing: close() drives the peer to CLOSED, which
        // reports a disconnect for that id. With the id still live the stale-stream guard
        // passes and a retry chain is armed right after we cancel it below -- that chain
        // then races the deliberate restart (e.g. the VPN transport switch).
        // Retiring the stream id is what makes a late answer/ICE for this session stale;
        // the answer pin is deliberately left in place so a late answer can still be
        // routed to the transport it belongs to if it does slip through.
        currentAudioStreamId = -1
        webRTCClient?.close()
        audioRelayActive = false
        clearAudioRelayStartInFlight()
        audioRelayRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        audioRelayRetryRunnable = null
        audioRelayRetryCount = 0
        releaseAudioRelayWifiLock()
        if (audioRelayViaLan) {
            lanRelayClient?.sendAudioRelayStop()
            lanRelayClient?.let { it.listener = null; it.close() }
            lanRelayClient = null
            audioRelayViaLan = false
        } else {
            orchestratorClient.sendAudioRelayStop("desktop-listener")
        }
    }

    /**
     * Tears the desktop audio relay down so remote input can take the radio.
     *
     * The priority here is INVERTED relative to the guard in `PhoneBtHost` and
     * `InputRfcommClient.maybeSelfHeal`, which skip RFCOMM paging while a relay
     * streams (each page to absent glasses steals 2.4 GHz airtime and stutters the
     * audio). That guard stays in place for every other feature; for remote input the
     * user asked for the opposite, because a bezel turn that does nothing is worse
     * than audio that stops.
     *
     * `setAudioRelayDesired(false)` is essential, not incidental: `stopAudioRelay()`
     * alone leaves the user's standing intent set, and `scheduleAudioRelayRetry`
     * would bring the relay straight back and re-block input a few seconds later.
     * Clearing the intent is what makes the teardown stick, and it also means the
     * relay does not silently resurrect the next time the transport flaps -- the user
     * turns it back on deliberately, from the UI that turned it on.
     *
     * Idempotent, and safe to call from a 30 Hz burst: it returns immediately once
     * the relay is down.
     *
     * @return true if a relay was actually torn down.
     */
    fun stopAudioRelayForRemoteInput(reason: String): Boolean {
        if (!audioRelayActive && !audioRelayStartInFlight) return false
        LogCollector.i(
            TAG,
            "Audio relay stopped to allow remote input ($reason). " +
                "The relay will not auto-restart; re-enable it from the Desktop tab.",
        )
        // Mark the claim BEFORE tearing down, so paths that defer to the relay stop
        // deferring immediately rather than waiting out the asynchronous teardown --
        // that window is exactly when the user is turning the bezel.
        audioRelayYieldedToInput = true
        // Clear the standing intent FIRST. stopAudioRelay() schedules nothing itself,
        // but the disconnect it drives reaches scheduleAudioRelayRetry, which consults
        // this flag -- clearing it afterwards would lose that race.
        AppConfig.setAudioRelayDesired(this, false)
        stopAudioRelay()
        return true
    }

    /** A transport is available if the cloud is connected OR a LAN PC is discovered. */
    private fun audioRelayTransportAvailable(): Boolean =
        orchestratorConnected || (primaryWifiNetwork() != null && desktopRelayLocator?.endpoint() != null)

    private fun scheduleAudioRelayRetry() {
        if (serviceDestroyed) return
        // Single-flight: cancel any already-pending retry so rapid disconnects
        // (e.g. WebRTCClient closing the old peer when a new offer arrives) can't
        // pile up parallel retry chains that each spawn a new LanRelayClient and
        // tear each other down -- that feedback loop was the reconnect storm.
        audioRelayRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        audioRelayRetryRunnable = null

        val desired = AppConfig.getAudioRelayDesired(this)
        if (!desired) {
            LogCollector.i(TAG, "Audio relay retry skipped: not desired")
            return
        }
        if (audioRelayActive) {
            LogCollector.i(TAG, "Audio relay retry skipped: already active")
            return
        }
        if (!audioRelayTransportAvailable()) {
            // Do not give up: nothing else re-arms us. The orchestrator reconnect only
            // fires if the CLOUD socket bounces, and mDNS rediscovery does not kick a
            // retry -- so a Wi-Fi bounce on a LAN-only phone meant silence until the
            // user toggled the relay by hand. Re-check on the backstop interval.
            LogCollector.i(TAG, "Audio relay: no transport available, re-checking in ${AUDIO_RELAY_BACKSTOP_DELAY_MS}ms")
            val recheck = Runnable {
                audioRelayRetryRunnable = null
                scheduleAudioRelayRetry()
            }
            audioRelayRetryRunnable = recheck
            mainHandler.postDelayed(recheck, AUDIO_RELAY_BACKSTOP_DELAY_MS)
            return
        }
        // Past the backoff ladder, keep retrying on a long interval instead of giving up.
        // Nothing else would resurrect the relay: a disconnect callback needs a live
        // peer, the start watchdog needs an attempt in flight, and the orchestrator
        // reconnect only fires if the cloud WS itself bounces -- which it does not when
        // the DESKTOP is the flaky party, and never on the LAN path. Exhausting the
        // ladder therefore meant silence until the user toggled the relay by hand.
        val delay = if (audioRelayRetryCount >= audioRelayRetryDelays.size) {
            AUDIO_RELAY_BACKSTOP_DELAY_MS
        } else {
            audioRelayRetryDelays[audioRelayRetryCount]
        }
        LogCollector.i(TAG, "Audio relay: scheduling retry #${audioRelayRetryCount + 1} in ${delay}ms")
        val runnable = Runnable {
            audioRelayRetryRunnable = null
            if (AppConfig.getAudioRelayDesired(this) && audioRelayTransportAvailable() && !audioRelayActive) {
                // A start that is coalesced away (one already in flight) must not burn a
                // retry: all three delays could elapse inside a single in-flight window,
                // exhausting the budget and leaving the relay dead for good. Re-arm and
                // wait instead -- real failures come back through onWebRTCDisconnected.
                if (audioRelayStartInFlight) {
                    LogCollector.i(TAG, "Audio relay: retry deferred, start already in flight")
                    scheduleAudioRelayRetry()
                    return@Runnable
                }
                // Clamp: past the ladder the delay is the fixed backstop, so there is no
                // reason to keep incrementing (and overflowing) the counter.
                if (audioRelayRetryCount < audioRelayRetryDelays.size) audioRelayRetryCount++
                val bitrate = AppConfig.getAudioBitrate(this)
                LogCollector.i(TAG, "Audio relay: auto-restarting (attempt $audioRelayRetryCount)")
                startAudioRelay(bitrate)
            }
        }
        audioRelayRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    override fun onAudioRelayAck(sampleRate: Int, channels: Int, bitrate: Int, frameSize: Int, frameDurationMs: Int) {
        LogCollector.i(TAG, "Audio relay ACK: ${sampleRate}Hz ${channels}ch ${bitrate}bps frame=${frameDurationMs}ms")
        // WebRTC will handle audio playback -- the webrtc_offer will arrive shortly after this ACK
        sendBroadcast(Intent(ACTION_AUDIO_RELAY_ACK).apply {
            setPackage(packageName)
            putExtra(EXTRA_AUDIO_SAMPLE_RATE, sampleRate)
            putExtra(EXTRA_AUDIO_CHANNELS, channels)
            putExtra(EXTRA_AUDIO_FRAME_DURATION_MS, frameDurationMs)
        })
    }

    /** Cloud-transport error (OrchestratorClient). */
    override fun currentAudioRelayEpoch(): Int = audioRelaySessionEpoch

    override fun onAudioRelayError(reason: String, epoch: Int) {
        handleAudioRelayError(reason, fromLan = false, epoch = epoch)
    }

    /**
     * Act only on the transport that DELIVERED the error. Branching on the currently
     * live transport instead meant the cloud error provoked by startAudioRelayViaLan's
     * own pre-emptive cloud stop arrived after audioRelayViaLan flipped true, and tore
     * down the LAN session it had just started.
     */
    private fun handleAudioRelayError(reason: String, fromLan: Boolean, epoch: Int) {
        if (serviceDestroyed) return
        // Drop an error belonging to a session we have already replaced. Transport
        // identity alone is not enough: a pre-emptive stop for the PREVIOUS session can
        // be answered with an error that arrives after a new session of the SAME
        // transport started, and tearing that one down would kill a healthy session.
        if (epoch != audioRelaySessionEpoch) {
            LogCollector.i(TAG, "Ignoring audio relay error from a superseded session: $reason")
            return
        }
        // Ignore an error for a transport that is no longer the live one; it refers to
        // a session we already replaced.
        if (fromLan != audioRelayViaLan) {
            LogCollector.i(TAG, "Ignoring audio relay error from ${if (fromLan) "LAN" else "cloud"} (not the live transport): $reason")
            return
        }
        LogCollector.e(TAG, "Audio relay error from desktop: $reason")
        retireAudioRelaySession()
        audioRelayActive = false
        clearAudioRelayStartInFlight()
        // Do NOT reset audioRelayRetryCount here: this path arms a retry at the end, so
        // resetting the budget every time makes the chain unbounded (error -> teardown
        // -> retry -> error ...) with nothing to pace it but the start watchdog.
        audioRelayRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        audioRelayRetryRunnable = null
        // Release the failed session's transport. Leaving the LAN socket open keeps the
        // desktop's peer consuming its shared capture, halving the next session's rate.
        currentAudioStreamId = -1
        webRTCClient?.close()
        if (audioRelayViaLan) {
            lanRelayClient?.let {
                it.sendAudioRelayStop()
                it.listener = null
                it.close()
            }
            lanRelayClient = null
            audioRelayViaLan = false
        } else {
            // Cloud session: tell the desktop to drop its consumer. Tearing down the LAN
            // client here instead would kill a healthy LAN session over a cloud error --
            // the LAN start itself provokes one by pre-emptively stopping the cloud.
            orchestratorClient.sendAudioRelayStop("desktop-listener")
        }
        sendBroadcast(Intent(ACTION_AUDIO_RELAY_ERROR).apply {
            setPackage(packageName)
            putExtra(EXTRA_ERROR_REASON, reason)
        })
        // Nothing else will retry: the peer is closed, so no disconnect callback is
        // coming, and the retry chain was just cleared above.
        scheduleAudioRelayRetry()
    }

    override fun onStreamError(reason: String, streamId: Int) {
        LogCollector.e(TAG, "Stream error from desktop: $reason (streamId=$streamId)")
        sendBroadcast(Intent(ACTION_STREAM_ERROR).apply {
            setPackage(packageName)
            putExtra(EXTRA_ERROR_REASON, reason)
            putExtra(EXTRA_STREAM_ID, streamId)
        })
    }

    private fun getOrCreateWebRTCClient(): WebRTCClient {
        webRTCClient?.let { return it }
        return WebRTCClient(this).also {
            it.listener = webRTCListener
            webRTCClient = it
            LogCollector.i(TAG, "WebRTCClient initialized")
        }
    }

    override fun onWebRTCOffer(streamId: Int, sdp: String, epoch: Int) {
        // An offer for a session we have already replaced must not be adopted: it would
        // close the healthy peer, take over the stale stream id and answer a desktop
        // peer that is gone -- dead audio until the start watchdog fires.
        if (epoch != audioRelaySessionEpoch) {
            LogCollector.i(TAG, "Ignoring cloud offer for superseded session (epoch $epoch)")
            return
        }
        if (!AppConfig.getAudioRelayDesired(this)) {
            LogCollector.i(TAG, "Ignoring cloud offer for stream $streamId: relay not desired")
            return
        }
        // A cloud offer emitted before we switched to LAN can arrive after the LAN
        // session is up. Accepting it would flip the transport flag and swap the peer
        // while leaving lanRelayClient live with its socket open -- every later
        // stopAudioRelay() would then take the cloud branch and never close it, so the
        // desktop keeps two consumers on its shared capture (half frame rate).
        if (audioRelayViaLan && lanRelayClient != null) {
            LogCollector.i(TAG, "Ignoring cloud offer for stream $streamId: LAN session is live")
            return
        }
        // Cloud-path offer. Bind the answer transport to the cloud as we accept
        // the offer so a late LAN offer can't misroute this stream's answer.
        audioRelayViaLan = false
        handleWebRTCOffer(streamId, sdp)
    }

    private fun handleWebRTCOffer(streamId: Int, sdp: String) {
        if (serviceDestroyed) {
            LogCollector.w(TAG, "Ignoring WebRTC offer for stream $streamId: service destroyed")
            return
        }
        LogCollector.i(TAG, "WebRTC offer received for stream $streamId (viaLan=$audioRelayViaLan)")
        // Pin the transport for this stream so its answer cannot be misrouted if the
        // transport flips while ICE gathers. Only the current stream is ever answered,
        // so one pair is enough -- and stream ids restart low on BOTH transports, so a
        // keyed map would let a stale entry from one misroute the other.
        audioRelayAnswerStreamId = streamId
        audioRelayAnswerViaLan = audioRelayViaLan
        // This is now the stream we care about; disconnects for older streams
        // (from the peer swap below) will be ignored.
        currentAudioStreamId = streamId
        getOrCreateWebRTCClient().handleOffer(streamId, sdp)
    }

    override fun onWebRTCIce(streamId: Int, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        if (serviceDestroyed) return
        // Candidates are delivered via the main looper, so one for an older stream can
        // be dequeued after a newer offer swapped the peer. Feeding stale remote
        // candidates to the new peer breaks ICE pairing -> silent no-audio.
        // Ids restart low on BOTH transports, so a stale cloud candidate can carry the
        // same id as the current LAN stream. Only the cloud path ever trickles ICE, so
        // any candidate for a LAN-answered stream is stale by definition.
        if (streamId != currentAudioStreamId || audioRelayAnswerViaLan) {
            LogCollector.i(
                TAG,
                "Ignoring ICE for stream $streamId (current=$currentAudioStreamId viaLan=$audioRelayAnswerViaLan)"
            )
            return
        }
        webRTCClient?.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    override fun onStreamConnect(streamId: Int, streamType: String, endpoint: String, token: String) {
        LogCollector.i(TAG, "Stream connect: $streamType streamId=$streamId")
        when (streamType) {
            "video" -> {
                orchestratorClient.openStreamConnection(streamId, streamType, endpoint, token,
                    onBinaryReceived = { data -> streamFrameListener?.invoke(data) }
                )
            }
            "audio" -> {
                // Audio now uses WebRTC, this case should not be reached
                LogCollector.w(TAG, "Unexpected stream_connect for audio -- should use WebRTC")
            }
            "mouse" -> {
                val conn = orchestratorClient.openStreamConnection(streamId, streamType, endpoint, token)
                mouseEventListener = { bytes -> conn.sendBinary(bytes) }
            }
            "keyboard" -> {
                val conn = orchestratorClient.openStreamConnection(streamId, streamType, endpoint, token)
                keyboardEventListener = { bytes -> conn.sendBinary(bytes) }
            }
        }
    }

    override fun onTodoResult(data: String) {
        LogCollector.i(TAG, "Todo result from orchestrator: ${data.length} chars")
        cachedTodos = data
        sendBroadcast(Intent(ACTION_TODO_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_TODO_DATA, data)
        })
        // Relay only active (non-completed) todos to glasses
        if (phoneBtHost.isConnected) {
            val filtered = try {
                val arr = org.json.JSONArray(data)
                val active = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (!obj.optBoolean("completed", false)) active.put(obj)
                }
                active.toString()
            } catch (e: Exception) {
                data
            }
            phoneBtHost.sendTodoListResponse(filtered)
        }
    }

    override fun onJobResult(data: String) {
        LogCollector.i(TAG, "Job result from orchestrator: ${data.length} chars")

        // Resolve any pending ADB job commands
        val iterator = pendingAdbJobCommands.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value
            pending.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            iterator.remove()
            try {
                AdbResultWriter.writeSuccess(this, pending.commandId, pending.type,
                    JSONObject().apply { put("jobs", org.json.JSONArray(data)) })
            } catch (e: Exception) {
                AdbResultWriter.writeSuccess(this, pending.commandId, pending.type,
                    JSONObject().apply { put("jobs_raw", data) })
            }
        }

        sendBroadcast(Intent(ACTION_JOB_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_JOB_DATA, data)
        })
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendJobListResponse(data)
        }
    }

    override fun onJobNotification(jobId: String, jobName: String, status: String, result: String) {
        LogCollector.i(TAG, "Job notification: jobId=$jobId jobName=$jobName status=$status")

        // Post Android notification
        val notifText = when (status) {
            "needs_input" -> "Job '$jobName' needs your input"
            "failed" -> "Job '$jobName' failed"
            else -> "Job '$jobName' completed"
        }
        val notifIntent = Intent(this, com.repository.listener.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "jobs")
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, jobId.hashCode(), notifIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, "ai_jobs")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("AI Job")
            .setContentText(notifText)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel("ai_jobs", "AI Jobs", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            nm.notify(jobId.hashCode(), notification)
        } else {
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(jobId.hashCode(), notification)
        }

        // Broadcast to refresh UI
        sendBroadcast(Intent(ACTION_JOB_NOTIFICATION).apply {
            setPackage(packageName)
            putExtra("job_id", jobId)
            putExtra("job_status", status)
        })
        if (phoneBtHost.isConnected) {
            serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendJobList() }
        }

        // TTS notification on glasses: title = job name, message = AI result (untruncated)
        if (phoneBtHost.isConnected && result.isNotEmpty()) {
            val ttsText = result.ifEmpty { notifText }
            val notifId = "job_${jobId.takeLast(8)}"
            notificationQueue.enqueue(notifId, jobName, ttsText, "")
            LogCollector.i(TAG, "Job TTS queued: $notifId sender=$jobName text=${ttsText.take(80)}")
        }
    }

    override fun onTelegramSavedResult(data: String) {
        LogCollector.i(TAG, "Telegram saved result from orchestrator: ${data.length} chars")
        // Sanitize: remove null bytes that crash CXR JNI (Modified UTF-8)
        val safeData = data.replace("\u0000", "")
        sendBroadcast(Intent(ACTION_TELEGRAM_SAVED_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_TELEGRAM_DATA, safeData)
        })
        // Relay to glasses if connected
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendTelegramSavedResponse(escapeForJni(safeData))
        }
    }

    override fun onTelegramSavedError(error: String) {
        LogCollector.e(TAG, "Telegram saved error: $error")
        sendBroadcast(Intent(ACTION_TELEGRAM_SAVED_ERROR).apply {
            setPackage(packageName)
            putExtra(EXTRA_ERROR_MESSAGE, error)
        })
        // Relay error to glasses too
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendTelegramSavedResponse("{\"error\":\"$error\"}")
        }
    }

    override fun onTelegramChatListResult(data: String, error: String?) {
        android.util.Log.d("TG_DEBUG", "Phone: onTelegramChatListResult error=$error dataLen=${data.length}")
        if (error != null) {
            LogCollector.e(TAG, "Telegram chat list error: $error")
            if (phoneBtHost.isConnected) {
                phoneBtHost.sendTelegramChatListResponse("{\"error\":\"$error\"}")
            }
            return
        }
        LogCollector.i(TAG, "Telegram chat list result: ${data.length} chars")
        // Send chat list immediately without avatars for fast display
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendTelegramChatListResponse(escapeForJni(data))
        }
    }

    override fun onTelegramMessagesResult(chatId: String, data: String, error: String?) {
        android.util.Log.d("TG_DEBUG", "Phone: onTelegramMessagesResult chatId=$chatId error=$error dataLen=${data.length} btConnected=${phoneBtHost.isConnected}")
        if (error != null) {
            LogCollector.e(TAG, "Telegram messages error: $error")
            if (phoneBtHost.isConnected) {
                phoneBtHost.sendTelegramMessagesResponse(chatId, "{\"error\":\"$error\"}")
            }
            return
        }
        LogCollector.i(TAG, "Telegram messages result: chatId=$chatId ${data.length} chars")
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendTelegramMessagesResponse(chatId, escapeForJni(data))
            android.util.Log.d("TG_DEBUG", "Phone: sent messages response to glasses via BT")
        }
    }

    override fun onTelegramTopicsResult(chatId: String, data: String, error: String?) {
        if (error != null) {
            LogCollector.e(TAG, "Telegram topics error: $error")
            return
        }
        LogCollector.i(TAG, "Telegram topics result: chatId=$chatId ${data.length} chars")
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendTelegramTopicsResponse(chatId, escapeForJni(data))
        }
    }

    override fun onTelegramSendResult(data: String, error: String?) {
        if (error != null) {
            LogCollector.e(TAG, "Telegram send error: $error")
            if (phoneBtHost.isConnected) {
                phoneBtHost.sendTelegramSendResponse("{\"error\":\"$error\"}")
            }
            return
        }
        LogCollector.i(TAG, "Telegram send result received")
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendTelegramSendResponse(data)
        }
    }

    override fun onTelegramNewMessage(messageJson: String) {
        LogCollector.i(TAG, "Telegram new message from orchestrator")
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendTelegramNewMessage(escapeForJni(messageJson))
        }
    }

    override fun onTelegramUserStatus(userId: String, isOnline: Boolean, lastSeen: String?) {
        LogCollector.d(TAG, "Telegram user status: $userId online=$isOnline")
        if (phoneBtHost.isConnected) {
            // Send as a new message with type=status so glasses can update the chat list
            val json = org.json.JSONObject().apply {
                put("type", "user_status")
                put("userId", userId)
                put("isOnline", isOnline)
                if (lastSeen != null) put("lastSeen", lastSeen)
            }.toString()
            phoneBtHost.sendTelegramNewMessage(escapeForJni(json))
        }
    }

    override fun onReidMerge(sourcePersonId: String, targetPersonId: String, targetDisplayName: String) {
        LogCollector.i(TAG, "Reid merge: $sourcePersonId -> $targetPersonId ($targetDisplayName)")
        if (phoneBtHost.isConnected) {
            phoneBtHost.sendReidMerge(sourcePersonId, targetPersonId, targetDisplayName)
        }
    }

    // --- Remote Control callbacks ---

    override fun onRcSessionStart(sessionId: String, workDir: String) {
        LogCollector.i(TAG, "RC session started: $sessionId workDir=$workDir")
        // rcDumpState must stay in lockstep with the ACTION_RC_SESSION_START broadcast below.
        rcDumpState[sessionId] = RcDumpEntry(workDir, "active", false)
        touchRcSession(sessionId)
        pushRcState(force = true)
        if (!rcSessionTurning.containsKey(sessionId)) {
            rcSessionTurning[sessionId] = false
            refreshRcNotification()
        }
        sendBroadcast(Intent(ACTION_RC_SESSION_START).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, JSONObject().put("workDir", workDir).toString())
        })
    }

    override fun onRcSessionEnd(sessionId: String) {
        thinkingRcStartTimes.remove(sessionId)
        // rcDumpState must stay in lockstep with the ACTION_RC_SESSION_END broadcast below.
        // Retain the entry as status="ended" so it lingers in the All view;
        // "Only open" filter excludes ended sessions automatically.
        // discovered=false is what makes it linger: an entry the REST list still owns would be
        // deleted by the very next reconcile, since an ended session stops being listed.
        rcDumpState[sessionId]?.let { existing ->
            rcDumpState[sessionId] = existing.copy(status = "ended", turning = false, discovered = false)
        }
        cancelRcDoneClear(sessionId)
        rcTurnFinishRunnables.remove(sessionId)?.let { mainHandler.removeCallbacks(it) }
        rcSessionTurning.remove(sessionId)
        rcTranscriptCache.remove(sessionId)
        // Promptness only, NOT the memory bound: this hook never fires on a dropped WS, a PC-side
        // CLI kill or a restart. The store's LRU is what actually bounds it.
        rcMirror.clear(sessionId)
        // An ended session's prompts can never be resolved. Keeping them pending would leave a
        // stale hit for a request id a later session may reuse; the registry's own 16-entry bound
        // remains the actual limit, exactly as the store's LRU is for rows.
        rcPrompts.clearSession(sessionId)
        pushRcState(force = true)
        // One session ending often means others changed too (a CLI restart ends and restarts
        // several). Re-read the authoritative list rather than waiting up to a poll interval.
        reconcileLiveRcSessions("rc_session_end")
        refreshRcNotification()
        LogCollector.i(TAG, "RC session ended: $sessionId")
        sendBroadcast(Intent(ACTION_RC_SESSION_END).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
        })
    }

    override fun onRcMessage(sessionId: String, text: String, isFinal: Boolean, requestId: String?, contextPct: Int, costUsd: Double) {
        thinkingRcStartTimes.remove(sessionId)
        touchRcSession(sessionId)
        // rcDumpState must stay in lockstep with the ACTION_RC_MESSAGE broadcast below (turning = !isFinal).
        // Detect a real true->false transition so we set unread + fire the glasses
        // notification exactly once per turn-finish (not on every isFinal chunk).
        val turnFinished = resolveRcTurnTransition(sessionId, isFinal)
        rcDumpState[sessionId]?.let { entry ->
            if (entry.sessionName == null && text.isNotBlank()) {
                rcDumpState[sessionId] = entry.copy(sessionName = text.take(50))
            }
        }
        val unread = rcDumpState[sessionId]?.unread ?: false
        // The text is cumulative, so this STORES the latest snapshot rather than appending, and
        // nothing but the tiny state frame goes on the wire until the turn is really finished.
        // isFinal carries a full snapshot too and fires between tool calls, so it is stored on the
        // same footing -- only the debounce runnable decides a turn has ended.
        if (text.isNotBlank()) rcMirror.noteAssistantText(sessionId, text)
        if (turnFinished) {
            // Debounce the turn-finish commit: agentic Claude Code emits isFinal=true
            // between tool calls, so firing immediately would spam "Done" during
            // tool chains. Wait RC_DONE_DEBOUNCE_MS; onRcToolStatus cancels if
            // a tool event arrives, proving the turn isn't really finished.
            rcTurnFinishRunnables.remove(sessionId)?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                rcTurnFinishRunnables.remove(sessionId)
                // Only here is a turn really over: raw isFinal fires between tool calls. Commit the
                // rows, and if the glasses are sitting in this thread, delivering them IS the read
                // -- without that the bar stays lit behind a thread the user is actively reading,
                // because the glasses never send another messages request.
                val rows = rcMirror.commitTurn(sessionId)
                rcSeenToolCallIds.clear()
                // Clearing unread is conditional on the rows actually reaching the glasses: a
                // dropped frame that still cleared the bar would lose the turn silently.
                if (rcBridge.openSessionId == sessionId && rcBridge.pushRows(sessionId, rows)) {
                    markRcRead(sessionId)
                }
                // No "Done" notification is pushed to the glasses: the forced state snapshot above
                // already lights that session's unread bar in the chat list, and a notification on
                // top of it was a duplicate alert plus a bar that outlived it.
                pushRcState(force = true)
                val entry = rcDumpState[sessionId]
                val folder = folderNameFromWorkDir(entry?.workDir)
                val title = entry?.sessionName
                val notifTitle = listOfNotNull(folder, title).joinToString(": ").ifEmpty { "Claude Code" }
                // Heads-up notification on the phone itself.
                try {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val tapIntent = android.content.Intent(this, com.repository.listener.ui.rc.RemoteControlActivity::class.java).apply {
                        putExtra(com.repository.listener.ui.rc.RemoteControlActivity.EXTRA_SESSION_ID, sessionId)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this, sessionId.hashCode(), tapIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val headsUp = androidx.core.app.NotificationCompat.Builder(this@ListenerService, com.repository.listener.ListenerApp.RC_DONE_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle(notifTitle)
                        .setContentText("Done")
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setTimeoutAfter(10_000)
                        .build()
                    nm.notify("rc_done_$sessionId".hashCode(), headsUp)
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to show RC done heads-up: ${e.message}")
                }
            }
            rcTurnFinishRunnables[sessionId] = r
            mainHandler.postDelayed(r, RC_DONE_DEBOUNCE_MS)
            // Tell the UI the unread bit flipped to true so it can repaint dot + badge.
            sendBroadcast(Intent(ACTION_RC_UNREAD_CHANGED).apply {
                setPackage(packageName)
                putExtra(EXTRA_RC_SESSION_ID, sessionId)
                putExtra(EXTRA_RC_UNREAD, true)
            })
        }
        val data = JSONObject().apply {
            put("text", text)
            put("isFinal", isFinal)
            put("unread", unread)
            if (requestId != null) put("requestId", requestId)
            if (contextPct >= 0) put("contextPct", contextPct)
            if (costUsd >= 0) put("costUsd", costUsd)
        }.toString()
        if (rcSessionTurning.containsKey(sessionId)) {
            markRcTurning(sessionId, !isFinal)
        }
        sendBroadcast(Intent(ACTION_RC_MESSAGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, data)
        })
        pushRcState()
    }

    /**
     * Atomically applies a turn-transition update to rcDumpState[sessionId]:
     *  - reads previous turning, computes newTurning = !isFinal
     *  - if entry exists, writes back with updated turning, and sets unread=true on
     *    a real true->false transition (otherwise preserves prior unread)
     *  - if entry doesn't exist, leaves the map untouched (caller is responsible
     *    for creating it -- workDir/status come from caller context)
     * Returns true iff this was a real true->false transition AND the entry existed,
     * so callers know whether to commit the turn + broadcast ACTION_RC_UNREAD_CHANGED.
     */
    private fun resolveRcTurnTransition(sessionId: String, isFinal: Boolean): Boolean {
        val newTurning = !isFinal
        var fired = false
        rcDumpState.compute(sessionId) { _, previous ->
            if (previous == null) {
                fired = false
                null
            } else {
                val prevTurning = previous.turning
                val turnFinished = prevTurning && !newTurning
                fired = turnFinished
                val newUnread = if (turnFinished) true else previous.unread
                previous.copy(turning = newTurning, unread = newUnread)
            }
        }
        return fired
    }

    /** The last snapshot actually handed to the socket, or null when the glasses link is down. */
    @Volatile private var lastPushedStateJson: String? = null

    /**
     * Builds the full RC session snapshot and pushes it to the glasses when it differs from the one
     * last sent. There is deliberately no coalescing timer: the frame carries only ws and the
     * per-session tuple, none of which change per streaming rc_message, so byte-equality already
     * collapses a 10-20 Hz storm to roughly one frame per turn.
     *
     * [force] skips the comparison, and is used from the WS lifecycle and from glasses link-up
     * where the push must be authoritative rather than deduped away.
     */
    private fun pushRcState(force: Boolean = false) = synchronized(rcStatePushLock) {
        // Build INSIDE the lock: two threads building outside it can reorder such that an older
        // snapshot is written last and then cached, pinning the glasses on stale state forever.
        val sessions = rcDumpState.entries.map { (id, e) ->
            com.repository.listener.rc.RcSessionState(
                id = id,
                name = e.sessionName ?: e.workDir.substringAfterLast('/'),
                folder = e.workDir.substringAfterLast('/'),
                ended = e.status == "ended",
                turning = e.turning,
                debouncePending = rcTurnFinishRunnables.containsKey(id),
                unread = e.unread,
                lastSeq = rcMirror.lastSeq(id),
                lastActivityMs = rcLastActivityMs[id] ?: 0L
            )
        }
        val json = com.repository.listener.rc.RcStateSnapshot.build(orchestratorConnected, sessions)
        if (!force && json == lastPushedStateJson) return@synchronized
        // Written synchronously, one frame, exactly as every other single-frame send in PhoneBtHost
        // already is from these same threads. The multi-second hazard was the chunked sleep loop,
        // and that now runs on its own sender thread. Keeping this synchronous also keeps state
        // frames ordered against the row frames RcBridge writes.
        // Cached only on a confirmed write: a dropped frame must not be deduped away next time.
        val sent = phoneBtHost.sendRcStateIfConnected(json)
        lastPushedStateJson = if (sent) json else null
        // The snapshot body itself, so a glasses list that renders wrong can be diagnosed from
        // logcat alone without guessing what the phone believed. Bounded by MAX_FRAME_CHARS.
        LogCollector.i(TAG, "[RCPUSH] sent=$sent force=$force n=${sessions.size} json=$json")
    }

    private val rcStatePushLock = Any()

    // --- Live session reconciliation (orchestrator REST list) ---

    /**
     * How often the live session list is polled while the glasses are connected.
     *
     * This is a battery-constrained phone, so the poll is deliberately slow AND conditional: it
     * only runs while the glasses link is up (nothing renders the snapshot otherwise), and it is
     * armed/disarmed by the BT lifecycle rather than running for the life of the service. Every
     * moment that actually matters -- glasses link-up, and each RC WebSocket event -- reconciles
     * immediately, so the poll exists purely to catch a session started in the phone's own RC UI
     * while the glasses were already connected. Two minutes is the latency ceiling for that one
     * case; one HTTPS GET per two minutes is negligible next to the RFCOMM link it accompanies.
     */
    private val rcLiveListPollMs = 120_000L

    /** Serializes reconcile writes against each other; WS writers race them via CAS instead. */
    private val rcLiveMergeLock = Any()

    private val rcLiveListInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * One client for every reconcile. A per-call instance would build a fresh OkHttp Dispatcher
     * executor and connection pool on each poll and each session end, and drop the keep-alive.
     */
    private val rcLiveListClient by lazy {
        com.repository.listener.network.RemoteSessionClient(
            AppConfig.getOrchestratorUrl(this),
            AppConfig.getApiKey(this),
            AppConfig.getDeviceId(this)
        )
    }

    private val rcLiveListPoll = object : Runnable {
        override fun run() {
            if (serviceDestroyed) return
            reconcileLiveRcSessions("poll")
            mainHandler.postDelayed(this, rcLiveListPollMs)
        }
    }

    /** Arms the poll. Idempotent: a second call does not stack a second chain of runnables. */
    private fun startRcLiveListPolling() {
        mainHandler.removeCallbacks(rcLiveListPoll)
        mainHandler.postDelayed(rcLiveListPoll, rcLiveListPollMs)
        LogCollector.i(TAG, "[RCLIST] poll armed every ${rcLiveListPollMs}ms")
    }

    private fun stopRcLiveListPolling() {
        mainHandler.removeCallbacks(rcLiveListPoll)
        LogCollector.i(TAG, "[RCLIST] poll disarmed")
    }

    /**
     * Pulls the authoritative live session list and merges it into [rcDumpState].
     *
     * This is what makes a session opened in the phone's own RC UI reach the glasses at all: that
     * UI talks to the orchestrator REST API directly and never touches this service, so nothing
     * else would ever put the session in the map. The list is also independent of this service's
     * orchestrator WebSocket, so it works while that socket is down -- which is the common case.
     *
     * Only ever pushes when the merge actually changed something; an unchanged list is silent.
     */
    private fun reconcileLiveRcSessions(reason: String) {
        if (serviceDestroyed) return
        // One request at a time. A slow list plus a burst of RC events would otherwise stack
        // requests whose replies land out of order, and the older reply would win the merge.
        // CAS, not check-then-set: this is called from the main looper AND from the OkHttp
        // WebSocket reader thread (onRcSessionEnd), which are genuinely concurrent.
        if (!rcLiveListInFlight.compareAndSet(false, true)) {
            LogCollector.i(TAG, "[RCLIST] skip ($reason): request already in flight")
            return
        }
        // Cleared on EVERY exit path. A synchronous throw out of enqueue() would otherwise latch
        // the flag true and kill reconciliation for the life of the process.
        try {
            rcLiveListClient.listSessions { result ->
                rcLiveListInFlight.set(false)
                if (serviceDestroyed) return@listSessions
                result.onFailure { err ->
                    // A failed list must change nothing: treating "cannot reach the orchestrator"
                    // as "no sessions exist" would wipe every discovered row off the glasses.
                    LogCollector.w(TAG, "[RCLIST] list failed ($reason): ${err.message}")
                }
                result.onSuccess { listed ->
                    applyLiveRcSessions(reason, listed.map {
                        RcLiveSession(it.sessionId, it.workDir, it.alive, it.title)
                    })
                }
            }
        } catch (e: Exception) {
            rcLiveListInFlight.set(false)
            LogCollector.w(TAG, "[RCLIST] list dispatch failed ($reason): ${e.message}")
        }
    }

    /**
     * Applies a merge result to [rcDumpState].
     *
     * Written back entry by entry with compare-and-set, NOT wholesale. The RC WebSocket callbacks
     * run on the OkHttp reader thread while this runs on the main looper, so between the merge
     * reading the map and this writing it back, a WS event can have set turning or unread. A bulk
     * `putAll` of the merge's view would revert it -- silently losing a spinner or an unread dot
     * that nothing would ever set again. Losing the poll's much poorer view instead is free: the
     * next reconcile recomputes it.
     */
    private fun applyLiveRcSessions(reason: String, listed: List<RcLiveSession>) {
        val changed = synchronized(rcLiveMergeLock) {
            val before = HashMap(rcDumpState)
            val result = RcLiveSessionMerge.merge(before, listed)
            if (!result.changed) return@synchronized false
            var applied = 0
            for ((id, next) in result.entries) {
                val prior = before[id]
                if (prior == next) continue
                val ok = if (prior == null) {
                    // absent -> present. Loses to a WS onRcSessionStart that got there first,
                    // which is the correct winner: its entry is richer and WS-owned.
                    rcDumpState.putIfAbsent(id, next) == null
                } else {
                    rcDumpState.replace(id, prior, next)
                }
                if (ok) applied++
            }
            // Newly discovered sessions have no activity timestamp, and 0 sorts them last -- so a
            // session the user just opened in the UI would be the first one dropped by the
            // 8-session cap. Stamp them now, WITHOUT promoting: the list still owns them.
            result.added.forEach { stampDiscoveredRcSession(it) }
            for (id in result.removed) {
                // Conditional: a WS event may have promoted this entry out of REST ownership
                // after the merge decided to delete it, and a promoted entry is not ours to drop.
                val prior = before[id] ?: continue
                if (rcDumpState.remove(id, prior)) rcLastActivityMs.remove(id)
            }
            LogCollector.i(
                TAG,
                "[RCLIST] merged ($reason): listed=${listed.size} added=${result.added} " +
                    "removed=${result.removed} revived=${result.revived} applied=$applied " +
                    "total=${rcDumpState.size}"
            )
            true
        }
        if (changed) pushRcState(force = true)
    }

    /** sessionId -> last time anything happened on it, for the 8-most-recent truncation. */
    private val rcLastActivityMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Tool call ids already counted, so the several status events of one call are not counted
     * several times. Bounded because one tool chain is finite and the set is cleared per turn.
     */
    private val rcSeenToolCallIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * Marks a WebSocket event on a session: stamps its activity time AND takes ownership of the
     * entry away from the REST list.
     *
     * The promotion is the point. Once WS is speaking for a session the entry holds state the
     * list cannot see (turning, unread, a real assistant-text name), so the list must stop being
     * allowed to rewrite it or to delete it when it stops reporting it. Called from every RC WS
     * callback; the REST reconcile deliberately uses [stampDiscoveredRcSession] instead.
     */
    private fun touchRcSession(sessionId: String) {
        rcLastActivityMs[sessionId] = System.currentTimeMillis()
        rcDumpState.computeIfPresent(sessionId) { _, e ->
            if (e.discovered) e.copy(discovered = false) else e
        }
    }

    /** Activity stamp only, for a session the REST list just discovered and still owns. */
    private fun stampDiscoveredRcSession(sessionId: String) {
        rcLastActivityMs[sessionId] = System.currentTimeMillis()
    }

    /**
     * Records an outgoing user message as a row and pushes it straight to an open glasses thread.
     * The glasses render NO optimistic local row -- seq is minted phone-side and the row schema
     * carries no correlation id, so a local row could never be matched away and would render twice.
     */
    private fun noteRcUserMessage(sessionId: String, text: String) {
        touchRcSession(sessionId)
        val row = rcMirror.appendUser(sessionId, text)
        rcBridge.pushRows(sessionId, listOf(row))
        pushRcState(force = true)
    }

    /**
     * Clears the unread flag for the given RC session. Called when the user opens
     * the RC chat row in the chats list. Idempotent: re-broadcast only when the
     * flag actually changed. In-memory only -- matches the lifetime of rcDumpState.
     *
     * [seenSeq] is the highest row seq the caller has actually rendered. The glasses pass a real
     * value so a turn committing between their render and this call keeps its unread bar; the phone
     * UI renders everything it holds and passes the default.
     */
    fun markRcRead(sessionId: String, seenSeq: Long = Long.MAX_VALUE) {
        var changed = false
        rcDumpState.compute(sessionId) { _, previous ->
            when {
                previous == null -> null
                !previous.unread -> previous
                // Read the high-water mark INSIDE the compute: a turn committing between a
                // separate read and this write would otherwise have its unread bar cleared,
                // which is the exact hazard the seenSeq guard exists to close.
                !com.repository.listener.rc.RcReadPolicy.shouldClearUnread(
                    rcMirror.lastSeq(sessionId), seenSeq) -> previous
                else -> { changed = true; previous.copy(unread = false) }
            }
        }
        if (!changed) return
        sendBroadcast(Intent(ACTION_RC_UNREAD_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_UNREAD, false)
        })
    }

    override fun onRcPermissionRequest(sessionId: String, toolName: String, toolArgs: String, requestId: String, description: String?) {
        thinkingRcStartTimes.remove(sessionId)
        touchRcSession(sessionId)
        // The options are derived phone-side, not carried by the orchestrator: an AskUserQuestion
        // enumerates them in toolArgs (the phone RC UI parses the same field for its buttons), and
        // every other tool's choice is the approve/reject verdict. Registering BEFORE projecting
        // means the row can never reach the glasses describing a choice the answer path would
        // refuse.
        val options = com.repository.listener.rc.RcPrompts.optionsFor(toolName, toolArgs)
        rcPrompts.register(sessionId, requestId, toolName, options)
        val promptRow = rcMirror.appendPrompt(
            sessionId, description ?: toolName, options,
            requestId = if (options.isEmpty()) "" else requestId
        )
        rcBridge.pushRows(sessionId, listOf(promptRow))
        pushRcState(force = true)
        val data = JSONObject().apply {
            put("toolName", toolName)
            put("toolArgs", toolArgs)
            put("requestId", requestId)
            if (description != null) put("description", description)
        }.toString()
        sendBroadcast(Intent(ACTION_RC_PERMISSION_REQUEST).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, data)
        })
    }

    override fun onRcToolStatus(sessionId: String, toolName: String, status: String, result: String?, toolArgs: String?, toolCallId: String?, contextPct: Int, isAgent: Boolean, agentName: String?, agentTask: String?, agentToolCount: Int?, agentTokens: Long?, agentElapsedMs: Long?) {
        // A tool event means the AI is still working: cancel any pending
        // "done" clear that an earlier isFinal MESSAGE may have scheduled.
        if (rcSessionTurning.containsKey(sessionId)) {
            markRcTurning(sessionId, true)
        }
        // Cancel the pending turn-finish commit -- the turn is not over yet.
        rcTurnFinishRunnables.remove(sessionId)?.let { mainHandler.removeCallbacks(it) }
        // Re-mark turning in rcDumpState so the next isFinal can fire a real transition.
        rcDumpState.compute(sessionId) { _, prev ->
            prev?.copy(turning = true)
        }
        touchRcSession(sessionId)
        // Count each invocation once. "calling" and "running" are both in-flight states for one
        // call and a tool may arrive first as either, so key on the toolCallId when there is one
        // and fall back to the in-flight statuses otherwise.
        val firstEventForCall = if (toolCallId != null) rcSeenToolCallIds.add(toolCallId)
        else status == "calling" || status == "running"
        if (firstEventForCall) rcMirror.noteTool(sessionId, toolName)
        pushRcState()
        val data = JSONObject().apply {
            put("toolName", toolName)
            put("status", status)
            if (result != null) put("result", result)
            if (toolArgs != null) put("toolArgs", toolArgs)
            if (toolCallId != null) put("toolCallId", toolCallId)
            if (contextPct >= 0) put("contextPct", contextPct)
            if (isAgent) put("isAgent", true)
            if (agentName != null) put("agentName", agentName)
            if (agentTask != null) put("agentTask", agentTask)
            if (agentToolCount != null) put("agentToolCount", agentToolCount)
            if (agentTokens != null) put("agentTokens", agentTokens)
            if (agentElapsedMs != null) put("agentElapsedMs", agentElapsedMs)
        }.toString()
        sendBroadcast(Intent(ACTION_RC_TOOL_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, data)
        })
    }

    override fun onRcPlanUpdate(sessionId: String, entering: Boolean, planContent: String?) {
        val data = JSONObject().apply {
            put("entering", entering)
            if (planContent != null) put("planContent", planContent)
        }.toString()
        sendBroadcast(Intent(ACTION_RC_PLAN_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, data)
        })
    }

    override fun onRcAgentStatus(sessionId: String, agentId: String, name: String, status: String, depth: Int) {
        val data = JSONObject().apply {
            put("agentId", agentId)
            put("name", name)
            put("status", status)
            put("depth", depth)
        }.toString()
        sendBroadcast(Intent(ACTION_RC_AGENT_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, data)
        })
    }

    override fun onRcThinkingEnd(sessionId: String, elapsedMs: Long) {
        thinkingRcStartTimes.remove(sessionId)
        // Do NOT clear rcSessionTurning here: THINKING_END means the spinner stops
        // because the AI is now streaming output, but the turn is still in progress.
        // Only MESSAGE with isFinal=true marks the turn done.
        sendBroadcast(Intent(ACTION_RC_THINKING_END).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, elapsedMs.toString())
        })
    }

    override fun onRcThinking(sessionId: String, text: String, startedAt: Long) {
        val effectiveStart = if (startedAt > 0L) startedAt else System.currentTimeMillis()
        thinkingRcStartTimes[sessionId] = effectiveStart
        // Cancel the pending turn-finish commit -- still working.
        rcTurnFinishRunnables.remove(sessionId)?.let { mainHandler.removeCallbacks(it) }
        // rcDumpState must stay in lockstep with the ACTION_RC_THINKING broadcast below.
        rcDumpState[sessionId]?.let { existing ->
            rcDumpState[sessionId] = existing.copy(turning = true)
        }
        markRcTurning(sessionId, true)
        sendBroadcast(Intent(ACTION_RC_THINKING).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, text)
            putExtra("startedAt", effectiveStart)
        })
    }

    override fun onRcModeChange(sessionId: String, newMode: String) {
        sendBroadcast(Intent(ACTION_RC_MODE_CHANGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, newMode)
        })
    }

    override fun onRcUserInput(sessionId: String, prompt: String, requestId: String) {
        thinkingRcStartTimes.remove(sessionId)
        touchRcSession(sessionId)
        // No options: rc_user_input's answer is free text the orchestrator does not enumerate, so
        // there is nothing to offer as a selectable list. The prompt text mirrors to the glasses;
        // the answer is typed on the phone.
        rcMirror.appendPrompt(sessionId, prompt, emptyList())
        pushRcState(force = true)
        val data = JSONObject().apply {
            put("prompt", prompt)
            put("requestId", requestId)
        }.toString()
        sendBroadcast(Intent(ACTION_RC_USER_INPUT).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, data)
        })
    }

    override fun onRcTranscript(sessionId: String, transcript: String) {
        // RC transcripts can grow well past the Binder transaction limit
        // (~1MB hard cap, with stricter OEM ceilings). Putting the JSON
        // directly in Intent extras triggers TransactionTooLargeException
        // and the OS kills the receiver process with "Can't deliver
        // broadcast". Stash the payload in an in-process cache and pass
        // only the sessionId via the broadcast.
        rcTranscriptCache[sessionId] = transcript
        // Seeds and replies only when this session is still the one the glasses have open, so rows
        // belonging to one thread can never render tagged as another after a navigation.
        rcBridge.onTranscript(sessionId, transcript)
        sendBroadcast(Intent(ACTION_RC_TRANSCRIPT).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
        })
    }

    override fun onRcError(sessionId: String, errorText: String, source: String) {
        LogCollector.w(TAG, "RC error: [$source] $errorText")
        // Clear thinking state so the UI doesn't get stuck in "Thinking..."
        // when the error is the only cleanup event (e.g. turn timeout).
        thinkingRcStartTimes.remove(sessionId)
        val data = JSONObject().apply {
            put("error", errorText)
            put("source", source)
        }.toString()
        sendBroadcast(Intent(ACTION_RC_ERROR).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_DATA, data)
        })
    }

    override fun onRcUserMessageStatus(
        sessionId: String,
        requestId: String,
        status: String,
        attempt: Int,
        nextRetryAtMs: Long,
    ) {
        // Forward to RemoteControlActivity so the chat UI can render
        // "Sending - retry N in Ks" countdowns. Also clears the local turning
        // flag if the message permanently failed; otherwise leave the
        // existing turn state alone (the AI may still be processing a prior
        // message even when this one is queued).
        if (status == "failed") {
            markRcTurning(sessionId, false)
        }
        // Cache the latest non-terminal status so a paused/killed activity
        // can re-sync on resume. Terminal states (delivered/failed) drop the
        // entry -- there's nothing left to render.
        if (status == "delivered" || status == "failed") {
            rcUserMsgStatusBySession.remove(sessionId)
        } else {
            rcUserMsgStatusBySession[sessionId] = RcUserMsgStatusSnapshot(
                requestId = requestId, status = status, attempt = attempt, nextRetryAtMs = nextRetryAtMs,
            )
        }
        sendBroadcast(Intent(ACTION_RC_USER_MSG_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_REQUEST_ID, requestId)
            putExtra(EXTRA_RC_USER_MSG_STATUS, status)
            putExtra(EXTRA_RC_USER_MSG_ATTEMPT, attempt)
            putExtra(EXTRA_RC_USER_MSG_NEXT_RETRY_AT, nextRetryAtMs)
        })
    }

    /**
     * Re-broadcasts the cached non-terminal rc_user_message status for the
     * given session. Triggered by RemoteControlActivity.onResume so the UI
     * can reconcile after missing live status broadcasts while paused. If
     * no pending status exists, sends a synthetic "delivered" so the UI
     * exits its Sending indicator (the message has either been ack'd or
     * was never sent in the first place).
     */
    private fun handleRcUserMsgStatusQuery(sessionId: String) {
        val snap = rcUserMsgStatusBySession[sessionId]
        val intent = Intent(ACTION_RC_USER_MSG_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            if (snap != null) {
                putExtra(EXTRA_RC_REQUEST_ID, snap.requestId)
                putExtra(EXTRA_RC_USER_MSG_STATUS, snap.status)
                putExtra(EXTRA_RC_USER_MSG_ATTEMPT, snap.attempt)
                putExtra(EXTRA_RC_USER_MSG_NEXT_RETRY_AT, snap.nextRetryAtMs)
            } else {
                putExtra(EXTRA_RC_REQUEST_ID, "")
                putExtra(EXTRA_RC_USER_MSG_STATUS, "delivered")
                putExtra(EXTRA_RC_USER_MSG_ATTEMPT, 0)
                putExtra(EXTRA_RC_USER_MSG_NEXT_RETRY_AT, 0L)
            }
        }
        sendBroadcast(intent)
    }

    // --- Telegram voice helpers ---

    /**
     * Speaker verification for Telegram auth gate.
     * Collect-then-verify: starts buffering glasses audio for 3s, then runs
     * ECAPA-TDNN verification against the stored voice profile.
     * Audio is collected in the IDLE audio handler when speakerVerifyBuffering=true.
     */
    private fun handleSpeakerVerifyForTelegram() {
        if (!::speakerVerifier.isInitialized) {
            LogCollector.w(TAG, "Speaker verifier not initialized, rejecting")
            phoneBtHost.sendSpeakerVerifyResult(false, 0f)
            return
        }

        // Pre-flight: check if glasses mic stream is healthy before collecting audio
        val ts = lastGlassesAudioTimestamp
        val stalledMs = if (ts > 0) android.os.SystemClock.elapsedRealtime() - ts else Long.MAX_VALUE
        if (stalledMs > MIC_STALL_THRESHOLD_MS) {
            LogCollector.w(TAG, "Speaker verify: mic stream stalled (${stalledMs}ms), requesting restart and retrying in 3s")
            phoneBtHost.sendDeviceCommand("restart_audio", "{}")
            lastGlassesAudioTimestamp = android.os.SystemClock.elapsedRealtime()
            mainHandler.postDelayed({
                val newTs = lastGlassesAudioTimestamp
                val newStalled = if (newTs > 0) android.os.SystemClock.elapsedRealtime() - newTs else Long.MAX_VALUE
                if (newStalled > MIC_STALL_THRESHOLD_MS) {
                    LogCollector.w(TAG, "Speaker verify: mic still stalled after restart, rejecting")
                    phoneBtHost.sendSpeakerVerifyResult(false, 0f)
                } else {
                    LogCollector.i(TAG, "Speaker verify: mic recovered, proceeding with verification")
                    doSpeakerVerifyForTelegram()
                }
            }, 3000)
            return
        }

        doSpeakerVerifyForTelegram()
    }

    private fun doSpeakerVerifyForTelegram() {
        // Cancel any pending verify
        speakerVerifyRunnable?.let { mainHandler.removeCallbacks(it) }

        // Start collecting audio from glasses mic
        speakerVerifyBuffering = true
        synchronized(glassesAudioBuffer) { glassesAudioBuffer.clear() }
        LogCollector.i(TAG, "Speaker verify: collecting audio for 3s...")

        // After 3s of collection, run verification
        speakerVerifyRunnable = Runnable {
            speakerVerifyBuffering = false

            val audio = copyBufferToFloat(true)
            synchronized(glassesAudioBuffer) { glassesAudioBuffer.clear() }

            if (audio.size < 8000) { // < 0.5s usable audio
                LogCollector.w(TAG, "Speaker verify: insufficient audio (${audio.size} samples, ${audio.size / 16000f}s)")
                phoneBtHost.sendSpeakerVerifyResult(false, 0f)
                return@Runnable
            }

            LogCollector.i(TAG, "Speaker verify: collected ${audio.size} samples (${audio.size / 16000f}s)")

            serviceScope.launch(Dispatchers.Default) {
                try {
                    val (_, similarity) = speakerVerifier.verify(audio)
                    // Telegram verify uses BT-relayed audio which has lower quality than direct mic.
                    // Use a relaxed threshold (70% of normal) to compensate.
                    val btThreshold = AppConfig.SPEAKER_SIMILARITY_THRESHOLD * 0.7f
                    val verified = similarity >= btThreshold
                    LogCollector.i(TAG, "Speaker verify (BT): similarity=${"%.4f".format(similarity)} threshold=${"%.4f".format(btThreshold)} verified=$verified")
                    phoneBtHost.sendSpeakerVerifyResult(verified, similarity)
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Speaker verify error: ${e.message}")
                    phoneBtHost.sendSpeakerVerifyResult(false, 0f)
                }
            }
        }
        mainHandler.postDelayed(speakerVerifyRunnable!!, 3000)
    }

    /** Verify speaker from raw PCM audio captured on glasses mic 2 (base64 encoded) */
    private fun handleSpeakerVerifyWithAudio(base64Audio: String) {
        if (!::speakerVerifier.isInitialized) {
            LogCollector.w(TAG, "Speaker verifier not initialized, rejecting")
            phoneBtHost.sendSpeakerVerifyResult(false, 0f)
            return
        }

        serviceScope.launch(Dispatchers.Default) {
            try {
                val bytes = android.util.Base64.decode(base64Audio, android.util.Base64.NO_WRAP)
                val shortBuf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = ShortArray(shortBuf.remaining())
                shortBuf.get(samples)

                val audio = FloatArray(samples.size) { samples[it] / 32768f }
                LogCollector.i(TAG, "Speaker verify (mic 2): ${audio.size} samples (${audio.size / 16000f}s)")

                val (verified, similarity) = speakerVerifier.verify(audio)
                LogCollector.i(TAG, "Speaker verify (mic 2): verified=$verified similarity=${"%.4f".format(similarity)}")
                phoneBtHost.sendSpeakerVerifyResult(verified, similarity)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Speaker verify (mic 2) error: ${e.message}")
                phoneBtHost.sendSpeakerVerifyResult(false, 0f)
            }
        }
    }

    // --- Mic stream watchdog ---

    private fun startMicWatchdog() {
        stopMicWatchdog()
        lastGlassesAudioTimestamp = 0L
        val runnable = object : Runnable {
            override fun run() {
                checkMicStreamHealth()
                // Self-heal translation desync: re-assert desired state only while
                // translation is active (the session already keeps the link hot, so
                // no idle chatter). Heals a start/stop command that dropped mid-flight.
                if (translationMode) pushTranslationState()
                mainHandler.postDelayed(this, MIC_WATCHDOG_INTERVAL_MS)
            }
        }
        micWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, MIC_WATCHDOG_INTERVAL_MS)
        LogCollector.i(TAG, "Mic stream watchdog started")
    }

    private fun stopMicWatchdog() {
        micWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        micWatchdogRunnable = null
    }

    private fun checkMicStreamHealth() {
        if (!phoneBtHost.isConnected) return
        if (btAudioClientSocket == null) return

        val ts = lastGlassesAudioTimestamp
        if (ts == 0L) return  // no audio received yet

        val stalledMs = android.os.SystemClock.elapsedRealtime() - ts
        if (stalledMs < MIC_STALL_THRESHOLD_MS) return

        // FIX 3b: never restart the glasses mic while a notification voice-reply or
        // tg-chat voice session is actively capturing -- restart_audio tears down the
        // live capture mid-utterance and destroys the user's reply. notifReplyId != null
        // marks an active notif reply; telegramVoiceMode marks an active voice capture
        // (set true for both notif replies and tg-chat voice). The watchdog still
        // recovers a genuinely dead mic in the IDLE/non-reply case below.
        if (notifReplyId != null || telegramVoiceMode) {
            LogCollector.w(TAG, "Mic stream stalled for ${stalledMs}ms but voice reply active (notifReplyId=$notifReplyId, tgVoice=$telegramVoiceMode); skipping restart_audio")
            return
        }

        // During a system-source translation the glasses mic is NOT the capture source
        // (playback sub-source = phone MediaProjection; call sub-source = far-party downlink
        // over the MESSAGE socket). The a1b2c3 audio socket that feeds
        // lastGlassesAudioTimestamp is legitimately quiet: the glasses stream-mode gate
        // auto-reverts to LOCAL_ONLY and stops writing. Firing restart_audio here put the
        // glasses in a 40s hard-restart loop (mic pump + socket cycle) that contended with
        // the translation front-mic recorder's 8ch HAL capture. Skip the watchdog for the
        // whole system source, not just the call sub-source.
        if (translationMode && translationAudioSource == "system") {
            LogCollector.w(TAG, "Mic stream stalled for ${stalledMs}ms but system-source translation active (sub-source=$systemSubSource); skipping restart_audio")
            return
        }

        LogCollector.w(TAG, "Mic stream stalled for ${stalledMs}ms, requesting glasses mic restart")
        lastGlassesAudioTimestamp = android.os.SystemClock.elapsedRealtime()
        phoneBtHost.sendDeviceCommand("restart_audio", "{}")
    }

    /**
     * Deliver a confirmed voice reply to the originating notification by firing
     * its native RemoteInput PendingIntent. On any failure (expired/dismissed
     * notification, canceled intent, etc.) push an error notification to the
     * glasses so the user is informed.
     */
    private fun fireNotificationReply(notifId: String, text: String) {
        // Test notifications (test_notif repliable=true) have no real RemoteInput;
        // echo the reply and report success instead of pushing a failure notification.
        if (notifId.startsWith("testrepl-")) {
            LogCollector.i(TAG, "Notif reply (TEST echo): ${text.take(120)}")
            notifIdToReplyRef.remove(notifId)
            return
        }
        val ref = notifIdToReplyRef[notifId]
        try {
            val entry = ref?.sbnKey?.let { com.repository.listener.notification.NotificationReplyStore.get(it) }
            if (entry == null) {
                LogCollector.w(TAG, "Notif reply: no stored reply action for notifId=$notifId (sbnKey=${ref?.sbnKey})")
                phoneBtHost.sendNotifReplyResult(notifId, false)
                return
            }
            try {
                val intent = Intent()
                val results = android.os.Bundle().apply { putCharSequence(entry.resultKey, text) }
                android.app.RemoteInput.addResultsToIntent(entry.remoteInputs, intent, results)
                entry.actionIntent.send(applicationContext, 0, intent)
                LogCollector.i(TAG, "Notif reply fired for notifId=$notifId (resultKey=${entry.resultKey}) text=${text.take(40)}")
                phoneBtHost.sendNotifReplyResult(notifId, true)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Notif reply fire failed for notifId=$notifId: ${e.message}")
                phoneBtHost.sendNotifReplyResult(notifId, false)
            }
        } finally {
            // Prune on every outcome (success, no-store-entry, and fire failure) so the
            // map cannot grow unbounded across the service lifetime.
            notifIdToReplyRef.remove(notifId)
        }
    }

    private fun armTelegramVoiceNoSpeechWatchdog() {
        // New session: re-open the single-finalize gate so this session can be
        // finalized exactly once (the previous session already consumed its gate).
        //
        // This happens BEFORE the local-mode check on purpose. Returning early
        // without re-opening the gate would leave it latched shut from the
        // previous session, so the SECOND local failure onwards could never
        // finalize: the utterance would be lost and notifReplyId never cleared,
        // hanging the notification reply in SENDING.
        telegramVoiceFinalizing.set(false)
        clearTelegramVoiceNoSpeechWatchdog()

        // The watchdog itself fires when the PHONE's VAD has heard nothing. In
        // local mode it hears nothing by design, so arming it would cancel every
        // healthy on-glasses session after the timeout.
        if (!glassesSttGate.shouldArmNoSpeechWatchdog()) {
            LogCollector.i(TAG, "glasses STT is local; no-speech watchdog not armed")
            return
        }
        clearTelegramVoiceNoSpeechWatchdog()
        val watchdog = Runnable {
            if (!telegramVoiceMode) return@Runnable
            if (telegramVoiceSpeechDetected) return@Runnable
            LogCollector.i(TAG, "Telegram voice: no-speech watchdog fired (no speech in ${AppConfig.NO_SPEECH_TIMEOUT_MS}ms), finalizing")
            finishTelegramVoiceRecording()
        }
        telegramVoiceNoSpeechWatchdog = watchdog
        mainHandler.postDelayed(watchdog, AppConfig.NO_SPEECH_TIMEOUT_MS)
    }

    private fun clearTelegramVoiceNoSpeechWatchdog() {
        telegramVoiceNoSpeechWatchdog?.let { mainHandler.removeCallbacks(it) }
        telegramVoiceNoSpeechWatchdog = null
    }

    /**
     * The on-glasses recogniser reported it could not transcribe this utterance
     * (model absent, NPU busy, Binder timeout, capture killed). Re-run it the old
     * way over the PCM the phone buffered throughout the session -- that buffer
     * exists for exactly this, so a local failure costs latency, not the wearer's
     * words.
     *
     * Both finishers already own the whole tail (batch transcription, delivery,
     * the cancel contract), so this dispatches to them rather than duplicating it.
     */
    private fun fallbackToRemoteTranscription(tag: String) {
        if (tag == LocalTranscriptWire.TAG_TG_VOICE) {
            // finishTelegramVoiceRecording reads telegramVoiceAudioBuffer itself
            // and owns the whole tail, including the blank-is-cancel contract.
            finishTelegramVoiceRecording()
            return
        }

        // The assistant path cannot just call finishGlassesRecording(): that
        // finisher is the tail of the REMOTE flow and returns immediately unless
        // the state is CONFIRMING, which local mode never enters (there is no
        // phone-side VAD to detect end-of-speech and open the 2 s confirm
        // window). It also transcribes pendingGlassesChunks, which only
        // startGlassesConfirmDelay ever fills -- so on the local path it would
        // find an empty buffer.
        //
        // Hand the buffered audio over and enter CONFIRMING explicitly, so the
        // one existing implementation of batch transcription and delivery runs
        // rather than a second copy of it.
        if (glassesAudioState != GlassesAudioState.LISTENING) {
            LogCollector.w(TAG, "local STT fallback ignored: state=$glassesAudioState")
            return
        }
        synchronized(glassesAudioBuffer) {
            pendingGlassesChunks = glassesAudioBuffer.toList()
            glassesAudioBuffer.clear()
        }
        if (pendingGlassesChunks.isEmpty()) {
            // Nothing was ever buffered, so there is nothing to recover. Return
            // to IDLE rather than leaving the wearer in LISTENING forever.
            LogCollector.w(TAG, "local STT fallback has no buffered audio; dismissing")
            setGlassesState(GlassesAudioState.IDLE, "local stt fallback empty")
            phoneBtHost.sendDismissSession()
            return
        }
        // Local mode showed no live partials, so there is no stream text: the
        // finisher will take its batch-transcription branch.
        pendingGlassesStreamText = ""
        setGlassesState(GlassesAudioState.CONFIRMING, "local stt fallback")
        finishGlassesRecording()
    }

    private fun finishTelegramVoiceRecording() {
        // Single-finalize gate: only the first of the (up to three) concurrent
        // callers proceeds. Reset in armTelegramVoiceNoSpeechWatchdog() when the
        // next session starts.
        if (!telegramVoiceFinalizing.compareAndSet(false, true)) {
            LogCollector.i(TAG, "Telegram voice: finalize already in progress, ignoring duplicate")
            return
        }
        clearTelegramVoiceNoSpeechWatchdog()
        telegramVoiceMode = false
        // A notification reply has no chatId; it is keyed by notifReplyId instead.
        // Both flows share capture/STT and the "final text -> sendGlassesUserText"
        // delivery below. Only one of the two identifiers will be set.
        // Snapshot the id this finalize belongs to: delivery may complete long
        // after the session ends, by which time notifReplyId may hold a NEW
        // reply's id. Everything downstream compares against this snapshot.
        val notifIdForThisSession = notifReplyId
        val isNotifReply = notifIdForThisSession != null
        if (!isNotifReply && telegramVoiceChatId == null) return
        stopTranscriberStream()

        val savedChunks: List<ShortArray>
        synchronized(telegramVoiceAudioBuffer) {
            savedChunks = telegramVoiceAudioBuffer.toList()
            telegramVoiceAudioBuffer.clear()
        }

        if (!telegramVoiceSpeechDetected || savedChunks.isEmpty()) {
            LogCollector.i(TAG, "Telegram voice: no speech detected, skipping")
            // An explicit EMPTY FINAL, not silence: this is the blank-is-cancel
            // contract, and it goes through the SAME delivery function as a real
            // transcript so the local and remote paths cannot diverge.
            deliverTelegramVoiceTranscript("", notifIdForThisSession)
            return
        }

        // Use transcriber stream's final text first, fall back to batch
        val streamText = transcriberStreamClient?.getLastFinalText() ?: ""

        serviceScope.launch(Dispatchers.IO) {
            try {
                val text = streamText.ifBlank {
                    LogCollector.i(TAG, "Telegram voice: stream text empty, using batch transcription")
                    val totalSamples = savedChunks.sumOf { it.size }
                    val pcmBytes = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (chunk in savedChunks) {
                        for (s in chunk) pcmBytes.putShort(s)
                    }
                    run {
                        phoneBtHost.setActiveSession("transcription_pending")
                        try {
                            transcriptionClient.transcribe(pcmBytes.array(), AppConfig.getSttProvider(this@ListenerService), AppConfig.getSttLanguage(this@ListenerService)) ?: ""
                        } finally {
                            phoneBtHost.clearActiveSession("transcription_pending")
                        }
                    }
                }

                // Delivery (including the blank-is-cancel case and clearing the
                // notif-reply marker) is owned by deliverTelegramVoiceTranscript,
                // which the local on-glasses STT path calls too.
                deliverTelegramVoiceTranscript(text, notifIdForThisSession)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Telegram voice transcription error: ${e.message}")
            }
        }
    }

    // --- TtsPlayer.TtsListener ---

    override fun onPlaybackStarted() {
        LogCollector.i(TAG, "TTS playback started")
        mainHandler.post {
            mainHandler.removeCallbacks(ttsTimeoutRunnable)
            wakeWordDetector.setMode(WakeWordDetector.Mode.TTS_INTERRUPT)
            wakeWordDetector.reset()
            overlay.startResponding()
        }
    }

    override fun onPlaybackFinished() {
        LogCollector.i(TAG, "TTS playback finished")
        mainHandler.post {
            if (state == State.RESPONDING) {
                dismiss()
            }
        }
    }

    override fun onInterrupted(requestId: String) {
        LogCollector.i(TAG, "TTS interrupted, sending interrupt for $requestId")
        orchestratorClient.sendTtsInterrupt(requestId)
        mainHandler.post {
            if (state == State.RESPONDING) {
                dismiss()
            }
        }
    }

    override fun onTtsAmplitude(level: Float) {
        if (state == State.RESPONDING) {
            overlay.updateTtsLevel(level)
        }
    }

    // --- PhoneBtHost.GlassesListener ---

    private fun resetScreenHeartbeatWatchdog() {
        screenHeartbeatWatchdog?.let { mainHandler.removeCallbacks(it) }
        val watchdog = Runnable {
            if (isGlassesScreenOn) {
                LogCollector.i(TAG, "Glasses screen heartbeat timeout -- assuming screen OFF")
                isGlassesScreenOn = false
            }
        }
        screenHeartbeatWatchdog = watchdog
        mainHandler.postDelayed(watchdog, 20_000L)
    }

    override fun onGlassesStateSnapshot(state: String, requestId: String?) {
        LogCollector.i(TAG, "Glasses state snapshot: state=$state requestId=$requestId currentPhoneState=$glassesAudioState")
        // Reconcile phone's glassesAudioState to the snapshot. Authoritative source:
        // glasses-side state. Per-transition CH_STATUS messages may have been dropped
        // while the relay was disconnected; this restores correctness on reconnect.
        //
        // Glasses-side State enum is only IDLE/LISTENING/RESPONDING. Phone-side
        // GlassesAudioState additionally has SENDING/CONFIRMING which are brief
        // intermediate states owned by the phone -- the glasses never report them,
        // so they don't appear here.
        when (state) {
            "LISTENING" -> {
                if (glassesAudioState != GlassesAudioState.LISTENING) {
                    // Reuse existing self-activate path: same buffer reset + VAD start +
                    // setGlassesState(LISTENING) which calls startTranscriberStream(true).
                    onGlassesStatus("LISTENING")
                }
            }
            "IDLE" -> {
                if (glassesAudioState != GlassesAudioState.IDLE) {
                    setGlassesState(GlassesAudioState.IDLE, "snapshot reconcile")
                }
            }
            "RESPONDING" -> {
                // Glasses already mid-response from a prior session. Phone caught up
                // post-disconnect; nothing for the phone to do beyond noting state.
                if (glassesAudioState == GlassesAudioState.IDLE) {
                    setGlassesState(GlassesAudioState.RESPONDING, "snapshot reconcile")
                }
            }
            else -> LogCollector.w(TAG, "Glasses state snapshot: unknown state=$state, ignoring")
        }
    }

    override fun onGlassesStatus(state: String) {
        LogCollector.i(TAG, "Glasses status: $state")
        when (state) {
            // "screen_on" = real screen-on event from glasses ScreenStateReceiver.
            // "heartbeat_screen_on" = periodic liveness signal while screen is on.
            // "glasses_audio_open" = glasses is opening the BT audio gate
            //   (signalAudioStart -- phone/PC request, BT-connect, activate-listening,
            //   post-diagnostic restart). Historically this was conflated with
            //   "screen_on"; it shares the same downstream effect (mark screen on +
            //   kick the heartbeat watchdog) because an open audio gate implies the
            //   glasses UI is active, but it is a distinct event on the wire.
            "screen_on", "heartbeat_screen_on", "glasses_audio_open" -> {
                isGlassesScreenOn = true
                resetScreenHeartbeatWatchdog()
            }
            "screen_off", "heartbeat_screen_off" -> {
                isGlassesScreenOn = false
                resetScreenHeartbeatWatchdog()
            }
            "LISTENING" -> {
                // Glasses triggered listening locally (long-tap). Start glasses VAD session.
                if (glassesAudioState == GlassesAudioState.IDLE && phoneBtHost.isConnected) {
                    LogCollector.i(TAG, "Glasses self-activated listening, starting VAD")
                    synchronized(glassesAudioBuffer) { glassesAudioBuffer.clear() }
                    glassesVadEngine?.reset()
                    val now = System.currentTimeMillis()
                    glassesLastSpeechTime = now
                    glassesRecordingStartTime = now
                    glassesSpeechDetected = false
                    glassesVadChunkCount = 0
                    setGlassesState(GlassesAudioState.LISTENING, "glasses self-activate")
                    if (this.state != State.IDLE) dismiss()
                }
            }
            "IDLE" -> {
                // Glasses-side authoritative idle. Normally phone-side state clears
                // when the orchestrator response/TTS lands, but if that path stalls
                // (network hiccup, response hangs) the phone gets stuck in
                // RESPONDING/SENDING and the next hold-tap is silently rejected
                // because the LISTENING case above requires IDLE. Treat this as a
                // forcing signal: glasses says IDLE, we obey.
                if (glassesAudioState != GlassesAudioState.IDLE) {
                    setGlassesState(GlassesAudioState.IDLE, "glasses status IDLE")
                }
            }
            "RESPONDING" -> {
                // Mirror of IDLE handling for snapshot-style symmetry. Only forward-
                // transition from IDLE; if we're already mid-flight (SENDING/etc.)
                // don't stomp on it.
                if (glassesAudioState == GlassesAudioState.IDLE) {
                    setGlassesState(GlassesAudioState.RESPONDING, "glasses status RESPONDING")
                }
            }
            "CANCEL_CONFIRM" -> {
                LogCollector.i(TAG, "Glasses CANCEL_CONFIRM, glassesState=$glassesAudioState")
                when (glassesAudioState) {
                    GlassesAudioState.IDLE -> {} // already idle
                    GlassesAudioState.CONFIRMING -> cancelGlassesConfirm()
                    GlassesAudioState.LISTENING, GlassesAudioState.SENDING, GlassesAudioState.RESPONDING -> {
                        pendingPhotoDeferred?.complete(null)
                        pendingPhotoDeferred = null
                        setGlassesState(GlassesAudioState.IDLE, "cancel from $glassesAudioState")
                        phoneBtHost.sendDismissSession()
                    }
                }
            }
        }
    }

    override fun onGlassesCommand(command: String, args: List<String>) {
        LogCollector.i(TAG, "Glasses command: $command args=${args.map { it.take(40) }}")
        when (command) {
            "stop_journey" -> {
                LogCollector.i(TAG, "Glasses requested journey stop")
                mainHandler.post {
                    NavigationManager.getInstance(this).stopJourney()
                }
            }
            "nav_zoom" -> {
                // Glasses zoom slider: args = [fraction] in 0..1. The phone maps it
                // into the active provider's own zoom range.
                val fraction = args.firstOrNull()?.toFloatOrNull()
                if (fraction == null) {
                    LogCollector.w(TAG, "nav_zoom: invalid fraction=${args.firstOrNull()}")
                } else {
                    LogCollector.i(TAG, "Glasses zoom: fraction=$fraction")
                    mainHandler.post {
                        NavigationManager.getInstance(this).setMinimapZoomFraction(fraction)
                    }
                }
            }
            "recent_photo" -> {
                val base64 = args.firstOrNull()
                if (!base64.isNullOrEmpty()) {
                    LogCollector.i(TAG, "Received glasses photo (${base64.length} chars)")
                    val deferred = pendingPhotoDeferred
                    if (deferred != null && !deferred.isCompleted) {
                        deferred.complete(base64)
                    } else {
                        lastGlassesPhotoBase64 = base64
                        lastGlassesPhotoTimestamp = System.currentTimeMillis()
                    }
                }
            }
            "lone_devices_update" -> {
                // Glasses (source of truth) pushed the merged device list for the Lone mode modal.
                val json = args.firstOrNull() ?: return
                sendBroadcast(Intent(ACTION_LONE_DEVICES).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_LONE_DEVICES, json)
                })
            }
        }
    }

    private var glassesAudioDataCount = 0L
    private var glassesAudioDataDecodeErrCount = 0L

    override fun onGlassesAudioData(b64Pcm: String) {
        glassesAudioDataCount++

        // Copilot mode: front-beam PCM is the INTERLOCUTOR when their source is
        // the glasses mic. Feed it straight to the interlocutor recognizer.
        if (copilotMode && copilotInterlocutorSource == "glasses") {
            val pcm = try {
                android.util.Base64.decode(b64Pcm, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            } catch (t: Throwable) { return }
            if (pcm.isNotEmpty() && pcm.size % 2 == 0) {
                copilotInterlocutorSession?.takeIf { it.isRunning }?.pushPcm(pcm)
            }
            return
        }

        // The general audio bus is RFCOMM/Opus -- this CXR-S/RFCOMM-data channel
        // has too much buffering for low-latency capture. BUT the glasses-side
        // BeamformMicRecorder still ships translation PCM through here, so for
        // translation mode we decode and feed it into the same gating path used
        // by the Opus mic. Otherwise (general audio) keep ignoring it.
        if (!translationMode || translationAudioSource != "glasses") return

        val pcmBytes: ByteArray = try {
            android.util.Base64.decode(b64Pcm, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        } catch (t: Throwable) {
            if (glassesAudioDataDecodeErrCount++ % 100 == 0L) {
                LogCollector.w(TAG, "onGlassesAudioData base64 decode failed (#${glassesAudioDataDecodeErrCount}): ${t.message}")
            }
            return
        }
        if (pcmBytes.isEmpty() || pcmBytes.size % 2 != 0) return

        val samples = ShortArray(pcmBytes.size / 2)
        java.nio.ByteBuffer.wrap(pcmBytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        feedGlassesAudioFromMic(samples)
    }

    private var glassesInwardAudioCount = 0L
    private var glassesInwardAudioDecodeErrCount = 0L
    private var glassesCallAudioCount = 0L
    private var glassesCallAudioDecodeErrCount = 0L

    /**
     * Inward mic audio from glasses (CH_AUDIO_DATA_INWARD).
     * Only used in two-way translation: captures the wearer's speech and feeds
     * it to the azurePhoneMicSession (results shown on the phone screen).
     */
    private fun onGlassesInwardAudioData(b64Pcm: String) {
        // Copilot mode: inward mic is always the WEARER.
        if (copilotMode) {
            val pcm = try {
                android.util.Base64.decode(b64Pcm, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            } catch (t: Throwable) { return }
            if (pcm.isNotEmpty() && pcm.size % 2 == 0) {
                copilotWearerSession?.takeIf { it.isRunning }?.pushPcm(pcm)
            }
            return
        }
        if (!translationTwoWay || !translationMode) return
        glassesInwardAudioCount++

        val pcmBytes: ByteArray = try {
            android.util.Base64.decode(b64Pcm, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        } catch (t: Throwable) {
            if (glassesInwardAudioDecodeErrCount++ % 100 == 0L) {
                LogCollector.w(TAG, "onGlassesInwardAudioData base64 decode failed (#${glassesInwardAudioDecodeErrCount}): ${t.message}")
            }
            return
        }
        if (pcmBytes.isEmpty() || pcmBytes.size % 2 != 0) return

        if (glassesInwardAudioCount <= 3 || glassesInwardAudioCount % 200 == 0L) {
            LogCollector.i(TAG, "Inward mic audio #$glassesInwardAudioCount: ${pcmBytes.size} bytes, azurePhoneMicSession=${azurePhoneMicSession?.isRunning ?: false}")
        }

        val sess = azurePhoneMicSession
        if (sess != null && sess.isRunning) {
            sess.pushPcm(pcmBytes)
            // Broadcast audio level for spectrogram on TwoWayTranslationActivity
            val samples = ShortArray(pcmBytes.size / 2)
            java.nio.ByteBuffer.wrap(pcmBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples)
            broadcastTwoWayAudioLevel(samples)
        }
    }


    // --- Glasses wake word handling ---

    private var glassesWakeWordCooldown = 0L

    private fun handleGlassesWakeWordDetected() {
        if (glassesAudioState != GlassesAudioState.IDLE) return
        if (!orchestratorClient.isConnected) {
            LogCollector.i(TAG, "Glasses wake word ignored - orchestrator not connected")
            return
        }

        LogCollector.i(TAG, "Glasses wake word detected")

        // Compute amplitude for conflict resolution
        glassesWakeAmplitude = computeGlassesAvgRms()
        glassesWakeTimestamp = System.currentTimeMillis()
        LogCollector.i(TAG, "Glasses wake amplitude (computed): $glassesWakeAmplitude")

        // Rule 1: glasses screen ON = glasses always win, no conflict needed
        if (isGlassesScreenOn) {
            phoneBtHost.activateWithEnsure(healthMonitor)
            glassesVadEngine?.reset()

            val now = System.currentTimeMillis()
            glassesLastSpeechTime = now
            glassesRecordingStartTime = now
            glassesSpeechDetected = false
            glassesVadChunkCount = 0
            setGlassesState(GlassesAudioState.LISTENING, "wake word (screen ON)")
            // Force-dismiss phone if it also started
            if (state != State.IDLE) {
                dismiss()
            }
            resetWakeConflictState()
            return
        }

        // Rule 2: glasses screen OFF - start phone session (phone mic may not hear it)
        LogCollector.i(TAG, "Glasses wake word + screen OFF - starting phone session")
        if (state != State.IDLE) return

        state = State.ACTIVATING
        synchronized(audioBuffer) { audioBuffer.clear() }
        vadEngine.reset()
        wakeWordDetector.setMode(WakeWordDetector.Mode.CANCEL)
        wakeWordDetector.reset()
        val now = System.currentTimeMillis()
        lastSpeechTime = now
        recordingStartTime = now
        speechDetected = false
        vadChunkCount = 0
        state = State.LISTENING
        startTranscriberStream(false)
        startPromptSpeakerVerification(false)
        LogCollector.i(TAG, ">>> CONVERSATION STARTED via PHONE MIC (glasses wake, screen OFF)")
        // UI operations must run on main thread (this runs on glasses audio thread)
        mainHandler.post {
            overlay.show()
            activatePlayer?.let {
                if (it.isPlaying) it.stop()
                it.seekTo(0)
                it.start()
            }
        }
        updateNotification("Recording...")
        broadcastState("LISTENING", "Wake word detected - recording")
        resetWakeConflictState()

        // Check for conflict with phone wake word
        if (phoneWakeTimestamp > 0 &&
            glassesWakeTimestamp - phoneWakeTimestamp <= WAKE_COMPARISON_WINDOW_MS) {
            resolveWakeConflict()
        }
    }

    private fun handleGlassesCancelWordDetected() {
        if (glassesAudioState != GlassesAudioState.LISTENING) return
        LogCollector.i(TAG, "Glasses cancel word detected")
        setGlassesState(GlassesAudioState.IDLE, "cancel word")
        phoneBtHost.sendDismissSession()
    }

    private fun handleGlassesTtsInterruptDetected() {
        LogCollector.i(TAG, "Glasses TTS interrupt detected")
        if (glassesAudioState == GlassesAudioState.RESPONDING) {
            setGlassesState(GlassesAudioState.IDLE, "TTS interrupt")
        }
        phoneBtHost.sendDismissSession()
    }

    private fun startGlassesConfirmDelay() {
        if (glassesAudioState != GlassesAudioState.LISTENING) return

        // Stop transcriber stream and wait for final text (up to 3s)
        pendingGlassesStreamText = transcriberStreamClient?.stopAndWaitForFinal(3000) ?: ""

        setGlassesState(GlassesAudioState.CONFIRMING, "silence detected")
        LogCollector.i(TAG, "Glasses stream text: '${pendingGlassesStreamText}'")

        // Save audio chunks for batch fallback
        synchronized(glassesAudioBuffer) {
            pendingGlassesChunks = glassesAudioBuffer.toList()
            glassesAudioBuffer.clear()
        }

        if (!glassesSpeechDetected) {
            LogCollector.i(TAG, "Glasses: no speech detected, skipping transcription")
            setGlassesState(GlassesAudioState.IDLE, "no speech")
            phoneBtHost.sendDismissSession()
            return
        }

        // Gate on prompt-level speaker verification
        promptVerificationJob?.cancel()
        promptVerificationJob = null
        if (AppConfig.PROMPT_SPEAKER_VERIFICATION_ENABLED && AppConfig.SPEAKER_VERIFICATION_ENABLED
            && ::speakerVerifier.isInitialized && !promptSpeakerVerified) {
            LogCollector.i(TAG, "Glasses prompt speaker NOT verified (similarity=${"%.4f".format(promptSpeakerSimilarity)}) - cancelling")
            PipelineTracer.onSpeakerRejected(promptSpeakerSimilarity, AppConfig.SPEAKER_SIMILARITY_THRESHOLD, "glasses-prompt")
            setGlassesState(GlassesAudioState.IDLE, "speaker not verified")
            phoneBtHost.sendDismissSession()
            return
        }

        // Show final user text on glasses as "pending" (clears partial messages)
        val previewText = stripWakeWords(pendingGlassesStreamText)
        if (previewText.isNotBlank()) {
            phoneBtHost.sendGlassesUserText("pending", previewText)
        }

        // 2s cancel window
        confirmRunnable = Runnable {
            confirmRunnable = null
            finishGlassesRecording()
        }
        mainHandler.postDelayed(confirmRunnable!!, 2000L)
    }

    private fun cancelGlassesConfirm() {
        LogCollector.i(TAG, "Glasses: cancel confirm, aborting send")
        setGlassesState(GlassesAudioState.IDLE, "confirm cancelled")
        phoneBtHost.sendDismissSession()
    }

    private fun finishGlassesRecording() {
        if (glassesAudioState != GlassesAudioState.CONFIRMING) return
        setGlassesState(GlassesAudioState.SENDING, "confirm timeout")

        val streamText = pendingGlassesStreamText
        val savedGlassesChunks = pendingGlassesChunks
        pendingGlassesStreamText = ""
        pendingGlassesChunks = emptyList()

        serviceScope.launch {
            try {
                val rawText = streamText.ifBlank {
                    LogCollector.i(TAG, "Glasses stream empty, falling back to batch transcription")
                    val totalSamples = savedGlassesChunks.sumOf { it.size }
                    val pcmBytes = java.nio.ByteBuffer.allocate(totalSamples * 2)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (chunk in savedGlassesChunks) {
                        for (s in chunk) pcmBytes.putShort(s)
                    }
                    run {
                        phoneBtHost.setActiveSession("transcription_pending")
                        try {
                            transcriptionClient.transcribe(pcmBytes.array(), AppConfig.getSttProvider(this@ListenerService), AppConfig.getSttLanguage(this@ListenerService)) ?: ""
                        } finally {
                            phoneBtHost.clearActiveSession("transcription_pending")
                        }
                    }
                }
                deliverGlassesTranscript(
                    rawText,
                    hadStreamPreview = streamText.isNotBlank(),
                    source = TranscriptSource.REMOTE,
                )
            } catch (e: Exception) {
                LogCollector.e(TAG, "Glasses transcription error: ${e.message}")
                setGlassesState(GlassesAudioState.IDLE, "transcription error")
                phoneBtHost.sendDismissSession()
            }
        }
    }

    /**
     * The text-consuming tail of finishTelegramVoiceRecording, shared by the
     * remote transcriber and the on-glasses local STT path. Covers Telegram
     * voice, notification reply and RC voice -- all three share the "tg_voice"
     * requestId and are disambiguated by the glasses' focusState.
     *
     * BLANK IS A CANCEL, not "nothing happened". A blank final must still reach
     * the glasses as an empty user text, or a notification reply hangs forever in
     * SENDING / NOTIFICATION_REPLY. This is the single implementation of that
     * contract: the "no speech detected" early return calls it with "" rather
     * than open-coding the emission, so local and remote cannot diverge.
     *
     * notifReplyId is cleared on EVERY exit path. Leaving it set would wedge the
     * next voice session, since onTelegramVoiceStart rejects while it is set (the
     * RemoteInput fire itself is driven later by the glasses' CH_NOTIF_REPLY_SEND,
     * which carries its own notifId, so it is not needed past this point).
     */
    private fun deliverTelegramVoiceTranscript(text: String, notifId: String?) =
        transcriptDelivery.deliverTelegramVoiceTranscript(text, notifId)

    /**
     * The text-consuming tail of finishGlassesRecording, shared by the remote
     * transcriber and the on-glasses local STT path.
     *
     * @param hadStreamPreview whether a live partial was already shown on the
     *   glasses. LOCAL is always false (finals only), so it sends the "pending"
     *   preview -- correct, since the glasses have displayed nothing yet.
     * @param source LOCAL must drive the SENDING transition itself: in local mode
     *   the phone never passes through CONFIRMING/SENDING, so without it the
     *   RESPONDING guard is false and the glasses hang in LISTENING.
     */
    private suspend fun deliverGlassesTranscript(
        rawText: String,
        hadStreamPreview: Boolean,
        source: TranscriptSource,
    ) = transcriptDelivery.deliverGlassesTranscript(rawText, hadStreamPreview, source)

    /**
     * The single TranscriptDelivery instance. All glasses transcript delivery --
     * remote and local, assistant and tg_voice -- goes through this, so the
     * JVM-tested logic IS the shipped logic.
     */
    private val transcriptDelivery: TranscriptDelivery by lazy {
        TranscriptDelivery(object : TranscriptDelivery.Port {
            override fun sendGlassesUserText(requestId: String, text: String) =
                phoneBtHost.sendGlassesUserText(requestId, text)

            override fun clearNotifReplyIdIfStill(expected: String?) {
                // Compare-and-clear: delivery can complete many seconds after the
                // session ended (batch transcription, the 15 s photo fetch). By
                // then a NEW notification reply may own the field, and clearing it
                // unconditionally would wedge that new session.
                if (notifReplyId == expected) notifReplyId = null
            }

            override fun currentGlassesState(): GlassesAudioState = glassesAudioState

            override fun setGlassesState(s: GlassesAudioState, reason: String) =
                this@ListenerService.setGlassesState(s, reason)

            override suspend fun sendToOrchestrator(text: String): OrchestratorSend {
                val model = AppConfig.getModel(this@ListenerService)
                val userSystemPrompt = AiContextBuilder.build(
                    AppConfig.getUserSystemPrompt(this@ListenerService).ifBlank { null },
                    notificationHistory, cachedTodos
                )
                // Auto-attach: use cached photo or fetch from glasses (event-driven)
                var recentPhoto = if (System.currentTimeMillis() - lastGlassesPhotoTimestamp < 60_000) lastGlassesPhotoBase64 else null
                if (recentPhoto == null) {
                    LogCollector.i(TAG, "Requesting DCIM photo from glasses for auto-attach")
                    val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
                    pendingPhotoDeferred = deferred
                    phoneBtHost.sendCommand("fetch_dcim_photo", "voice_photo", "{}")
                    recentPhoto = kotlinx.coroutines.withTimeoutOrNull(15_000L) { deferred.await() }
                    pendingPhotoDeferred = null
                }
                if (recentPhoto != null) {
                    lastGlassesPhotoBase64 = null
                    lastGlassesPhotoTimestamp = 0
                    LogCollector.i(TAG, "Auto-attaching glasses photo to voice request (${recentPhoto.length} chars)")
                } else {
                    LogCollector.i(TAG, "No recent photo from glasses, sending without image")
                }
                // Remembered so a Failed result can arm the retry with the same model.
                lastGlassesSendModel = model
                val sent = orchestratorClient.sendRequest(
                    text, imageBase64 = recentPhoto, model = model,
                    deviceType = "glasses", userSystemPrompt = userSystemPrompt
                )
                if (!sent) return OrchestratorSend.Failed
                val id = orchestratorClient.lastRequestId
                    ?: return OrchestratorSend.SentWithoutId
                return OrchestratorSend.Sent(id)
            }

            override fun registerRequestId(requestId: String) {
                glassesRequestIds.add(requestId)
            }

            override fun armRetry(text: String) {
                // Only armed on ACTUAL send failure: arming unconditionally before
                // the send caused duplicate requests on a WS hiccup.
                pendingGlassesRetry = Pair(text, lastGlassesSendModel)
            }

            override fun stripWakeWords(text: String): String =
                this@ListenerService.stripWakeWords(text)

            override fun dismissSession() = phoneBtHost.sendDismissSession()

            override fun log(msg: String) = LogCollector.i(TAG, msg)
        })
    }

    private fun stripWakeWords(text: String): String {
        // Strip any remaining wake word fragments from transcription
        return text.replace(Regex("сиренев\\S*", RegexOption.IGNORE_CASE), "").trim()
    }

    // --- System audio capture (phone's own audio output) ---

    private fun requestProjection() {
        LogCollector.w(TAG, "System audio capture requires MediaProjection -- requesting consent")
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_REQUEST_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun startSystemAudioCaptureWithRetry(retries: Int, delayMs: Long) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!translationMode || translationAudioSource != "system") return@postDelayed
            if (systemAudioCapturer?.isCapturing == true) return@postDelayed
            startSystemAudioCapture()
            if (systemAudioCapturer == null && retries > 0) {
                LogCollector.w(TAG, "System audio capture retry ($retries left, next delay ${delayMs * 2}ms)")
                startSystemAudioCaptureWithRetry(retries - 1, delayMs * 2)
            }
        }, delayMs)
    }

    private fun startSystemAudioCapture() {
        if (!hasProjection) {
            requestProjection()
            return
        }
        val projection = screenCapturer.getMediaProjection()
        if (projection == null) {
            LogCollector.w(TAG, "MediaProjection object is null -- invalidating and re-requesting")
            hasProjection = false
            requestProjection()
            return
        }
        systemAudioCapturer?.stop()
        val capturer = SystemAudioCapturer(projection)
        capturer.listener = this
        if (!capturer.start()) {
            LogCollector.e(TAG, "System audio capture failed -- could not register audio policy")
            return
        }
        systemAudioCapturer = capturer
        LogCollector.i(TAG, "System audio capture started for translation")
    }

    private fun stopSystemAudioCapture() {
        // Translation and Copilot can both use system audio via the SAME shared
        // MediaProjection. Only tear down the capturer + release the projection
        // if the other feature isn't still relying on system audio.
        val translationNeedsSystem = translationMode && translationAudioSource == "system"
        val copilotNeedsSystem = copilotMode && copilotInterlocutorSource == "system"
        if (translationNeedsSystem || copilotNeedsSystem) {
            LogCollector.i(TAG, "stopSystemAudioCapture skipped -- other feature still using system audio (translation=$translationNeedsSystem copilot=$copilotNeedsSystem)")
            return
        }
        systemAudioCapturer?.stop()
        systemAudioCapturer = null
        releaseMediaProjection()
    }

    private fun releaseMediaProjection() {
        if (!hasProjection) return
        screenCapturer.release()
        hasProjection = false
        // Downgrade FGS type back to MICROPHONE only (removes casting indicator)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Listening..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            LogCollector.i(TAG, "FGS downgraded to MICROPHONE only (projection released)")
        } catch (e: Exception) {
            // During onDestroy or if service is stopping, this may fail
            LogCollector.w(TAG, "Could not downgrade FGS type: ${e.message}")
        }
    }

    // --- Phone call state handling (prevents echo during calls) ---

    private fun registerCallStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val tm = telephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(callState: Int) {
                    handleCallStateChanged(callState)
                }
            }
            tm.registerTelephonyCallback(mainExecutor, callback)
            callStateCallback = callback
            LogCollector.i(TAG, "Call state listener registered (TelephonyCallback)")
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                val listener = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(callState: Int, phoneNumber: String?) {
                        handleCallStateChanged(callState)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                callStateCallback = listener
                LogCollector.i(TAG, "Call state listener registered (PhoneStateListener)")
            } else {
                LogCollector.w(TAG, "READ_PHONE_STATE not granted -- call state detection disabled")
            }
        }
    }

    private fun unregisterCallStateListener() {
        val tm = telephonyManager ?: return
        val callback = callStateCallback ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tm.unregisterTelephonyCallback(callback as TelephonyCallback)
        } else {
            @Suppress("DEPRECATION")
            tm.listen(
                callback as android.telephony.PhoneStateListener,
                android.telephony.PhoneStateListener.LISTEN_NONE
            )
        }
        callStateCallback = null
        telephonyManager = null
        LogCollector.i(TAG, "Call state listener unregistered")
    }

    private fun handleCallStateChanged(callState: Int) {
        when (callState) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!isInPhoneCall) {
                    isInPhoneCall = true
                    LogCollector.i(TAG, "Phone call active -- pausing mic (system audio capture continues)")

                    // Stop mic recording to prevent echo / AEC conflict.
                    // System audio capture (AudioPlaybackCapture) is safe during calls --
                    // it only captures USAGE_MEDIA/GAME/UNKNOWN, not VOICE_COMMUNICATION.
                    audioRecorder.stop()

                    // Reset pipeline state (wake word / TTS)
                    if (state != State.IDLE) {
                        ttsPlayer.interrupt()
                        state = State.IDLE
                        overlay.hide()
                        synchronized(audioBuffer) { audioBuffer.clear() }
                        wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
                        wakeWordDetector.reset()
                    }
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isInPhoneCall) {
                    isInPhoneCall = false
                    LogCollector.i(TAG, "Phone call ended -- resuming mic")

                    wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
                    wakeWordDetector.reset()
                    audioRecorder.start()
                }
            }
        }
    }

    override fun onSystemAudioChunk(samples: ShortArray) {
        // Copilot mode: system audio is the INTERLOCUTOR (e.g. the other party
        // on a phone call) when their source is set to system audio.
        if (copilotMode && copilotInterlocutorSource == "system") {
            val sess = copilotInterlocutorSession ?: return
            if (!sess.isRunning) return
            val bb = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putShort(s)
            sess.pushPcm(bb.array())
            return
        }
        if (!translationMode || translationAudioSource != "system") return
        // During a call the far party arrives on CH_AUDIO_DATA_CALL (onCallAudioData);
        // the phone's own playback capture yields silence but may still tick, so gate it
        // to the playback sub-source and let onCallAudioData own the call sub-source.
        if (systemSubSource != "playback") return
        // Stream all audio to server -- no phone-side VAD gating.
        // Server-side whisper vad_filter handles silence detection.
        val byteBuffer = java.nio.ByteBuffer.allocate(samples.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in samples) byteBuffer.putShort(s)
        val pcmBytes = byteBuffer.array()
        if (translationProvider == "azure") {
            azureTranslationSession?.pushPcm(pcmBytes)
            broadcastTwoWayAudioLevel(samples)
        } else {
            orchestratorClient.sendTranscribeAudioFrame(pcmBytes)
        }
    }

    /**
     * Far-party HFP call downlink from the glasses (CH_AUDIO_DATA_CALL). Feeds the SAME
     * translation session as onSystemAudioChunk, so "System Audio" translation covers
     * phone calls. Guarded on the call sub-source so it never double-feeds with playback.
     */
    private fun onGlassesCallAudioData(b64Opus: String) {
        if (!translationMode || translationAudioSource != "system") return
        if (systemSubSource != "call") return
        // The glasses Opus-compress the call downlink to avoid saturating RFCOMM.
        // The payload is Base64(concatenated 2-byte-LE-length Opus frames); the
        // encoder used NO_WRAP, so decode with NO_WRAP only -- adding NO_PADDING
        // here would corrupt frames whose Base64 length is not a multiple of 4.
        val opusBytes: ByteArray = try {
            android.util.Base64.decode(b64Opus, android.util.Base64.NO_WRAP)
        } catch (t: Throwable) {
            if (glassesCallAudioDecodeErrCount++ % 100 == 0L) {
                LogCollector.w(TAG, "onGlassesCallAudioData base64 decode failed (#${glassesCallAudioDecodeErrCount}): ${t.message}")
            }
            return
        }
        if (opusBytes.isEmpty()) return

        val pcmBytes = decodeCallOpusFrames(opusBytes) ?: return
        if (pcmBytes.isEmpty() || pcmBytes.size % 2 != 0) return
        glassesCallAudioCount++
        if (glassesCallAudioCount <= 3 || glassesCallAudioCount % 200 == 0L) {
            LogCollector.i(TAG, "Call downlink audio #$glassesCallAudioCount: ${pcmBytes.size} PCM bytes (${opusBytes.size} opus)")
        }
        if (translationProvider == "azure") {
            azureTranslationSession?.pushPcm(pcmBytes)
            val samples = ShortArray(pcmBytes.size / 2)
            java.nio.ByteBuffer.wrap(pcmBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples)
            broadcastTwoWayAudioLevel(samples)
        } else {
            orchestratorClient.sendTranscribeAudioFrame(pcmBytes)
        }
    }

    /**
     * Parse concatenated 2-byte-LE-length Opus frames and decode them to a single
     * PCM16LE buffer via the long-lived [callOpusDecoder]. Lazily initializes the
     * decoder. Returns null if the decoder is unavailable or produced nothing.
     * Serialized on [callOpusDecoderLock] against teardown from other threads.
     */
    private fun decodeCallOpusFrames(opusBytes: ByteArray): ByteArray? {
        synchronized(callOpusDecoderLock) {
            var decoder = callOpusDecoder
            if (decoder == null) {
                decoder = com.repository.listener.audio.OpusStreamDecoder()
                if (!decoder.initialize()) {
                    LogCollector.e(TAG, "Call Opus decoder init failed; call downlink will not be decoded")
                    callOpusDecoder = null
                    return null
                }
                callOpusDecoder = decoder
            }

            val out = java.io.ByteArrayOutputStream(opusBytes.size * 4)
            var pos = 0
            var produced = false
            while (pos + 2 <= opusBytes.size) {
                val frameLen = (opusBytes[pos].toInt() and 0xFF) or
                    ((opusBytes[pos + 1].toInt() and 0xFF) shl 8)
                pos += 2
                if (frameLen <= 0 || pos + frameLen > opusBytes.size) {
                    // Truncated/garbled tail -- stop rather than read out of bounds.
                    if (glassesCallAudioDecodeErrCount++ % 100 == 0L) {
                        LogCollector.w(TAG, "Call Opus frame length $frameLen out of range at pos=$pos len=${opusBytes.size}")
                    }
                    break
                }
                val frame = opusBytes.copyOfRange(pos, pos + frameLen)
                pos += frameLen
                val pcm = decoder.decode(frame) ?: continue
                val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.asShortBuffer().put(pcm)
                out.write(bb.array())
                produced = true
            }
            return if (produced) out.toByteArray() else null
        }
    }

    /** Release the call-downlink Opus decoder. Safe to call when none exists. */
    private fun releaseCallOpusDecoder() {
        synchronized(callOpusDecoderLock) {
            callOpusDecoder?.let { try { it.release() } catch (_: Throwable) {} }
            callOpusDecoder = null
        }
    }

    /**
     * Glasses HFP SCO state relay (CH_CALL_STATE). The glasses are the hands-free endpoint,
     * so their SCO state is the authoritative signal that far-party call audio is arriving.
     * Flip the translation system sub-source so onSystemAudioChunk / onGlassesCallAudioData
     * hand off cleanly. No session restart -- the sink is source-agnostic.
     */
    private fun onGlassesCallState(scoActive: Boolean) {
        val newSub = if (scoActive) "call" else "playback"
        if (systemSubSource == newSub) return
        systemSubSource = newSub
        // Leaving the call sub-source: drop the Opus decoder so the next call starts
        // from a clean codec + framing boundary (mirrors the per-accept fresh decoder
        // on the dedicated MicStream socket). It is re-created lazily on the next
        // call chunk.
        if (newSub != "call") releaseCallOpusDecoder()
        LogCollector.i(TAG, "Translation system sub-source -> $newSub (glasses sco=$scoActive)")
    }

    // --- Glasses amplitude tracking for conflict resolution ---

    private fun trackGlassesIdleAmplitude(samples: ShortArray) {
        var sumSq = 0.0
        var maxAbs = 0
        for (s in samples) {
            val f = s.toDouble()
            sumSq += f * f
            val a = kotlin.math.abs(s.toInt())
            if (a > maxAbs) maxAbs = a
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size).toFloat()
        val rmsNorm = rms / 32768f
        val maxNorm = maxAbs / 32768f
        glassesRmsValues[glassesRmsWriteIndex % glassesRmsValues.size] = rmsNorm
        glassesRmsWriteIndex++
        glassesChunkCount++
        // Log every ~2 seconds (assuming 16kHz 512-sample chunks = ~60 chunks/2s)
        if (glassesChunkCount % 60 == 0) {
            val count = minOf(glassesRmsWriteIndex, glassesRmsValues.size)
            var sum = 0f
            var peak = 0f
            for (i in 0 until count) {
                sum += glassesRmsValues[i]
                if (glassesRmsValues[i] > peak) peak = glassesRmsValues[i]
            }
            val avg = sum / count
            LogCollector.d(TAG, "GLASSES MIC: avg=%.5f max=%.5f peak=%.5f chunks=%d"
                .format(avg, maxNorm, peak, glassesChunkCount))
        }
    }

    private fun computeGlassesAvgRms(): Float {
        val count = minOf(glassesRmsWriteIndex, glassesRmsValues.size)
        if (count == 0) return 0f
        var sum = 0f
        for (i in 0 until count) sum += glassesRmsValues[i]
        return sum / count
    }


    override fun onGlassesConnected() {
        LogCollector.i(TAG, "Glasses connected via BT")
        glassesAudioArchiver?.start()
        // Send current settings to glasses
        val settings = JSONObject().apply {
            put("model", AppConfig.getModel(this@ListenerService))
            put("deviceId", AppConfig.getDeviceId(this@ListenerService))
        }
        phoneBtHost.sendSettings(settings.toString())
        // Kick off sync handshake. forceSync posts onto the FSM executor so it's safe to call
        // even if BT connected before GlassesSyncClient finished initializing (startup race).
        try { glassesSyncClient.onBtConnected() } catch (e: Exception) {
            LogCollector.w(TAG, "glassesSyncClient.onBtConnected failed: ${e.message}")
        }

        // Weather widget: push cached frame if fresh (<24h) and enabled; kick a one-shot refresh.
        if (AppConfig.isWeatherEnabled(this@ListenerService)) {
            try {
                val icon = AppConfig.getLastWeatherIcon(this@ListenerService)
                val fetchMs = AppConfig.getLastWeatherFetchMs(this@ListenerService)
                val freshWindowMs = 24L * 3600L * 1000L
                if (icon.isNotEmpty() && (System.currentTimeMillis() - fetchMs) < freshWindowMs) {
                    val temp = AppConfig.getLastWeatherTemp(this@ListenerService)
                    val loc = AppConfig.getLastWeatherLocation(this@ListenerService)
                    phoneBtHost.sendWeather(icon, temp.toString(), loc)
                }
                WeatherScheduler.oneShot(this@ListenerService)
            } catch (e: Exception) {
                LogCollector.w(TAG, "weather push on BT connect failed: ${e.message}")
            }
        }

        // Push all saved display/system settings to glasses (they revert to OS defaults on reconnect)
        phoneBtHost.syncAllSettings(this@ListenerService)

        // Re-sync active navigation to glasses after reconnect
        try {
            NavigationManager.getInstance(this@ListenerService).onBtReady()
        } catch (e: Exception) {
            LogCollector.w(TAG, "Nav re-sync on glasses connect failed: ${e.message}")
        }

        // The glasses hold no RC state of their own, and pushRcState only fires on RC
        // EVENTS. A session opened before the link came up therefore never reached the
        // glasses: nothing had changed since, so nothing was ever sent, and the chats
        // list stayed empty. Push the whole snapshot on connect, forced past the dedup
        // cache, the same way the translation config below is reasserted.
        try {
            pushRcState(force = true)
        } catch (e: Exception) {
            LogCollector.w(TAG, "RC state push on BT connect failed: ${e.message}")
        }

        // Always push current translation config so glasses display the correct language
        // pair even when translation is not active. Then restore active session if needed.
        try {
            val cfgFrom = if (translationFromLang.isNotEmpty()) translationFromLang else AppConfig.getTranslationFromLanguage(this@ListenerService)
            val cfgTo = if (translationToLang.isNotEmpty()) translationToLang else AppConfig.getTranslationToLanguage(this@ListenerService)
            val cfgFontSize = if (translationFontSize > 0) translationFontSize else AppConfig.getTranslationFontSize(this@ListenerService)
            val cfgTwoWay = translationTwoWay || AppConfig.getTranslationTwoWay(this@ListenerService)
            if (cfgFrom.isNotEmpty() && cfgTo.isNotEmpty()) {
                phoneBtHost.sendTranslationConfigToApp(cfgFrom, cfgTo, cfgFontSize, cfgTwoWay)
            }
            // Reconcile the glasses recorder to our authoritative translationMode on every
            // (re)connect. Idempotent on the glasses side; heals a start/stop command that
            // was lost while BT was down. One channel, one code path -- no fire-and-forget
            // start_translation/stop_translation restore to drift out of sync.
            pushTranslationState()
            LogCollector.i(TAG, "Translation restore: pushed translation state (active=$translationMode) on reconnect")
        } catch (e: Exception) {
            LogCollector.w(TAG, "Translation state restore failed: ${e.message}")
        }

        // Same rationale as the translation reconcile above: the glasses hold no RC state a push
        // does not replace wholesale, so one authoritative snapshot per link-up heals everything.
        pushRcState(force = true)

        // ...but the snapshot above can only describe sessions this SERVICE knows about, and the
        // phone's own RC UI starts sessions without ever telling the service. Pull the
        // authoritative list so the very first snapshot the glasses see is complete, and arm the
        // slow poll for sessions opened later while the link stays up.
        reconcileLiveRcSessions("glasses_connect")
        startRcLiveListPolling()

        // Sync phone time/timezone to glasses over the relay. The glasses-side
        // set_time handler applies the wall clock via root and re-applies on boot.
        serviceScope.launch {
            try {
                val params = JSONObject().apply {
                    put("epochMs", System.currentTimeMillis())
                    put("tz", java.util.TimeZone.getDefault().id)
                }
                val resp = phoneBtHost.sendDeviceCommand("set_time", params.toString())
                if (resp != null) {
                    LogCollector.i(TAG, "Synced time to glasses (set_time)")
                } else {
                    LogCollector.w(TAG, "set_time command got no reply (timeout)")
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to sync time to glasses: ${e.message}")
            }
        }

        // Update device-identity mirrors (reachability state + ACTION_GLASSES_STATE
        // broadcast are now owned by PhoneBtHost.GlassesReachability; do not write
        // glassesConnected here).
        glassesDeviceName = phoneBtHost.bluetoothHelper.connectedDeviceName
        glassesDeviceMac = phoneBtHost.bluetoothHelper.connectedDeviceMac

        // Start health monitoring
        healthMonitor.start()
        startMicWatchdog()

        // Glasses mic audio: glasses capture mic locally and stream PCM over BT data channel.
        // Phone receives via CustomCmdListener "listener_audio_data" -> onGlassesAudioData().
        glassesAudioRouted = true
        // Force full cleanup on connect (may reconnect mid-session)
        val prevState = glassesAudioState
        glassesAudioState = GlassesAudioState.LISTENING // force non-IDLE so setGlassesState runs
        setGlassesState(GlassesAudioState.IDLE, "BT connect init")
        LogCollector.i(TAG, "Glasses audio: waiting for PCM stream over data channel (was $prevState)")

        // Start throughput tracking
        startThroughputTracking()

        // Push wall-clock + timezone to glasses. Bug 8.
        try {
            phoneBtHostInstance?.sendTimeSync()
        } catch (e: Exception) {
            LogCollector.w(TAG, "sendTimeSync on connect failed: ${e.message}")
        }
    }

    override fun onGlassesDisconnected() {
        LogCollector.i(TAG, "Glasses disconnected from BT")
        // Back to remote. The gate is only ever set by an announcement FROM the
        // glasses, so leaving it latched on "local" after they vanish means the
        // phone refuses to open its transcriber, feed its VAD or arm its watchdog
        // for the next session -- and nobody transcribes at all.
        glassesSttGate = GlassesSttGate.default()
        // Nothing renders the snapshot with the link down, so the poll is pure battery cost.
        stopRcLiveListPolling()
        // Without this the byte-identical dedup would permanently suppress the corrective resync
        // the next link-up owes: the glasses come back holding nothing. Under the push lock so it
        // cannot be overwritten by a push already past its own send.
        synchronized(rcStatePushLock) { lastPushedStateJson = null }
        // Clean up mouse HID bridge -- glasses can't send reports anymore
        phoneBtHost.onMouseReport = null
        phoneHidMouse?.destroy()
        phoneHidMouse = null
        broadcastMouseHidStatus()
        audioDucker.reset()
        // GlassesAudioArchiver.stop() does encoderThread.join(10s) to drain the MediaCodec.
        // This callback runs on the main thread (handleDisconnect posts onDisconnected via
        // mainHandler), so a synchronous join can ANR the process when BT teardown races
        // with active recording. Offload to IO; the archiver guards its own state.
        val archiver = glassesAudioArchiver
        if (archiver != null) {
            serviceScope.launch(Dispatchers.IO) {
                try { archiver.stop() } catch (e: Exception) {
                    LogCollector.w(TAG, "glassesAudioArchiver.stop failed: ${e.message}")
                }
            }
        }
        notificationQueue.clear("bt_disconnect")
        glassesAudioRouted = false
        try { glassesSyncClient.onBtDisconnected() } catch (e: Exception) {
            LogCollector.w(TAG, "glassesSyncClient.onBtDisconnected failed: ${e.message}")
        }

        // Pause nav streaming so we don't waste CPU rendering into a dead BT connection
        try {
            NavigationManager.getInstance(this@ListenerService).onBtLost()
        } catch (e: Exception) {
            LogCollector.w(TAG, "Nav pause on glasses disconnect failed: ${e.message}")
        }

        // Cleanup Telegram state
        if (telegramVoiceMode) {
            clearTelegramVoiceNoSpeechWatchdog()
            stopAzureNotifReplySession()
            telegramVoiceMode = false
            telegramVoiceChatId = null
            notifReplyId = null
            stopTranscriberStream()
            synchronized(telegramVoiceAudioBuffer) { telegramVoiceAudioBuffer.clear() }
        }
        // Glasses are gone: every queued/displayed notification is unreachable, so no
        // stored reply ref can ever be consumed. Drop them all to bound map growth.
        notifIdToReplyRef.clear()
        // Any pending continuation-TTS mappings can no longer be played; drop them so
        // a never-arriving orchestrator reply can't leak entries across a session.
        appendTtsContToNotif.clear()
        currentGlassesTgChatTitle = null
        serviceScope.launch(Dispatchers.IO) { orchestratorClient.sendTelegramUnsubscribe() }
        screenHeartbeatWatchdog?.let { mainHandler.removeCallbacks(it) }
        screenHeartbeatWatchdog = null

        // Translation mode survives BT reconnects: keep translationMode true so
        // audio resumes pushing to Azure (or the transcribe stream) the moment
        // glasses audio comes back. Tearing down here orphans the Azure session
        // and the UI sits in translation mode with no results.
        // Default provider's transcribe stream reconnects via its own onStreamError.
        // Azure session is PCM-driven: no PCM during the gap is harmless.
        // System audio capture *is* phone-local so it can keep going.
        if (translationMode && translationProvider != "azure") {
            orchestratorClient.disconnectTranscribeStream()
        }

        // Stop health monitoring
        healthMonitor.stop()
        stopMicWatchdog()

        // Stop glasses audio state
        if (glassesAudioState != GlassesAudioState.IDLE) {
            setGlassesState(GlassesAudioState.IDLE, "BT disconnect")
        }
        isGlassesScreenOn = false

        // Clear device-identity mirrors. Reachability state + ACTION_GLASSES_STATE
        // broadcast are owned by PhoneBtHost.GlassesReachability; it handles the
        // disconnected transition (debounced via confirm-ping).
        glassesDeviceName = null
        glassesDeviceMac = null
        glassesBatteryLevel = -1
        glassesBatteryCharging = false

        // Stop throughput tracking
        stopThroughputTracking()
    }

    // --- ScreenStateReceiver.ScreenStateListener ---

    override fun onScreenOff() {
        LogCollector.i(TAG, "Screen off - stopping mic")
        if (!isInPhoneCall) audioRecorder.stop()
        if (state != State.IDLE) {
            ttsPlayer.interrupt()
            state = State.IDLE
            overlay.hide()
            synchronized(audioBuffer) {
                audioBuffer.clear()
            }
            wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
            wakeWordDetector.reset()
        }
    }

    override fun onScreenUnlocked() {
        LogCollector.i(TAG, "Screen unlocked - resuming mic")
        wakeWordDetector.setMode(WakeWordDetector.Mode.WAKE)
        wakeWordDetector.reset()
        if (!isInPhoneCall) audioRecorder.start()
    }

    // --- Spectrogram ---

    private fun updateSpectrogram(samples: ShortArray) {
        val numBars = 28

        // RMS energy per band with overlapping segments for smoother look
        val segmentSize = samples.size / numBars
        val bars = FloatArray(numBars)

        for (i in 0 until numBars) {
            // Overlapping window: each bar reads 2x segment with 50% overlap
            val center = (i + 0.5f) * segmentSize
            val halfWin = segmentSize.toFloat()
            val start = maxOf(0, (center - halfWin).toInt())
            val end = minOf(samples.size, (center + halfWin).toInt())

            var sumSq = 0.0
            for (j in start until end) {
                val s = samples[j].toDouble()
                sumSq += s * s
            }
            val rms = kotlin.math.sqrt(sumSq / (end - start))
            // Normalize RMS (typical speech RMS ~1000-4000, max ~32768)
            bars[i] = (rms / 4000.0).toFloat().coerceIn(0f, 1f)
        }

        // Log scale for perceptual balance
        for (i in 0 until numBars) {
            bars[i] = kotlin.math.ln(1f + bars[i] * 10f) / kotlin.math.ln(11f)
            // Smooth with previous (light smoothing, main interpolation is in overlay)
            bars[i] = 0.2f * prevBars[i] + 0.8f * bars[i]
            prevBars[i] = bars[i]
        }

        overlay.updateBars(bars)
    }

    // --- Glasses alarm sync helper ---

    private fun sendAlarmListToGlasses() {
        val alarms = AlarmStore.getAll(this@ListenerService)
        val arr = org.json.JSONArray()
        for (a in alarms) {
            arr.put(org.json.JSONObject().apply {
                put("id", a.id)
                put("hour", a.hour)
                put("minute", a.minute)
                put("title", a.title)
                put("enabled", a.enabled)
                put("triggerTimeMillis", a.triggerTimeMillis)
            })
        }
        phoneBtHost.sendAlarmListResponse(arr.toString())
    }

    // --- Chat broadcasts ---

    private fun broadcastChatMessage(requestId: String, role: String, text: String) {
        val json = JSONObject().apply {
            put("requestId", requestId)
            put("role", role)
            put("text", text)
        }.toString()
        sendBroadcast(Intent(ACTION_CHAT_MESSAGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_CHAT_MESSAGE, json)
        })
    }

    private fun broadcastToolStatus(requestId: String, toolName: String, status: String, toolArgs: JSONObject? = null, toolCallId: String = "") {
        val json = JSONObject().apply {
            put("requestId", requestId)
            put("toolName", toolName)
            put("status", status)
            if (toolArgs != null) put("toolArgs", toolArgs)
            if (toolCallId.isNotEmpty()) put("toolCallId", toolCallId)
        }.toString()
        sendBroadcast(Intent(ACTION_TOOL_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_TOOL_STATUS, json)
        })
    }

    private fun broadcastMetadata(requestId: String, totalTokens: Int) {
        // NO BROADCAST for glasses requests
        if (requestId in glassesRequestIds) {
            return
        }

        if (requestSentTimestamp > 0) {
            val responseTimeMs = System.currentTimeMillis() - requestSentTimestamp
            val timeSec = "%.1f".format(responseTimeMs / 1000.0)
            val metaText = "${timeSec}s | $totalTokens tokens"
            broadcastChatMessage(requestId, "METADATA", metaText)
            requestSentTimestamp = 0
        } else if (totalTokens > 0) {
            broadcastChatMessage(requestId, "METADATA", "$totalTokens tokens")
        }
    }

    // --- State broadcast ---

    private fun broadcastState(state: String, detail: String) {
        sendBroadcast(Intent(ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_DETAIL, detail)
        })
    }

    // --- Notification ---

    // sessionId -> turning? (true = AI currently generating for that session)
    private val rcSessionTurning = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Bounded projection of RC events into rows the glasses render. Dies with the service, exactly
     * like rcDumpState. Its 8-entry LRU is the sole memory bound (see RcMirrorStore.MAX_SESSIONS).
     */
    private val rcMirror = com.repository.listener.rc.RcMirrorStore()

    /**
     * Which mirrored prompts the glasses may still answer. Separate from the row projection because
     * that projection is append-only: a delivered prompt row cannot be mutated to withdraw its
     * options, so "already resolved" is decided here, on the answer path.
     */
    private val rcPrompts = com.repository.listener.rc.RcPromptRegistry()

    /** Owns the six RC mirror channels so neither PhoneBtHost nor this class grows the logic. */
    private val rcBridge = com.repository.listener.rc.RcBridge(
        store = rcMirror,
        prompts = rcPrompts,
        send = { channel, args -> phoneBtHost.sendRcIfConnected(channel, *args) },
        sendUserMessage = { sessionId, text ->
            orchestratorClient.sendRcUserMessage(sessionId, text)
            noteRcUserMessage(sessionId, text)
        },
        sendUserResponse = { sessionId, requestId, text ->
            orchestratorClient.sendRcUserResponse(sessionId, requestId, text)
        },
        sendPermissionResponse = { sessionId, requestId, approved, mode, reason ->
            markRcTurning(sessionId, true)
            serviceScope.launch(Dispatchers.IO) {
                orchestratorClient.sendRcPermissionResponse(sessionId, requestId, approved, mode, reason)
            }
        },
        markRead = { sessionId, seenSeq -> markRcRead(sessionId, seenSeq) },
        requestTranscript = { sessionId -> orchestratorClient.requestRcTranscript(sessionId) },
        cachedTranscript = { sessionId -> rcTranscriptCache[sessionId] }
    )
    // Pending "mark this session as done" runnables, keyed by sessionId. Used to
    // debounce isFinal MESSAGE events: agentic Claude Code emits isFinal=true
    // between tool calls (text -> tool -> text -> tool -> final), so clearing
    // turning immediately would flash "X/N Done" between segments.
    private val rcDoneClearRunnables = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    private val rcTurnFinishRunnables = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    private val RC_DONE_DEBOUNCE_MS: Long = 5000L
    @Volatile private var lastIdleNotificationText: String = "Waiting for command"

    /**
     * Mark a session as turning (true) or about-to-be-done (false). When false,
     * the clear is debounced -- if any further activity arrives within
     * RC_DONE_DEBOUNCE_MS it gets cancelled and the session stays turning.
     */
    private fun markRcTurning(sessionId: String, turning: Boolean) {
        if (turning) {
            // Use compute() to avoid resurrecting an entry that onRcSessionEnd
            // just removed in a race; only update when the key already exists.
            var changed = false
            rcSessionTurning.compute(sessionId) { _, prev ->
                if (prev == null) {
                    null
                } else {
                    if (prev != true) changed = true
                    true
                }
            }
            if (rcSessionTurning.containsKey(sessionId)) {
                rcDoneClearRunnables.remove(sessionId)?.let { mainHandler.removeCallbacks(it) }
            }
            if (changed) refreshRcNotification()
        } else {
            // Schedule a delayed clear; replace any prior pending one.
            rcDoneClearRunnables.remove(sessionId)?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                rcDoneClearRunnables.remove(sessionId)
                if (rcSessionTurning.containsKey(sessionId)) {
                    rcSessionTurning[sessionId] = false
                    refreshRcNotification()
                }
            }
            rcDoneClearRunnables[sessionId] = r
            mainHandler.postDelayed(r, RC_DONE_DEBOUNCE_MS)
        }
    }

    /** Cancel any pending debounce clear for the given session. */
    private fun cancelRcDoneClear(sessionId: String) {
        rcDoneClearRunnables.remove(sessionId)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Update notification text based on remote-session activity.
     *  - 0 sessions => last idle text (e.g. "Waiting for command")
     *  - N sessions => "X/N Done" where X = sessions not currently turning
     */
    private fun refreshRcNotification() {
        val total = rcSessionTurning.size
        if (total == 0) {
            updateNotification(lastIdleNotificationText)
            return
        }
        val done = rcSessionTurning.values.count { !it }
        updateNotification("$done/$total Done")
    }

    /**
     * Set the fallback "idle" notification text. Only applied immediately when
     * there are no active remote sessions; otherwise stored for later.
     */
    private fun setIdleText(text: String) {
        lastIdleNotificationText = text
        if (rcSessionTurning.isEmpty()) updateNotification(text)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Repositorry")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    // --- ReID person intel for glasses ---

    private fun buildGlassesPersonIntel(personUid: String, person: JSONObject): JSONObject {
        val result = JSONObject()
        result.put("uid", personUid)

        val infoFound = person.optJSONObject("information_found")

        // Collect name candidates with confidence from all sources, pick best
        data class NameCandidate(val name: String, val confidence: Int, val source: String)
        val nameCandidates = mutableListOf<NameCandidate>()
        var bestPhone = ""
        var bestAge = -1

        // Photo matches section
        val sections = org.json.JSONArray()
        val sherlockPhoto = infoFound?.optJSONObject("sherlock_photo")
        val photoPersons = sherlockPhoto?.optJSONArray("persons")
        if (photoPersons != null && photoPersons.length() > 0) {
            val photoSection = JSONObject()
            photoSection.put("title", "Photo Matches")
            val items = org.json.JSONArray()
            for (i in 0 until photoPersons.length()) {
                val p = photoPersons.optJSONObject(i) ?: continue
                val fullName = p.optString("fullName", "").ifBlank {
                    val fn = p.optString("firstName", "")
                    val ln = p.optString("lastName", "")
                    "$fn $ln".trim()
                }
                val similarity = p.optDouble("similarity", 0.0)
                val confidenceInt = if (similarity > 1.0) similarity.toInt() else (similarity * 100).toInt()
                if (fullName.isNotBlank()) nameCandidates.add(NameCandidate(fullName, confidenceInt, "sherlock"))

                val nameItem = JSONObject()
                nameItem.put("label", "Name")
                nameItem.put("value", fullName.ifBlank { "Unknown" })
                nameItem.put("confidence", confidenceInt)
                items.put(nameItem)

                val age = p.optInt("age", 0)
                if (age > 0) {
                    if (bestAge < 0) bestAge = age
                    val ageItem = JSONObject()
                    ageItem.put("label", "Age")
                    ageItem.put("value", age.toString())
                    items.put(ageItem)
                }

                val city = p.optString("city", "")
                if (city.isNotBlank()) {
                    val cityItem = JSONObject()
                    cityItem.put("label", "City")
                    cityItem.put("value", city)
                    items.put(cityItem)
                }

                val phone = p.optString("phone", "")
                if (phone.isNotBlank()) {
                    if (bestPhone.isBlank()) bestPhone = phone
                    val phoneItem = JSONObject()
                    phoneItem.put("label", "Phone")
                    phoneItem.put("value", phone)
                    items.put(phoneItem)
                }

                val vk = p.optString("vkUrl", "").ifBlank {
                    val vkObj = p.optJSONObject("vk")
                    vkObj?.optString("url", "") ?: p.optString("vk", "")
                }
                if (vk.isNotBlank()) {
                    val vkItem = JSONObject()
                    vkItem.put("label", "VK")
                    vkItem.put("value", vk)
                    items.put(vkItem)
                }
            }
            photoSection.put("items", items)
            sections.put(photoSection)
        }

        // VK search (search4faces) section
        val vkSearch = infoFound?.optJSONObject("search4faces_vk")
        val vkPersons = vkSearch?.optJSONArray("persons")
        if (vkPersons != null && vkPersons.length() > 0) {
            val vkSection = JSONObject()
            vkSection.put("title", "VK Matches")
            val items = org.json.JSONArray()
            for (i in 0 until vkPersons.length()) {
                val p = vkPersons.optJSONObject(i) ?: continue
                val fullName = p.optString("fullName", "").ifBlank {
                    p.optString("firstName", "")
                }.trim()

                val similarity = p.optDouble("similarity", 0.0)
                val confidenceInt = if (similarity > 1.0) similarity.toInt() else (similarity * 100).toInt()
                if (fullName.isNotBlank()) nameCandidates.add(NameCandidate(fullName, confidenceInt, "vk"))

                val nameItem = JSONObject()
                nameItem.put("label", "Name")
                nameItem.put("value", fullName.ifBlank { "Unknown" })
                nameItem.put("confidence", confidenceInt)
                items.put(nameItem)

                val age = p.optInt("age", 0)
                if (age > 0) {
                    if (bestAge < 0) bestAge = age
                    val ageItem = JSONObject()
                    ageItem.put("label", "Age")
                    ageItem.put("value", age.toString())
                    items.put(ageItem)
                }

                val city = p.optString("city", "")
                if (city.isNotBlank()) {
                    val cityItem = JSONObject()
                    cityItem.put("label", "City")
                    cityItem.put("value", city)
                    items.put(cityItem)
                }

                val vkObj = p.optJSONObject("vk")
                val vkUrl = vkObj?.optString("url", "") ?: ""
                if (vkUrl.isNotBlank()) {
                    val vkItem = JSONObject()
                    vkItem.put("label", "VK")
                    vkItem.put("value", vkUrl)
                    items.put(vkItem)
                }
            }
            vkSection.put("items", items)
            sections.put(vkSection)
        }

        // Phone lookup section
        val sherlockPhone = infoFound?.optJSONObject("sherlock_phone")
        if (sherlockPhone != null) {
            val phoneSection = JSONObject()
            phoneSection.put("title", "Phone Lookup")
            val items = org.json.JSONArray()

            val phone = sherlockPhone.optString("phone", "")
            if (phone.isNotBlank()) {
                if (bestPhone.isBlank()) bestPhone = phone
                val item = JSONObject()
                item.put("label", "Phone")
                item.put("value", phone)
                items.put(item)
            }

            val operator = sherlockPhone.optString("operator", "")
            if (operator.isNotBlank()) {
                val item = JSONObject()
                item.put("label", "Operator")
                item.put("value", operator)
                items.put(item)
            }

            val country = sherlockPhone.optString("country", "")
            if (country.isNotBlank()) {
                val item = JSONObject()
                item.put("label", "Country")
                item.put("value", country)
                items.put(item)
            }

            val name = sherlockPhone.optString("fullName", "").ifBlank { sherlockPhone.optString("name", "") }
            if (name.isNotBlank()) {
                nameCandidates.add(NameCandidate(name, 100, "sherlock_phone"))
                val item = JSONObject()
                item.put("label", "Name")
                item.put("value", name)
                items.put(item)
            }

            val dob = sherlockPhone.optString("dob", "").ifBlank { sherlockPhone.optString("birthday", "") }
            if (dob.isNotBlank()) {
                val item = JSONObject()
                item.put("label", "DOB")
                item.put("value", dob)
                items.put(item)
            }

            val phonebookNames = sherlockPhone.optJSONArray("phonebookNames")
                ?: sherlockPhone.optJSONArray("names")
            if (phonebookNames != null && phonebookNames.length() > 0) {
                val names = (0 until phonebookNames.length()).mapNotNull { phonebookNames.optString(it) }
                val item = JSONObject()
                item.put("label", "Names")
                item.put("value", names.joinToString(", "))
                items.put(item)
            }

            val socials = listOf(
                "VK" to (sherlockPhone.optString("vkUrl", "").ifBlank {
                    val vkObj = sherlockPhone.optJSONObject("vk")
                    vkObj?.optString("url", "") ?: sherlockPhone.optString("vk", "")
                }),
                "Telegram" to (sherlockPhone.optString("telegramUrl", "").ifBlank { sherlockPhone.optString("telegram", "") }),
                "WhatsApp" to (sherlockPhone.optString("whatsappUrl", "").ifBlank { sherlockPhone.optString("whatsapp", "") }),
                "OK" to (sherlockPhone.optString("okUrl", "").ifBlank { sherlockPhone.optString("ok", "") })
            )
            for ((label, value) in socials) {
                if (value.isNotBlank()) {
                    val item = JSONObject()
                    item.put("label", label)
                    item.put("value", value)
                    items.put(item)
                }
            }

            val emails = sherlockPhone.optJSONArray("emails")
            if (emails != null && emails.length() > 0) {
                val emailList = (0 until emails.length()).mapNotNull { emails.optString(it) }
                val item = JSONObject()
                item.put("label", "Emails")
                item.put("value", emailList.joinToString(", "))
                items.put(item)
            }

            if (items.length() > 0) {
                phoneSection.put("items", items)
                sections.put(phoneSection)
            }
        }

        // Identifiers section (search_keys)
        val searchKeys = person.optJSONArray("search_keys")
        if (searchKeys != null && searchKeys.length() > 0) {
            val grouped = mutableMapOf<String, MutableList<String>>()
            for (i in 0 until searchKeys.length()) {
                val key = searchKeys.optJSONObject(i) ?: continue
                val type = key.optString("type", "other")
                val value = key.optString("value", "")
                if (value.isNotBlank()) {
                    grouped.getOrPut(type) { mutableListOf() }.add(value)
                }
            }
            if (grouped.isNotEmpty()) {
                val idSection = JSONObject()
                idSection.put("title", "Identifiers")
                val items = org.json.JSONArray()
                for ((type, values) in grouped) {
                    val label = type.replace("_", " ").replaceFirstChar { it.uppercase() }
                    val item = JSONObject()
                    item.put("label", label)
                    item.put("value", values.joinToString(", "))
                    items.put(item)
                }
                idSection.put("items", items)
                sections.put(idSection)
            }
        }

        // Other sources (remaining keys in information_found)
        if (infoFound != null) {
            val knownKeys = setOf("sherlock_photo", "sherlock_phone", "search4faces_vk")
            val otherKeys = infoFound.keys().asSequence().filter { it !in knownKeys }.toList()
            for (sourceKey in otherKeys) {
                val sourceData = infoFound.optJSONObject(sourceKey) ?: continue
                val sourceSection = JSONObject()
                sourceSection.put("title", sourceKey.replace("_", " ").replaceFirstChar { it.uppercase() })
                val items = org.json.JSONArray()
                for (field in sourceData.keys()) {
                    val value = sourceData.opt(field) ?: continue
                    val valueStr = when (value) {
                        is org.json.JSONArray -> (0 until value.length()).mapNotNull { value.optString(it) }.joinToString(", ")
                        is JSONObject -> continue
                        else -> value.toString()
                    }
                    if (valueStr.isNotBlank() && valueStr != "null") {
                        val item = JSONObject()
                        item.put("label", field.replace("_", " ").replaceFirstChar { it.uppercase() })
                        item.put("value", valueStr)
                        items.put(item)
                    }
                }
                if (items.length() > 0) {
                    sourceSection.put("items", items)
                    sections.put(sourceSection)
                }
            }
        }

        // Pick best name by highest confidence across all sources
        val bestName = if (nameCandidates.isNotEmpty()) {
            nameCandidates.sortedByDescending { it.confidence }.first().name
        } else {
            person.optString("display_name", "").let {
                if (it == "null" || it == "Unknown Person" || it.isBlank()) "Unknown" else it
            }
        }

        result.put("name", bestName)
        result.put("phone", bestPhone)
        if (bestAge > 0) result.put("age", bestAge)
        result.put("sections", sections)

        return result
    }

    // --- Throughput tracking ---

    private fun startThroughputTracking() {
        if (throughputRunning) return
        throughputRunning = true
        totalTxBytes = 0L
        totalRxBytes = 0L
        phoneBtHost.getAndResetTxBytes()
        phoneBtHost.getAndResetRxBytes()
        throughputHandler.postDelayed(throughputRunnable, 1000)
    }

    private fun stopThroughputTracking() {
        throughputRunning = false
        throughputHandler.removeCallbacks(throughputRunnable)
    }

    // --- Mouse HID status ---

    private fun broadcastMouseHidStatus() {
        sendBroadcast(Intent(ACTION_MOUSE_HID_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_MOUSE_HID_CONNECTED, phoneHidMouse?.isConnected ?: false)
            putExtra(EXTRA_MOUSE_HID_DEVICE_NAME, phoneHidMouse?.connectedDeviceName ?: "")
        })
    }

    // Single state-driven sink for glasses RFCOMM mouse reports. When a video-stream
    // mouse channel is live (mouseEventListener != null) the report is transcoded to the
    // 7-byte stream frame and driven to the PC over the stream; otherwise it falls through
    // to the Bluetooth-HID bridge. Reading mouseEventListener per report makes streams
    // starting/ending mid-session switch the output automatically, in both directions.
    private fun wireGlassesMouseRouting() {
        lastMouseRoute = ""
        phoneBtHost.onMouseReport = { report ->
            val streamSink = mouseEventListener
            if (streamSink != null) {
                val m = decodeGlassesMouseReport(report)
                streamSink.invoke(encodeStreamMouseReport(m.dx, m.dy, m.buttons, m.scroll))
                if (lastMouseRoute != "stream") {
                    lastMouseRoute = "stream"
                    LogCollector.i(TAG, "Glasses mouse routing -> video stream")
                }
            } else {
                phoneHidMouse?.sendReport(report)
                if (lastMouseRoute != "hid") {
                    lastMouseRoute = "hid"
                    LogCollector.i(TAG, "Glasses mouse routing -> BT-HID")
                }
            }
        }
    }

    // Tracks the last route taken by the glasses mouse so a switch (stream<->hid) is logged once.
    private var lastMouseRoute: String = ""

    // --- Lifecycle ---

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        LogCollector.i(TAG, "Task removed -- finalizing audio archiver")
        backgroundTranscriber?.stop()
        glassesAudioArchiver?.stop()
    }

    override fun onDestroy() {
        LogCollector.i(TAG, "Service destroying")
        // Clear the static bridge reference first: GMS can bind the listener
        // service at any moment and must not hand frames to a dead bridge.
        // Identity-checked: a restart can interleave the OLD instance's onDestroy
        // after the NEW instance's onCreate, and an unconditional null would wipe
        // the live bridge and leave watch input permanently dead.
        if (com.repository.listener.wear.WatchMessageListenerService.bridge === watchInputBridge) {
            com.repository.listener.wear.WatchMessageListenerService.bridge = null
        }
        watchInputBridge?.shutdown()
        watchInputBridge = null
        // Set FIRST: callbacks posted from the OkHttp/WebRTC threads may already be
        // queued on the main looper and would otherwise rebuild a PeerConnectionFactory
        // or re-arm relay state on a dead service (leaked peer + audio device).
        serviceDestroyed = true
        // Lone mode: cancel the phone scan loop; do NOT send lone_stop so the glasses keep
        // detecting standalone. Off-switch is the explicit in-app stop or a glasses reboot.
        loneActive = false
        loneScanJob?.cancel()
        loneScanJob = null
        phoneBtHost.onMouseReport = null
        phoneHidMouse?.destroy()
        phoneHidMouse = null
        // Drop any pending RC done-debounce runnables so they don't fire after
        // the service is gone (would leak this through the lambda closure).
        rcDoneClearRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        rcDoneClearRunnables.clear()
        rcSessionTurning.clear()
        // Self-rescheduling: without this the chain outlives the service and reconciles into a
        // dead instance's map forever.
        stopRcLiveListPolling()
        phoneBtHostInstance = null
        sideloadHttpServerInstance = null
        if (::sideloadHttpServer.isInitialized) { try { sideloadHttpServer.stop() } catch (_: Exception) {} }
        audioDucker.reset()
        notificationQueue.clear("destroy")

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        audioRecorder.stop()
        wakeWordDetector.release()
        vadEngine.release()
        if (::speakerVerifier.isInitialized) speakerVerifier.release()
        backgroundTranscriber?.stop()
        backgroundTranscriber = null
        glassesAudioArchiver?.stop()
        audioArchiver = null
        glassesAudioArchiver = null
        try { glassesSyncClient.shutdown() } catch (_: Exception) {}
        glassesSyncClientInstance = null
        glassesVadEngine?.release()
        transcriberStreamClient?.release()
        stopAzureNotifReplySession()
        stopSystemAudioCapture()
        locationProvider.shutdown()
        orchestratorClient.closeStreamConnections()
        // Release the desktop's shared audio capture before dropping the socket. Without
        // this a cloud session outlives the service and the desktop keeps that peer
        // consuming the capture, halving the next session's frame rate.
        if (!audioRelayViaLan && (audioRelayActive || audioRelayStartInFlight)) {
            orchestratorClient.sendAudioRelayStop("desktop-listener")
        }
        orchestratorClient.disconnect()
        activatePlayer?.release()
        activatePlayer = null
        overlay.release()
        healthMonitor.stop()
        stopThroughputTracking()
        unregisterReceiver(screenStateReceiver)
        unregisterReceiver(settingsReceiver)
        unregisterReceiver(syncReceiver)
        unregisterReceiver(deleteReceiver)
        unregisterReceiver(streamReceiver)
        unregisterReceiver(todoReceiver)
        unregisterReceiver(jobReceiver)
        unregisterReceiver(rcInboundReceiver)
        unregisterReceiver(alarmChangedReceiver)
        unregisterReceiver(telegramNotifReceiver)
        try { unregisterReceiver(weatherUpdatedReceiver) } catch (_: Exception) {}
        try { WeatherScheduler.cancel(this) } catch (_: Exception) {}
        mainHandler.removeCallbacks(notifListenerHealthCheck)
        mouseEventListener = null
        keyboardEventListener = null
        webRTCClient?.release()
        webRTCClient = null
        // Tell the desktop to release its capture consumer before dropping the socket,
        // otherwise it keeps streaming into a dead session.
        lanRelayClient?.let {
            it.sendAudioRelayStop()
            it.listener = null
            it.close()
        }
        lanRelayClient = null
        desktopRelayLocator?.stop()
        desktopRelayLocator = null
        audioRelayVpnCallback?.let { cb ->
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        audioRelayVpnCallback = null
        audioRelayNetworkRecheck?.let { mainHandler.removeCallbacks(it) }
        audioRelayNetworkRecheck = null
        audioRelayRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        audioRelayRetryRunnable = null
        audioRelayStartWatchdog?.let { mainHandler.removeCallbacks(it) }
        audioRelayStartWatchdog = null
        releaseAudioRelayWifiLock()
        audioRelayActive = false
        unregisterCallStateListener()
        serviceScope.cancel()
        ttsPlayer.release()
        phoneBtHost.release()
        stopGlassesAudioStreamServer()

        super.onDestroy()
    }
}
