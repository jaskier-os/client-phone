package com.repository.listener.wear

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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

    private var linkService: WatchLinkService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, WatchLinkService::class.java))

        setContent {
            MaterialTheme {
                RemoteScreen(
                    onRotaryDelta = { delta, eventTime ->
                        WatchLinkService.current()?.onRotaryDelta(delta, eventTime)
                    },
                    onTap = { WatchLinkService.current()?.onTap() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        linkService = WatchLinkService.current()
    }

    override fun onStop() {
        super.onStop()
        // Detach only. Wrist-down must NOT close the session, or the sid churns
        // and the accumulator's carried remainder is lost mid-scroll.
        linkService?.setStateListener(null)
        linkService = null
    }
}

@Composable
private fun RemoteScreen(
    onRotaryDelta: (Float, Long) -> Unit,
    onTap: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    var state by remember { mutableStateOf(LinkState.SETUP) }
    var detents by remember { mutableStateOf(0) }

    // onRotaryScrollEvent delivers nothing unless the composable is focusable AND
    // actually holds focus, so request it once on first composition.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        WatchLinkService.current()?.setStateListener { next -> state = next }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // EXACTLY ONE dispatch path is hooked. Rotary events are delivered to
            // all four of Activity.dispatchGenericMotionEvent,
            // Activity.onGenericMotionEvent, View.OnGenericMotionListener and
            // View.onGenericMotionEvent -- hooking more than one counts every
            // detent multiple times.
            .onRotaryScrollEvent { event ->
                onRotaryDelta(event.verticalScrollPixels, SystemClock.uptimeMillis())
                detents++
                performDetentHaptic(view)
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.label,
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                color = if (state.inputEnabled) Color(0xFF4FC3F7) else Color(0xFFFFB74D),
            )
            Text(
                text = if (state.inputEnabled) "Turn the bezel to scroll" else "",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(top = 6.dp),
            )
            // Detent counter: the cheapest possible proof, visible on the watch,
            // that bezel events are actually reaching this app.
            Text(
                text = if (detents > 0) "detents $detents" else "",
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = Color(0xFF616161),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
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
