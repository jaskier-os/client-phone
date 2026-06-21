package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.repository.listener.config.AppConfig
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
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
 * Messaging uses GlassesRfcommClient (direct RFCOMM, framed). The glasses listener is
 * activated warm via CH_ACTIVATE and cold-started via the 0x07 BLE wake event.
 */
class PhoneBtHost(private val context: Context) {

    companion object {
        private const val TAG = "PhoneBtHost"
        private const val RETRY_INTERVAL_MS = 5000L
        /** Bonded-but-RFCOMM-down cycles before we presume the listener is dead and cold-start it. */
        private const val COLD_START_AFTER_FAILURES = 2
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

    /**
     * Bond-state receiver. onGlassesIdentified calls createBond() (async) then kicks the reconnect
     * immediately, so the first RFCOMM dial races bonding and fails -- recovery would otherwise wait
     * up to 5s for the retry timer. This receiver fires an immediate reconnect the moment the bond
     * completes for our pending pair target (or any bonded glasses), so pairing connects promptly.
     */
    @Volatile private var bondReceiverRegistered = false
    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val newState = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR
            )
            if (newState != BluetoothDevice.BOND_BONDED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                ?: return
            val pinned = pendingPairAddress
            val isPendingTarget = pinned != null && device.address.equals(pinned, ignoreCase = true)
            val isBondedGlasses = device.address.equals(
                findBondedGlasses()?.address, ignoreCase = true
            )
            if (!isPendingTarget && !isBondedGlasses) return
            log("Bond completed for ${device.address} -- kicking immediate RFCOMM reconnect")
            rfcommClient.requestImmediateReconnect("bond_complete")
            mapRfcommClient.requestImmediateReconnect("bond_complete")
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun ensureBondReceiverRegistered() {
        if (bondReceiverRegistered) return
        bondReceiverRegistered = true
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondStateReceiver, filter)
        }
    }

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

    /**
     * In-flight device-command deferreds keyed by requestId. Resolved when the glasses
     * reply over CH_COMMAND with ["command_result", requestId, resultJson] (handled in
     * [handleCommandFromGlasses]). Mirrors [pendingPings].
     */
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val deviceCommandIdSeq = AtomicLong(0)

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

        val currentState: State get() = state

        /** True iff a confirm-ping is currently outstanding (armed by a timeout or RFCOMM drop). */
        @Volatile private var confirmInFlight: Boolean = false

        /**
         * Monotonic generation, bumped whenever the confirm state is cleared (any proof of
         * life: pong/hello/rfcomm-connect/inbound-frame) OR a new confirm-ping is armed. A
         * confirm-ping coroutine captures the generation at arm time; when its ping times out it
         * is only authoritative if the generation is unchanged. This prevents a stale confirm
         * timeout from spuriously flipping Unreachable (or re-arming) after a newer proof of life
         * already promoted us back to Reachable.
         */
        private var confirmGeneration: Long = 0

        /** Clear any in-flight confirm and invalidate its pending timeout. */
        private fun clearConfirm() {
            confirmInFlight = false
            confirmGeneration++
        }

        @Synchronized
        fun onBlePong() {
            clearConfirm()
            transition(State.Reachable, "ble_pong")
        }

        @Synchronized
        fun onBleHello() {
            clearConfirm()
            transition(State.Reachable, "ble_hello")
        }

        @Synchronized
        fun onRfcommConnected() {
            clearConfirm()
            transition(State.Reachable, "rfcomm_connected")
        }

        /**
         * Any inbound RFCOMM frame from the glasses is hard proof of life. Use this to
         * recover from a stale Unreachable that can occur when a ping timed out (e.g. during
         * a glasses reboot) but the RFCOMM socket never observed a drop -- so onRfcommConnected
         * never re-fired.
         */
        @Synchronized
        fun onProofOfLife() {
            clearConfirm()
            transition(State.Reachable, "rfcomm_inbound")
        }

        @Synchronized
        fun onRfcommDisconnected() {
            // Do NOT flip immediately. RFCOMM bounces during normal idle. Arm a
            // confirm-ping; if it also fails we conclude Unreachable.
            armConfirmPing("rfcomm_disconnected")
        }

        /**
         * The BLE wake link (the steady-state idle channel) went down. That is a strong
         * signal the glasses BT stack is gone (reboot, range loss, power off), but BLE
         * also bounces transiently, so debounce via a confirm-ping rather than flipping
         * Unreachable immediately. If the confirm-ping fails, [onConfirmTimeout] flips us
         * Unreachable. This is what makes "any BT stack down => debounced Disconnected".
         */
        @Synchronized
        fun onBleLinkDown() {
            armConfirmPing("ble_link_down")
        }

        /**
         * A bare ping (BLE-up probe or the periodic safety ping) timed out while we believed we
         * were Reachable and no confirm is outstanding -- arm a confirm-ping to debounce.
         */
        @Synchronized
        fun onPingTimeout() {
            if (state == State.Reachable && !confirmInFlight) {
                armConfirmPing("ping_timeout")
            }
            // If a confirm is already in flight, its own [onConfirmTimeout] decides -- don't
            // double-act here.
        }

        /**
         * Result of a confirm-ping that was armed at [generation]. Only authoritative if no
         * proof of life or newer confirm has bumped the generation since. [pong] is false on
         * timeout. On an authoritative timeout we flip Unreachable.
         */
        @Synchronized
        fun onConfirmTimeout(generation: Long) {
            if (generation != confirmGeneration || !confirmInFlight) {
                // A newer proof-of-life / confirm superseded this one -- ignore the stale timeout.
                return
            }
            confirmInFlight = false
            transition(State.Unreachable, "confirm_ping_failed")
        }

        private fun armConfirmPing(reason: String) {
            if (confirmInFlight) return
            confirmInFlight = true
            confirmGeneration++
            val generation = confirmGeneration
            log("Reachability: arming confirm-ping ($reason, gen=$generation)")
            scope.launch {
                // Brief settle delay before the confirm-ping so we don't race a fresh
                // RFCOMM reconnect.
                delay(500)
                // A successful pong resolves via onBlePong (clears confirm). If it times out,
                // pingGlasses returns false and we adjudicate against the captured generation
                // (do NOT let pingGlasses re-enter onPingTimeout -- this IS the confirm).
                val pong = pingGlasses(timeoutMs = 1500, notifyReachabilityOnTimeout = false)
                if (!pong) onConfirmTimeout(generation)
            }
        }

        private fun transition(next: State, reason: String) {
            val prev = state
            if (prev == next) return
            state = next
            log("Reachability: $prev -> $next ($reason)")
            broadcast(next == State.Reachable)
        }

        /**
         * Re-emit the current state even if it didn't change. Needed when the phone re-pairs to a
         * DIFFERENT unit while already Reachable: the bond/RFCOMM switches but the reachability
         * state stays Reachable, so transition() would emit nothing and the UI's "Pairing..."
         * button would never reset. Forcing a broadcast pushes a fresh connected event + the new
         * device name so the UI updates.
         */
        fun forceBroadcast() {
            log("Reachability: forced re-broadcast (state=$state)")
            broadcast(state == State.Reachable)
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
     * Single source of truth for glasses connectivity (mirrors the SOLE writer of
     * [ListenerService.glassesConnected]). True iff the reachability machine is in
     * Reachable.
     */
    val isReachable: Boolean
        get() = reachability.currentState == State.Reachable

    /**
     * Connection phase for the HTTP status endpoint / UI:
     * - "connected"     -- reachability machine in Reachable
     * - "connecting"    -- RFCOMM socket up but not yet promoted, or an attempt in flight
     * - "not_connected" -- otherwise
     */
    fun glassesConnectionPhase(): String = when {
        reachability.currentState == State.Reachable -> "connected"
        rfcommConnected || ListenerService.glassesConnecting -> "connecting"
        else -> "not_connected"
    }

    /**
     * Send one BLE_PING and wait up to [timeoutMs] for the matching BLE_PONG.
     * Returns true on pong, false on timeout or failure to send. On timeout the
     * reachability state machine is notified.
     */
    /**
     * Send a BLE ping and await a pong. [notifyReachabilityOnTimeout] is true for a bare
     * liveness probe (BLE-up / safety ping) which should debounce via reachability.onPingTimeout;
     * false for the reachability machine's own confirm-ping, which adjudicates the timeout itself
     * (via the generation guard) and must NOT re-enter onPingTimeout.
     */
    suspend fun pingGlasses(timeoutMs: Long = 1500, notifyReachabilityOnTimeout: Boolean = true): Boolean {
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
            if (notifyReachabilityOnTimeout) reachability.onPingTimeout()
            false
        }
    }

    /**
     * Run [action] only if the glasses are reachable. If the RFCOMM data link is already up
     * that is hard proof of life -- proceed without a BLE ping (the BLE ping is only a wake
     * mechanism for when RFCOMM is idle/down, and it fails outright when GATT discovery hasn't
     * cached the RX characteristic, even though messaging works fine over RFCOMM). Otherwise
     * fall back to a fresh BLE ping. Calls [onUnreachable] on the main thread if neither holds.
     * Acquires + releases the bt-manager active session for the duration of [action] so RFCOMM
     * keep-alive stays armed during user-driven workflows.
     */
    fun runWithGlasses(
        timeoutMs: Long = 1500,
        sessionLabel: String = "ui_action",
        onUnreachable: () -> Unit,
        action: suspend () -> Unit,
    ): Job {
        return scope.launch {
            if (!rfcommConnected && !pingGlasses(timeoutMs)) {
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

    /**
     * Consecutive bonded-but-RFCOMM-down reconnect cycles. After
     * [COLD_START_AFTER_FAILURES] of these the listener process is presumed dead
     * (force-stopped/OOM), so we cold-start it via a 0x07 BLE wake to bt-manager.
     */
    private var coldStartFailures = 0
    @Volatile
    private var pairModeActive = false

    /**
     * Address of the device we locked onto in pair mode (set when we pin a device in
     * onGlassesIdentified). Once set, further identifies for a DIFFERENT address are ignored
     * until the connection completes (onConnected) or pairing is reset (disconnect-with-failure,
     * enterPairMode, exitPairMode). This stops a second glasses left in pairing from overwriting
     * the target the user actually chose.
     */
    @Volatile
    private var pendingPairAddress: String? = null
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
    /** Glasses reported its authoritative enable_sideloading state (in the status snapshot). The
     *  phone mirrors this -- persists + drives the LAN server -- and never pushes back on connect. */
    var onGlassesSideloadingState: ((enabled: Boolean) -> Unit)? = null
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
            }
        ).also {
            it.logCallback = { msg -> logCallback?.invoke(msg) }
            it.onGlassesIdentified = { device, _, pairingAvailable ->
                // The BLE scan callback runs on the BLE binder thread, but every pair-flag
                // (pairModeActive / autoConnectPending / rfcommConnected / pendingPairAddress) is
                // mutated on the main thread by startScanning / enter/exitPairMode / onConnected /
                // onDisconnected. Hop to main so the whole check-then-act below is single-threaded
                // and races nothing.
                mainHandler.post {
                    // BLE scan positively identified our glasses via REPO magic. The data link is the
                    // RFCOMM message relay (dialed by bond on a fixed MESSAGE_UUID) plus the BLE wake
                    // link -- no GATT/UUID discovery. Bond (RFCOMM requires it), pin BOTH relays to this
                    // EXACT device object, cache its address, then kick the relay.
                    //
                    // In user-driven pair mode we ONLY bond a unit that advertises pairingAvailable=1
                    // (unbonded / pairing-window-open). This is what lets the phone skip an old, still-
                    // paired glasses that is in range advertising the same name, and bond the NEW one
                    // that is actually waiting to pair. Outside pair mode we ignore the flag so a
                    // normal reconnect to the already-paired unit still works.
                    val blockedByPairing = pairModeActive && !pairingAvailable
                    // Latch: once we have pinned a device (autoConnectPending) ignore any further
                    // identify for a DIFFERENT address until this attempt completes or is reset.
                    // If the user left BOTH units in pairing, the second unit's identify must not
                    // overwrite the one we already chose.
                    val pinned = pendingPairAddress
                    if (autoConnectPending && pinned != null && device.address != pinned) {
                        log("Pair lock: ignoring ${device.address} -- already locked onto $pinned")
                        return@post
                    }
                    if (blockedByPairing) {
                        log("Pair mode: ignoring ${device.address} (pairingAvailable=false -- already-paired unit)")
                    } else if (!autoConnectPending && !rfcommConnected) {
                        autoConnectPending = true
                        pendingPairAddress = device.address
                        pairModeActive = false
                        // Cache the address the BOND actually uses -- device.address (the address the
                        // BLE scan found, which createBond/RFCOMM and bondedDevices all key on). Do NOT
                        // cache the REPO-advertised classicMac: on some units that is the public
                        // identity MAC while the bond is keyed by a resolvable private address (RPA),
                        // so caching classicMac makes findGlassesDevice() miss the bond and fall back
                        // to the wrong bonded unit. The cache only matters for a later cold reconnect;
                        // THIS pairing dials the exact object below regardless of what the cache holds.
                        AppConfig.setGlassesMac(context, device.address)
                        AppConfig.setGlassesSerial(context, "auto")
                        // Bond first -- RFCOMM requires an active bond. createBond() is a no-op that
                        // returns true if already bonded, so re-pairing an already-bonded unit is safe.
                        try { device.createBond() } catch (_: Exception) {}
                        ensureRfcommClientStarted()
                        // Pin BOTH relays to THIS exact device. setTargetDevice sets currentDevice +
                        // drops any stale socket + signals reconnect, so the connect loop dials this
                        // unit with NO MAC re-lookup -- the exact device BLE saw is the one we bond.
                        rfcommClient.setTargetDevice(device)
                        mapRfcommClient.setTargetDevice(device)
                        mainHandler.postDelayed({
                            if (!rfcommConnected) {
                                rfcommClient.requestImmediateReconnect("repo_ble_bonded")
                            }
                        }, 3000)
                    }
                }
            }
        }
    }

    val isConnected: Boolean
        get() = rfcommConnected

    /**
     * Count one bonded-but-RFCOMM-down reconnect cycle. Once we cross
     * [COLD_START_AFTER_FAILURES] the glasses listener process is presumed dead
     * (force-stopped / OOM-killed -- START_STICKY won't bring those back), so push a
     * 0x07 LAUNCH_LISTENER over BLE for bt-manager to startForegroundService it, then
     * let the normal RFCOMM reconnect re-attach. The counter resets on a successful
     * RFCOMM connect (onConnected).
     */
    private fun maybeColdStartGlassesListener(reason: String) {
        coldStartFailures++
        if (coldStartFailures < COLD_START_AFTER_FAILURES) return
        if (bleWake.sendLaunchListener()) {
            log("Cold-start: sent 0x07 LAUNCH_LISTENER after $coldStartFailures failed cycles ($reason)")
        } else {
            log("Cold-start: 0x07 LAUNCH_LISTENER not sent (BLE wake link down) ($reason)")
        }
        // Throttle: re-arm only after another full window of failures.
        coldStartFailures = 0
    }

    fun startScanning() {
        ensureRfcommClientStarted()

        // Already connected -- the 5s watchdog must not churn the link.
        if (rfcommConnected) {
            log("RFCOMM already connected, skipping reconnect scan")
            scheduleRetry()
            return
        }

        // During user-driven pair mode the ONLY path allowed to drive bonding/connection is the
        // BLE onGlassesIdentified callback (it gates on pairingAvailable and pins the exact unit).
        // The findBondedGlasses() -> requestImmediateReconnect("bonded") path below resolves by
        // cached MAC / first-bonded name and would re-latch the WRONG already-bonded unit, racing
        // the identify path. So while pairing, just reschedule the retry and bail.
        if (pairModeActive) {
            log("Pair mode active -- deferring to BLE identify path, not kicking bonded reconnect")
            scheduleRetry()
            return
        }

        log("Starting BT reconnect for glasses")
        autoConnectPending = false

        val cachedMac = AppConfig.getGlassesMac(context)

        // Detect bogus MAC (Android returns 02:00:00:00:00:00 without LOCAL_MAC_ADDRESS permission)
        val isBogus = cachedMac == "02:00:00:00:00:00" || cachedMac == "00:00:00:00:00:00"
        if (isBogus && cachedMac.isNotEmpty()) {
            log("Bogus cached MAC ($cachedMac) -- clearing and re-pairing")
            AppConfig.setGlassesMac(context, "")
        }

        // The data link is the RFCOMM message relay (GlassesRfcommClient): it dials a fixed
        // MESSAGE_UUID and finds the glasses by bond, plus the BLE wake link. No GATT/UUID
        // discovery. If the glasses are still bonded, kick the relay + map socket and ensure
        // the BLE wake link is armed; cold-start the listener over 0x07 if it keeps failing.
        val bondedGlasses = findBondedGlasses()
        if (bondedGlasses != null) {
            log("Glasses bonded (${bondedGlasses.address}) -- kicking RFCOMM message relay + map socket + BLE wake")
            maybeColdStartGlassesListener("bonded")
            rfcommClient.requestImmediateReconnect("bonded")
            mapRfcommClient.requestImmediateReconnect("bonded")
            ensureBleWakeStarted()
            scheduleRetry()
        } else {
            // Not bonded yet -- run BLE discovery to identify + bond our glasses. (pairModeActive
            // already returned above, so this is always the non-pair-mode self-heal entry.)
            log("No bonded glasses -- auto-starting BLE discovery")
            enterPairMode(clearCache = false)
        }
    }

    /**
     * Enter pair mode: start BLE discovery to identify + bond our glasses.
     *
     * @param clearCache when true (the user-driven "Pair Mode" button), wipe the cached
     *   MAC so discovery can bind to a different physical device. When false (automatic
     *   self-heal), keep the cached MAC -- discovery re-finds the SAME bonded glasses.
     */
    fun enterPairMode(clearCache: Boolean = true) {
        log("=== ENTERING PAIR MODE (clearCache=$clearCache) ===")
        pairModeActive = true
        mainHandler.removeCallbacks(reconnectRunnable)
        autoConnectPending = false
        // Fresh pair attempt -- clear any stale lock so the next identify can pin.
        pendingPairAddress = null

        // Drop any stale connected-device identity.
        bluetoothHelper.clearConnectedDevice()

        if (clearCache) {
            // Explicit user repair: clear the cached MAC so discovery can bind to a
            // different physical device.
            AppConfig.setGlassesMac(context, "")
            // Drop the live RFCOMM link (so the guards in handleDeviceFound /
            // onGlassesIdentified clear) but KEEP the old bond. We deliberately do not unpair
            // the old unit: a still-bonded unit advertises pairingAvailable=0 and is skipped,
            // while the new unit advertises pairingAvailable=1 and gets bonded. Unpairing the
            // old unit would make it also advertise pairing=1 and reintroduce the ambiguity.
            rfcommConnected = false
            autoConnectPending = false
            // Forget the previously-targeted unit so the relays re-resolve to whichever unit we
            // bond next (via the new cached MAC). Without this, currentDevice stays latched on
            // the old glasses and the relay reattaches to it even after a fresh pairing.
            rfcommClient.resetTarget()
            mapRfcommClient.resetTarget()
            log("Cleared cached MAC + reset relay target (kept old bond), starting flag-gated BLE discovery...")
        } else {
            log("Keeping cached credentials, starting BLE discovery...")
        }

        // Start BLE scan to identify + bond our glasses.
        bluetoothHelper.onPermissionsGranted()
        scheduleRetry()
    }

    /** Cancel an in-progress pairing: stop the BLE pair scan + retry loop and clear pair state.
     *  Lets the user toggle pairing off from the UI. Any already-established RFCOMM link is left
     *  alone; this only stops the active *search* for a new unit. */
    fun exitPairMode() {
        log("=== EXITING PAIR MODE (user cancelled) ===")
        pairModeActive = false
        autoConnectPending = false
        // User cancelled -- release the pair lock.
        pendingPairAddress = null
        mainHandler.removeCallbacks(reconnectRunnable)
        cancelCountdown()
        try { bluetoothHelper.stopScan() } catch (_: Exception) {}
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
        if (rfcommConnected) return
        if (autoConnectPending) return
        // In user-driven pair mode, do NOT bond on name alone -- multiple units advertise the
        // same "Glasses_xxxx" name, so name-matching would grab whichever (often the old, still-
        // paired) unit is seen first. Defer to the BLE onGlassesIdentified path, which gates on
        // the pairing flag and only bonds a unit that is actually available for pairing.
        if (pairModeActive) {
            log("Pair mode: deferring '$name' (${device.address}) to flag-gated BLE identify path")
            return
        }

        autoConnectPending = true
        log("Auto-connecting to '$name' -- bonding + kicking RFCOMM relay")
        AppConfig.setGlassesMac(context, device.address)
        try { device.createBond() } catch (_: Exception) {}
        ensureRfcommClientStarted()
        mainHandler.postDelayed({
            if (!rfcommConnected) {
                rfcommClient.requestImmediateReconnect("device_found_bonded")
            }
        }, 3000)
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
                coldStartFailures = 0
                pairModeActive = false
                // Connection completed -- release the pair lock so a later re-pair can pin again.
                pendingPairAddress = null
                // Record the connected-device identity for the UI / identity mirrors.
                val cachedMac = AppConfig.getGlassesMac(context).takeIf { it.isNotEmpty() }
                bluetoothHelper.setConnectedDevice(deviceName, cachedMac)
                // Single-glasses policy: this app supports exactly ONE active glasses. Now that we
                // are connected, unpair every OTHER bonded glasses so two bonded units can never
                // fight over the link (BLE wakes / reconnects from a second bonded unit otherwise
                // flap the connection + A2DP between the two). The keeper is the unit we just
                // connected to -- match by the bonded device whose connection backs this socket.
                pruneOtherBondedGlasses(keepDevice = rfcommClient.connectedDevice())
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
                // Mirror the freshly-connected device identity, then force a state re-broadcast.
                // If reachability was ALREADY Reachable (e.g. we re-paired from one unit to another
                // without ever dropping to Unreachable), onRfcommConnected() emits nothing, and the
                // UI's "Pairing..." button would never reset. The forced broadcast guarantees the
                // UI sees a connected event + the new device name and resets the button.
                ListenerService.glassesDeviceName = deviceName
                AppConfig.getGlassesMac(context).takeIf { it.isNotEmpty() }?.let {
                    ListenerService.glassesDeviceMac = it
                }
                reachability.forceBroadcast()
            }
            override fun onDisconnected() {
                log("RFCOMM disconnected from glasses")
                rfcommConnected = false
                // Pairing attempt failed / link dropped before completing: release the pair lock
                // and the auto-connect guard so the next identify (or retry) can pin a target again.
                autoConnectPending = false
                pendingPairAddress = null
                // Connection drop with TTS in flight: bt-manager will eventually
                // safety-timeout the session, but we must reset our local guard now
                // so the next stream's compareAndSet re-arms setActiveSession.
                if (ttsSessionHeld.compareAndSet(true, false)) {
                    rfcommClient.clearActiveSession("tts_playback")
                }
                bluetoothHelper.clearConnectedDevice()
                // Drop any live sideload session: the p2p0 route dies with the BT link.
                onSideloadBtDisconnected?.invoke()
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
        ensureBondReceiverRegistered()
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
            // During user-driven pair mode the ONLY path allowed to drive bonding/connection is the
            // BLE onGlassesIdentified callback (it gates on pairingAvailable and pins the exact unit).
            // A BLE-wake notify here would kick the relays onto whatever the cached MAC resolves to,
            // racing the identify path -- so defer, matching startScanning / handleDeviceFound.
            if (pairModeActive) {
                log("BleWake: rx code=0x${"%02X".format(code.toInt() and 0xFF)} ignored -- pair mode active")
                return@setOnNotifyCallback
            }
            log("BleWake: rx code=0x${"%02X".format(code.toInt() and 0xFF)} -> requesting RFCOMM reconnect")
            rfcommClient.requestImmediateReconnect("ble:0x${"%02X".format(code.toInt() and 0xFF)}")
            mapRfcommClient.requestImmediateReconnect("ble:0x${"%02X".format(code.toInt() and 0xFF)}")
        }
        bleWake.setOnConnectionStateCallback { up ->
            log("BleWake: link state=${if (up) "UP" else "DOWN"}")
            if (up) {
                // The BLE wake link is the steady-state idle channel (RFCOMM is demand-opened
                // and closed during idle). When BLE (re)connects -- e.g. after a glasses reboot
                // -- probe liveness so a stale Unreachable (left by a ping that timed out while
                // the glasses were down) recovers immediately instead of waiting up to 5 min
                // for the next reachability ticker. A pong promotes reachability to Reachable.
                scope.launch { pingGlasses(timeoutMs = 2_000) }
            } else {
                // BLE wake link dropped. Feed the reachability machine so a stack-down
                // (reboot/range-loss/power-off) debounces to Disconnected via a confirm-ping.
                reachability.onBleLinkDown()
            }
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

    private fun findBondedGlasses(): android.bluetooth.BluetoothDevice? {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return null
            val bonded = adapter.bondedDevices ?: return null
            // Prefer the exact paired unit (cached MAC). With TWO glasses bonded (old + freshly
            // paired) a first-by-name match would return whichever is first in the bonded set and
            // arm BLE-wake / A2DP / cold-start against the WRONG unit after a switch. The cached
            // MAC is the single source of truth for "our glasses". Mirrors the relays'
            // findGlassesDevice().
            val cachedMac = AppConfig.getGlassesMac(context)
            if (cachedMac.isNotEmpty()) {
                bonded.firstOrNull { it.address.equals(cachedMac, ignoreCase = true) }?.let { return it }
            }
            // Name fallback ONLY when there is exactly ONE bonded glasses -- two units must never
            // silently pick the wrong one, so return null on ambiguity.
            bonded.filter {
                val name = it.name ?: ""
                name.startsWith("Glasses_") || name.contains("glasses", ignoreCase = true)
            }.singleOrNull()
        } catch (_: Exception) { null }
    }

    /**
     * Single-glasses policy: unpair every bonded glasses EXCEPT the one we are now connected to.
     * Without this, two bonded units (an old one + a freshly paired one) both send BLE wakes and
     * answer reconnects, flapping the RFCOMM link + A2DP back and forth between them. After pairing
     * a new unit we forget the others so there is exactly one bonded glasses and zero ambiguity.
     * Uses the hidden BluetoothDevice.removeBond() (no public API on Android 12). The keeper is the
     * exact device the relay connected to; fall back to the cached MAC if that's unavailable.
     */
    @SuppressLint("MissingPermission")
    private fun pruneOtherBondedGlasses(keepDevice: android.bluetooth.BluetoothDevice?) {
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            val keepAddr = (keepDevice?.address ?: AppConfig.getGlassesMac(context)).takeIf { it.isNotEmpty() }
            if (keepAddr == null) { log("pruneOtherBondedGlasses: no keeper address, skipping"); return }
            // Persist the keeper so later reconnects resolve to exactly this unit.
            AppConfig.setGlassesMac(context, keepAddr)
            val others = (adapter.bondedDevices ?: emptySet()).filter { d ->
                val name = d.name ?: ""
                (name.startsWith("Glasses_") || name.contains("glasses", ignoreCase = true) ||
                    name.startsWith("RG_") || name.contains("Rokid", ignoreCase = true)) &&
                    !d.address.equals(keepAddr, ignoreCase = true)
            }
            if (others.isEmpty()) return
            for (d in others) {
                try {
                    val ok = d.javaClass.getMethod("removeBond").invoke(d) as? Boolean ?: false
                    log("pruneOtherBondedGlasses: removeBond(${d.name}/${d.address}) -> $ok (keeper=$keepAddr)")
                } catch (e: Exception) {
                    log("pruneOtherBondedGlasses: removeBond(${d.address}) failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            log("pruneOtherBondedGlasses failed: ${e.message}")
        }
    }

    private fun installCustomCmdListener() {
        try {
            rfcommClient.setRelayListener(object : RelayListener {
                override fun onCustomCmd(cmd: String?, args: RelayCaps?) {
                    if (cmd == null || args == null) return
                    // Any incoming RFCOMM frame is hard proof of a live connection. Feed the
                    // reachability state machine (sole writer of glassesConnected) so a stale
                    // Unreachable -- e.g. left by a ping timeout during a glasses reboot where
                    // the RFCOMM socket never observed a drop, or by restarting the phone app
                    // over a still-live socket -- recovers immediately.
                    reachability.onProofOfLife()
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
                        BtProtocol.CH_SIDELOAD -> {
                            try {
                                val json = args.at(0).getString()
                                log("Sideload reply from glasses: ${json.take(80)}")
                                onSideloadReply?.invoke(json)
                            } catch (e: Exception) {
                                log("Failed to parse sideload reply: ${e.message}")
                            }
                        }
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
                    // Glasses is the source of truth for sideloading. MIRROR its reported value
                    // into the phone (persist + drive the LAN server) -- the phone never pushes
                    // enable_sideloading on connect, it only reflects what the glasses reports.
                    if (obj.has("enable_sideloading")) {
                        onGlassesSideloadingState?.invoke(obj.optBoolean("enable_sideloading", false))
                    }
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
                // Resolve any awaiting sendDeviceCommand() caller first.
                pendingCommands.remove(requestId)?.complete(resultJson)
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

    /** Glasses -> phone sideload reply (CH_SIDELOAD JSON frame). Fed to SideloadForwarder. */
    var onSideloadReply: ((String) -> Unit)? = null

    /** Fired when the RFCOMM link drops so a live sideload session can be invalidated. */
    var onSideloadBtDisconnected: (() -> Unit)? = null

    /** Send a CH_SIDELOAD JSON frame (OPEN_WIFI / CLOSE_WIFI) to the glasses. */
    fun sendSideloadFrame(json: String): Boolean {
        if (!isConnected) {
            log("Sideload frame dropped: not connected")
            return false
        }
        return try {
            val ok = rfcommClient.send(BtProtocol.CH_SIDELOAD, json)
            txByteCount.addAndGet(estimateCapsSize(json))
            log("Sideload frame sent: ${json.take(60)}")
            ok
        } catch (e: Exception) {
            log("Failed to send sideload frame: ${e.message}")
            false
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

    /**
     * Send a device command on CH_DEVICE_COMMAND and await the glasses' reply.
     *
     * The glasses dispatch onCommand(type, requestId, paramsJson) and reply via
     * sendCommandResult(requestId, resultJson), which lands on CH_COMMAND as
     * ["command_result", requestId, resultJson] and is completed in
     * [handleCommandFromGlasses]. Returns the raw reply JSON string, or null on timeout.
     */
    suspend fun sendDeviceCommand(type: String, paramsJson: String = "{}", timeoutMs: Long = 8000): String? {
        val requestId = "dc-${deviceCommandIdSeq.incrementAndGet()}-${System.currentTimeMillis()}"
        val deferred = CompletableDeferred<String>()
        pendingCommands[requestId] = deferred
        try {
            val caps = RelayCaps()
            caps.write(type)
            caps.write(requestId)
            caps.write(paramsJson)
            rfcommClient.send(BtProtocol.CH_DEVICE_COMMAND, *caps.asArray())
            txByteCount.addAndGet(estimateCapsSize(type, requestId, paramsJson))
            log("Device command sent (await): $type ($requestId)")
        } catch (e: Exception) {
            pendingCommands.remove(requestId)
            log("Failed to send device command (await): ${e.message}")
            return null
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            log("Device command timed out: $type ($requestId)")
            null
        } finally {
            pendingCommands.remove(requestId)
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
     * Activate the glasses listener.
     * Warm path: if the health monitor reports the listener responsive (or RFCOMM is up),
     * send CH_ACTIVATE directly.
     * Cold path: the listener process is likely dead (force-stopped / OOM) -- send a 0x07
     * LAUNCH_LISTENER BLE wake so bt-manager startForegroundService's it, kick the RFCOMM
     * relay, and send CH_ACTIVATE once a frame proves it is back (best-effort: also send
     * after a short delay).
     */
    fun activateWithEnsure(healthMonitor: GlassesHealthMonitor) {
        if (healthMonitor.isResponsive || rfcommConnected) {
            log("Glasses responsive - warm path activate (CH_ACTIVATE)")
            sendActivateCommand()
            return
        }

        log("Glasses not responsive - cold-start via 0x07 LAUNCH_LISTENER")
        if (bleWake.sendLaunchListener()) {
            log("Cold-start: sent 0x07 LAUNCH_LISTENER")
        } else {
            log("Cold-start: 0x07 LAUNCH_LISTENER not sent (BLE wake link down)")
        }
        rfcommClient.requestImmediateReconnect("activate_cold_start")
        // Send the activate once the relay is (re)established; also fire after a short
        // safety delay so a missed reconnect callback does not strand the activation.
        mainHandler.postDelayed({ sendActivateCommand() }, 3000)
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
     * Sync all saved display/system settings to glasses via the CH_SETTINGS JSON channel
     * (read by glasses-side GlassesConfig.applySettings). Called on glasses connection to restore
     * phone-authoritative settings that glasses lose on reboot/reconnect. Brightness and volume
     * are glasses-authoritative for those values but still pushed here for parity.
     */
    fun pushGlassesSettings() {
        syncAllSettings(context.applicationContext)
    }

    fun syncAllSettings(context: Context) {
        log("Syncing all saved settings to glasses")

        val notificationDuration = AppConfig.getGlassesNotificationDuration(context).ifEmpty { "5" }
        val notificationSound = AppConfig.getGlassesNotificationSound(context)

        // App settings -- sent via CH_SETTINGS to GlassesConfig.applySettings (the only live
        // settings channel now that the CXR RKSettingsManager pipeline is gone).
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
            // Deliberately NOT included in the bulk reconnect-sync: enable_sideloading is
            // persisted on the GLASSES too and re-armed there at boot from GlassesConfig.load().
            // Re-pushing it on every BT reconnect would let a stale phone value (e.g. after the
            // phone app's data was cleared, which resets AppConfig to false) silently turn off
            // sideloading the user expected to stay on. The glasses' persisted value is therefore
            // authoritative; the flag is pushed ONLY on an explicit user toggle (see
            // GlassesSettingsFragment.setSideloadingEnabled / the sideload_toggle ADB command).
        }
        sendSettings(appSettings.toString())

        // Rokid's built-in offline wakeword/assistant must NEVER run -- speech
        // detection is exclusively our pipeline. It defaults ON at boot, so force
        // it OFF on every sync. The glasses-side voice_ctrl_off handler drives the
        // AssistantSuppressor and persists the local setting; it re-applies on boot.
        scope.launch {
            val resp = sendDeviceCommand("voice_ctrl_off")
            if (resp != null) {
                log("Forced Rokid voice control OFF (voice_ctrl_off)")
            } else {
                log("voice_ctrl_off command got no reply (timeout)")
            }
        }
    }

    fun release() {
        mainHandler.removeCallbacks(reconnectRunnable)
        cancelCountdown()
        if (bondReceiverRegistered) {
            try { context.unregisterReceiver(bondStateReceiver) } catch (_: Exception) {}
            bondReceiverRegistered = false
        }
        scope.cancel()
        // Tear down both RFCOMM links so neither connect thread is leaked.
        rfcommClient.stop()
        mapRfcommClient.stop()
        bluetoothHelper.release()
    }
}
