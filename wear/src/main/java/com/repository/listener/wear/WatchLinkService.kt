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

        /**
         * The starting value for [statusBits]: health bits set, problem bits clear.
         *
         * See the field for why a zero seed is wrong. Written as the health mask
         * rather than a literal so adding a health flag cannot silently reintroduce
         * the absorbing-zero bug for that one bit.
         */
        private val HEALTH_SEED =
            RemoteInputProtocol.StatusFlags.GLASSES_LINK_UP or
                RemoteInputProtocol.StatusFlags.PHONE_SERVICE_ALIVE or
                RemoteInputProtocol.StatusFlags.GLASSES_SINK_ATTACHED

        /**
         * Keepalive cadence for a given last-detent stamp. Pure, so the rule can be
         * tested without a running service or a real clock.
         *
         * [lastDetentMs] is 0 until the first real detent, and `now - 0` is the whole
         * device uptime -- days here. Comparing that against the idle threshold made
         * the backoff engage IMMEDIATELY on every fresh session instead of after a
         * minute of genuine idleness, which is what produced a 30 s keepalive gap
         * against the glasses' 20 s expiry from the very first session.
         */
        @JvmStatic
        @androidx.annotation.VisibleForTesting
        fun pingIntervalFor(lastDetentMs: Long, nowMs: Long): Long {
            if (lastDetentMs == 0L) return RemoteInputProtocol.PING_INTERVAL_MS
            val idleFor = nowMs - lastDetentMs
            return if (idleFor > RemoteInputProtocol.IDLE_BEFORE_PING_BACKOFF_MS) {
                RemoteInputProtocol.PING_IDLE_BACKOFF_MS
            } else {
                RemoteInputProtocol.PING_INTERVAL_MS
            }
        }

        @Volatile
        private var instance: WatchLinkService? = null

        /** Null when no session is running. */
        fun current(): WatchLinkService? = instance
    }

    /** Notified on link-state changes so the UI and the complication can update. */
    fun interface StateListener {
        fun onStateChanged(state: LinkState)
    }

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler

    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(this) }
    private val capabilityClient: CapabilityClient by lazy { Wearable.getCapabilityClient(this) }

    /**
     * Transport seam for tests. Null in production, where [dispatchEvent] talks to
     * [messageClient] directly.
     *
     * This exists because the defect it guards against was an INTERACTION, not a
     * component: `openSession` called the asynchronous node resolution and then sent
     * OPEN immediately, and `sendEvent` silently discarded it because the node was
     * still null. Every unit-testable piece was individually correct. A test can
     * only catch that by driving this service's real session lifecycle and looking
     * at what was actually handed to the radio, which needs somewhere to look.
     *
     * Deliberately a frame recorder rather than a mock MessageClient: it captures
     * the path and the encoded payload, so a test asserts on the bytes the phone
     * would receive rather than on an interaction with a mock.
     */
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var testFrameSink: ((path: String, payload: ByteArray) -> Unit)? = null

    /**
     * Node-resolution seam for tests. When set, it replaces the capability lookup
     * and is invoked with the callback that must receive the resolved node id (or
     * null). Keeping the SHAPE of the real resolver -- asynchronous, callback
     * delivered off the worker -- is the point: a synchronous stub would remove the
     * exact race the defect lived in.
     */
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var testNodeResolver: ((onResolved: (String?) -> Unit) -> Unit)? = null

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

    /**
     * Whether an OPEN for the current [sid] has actually been handed to the radio.
     *
     * Distinct from "openSession() ran". Node resolution is asynchronous, so on a
     * fresh process [phoneNodeId] is null at the moment openSession() wants to send
     * and the frame is silently discarded. The glasses NEVER adopt a session
     * implicitly -- every action for a sid they hold no OPEN for is rejected -- so a
     * session whose OPEN was dropped is permanently dead. This flag is what makes
     * establishment retryable instead of fire-and-forget.
     *
     * Worker-thread confined. Every write goes through [handler] even from GMS
     * callbacks, which run on the main thread.
     */
    private var sessionOpenSent = false

    /**
     * elapsedRealtime of the last frame handed to the radio for this session.
     *
     * Used only to detect that the glasses have almost certainly expired the session
     * on their side, so it can be re-announced. Worker-thread confined, like
     * [sessionOpenSent].
     */
    private var lastFrameSentMs = 0L

    @Volatile
    private var everSawPhoneNode = false

    /** One outstanding capability lookup at a time; see [resolvePhoneNode]. */
    @Volatile
    private var resolveInFlight = false

    @Volatile
    private var lastResolveMs = 0L

    /**
     * The watch's view of the link, folded from the phone's advisory status frames.
     *
     * Seeded with the health bits SET, not cleared. [StatusFlags.applyAdvisory]
     * AND-folds health bits so an unauthenticated frame can never assert health the
     * watch has not otherwise observed -- which means a zero seed is an absorbing
     * state: `0 AND anything == 0`, so every health bit stays off forever no matter
     * how many perfectly healthy frames arrive, and the watch shows
     * "Phone app stopped - open it" permanently while the phone is demonstrably
     * running. The seed is the watch's own prior, and the honest prior before any
     * evidence is "no failure observed"; the very first frame then intersects it
     * down to whatever the phone actually reports.
     *
     * Health is only ever RE-asserted here, at the start of a session. It is never
     * restored mid-session, so a failure the watch folded in still cannot be
     * cleared by a forged frame -- the containment that AND-folding exists for is
     * intact.
     */
    @Volatile
    private var statusBits = HEALTH_SEED

    @Volatile
    private var lastStatusMs = 0L

    @Volatile
    private var lastDetentMs = 0L

    /** elapsedRealtime when the outstanding PING was handed to the radio. */
    @Volatile
    private var lastPingSentMs = 0L

    /** seq of the outstanding PING, so only its reply is timed. */
    @Volatile
    private var lastPingSeq = 0

    @Volatile
    var state: LinkState = LinkState.SETUP
        private set

    @Volatile
    private var listener: StateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startWorker()
        startForegroundSafely()
        handler.post(statusTick)
    }

    /**
     * Everything [onCreate] does except the Android service ceremony.
     *
     * Split out so a test can exercise the real session lifecycle -- real worker
     * thread, real accumulator, real coalescer, real send path -- without needing
     * foreground-service permissions, which have nothing to do with the logic that
     * was broken.
     */
    private fun startWorker() {
        worker = HandlerThread("watch-link").apply { start() }
        handler = Handler(worker.looper)

        accumulator = DetentAccumulator(threshold = ROTARY_DETENT_UNITS)
        coalescer = ScrollCoalescer(sink = { type, steps, timeMs ->
            // Called on the worker thread only.
            sendEvent(type, steps, timeMs)
        })

        handler.post { openSession() }
    }

    @androidx.annotation.VisibleForTesting
    internal fun startForTest() = startWorker()

    /**
     * The session teardown [onDestroy] performs, without the Android service lifecycle.
     *
     * Separate from [stopForTest], which only kills the worker: what a test needs to exercise is the
     * FLUSH -- the pending detents and the half-recognised tap that must still be emitted -- and
     * that lives in `closeSession`, not in the looper shutdown.
     */
    @androidx.annotation.VisibleForTesting
    internal fun closeSessionForTest() {
        handler.post { closeSession() }
    }

    @androidx.annotation.VisibleForTesting
    internal fun stopForTest() {
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
    }

    /** Forces a node re-resolution, bypassing the retry throttle. */
    @androidx.annotation.VisibleForTesting
    internal fun resolveForTest() {
        lastResolveMs = 0L
        resolveInFlight = false
        handler.post { resolvePhoneNode() }
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
        // A tap half-recognised under the OLD session must not resolve into the new
        // one: it would be sent with a fresh sid and a stamp from before the session
        // existed, which the receiver ages against the wrong baseline.
        handler.removeCallbacks(singleTapRunnable)
        pendingTapMs = null
        sid = mintSid()
        synchronized(sendLock) { seq = 0 }
        sessionOpenSent = false
        lastFrameSentMs = 0L
        // Re-seed the fold for the new session. Carrying a previous session's
        // latched problem bits across an explicit reopen would report a failure the
        // new link has not exhibited.
        statusBits = HEALTH_SEED
        // Seed the status clock so the first seconds of a session read as SETUP or
        // UNPAIRED rather than as "Phone unreachable" before any frame can arrive.
        lastStatusMs = SystemClock.elapsedRealtime()
        resolvePhoneNode()
        // No send here. The node is not resolved yet on a fresh process, so a send
        // now is discarded. [ensureSessionOpen] emits the OPEN the moment
        // there is somewhere to send it, ahead of the first real event.
        Log.i(TAG, "session open sid=${sid.toUInt()} (OPEN pending node resolution)")
    }

    /**
     * Emits OPEN if this session has not announced itself yet. Worker thread only.
     *
     * Called from [sendEvent] rather than only from the resolution callback so that
     * OPEN is guaranteed to take a LOWER seq than the event that triggered it and to
     * be handed to the radio first. Ordering matters more than it looks: the phone's
     * relay drops anything at or behind what it already forwarded, so an ACTION that
     * overtakes its OPEN does not merely arrive early -- it makes the phone discard
     * the OPEN outright, and every subsequent action is then rejected for an unknown
     * sid until the session expires.
     */
    private fun ensureSessionOpen(node: String) {
        if (sessionOpenSent) return
        // Latched only on a dispatch that actually happened. Setting it up front
        // would bound an OPEN storm, but it would also mean a dispatch refused for
        // a local reason -- an absent HMAC key, an unencodable event -- left the
        // session marked announced forever with nothing on the wire, which is the
        // same permanently dead session this whole fix exists to prevent. The storm
        // is bounded instead by the fact that this runs on the single worker thread:
        // the OPEN is dispatched before the caller's own frame, so the flag is
        // already true for every later event in the burst.
        sessionOpenSent = dispatchEvent(node, EventType.OPEN, 0, SystemClock.elapsedRealtime())
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
     * One physical tap on the watch face.
     *
     * The watch RECOGNISES the gesture and emits the SEMANTIC ACTION it means: one
     * tap is [EventType.SELECT], two taps inside [DOUBLE_TAP_WINDOW_MS] are a single
     * [EventType.BACK]. Nothing downstream re-derives gestures -- see the note on
     * `RemoteInputProtocol.EventType`.
     *
     * Recognising here rather than on the glasses is the whole point: distinguishing
     * single from double REQUIRES waiting out the window before acting, and that wait
     * is free on the device the finger is touching. Stacked on top of ~450 ms of
     * transport it was not: a single tap took most of a second, and the code that
     * skipped the wait emitted a select AND a back for one double tap.
     */
    fun onTap() = onTapAt(SystemClock.elapsedRealtime())

    /**
     * [onTap] with the tap instant passed in rather than read.
     *
     * The same seam [ScrollCoalescer] uses, and for the same reason: the recogniser's whole job is
     * arithmetic on the gap between two taps, and a test that produced that gap with `Thread.sleep`
     * would be measuring the watch's scheduler, not the rule. It does -- on this hardware a
     * `sleep(150)` from the instrumentation thread was observed landing 552 ms later, which the
     * recogniser then correctly classified as two singles and the test wrongly called a failure.
     * Everything downstream of the timestamp (worker thread, coalescer, sequence, encoder, send)
     * is still the real thing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onTapAt(tapMs: Long) {
        handler.post {
            // Jitter instrumentation. `queue` is the delay between the physical tap
            // and this reaching the worker, i.e. how much of the recognition window
            // is spent before the recogniser even sees the tap.
            val onWorker = SystemClock.elapsedRealtime()
            Log.i(TAG, "TAP tap=$tapMs worker=$onWorker queue=${onWorker - tapMs}")
            onTapRecognised(tapMs)
        }
    }

    /**
     * The pending FIRST tap of a possible double, or null when no window is open.
     *
     * Worker-thread confined, like every other piece of session state.
     */
    private var pendingTapMs: Long? = null

    /** Fires when a pending tap's window closes with no second tap: it was a single. */
    private val singleTapRunnable = Runnable {
        val tapMs = pendingTapMs ?: return@Runnable
        pendingTapMs = null
        Log.i(TAG, "GESTURE single -> SELECT tap=$tapMs")
        coalescer.onDiscreteEvent(EventType.SELECT, tapMs)
    }

    /**
     * Worker-thread tap recogniser. See [onTap].
     *
     * A third rapid tap cannot produce anything absurd: the second tap CLOSES the
     * window (cancelling the timer and clearing [pendingTapMs]) before emitting BACK,
     * so a third tap opens a brand new window and is judged on its own. Three fast
     * taps are therefore BACK then SELECT, never a second BACK from a re-used first
     * tap and never two overlapping windows.
     */
    private fun onTapRecognised(tapMs: Long) {
        val first = pendingTapMs
        if (first != null && tapMs - first < RemoteInputProtocol.DOUBLE_TAP_WINDOW_MS) {
            handler.removeCallbacks(singleTapRunnable)
            pendingTapMs = null
            Log.i(TAG, "GESTURE double -> BACK first=$first second=$tapMs gap=${tapMs - first}")
            // Stamped with the SECOND tap: that is the moment the user completed the
            // gesture, and it is what the receiver's TTL must be measured against.
            // Stamping the first would charge the whole recognition window to age and
            // land every BACK a window closer to being dropped as stale.
            coalescer.onDiscreteEvent(EventType.BACK, tapMs)
            return
        }
        // A tap while another window is open but OUTSIDE it: the pending one is a
        // single and must be emitted now, in order, before this one opens its window.
        if (first != null) {
            handler.removeCallbacks(singleTapRunnable)
            singleTapRunnable.run()
        }
        pendingTapMs = tapMs
        handler.postDelayed(singleTapRunnable, RemoteInputProtocol.DOUBLE_TAP_WINDOW_MS)
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
        // A tap still inside its recognition window is a SINGLE: no second tap is
        // coming, because the session is ending. Dropping it would silently discard
        // an action the user actually performed -- the same conservation rule the
        // coalescer's own flush exists to satisfy.
        resolvePendingTapAsSingle()
    }

    /** Emits a still-pending first tap as SELECT now, if there is one. */
    private fun resolvePendingTapAsSingle() {
        if (pendingTapMs == null) return
        handler.removeCallbacks(singleTapRunnable)
        singleTapRunnable.run()
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
        // The glasses drop a session that has been silent for SESSION_EXPIRY_MS, and
        // they REJECT a PING for a sid they no longer hold -- a PING cannot
        // resurrect anything. The idle PING backoff (30 s) is deliberately longer
        // than that expiry (20 s), so an idle session is always already gone by the
        // time the next keepalive goes out. Rather than shortening the backoff and
        // paying for it on both batteries, notice the gap and re-announce: the
        // glasses' resume path accepts an OPEN for the session already in progress
        // and preserves its sequence high-water mark.
        val now = SystemClock.elapsedRealtime()
        if (sessionOpenSent &&
            lastFrameSentMs != 0L &&
            now - lastFrameSentMs >= RemoteInputProtocol.SESSION_EXPIRY_MS
        ) {
            // Mint a new sid rather than re-announcing the old one. Reusing it puts the
            // receiver on its resume path, which keeps the previous session's sequence
            // high-water mark -- correct while this process keeps counting up, but fatal
            // once the process has restarted in between: the counter is back near zero,
            // every frame lands under the mark, and the link rejects input for good while
            // still looking healthy. A fresh sid is always higher, so it is never mistaken
            // for a replay and it carries its own floor.
            val previous = sid
            sid = mintSid()
            synchronized(sendLock) { seq = 0 }
            Log.i(
                TAG,
                "re-opening as sid=${sid.toUInt()} (was ${previous.toUInt()}) " +
                    "after ${now - lastFrameSentMs}ms silence",
            )
            sessionOpenSent = false
        }
        // Every session must announce itself before it can act. OPEN itself is
        // exempt, or this would recurse.
        if (type != EventType.OPEN) ensureSessionOpen(node)
        dispatchEvent(node, type, steps, timeMs)
    }

    /** @return true if the frame was genuinely handed onward. */
    private fun dispatchEvent(node: String, type: EventType, steps: Int, timeMs: Long): Boolean {
        if (hmacKey.isEmpty()) {
            Log.e(TAG, "no HMAC key configured; refusing to send unauthenticated input")
            return false
        }

        val payload: ByteArray
        val sentSeq: Int
        synchronized(sendLock) {
            sentSeq = ++seq
            val event = RemoteInputEvent(
                sid = sid,
                seq = sentSeq,
                type = type,
                steps = steps,
                wms = timeMs.toInt(),
            )
            payload = try {
                RemoteInputProtocol.encodeEvent(hmacKey, event)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "refusing to encode malformed event: ${e.message}")
                return false
            }
        }

        val path = if (type == EventType.OPEN) {
            RemoteInputProtocol.PATH_OPEN
        } else {
            RemoteInputProtocol.PATH_EVENT
        }
        val handoffMs = SystemClock.elapsedRealtime()
        lastFrameSentMs = handoffMs
        if (type == EventType.PING) {
            // Stamp only once the frame is genuinely on its way. Stamping before
            // the early returns above would arm the timer for a PING that was
            // never sent, and the next unsolicited status would be recorded as a
            // huge round trip.
            lastPingSentMs = handoffMs
            lastPingSeq = sentSeq
        }
        val sink = testFrameSink
        if (sink != null) {
            sink(path, payload)
            Log.i(TAG, "SENT type=$type sid=${sid.toUInt()} seq=${sentSeq.toUInt()} (test sink)")
            return true
        }
        messageClient.sendMessage(node, path, payload)
            .addOnSuccessListener {
                // Measurement path. `stamp` is the age of the event when it was
                // handed to the radio -- for a recognised action that legitimately
                // includes the recognition window it waited out. `ack` is the round
                // trip to GMS accepting the message, which bounds the Data Layer hop
                // and therefore the TTL the glasses enforce.
                val ackMs = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "SENT type=$type sid=${sid.toUInt()} seq=${sentSeq.toUInt()} " +
                        "stamp=${handoffMs - timeMs} ack=${ackMs - handoffMs} " +
                        "total=${ackMs - timeMs}",
                )
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "send failed ($type): ${e.message}")
                // Input events are never retried: a retried SCROLL arrives stale
                // and a retried SELECT could confirm something the user did not.
                phoneNodeId = null
                // The session is no longer established as far as we can tell -- and
                // if the frame that failed WAS the OPEN, nothing else would ever
                // re-send it. Marshalled onto the worker: this listener runs on the
                // main thread, and a plain write here could clobber a concurrent
                // worker-side write.
                handler.post { sessionOpenSent = false }
            }
        return true
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

        val stub = testNodeResolver
        if (stub != null) {
            stub { nodeId -> onNodeResolved(nodeId) }
            return
        }

        com.google.android.gms.wearable.Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                Log.i(TAG, "connectedNodes=${nodes.size} " +
                    nodes.joinToString { "${it.displayName}/${it.id}/nearby=${it.isNearby}" })
            }
            .addOnFailureListener { e -> Log.w(TAG, "connectedNodes failed: ${e.message}") }

        capabilityClient.getCapability(CAP_PHONE_INPUT_SINK, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
                Log.i(
                    TAG,
                    "capability nodes=${info.nodes.size} " +
                        info.nodes.joinToString { "${it.displayName}/${it.id}/nearby=${it.isNearby}" },
                )
                onNodeResolved(node?.id)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "capability lookup failed: ${e.message}")
                onNodeResolved(null)
            }
    }

    /**
     * Single completion path for node resolution, however it was performed.
     *
     * This is where blocker A is actually closed: resolution is asynchronous, so the
     * OPEN cannot be sent by [openSession] -- it has to be sent here, the first
     * moment there is a node to send it to. Nothing else re-sent it, so before this
     * existed every session was announced to nobody and the glasses rejected every
     * action for a sid they had never seen an OPEN for.
     *
     * Callers run on the MAIN thread (GMS task listeners do). Session state is
     * worker-confined, so every mutation is marshalled through [handler].
     */
    private fun onNodeResolved(nodeId: String?) {
        phoneNodeId = nodeId
        if (nodeId != null) everSawPhoneNode = true
        resolveInFlight = false
        handler.post {
            val resolved = phoneNodeId
            if (resolved != null) {
                ensureSessionOpen(resolved)
            } else {
                // Node lost. The next resolution must re-announce the session, or the
                // glasses keep rejecting every action for a sid they never saw an
                // OPEN for. Re-announcing is safe: the glasses treat an OPEN for the
                // session already in progress as liveness and PRESERVE their own
                // sequence high-water mark, so it can never rewind them.
                sessionOpenSent = false
            }
            recomputeState()
        }
    }

    /** Applies a status frame received from the phone. */
    fun onStatus(bits: Int, replyToSeq: Int?) {
        // Round-trip measurement, taken on a SINGLE clock.
        //
        // Both devices report elapsedRealtime but from different boots, so
        // subtracting one from the other measures the clock offset rather than
        // latency. Timing the phone's reply against the PING that caused it needs
        // no clock synchronisation at all.
        //
        // ONLY correlated replies are timed. The phone also pushes status
        // spontaneously (link-state change, dropped send, waking glasses), and
        // attributing one of those to the last PING fabricates a round trip of up
        // to a whole ping interval -- which would land in exactly the upper tail
        // that sets the staleness cutoff.
        val pingAt = lastPingSentMs
        // Correlation is what lets a health bit RECOVER. A frame answering our own
        // outstanding PING is evidence we asked for, so it can restore health the watch
        // had latched off; an unsolicited frame still cannot.
        val correlated = replyToSeq != null && replyToSeq == lastPingSeq
        if (pingAt != 0L && correlated) {
            val rtt = SystemClock.elapsedRealtime() - pingAt
            lastPingSentMs = 0L
            Log.i(TAG, "RTT ms=$rtt seq=${replyToSeq!!.toUInt()}")
        } else if (replyToSeq == null) {
            Log.i(TAG, "status push (unsolicited, not timed)")
        }
        handler.post {
            // The status path is unauthenticated, so an UNSOLICITED frame may only ever
            // make the watch more pessimistic. A CORRELATED one may also clear a health
            // bit the watch latched off -- without that, one cold-start
            // `replyPhoneStopped` pinned the watch at "Phone service down" for the life
            // of the process and reopening the phone app could not fix it.
            statusBits = RemoteInputProtocol.StatusFlags.foldStatus(
                current = statusBits, received = bits, correlated = correlated,
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

    /**
     * Backs off once the session has been idle, to save both batteries.
     *
     * `lastDetentMs` starts at 0 and is written only by a real detent, so before the
     * FIRST detent of a process `idleFor` is the whole device uptime -- days on this
     * hardware. The backoff therefore engages IMMEDIATELY on a fresh session rather
     * than after a minute of genuine idleness, which is the opposite of intent.
     * Treating "no detent yet" as "not idle yet" is what makes the constant mean
     * what its name says.
     */
    private fun pingIntervalMs(): Long = pingIntervalFor(lastDetentMs, SystemClock.elapsedRealtime())

    @androidx.annotation.VisibleForTesting
    internal fun statusBitsForTest(): Int = statusBits

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
            LinkComplicationService.requestUpdate(this)
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
