package com.repository.listener.wear

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * The remote scroll screen.
 *
 * This activity is exported (the Tile's LaunchAction requires it), so it must not
 * perform anything privileged merely on launch: it does NOT open the session, does
 * NOT emit input, and does NOT hold the screen on until the user genuinely
 * interacts. The session belongs to [WatchLinkService]; this screen attaches to it.
 */
class ScrollRemoteActivity : ComponentActivity() {

    private companion object {
        const val ATTACH_RETRY_MS = 150L
        const val ATTACH_MAX_ATTEMPTS = 40

        /** Cadence for re-evaluating whether the screen should stay awake. */
        const val SCREEN_TICK_MS = 5_000L
    }

    private val attachHandler = Handler(Looper.getMainLooper())
    private var linkService: WatchLinkService? = null

    private val linkState: MutableState<LinkState> = mutableStateOf(LinkState.SETUP)
    private val detentCount: MutableState<Int> = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, WatchLinkService::class.java))

        setContent {
            MaterialTheme {
                RemoteScreen(
                    state = linkState,
                    detents = detentCount,
                    onRotaryDelta = { delta ->
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            service.setStateListener { next ->
                runOnUiThread { linkState.value = next }
            }
            return
        }
        if (attempt < ATTACH_MAX_ATTEMPTS) {
            attachHandler.postDelayed({ attachWhenReady(attempt + 1) }, ATTACH_RETRY_MS)
        }
    }

    private fun noteInteraction() {
        detentCount.value = detentCount.value + 1
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Releases the screen once the session has been idle, per the battery budget.
     * keepScreenOn on this AMOLED costs far more than the link itself, so it is
     * held only while the user is actually scrolling.
     */
    private val screenTick = object : Runnable {
        override fun run() {
            val keep = linkService?.screenShouldStayOn() ?: false
            if (keep) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            attachHandler.postDelayed(this, SCREEN_TICK_MS)
        }
    }
}

@Composable
private fun RemoteScreen(
    state: MutableState<LinkState>,
    detents: MutableState<Int>,
    onRotaryDelta: (Float) -> Unit,
    onTap: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current

    // onRotaryScrollEvent delivers nothing unless the composable is focusable AND
    // actually holds focus, so request it once on first composition.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val current = state.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // EXACTLY ONE dispatch path is hooked. Rotary events reach all four of
            // Activity.dispatchGenericMotionEvent, Activity.onGenericMotionEvent,
            // View.OnGenericMotionListener and View.onGenericMotionEvent --
            // hooking more than one counts every detent multiple times.
            .onRotaryScrollEvent { event ->
                onRotaryDelta(rawAxisScroll(view, event.verticalScrollPixels))
                performDetentHaptic(view)
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            // One raw physical tap. No double-tap detection here on purpose: the
            // glasses own that disambiguation against a 400 ms threshold, and a
            // second detector would consume the pair and mask their logic.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = current.label,
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                color = if (current.inputEnabled) Color(0xFF4FC3F7) else Color(0xFFFFB74D),
            )
            Text(
                text = if (current.inputEnabled) "Turn the bezel to scroll" else "",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(top = 6.dp),
            )
            // Detent counter: the cheapest possible proof, visible on the watch,
            // that bezel events are actually reaching this app.
            Text(
                text = if (detents.value > 0) "detents ${detents.value}" else "",
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = Color(0xFF616161),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Recovers the RAW `AXIS_SCROLL` value from Compose's rotary event.
 *
 * Compose does not hand through the raw axis. `RotaryScrollEvent` is built as
 * `-getAxisValue(AXIS_SCROLL) * ViewConfiguration.scaledVerticalScrollFactor`, so
 * the value is (a) negated and (b) multiplied by a device-dependent pixel factor
 * that is roughly a list-item height -- on this watch (340 dpi) around two orders
 * of magnitude. Feeding that straight into an accumulator calibrated at 1.0 units
 * per detent would turn one detent into hundreds of steps: a single flick would
 * scroll the glasses for many seconds, and in the wrong direction.
 *
 * Dividing the factor back out restores the normalized -1..+1 per detent that the
 * hardware actually reports (verified via `dumpsys input`: SCROLL min=-1 max=+1),
 * and re-negating restores the sign convention where positive means forward/down.
 *
 * The factor is queried per event rather than cached because it is
 * configuration-dependent and this is a single cheap field read.
 */
private fun rawAxisScroll(view: View, verticalScrollPixels: Float): Float {
    val factor = android.view.ViewConfiguration.get(view.context).scaledVerticalScrollFactor
    val raw = if (factor <= 0f) -verticalScrollPixels else -verticalScrollPixels / factor.toFloat()
    // Calibration trace. Confirms on real hardware that one detent yields ~1.0
    // raw units, which is what ROTARY_DETENT_UNITS assumes. Read with:
    //   adb -s <watch> logcat -s RotaryCal
    android.util.Log.i(
        "RotaryCal",
        "pixels=$verticalScrollPixels factor=$factor raw=$raw t=${SystemClock.elapsedRealtime()}",
    )
    return raw
}

/**
 * One tick per detent. Falls back on the RETURN VALUE rather than on
 * Build.VERSION: performHapticFeedback reports false when a constant is not
 * supported on the device, which is the honest signal.
 */
private fun performDetentHaptic(view: View) {
    if (view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)) return
    if (view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)) return
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
