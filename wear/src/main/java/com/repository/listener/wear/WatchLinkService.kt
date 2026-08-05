package com.repository.listener.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.repository.listener.protocol.DetentAccumulator
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.EventType
import com.repository.listener.protocol.RemoteInputProtocol.RemoteInputEvent
import com.repository.listener.protocol.ScrollCoalescer
import com.repository.listener.protocol.SessionIdentity

/**
 * Owns the phone link AND the input session.
 *
 * The session lives here rather than in the activity on purpose. Tying it to the
 * activity's lifecycle would mean wrist-down or an ambient transition closes the
 * session, mints a new sid on wrist-up, flickers the status glyph on the glasses
 * on every wrist movement, and loses the detent accumulator's carried remainder
 * mid-scroll. The activity merely attaches and detaches.
 *
 * Threading: everything that touches session state runs on [worker]. The activity
 * posts raw deltas in; nothing else mutates the accumulator, the coalescer or the
 * sequence counter. Fields read from other threads are marked @Volatile.
 */
class WatchLinkService : Service() {

    companion object {
        private const val TAG = "WatchLink"

        private const val NOTIFICATION_CHANNEL = "glasses_remote_link"
        private const val NOTIFICATION_ID = 1

        const val CAP_PHONE_INPUT_SINK = "phone_input_sink"

        /**
         * Raw AXIS_SCROLL units per detent.
         *
         * Measured on the target watch (SM-L300): the rotary encoder reports
         * `SCROLL: source=ROTARY_ENCODER, min=-1.000, max=1.000`, i.e. the axis is
         * normalized to +/-1 per detent, and the platform emits exactly one event
         * per detent with no batching. So one unit is one detent.
         */
        const val ROTARY_DETENT_UNITS = 1.0f

        /** Release keepScreenOn after this long with no detent. */
        const val SCREEN_IDLE_RELEASE_MS = 30_000L

        /**
         * No status frame for this long -> UNREACHABLE.
         *
         * MUST exceed the backed-off PING interval. PING drops to 30 s once the
         * session has been idle for a minute, so a 25 s timeout would flap into
         * UNREACHABLE -- disabling input -- on every idle period even though the
         * link is perfectly healthy.
         */
        const val STATUS_TIMEOUT_MS = 75_000L

        /** Node resolution retry cadence while unpaired. */
        private const val NODE_RETRY_MS = 5_000L

        @Volatile
        private var instance: WatchLinkService? = null

        /** Null when no session is running. */
        fun current(): WatchLinkService? = instance
    }

    /** Notified on link-state changes so the UI and the Tile can update. */
    fun interface StateListener {
        fun onStateChanged(state: LinkState)
    }

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler

    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(this) }
    private val capabilityClient: CapabilityClient by lazy { Wearable.getCapabilityClient(this) }

    /**
     * The one sequence counter for this session.
     *
     * A plain Int guarded by [sendLock]: every caller runs on the worker thread,
     * and the lock spans mint-and-dispatch so numbering order cannot diverge from
     * the order frames are handed to the radio. An atomic on top of the lock would
     * imply a second concurrency story that does not exist.
     */
    private var seq = 0
    private val sendLock = Any()

    private var sid: Int = 0

    private val hmacKey: ByteArray by lazy { BuildConfig.REMOTE_INPUT_HMAC_KEY.toByteArray() }

    private lateinit var accumulator: DetentAccumulator
    private lateinit var coalescer: ScrollCoalescer

    @Volatile
    private var phoneNodeId: String? = null

    @Volatile
    private var everSawPhoneNode = false

    /** One outstanding capability lookup at a time; see [resolvePhoneNode]. */
    @Volatile
    private var resolveInFlight = false

    @Volatile
    private var lastResolveMs = 0L

    @Volatile
    private var statusBits = 0

    @Volatile
    private var lastStatusMs = 0L

    @Volatile
    private var lastDetentMs = 0L

    @Volatile
    var state: LinkState = LinkState.SETUP
        private set

    @Volatile
    private var listener: StateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        worker = HandlerThread("watch-link").apply { start() }
        handler = Handler(worker.looper)

        accumulator = DetentAccumulator(threshold = ROTARY_DETENT_UNITS)
        coalescer = ScrollCoalescer(sink = { type, steps, timeMs ->
            // Called on the worker thread only.
            sendEvent(type, steps, timeMs)
        })

        startForegroundSafely()
        handler.post { openSession() }
        handler.post(statusTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Best effort: the process can still die before GMS ships this, which is
        // why the receiver also expires a session after a silence timeout.
        handler.post { closeSession() }
        handler.removeCallbacks(statusTick)
        worker.quitSafely()
        instance = null
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Glasses remote link",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Glasses remote")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // The connectedDevice type requires a qualifying permission at the
            // moment of this call. If promotion fails we MUST stop: the service
            // was started with startForegroundService, and failing to promote
            // inside the platform's window raises
            // ForegroundServiceDidNotStartInTimeException, which is NOT catchable
            // and kills the process. Stopping cleanly turns a crash into a
            // degraded-but-diagnosable state.
            Log.e(TAG, "startForeground failed; stopping service", e)
            stopSelf()
        }
    }

    // ---- Session ----

    private fun openSession() {
        sid = mintSid()
        synchronized(sendLock) { seq = 0 }
        // Seed the status clock so the first seconds of a session read as SETUP or
        // UNPAIRED rather than as "Phone unreachable" before any frame can arrive.
        lastStatusMs = SystemClock.elapsedRealtime()
        resolvePhoneNode()
        sendEvent(EventType.OPEN, 0, SystemClock.elapsedRealtime())
        Log.i(TAG, "session open sid=${sid.toUInt()}")
    }

    private fun closeSession() {
        flushPending()
        sendEvent(EventType.CLOSE, 0, SystemClock.elapsedRealtime())
    }

    /**
     * Mints the next session id from the persisted counter.
     *
     * Uses commit(), not apply(): the value MUST be durable before the first frame
     * leaves. An async write lost to a power cut would let the watch reuse a sid at
     * a lower sequence, which is exactly the replay window the persisted counter
     * exists to close.
     */
    private fun mintSid(): Int {
        val prefs = getSharedPreferences(SessionIdentity.PREF_FILE, Context.MODE_PRIVATE)
        val previous = prefs.getInt(SessionIdentity.KEY_LAST_SID, 0)
        val next = SessionIdentity.mintNextSid(previous)
        prefs.edit().putInt(SessionIdentity.KEY_LAST_SID, next).commit()
        return next
    }

    // ---- Input ----

    /**
     * Feeds a raw rotary delta. Safe to call from the UI thread; the work is
     * marshalled onto the worker so the accumulator is never touched concurrently.
     */
    fun onRotaryDelta(delta: Float, eventTimeMs: Long) {
        handler.post {
            // ONE clock throughout. The accumulator's idle reset and the rate
            // limiter's window both compare timestamps, and mixing uptimeMillis
            // (which stops in deep sleep) with elapsedRealtime (which does not)
            // makes those deltas go negative after any doze, wedging the limiter
            // window so detents stop draining. The caller's event time is used
            // only for ordering within a gesture, so stamping here is safe.
            val now = SystemClock.elapsedRealtime()
            lastDetentMs = now
            val steps = accumulator.onDelta(delta, now)
            if (steps != 0) coalescer.onDetents(steps, now)
            scheduleDrain()
        }
    }

    /**
     * One raw physical tap. Emitted immediately and never merged with scroll: the
     * glasses own double-tap disambiguation against a 400 ms threshold, and
     * delaying a tap here would corrupt that arithmetic.
     */
    fun onTap() {
        val tapMs = SystemClock.elapsedRealtime()
        handler.post {
            // Jitter instrumentation. `queue` is the delay between the physical tap
            // and this reaching the worker. It is the component that could corrupt
            // the glasses' 400 ms double-tap arithmetic, because the receiver
            // disambiguates on the tap-time stamp carried in the frame.
            val onWorker = SystemClock.elapsedRealtime()
            Log.i(TAG, "TAP tap=$tapMs worker=$onWorker queue=${onWorker - tapMs}")
            coalescer.onDiscreteEvent(EventType.SELECT, tapMs)
        }
    }

    private val drainRunnable = Runnable {
        val now = SystemClock.elapsedRealtime()
        // Deliver whatever the rate limiter now permits, then close the window if
        // it has elapsed. Both are needed: the limiter holds a spin's tail after
        // the finger stops, and the window closes a partially filled event.
        val carried = accumulator.drain(now)
        if (carried != 0) coalescer.onDetents(carried, now)
        coalescer.onTimeout(now)
        if (accumulator.hasUndeliveredDetents || coalescer.hasPendingWindow) scheduleDrain()
    }

    private fun scheduleDrain() {
        handler.removeCallbacks(drainRunnable)
        handler.postDelayed(drainRunnable, 20L)
    }

    private fun flushPending() {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val chunk = accumulator.flushChunk()
            if (chunk == 0) break
            coalescer.onDetents(chunk, now)
        }
        coalescer.flush(now)
    }

    // ---- Sending ----

    /**
     * Mints a sequence number and sends, holding [sendLock] across both so the
     * numbering order and the dispatch order cannot diverge.
     *
     * Runs on the worker thread. `sendMessage` is asynchronous and we do not wait
     * on it: a blocking wait here would stall the input pipeline behind the radio.
     */
    private fun sendEvent(type: EventType, steps: Int, timeMs: Long) {
        val node = phoneNodeId
        if (node == null) {
            resolvePhoneNode()
            return
        }
        if (hmacKey.isEmpty()) {
            Log.e(TAG, "no HMAC key configured; refusing to send unauthenticated input")
            return
        }

        val payload: ByteArray
        synchronized(sendLock) {
            val event = RemoteInputEvent(
                sid = sid,
                seq = ++seq,
                type = type,
                steps = steps,
                wms = timeMs.toInt(),
            )
            payload = try {
                RemoteInputProtocol.encodeEvent(hmacKey, event)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "refusing to encode malformed event: ${e.message}")
                return
            }
        }

        val path = if (type == EventType.OPEN) {
            RemoteInputProtocol.PATH_OPEN
        } else {
            RemoteInputProtocol.PATH_EVENT
        }
        val handoffMs = SystemClock.elapsedRealtime()
        messageClient.sendMessage(node, path, payload)
            .addOnSuccessListener {
                // Measurement path. `stamp` is the age of the event when it was
                // handed to the radio: the jitter that shifts the glasses'
                // double-tap arithmetic. `ack` is the round trip to GMS accepting
                // the message, which bounds the Data Layer hop and therefore TTL.
                val ackMs = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "SENT type=$type stamp=${handoffMs - timeMs} " +
                        "ack=${ackMs - handoffMs} total=${ackMs - timeMs}",
                )
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "send failed ($type): ${e.message}")
                // Input events are never retried: a retried SCROLL arrives stale
                // and a retried SELECT could confirm something the user did not.
                phoneNodeId = null
            }
    }

    // ---- Node resolution & status ----

    /**
     * Resolves the phone node ASYNCHRONOUSLY.
     *
     * This must never block the worker thread. A blocking await here would charge
     * its full timeout to EVERY event whenever the phone is unreachable, queueing
     * every posted rotary delta behind it and starving the drain runnable -- the
     * user's scrolling would simply stop. A single in-flight flag plus a retry
     * throttle keeps this to one outstanding lookup regardless of event rate.
     */
    private fun resolvePhoneNode() {
        val now = SystemClock.elapsedRealtime()
        if (resolveInFlight) return
        if (now - lastResolveMs < NODE_RETRY_MS) return
        lastResolveMs = now
        resolveInFlight = true

        capabilityClient.getCapability(CAP_PHONE_INPUT_SINK, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
                phoneNodeId = node?.id
                if (node != null) everSawPhoneNode = true
                resolveInFlight = false
                handler.post { recomputeState() }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "capability lookup failed: ${e.message}")
                phoneNodeId = null
                resolveInFlight = false
            }
    }

    /** Applies a status frame received from the phone. */
    fun onStatus(bits: Int) {
        handler.post {
            // The status path is unauthenticated, so a frame may only ever make
            // the watch more pessimistic. It can never assert health over a
            // failure the watch observed for itself.
            statusBits = RemoteInputProtocol.StatusFlags.applyAdvisory(
                current = statusBits, received = bits, trusted = false,
            )
            lastStatusMs = SystemClock.elapsedRealtime()
            recomputeState()
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (phoneNodeId == null) resolvePhoneNode()
            sendEvent(EventType.PING, 0, SystemClock.elapsedRealtime())
            recomputeState()
            handler.postDelayed(this, pingIntervalMs())
        }
    }

    /** Backs off once the session has been idle, to save both batteries. */
    private fun pingIntervalMs(): Long {
        val idleFor = SystemClock.elapsedRealtime() - lastDetentMs
        return if (idleFor > RemoteInputProtocol.IDLE_BEFORE_PING_BACKOFF_MS) {
            RemoteInputProtocol.PING_IDLE_BACKOFF_MS
        } else {
            RemoteInputProtocol.PING_INTERVAL_MS
        }
    }

    private fun recomputeState() {
        val fresh = lastStatusMs != 0L &&
            SystemClock.elapsedRealtime() - lastStatusMs < STATUS_TIMEOUT_MS
        val btOn = try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: true
        } catch (e: SecurityException) {
            true
        }
        val next = LinkState.fromStatus(
            bits = statusBits,
            bluetoothOn = btOn,
            phoneNodeKnown = phoneNodeId != null,
            everSawPhoneNode = everSawPhoneNode,
            statusFresh = fresh,
        )
        if (next != state) {
            state = next
            listener?.onStateChanged(next)
            StatusTileService.requestUpdate(this)
        }
    }

    fun setStateListener(l: StateListener?) {
        listener = l
        l?.onStateChanged(state)
    }

    /** True while a detent has been seen recently enough to justify the screen. */
    fun screenShouldStayOn(): Boolean =
        lastDetentMs != 0L && SystemClock.elapsedRealtime() - lastDetentMs < SCREEN_IDLE_RELEASE_MS
}
