package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.repository.listener.config.AppConfig
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiHotStatusCallback
import com.rokid.cxr.client.extend.infos.RKAppInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level BT host for glasses communication.
 *
 * Messaging uses GlassesRfcommClient (direct RFCOMM, framed).
 * CxrApi is retained for non-messaging features: WiFi P2P hotspot (file sync),
 * Rokid APK launch/stop, and Rokid audio scene/codec control.
 */
class PhoneBtHost(private val context: Context) {

    companion object {
        private const val TAG = "PhoneBtHost"
        private const val RETRY_INTERVAL_MS = 5000L
        /** Max time a CONNECTING state can linger without any incoming RFCOMM frame before we
         *  conclude the attempt is zombied and reset it. Normal attempts complete in <5s; 30s
         *  gives slow BT stacks breathing room without making the UI feel dead. */
        private const val STALE_CONNECTING_MS = 30_000L
        private const val MAX_CACHED_FAILURES = 3
        private const val MAX_CAPS_CHARS = 10_000  // Safe limit: worst-case 3 bytes/char in JNI modified UTF-8
    }

    /** Direct RFCOMM client to glasses (replaces CXR-M custom commands). */
    private val rfcommClient = GlassesRfcommClient(context)

    /**
     * Dedicated outbound RFCOMM client for navigation map frames (MAP_UUID).
     * Isolated from rfcommClient so 10-15 FPS map traffic cannot head-of-line-
     * block audio/control. Map frames are droppable: sendBinary() drops when the
     * link is down rather than queuing.
     */
    private val mapRfcommClient = MapRfcommClient(context)

    /**
     * G3: persistent BLE wake link to glasses BleWakeService. Used to wake the
     * other side when we have outbound data and RFCOMM is torn down, and to
     * receive WAKE_WORD/BUTTON_PRESS/etc notifications from the glasses.
     */
    val bleWake = BleWakeNotifyClient(context)
    private val batteryLogger = BatteryEventLogger(context)
    @Volatile private var bleWakeStarted = false

    /** Coroutine scope for reachability ping/pong + the 5-minute background ticker. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** request-id generator for outbound BLE pings. Wraps 1..255, skipping 0. */
    private val pingIdSeq = AtomicInteger(0)
    private fun nextRequestId(): Int {
        // 1..255, skip 0 (0 is reserved/used as "no id" sentinel by some receivers).
        var v = pingIdSeq.incrementAndGet() and 0xFF
        if (v == 0) v = pingIdSeq.incrementAndGet() and 0xFF
        if (v == 0) v = 1
        return v
    }

    /** In-flight ping deferreds keyed by requestId. Resolved by onBlePong handler. */
    private val pendingPings = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    /** 5-minute background reachability ticker job. Started in ensureRfcommClientStarted. */
    private var reachabilityTickerJob: Job? = null

    /**
     * GlassesReachability is the SOLE writer of [ListenerService.glassesConnected] and the
     * SOLE emitter of [ListenerService.ACTION_GLASSES_STATE]. It debounces transient
     * RFCOMM bounces: a single missed ping does not flip Reachable -> Unreachable.
     * A confirm-ping is required, OR a hard close (RFCOMM disconnect followed by a
     * failed confirm).
     */
    private enum class State { Unknown, Reachable, Unreachable }

    private inner class GlassesReachability {
        @Volatile private var state: State = State.Unknown

        /** True iff a confirm-ping is currently outstanding (armed by a timeout or RFCOMM drop). */
        @Volatile private var confirmInFlight: Boolean = false

        @Synchronized
        fun onBlePong() {
            confirmInFlight = false
            transition(State.Reachable, "ble_pong")
        }

        @Synchronized
        fun onBleHello() {
            confirmInFlight = false
            transition(State.Reachable, "ble_hello")
        }

        @Synchronized
        fun onRfcommConnected() {
            confirmInFlight = false
            transition(State.Reachable, "rfcomm_connected")
        }

        @Synchronized
        fun onRfcommDisconnected() {
            // Do NOT flip immediately. RFCOMM bounces during normal idle. Arm a
            // confirm-ping; if it also fails we conclude Unreachable.
            armConfirmPing("rfcomm_disconnected")
        }

        @Synchronized
        fun onPingTimeout() {
            if (state == State.Reachable && !confirmInFlight) {
                // First missed ping while we believed Reachable -- arm a confirm-ping.
                armConfirmPing("ping_timeout")
                return
            }
            // Confirm-ping also timed out (or we were already Unreachable/Unknown).
            // Flip Unreachable.
            confirmInFlight = false
            transition(State.Unreachable, "ping_timeout_confirm")
        }

        private fun armConfirmPing(reason: String) {
            if (confirmInFlight) return
            confirmInFlight = true
            log("Reachability: arming confirm-ping ($reason)")
            scope.launch {
                // Brief settle delay before the confirm-ping so we don't race a fresh
                // RFCOMM reconnect.
                delay(500)
                pingGlasses(timeoutMs = 1500)
            }
        }

        private fun transition(next: State, reason: String) {
            val prev = state
            if (prev == next) return
            state = next
            log("Reachability: $prev -> $next ($reason)")
            broadcast(next == State.Reachable)
        }

        private fun broadcast(reachable: Boolean) {
            try {
                ListenerService.glassesConnected = reachable
                if (reachable) {
                    ListenerService.glassesConnecting = false
                }
                val intent = android.content.Intent(ListenerService.ACTION_GLASSES_STATE).apply {
                    setPackage(context.packageName)
                    putExtra(ListenerService.EXTRA_GLASSES_CONNECTED, reachable)
                    putExtra(ListenerService.EXTRA_GLASSES_CONNECTING, false)
                    val name = ListenerService.glassesDeviceName
                    val mac = ListenerService.glassesDeviceMac
                    if (name != null) putExtra(ListenerService.EXTRA_GLASSES_DEVICE_NAME, name)
                    if (mac != null) putExtra(ListenerService.EXTRA_GLASSES_MAC, mac)
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                log("Reachability: broadcast failed: ${e.message}")
            }
        }
    }

    private val reachability = GlassesReachability()

    /**
     * Send one BLE_PING and wait up to [timeoutMs] for the matching BLE_PONG.
     * Returns true on pong, false on timeout or failure to send. On timeout the
     * reachability state machine is notified.
     */
    suspend fun pingGlasses(timeoutMs: Long = 1500): Boolean {
        val id = nextRequestId()
        val deferred = CompletableDeferred<Boolean>()
        pendingPings[id] = deferred
        if (!bleWake.sendPing(id)) {
            pendingPings.remove(id)
            return false
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingPings.remove(id)
            reachability.onPingTimeout()
            false
        }
    }

    /**
     * Run [action] only if the glasses are reachable (verified via a fresh BLE ping).
     * Calls [onUnreachable] on the main thread otherwise. Acquires + releases the
     * bt-manager active session for the duration of [action] so RFCOMM keep-alive
     * stays armed during user-driven workflows.
     */
    fun runWithGlasses(
        timeoutMs: Long = 1500,
        sessionLabel: String = "ui_action",
        onUnreachable: () -> Unit,
        action: suspend () -> Unit,
    ): Job {
        return scope.launch {
            if (!pingGlasses(timeoutMs)) {
                withContext(Dispatchers.Main) { onUnreachable() }
                return@launch
            }
            setActiveSession(sessionLabel)
            try {
                action()
            } finally {
                clearActiveSession(sessionLabel)
            }
        }
    }

    /** Volatile flag tracking RFCOMM connection state. */
    @Volatile
    private var rfcommConnected: Boolean = false

    @Volatile
    private var autoConnectPending = false
    private var cachedReconnectFailures = 0
    private var rfcommReconnectFailures = 0
    private var pairModeActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { startScanning() }

    /** Callback for retry countdown ticks (seconds remaining). -1 = no retry scheduled. */
    var onRetryCountdown: ((Int) -> Unit)? = null
    @Volatile
    private var retryTargetTime = 0L
    private val countdownRunnable = object : Runnable {
        override fun run() {
            val remaining = ((retryTargetTime - System.currentTimeMillis()) / 1000).toInt()
                .coerceAtLeast(0)
            onRetryCountdown?.invoke(remaining)
            if (remaining > 0) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    interface GlassesListener {
        /** Status update from glasses (e.g. "listening", "idle"). */
        fun onGlassesStatus(state: String)
        /**
         * Authoritative state snapshot from glasses, sent on every (re)connect.
         * Phone reconciles its glassesAudioState to match. Robust to dropped
         * CH_STATUS messages while the relay was disconnected.
         */
        fun onGlassesStateSnapshot(state: String, requestId: String?) {}
        /** Command from glasses (e.g. button press). */
        fun onGlassesCommand(command: String, args: List<String>)
        /** Glasses connected via BT. */
        fun onGlassesConnected()
        /** Glasses disconnected from BT. */
        fun onGlassesDisconnected()
        /** Base64-encoded PCM audio data from glasses mic (local AudioRecord -> BT data channel). */
        fun onGlassesAudioData(b64Pcm: String)
    }

    var listener: GlassesListener? = null
    var logCallback: ((String) -> Unit)? = null
    var onHealthPong: (() -> Unit)? = null
    var onGlassesCommandResult: ((String, JSONObject) -> Unit)? = null
    var onBatteryChanged: ((level: Int, isCharging: Boolean) -> Unit)? = null
    /** Glasses recording-state snapshot push. Fires on every state change on the
     *  glasses side (battery cross, settings flip, wear toggle). All four params are
     *  the latest authoritative values from glasses. */
    var onRecordingStateChanged: ((batteryPct: Int, alwaysRecord: Boolean, onDemandActive: Boolean, recordingActive: Boolean, worn: Boolean) -> Unit)? = null
    var onChatListRequested: (() -> Unit)? = null
    var onSwitchChatRequested: ((String) -> Unit)? = null
    var onNewChatRequested: (() -> Unit)? = null
    var onMouseReport: ((ByteArray) -> Unit)? = null
    var onReidFace: ((trackingId: String, imageBase64: String) -> Unit)? = null
    var onReidPersonRequested: ((personUid: String) -> Unit)? = null
    var onTodoListRequested: (() -> Unit)? = null
    var onTodoToggle: ((id: String) -> Unit)? = null
    var onTodoAdd: ((text: String) -> Unit)? = null
    var onTodoRemove: ((id: String) -> Unit)? = null
    var onAlarmListRequested: (() -> Unit)? = null
    var onJobListRequested: (() -> Unit)? = null
    var onTelegramSavedRequested: (() -> Unit)? = null
    var onTelegramChatListRequested: ((limit: Int) -> Unit)? = null
    var onTelegramMessagesRequested: ((chatId: String, limit: Int, topicId: Int, offsetId: Int) -> Unit)? = null
    var onTelegramSendRequested: ((chatId: String, text: String, topicId: Int) -> Unit)? = null
    var onTelegramTopicsRequested: ((chatId: String) -> Unit)? = null
    var onTelegramSubscribeRequested: (() -> Unit)? = null
    var onTelegramUnsubscribeRequested: (() -> Unit)? = null
    var onTelegramChatOpened: ((chatId: String, chatTitle: String) -> Unit)? = null
    var onTelegramChatClosed: (() -> Unit)? = null
    var onTelegramVoiceStart: ((chatId: String) -> Unit)? = null
    var onTelegramVoiceStop: (() -> Unit)? = null
    var onNotifReplyStart: ((notifId: String) -> Unit)? = null
    var onNotifReplySend: ((notifId: String, text: String) -> Unit)? = null
    var onNotifReplyCancel: ((notifId: String) -> Unit)? = null
    var onGlassesInwardAudioData: ((String) -> Unit)? = null  // base64 PCM from glasses inward mic (two-way translation)
    var onSpeakerVerifyRequested: (() -> Unit)? = null
    var onSpeakerVerifyAudio: ((String) -> Unit)? = null  // base64 PCM from glasses mic 2
    private val speakerVerifyAudioBuffer = StringBuilder()
    var onNotificationDone: ((notifId: String) -> Unit)? = null
    var onAudioDuck: ((duck: Boolean) -> Unit)? = null
    var onWearState: ((worn: Boolean) -> Unit)? = null
    var onScreenState: ((on: Boolean) -> Unit)? = null
    /** (msgType, sessionId, payload) -> handled. Wired by ListenerService to GlassesSyncClient. */
    var onSyncMessage: ((String, String, List<String>) -> Unit)? = null
    /**
     * Glasses-side WakeWordPipeline fired. Payload: (confidence: Float, epochNanos: Long).
     * Wired by ListenerService to handleGlassesWakeEvent. The phone does NOT run a
     * WakeWordDetector against the glasses audio stream -- this callback is the sole
     * activation path for glasses wake-ups. Phone-mic wake-ups are independent and
     * handled via the phone's own WakeWordDetector.WakeWordListener callback.
     */
    var onGlassesCommand: ((type: String, params: JSONObject) -> Unit)? = null
    var onGlassesWakeEvent: ((confidence: Float, epochNanos: Long) -> Unit)? = null

    private val txByteCount = AtomicLong(0)
    private val rxByteCount = AtomicLong(0)

    fun getAndResetTxBytes(): Long = txByteCount.getAndSet(0)
    fun getAndResetRxBytes(): Long = rxByteCount.getAndSet(0)

    val bluetoothHelper: BluetoothHelper by lazy {
        BluetoothHelper(
            context = context,
            initStatus = { status ->
                log("BT init status: $status")
            },
            deviceFound = { device ->
                log("Device found: ${device.name ?: device.address}")
                handleDeviceFound(device)
            },
            onConnectionStateChanged = { state ->
                log("BT connection state: $state")
                when (state) {
                    BluetoothHelper.ConnectionState.CONNECTED -> {
                        // CXR-M connected (used for WiFi hotspot / apps). Messaging uses rfcommClient.
                        log("CXR-M connected (for WiFi hotspot/app mgmt)")
                        ensureRfcommClientStarted()
                    }
                    BluetoothHelper.ConnectionState.NOT_CONNECTED -> {
                        if (hotspotInfo != null) disableHotspot()
                        autoConnectPending = false
                        cachedReconnectFailures++
                        if (cachedReconnectFailures >= MAX_CACHED_FAILURES) {
                            log("Cached reconnect failed $MAX_CACHED_FAILURES times (keeping MAC+UUID, only cleared on re-pair)")
                            cachedReconnectFailures = 0
                        }
                        // Messaging lives on RFCOMM (rfcommClient), not on CXR-M GATT. If GATT
                        // drops but the RFCOMM socket is still alive, user-visible state is
                        // "connected" -- only fire onGlassesDisconnected when RFCOMM is also
                        // down. Otherwise the UI flashes "Disconnected" during normal GATT
                        // bounces while messaging keeps working.
                        if (!rfcommConnected) {
                            listener?.onGlassesDisconnected()
                        } else {
                            log("GATT NOT_CONNECTED but RFCOMM alive; UI stays connected")
                        }
                        scheduleRetry()
                    }
                    else -> {}
                }
            }
        ).also {
            it.logCallback = { msg -> logCallback?.invoke(msg) }
            it.onGlassesIdentified = { device, classicMac ->
                // BLE scan positively identified our glasses via REPO magic.
                // Service data contains the classic BT MAC -- use it directly
                // for RFCOMM without GATT discovery.
                if (!autoConnectPending && !rfcommConnected) {
                    val isBogus = classicMac == null ||
                        classicMac == "00:00:00:00:00:00" ||
                        classicMac == "02:00:00:00:00:00"
                    if (isBogus) {
                        log("REPO glasses identified but MAC is bogus ($classicMac) -- trying GATT")
                        autoConnectPending = true
                        pairModeActive = false
                        val serial = AppConfig.getGlassesSerial(context).ifEmpty { "auto" }
                        bluetoothHelper.initDevice(device, serial)
                    } else {
                        log("REPO glasses identified -- classic MAC=$classicMac, bonding first then RFCOMM")
                        autoConnectPending = true
                        pairModeActive = false
                        AppConfig.setGlassesMac(context, classicMac)
                        AppConfig.setGlassesSerial(context, "auto")
                        // Do NOT seed the CXR socket UUID here. The CXR-M control
                        // channel (CxrApi.connectBluetooth) terminates at the glasses
                        // CXRService, which listens on a per-session random serviceRecord
                        // UUID advertised over the Rokid GATT characteristic (9301).
                        // Seeding the message-relay UUID would make connectBluetooth dial
                        // the MessageRelay socket and corrupt its frame parser. The CXR
                        // socket UUID is discovered and cached only via GATT (initDevice).
                        // The message relay (GlassesRfcommClient) dials MESSAGE_UUID
                        // directly using the bonded MAC and does not need this cache.
                        // Bond first -- RFCOMM requires an active bond. The RFCOMM
                        // connect is deferred to the bond-complete callback or next
                        // retry cycle (5s) which will find cached credentials.
                        try { device.createBond() } catch (_: Exception) {}
                        ensureRfcommClientStarted()
                        // Schedule RFCOMM after a short delay to let bonding start
                        mainHandler.postDelayed({
                            if (!rfcommConnected) {
                                rfcommClient.requestImmediateReconnect("repo_ble_bonded")
                            }
                        }, 3000)
                        // Run GATT discovery to obtain the real CXR serviceRecord UUID
                        // for the CXR-M control session (settings, openApp, audio, WiFi P2P).
                        // This caches the correct dynamic UUID via onConnectionInfo.
                        val serial = AppConfig.getGlassesSerial(context).ifEmpty { "auto" }
                        bluetoothHelper.initDevice(device, serial)
                    }
                }
            }
        }
    }

    val isConnected: Boolean
        get() = rfcommConnected

    data class HotspotInfo(
        val ssid: String,
        val password: String,
        val ip: String,
        val securityType: Int
    )

    @Volatile
    var hotspotInfo: HotspotInfo? = null
        private set

    fun enableHotspot(onResult: (HotspotInfo?) -> Unit) {
        if (!isConnected) {
            log("enableHotspot: not connected")
            onResult(null)
            return
        }
        try {
            log("enableHotspot: calling initWifiHot...")
            val result = CxrApi.getInstance().initWifiHot(object : WifiHotStatusCallback {
                override fun onWifiHotAvailable(ssid: String?, password: String?, ip: String?, securityType: Int) {
                    log("Hotspot available: SSID=$ssid IP=$ip security=$securityType")
                    val info = if (ssid != null && password != null && ip != null) {
                        HotspotInfo(ssid, password, ip, securityType).also { hotspotInfo = it }
                    } else null
                    onResult(info)
                }
            })
            log("enableHotspot: initWifiHot returned $result")
            if (result != null && result.name != "REQUEST_SUCCEED") {
                log("enableHotspot: initWifiHot failed with $result")
                onResult(null)
            }
        } catch (e: Exception) {
            log("enableHotspot failed: ${e.message}")
            onResult(null)
        }
    }

    fun disableHotspot() {
        log("disableHotspot: calling deinitWifiHot...")
        try {
            CxrApi.getInstance().deinitWifiHot()
        } catch (_: Exception) {}
        hotspotInfo = null
    }

    fun startScanning() {
        ensureRfcommClientStarted()

        // Already connected -- the 5s retry timer must not demote the state machine by
        // kicking reconnectFromCache. Without this guard the UI oscillates CONNECTED ->
        // CONNECTING every 5s and shows "BT disconnected" at the trough.
        if (rfcommConnected) {
            log("RFCOMM already connected, skipping reconnect scan")
            scheduleRetry()
            return
        }
        if (bluetoothHelper.connectionState == BluetoothHelper.ConnectionState.CONNECTED) {
            // CXR-M reports connected but RFCOMM isn't up yet -- kick the RFCOMM
            // reconnect signal so the connect thread attempts immediately instead of
            // waiting for an external BLE wake or outbound send that may never come.
            rfcommReconnectFailures++
            if (rfcommReconnectFailures > MAX_CACHED_FAILURES) {
                // CXR-M state is stale -- glasses are likely unreachable. Reset so the
                // normal reconnect path with backoff takes over.
                log("CXR-M CONNECTED but RFCOMM failed ${rfcommReconnectFailures - 1} times -- resetting stale CXR-M state")
                rfcommReconnectFailures = 0
                bluetoothHelper.resetConnectionState()
            } else {
                log("CXR-M connected but RFCOMM not (attempt $rfcommReconnectFailures) -- requesting immediate RFCOMM reconnect")
                rfcommClient.requestImmediateReconnect("cxrm_connected_no_rfcomm")
            }
            scheduleRetry()
            return
        }

        log("Starting BT reconnect for glasses")

        // If rfcommClient is already receiving frames, PhoneBtHost's relay listener would
        // have force-promoted BluetoothHelper.connectionState to CONNECTED, which skips
        // this code path. So anything we see as CONNECTING here is either (a) a fresh
        // in-flight attempt (< stale threshold -- wait for it) or (b) a zombie where the
        // callback never completed AND no data flows (> stale threshold -- reset + retry).
        if (bluetoothHelper.connectionState == BluetoothHelper.ConnectionState.CONNECTING) {
            val stuckMs = System.currentTimeMillis() - bluetoothHelper.connectionStateSince
            if (stuckMs < STALE_CONNECTING_MS) {
                log("Already connecting (${stuckMs}ms), skipping -- will retry")
                scheduleRetry()
                return
            }
            log("Stuck in CONNECTING for ${stuckMs}ms with no incoming traffic -- resetting zombie")
            bluetoothHelper.resetConnectionState()
        }

        autoConnectPending = false

        val cachedMac = AppConfig.getGlassesMac(context)
        val cachedUuid = AppConfig.getGlassesSocketUuid(context)
        val serial = AppConfig.getGlassesSerial(context)

        // Detect bogus MAC (Android returns 02:00:00:00:00:00 without LOCAL_MAC_ADDRESS permission)
        val isBogus = cachedMac == "02:00:00:00:00:00" || cachedMac == "00:00:00:00:00:00"
        if (isBogus && cachedMac.isNotEmpty()) {
            log("Bogus cached MAC ($cachedMac) -- clearing and re-pairing")
            AppConfig.setGlassesMac(context, "")
            AppConfig.setGlassesSocketUuid(context, "")
        }

        if (!isBogus && cachedMac.isNotEmpty() && cachedUuid.isNotEmpty() && serial.isNotEmpty()) {
            log("Cached glasses info found (mac=$cachedMac) -- direct reconnect")
            autoConnectPending = true
            bluetoothHelper.reconnectFromCache(cachedMac, cachedUuid, serial)
            scheduleRetry()
            return
        }

        // No cache -- auto-start BLE discovery to (re)find the glasses.
        // Self-healing: no manual intervention needed after bond loss.
        // NON-destructive: never wipe cached credentials here. Credentials are
        // only cleared by an explicit user repair (the Pair Mode button); a
        // missing CXR socketUuid (e.g. GATT onConnectionInfo never landed) must
        // not nuke the still-valid bonded MAC, which would kick the phone out of
        // its own pairing with no user action.
        // Guard: don't re-enter if already scanning (prevents 5s loop).
        if (!pairModeActive) {
            log("No cached credentials -- auto-starting BLE discovery (non-destructive)")
            enterPairMode(clearCache = false)
        } else {
            log("No cached credentials -- pair mode already active, waiting for discovery")
            scheduleRetry()
        }
    }

    /**
     * Enter pair mode: close all connections, start BLE discovery.
     *
     * @param clearCache when true (the user-driven "Pair Mode" button), wipe the
     *   cached MAC + socketUuid so GATT discovery runs fully fresh against a new
     *   device. When false (automatic self-heal after a transient cache miss),
     *   keep the cached credentials -- discovery re-finds the SAME bonded glasses
     *   and re-populates any missing field without kicking the user out of an
     *   otherwise-valid pairing.
     */
    fun enterPairMode(clearCache: Boolean = true) {
        log("=== ENTERING PAIR MODE (clearCache=$clearCache) ===")
        pairModeActive = true
        mainHandler.removeCallbacks(reconnectRunnable)
        autoConnectPending = false
        cachedReconnectFailures = 0

        // Disconnect existing connection
        bluetoothHelper.resetConnectionState()

        if (clearCache) {
            // Explicit user repair: clear cached credentials so GATT discovery
            // runs fresh (and can bind to a different physical device).
            AppConfig.setGlassesMac(context, "")
            AppConfig.setGlassesSocketUuid(context, "")
            log("Cleared cached credentials, starting BLE discovery...")
        } else {
            log("Keeping cached credentials, starting BLE discovery...")
        }

        // Start full BLE scan + GATT discovery
        bluetoothHelper.onPermissionsGranted()
        scheduleRetry()
    }

    private fun scheduleRetry() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RETRY_INTERVAL_MS)
        // Start countdown ticker
        retryTargetTime = System.currentTimeMillis() + RETRY_INTERVAL_MS
        mainHandler.removeCallbacks(countdownRunnable)
        countdownRunnable.run()
        log("Next scan retry in ${RETRY_INTERVAL_MS}ms")
    }

    private fun cancelCountdown() {
        mainHandler.removeCallbacks(countdownRunnable)
        retryTargetTime = 0
        onRetryCountdown?.invoke(-1)
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceFound(device: BluetoothDevice) {
        val name = device.name ?: return
        if (!name.startsWith("Glasses_")) return
        if (bluetoothHelper.connectionState != BluetoothHelper.ConnectionState.NOT_CONNECTED) return
        if (autoConnectPending) return

        val serial = AppConfig.getGlassesSerial(context)
        if (serial.isEmpty()) {
            log("Glasses device '$name' found but serial is empty -- waiting for Config. Retrying in ${RETRY_INTERVAL_MS}ms.")
            return
        }

        autoConnectPending = true
        log("Auto-connecting to '$name' with serial '$serial'")
        bluetoothHelper.initDevice(device, serial)
    }

    private fun log(msg: String) {
        LogCollector.i(TAG, msg)
        logCallback?.invoke("[PhoneBtHost] $msg")
    }

    /**
     * Install RelayListener on GlassesRfcommClient to receive framed messages from glasses.
     */
    private var rfcommClientStarted = false

    /** Start direct-RFCOMM client. Idempotent. */
    private fun ensureRfcommClientStarted() {
        if (rfcommClientStarted) return
        rfcommClientStarted = true
        rfcommClient.listener = object : GlassesRfcommClient.Listener {
            override fun onConnected(deviceName: String) {
                log("RFCOMM connected to glasses: $deviceName")
                rfcommConnected = true
                mainHandler.removeCallbacks(reconnectRunnable)
                cancelCountdown()
                autoConnectPending = false
                cachedReconnectFailures = 0
                rfcommReconnectFailures = 0
                pairModeActive = false
                // The RFCOMM socket is up, so by definition we are CONNECTED. The GATT-
                // discovery path that normally promotes BluetoothHelper.connectionState can
                // leave us stuck in CONNECTING after an app force-stop or BT stack bounce
                // (its callbacks don't re-fire on an already-open socket). Promote here
                // directly so the UI + startScanning guard reflect reality.
                val cachedMac = AppConfig.getGlassesMac(context).takeIf { it.isNotEmpty() }
                bluetoothHelper.forceConnected(deviceName, cachedMac)
                installCustomCmdListener()
                listener?.onGlassesConnected()
                // Kick the classic-BT A2DP source -> sink connection so media
                // routes to the glasses without the user having to open
                // Android BT settings. Best-effort (ignored on stock builds
                // without BLUETOOTH_PRIVILEGED; we then rely on whatever the
                // system auto-connect policy decides).
                val classicMac = cachedMac ?: findBondedGlasses()?.address
                classicMac?.let { connectA2dpToGlasses(it) }
                    ?: log("A2DP connect skipped: no cached MAC and no bonded glasses")
                // Push contacts hash so glasses can refresh their HFP caller-ID cache
                // if their copy is stale or absent (request full list back if needed).
                scope.launch { try { sendContactsHash() } catch (_: Exception) {} }
                // Bring up the dedicated map socket alongside the control socket.
                // It connects to the same bonded glasses on MAP_UUID; if it is
                // already up this is a no-op.
                mapRfcommClient.requestImmediateReconnect("control_connected")
                // Reachability: RFCOMM up is hard proof of life.
                reachability.onRfcommConnected()
                // Start the 5-minute background reachability ticker if not already running.
                ensureReachabilityTickerStarted()
            }
            override fun onDisconnected() {
                log("RFCOMM disconnected from glasses")
                rfcommConnected = false
                // Connection drop with TTS in flight: bt-manager will eventually
                // safety-timeout the session, but we must reset our local guard now
                // so the next stream's compareAndSet re-arms setActiveSession.
                if (ttsSessionHeld.compareAndSet(true, false)) {
                    rfcommClient.clearActiveSession("tts_playback")
                }
                bluetoothHelper.resetConnectionState()
                listener?.onGlassesDisconnected()
                // Reachability debounces this -- one drop arms a confirm-ping rather
                // than flipping Unreachable immediately.
                reachability.onRfcommDisconnected()
                // Restart the retry timer so reconnection attempts resume.
                scheduleRetry()
            }
            override fun onMessage(channel: String, args: List<String>) {
                // Dispatch handled via RelayListener (setRelayListener)
            }
            override fun onLog(msg: String) {
                log("RFCOMM: $msg")
            }
        }
        rfcommClient.onSendWhileDisconnected = { channel ->
            // G3 outbound demand path: wake glasses via BLE so its bt-manager
            // can hold a session open by the time RFCOMM finishes reconnecting.
            try {
                bleWake.notify(BleWakeEvent.RFCOMM_REQUEST, System.nanoTime())
                log("BleWake: outbound demand on '$channel' -> notify RFCOMM_REQUEST")
            } catch (e: Exception) {
                log("BleWake: outbound notify failed: ${e.message}")
            }
        }
        rfcommClient.start()
        mapRfcommClient.onLog = { msg -> log("MapRFCOMM: $msg") }
        mapRfcommClient.start()
        ensureBleWakeStarted()
    }

    /**
     * G3: bring up the persistent BLE wake link to the bonded glasses device.
     * Inbound notifications trigger an immediate RFCOMM reconnect so we never
     * miss data because we were idling.
     */
    private fun ensureBleWakeStarted() {
        if (bleWakeStarted) return
        val dev = findBondedGlasses() ?: run {
            log("BleWake: no bonded glasses yet, will try on next ensure")
            return
        }
        bleWakeStarted = true
        bleWake.setOnNotifyCallback { code, data, _ ->
            if (code == BleWakeEvent.BATTERY_LEVEL) {
                val pct = data.toInt() and 0xFF
                log("BleWake: rx BATTERY_LEVEL pct=$pct")
                batteryLogger.record(pct, System.nanoTime())
                // Telemetry only -- do NOT trigger an RFCOMM reconnect for battery pings.
                return@setOnNotifyCallback
            }
            log("BleWake: rx code=0x${"%02X".format(code.toInt() and 0xFF)} -> requesting RFCOMM reconnect")
            rfcommClient.requestImmediateReconnect("ble:0x${"%02X".format(code.toInt() and 0xFF)}")
            mapRfcommClient.requestImmediateReconnect("ble:0x${"%02X".format(code.toInt() and 0xFF)}")
        }
        bleWake.setOnConnectionStateCallback { up ->
            log("BleWake: link state=${if (up) "UP" else "DOWN"}")
        }
        bleWake.setOnPongCallback { requestId, rttMillis ->
            log("BleWake: rx PONG id=$requestId rtt=${rttMillis}ms")
            pendingPings.remove(requestId)?.complete(true)
            reachability.onBlePong()
        }
        bleWake.setOnHelloCallback {
            log("BleWake: rx HELLO")
            reachability.onBleHello()
        }
        bleWake.start(dev)
    }

    /**
     * Background reachability ticker. Once RFCOMM has come up at least once we keep
     * pinging the glasses every 5 minutes regardless of RFCOMM state, so the
     * reachability state machine has fresh evidence even during long idle stretches.
     */
    private fun ensureReachabilityTickerStarted() {
        if (reachabilityTickerJob?.isActive == true) return
        reachabilityTickerJob = scope.launch {
            while (isActive) {
                delay(300_000) // 5 minutes
                pingGlasses(timeoutMs = 2_000)
            }
        }
    }

    private fun findBondedGlasses(): android.bluetooth.BluetoothDevice? {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return null
            val cachedMac = AppConfig.getGlassesMac(context)
            adapter.bondedDevices?.firstOrNull { d ->
                (cachedMac.isNotEmpty() && d.address.equals(cachedMac, ignoreCase = true)) ||
                (d.name ?: "").startsWith("Glasses_") ||
                (d.name ?: "").contains("glasses", ignoreCase = true)
            }
        } catch (_: Exception) { null }
    }

    private fun installCustomCmdListener() {
        try {
            rfcommClient.setRelayListener(object : RelayListener {
                override fun onCustomCmd(cmd: String?, args: RelayCaps?) {
                    if (cmd == null || args == null) return
                    // Any incoming RFCOMM frame is proof of a live connection. If the state
                    // machine hasn't caught up (e.g. the initial onConnected callback was
                    // missed during an app-restart race), force-promote now so reality
                    // matches the UI.
                    if (bluetoothHelper.connectionState != BluetoothHelper.ConnectionState.CONNECTED) {
                        bluetoothHelper.forceConnected(
                            bluetoothHelper.connectedDeviceName,
                            bluetoothHelper.connectedDeviceMac ?: AppConfig.getGlassesMac(context).takeIf { it.isNotEmpty() },
                        )
                    }
                    when (cmd) {
                        BtProtocol.CH_STATUS -> handleStatusFromGlasses(args)
                        BtProtocol.CH_STATE_SNAPSHOT -> handleStateSnapshotFromGlasses(args)
                        BtProtocol.CH_COMMAND -> handleCommandFromGlasses(args)
                        BtProtocol.CH_HEALTH_PONG -> {
                            log("Health pong received")
                            onHealthPong?.invoke()
                        }
                        BtProtocol.CH_MOUSE_REPORT -> {
                            val b64 = args.at(0).getString()
                            val report = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                            if (report.size == 6) onMouseReport?.invoke(report)
                        }
                        BtProtocol.CH_AUDIO_DATA -> handleAudioDataFromGlasses(args)
                        BtProtocol.CH_AUDIO_DATA_INWARD -> handleInwardAudioDataFromGlasses(args)
                        BtProtocol.CH_CHAT_LIST_REQUEST -> handleChatListRequest()
                        BtProtocol.CH_SWITCH_CHAT -> handleSwitchChat(args)
                        BtProtocol.CH_NEW_CHAT -> {
                            log("New chat requested by glasses")
                            onNewChatRequested?.invoke()
                        }
                        BtProtocol.CH_REID_FACE -> handleReidFaceFromGlasses(args)
                        BtProtocol.CH_REID_PERSON_REQ -> {
                            try {
                                val personUid = args.at(0).getString()
                                log("Reid person intel requested by glasses: $personUid")
                                onReidPersonRequested?.invoke(personUid)
                            } catch (e: Exception) {
                                log("Failed to parse reid person request: ${e.message}")
                            }
                        }
                        BtProtocol.CH_TODO_LIST_REQ -> {
                            log("Todo list requested by glasses")
                            onTodoListRequested?.invoke()
                        }
                        BtProtocol.CH_TODO_TOGGLE -> {
                            try {
                                val id = args.at(0).getString()
                                log("Todo toggle from glasses: $id")
                                onTodoToggle?.invoke(id)
                            } catch (e: Exception) {
                                log("Failed to parse todo toggle: ${e.message}")
                            }
                        }
                        BtProtocol.CH_TODO_ADD -> {
                            try {
                                val text = args.at(0).getString()
                                log("Todo add from glasses: ${text.take(40)}")
                                onTodoAdd?.invoke(text)
                            } catch (e: Exception) {
                                log("Failed to parse todo add: ${e.message}")
                            }
                        }
                        BtProtocol.CH_TODO_REMOVE -> {
                            try {
                                val id = args.at(0).getString()
                                log("Todo remove from glasses: $id")
                                onTodoRemove?.invoke(id)
                            } catch (e: Exception) {
                                log("Failed to parse todo remove: ${e.message}")
                            }
                        }
                        BtProtocol.CH_CONTACTS -> {
                            try {
                                val op = args.at(0).getString()
                                if (op == "REQUEST_FULL") {
                                    val agMac = if (args.size() > 1) args.at(1).getString() else getAgMac()
                                    log("Contacts: REQUEST_FULL agMac=$agMac")
                                    sendContactsList(agMac)
                                } else {
                                    log("Contacts: unknown op '$op'")
                                }
                            } catch (e: Exception) {
                                log("Contacts: parse failed: ${e.message}")
                            }
                        }
                        BtProtocol.CH_ALARM_LIST_REQ -> {
                            log("Alarm list requested by glasses")
                            onAlarmListRequested?.invoke()
                        }
                        BtProtocol.CH_JOB_LIST_REQ -> {
                            log("Job list requested by glasses")
                            onJobListRequested?.invoke()
                        }
                        BtProtocol.CH_TELEGRAM_SAVED_REQ -> {
                            log("Telegram saved requested by glasses")
                            onTelegramSavedRequested?.invoke()
                        }
                        BtProtocol.CH_TG_CHAT_LIST_REQ -> {
                            val limit = try { args.at(0).getString().toInt() } catch (_: Exception) { 20 }
                            log("Telegram chat list requested by glasses (limit=$limit)")
                            onTelegramChatListRequested?.invoke(limit)
                        }
                        BtProtocol.CH_TG_MESSAGES_REQ -> {
                            try {
                                val chatId = args.at(0).getString()
                                val limit = try { args.at(1).getString().toInt() } catch (_: Exception) { 20 }
                                val topicId = try { args.at(2).getString().toInt() } catch (_: Exception) { 0 }
                                val offsetId = try { args.at(3).getString().toInt() } catch (_: Exception) { 0 }
                                log("Telegram messages requested: chatId=$chatId limit=$limit topicId=$topicId offsetId=$offsetId")
                                onTelegramMessagesRequested?.invoke(chatId, limit, topicId, offsetId)
                            } catch (e: Exception) {
                                log("Failed to parse telegram messages request: ${e.message}")
                            }
                        }
                        BtProtocol.CH_TG_SEND_REQ -> {
                            try {
                                val chatId = args.at(0).getString()
                                val text = args.at(1).getString()
                                val topicId = try { args.at(2).getString().toInt() } catch (_: Exception) { 0 }
                                log("Telegram send requested: chatId=$chatId topicId=$topicId text=${text.take(40)}")
                                onTelegramSendRequested?.invoke(chatId, text, topicId)
                            } catch (e: Exception) {
                                log("Failed to parse telegram send request: ${e.message}")
                            }
                        }
                        BtProtocol.CH_TG_TOPICS_REQ -> {
                            try {
                                val chatId = args.at(0).getString()
                                log("Telegram topics requested: chatId=$chatId")
                                onTelegramTopicsRequested?.invoke(chatId)
                            } catch (e: Exception) {
                                log("Failed to parse telegram topics request: ${e.message}")
                            }
                        }
                        BtProtocol.CH_TG_SUBSCRIBE -> {
                            log("Telegram subscribe requested by glasses")
                            onTelegramSubscribeRequested?.invoke()
                        }
                        BtProtocol.CH_TG_UNSUBSCRIBE -> {
                            log("Telegram unsubscribe requested by glasses")
                            onTelegramUnsubscribeRequested?.invoke()
                        }
                        BtProtocol.CH_TG_OPEN_CHAT -> {
                            try {
                                val chatId = args.at(0).getString()
                                val chatTitle = args.at(1).getString()
                                log("Telegram chat opened: chatId=$chatId title=$chatTitle")
                                onTelegramChatOpened?.invoke(chatId, chatTitle)
                            } catch (e: Exception) {
                                log("Failed to parse telegram open chat: ${e.message}")
                            }
                        }
                        BtProtocol.CH_TG_CLOSE_CHAT -> {
                            log("Telegram chat closed by glasses")
                            onTelegramChatClosed?.invoke()
                        }
                        BtProtocol.CH_TG_VOICE_START -> {
                            try {
                                val chatId = args.at(0).getString()
                                log("Telegram voice start: chatId=$chatId")
                                onTelegramVoiceStart?.invoke(chatId)
                            } catch (e: Exception) {
                                log("Failed to parse telegram voice start: ${e.message}")
                            }
                        }
                        BtProtocol.CH_TG_VOICE_STOP -> {
                            log("Telegram voice stop")
                            onTelegramVoiceStop?.invoke()
                        }
                        BtProtocol.CH_NOTIF_REPLY_START -> {
                            try {
                                val notifId = args.at(0).getString()
                                log("Notif reply start: notifId=$notifId")
                                onNotifReplyStart?.invoke(notifId)
                            } catch (e: Exception) {
                                log("Failed to parse notif reply start: ${e.message}")
                            }
                        }
                        BtProtocol.CH_NOTIF_REPLY_SEND -> {
                            try {
                                val notifId = args.at(0).getString()
                                val text = args.at(1).getString()
                                log("Notif reply send: notifId=$notifId text=${text.take(40)}")
                                onNotifReplySend?.invoke(notifId, text)
                            } catch (e: Exception) {
                                log("Failed to parse notif reply send: ${e.message}")
                            }
                        }
                        BtProtocol.CH_NOTIF_REPLY_CANCEL -> {
                            try {
                                val notifId = args.at(0).getString()
                                log("Notif reply cancel: notifId=$notifId")
                                onNotifReplyCancel?.invoke(notifId)
                            } catch (e: Exception) {
                                log("Failed to parse notif reply cancel: ${e.message}")
                            }
                        }
                        BtProtocol.CH_SPEAKER_VERIFY_REQ -> {
                            try {
                                val data = args.at(0).getString()
                                if (data == "verify") {
                                    // Old path: use BT stream audio
                                    log("Speaker verify requested by glasses (BT stream)")
                                    onSpeakerVerifyRequested?.invoke()
                                } else {
                                    // New path: chunked base64 PCM from glasses mic 2
                                    val isFinal = try { args.at(1).getString() == "1" } catch (_: Exception) { true }
                                    speakerVerifyAudioBuffer.append(data)
                                    if (isFinal) {
                                        val fullBase64 = speakerVerifyAudioBuffer.toString()
                                        speakerVerifyAudioBuffer.clear()
                                        log("Speaker verify audio received: ${fullBase64.length} chars base64")
                                        onSpeakerVerifyAudio?.invoke(fullBase64)
                                    }
                                }
                            } catch (e: Exception) {
                                log("Speaker verify parse error: ${e.message}")
                            }
                        }
                        BtProtocol.CH_NOTIFICATION_DONE -> {
                            try {
                                val notifId = args.at(0).getString()
                                log("Notification done from glasses: $notifId")
                                onNotificationDone?.invoke(notifId)
                            } catch (e: Exception) {
                                log("Failed to parse notification done: ${e.message}")
                            }
                        }
                        BtProtocol.CH_AUDIO_DUCK -> {
                            try {
                                val duck = args.at(0).getString() == "1"
                                log("Audio duck from glasses: duck=$duck")
                                onAudioDuck?.invoke(duck)
                            } catch (e: Exception) {
                                log("Failed to parse audio duck: ${e.message}")
                            }
                        }
                        BtProtocol.CH_WEAR_STATE -> {
                            try {
                                val worn = args.at(0).getString() == "1"
                                log("Wear state from glasses: worn=$worn")
                                onWearState?.invoke(worn)
                            } catch (e: Exception) {
                                log("Failed to parse wear state: ${e.message}")
                            }
                        }
                        BtProtocol.CH_SCREEN_STATE -> {
                            try {
                                val on = args.at(0).getString() == "1"
                                log("Screen state from glasses: on=$on")
                                onScreenState?.invoke(on)
                            } catch (e: Exception) {
                                log("Failed to parse screen state: ${e.message}")
                            }
                        }
                        BtProtocol.CH_GLASSES_COMMAND -> {
                            try {
                                val raw = args.at(0).getString()
                                val arr = JSONArray(raw)
                                val type = arr.getString(0)
                                val params = if (arr.length() > 1) arr.getJSONObject(1) else JSONObject()
                                log("Glasses command: type=$type")
                                onGlassesCommand?.invoke(type, params)
                            } catch (e: Exception) {
                                log("Failed to parse glasses command: ${e.message}")
                            }
                        }
                        BtProtocol.CH_SYNC -> handleSyncFromGlasses(args)
                        BtProtocol.CH_WAKE_EVENT -> handleWakeEventFromGlasses(args)
                        "Dev" -> handleDevNotification(args)
                        else -> {
                            if (cmd.startsWith("listener_")) {
                                log("Unknown listener channel: $cmd")
                            }
                        }
                    }
                }
            })
            log("RelayListener installed")
        } catch (e: Exception) {
            log("Failed to install RelayListener: ${e.message}")
        }

        // Audio capture: glasses capture mic locally with AudioRecord and stream
        // Base64-encoded PCM over CH_AUDIO_DATA channel. Handled above in dispatch.

        log("AI blocking handled by glasses app (confused deputy attack)")
    }

    private var audioDataCount = 0L

    private fun handleAudioDataFromGlasses(args: RelayCaps) {
        try {
            val b64Pcm = args.at(0).getString()
            audioDataCount++
            rxByteCount.addAndGet(b64Pcm.length.toLong() + 8)
            if (audioDataCount <= 3 || audioDataCount % 200 == 0L) {
                log("Audio data #$audioDataCount: ${b64Pcm.length} chars")
            }
            listener?.onGlassesAudioData(b64Pcm)
        } catch (e: Exception) {
            log("Failed to parse audio data: ${e.message}")
        }
    }

    private var inwardAudioDataCount = 0L

    private fun handleInwardAudioDataFromGlasses(args: RelayCaps) {
        try {
            val b64Pcm = args.at(0).getString()
            inwardAudioDataCount++
            rxByteCount.addAndGet(b64Pcm.length.toLong() + 8)
            if (inwardAudioDataCount <= 3 || inwardAudioDataCount % 200 == 0L) {
                log("Inward audio data #$inwardAudioDataCount: ${b64Pcm.length} chars")
            }
            onGlassesInwardAudioData?.invoke(b64Pcm)
        } catch (e: Exception) {
            log("Failed to parse inward audio data: ${e.message}")
        }
    }

    private fun handleStatusFromGlasses(args: RelayCaps) {
        try {
            val state = args.at(0).getString()
            rxByteCount.addAndGet(state.length.toLong() + 8)
            log("Glasses status: $state")

            // recording_status:<json> -- parse and fire onRecordingStateChanged.
            // Glasses sends this on every reconcile via btClient.sendStatus(),
            // which arrives on CH_STATUS, not CH_DEV. Don't propagate further.
            if (state.startsWith("recording_status:")) {
                val json = state.substringAfter("recording_status:")
                try {
                    val obj = JSONObject(json)
                    val pct = obj.optInt("glasses_battery_pct", -1)
                    val always = obj.optBoolean("always_record_enabled", false)
                    val onDemand = obj.optBoolean("on_demand_recording_active", false)
                    val active = obj.optBoolean("recording_active", false)
                    val worn = obj.optBoolean("worn", true)
                    onRecordingStateChanged?.invoke(pct, always, onDemand, active, worn)
                } catch (t: Throwable) {
                    log("Failed to parse recording_status JSON: ${t.message}")
                }
                return
            }

            listener?.onGlassesStatus(state)
        } catch (e: Exception) {
            log("Failed to parse status: ${e.message}")
        }
    }

    private fun handleStateSnapshotFromGlasses(args: RelayCaps) {
        try {
            val state = args.at(0).getString()
            val requestId = if (args.size() > 1) args.at(1).getString().takeIf { it.isNotEmpty() } else null
            log("Glasses state snapshot: state=$state requestId=$requestId")
            listener?.onGlassesStateSnapshot(state, requestId)
        } catch (e: Exception) {
            log("Failed to parse state snapshot: ${e.message}")
        }
    }

    private fun handleSyncFromGlasses(args: RelayCaps) {
        try {
            val msgType = args.at(0).getString()
            val sessionId = args.at(1).getString()
            val n = args.size()
            val payload = if (n > 2) (2 until n).map { args.at(it).getString() } else emptyList()
            log("Sync frame: $msgType session=$sessionId payload=${payload.size}")
            onSyncMessage?.invoke(msgType, sessionId, payload)
        } catch (e: Exception) {
            log("Failed to parse sync frame: ${e.message}")
        }
    }

    private fun handleWakeEventFromGlasses(args: RelayCaps) {
        try {
            val rawConfidence = args.at(0).getString()
            val rawEpochNanos = args.at(1).getString()
            val confidence = rawConfidence.toFloatOrNull()
            val epochNanos = rawEpochNanos.toLongOrNull()
            if (confidence == null || epochNanos == null) {
                // A malformed frame coercing to (0f, 0L) would be treated as a legitimate
                // zero-confidence wake downstream. Reject loudly instead.
                log("WARN: malformed wake event from glasses: confidence='$rawConfidence' epochNanos='$rawEpochNanos'")
                return
            }
            // Range-check confidence. We allow slight floating-point slop beyond [0,1]
            // but reject anything clearly out of range (e.g. a serialization bug).
            if (confidence < -0.01f || confidence > 1.01f) {
                log("WARN: wake event confidence out of range [0,1]: $confidence (raw='$rawConfidence')")
                return
            }
            log("Glasses wake event: confidence=$confidence epochNanos=$epochNanos")
            onGlassesWakeEvent?.invoke(confidence, epochNanos)
        } catch (e: Exception) {
            log("Failed to parse wake event: ${e.message}")
        }
    }

    /**
     * Publish a sync message on listener_sync.
     * Wire format: [msgType, sessionId, payload...]. See GlassesSyncClient / SyncChannelHandler.
     */
    fun sendSync(msgType: String, sessionId: String, vararg payload: String): Boolean {
        val caps = RelayCaps()
        caps.write(msgType)
        caps.write(sessionId)
        for (p in payload) caps.write(p)
        return rfcommClient.send(BtProtocol.CH_SYNC, *caps.asArray())
    }

    /** Chunk reassembly buffers for glasses -> phone chunked commands */
    private val glassesChunkBuffers = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()

    private fun handleCommandFromGlasses(args: RelayCaps) {
        try {
            val command = args.at(0).getString()

            // Check if this is a command_result response from glasses
            if (command == "command_result") {
                val requestId = args.at(1).getString()
                val resultJson = args.at(2).getString()
                rxByteCount.addAndGet(command.length.toLong() + requestId.length.toLong() + resultJson.length.toLong() + 16)
                log("Glasses command result: requestId=$requestId")
                onGlassesCommandResult?.invoke(requestId, JSONObject(resultJson))
                return
            }

            // Check for chunked command: [command, chunk, isFinal("0"/"1")]
            val chunkData = try { args.at(1).getString() } catch (_: Exception) { null }
            val isFinalStr = try { args.at(2).getString() } catch (_: Exception) { null }
            if (chunkData != null && (isFinalStr == "0" || isFinalStr == "1")) {
                val buffer = glassesChunkBuffers.getOrPut(command) { StringBuilder() }
                buffer.append(chunkData)
                if (isFinalStr == "1") {
                    glassesChunkBuffers.remove(command)
                    val fullData = buffer.toString()
                    rxByteCount.addAndGet(fullData.length.toLong() + 16)
                    log("Glasses chunked command reassembled: $command (${fullData.length} chars)")
                    listener?.onGlassesCommand(command, listOf(fullData))
                }
                return
            }

            val argsList = mutableListOf<String>()
            for (i in 1..5) {
                try { argsList.add(args.at(i).getString()) } catch (_: Exception) { break }
            }
            rxByteCount.addAndGet(command.length.toLong() + argsList.sumOf { it.length.toLong() } + 16)
            log("Glasses command: $command args=$argsList")
            listener?.onGlassesCommand(command, argsList)
        } catch (e: Exception) {
            log("Failed to parse command: ${e.message}")
        }
    }

    private fun handleDevNotification(args: RelayCaps) {
        try {
            val event = args.at(0).getString()
            when (event) {
                "Dev_BatteryChanged" -> {
                    val json = JSONObject(args.at(1).getString())
                    val level = json.getInt("level")
                    val isCharging = json.getBoolean("isCharging")
                    log("Battery: $level% charging=$isCharging")
                    onBatteryChanged?.invoke(level, isCharging)
                }
                else -> log("Unknown Dev event: $event")
            }
        } catch (e: Exception) {
            log("Failed to parse Dev notification: ${e.message}")
        }
    }

    private fun handleChatListRequest() {
        log("Chat list requested by glasses")
        onChatListRequested?.invoke()
    }

    private fun handleSwitchChat(args: RelayCaps) {
        try {
            val conversationId = args.at(0).getString()
            log("Switch chat requested by glasses: $conversationId")
            onSwitchChatRequested?.invoke(conversationId)
        } catch (e: Exception) {
            log("Failed to parse switch chat: ${e.message}")
        }
    }

    private fun handleReidFaceFromGlasses(args: RelayCaps) {
        try {
            val trackingId = args.at(0).getString()
            val imageBase64 = args.at(1).getString()
            rxByteCount.addAndGet(trackingId.length.toLong() + imageBase64.length.toLong() + 8)
            log("Reid face received: trackingId=$trackingId imageLen=${imageBase64.length}")
            onReidFace?.invoke(trackingId, imageBase64)
        } catch (e: Exception) {
            log("Failed to parse reid face: ${e.message}")
        }
    }

    // --- Send to glasses ---

    private fun estimateCapsSize(vararg strings: String): Long {
        // RelayCaps overhead ~4 bytes per field + string bytes
        return strings.sumOf { it.toByteArray().size.toLong() + 4 }
    }

    fun sendResponse(requestId: String, text: String, tokenCount: Int = 0) {
        try {
            val caps = RelayCaps()
            caps.write(requestId)
            caps.write(text)
            caps.write("complete")
            caps.write(tokenCount.toString())
            rfcommClient.send(BtProtocol.CH_RESPONSE, *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize(requestId, text, "complete", tokenCount.toString()))
            log("Response sent ($requestId) tokens=$tokenCount: ${text.take(80)}...")
        } catch (e: Exception) {
            log("Failed to send response: ${e.message}")
        }
    }

    fun sendStreamingText(requestId: String, text: String) {
        try {
            rfcommClient.send(BtProtocol.CH_STREAMING_TEXT, requestId, text)
            txByteCount.addAndGet(estimateCapsSize(requestId, text))
        } catch (e: Exception) {
            log("Failed to send streaming text: ${e.message}")
        }
    }

    fun sendGlassesPartialText(text: String) {
        try {
            rfcommClient.send(BtProtocol.CH_GLASSES_PARTIAL_TEXT, text)
            txByteCount.addAndGet(estimateCapsSize(text))
        } catch (e: Exception) {
            log("Failed to send partial text: ${e.message}")
        }
    }

    fun sendGlassesUserText(requestId: String, text: String) {
        try {
            rfcommClient.send(BtProtocol.CH_GLASSES_USER_TEXT, requestId, text)
            txByteCount.addAndGet(estimateCapsSize(requestId, text))
            log("User text sent to glasses ($requestId): ${text.take(80)}")
        } catch (e: Exception) {
            log("Failed to send user text: ${e.message}")
        }
    }

    fun sendTtsAudio(requestId: String, audioBase64: String, sentenceIndex: Int, totalSentences: Int, text: String, isFinal: Boolean) {
        try {
            // Hold a tts_playback active session for the encoder/queue lifecycle. First
            // chunk sets, isFinal clears. Per-label safety timeout (60s) auto-clears if
            // we get a partial stream and no final frame.
            if (sentenceIndex == 0 || !ttsSessionHeld.get()) {
                if (ttsSessionHeld.compareAndSet(false, true)) {
                    rfcommClient.setActiveSession("tts_playback")
                }
            }
            val caps = RelayCaps()
            caps.write(requestId)
            caps.write(audioBase64)
            caps.write(sentenceIndex.toString())
            caps.write(totalSentences.toString())
            caps.write(text)
            caps.write(if (isFinal) "1" else "0")
            rfcommClient.send(BtProtocol.CH_TTS_AUDIO, *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize(requestId, audioBase64, sentenceIndex.toString(), totalSentences.toString(), text, if (isFinal) "1" else "0"))
            log("TTS audio sent ($requestId): sentence $sentenceIndex/$totalSentences")
            if (isFinal && ttsSessionHeld.compareAndSet(true, false)) {
                rfcommClient.clearActiveSession("tts_playback")
            }
        } catch (e: Exception) {
            log("Failed to send TTS audio: ${e.message}")
            // Abnormal stream end: defensively clear so the next stream's
            // compareAndSet(false, true) re-arms the bt-manager session.
            // clearActiveSession is safe to call even if the refcount is already 0
            // (GlassesRfcommClient drops the call when no entry exists).
            if (ttsSessionHeld.compareAndSet(true, false)) {
                rfcommClient.clearActiveSession("tts_playback")
            }
        }
    }

    fun sendTtsInterrupt(requestId: String) {
        try {
            rfcommClient.send(BtProtocol.CH_TTS_INTERRUPT, requestId)
            txByteCount.addAndGet(estimateCapsSize(requestId))
            log("TTS interrupt sent ($requestId)")
            if (ttsSessionHeld.compareAndSet(true, false)) {
                rfcommClient.clearActiveSession("tts_playback")
            }
        } catch (e: Exception) {
            log("Failed to send TTS interrupt: ${e.message}")
        }
    }

    // -- Active-session helpers exposed to ListenerService for transcription_pending --

    private val ttsSessionHeld = AtomicBoolean(false)

    fun setActiveSession(label: String) = rfcommClient.setActiveSession(label)
    fun clearActiveSession(label: String) = rfcommClient.clearActiveSession(label)

    /**
     * Same wake-and-reconnect pair we use on outbound demand, exposed for code
     * paths that aren't going through queueAndSend. Specifically the user's
     * "Sync now" button: we want the click to work while RFCOMM is down (e.g.
     * glasses off-head, audio profiles never came up, ACL closed). Sending a
     * BLE wake to the glasses + tickling the local rfcomm reconnect signal
     * brings RFCOMM up out of band; the queued forceSync() then runs as soon
     * as ListenerService.onGlassesConnected fires.
     */
    fun wakeAndReconnect(reason: String) {
        try {
            bleWake.notify(BleWakeEvent.RFCOMM_REQUEST, System.nanoTime())
            log("BleWake: $reason -> notify RFCOMM_REQUEST")
        } catch (e: Exception) {
            log("BleWake: $reason notify failed: ${e.message}")
        }
        rfcommClient.requestImmediateReconnect("wake:$reason")
        mapRfcommClient.requestImmediateReconnect("wake:$reason")
    }

    fun sendSettings(settingsJson: String) {
        try {
            rfcommClient.send(BtProtocol.CH_SETTINGS, settingsJson)
            txByteCount.addAndGet(estimateCapsSize(settingsJson))
            log("Settings sent (${settingsJson.length} chars)")
        } catch (e: Exception) {
            log("Failed to send settings: ${e.message}")
        }
    }

    fun sendToolStatus(requestId: String, toolName: String, status: String, toolArgs: JSONObject? = null, toolCallId: String = "") {
        try {
            val caps = RelayCaps()
            caps.write(requestId)
            caps.write(toolName)
            caps.write(status)
            caps.write(toolArgs?.toString() ?: "")
            caps.write(toolCallId)
            rfcommClient.send(BtProtocol.CH_TOOL_STATUS, *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize(requestId, toolName, status, toolArgs?.toString() ?: "", toolCallId))
        } catch (e: Exception) {
            log("Failed to send tool status: ${e.message}")
        }
    }

    fun sendDismissSession() {
        try {
            rfcommClient.send(BtProtocol.CH_DISMISS_SESSION)
            txByteCount.addAndGet(4)
            log("Dismiss session sent to glasses")
        } catch (e: Exception) {
            log("Failed to send dismiss session: ${e.message}")
        }
    }

    /**
     * Send a map frame as RAW WEBP bytes on the dedicated map socket. Droppable:
     * if the map link is down the frame is dropped (sendBinary returns false, no
     * queue) -- the next generated frame supersedes it anyway.
     */
    fun sendMapBitmapBytes(webp: ByteArray) {
        try {
            mapRfcommClient.sendBinary(BtProtocol.CH_MAP_BITMAP_BIN, webp)
            txByteCount.addAndGet(webp.size.toLong())
        } catch (e: Exception) {
            log("Failed to send map bitmap: ${e.message}")
        }
    }

    fun sendMapArrow(normX: Float, normY: Float, headingDeg: Float) {
        try {
            rfcommClient.send(
                BtProtocol.CH_MAP_ARROW,
                normX.toString(),
                normY.toString(),
                headingDeg.toString()
            )
            txByteCount.addAndGet(48L)
        } catch (e: Exception) {
            log("Failed to send map arrow: ${e.message}")
        }
    }

    /**
     * Stable AG identifier for the contacts cache key. BluetoothAdapter.getAddress
     * returns "02:00:00:00:00:00" for user apps on Android 9+, so use ANDROID_ID
     * which is per-app, per-user, stable across reboots.
     */
    private fun getAgMac(): String {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ).orEmpty()
        } catch (_: Exception) { "" }
    }

    /** Send only the contacts hash so glasses can decide whether to ask for the full list. */
    fun sendContactsHash() {
        try {
            val snap = com.repository.listener.phone.ContactsRepository.build(context)
            val agMac = getAgMac()
            rfcommClient.send(BtProtocol.CH_CONTACTS, "HASH", agMac, snap.hash)
            log("Contacts: hash sent (count=${snap.count} hash=${snap.hash.take(12)} agMac=$agMac)")
        } catch (e: Exception) {
            log("Contacts: sendContactsHash failed: ${e.message}")
        }
    }

    /** Stream the full contacts list to glasses, chunked. Triggered by REQUEST_FULL. */
    fun sendContactsList(agMacFromGlasses: String = "") {
        try {
            val snap = com.repository.listener.phone.ContactsRepository.build(context)
            val agMac = agMacFromGlasses.ifEmpty { getAgMac() }
            val json = snap.json
            val maxChunk = MAX_CAPS_CHARS
            if (json.length <= maxChunk) {
                val caps = RelayCaps()
                caps.write("LIST"); caps.write(agMac); caps.write(snap.hash)
                caps.write(json); caps.write("1")
                rfcommClient.send(BtProtocol.CH_CONTACTS, *caps.asArray())
            } else {
                var start = 0
                var idx = 0
                while (start < json.length) {
                    var end = minOf(start + maxChunk, json.length)
                    if (end < json.length && Character.isHighSurrogate(json[end - 1])) end--
                    val isFinal = end >= json.length
                    val caps = RelayCaps()
                    caps.write("LIST"); caps.write(agMac); caps.write(snap.hash)
                    caps.write(json.substring(start, end))
                    caps.write(if (isFinal) "1" else "0")
                    rfcommClient.send(BtProtocol.CH_CONTACTS, *caps.asArray())
                    if (!isFinal) Thread.sleep(50)
                    start = end
                    idx++
                }
                log("Contacts: list chunked ${json.length} chars -> $idx chunks")
            }
            log("Contacts: list sent (count=${snap.count}, ${json.length} chars)")
        } catch (e: Exception) {
            log("Contacts: sendContactsList failed: ${e.message}")
        }
    }

    /**
     * Push a weather widget frame. Empty [iconTag] hides the widget on glasses.
     * [tempC] is sent as plain integer string (no decimal, no unit).
     */
    fun sendWeather(iconTag: String, tempC: String, locationLabel: String) {
        try {
            rfcommClient.send(BtProtocol.CH_WEATHER, iconTag, tempC, locationLabel)
            txByteCount.addAndGet((iconTag.length + tempC.length + locationLabel.length + 12).toLong())
        } catch (e: Exception) {
            log("Failed to send weather: ${e.message}")
        }
    }

    /**
     * Send JSON to glasses via RelayCaps, chunking if payload exceeds CXR JNI limit.
     * Optional [prefix] is written as the first RelayCaps field in every chunk (e.g. conversationId).
     */
    private fun sendChunkedJson(channel: String, json: String, label: String, prefix: String? = null) {
        try {
            if (json.length <= MAX_CAPS_CHARS) {
                val caps = RelayCaps()
                prefix?.let { caps.write(it) }
                caps.write(json)
                caps.write("1")
                rfcommClient.send(channel, *caps.asArray())
            } else {
                var start = 0
                var chunkIndex = 0
                while (start < json.length) {
                    var end = minOf(start + MAX_CAPS_CHARS, json.length)
                    // Avoid splitting a surrogate pair
                    if (end < json.length && Character.isHighSurrogate(json[end - 1])) end--
                    val caps = RelayCaps()
                    prefix?.let { caps.write(it) }
                    caps.write(json.substring(start, end))
                    val isFinal = end >= json.length
                    caps.write(if (isFinal) "1" else "0")
                    rfcommClient.send(channel, *caps.asArray())
                    if (!isFinal) Thread.sleep(50)
                    start = end
                    chunkIndex++
                }
                log("$label chunking: ${json.length} chars -> $chunkIndex chunks")
            }
            txByteCount.addAndGet(estimateCapsSize(json))
            log("$label sent to glasses (${json.length} chars)")
        } catch (e: Exception) {
            log("Failed to send $label: ${e.message}")
        }
    }

    fun sendChatListResponse(chatsJson: String) =
        sendChunkedJson(BtProtocol.CH_CHAT_LIST_RESPONSE, chatsJson, "Chat list")

    fun sendChatHistory(conversationId: String, turnsJson: String) =
        sendChunkedJson(BtProtocol.CH_CHAT_HISTORY, turnsJson, "Chat history", prefix = conversationId)

    fun sendDeviceCommand(type: String, paramsJson: String) {
        try {
            val caps = RelayCaps()
            caps.write(type)
            caps.write("")  // requestId -- empty for fire-and-forget commands
            caps.write(paramsJson)
            rfcommClient.send(BtProtocol.CH_DEVICE_COMMAND, *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize(type, "", paramsJson))
            log("Device command sent: $type")
        } catch (e: Exception) {
            log("Failed to send device command: ${e.message}")
        }
    }

    // --- Lone mode (foreign-device proximity alarm) ---
    // The glasses own the dedup map + trusted set + overlay + alarm; these helpers drive its
    // lifecycle and feed the glasses the devices this phone scans. Glasses push the merged list
    // back via the "lone_devices_update" glasses-command handled in handleCommandFromGlasses.

    /** Activate lone mode on the glasses. trustedJson: {"trusted":[mac,...],"glasses_mac":mac}. */
    fun sendLoneStart(trustedJson: String) = sendDeviceCommand("lone_start", trustedJson)

    fun sendLoneStop() = sendDeviceCommand("lone_stop", "{}")

    /** Add/remove a MAC from the glasses trusted set. {"address":mac,"trusted":bool}. */
    fun sendLoneTrustUpdate(json: String) = sendDeviceCommand("lone_trust_update", json)

    /** Feed phone-scanned devices to the glasses. {"devices":[{address,name,rssi},...]}. */
    fun sendLoneDevices(devicesJson: String) = sendDeviceCommand("lone_devices", devicesJson)

    fun sendHealthPing() {
        try {
            rfcommClient.send(BtProtocol.CH_HEALTH_PING)
            log("Health ping sent")
        } catch (e: Exception) {
            log("Failed to send health ping: ${e.message}")
        }
    }

    fun sendActivateCommand() {
        try {
            rfcommClient.send(BtProtocol.CH_ACTIVATE)
            txByteCount.addAndGet(4)
            log("Activate command sent to glasses")
        } catch (e: Exception) {
            log("Failed to send activate command: ${e.message}")
        }
    }

    /**
     * Launch the glasses app via CXR-M openApp(). Centralizes the openApp logic.
     */
    fun ensureGlassesAppRunning(onSuccess: () -> Unit = {}, onFailure: () -> Unit = {}) {
        try {
            val appInfo = RKAppInfo(
                "com.repository.glasses.listener",
                "com.repository.glasses.listener.MainActivity"
            )
            CxrApi.getInstance().openApp(appInfo, object : ApkStatusCallback {
                    override fun onOpenAppSucceed() {
                        log("openApp succeeded")
                        onSuccess()
                    }
                    override fun onOpenAppFailed() {
                        log("openApp failed")
                        onFailure()
                    }
                    override fun onUploadApkSucceed() {}
                    override fun onUploadApkFailed() {}
                    override fun onInstallApkSucceed() {}
                    override fun onInstallApkFailed() {}
                    override fun onUninstallApkSucceed() {}
                    override fun onUninstallApkFailed() {}
                }
            )
        } catch (e: Exception) {
            log("ensureGlassesAppRunning failed: ${e.message}")
            onFailure()
        }
    }

    /**
     * Stop the glasses app via CXR-M stopApp(). Kills the process; service restarts via START_STICKY.
     */
    fun stopGlassesApp(onResult: (Boolean) -> Unit = {}) {
        try {
            CxrApi.getInstance().stopApp("com.repository.glasses.listener", object : ApkStatusCallback {
                override fun onOpenAppSucceed() {}
                override fun onOpenAppFailed() {}
                override fun onUploadApkSucceed() {}
                override fun onUploadApkFailed() {}
                override fun onInstallApkSucceed() {}
                override fun onInstallApkFailed() {}
                override fun onUninstallApkSucceed() {}
                override fun onUninstallApkFailed() {}
            })
            log("stopApp called")
            onResult(true)
        } catch (e: Exception) {
            log("stopGlassesApp failed: ${e.message}")
            onResult(false)
        }
    }

    /**
     * Activate glasses with openApp() safety net.
     * Fast path: if health monitor reports responsive, send activate directly.
     * Slow path: call openApp() first, then send activate in callback.
     * Timeout path: 3s safety timeout sends activate anyway.
     */
    fun activateWithEnsure(healthMonitor: GlassesHealthMonitor) {
        if (healthMonitor.isResponsive) {
            log("Glasses responsive - fast path activate")
            sendActivateCommand()
            return
        }

        log("Glasses not responsive - launching via openApp")
        val completed = AtomicBoolean(false)

        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                log("openApp timeout (3s) - sending activate anyway")
                sendActivateCommand()
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 3000)

        ensureGlassesAppRunning(
            onSuccess = {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    log("openApp succeeded - sending activate")
                    sendActivateCommand()
                }
            },
            onFailure = {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    log("openApp failed - sending activate anyway")
                    sendActivateCommand()
                }
            }
        )
    }

    fun requestGlassesAudioStream() {
        // Force-reset any stale stream first. After glasses app restart, the old
        // CXR-M stream stays "open" on phone side, delivering zeros. closeAudioRecord
        // tears it down so the subsequent openAudioRecord creates a fresh stream and
        // triggers a new onStartAudioStream callback.
        try {
            CxrApi.getInstance().closeAudioRecord("listener")
            log("closeAudioRecord (pre-reset): ok")
        } catch (_: Exception) {}

        try {
            // Codec 3 = Android platform audio (what Rokid uses for Android phones)
            val status = CxrApi.getInstance().openAudioRecord(3, 0, "listener")
            log("openAudioRecord(codec=3): $status")
        } catch (e: Exception) {
            log("openAudioRecord failed: ${e.message}")
        }
        // Also set speech scene via system-level command (Sys_ChangeAudioSceneId, not translation-only Trans_)
        try {
            val sceneStatus = CxrApi.getInstance().changeAudioSceneId(1, null)
            log("changeAudioSceneId(1): $sceneStatus")
        } catch (e: Exception) {
            log("changeAudioSceneId failed: ${e.message}")
        }
    }

    fun sendCommand(type: String, requestId: String, paramsJson: String) {
        try {
            rfcommClient.send(BtProtocol.CH_COMMAND_RESPONSE, type, requestId, paramsJson)
            txByteCount.addAndGet(estimateCapsSize(type, requestId, paramsJson))
            log("Command sent to glasses: $type ($requestId)")
        } catch (e: Exception) {
            log("Failed to send command to glasses: ${e.message}")
        }
    }

    fun sendCommandResponse(commandId: String, result: String) {
        try {
            rfcommClient.send(BtProtocol.CH_COMMAND_RESPONSE, commandId, result)
            txByteCount.addAndGet(estimateCapsSize(commandId, result))
            log("Command response sent ($commandId)")
        } catch (e: Exception) {
            log("Failed to send command response: ${e.message}")
        }
    }

    fun sendReidResult(trackingId: String, recognized: Boolean, personUid: String, displayName: String, score: Float) {
        try {
            val caps = RelayCaps()
            caps.write(trackingId)
            caps.write(recognized.toString())
            caps.write(personUid)
            caps.write(displayName)
            caps.write(score.toString())
            rfcommClient.send(BtProtocol.CH_REID_RESULT, *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize(trackingId, recognized.toString(), personUid, displayName, score.toString()))
            log("Reid result sent: trackingId=$trackingId recognized=$recognized uid=$personUid")
        } catch (e: Exception) {
            log("Failed to send reid result: ${e.message}")
        }
    }

    fun sendReidBestThumb(personUid: String, imageBase64: String) {
        try {
            rfcommClient.send(BtProtocol.CH_REID_BEST_THUMB, personUid, imageBase64)
            txByteCount.addAndGet(estimateCapsSize(personUid, imageBase64))
            log("Reid best thumb sent: uid=$personUid (${imageBase64.length} chars)")
        } catch (e: Exception) {
            log("Failed to send reid best thumb: ${e.message}")
        }
    }

    fun sendReidPersonResponse(personUid: String, personJson: String) {
        try {
            rfcommClient.send(BtProtocol.CH_REID_PERSON_RESP, personUid, personJson)
            txByteCount.addAndGet(estimateCapsSize(personUid, personJson))
            log("Reid person response sent: uid=$personUid (${personJson.length} chars)")
        } catch (e: Exception) {
            log("Failed to send reid person response: ${e.message}")
        }
    }

    fun sendReidMerge(sourcePersonId: String, targetPersonId: String, targetDisplayName: String) {
        try {
            rfcommClient.send(BtProtocol.CH_REID_MERGE, sourcePersonId, targetPersonId, targetDisplayName)
            txByteCount.addAndGet(estimateCapsSize(sourcePersonId, targetPersonId, targetDisplayName))
            log("Reid merge sent: source=$sourcePersonId target=$targetPersonId")
        } catch (e: Exception) {
            log("Failed to send reid merge: ${e.message}")
        }
    }

    fun sendSystemStatus(orchestratorConnected: Boolean) {
        try {
            val caps = RelayCaps()
            caps.write(if (orchestratorConnected) "1" else "0")
            rfcommClient.send(BtProtocol.CH_SYSTEM_STATUS, *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize(if (orchestratorConnected) "1" else "0"))
            log("System status sent: orchestrator=${if (orchestratorConnected) "connected" else "disconnected"}")
        } catch (e: Exception) {
            log("Failed to send system status: ${e.message}")
        }
    }

    fun sendTranslationResult(id: Int, text: String, finished: Boolean) {
        try {
            val resultJson = JSONObject().apply {
                put("id", id)
                put("subId", 0)
                put("temporary", !finished)
                put("finished", finished)
                put("result", text)
            }.toString()
            rfcommClient.send("Trans", "Trans_Result", resultJson)
            txByteCount.addAndGet(estimateCapsSize("Trans_Result", resultJson))
            log("Translation result sent (id=$id, finished=$finished): ${text.take(80)}")
        } catch (e: Exception) {
            log("Failed to send translation result: ${e.message}")
        }
    }

    fun sendTranslationConfig(fromLang: String, toLang: String) {
        try {
            val config = JSONObject().apply {
                put("fromLanguage", fromLang)
                put("toLanguage", toLang)
            }.toString()
            rfcommClient.send("Trans", "Trans_SetTvConfig", config)
            txByteCount.addAndGet(estimateCapsSize("Trans_SetTvConfig", config))
            log("Translation config sent: $fromLang -> $toLang")
        } catch (e: Exception) {
            log("Failed to send translation config: ${e.message}")
        }
    }

    /**
     * Send translation result to glasses app via custom BT channel.
     * New protocol: { id, text?, translation?, partial }
     * - text: source language text
     * - translation: translated text (from NLLB)
     * - partial: true for in-progress updates, false for finalized
     */
    fun sendTranslationResultToApp(id: Int, text: String?, translation: String?, partial: Boolean) {
        try {
            val resultJson = JSONObject().apply {
                put("id", id)
                if (text != null) put("text", text)
                if (translation != null) put("translation", translation)
                put("partial", partial)
            }.toString()
            rfcommClient.send(BtProtocol.CH_TRANSLATION_RESULT, resultJson)
            txByteCount.addAndGet(estimateCapsSize(resultJson))
            log("Translation result->app (id=$id, partial=$partial): text=${text?.take(40)} trans=${translation?.take(40)}")
        } catch (e: Exception) {
            log("Failed to send translation result to app: ${e.message}")
        }
    }

    fun sendTranslationConfigToApp(fromLang: String, toLang: String, fontSize: Int, twoWay: Boolean = false) {
        try {
            val configJson = JSONObject().apply {
                put("fromLanguage", fromLang)
                put("toLanguage", toLang)
                put("fontSize", fontSize)
                put("twoWay", twoWay)
            }.toString()
            rfcommClient.send(BtProtocol.CH_TRANSLATION_CONFIG, configJson)
            txByteCount.addAndGet(estimateCapsSize(configJson))
            log("Translation config->app: $fromLang -> $toLang fontSize=$fontSize")
        } catch (e: Exception) {
            log("Failed to send translation config to app: ${e.message}")
        }
    }

    fun sendTodoListResponse(json: String) =
        sendChunkedJson(BtProtocol.CH_TODO_LIST_RESP, json, "Todo list")

    fun sendAlarmListResponse(json: String) =
        sendChunkedJson(BtProtocol.CH_ALARM_LIST_RESP, json, "Alarm list")

    fun sendJobListResponse(json: String) =
        sendChunkedJson(BtProtocol.CH_JOB_LIST_RESP, json, "Job list")

    fun sendNotification(notifId: String, sender: String, text: String, chat: String, repliable: Boolean) {
        try {
            val repliableArg = if (repliable) "1" else "0"
            rfcommClient.send(BtProtocol.CH_NOTIFICATION, notifId, sender, text, chat, repliableArg)
            txByteCount.addAndGet(estimateCapsSize(notifId, sender, text, chat, repliableArg))
            log("Notification sent: $notifId $sender ${text.take(40)} repliable=$repliable")
        } catch (e: Exception) {
            log("Failed to send notification: ${e.message}")
        }
    }

    /**
     * Best-effort: push a classic-BT A2DP source->sink connect to the given
     * glasses MAC so media audio routes to the glasses without the user
     * needing to visit Android BT settings. Uses `BluetoothA2dp.connect(...)`
     * via reflection (hidden API on stock Android; works on AOSP-derived
     * builds that keep the method accessible). If the reflection fails we
     * fall back to setting `setConnectionPolicy(ALLOWED)` which most OEM
     * stacks honor as an auto-connect hint.
     */
    @SuppressLint("MissingPermission")
    private fun connectA2dpToGlasses(mac: String) {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: run {
            log("A2DP connect skipped: no BluetoothAdapter")
            return
        }
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) {
            log("A2DP connect skipped: bad MAC $mac: ${e.message}")
            return
        }
        val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile?) {
                if (profile != android.bluetooth.BluetoothProfile.A2DP || proxy == null) return
                try {
                    // 1) Policy hint (works on AOSP >= 12, public-ish API).
                    val policyOk = runCatching {
                        val m = proxy.javaClass.getMethod("setConnectionPolicy",
                            android.bluetooth.BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                        m.invoke(proxy, device, 100) // CONNECTION_POLICY_ALLOWED = 100
                    }.isSuccess
                    // 2) Explicit connect via reflection.
                    val connectOk = runCatching {
                        val m = proxy.javaClass.getMethod("connect",
                            android.bluetooth.BluetoothDevice::class.java)
                        m.invoke(proxy, device)
                    }.isSuccess
                    log("A2DP connect reflection: policyOk=$policyOk connectOk=$connectOk for $mac")
                } catch (t: Throwable) {
                    log("A2DP connect threw: ${t.message}")
                } finally {
                    runCatching { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, proxy) }
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }
        try {
            adapter.getProfileProxy(context, listener, android.bluetooth.BluetoothProfile.A2DP)
        } catch (t: Throwable) {
            log("A2DP getProfileProxy failed: ${t.message}")
        }
    }

    /** Push current phone wall-clock + tz id to glasses. Bug 8. */
    fun sendTimeSync(): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val tz = java.util.TimeZone.getDefault().id
            rfcommClient.send(BtProtocol.CH_TIME_SYNC, now.toString(), tz)
            txByteCount.addAndGet(estimateCapsSize("$now $tz"))
            log("TimeSync sent: epochMs=$now tz=$tz")
            true
        } catch (e: Exception) {
            log("Failed to send time-sync: ${e.message}")
            false
        }
    }

    fun sendNotificationSetText(notifId: String, fullText: String) {
        try {
            // Same length-prefixed caps-string arg framing as sendNotification's `text`
            // arg, so the full sorted body (newlines + Cyrillic) round-trips intact.
            rfcommClient.send(BtProtocol.CH_NOTIFICATION_SETTEXT, notifId, fullText)
            txByteCount.addAndGet(estimateCapsSize(notifId, fullText))
            log("[PhoneBtHost] Notification set-text sent: $notifId (${fullText.length} chars, ${fullText.count { it == '\n' } + 1} lines)")
        } catch (e: Exception) {
            log("Failed to send notification set-text: ${e.message}")
        }
    }

    fun sendNotifReplyResult(notifId: String, ok: Boolean) {
        try {
            rfcommClient.send(BtProtocol.CH_NOTIF_REPLY_RESULT, notifId, if (ok) "1" else "0")
            txByteCount.addAndGet(estimateCapsSize(notifId, if (ok) "1" else "0"))
            log("[PhoneBtHost] Notif reply result sent: $notifId ok=$ok")
        } catch (e: Exception) {
            log("Failed to send notif reply result: ${e.message}")
        }
    }

    fun sendNotificationTtsAudio(notifId: String, audioBase64: String, isFinal: Boolean) {
        // BT can't handle large single messages; chunk base64 if needed
        // Glasses reassemble chunks before playing
        val maxChunkSize = 40_000
        try {
            if (audioBase64.length <= maxChunkSize) {
                val caps = RelayCaps()
                caps.write(notifId)
                caps.write(audioBase64)
                caps.write(if (isFinal) "1" else "0")
                rfcommClient.send(BtProtocol.CH_NOTIFICATION_TTS, *caps.asArray())
                log("Notification TTS sent: $notifId (${audioBase64.length} b64 chars, final=$isFinal)")
            } else {
                val totalChunks = (audioBase64.length + maxChunkSize - 1) / maxChunkSize
                log("Notification TTS chunking: $notifId ${audioBase64.length} chars -> $totalChunks chunks")
                for (i in 0 until totalChunks) {
                    val start = i * maxChunkSize
                    val end = minOf(start + maxChunkSize, audioBase64.length)
                    val chunk = audioBase64.substring(start, end)
                    val isLast = (i == totalChunks - 1) && isFinal
                    val caps = RelayCaps()
                    caps.write(notifId)
                    caps.write(chunk)
                    caps.write(if (isLast) "1" else "0")
                    rfcommClient.send(BtProtocol.CH_NOTIFICATION_TTS, *caps.asArray())
                    Thread.sleep(50)
                }
                log("Notification TTS sent: $notifId ($totalChunks chunks, final=$isFinal)")
            }
        } catch (e: Exception) {
            log("Failed to send notification TTS: ${e.message}")
        }
    }

    fun sendTelegramSavedResponse(json: String) =
        sendChunkedJson(BtProtocol.CH_TELEGRAM_SAVED_RESP, json, "Telegram saved")

    fun sendTelegramChatListResponse(json: String) =
        sendChunkedJson(BtProtocol.CH_TG_CHAT_LIST_RESP, json, "Telegram chat list")

    fun sendTelegramMessagesResponse(chatId: String, json: String) =
        sendChunkedJson(BtProtocol.CH_TG_MESSAGES_RESP, json, "Telegram messages", prefix = chatId)

    fun sendTelegramTopicsResponse(chatId: String, json: String) =
        sendChunkedJson(BtProtocol.CH_TG_TOPICS_RESP, json, "Telegram topics", prefix = chatId)

    fun sendTelegramSendResponse(json: String) {
        try {
            rfcommClient.send(BtProtocol.CH_TG_SEND_RESP, json)
            txByteCount.addAndGet(estimateCapsSize(json))
            log("Telegram send response sent (${json.length} chars)")
        } catch (e: Exception) {
            log("Failed to send telegram send response: ${e.message}")
        }
    }

    fun sendTelegramNewMessage(json: String) =
        sendChunkedJson(BtProtocol.CH_TG_NEW_MESSAGE, json, "Telegram new message")

    fun sendSpeakerVerifyResult(verified: Boolean, similarity: Float) {
        try {
            val simStr = String.format(java.util.Locale.US, "%.4f", similarity)
            val caps = RelayCaps()
            caps.write(if (verified) "1" else "0")
            caps.write(simStr)
            rfcommClient.send(BtProtocol.CH_SPEAKER_VERIFY_RESP, *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize(if (verified) "1" else "0", simStr))
            log("Speaker verify result sent: verified=$verified similarity=$simStr")
        } catch (e: Exception) {
            log("Failed to send speaker verify result: ${e.message}")
        }
    }

    /**
     * Push key-value settings to glasses via RKSettingsManager pipeline.
     * Same mechanism as GlassesSettingsActivity uses for individual setting changes.
     */
    fun sendSettingsUpdate(vararg pairs: Pair<String, String>) {
        try {
            val caps = RelayCaps()
            caps.write("Settings_Update")
            val json = JSONArray().apply {
                pairs.forEach { (key, value) ->
                    put(JSONObject().apply {
                        put("key", key)
                        put("value", value)
                    })
                }
            }.toString()
            caps.write(json)
            rfcommClient.send("Settings", *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize("Settings_Update", json))
            log("Settings update sent: ${pairs.map { "${it.first}=${it.second}" }.joinToString(", ")}")
        } catch (e: Exception) {
            log("Failed to send settings update: ${e.message}")
        }
    }

    /**
     * Sync all saved display/system settings to glasses.
     * Called on glasses connection to restore phone-authoritative settings
     * that glasses lose on reboot/reconnect (they revert to Rokid OS defaults).
     * Brightness and volume are NOT pushed -- glasses are authoritative for those.
     */
    /**
     * Public entry point for callers that want to push the full current settings
     * snapshot to glasses via the CH_SETTINGS JSON channel. Delegates to
     * [syncAllSettings] using the host application context so brightness,
     * screen-timeout, and power-timeout travel over the agreed contract.
     */
    fun pushGlassesSettings() {
        syncAllSettings(context.applicationContext)
    }

    fun syncAllSettings(context: Context) {
        log("Syncing all saved settings to glasses")

        // RKSettingsManager settings -- batched via sendSettingsUpdate.
        // Brightness / screen-timeout / power-timeout are NOT sent here anymore;
        // they ride on the CH_SETTINGS JSON payload below (agreed contract with
        // the glasses-side worker).
        val notificationDuration = AppConfig.getGlassesNotificationDuration(context).ifEmpty { "5" }
        val soundEffect = AppConfig.getGlassesSoundEffect(context)
        val notificationSound = AppConfig.getGlassesNotificationSound(context)
        val ttsSpeed = AppConfig.getGlassesTtsSpeed(context)

        val settingsPairs = mutableListOf<Pair<String, String>>()
        settingsPairs.add("settings_msg_notification_display_duration" to notificationDuration)
        if (soundEffect.isNotEmpty()) settingsPairs.add("settings_sound_effect" to soundEffect)
        if (notificationSound.isNotEmpty()) settingsPairs.add("settings_msg_notification_sound_enabled" to notificationSound)
        if (ttsSpeed.isNotEmpty()) settingsPairs.add("settings_local_tts_speed" to ttsSpeed)

        if (settingsPairs.isNotEmpty()) {
            sendSettingsUpdate(*settingsPairs.toTypedArray())
        }

        // App settings -- sent via CH_SETTINGS to GlassesConfig.
        // Brightness / screen-timeout / power-timeout use the agreed Int keys.
        val normalizedY = AppConfig.getDisplayPositionY(context)
        val bottomMargin = ((1.0f - normalizedY) * 200).toInt()
        val appSettings = JSONObject().apply {
            put("settings_msg_notification_display_duration", notificationDuration)
            if (notificationSound.isNotEmpty()) put("settings_msg_notification_sound_enabled", notificationSound)
            put("settings_screen_ui_bottom_margin", bottomMargin.toString())
            put("settings_brightness", AppConfig.getGlassesBrightnessInt(context))
            put("settings_screen_timeout_s", AppConfig.getGlassesScreenTimeoutSec(context))
            put("settings_power_timeout_min", AppConfig.getGlassesPowerTimeoutMin(context))
            val chatFontSize = AppConfig.getGlassesChatFontSize(context)
            if (chatFontSize.isNotEmpty()) put("settings_chat_font_size", chatFontSize)
        }
        sendSettings(appSettings.toString())

        // Direct CxrApi settings (not routed through RKSettingsManager)
        val cxr = CxrApi.getInstance()

        // Rokid's built-in offline wakeword/assistant (settings_voice_control)
        // must NEVER run -- speech detection is exclusively our pipeline. It
        // defaults ON at boot, so force it OFF on every sync. "0" (any non-"open"
        // value) disables it; never send "open". Idempotent.
        try {
            cxr.setVoiceCtrl("0")
            log("Forced Rokid voice control OFF (setVoiceCtrl=0)")
        } catch (e: Exception) {
            log("Failed to force setVoiceCtrl(0): ${e.message}")
        }
    }

    fun release() {
        if (hotspotInfo != null) disableHotspot()
        mainHandler.removeCallbacks(reconnectRunnable)
        cancelCountdown()
        reachabilityTickerJob?.cancel()
        reachabilityTickerJob = null
        scope.cancel()
        // Tear down both RFCOMM links so neither connect thread is leaked.
        rfcommClient.stop()
        mapRfcommClient.stop()
        bluetoothHelper.release()
    }
}
