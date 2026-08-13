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
         * Ceiling on the reopen backoff.
         *
         * The condition it bounds -- phone off, or out of range -- lasts hours, so the
         * interval must grow into the same order of magnitude rather than settling at
         * something that still costs a synchronous preference commit every few seconds
         * all afternoon. Two minutes is far longer than any recovery the user waits on
         * (a phone coming back in range produces a node-resolution edge, and reconnect
         * paths reset the counter outright), and short enough that a genuinely stuck
         * link still retries unattended.
         */
        const val MAX_REOPEN_BACKOFF_MS = 120_000L

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

    /**
     * elapsedRealtime of the last session re-announcement, so the churn a forged
     * status frame could otherwise cause is bounded. Worker-thread confined.
     */
    private var lastReopenMs = 0L

    /**
     * Reopens since the far end last CONFIRMED it holds our session. Drives the
     * backoff. Worker-thread confined.
     */
    private var consecutiveReopens = 0

    /**
     * Set once teardown has begun, so the CLOSE frame cannot trip the silence backstop
     * and end the service's life by minting a session and announcing it.
     * Worker-thread confined.
     */
    private var shuttingDown = false

    /**
     * elapsedRealtime at which the OPEN for the current [sid] was dispatched.
     *
     * Suppresses a redundant SECOND reopen immediately after a first. The phone
     * answers a PING from a CACHED view of the glasses' session, so the correlated
     * reply to the very next PING after a fresh OPEN can still be carrying the verdict
     * from before that OPEN existed. Acting on it would mint again for no reason and,
     * because every mint restarts the establishment, could do so indefinitely under a
     * slow link. Worker-thread confined.
     */
    private var sessionOpenSentMs = 0L

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
        // Both marshalled onto the worker, and the flag set FIRST so it is already true
        // when closeSession runs. Setting it from this thread instead would race the
        // worker, and cancelling the retry from here would run BEFORE the posted
        // closeSession -- so a retry that closeSession itself queued would survive the
        // cancellation.
        handler.post { shuttingDown = true }
        handler.post { closeSession() }
        handler.post { handler.removeCallbacks(reopenRetryRunnable) }
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
        sessionOpenSentMs = 0L
        lastFrameSentMs = 0L
        // This IS the fresh start the backoff is meant to be released for, so it is
        // released here as well as on confirmation. Carrying a previous session's
        // failure count into a deliberately new one would throttle recovery for a
        // condition that no longer applies.
        consecutiveReopens = 0
        lastReopenMs = 0L
        // A reopen owed by the PREVIOUS session must not fire into this one: it would
        // discard a session that has just been minted and never given a chance.
        handler.removeCallbacks(reopenRetryRunnable)
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
        val now = SystemClock.elapsedRealtime()
        sessionOpenSent = dispatchEvent(node, EventType.OPEN, 0, now)
        if (sessionOpenSent) sessionOpenSentMs = now
    }

    /**
     * Abandons the current session and arms a brand new one: fresh sid, seq back to 0,
     * OPEN owed again. Worker thread only.
     *
     * ## Why this always MINTS, and is the only way to re-announce
     *
     * Every path that wants the session announced again routes through here, and none
     * of them may simply clear [sessionOpenSent] for the sid already on the wire. That
     * looks equivalent and is not. The receiver treats an OPEN for a sid it already
     * knows as a RESUME and deliberately preserves that session's sequence high-water
     * mark -- correct while this process keeps counting up, fatal otherwise. Two ways
     * it goes wrong, and the second is why the rule has to be absolute:
     *
     *  - Across a process restart the counter is back near zero, so every frame lands
     *    under the retained mark.
     *  - Even WITHIN one process, the receiver reserves its durable floor in blocks
     *    AHEAD of what it has applied, so after a receiver restart the floor can sit up
     *    to a whole reservation above the seq this watch has actually reached. A
     *    same-sid re-announce then wedges in the worst possible way: the receiver holds
     *    an open session, so it reports itself HEALTHY, and the status signal that
     *    would otherwise rescue us never fires.
     *
     * A fresh sid is always higher, so it is never mistaken for a replay, it takes the
     * receiver's adopt path, and it carries its own floor.
     *
     * ## Why it is rate limited, and why a refusal does NOT fall back
     *
     * Minting is a synchronous commit and a permanent step of a monotonic counter, and
     * the trigger reaches us over an unauthenticated channel. When the limit refuses,
     * this returns with [sessionOpenSent] UNTOUCHED -- clearing it anyway would fall
     * back to exactly the same-sid re-announce described above, turning the safety
     * limit into the bug. A retry is scheduled instead, because the caller has no other
     * recovery: sends are succeeding and the node is resolved, so the silence-based
     * trigger cannot fire, and without the retry the watch sits deadlocked until some
     * later status edge happens to arrive.
     *
     * ## Why the interval BACKS OFF, which the flat limit alone did not handle
     *
     * A flat 5 s limit still permits an unbounded loop, and one that fires on an
     * entirely ordinary condition rather than an attack: with the phone off or out of
     * range, the send fails, its listener asks for a reopen, the reopen dispatches a
     * fresh OPEN, that send fails too. One synchronous preference commit every 5 s for
     * as long as the user is away from their phone. Recovery must therefore get
     * CHEAPER the longer it keeps not working, so the interval doubles per consecutive
     * attempt up to [MAX_REOPEN_BACKOFF_MS].
     *
     * The counter resets on EVIDENCE, not on optimism -- see [noteSessionConfirmed],
     * called when the far end tells us, on a frame we correlated, that it is holding
     * our session. Resetting on a successful local dispatch instead would reset on
     * every attempt, since a dispatch "succeeding" only means GMS accepted the bytes.
     *
     * @return true if the session was actually re-armed.
     */
    private fun reopenSession(reason: String): Boolean {
        // The session is being torn down; a new one must not be minted, and an OPEN for
        // it must certainly not be put on the wire. Without this, closeSession's own
        // CLOSE trips the silence backstop and shutdown ends by ANNOUNCING a session.
        if (shuttingDown) return false
        // Nothing has been announced yet, so the OPEN still owed will carry the current
        // sid. Minting here would discard a perfectly good unused session id.
        if (!sessionOpenSent) return false

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastReopenMs
        val minInterval = reopenBackoffMs()
        if (lastReopenMs != 0L && sinceLast < minInterval) {
            val waitMs = minInterval - sinceLast
            Log.i(TAG, "reopen ($reason) rate limited; retrying in ${waitMs}ms")
            handler.removeCallbacks(reopenRetryRunnable)
            handler.postDelayed(reopenRetryRunnable, waitMs)
            return false
        }
        handler.removeCallbacks(reopenRetryRunnable)
        lastReopenMs = now
        if (consecutiveReopens < Int.MAX_VALUE) consecutiveReopens++

        val previous = sid
        sid = mintSid()
        synchronized(sendLock) { seq = 0 }
        sessionOpenSent = false
        sessionOpenSentMs = 0L
        // Clear the silence baseline too. Leaving the old session's last-send stamp in
        // place would let the silence branch fire again the instant the new session
        // sends its first frame, minting a second id for a session one frame old.
        lastFrameSentMs = 0L
        Log.i(
            TAG,
            "re-opening as sid=${sid.toUInt()} (was ${previous.toUInt()}): $reason",
        )
        // Announce immediately when there is somewhere to send it, rather than waiting
        // for the user's next detent. Recovery the user has to trigger by hand is the
        // deadlock they already reported, one retry later.
        phoneNodeId?.let { ensureSessionOpen(it) } ?: resolvePhoneNode()
        return true
    }

    /** Re-attempts a reopen the rate limit refused. See [reopenSession]. */
    private val reopenRetryRunnable = Runnable { reopenSession("retry after rate limit") }

    /**
     * The current minimum spacing between reopens: the base interval doubled once per
     * consecutive unconfirmed attempt, capped.
     *
     * Shifting rather than multiplying, and capping the shift distance, because the
     * counter is unbounded and `1 shl 32` is `1` in Kotlin -- a silent wrap that would
     * collapse the backoff back to its base at the exact point it is needed most.
     */
    private fun reopenBackoffMs(): Long {
        val shift = (consecutiveReopens - 1).coerceIn(0, 16)
        val scaled = RemoteInputProtocol.REOPEN_MIN_INTERVAL_MS shl shift
        return scaled.coerceAtMost(MAX_REOPEN_BACKOFF_MS)
    }

    /**
     * The far end has confirmed, on a frame we correlated, that it holds our session.
     *
     * This is the ONLY thing that resets the backoff, because it is the only available
     * evidence that a reopen actually achieved something. A locally successful dispatch
     * does not qualify: it means GMS accepted the bytes, which is equally true of every
     * attempt in a loop that is getting nowhere.
     */
    private fun noteSessionConfirmed() {
        if (consecutiveReopens == 0) return
        consecutiveReopens = 0
        handler.removeCallbacks(reopenRetryRunnable)
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
     * The RECEIVER's hold threshold, as last reported on the status channel.
     *
     * The UI reads this to size its own press timer, so a hold on the watch takes
     * exactly as long as a hold on the glasses' own touchpad. Falls back to the
     * protocol default until a frame reports one; a receiver that never reports keeps
     * the fallback forever, which is the correct degradation.
     */
    @Volatile
    var holdThresholdMs: Int = RemoteInputProtocol.StatusFlags.DEFAULT_HOLD_MS
        private set

    /**
     * A press was held past [holdThresholdMs]. Emitted as its own action, immediately.
     *
     * A hold is NOT a tap, so this CANCELS any pending single-tap window. Without that
     * the press that became a hold would also resolve to a SELECT when its window
     * expired and the glasses would act on both -- the same double-emission bug that
     * made glasses-side gesture recognition untenable in the first place.
     */
    fun onHold() = onHoldAt(SystemClock.elapsedRealtime())

    /**
     * A capture button was pressed. [type] is [EventType.PHOTO] or [EventType.VIDEO].
     *
     * Emitted immediately and never coalesced, like every other discrete action. The watch
     * attaches NO meaning to either: whether VIDEO starts or stops a recording is the
     * glasses' decision, because only they know whether one is running.
     */
    fun onCapture(type: EventType) = onCaptureAt(type, SystemClock.elapsedRealtime())

    @androidx.annotation.VisibleForTesting
    internal fun onCaptureAt(type: EventType, atMs: Long) {
        handler.post {
            // A capture press is not a tap, so it must not leave a half-recognised tap
            // behind to resolve into a stray SELECT afterwards.
            handler.removeCallbacks(singleTapRunnable)
            pendingTapMs = null
            Log.i(TAG, "GESTURE capture -> $type at=$atMs")
            coalescer.onDiscreteEvent(type, atMs)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun onHoldAt(holdMs: Long) {
        handler.post {
            handler.removeCallbacks(singleTapRunnable)
            pendingTapMs = null
            Log.i(TAG, "GESTURE hold -> HOLD at=$holdMs")
            coalescer.onDiscreteEvent(EventType.HOLD, holdMs)
        }
    }

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
        // they REJECT a PING for a sid they no longer hold -- a PING cannot resurrect
        // anything. So a gap that long means the session is gone and must be re-armed.
        //
        // This branch is a BACKSTOP, not the recovery path, and saying so matters
        // because it reads like the latter. The keepalive cadence is deliberately well
        // under the expiry, and every frame including a PING stamps lastFrameSentMs, so
        // on a working link this can never fire -- the keepalive that keeps the session
        // alive is precisely what stops it noticing the session is dead. It survives
        // only for the case where the keepalive itself stopped running. The real
        // recovery is the glasses reporting the lost session; see [onStatus].
        val now = SystemClock.elapsedRealtime()
        if (lastFrameSentMs != 0L &&
            now - lastFrameSentMs >= RemoteInputProtocol.SESSION_EXPIRY_MS
        ) {
            reopenSession("${now - lastFrameSentMs}ms silence")
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
                //
                // Re-armed with a NEW sid rather than by clearing the flag in place.
                // We do not know how much of this session the far end received, so
                // re-announcing the same sid risks landing on its resume path against
                // a sequence floor this session can no longer reach. See
                // [reopenSession] -- it is the only sanctioned way to re-announce.
                handler.post { reopenSession("send failed ($type)") }
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
                // Node lost. The next resolution must re-announce, or the glasses keep
                // rejecting every action for a sid they never saw an OPEN for.
                //
                // Through [reopenSession], so the re-announcement carries a NEW sid.
                // Clearing the flag in place would re-send OPEN for the current sid,
                // which puts the glasses on their resume path against a sequence floor
                // that may already be unreachable -- a session that reports healthy and
                // accepts nothing. That is the exact deadlock this feature shipped with.
                reopenSession("phone node lost")
            }
            recomputeState()
        }
    }

    /**
     * Adopts the receiver's hold threshold from a status frame.
     *
     * Applied from ANY frame, correlated or not, unlike the health bits. It is not a
     * health claim and cannot admit input or clear an observed failure -- the worst a
     * forged value can do is make holds slightly long or short, and [sanitizeHoldMs]
     * bounds even that to something performable.
     */
    /**
     * The receiver's opaque state bits, as last reported.
     *
     * Read by the UI to render indicators. The MEANING of each bit is agreed between the
     * glasses and this app; nothing in between knows them. Bit 0 = recording.
     */
    @Volatile
    var deviceState: Int = 0
        private set

    /** True when the glasses report a recording in progress. */
    val recording: Boolean
        get() = (deviceState and RemoteInputProtocol.StatusFlags.DEVICE_STATE_RECORDING) != 0

    fun onDeviceState(bits: Int) {
        if (bits == deviceState) return
        deviceState = bits
        Log.i(TAG, "device state <- 0x${Integer.toHexString(bits)} recording=$recording")
    }

    fun onHoldThreshold(reportedMs: Int?) {
        if (reportedMs == null) return
        val next = RemoteInputProtocol.StatusFlags.sanitizeHoldMs(reportedMs)
        if (next == holdThresholdMs) return
        holdThresholdMs = next
        Log.i(TAG, "hold threshold <- ${next}ms (reported $reportedMs)")
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
            maybeReopenForLostSession(bits, correlated)
            recomputeState()
        }
    }

    /**
     * THE recovery path: the glasses say they hold no session for us, so re-announce.
     *
     * Worker thread only. Reads the RECEIVED bits rather than the folded [statusBits]
     * on purpose -- the fold OR-retains a problem bit until a correlated frame clears
     * it, so acting on the folded value would keep re-triggering off a stale assertion
     * long after the glasses stopped making it.
     *
     * ## Only on a correlated frame
     *
     * A correlated frame answers a PING this watch sent, quoting its seq. That is as
     * much trust as an unauthenticated channel can offer, and it is the same bar every
     * other bit here must clear to change the watch's mind. The consequence is a bound
     * on recovery time of one ping interval, which is the price of not letting anyone
     * who can write to this channel steer our session id.
     *
     * An uncorrelated assertion is deliberately NOT answered with an extra PING to
     * "solicit" a correlated reply. That loop sustains itself -- solicit, glasses
     * reject, unsolicited report, solicit -- for as long as the reopen is rate limited,
     * and it spends the glasses' separate keepalive budget while doing so. The ordinary
     * 10 s PING already produces a correlated reply on its own.
     *
     * Recovery therefore takes up to TWO ping cycles rather than one, and it is worth
     * being exact about why: the phone answers a PING immediately from its CACHED view
     * of the glasses, so the correlated reply to the ping that first observes the loss
     * can still be carrying the previous verdict. The fresh verdict arrives with the
     * ping after it. Roughly 20 s worst case, against a deadlock that is currently
     * permanent.
     *
     * ## Why this cannot admit anything
     *
     * The most a forger achieves is making us mint a HIGHER session id and sign a fresh
     * OPEN with a key they do not have. No frame becomes acceptable that was not
     * already; a new session strictly invalidates the old one. The cost is bounded
     * churn, which is what [RemoteInputProtocol.REOPEN_MIN_INTERVAL_MS] limits.
     */
    private fun maybeReopenForLostSession(received: Int, correlated: Boolean) {
        if (!correlated) return
        if (!RemoteInputProtocol.StatusFlags.isSet(
                received, RemoteInputProtocol.StatusFlags.GLASSES_SESSION_LOST,
            )
        ) {
            // A correlated frame that does NOT report a lost session is the only positive
            // evidence available that a reopen worked. It is what releases the backoff;
            // see [noteSessionConfirmed].
            noteSessionConfirmed()
            return
        }
        // Suppress a report that predates our own most recent OPEN. The phone answers a
        // PING from its cached view of the glasses, so the reply to the first PING after
        // an OPEN can still describe the world before it. Acting on that would mint a
        // second id for a session that was never given a chance to establish.
        val sentAt = sessionOpenSentMs
        if (sentAt != 0L &&
            SystemClock.elapsedRealtime() - sentAt < RemoteInputProtocol.REOPEN_MIN_INTERVAL_MS
        ) {
            return
        }
        reopenSession("glasses report no open session")
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
