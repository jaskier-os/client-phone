package com.repository.listener.wear

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import android.view.WindowManager
import androidx.wear.compose.material3.MaterialTheme

/**
 * The remote scroll screen.
 *
 * This activity is exported (the complication's tap PendingIntent requires it), so
 * it must not
 * perform anything privileged merely on launch: it does NOT open the session, does
 * NOT emit input, and does NOT hold the screen on until the user genuinely
 * interacts. The session belongs to [WatchLinkService]; this screen attaches to it.
 *
 * It owns presentation only. The link state machine, the session and the wire
 * protocol all live in the service; this class reads [LinkState] and renders it.
 */
class ScrollRemoteActivity : ComponentActivity() {

    private companion object {
        const val ATTACH_RETRY_MS = 150L
        const val ATTACH_MAX_ATTEMPTS = 40

        /** Cadence for re-evaluating whether the screen should stay awake. */
        const val SCREEN_TICK_MS = 5_000L

        /**
         * Cadence for refreshing the elapsed-time readout on states that can
         * hang. One second is the coarsest tick that still looks live; anything
         * faster would recompose for no readable gain.
         */
        const val ELAPSED_TICK_MS = 1_000L
    }

    private val attachHandler = Handler(Looper.getMainLooper())
    private var linkService: WatchLinkService? = null

    private val linkState: MutableState<LinkState> = mutableStateOf(LinkState.SETUP)
    private val elapsedLabel: MutableState<String> = mutableStateOf("")

    /** Visual/tactile feedback state. Plain object, never snapshot state. */
    private val feedback = FeedbackEngine()

    /**
     * Mirrors FLAG_KEEP_SCREEN_ON so it is only ever toggled on a real change.
     * addFlags dispatches window attributes to WindowManager over binder and
     * forces a relayout, so calling it per detent -- up to ~31 times a second --
     * would put a cross-process round trip directly on the input path.
     */
    private var screenHeld = false

    /** When the current state was entered, for the elapsed readout. */
    private var stateEnteredMs = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, WatchLinkService::class.java))

        setContent {
            MaterialTheme {
                RemoteScreen(
                    state = linkState,
                    elapsedLabel = elapsedLabel,
                    engine = feedback,
                    onRotaryDelta = RotaryDeltaSink { delta ->
                        WatchLinkService.current()?.onRotaryDelta(delta, SystemClock.elapsedRealtime())
                        noteInteraction()
                    },
                    onTap = {
                        WatchLinkService.current()?.onTap()
                        noteInteraction()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attachWhenReady()
        attachHandler.post(screenTick)
        attachHandler.post(elapsedTick)
    }

    override fun onStop() {
        super.onStop()
        // Detach only. Wrist-down must NOT close the session, or the sid churns
        // and the accumulator's carried remainder is lost mid-scroll.
        //
        // Clearing the listener also stops the service -- a process-lifetime
        // singleton -- from retaining this activity through the callback.
        linkService?.setStateListener(null)
        linkService = null
        attachHandler.removeCallbacksAndMessages(null)
        // Drop any animation still in flight, so returning to the screen never
        // resumes a fade belonging to an interaction the user has forgotten.
        feedback.reset()
        setScreenHeld(false)
    }

    /**
     * startForegroundService is asynchronous, so on a cold start the service does
     * not exist yet when onStart runs. Retry briefly rather than silently leaving
     * the UI frozen on its initial state forever.
     */
    private fun attachWhenReady(attempt: Int = 0) {
        val service = WatchLinkService.current()
        if (service != null) {
            linkService = service
            // Delivered through attachHandler rather than runOnUiThread, because
            // onStop clears this handler's queue. A runOnUiThread post cannot be
            // cancelled, so a state change racing detach would fire haptics and
            // mutate UI state for a screen the user has already left.
            service.setStateListener { next ->
                attachHandler.post { onStateChanged(next) }
            }
            return
        }
        if (attempt < ATTACH_MAX_ATTEMPTS) {
            attachHandler.postDelayed({ attachWhenReady(attempt + 1) }, ATTACH_RETRY_MS)
        }
    }

    /**
     * Applies a new link state and fires the transition haptics.
     *
     * Only the crossing between "input works" and "input does not" is signalled,
     * not every state change. The user is wearing AR glasses and usually is not
     * looking at the watch, so the one fact worth spending a vibration on is
     * whether their next gesture will do anything. Buzzing for a transition they
     * cannot act on is noise and battery.
     */
    private fun onStateChanged(next: LinkState) {
        val previous = linkState.value
        if (next == previous) return

        linkState.value = next
        stateEnteredMs = SystemClock.elapsedRealtime()
        refreshElapsed()

        val view = window.decorView
        if (previous.inputEnabled && !next.inputEnabled) {
            Haptics.linkLost(view)
        } else if (!previous.inputEnabled && next.inputEnabled) {
            Haptics.linkRestored(view)
        }
    }

    private fun noteInteraction() {
        setScreenHeld(true)
    }

    /** Single point of truth for the window flag; see [screenHeld]. */
    private fun setScreenHeld(hold: Boolean) {
        if (hold == screenHeld) return
        screenHeld = hold
        if (hold) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Releases the screen once the session has been idle, per the battery budget.
     * keepScreenOn on this AMOLED costs far more than the link itself, so it is
     * held only while the user is actually scrolling.
     */
    private val screenTick = object : Runnable {
        override fun run() {
            setScreenHeld(linkService?.screenShouldStayOn() ?: false)
            attachHandler.postDelayed(this, SCREEN_TICK_MS)
        }
    }

    /**
     * Keeps the elapsed readout live for states that can hang. Costs one snapshot
     * write per second and ONLY while such a state is showing -- for every other
     * state the label is already empty and the write is skipped entirely.
     */
    private val elapsedTick = object : Runnable {
        override fun run() {
            refreshElapsed()
            attachHandler.postDelayed(this, ELAPSED_TICK_MS)
        }
    }

    private fun refreshElapsed() {
        val next = if (!linkState.value.showsElapsed) {
            ""
        } else {
            val seconds = (SystemClock.elapsedRealtime() - stateEnteredMs) / 1000L
            if (seconds < 60L) "${seconds}s" else "${seconds / 60L}m ${seconds % 60L}s"
        }
        if (elapsedLabel.value != next) elapsedLabel.value = next
    }
}
