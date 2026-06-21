package com.repository.listener.ui.rc

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.config.DraftStore
import com.repository.listener.network.RemoteSessionClient
import com.repository.listener.service.ListenerService
import com.repository.listener.ui.views.ChatVoiceRecorder
import com.repository.listener.ui.views.RecordingOverlay
import com.repository.listener.ui.views.VoiceRecordButton
import com.repository.listener.util.LogCollector
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class RemoteControlActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RemoteControlActivity"
        private const val REQUEST_MIC = 1001
        const val EXTRA_SESSION_ID = "rc_session_id"
        const val EXTRA_WORK_DIR = "rc_work_dir"
        const val EXTRA_SESSION_STATUS = "rc_session_status"
        const val EXTRA_PERMISSION_MODE = "rc_permission_mode"
    }

    private lateinit var adapter: RcDetailAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageView
    private lateinit var micButton: VoiceRecordButton
    private lateinit var recordingOverlay: RecordingOverlay
    private lateinit var titleText: TextView
    private lateinit var statusDot: View
    private lateinit var modeSelector: TextView
    private lateinit var endedBanner: TextView
    private lateinit var scrollToBottomBtn: ImageView
    private lateinit var stopButton: ImageView
    private lateinit var contextPctText: TextView
    private lateinit var loadingOverlay: LinearLayout
    private var transcriptLoaded: Boolean = false

    private var sessionId: String = ""
    private var workDir: String = ""
    private var currentMode: String = "bypassAll"
    private val permissionQueue = ArrayDeque<RcMessage.PermissionRequest>()
    private var currentPermissionRequest: RcMessage.PermissionRequest? = null
    private lateinit var actionButtonsContainer: GridLayout
    private var pendingUserInputRequestId: String? = null
    private var receiversRegistered = false
    private var sessionEnded = false
    private var activeStreamingRequestId: String? = null
    private var isUserNearBottom = true

    @Volatile private var voiceRecorder: ChatVoiceRecorder? = null
    private var lastSavedDraft: String = ""
    private val draftRunnable = object : Runnable {
        override fun run() {
            val text = inputField.text.toString()
            if (text != lastSavedDraft) {
                lastSavedDraft = text
                DraftStore.set(this@RemoteControlActivity, sessionId, text)
            }
            thinkingHandler.postDelayed(this, 100)
        }
    }
    private var slashPopup: ScrollView? = null
    private var slashPopupContainer: LinearLayout? = null

    // Session state for slash commands
    var currentModel: String = "claude-opus-4-6[1m]"
        private set
    var currentEffort: String = "max"
        private set
    var isFastMode: Boolean = false
    private var contextPctValue: Int = 0
    private var totalCostUsd: Double = 0.0

    fun getSessionId(): String = sessionId
    fun getWorkDir(): String = workDir
    fun getContextPct(): Int = contextPctValue

    private var thinkingView: TextView? = null
    private var thinkingStartTime: Long = 0
    private val thinkingHandler = Handler(Looper.getMainLooper())

    private sealed class RcUiStatus {
        object Idle : RcUiStatus()
        /**
         * Outgoing rc_user_message in flight or queued for retry.
         *  - attempt: 1 for the first try; increments on each retry
         *  - nextRetryAtMs: epoch-ms when the next retry will fire. 0 means
         *    we're optimistically waiting for an ack (no failure yet);
         *    >0 means the previous attempt failed and we're showing a
         *    countdown to the next try.
         */
        data class Sending(val attempt: Int = 1, val nextRetryAtMs: Long = 0L) : RcUiStatus()
        data class Thinking(val startedAtMs: Long) : RcUiStatus()
        data class Thought(val elapsedMs: Long) : RcUiStatus()
    }
    private var rcUiStatus: RcUiStatus = RcUiStatus.Idle
    // Tracks when we entered the Sending state; used to enforce a minimum
    // visible window (SENDING_MIN_VISIBLE_MS) so transitions don't blink past
    // the user when the orchestrator answers in <300ms.
    private var sendingStartedAtMs: Long = 0L
    private val SENDING_MIN_VISIBLE_MS = 800L

    private val thinkingRunnable = object : Runnable {
        override fun run() {
            renderRcStatus()
            thinkingHandler.postDelayed(this, 1000)
        }
    }

    // Re-binds any in-flight agent rows once per second so the elapsed-time
    // segment ticks during the call. Auto-stops itself once no agent row is
    // still in calling/running state.
    @Volatile
    private var agentTickActive: Boolean = false
    private val agentTickRunnable = object : Runnable {
        override fun run() {
            val stillInFlight = adapter.tickInFlightAgentRows()
            if (stillInFlight) {
                thinkingHandler.postDelayed(this, 1000)
            } else {
                agentTickActive = false
            }
        }
    }
    private fun ensureAgentTicker() {
        if (agentTickActive) return
        agentTickActive = true
        thinkingHandler.removeCallbacks(agentTickRunnable)
        thinkingHandler.postDelayed(agentTickRunnable, 1000)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** Render the "Sending..." indicator. If a retry has been armed
     *  (nextRetryAtMs in the future), include the attempt count and a
     *  one-decimal-second countdown to the next attempt so the user can
     *  see why their message is still waiting. Plain "Sending..." for
     *  the optimistic in-flight state (attempt 1, no retry armed). */
    private fun renderSendingText(st: RcUiStatus.Sending): String {
        val nowMs = System.currentTimeMillis()
        val remainingMs = st.nextRetryAtMs - nowMs
        val retryArmed = st.nextRetryAtMs > 0L && remainingMs > 0L
        if (!retryArmed) return "Sending..."
        val secs = ((remainingMs + 999) / 1000).coerceAtLeast(1)
        return "Sending — retry ${st.attempt} in ${secs}s"
    }

    private fun renderRcStatus() {
        val v = thinkingView ?: return
        when (val st = rcUiStatus) {
            is RcUiStatus.Idle -> { v.visibility = View.GONE }
            is RcUiStatus.Sending -> {
                v.visibility = View.VISIBLE
                v.text = renderSendingText(st)
            }
            is RcUiStatus.Thinking -> {
                v.visibility = View.VISIBLE
                val elapsed = System.currentTimeMillis() - st.startedAtMs
                v.text = "Thinking... ${formatElapsed(elapsed)}"
            }
            is RcUiStatus.Thought -> {
                v.visibility = View.VISIBLE
                v.text = "Thought for ${formatElapsed(st.elapsedMs)}"
            }
        }
    }

    private fun setRcStatus(next: RcUiStatus) {
        // Enforce a minimum visible window for Sending so the user actually
        // sees it. If we're transitioning Sending -> Thinking before
        // SENDING_MIN_VISIBLE_MS has elapsed, defer the transition. Don't
        // defer transitions to Thought/Idle (those are terminal and the
        // server has already finished -- holding Sending would be a lie).
        if (rcUiStatus is RcUiStatus.Sending && next is RcUiStatus.Thinking) {
            val visibleMs = System.currentTimeMillis() - sendingStartedAtMs
            if (visibleMs < SENDING_MIN_VISIBLE_MS) {
                val remaining = SENDING_MIN_VISIBLE_MS - visibleMs
                thinkingHandler.postDelayed({
                    // Re-enter only if we're still in Sending; another path
                    // may have already advanced the state.
                    if (rcUiStatus is RcUiStatus.Sending) setRcStatus(next)
                }, remaining)
                return
            }
        }
        // The state machine guarantee is Sending -> Thinking -> Thought. If a
        // direct Sending -> Thought is requested (very fast turn, or rc_thinking
        // was never emitted), insert an observable Thinking phase first so UI
        // tests and users always see the Thinking label.
        if (rcUiStatus is RcUiStatus.Sending && next is RcUiStatus.Thought) {
            val elapsed = (next as RcUiStatus.Thought).elapsedMs
            // Synchronously commit Thinking with backdated start so renderRcStatus
            // shows "Thinking... <elapsed>" immediately.
            rcUiStatus = RcUiStatus.Thinking(System.currentTimeMillis() - elapsed)
            thinkingHandler.removeCallbacks(thinkingRunnable)
            renderRcStatus()
            // Defer the actual Thought transition by 250ms so the harness polling
            // loop (and the human eye) can observe Thinking before it flips.
            thinkingHandler.postDelayed({
                rcUiStatus = next
                thinkingHandler.removeCallbacks(thinkingRunnable)
                renderRcStatus()
            }, 250L)
            return
        }
        rcUiStatus = next
        if (next is RcUiStatus.Sending) sendingStartedAtMs = System.currentTimeMillis()
        thinkingHandler.removeCallbacks(thinkingRunnable)
        when (next) {
            is RcUiStatus.Sending, is RcUiStatus.Thinking -> {
                stopButton.visibility = View.VISIBLE
                renderRcStatus()
                thinkingHandler.postDelayed(thinkingRunnable, 1000)
            }
            else -> renderRcStatus()
        }
    }

    // --- Broadcast Receivers ---

    private val rcMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                val text = obj.optString("text", "")
                val isFinal = obj.optBoolean("isFinal", false)
                val requestId = obj.optString("requestId", "")
                val contextPct = obj.optInt("contextPct", -1)
                val costUsd = obj.optDouble("costUsd", -1.0)

                runOnUiThread {
                    if (isFinal) {
                        // Fallback: if no rc_thinking_end arrived, synthesize Thought from local timer.
                        if (rcUiStatus is RcUiStatus.Thinking || rcUiStatus is RcUiStatus.Sending) {
                            val elapsed = when (val st = rcUiStatus) {
                                is RcUiStatus.Thinking -> System.currentTimeMillis() - st.startedAtMs
                                else -> System.currentTimeMillis() - thinkingStartTime
                            }
                            // Ensure the state machine never skips Thinking. If we're going
                            // straight from Sending to Thought (very fast turn), flash Thinking
                            // first so the indicator is observably Sending -> Thinking -> Thought.
                            if (rcUiStatus is RcUiStatus.Sending) {
                                setRcStatus(RcUiStatus.Thinking(System.currentTimeMillis() - elapsed))
                            }
                            setRcStatus(RcUiStatus.Thought(elapsed))
                        }
                        hideThinking()
                    } else {
                        // First partial -> transition Sending -> Thinking too (matches rc_thinking).
                        if (rcUiStatus is RcUiStatus.Sending) {
                            setRcStatus(RcUiStatus.Thinking(System.currentTimeMillis()))
                        }
                    }
                    if (contextPct >= 0) updateContextUsage(contextPct, costUsd)
                    if (!isFinal && requestId.isNotEmpty()) {
                        // Streaming update
                        if (activeStreamingRequestId == requestId) {
                            // Update existing streaming message
                            adapter.updateStreamingText(requestId, text)
                        } else {
                            // Start new streaming message
                            activeStreamingRequestId = requestId
                            adapter.addMessage(RcMessage.TextMessage(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                role = RcMessage.Role.ASSISTANT,
                                text = text,
                                requestId = requestId
                            ))
                        }
                    } else {
                        // Final message (completion signal)
                        if (activeStreamingRequestId == requestId) {
                            // Update the streaming message with final text
                            if (text.isNotEmpty()) adapter.updateStreamingText(requestId, text)
                            activeStreamingRequestId = null
                        } else if (text.isNotEmpty()) {
                            // Only add a bubble if there's actual text
                            adapter.addMessage(RcMessage.TextMessage(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                role = RcMessage.Role.ASSISTANT,
                                text = text,
                                requestId = requestId
                            ))
                        }
                    }
                    scrollToBottom()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse RC message: ${e.message}")
            }
        }
    }

    private val rcPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                val toolArgsVal = if (obj.has("toolArgs") && !obj.isNull("toolArgs")) {
                    val ta = obj.get("toolArgs")
                    if (ta is String) ta else ta.toString()
                } else ""
                val request = RcMessage.PermissionRequest(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolName = obj.getString("toolName"),
                    toolArgs = toolArgsVal,
                    requestId = obj.getString("requestId"),
                    description = if (obj.has("description") && !obj.isNull("description")) obj.optString("description", "") else ""
                )
                runOnUiThread {
                    // Deduplicate: the orchestrator re-sends pending permissions
                    // on every rc_transcript_request (activity open/resume). Skip
                    // if we already have this requestId queued or currently showing.
                    val reqId = request.requestId
                    val alreadyQueued = permissionQueue.any { it.requestId == reqId }
                    val alreadyCurrent = currentPermissionRequest?.requestId == reqId
                    val alreadyInAdapter = adapter.getMessages().any {
                        it is RcMessage.PermissionRequest && it.requestId == reqId
                    }
                    if (alreadyQueued || alreadyCurrent || alreadyInAdapter) return@runOnUiThread

                    adapter.addMessage(request)
                    scrollToBottom()
                    permissionQueue.addLast(request)
                    showNextPermissionPrompt()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse RC permission request: ${e.message}")
            }
        }
    }

    private val rcToolStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                val toolName = obj.getString("toolName")
                val status = obj.getString("status")
                val result = if (obj.has("result") && !obj.isNull("result")) obj.optString("result", null) else null
                val toolArgs = if (obj.has("toolArgs") && !obj.isNull("toolArgs")) {
                    val ta = obj.get("toolArgs")
                    if (ta is String) ta else ta.toString()
                } else null
                val toolCallId = if (obj.has("toolCallId") && !obj.isNull("toolCallId")) obj.optString("toolCallId", null) else null
                val ctxPct = if (obj.has("contextPct")) obj.optInt("contextPct", -1) else -1
                val isAgent = obj.optBoolean("isAgent", false)
                val agentName = if (obj.has("agentName")) obj.optString("agentName", null) else null
                val agentTask = if (obj.has("agentTask")) obj.optString("agentTask", null) else null
                val agentToolCount = if (obj.has("agentToolCount")) obj.optInt("agentToolCount", -1).takeIf { it >= 0 } else null
                val agentTokens = if (obj.has("agentTokens")) obj.optLong("agentTokens", -1L).takeIf { it >= 0 } else null
                val agentElapsedMs = if (obj.has("agentElapsedMs")) obj.optLong("agentElapsedMs", -1L).takeIf { it >= 0 } else null
                runOnUiThread {
                    hideThinkingAnimation()
                    if (ctxPct >= 0) updateContextUsage(ctxPct, -1.0)
                    // A tool status event is concrete proof the orchestrator is actively
                    // working on the turn. If we're still in Sending (e.g. rc_thinking
                    // hasn't been emitted yet for tool-first turns), advance to Thinking
                    // so the indicator state machine never skips the Thinking phase.
                    if (rcUiStatus is RcUiStatus.Sending) {
                        setRcStatus(RcUiStatus.Thinking(sendingStartedAtMs))
                    }
                    // Only show tool status for completion/error, not calling (permission card handles that).
                    // Exception: agent tool calls (Task / sub-agents) do not produce a permission card,
                    // so render them on "calling" too — otherwise the user would see no row at all.
                    if (status == "calling" && !isAgent) return@runOnUiThread
                    adapter.upsertToolStatus(toolName, status, result, toolArgs, toolCallId, isAgent, agentName, agentTask, agentToolCount, agentTokens, agentElapsedMs)
                    if (isAgent && (status == "calling" || status == "running")) {
                        ensureAgentTicker()
                    }
                    scrollToBottom()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse RC tool status: ${e.message}")
            }
        }
    }

    private val rcPlanUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                val entering = obj.getBoolean("entering")
                val planContent = obj.optString("planContent", null)
                runOnUiThread {
                    hideThinkingAnimation()
                    adapter.addMessage(RcMessage.PlanUpdate(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        entering = entering,
                        planContent = planContent
                    ))
                    scrollToBottom()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse RC plan update: ${e.message}")
            }
        }
    }

    private val rcAgentStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                runOnUiThread {
                    hideThinkingAnimation()
                    adapter.addMessage(RcMessage.AgentStatus(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        agentId = obj.getString("agentId"),
                        name = obj.getString("name"),
                        status = obj.getString("status"),
                        depth = obj.optInt("depth", 0)
                    ))
                    scrollToBottom()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse RC agent status: ${e.message}")
            }
        }
    }

    private val rcThinkingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val text = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            val startedAt = intent.getLongExtra("startedAt", 0L)
            runOnUiThread {
                if (rcUiStatus !is RcUiStatus.Thinking) {
                    val effectiveStart = if (startedAt > 0L) startedAt else System.currentTimeMillis()
                    setRcStatus(RcUiStatus.Thinking(effectiveStart))
                }
                if (text.isNotEmpty()) {
                    adapter.updateThinking(text)
                    scrollToBottom()
                }
            }
        }
    }

    private val rcThinkingEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val elapsedStr = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            val serverElapsedMs = elapsedStr.toLongOrNull() ?: 0L
            runOnUiThread {
                // Use the greater of server elapsed and local elapsed so we never
                // show a shorter time than what the user actually waited. The server
                // only measures from first assistant event to result; the local timer
                // starts when the user pressed send (more accurate from UX perspective).
                val localElapsedMs = when (val st = rcUiStatus) {
                    is RcUiStatus.Thinking -> System.currentTimeMillis() - st.startedAtMs
                    is RcUiStatus.Sending -> System.currentTimeMillis() - thinkingStartTime
                    is RcUiStatus.Thought -> st.elapsedMs
                    else -> 0L
                }
                val elapsedMs = maxOf(serverElapsedMs, localElapsedMs)
                setRcStatus(RcUiStatus.Thought(elapsedMs))
            }
        }
    }

    private val rcModeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val newMode = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            runOnUiThread {
                hideThinkingAnimation()
                currentMode = newMode
                updateModeSelector()
                adapter.addMessage(RcMessage.ModeChange(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    newMode = newMode
                ))
                scrollToBottom()
            }
        }
    }

    private val rcUserInputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                val prompt = obj.getString("prompt")
                val requestId = obj.getString("requestId")
                runOnUiThread {
                    pendingUserInputRequestId = requestId
                    inputField.hint = prompt
                    inputField.requestFocus()
                    // Highlight input bar to indicate pending user input
                    inputField.background?.colorFilter = PorterDuffColorFilter(
                        Color.parseColor("#33FFEB3B"), PorterDuff.Mode.SRC_ATOP
                    )
                    sendButton.setColorFilter(Color.parseColor("#FFEB3B"))
                    micButton.setColorFilter(Color.parseColor("#FFEB3B"))
                    adapter.addMessage(RcMessage.UserInputRequest(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        prompt = prompt,
                        requestId = requestId
                    ))
                    scrollToBottom()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse RC user input request: ${e.message}")
            }
        }
    }

    private val rcErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                val errorText = obj.optString("error", "Unknown error")
                val source = obj.optString("source", "system")
                runOnUiThread {
                    setRcStatus(RcUiStatus.Idle)
                    hideThinking()
                    showError(errorText, source)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse RC error: ${e.message}")
            }
        }
    }

    /** Lifecycle of the most recent outgoing rc_user_message. The
     *  OrchestratorClient retries with capped backoff when delivery fails;
     *  this receiver picks up each transition (sending / queued / retrying /
     *  delivered / failed) and updates the chat-bottom indicator so the user
     *  sees attempt count + countdown to the next try instead of a silent
     *  "Sending..." that never advances. */
    private val rcUserMsgStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val status = intent.getStringExtra(ListenerService.EXTRA_RC_USER_MSG_STATUS) ?: return
            val attempt = intent.getIntExtra(ListenerService.EXTRA_RC_USER_MSG_ATTEMPT, 1)
            val nextRetryAt = intent.getLongExtra(ListenerService.EXTRA_RC_USER_MSG_NEXT_RETRY_AT, 0L)
            runOnUiThread {
                when (status) {
                    "delivered" -> {
                        // Server acked; transition to Thinking so the user
                        // sees the AI is now processing. Some sessions never
                        // emit a thinking event at all (e.g. when the AI's
                        // first action is a tool call) so we self-promote.
                        if (rcUiStatus is RcUiStatus.Sending) {
                            // Carry over the original send time so the thinking
                            // timer doesn't reset to 0 on delivery ack.
                            setRcStatus(RcUiStatus.Thinking(sendingStartedAtMs))
                        }
                    }
                    "failed" -> {
                        setRcStatus(RcUiStatus.Idle)
                        hideThinking()
                        showError("Couldn't deliver message after $attempt attempts", "phone")
                    }
                    "sending", "queued", "retrying" -> {
                        // Show retry countdown only when a retry has been
                        // armed (queued/retrying). Plain "Sending..." for
                        // the optimistic in-flight state.
                        val nextAt = if (status == "sending") 0L else nextRetryAt
                        setRcStatus(RcUiStatus.Sending(attempt = attempt, nextRetryAtMs = nextAt))
                    }
                }
            }
        }
    }

    private val rcSessionStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            runOnUiThread {
                // Session revived or reconnected
                sessionEnded = false
                endedBanner.visibility = View.GONE
                val dot = statusDot.background as? GradientDrawable
                dot?.setColor(ContextCompat.getColor(this@RemoteControlActivity, R.color.gbx_green))
            }
        }
    }

    private val rcSessionEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            runOnUiThread {
                hideThinking()
                sessionEnded = true
                endedBanner.visibility = View.VISIBLE
                endedBanner.text = "Session ended - send a message to revive"
                val dot = statusDot.background as? GradientDrawable
                dot?.setColor(ContextCompat.getColor(this@RemoteControlActivity, R.color.gbx_red))
                // Keep input enabled so user can revive the session
            }
        }
    }

    private val rcTranscriptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!matchesSession(intent)) return
            val sid = intent.getStringExtra(ListenerService.EXTRA_RC_SESSION_ID) ?: return
            // Transcript JSON is fetched from an in-process cache rather than
            // ridden in an Intent extra: payloads >1MB blow the Binder
            // transaction limit and the OS kills us with "Can't deliver
            // broadcast". Cache is populated by ListenerService.onRcTranscript.
            val transcript = ListenerService.rcTranscriptCache[sid] ?: return
            // Parse off the main thread -- O(n^2) supersession check on a
            // 100KB+ string can otherwise blow the broadcast deadline.
            Thread({
                try { parseAndLoadTranscript(transcript) }
                catch (e: Exception) { LogCollector.e(TAG, "Transcript parse failed: ${e.message}") }
            }, "RcTranscriptParser").start()
        }
    }

    private fun hideLoadingOverlay() {
        if (::loadingOverlay.isInitialized && loadingOverlay.visibility != View.GONE) {
            loadingOverlay.visibility = View.GONE
        }
    }

    private fun loadTranscriptFromOrchestrator() {
        if (sessionId.isEmpty()) {
            runOnUiThread { hideLoadingOverlay() }
            return
        }
        val wsUrl = AppConfig.getOrchestratorUrl(this)
        val rcApiKey = AppConfig.getApiKey(this)
        val rcDeviceId = AppConfig.getDeviceId(this)
        RemoteSessionClient(wsUrl, rcApiKey, rcDeviceId).getTranscript(sessionId) { result ->
            result.onSuccess { transcriptJson ->
                parseAndLoadTranscript(transcriptJson)
                transcriptLoaded = true
                runOnUiThread { hideLoadingOverlay() }
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Failed to fetch transcript: ${err.message}")
                runOnUiThread { hideLoadingOverlay() }
            }
        }
        // Also request pending permissions via WS. The HTTP transcript marks
        // permissions as non-pending (historical). The WS rc_transcript_request
        // triggers the orchestrator to re-send any LIVE pending permissions
        // (e.g. ExitPlanMode approval that arrived while the UI was closed).
        sendBroadcast(Intent(ListenerService.ACTION_RC_TRANSCRIPT_REQ).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
        })
    }

    private fun parseAndLoadTranscript(transcriptJson: String) {
        try {
            val arr = JSONArray(transcriptJson)
            val messages = mutableListOf<RcMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = obj.optString("type", "text")
                // Stored transcript entries have { type: "rc_message", data: { ... } }
                val data = obj.optJSONObject("data")
                when (type) {
                    "text" -> {
                        val role = when (obj.optString("role", "assistant")) {
                            "user" -> RcMessage.Role.USER
                            "assistant" -> RcMessage.Role.ASSISTANT
                            else -> RcMessage.Role.SYSTEM
                        }
                        messages.add(RcMessage.TextMessage(
                            id = UUID.randomUUID().toString(),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            role = role,
                            text = obj.optString("text", ""),
                            requestId = obj.optString("requestId", "")
                        ))
                    }
                    "tool" -> {
                        messages.add(RcMessage.ToolStatus(
                            id = UUID.randomUUID().toString(),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            toolName = obj.optString("toolName", ""),
                            status = obj.optString("status", "complete")
                        ))
                    }
                    "rc_message" -> {
                        if (data != null) {
                            val text = data.optString("text", "")
                            val isFinal = data.optBoolean("isFinal", false)
                            // Skip final markers (empty text) and non-final streaming updates
                            // Only keep messages whose text isn't a prefix of any later rc_message
                            if (text.isNotEmpty()) {
                                var isSuperseded = false
                                for (j in (i + 1) until arr.length()) {
                                    val nextObj = arr.getJSONObject(j)
                                    if (nextObj.optString("type") == "rc_message") {
                                        val nextData = nextObj.optJSONObject("data")
                                        val nextText = nextData?.optString("text", "") ?: ""
                                        if (nextText.isNotEmpty() && nextText.length >= text.length) {
                                            isSuperseded = true
                                        }
                                        break
                                    }
                                }
                                if (!isSuperseded) {
                                    messages.add(RcMessage.TextMessage(
                                        id = UUID.randomUUID().toString(),
                                        timestamp = System.currentTimeMillis(),
                                        role = RcMessage.Role.ASSISTANT,
                                        text = text,
                                        requestId = data.optString("requestId", "")
                                    ))
                                }
                            }
                        }
                    }
                    "rc_permission_request" -> {
                        if (data != null) {
                            val toolArgsVal = if (data.has("toolArgs") && !data.isNull("toolArgs")) {
                                val ta = data.get("toolArgs")
                                if (ta is String) ta else ta.toString()
                            } else ""
                            messages.add(RcMessage.PermissionRequest(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                toolName = data.optString("toolName", ""),
                                toolArgs = toolArgsVal,
                                requestId = data.optString("requestId", ""),
                                description = if (data.has("description") && !data.isNull("description")) data.optString("description", "") else "",
                                // Transcript permissions are historical -- always
                                // non-pending. Only LIVE re-sent permissions from the
                                // orchestrator (via rcPermissionReceiver) are pending,
                                // because those confirm the CLI is still waiting.
                                pending = false,
                                approved = false
                            ))
                        }
                    }
                    "rc_permission_resolved" -> {
                        // Will be used in merge step below
                    }
                    "rc_tool_status" -> {
                        if (data != null) {
                            val status = data.optString("status", "complete")
                            if (status != "calling") {
                                val toolArgsVal = if (data.has("toolArgs") && !data.isNull("toolArgs")) {
                                    val ta = data.get("toolArgs")
                                    if (ta is String) ta else ta.toString()
                                } else null
                                messages.add(RcMessage.ToolStatus(
                                    id = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    toolName = data.optString("toolName", ""),
                                    status = status,
                                    result = if (data.has("result") && !data.isNull("result")) data.optString("result", null) else null,
                                    toolArgs = toolArgsVal,
                                    isAgent = data.optBoolean("isAgent", false),
                                    agentName = if (data.has("agentName") && !data.isNull("agentName")) data.optString("agentName", null) else null,
                                    agentTask = if (data.has("agentTask") && !data.isNull("agentTask")) data.optString("agentTask", null) else null
                                ))
                            }
                        }
                    }
                    "user_message" -> {
                        if (data != null) {
                            val text = data.optString("text", "")
                            if (text.isNotEmpty()) {
                                messages.add(RcMessage.TextMessage(
                                    id = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    role = RcMessage.Role.USER,
                                    text = text,
                                    requestId = ""
                                ))
                            }
                        }
                    }
                }
            }
            // Deduplicate: remove consecutive assistant messages with same text
            val deduped = mutableListOf<RcMessage>()
            for (msg in messages) {
                if (msg is RcMessage.TextMessage && msg.role == RcMessage.Role.ASSISTANT && deduped.isNotEmpty()) {
                    val prev = deduped.lastOrNull()
                    if (prev is RcMessage.TextMessage && prev.role == RcMessage.Role.ASSISTANT && prev.text == msg.text) {
                        continue
                    }
                }
                deduped.add(msg)
            }
            // Resolve permission states using rc_permission_resolved entries from transcript
            val resolvedPermissions = mutableMapOf<String, Boolean>() // requestId -> approved
            for (i in 0 until arr.length()) {
                val obj2 = arr.getJSONObject(i)
                if (obj2.optString("type") == "rc_permission_resolved") {
                    val d = obj2.optJSONObject("data")
                    if (d != null) {
                        resolvedPermissions[d.optString("requestId", "")] = d.optBoolean("approved", false)
                    }
                }
            }
            // Apply resolved state to transcript permission requests.
            // All transcript permissions start as pending=false (historical).
            // This loop just sets the approved flag for resolved ones.
            for (msg in deduped) {
                if (msg is RcMessage.PermissionRequest) {
                    val resolved = resolvedPermissions[msg.requestId]
                    if (resolved != null) {
                        msg.approved = resolved
                    }
                    // else: stays pending=true from transcript parsing
                }
            }
            // Merge ToolStatus "complete" results into approved PermissionRequest cards
            val merged = mutableListOf<RcMessage>()
            val permsByRequestId = mutableMapOf<String, RcMessage.PermissionRequest>()
            for (msg in deduped) {
                if (msg is RcMessage.PermissionRequest) {
                    permsByRequestId[msg.requestId] = msg
                }
            }
            // Match tool results to their permission by chronological proximity
            val consumedResults = mutableSetOf<RcMessage>()
            val approvedPermQueue = mutableMapOf<String, MutableList<RcMessage.PermissionRequest>>()
            for (msg in deduped) {
                if (msg is RcMessage.PermissionRequest && msg.approved) {
                    approvedPermQueue.getOrPut(msg.toolName) { mutableListOf() }.add(msg)
                } else if (msg is RcMessage.ToolStatus && msg.status == "complete") {
                    val queue = approvedPermQueue[msg.toolName]
                    if (queue != null && queue.isNotEmpty()) {
                        val perm = queue.removeFirst()
                        perm.result = msg.result
                        consumedResults.add(msg)
                    }
                }
            }
            // Build merged list, skipping consumed ToolStatus entries
            for (msg in deduped) {
                if (msg is RcMessage.ToolStatus && consumedResults.contains(msg)) {
                    // Skip - merged into PermissionRequest
                } else {
                    merged.add(msg)
                }
            }
            runOnUiThread {
                android.util.Log.d("RcTranscript", "fetched=${arr.length()} merged=${merged.size} adapterBefore=${adapter.getMessages().size}")
                // Only replace if transcript has real content. Never clear an existing
                // adapter to empty -- a subsequent WS catch-up may yet populate it,
                // and live events will fill it as they arrive.
                if (merged.isNotEmpty()) {
                    adapter.submitMessages(merged)
                    scrollToBottom()
                }
                // Pending permissions are NOT queued from the transcript --
                // only live re-sends from the orchestrator (via rcPermissionReceiver)
                // are authoritative. The orchestrator re-sends them after the
                // transcript if the CLI is still waiting.
                transcriptLoaded = true
                hideLoadingOverlay()
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse RC transcript: ${e.message}")
        }
    }

    // --- Lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        workDir = intent.getStringExtra(EXTRA_WORK_DIR) ?: ""
        intent.getStringExtra(EXTRA_PERMISSION_MODE)?.let { mode ->
            currentMode = mode
        }

        recyclerView = findViewById(R.id.rcMessageList)
        inputField = findViewById(R.id.rcInput)
        sendButton = findViewById(R.id.rcSendButton)
        micButton = findViewById(R.id.rcMicButton)
        recordingOverlay = findViewById(R.id.rcRecordingOverlay)
        titleText = findViewById(R.id.rcTitle)
        statusDot = findViewById(R.id.rcStatusDot)
        modeSelector = findViewById(R.id.rcModeSelector)
        endedBanner = findViewById(R.id.rcEndedBanner)
        stopButton = findViewById(R.id.rcStopButton)
        actionButtonsContainer = findViewById(R.id.rcActionButtons)
        thinkingView = findViewById(R.id.rcThinkingView)
        contextPctText = findViewById(R.id.rcContextPct)
        scrollToBottomBtn = findViewById(R.id.rcScrollToBottom)
        scrollToBottomBtn.setOnClickListener {
            isUserNearBottom = true
            scrollToBottomBtn.visibility = View.GONE
            val count = adapter.itemCount
            if (count > 0) recyclerView.scrollToPosition(count - 1)
        }

        // Reseed contextPct chip from last known value for this session so the
        // toolbar isn't blank on re-entry before any live rc_message arrives.
        if (sessionId.isNotEmpty()) {
            val seededPct = com.repository.listener.config.RcSessionMetaStore.getContextPct(this, sessionId)
            if (seededPct != null) {
                val seededCost = com.repository.listener.config.RcSessionMetaStore.getCostUsd(this, sessionId)
                updateContextUsage(seededPct, seededCost)
            }
        }

        // Title = basename of workDir
        val dirName = workDir.substringAfterLast('/').ifEmpty {
            workDir.substringAfterLast('\\')
        }
        titleText.text = "> $dirName"

        // Setup RecyclerView
        adapter = RcDetailAdapter()
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        // Disable change animations so the 1-Hz agent ticker doesn't trigger
        // a fade-in/fade-out blink on the agent row. Insert/remove animations
        // remain on (we only suppress the change variant).
        (recyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                val totalItems = lm.itemCount
                isUserNearBottom = lastVisible >= totalItems - 3
                scrollToBottomBtn.visibility = if (isUserNearBottom) View.GONE else View.VISIBLE
            }
        })

        // Back button
        findViewById<ImageView>(R.id.rcBtnBack).setOnClickListener { finish() }

        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        // Stop/interrupt button -- optimistic client-side clear; orchestrator's
        // rc_thinking_end will arrive later and refresh the elapsed (idempotent).
        stopButton.setOnClickListener {
            val elapsed = when (val st = rcUiStatus) {
                is RcUiStatus.Thinking -> System.currentTimeMillis() - st.startedAtMs
                is RcUiStatus.Sending -> System.currentTimeMillis() - thinkingStartTime
                else -> 0L
            }
            setRcStatus(RcUiStatus.Thought(elapsed))
            sendBroadcast(Intent(ListenerService.ACTION_RC_INTERRUPT).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
            })
            stopButton.visibility = View.GONE
        }

        // Send button
        sendButton.setOnClickListener { sendMessage() }

        // Mic button voice recording
        setupVoiceRecordButton()

        // Send button animation + slash command autocomplete
        inputField.addTextChangedListener(object : TextWatcher {
            private var wasEmpty = true
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val isEmpty = s.isNullOrBlank()
                if (wasEmpty && !isEmpty) {
                    animateSendButton(show = true)
                    animateMicButton(show = false)
                } else if (!wasEmpty && isEmpty) {
                    animateSendButton(show = false)
                    animateMicButton(show = true)
                }
                wasEmpty = isEmpty

                // Slash command autocomplete from registry
                val text = s?.toString() ?: ""
                val allHandlers = SlashCommandRegistry.all()
                if (text.startsWith("/") && !text.contains(" ") && allHandlers.isNotEmpty()) {
                    val query = text.substring(1).lowercase()
                    if (query.isEmpty()) {
                        showSlashPopupFromHandlers(allHandlers)
                    } else {
                        val prefixMatches = allHandlers.filter { it.name.lowercase().startsWith(query) }
                        val containsMatches = allHandlers.filter {
                            !it.name.lowercase().startsWith(query) && it.name.lowercase().contains(query)
                        }
                        val descMatches = allHandlers.filter {
                            !it.name.lowercase().contains(query) && it.description.lowercase().contains(query)
                        }
                        val filtered = prefixMatches + containsMatches + descMatches
                        if (filtered.isNotEmpty()) {
                            showSlashPopupFromHandlers(filtered)
                        } else {
                            hideSlashPopup()
                        }
                    }
                } else {
                    hideSlashPopup()
                }
            }
        })

        // Initialize slash command registry
        SlashCommandRegistry.init()

        // Build slash command popup (hidden initially)
        buildSlashPopup()

        // Mode selector popup
        modeSelector.setOnClickListener { showModePopup() }
        updateModeSelector()

        // Status dot -- set based on initial status
        val initialStatus = intent.getStringExtra(EXTRA_SESSION_STATUS) ?: "active"
        if (initialStatus == "ended") {
            sessionEnded = true
            (statusDot.background as? GradientDrawable)?.setColor(
                ContextCompat.getColor(this, R.color.gbx_red)
            )
            endedBanner.visibility = View.VISIBLE
            endedBanner.text = "Session ended - send a message to revive"
        } else {
            (statusDot.background as? GradientDrawable)?.setColor(
                ContextCompat.getColor(this, R.color.gbx_green)
            )
        }

        // Loading overlay: visible until first transcript fetch resolves.
        loadingOverlay = findViewById(R.id.rcLoadingOverlay)
        loadingOverlay.visibility = View.VISIBLE

        // Register broadcast receivers BEFORE fetching transcript so we don't miss
        // ACTION_RC_TRANSCRIPT broadcasts from the service when WS subscribes to the session.
        registerBroadcastReceivers()

        // Fetch transcript via HTTP (bypasses WS/broadcast IPC size limits)
        loadTranscriptFromOrchestrator()

        // Reconcile UI session status with server truth (Intent extra may be stale)
        refreshSessionStatus()

        // Restore draft
        val draft = DraftStore.get(this, sessionId)
        if (draft.isNotEmpty()) {
            inputField.setText(draft)
            inputField.setSelection(draft.length)
            lastSavedDraft = draft
        }
    }

    override fun onResume() {
        super.onResume()
        registerBroadcastReceivers()
        refreshSessionStatus()
        thinkingHandler.post(draftRunnable)

        // Re-fetch transcript to catch messages that arrived while receivers
        // were unregistered (onPause -> onResume gap). Broadcasts are fire-and-
        // forget; anything missed during pause is lost without this catch-up.
        if (transcriptLoaded) {
            loadTranscriptFromOrchestrator()
        }

        // Reconcile any pending rc_user_message status that fired while the
        // activity was paused (or before this instance existed). The service
        // caches the latest non-terminal status per session and re-broadcasts
        // it -- if there's nothing pending, it sends a synthetic "delivered"
        // so we exit any stale Sending indicator.
        if (sessionId.isNotEmpty()) {
            sendBroadcast(Intent(ListenerService.ACTION_RC_USER_MSG_STATUS_QUERY).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
            })
        }

        // Restore turn UI if the session is still thinking (user left and came back mid-turn).
        // stopButton + sendButton visibility are handled by setRcStatus().
        val thinkingStartedAt = ListenerService.thinkingRcStartTimes[sessionId]
        if (rcUiStatus is RcUiStatus.Idle && thinkingStartedAt != null) {
            animateMicButton(show = false)
            setRcStatus(RcUiStatus.Thinking(thinkingStartedAt))
        }
    }

    private fun refreshSessionStatus() {
        val wsUrl = AppConfig.getOrchestratorUrl(this)
        val rcApiKey = AppConfig.getApiKey(this)
        val rcDeviceId = AppConfig.getDeviceId(this)
        RemoteSessionClient(wsUrl, rcApiKey, rcDeviceId).listSessions { result ->
            result.onSuccess { sessions ->
                val live = sessions.firstOrNull { it.sessionId == sessionId } ?: return@onSuccess
                if (live.alive && sessionEnded) {
                    runOnUiThread {
                        sessionEnded = false
                        endedBanner.visibility = View.GONE
                        (statusDot.background as? GradientDrawable)?.setColor(
                            ContextCompat.getColor(this@RemoteControlActivity, R.color.gbx_green)
                        )
                    }
                }
                // Restore permission mode from server (orchestrator stores
                // canonical names; map to phone short names for the UI).
                val serverMode = live.permissionMode
                if (serverMode != null) {
                    val phoneMode = when (serverMode) {
                        "ask_on_potentially_safe" -> "bypassAll"
                        "acceptAll" -> "acceptEdits"
                        "bypassAll" -> "bypassAll"
                        "plan" -> "plan"
                        else -> serverMode
                    }
                    runOnUiThread {
                        currentMode = phoneMode
                        updateModeSelector()
                    }
                }
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "refreshSessionStatus failed: ${err.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        thinkingHandler.removeCallbacks(draftRunnable)
        // Final save on pause
        val text = inputField.text.toString()
        if (text != lastSavedDraft) {
            DraftStore.set(this, sessionId, text)
        }
        unregisterBroadcastReceivers()
        thinkingHandler.removeCallbacks(thinkingRunnable)
        hideSlashPopup()
        val recorder = voiceRecorder
        voiceRecorder = null
        recorder?.cancelRecording()
        micButton.cancelRecording()
        recordingOverlay.reset()
        restoreRcInputBar()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "Mic permission granted, swipe up to record", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // --- Private methods ---

    private fun matchesSession(intent: Intent): Boolean {
        val sid = intent.getStringExtra(ListenerService.EXTRA_RC_SESSION_ID) ?: return false
        return sid == sessionId
    }

    private fun registerBroadcastReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        registerReceiver(rcMessageReceiver, IntentFilter(ListenerService.ACTION_RC_MESSAGE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcPermissionReceiver, IntentFilter(ListenerService.ACTION_RC_PERMISSION_REQUEST), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcToolStatusReceiver, IntentFilter(ListenerService.ACTION_RC_TOOL_STATUS), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcPlanUpdateReceiver, IntentFilter(ListenerService.ACTION_RC_PLAN_UPDATE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcAgentStatusReceiver, IntentFilter(ListenerService.ACTION_RC_AGENT_STATUS), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcThinkingReceiver, IntentFilter(ListenerService.ACTION_RC_THINKING), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcThinkingEndReceiver, IntentFilter(ListenerService.ACTION_RC_THINKING_END), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcModeChangeReceiver, IntentFilter(ListenerService.ACTION_RC_MODE_CHANGE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcUserInputReceiver, IntentFilter(ListenerService.ACTION_RC_USER_INPUT), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcSessionStartReceiver, IntentFilter(ListenerService.ACTION_RC_SESSION_START), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcSessionEndReceiver, IntentFilter(ListenerService.ACTION_RC_SESSION_END), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcTranscriptReceiver, IntentFilter(ListenerService.ACTION_RC_TRANSCRIPT), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcErrorReceiver, IntentFilter(ListenerService.ACTION_RC_ERROR), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(rcUserMsgStatusReceiver, IntentFilter(ListenerService.ACTION_RC_USER_MSG_STATUS), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterBroadcastReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        unregisterReceiver(rcMessageReceiver)
        unregisterReceiver(rcPermissionReceiver)
        unregisterReceiver(rcToolStatusReceiver)
        unregisterReceiver(rcPlanUpdateReceiver)
        unregisterReceiver(rcAgentStatusReceiver)
        unregisterReceiver(rcThinkingReceiver)
        unregisterReceiver(rcThinkingEndReceiver)
        unregisterReceiver(rcModeChangeReceiver)
        unregisterReceiver(rcUserInputReceiver)
        unregisterReceiver(rcSessionStartReceiver)
        unregisterReceiver(rcSessionEndReceiver)
        unregisterReceiver(rcTranscriptReceiver)
        unregisterReceiver(rcErrorReceiver)
        unregisterReceiver(rcUserMsgStatusReceiver)
    }

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return

        // If session ended, revive it first
        if (sessionEnded) {
            sessionEnded = false
            endedBanner.visibility = View.GONE
            val dot = statusDot.background as? GradientDrawable
            dot?.setColor(ContextCompat.getColor(this, R.color.gbx_yellow))
            // Send revive request to orchestrator
            sendBroadcast(Intent(ListenerService.ACTION_RC_REVIVE).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
                putExtra(ListenerService.EXTRA_RC_DATA, workDir)
            })
        }

        // Intercept slash commands
        if (text.startsWith("/") && pendingUserInputRequestId == null) {
            val parts = text.removePrefix("/").split(" ", limit = 2)
            val handler = SlashCommandRegistry.get(parts[0])
            if (handler != null) {
                inputField.text.clear()
                DraftStore.clear(this@RemoteControlActivity, sessionId)
                lastSavedDraft = ""
                hideSlashPopup()
                handler.execute(this, parts.getOrElse(1) { "" }) { result ->
                    runOnUiThread { handleCommandResult(result) }
                }
                return
            }
        }

        inputField.text.clear()
        DraftStore.clear(this, sessionId)
        lastSavedDraft = ""

        // If AskUserQuestion is active, send typed text as custom answer
        val currentQuestion = currentPermissionRequest
        if (currentQuestion != null && currentQuestion.toolName == "AskUserQuestion") {
            resolvePermission(currentQuestion, true, null, text)
            showThinking()
            return
        }

        // If there is a pending user input request, send as user response
        val pendingReqId = pendingUserInputRequestId
        if (pendingReqId != null) {
            pendingUserInputRequestId = null
            inputField.hint = "Send a message..."
            // Reset input bar highlighting
            inputField.background?.clearColorFilter()
            sendButton.clearColorFilter()
            micButton.clearColorFilter()

            // Mark the input request as answered
            val inputMsg = adapter.getMessages().filterIsInstance<RcMessage.UserInputRequest>()
                .lastOrNull { it.requestId == pendingReqId }
            if (inputMsg != null) {
                inputMsg.answered = true
                val inputPos = adapter.findPositionById(inputMsg.id)
                if (inputPos >= 0) adapter.notifyItemChanged(inputPos)
            }

            adapter.addMessage(RcMessage.TextMessage(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                role = RcMessage.Role.USER,
                text = text,
                requestId = pendingReqId
            ))
            scrollToBottom()

            sendBroadcast(Intent(ListenerService.ACTION_RC_USER_RESP).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
                putExtra("rc_request_id", pendingReqId)
                putExtra("rc_text", text)
            })
            showThinking()
        } else {
            // Proactive user message - send to orchestrator
            adapter.addMessage(RcMessage.TextMessage(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                role = RcMessage.Role.USER,
                text = text,
                requestId = ""
            ))
            scrollToBottom()

            // Send to orchestrator via service
            val intent = Intent(ListenerService.ACTION_RC_USER_MSG).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
                putExtra(ListenerService.EXTRA_RC_DATA, text)
            }
            sendBroadcast(intent)
            showThinking()
        }
    }

    /**
     * In-session /resume: list prior conversations for this folder and let the
     * user switch to one. sessionId is fixed for an activity's lifetime, so
     * resuming a different conversation relaunches the activity with the new
     * EXTRA_SESSION_ID; startSession(sessionId) makes pc-agent spawn --resume,
     * and the fresh activity backfills the chosen conversation's transcript.
     */
    internal fun showResumePicker() {
        val wsUrl = AppConfig.getOrchestratorUrl(this)
        val rcApiKey = AppConfig.getApiKey(this)
        val rcDeviceId = AppConfig.getDeviceId(this)
        val client = RemoteSessionClient(wsUrl, rcApiKey, rcDeviceId)

        val picker = com.repository.listener.ui.ConversationPickerBottomSheet().apply {
            this.workDir = this@RemoteControlActivity.workDir
            isLoading = true
            onNewConversation = { startResumedSession(client, null) }
            onConversationSelected = { conv -> startResumedSession(client, conv.sessionId) }
        }
        picker.show(supportFragmentManager, "resume_picker")

        client.listConversations(workDir) { result ->
            result.onSuccess { list -> picker.showConversations(list) }
            result.onFailure { err -> picker.showError("Failed to load conversations: ${err.message}") }
        }
    }

    private fun startResumedSession(client: RemoteSessionClient, resumeSessionId: String?) {
        // No-op if resuming the conversation already open in this activity.
        if (resumeSessionId != null && resumeSessionId == sessionId) return
        client.startSession(workDir, currentMode, resumeSessionId) { result ->
            result.onSuccess { response ->
                val intent = Intent(this, RemoteControlActivity::class.java).apply {
                    putExtra(EXTRA_SESSION_ID, response.sessionId)
                    putExtra(EXTRA_WORK_DIR, response.workDir)
                    putExtra(EXTRA_PERMISSION_MODE, currentMode)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Resume failed: ${err.message}")
                runOnUiThread {
                    adapter.addMessage(RcMessage.SessionEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        event = "Resume failed: ${err.message}"
                    ))
                }
            }
        }
    }

    internal fun showModePopup() {
        val popup = PopupMenu(this, modeSelector)
        popup.menu.add(0, 0, 0, "Plan mode")
        popup.menu.add(0, 1, 1, "Accept all edits")
        popup.menu.add(0, 2, 2, "Bypass all permissions")
        popup.setOnMenuItemClickListener { item ->
            val mode = when (item.itemId) {
                0 -> "plan"
                1 -> "acceptEdits"
                2 -> "bypassAll"
                else -> "bypassAll"
            }
            sendBroadcast(Intent(ListenerService.ACTION_RC_MODE_REQ).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
                putExtra("rc_mode", mode)
            })
            currentMode = mode
            updateModeSelector()
            true
        }
        popup.show()
    }

    private fun updateModeSelector() {
        val label = when (currentMode) {
            "plan" -> "Plan"
            "acceptEdits" -> "Accept"
            "bypassAll" -> "Bypass"
            else -> currentMode
        }
        modeSelector.text = label
    }

    private fun showNextPermissionPrompt() {
        if (currentPermissionRequest != null) return
        val request = permissionQueue.removeFirstOrNull() ?: return
        currentPermissionRequest = request

        // The AI is now blocked waiting for user approval -- stop the
        // thinking ticker and hide the stop button (nothing to abort).
        thinkingHandler.removeCallbacks(thinkingRunnable)
        stopButton.visibility = View.GONE
        val thinkingView = this.thinkingView
        if (thinkingView != null && rcUiStatus is RcUiStatus.Thinking) {
            val elapsed = System.currentTimeMillis() - (rcUiStatus as RcUiStatus.Thinking).startedAtMs
            rcUiStatus = RcUiStatus.Thought(elapsed)
            renderRcStatus()
        }

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        actionButtonsContainer.removeAllViews()

        val isQuestion = request.toolName == "AskUserQuestion"

        if (isQuestion) {
            // AskUserQuestion: show option buttons from toolArgs (all questions)
            data class QuestionGroup(val question: String, val options: List<Pair<String, String>>)
            val questionGroups = mutableListOf<QuestionGroup>()
            try {
                val args = JSONObject(request.toolArgs)
                val questions = args.optJSONArray("questions")
                if (questions != null) {
                    for (qi in 0 until questions.length()) {
                        val q = questions.getJSONObject(qi)
                        val qText = q.optString("question", "")
                        val opts = mutableListOf<Pair<String, String>>()
                        val optsArr = q.optJSONArray("options")
                        if (optsArr != null) {
                            for (j in 0 until optsArr.length()) {
                                val opt = optsArr.getJSONObject(j)
                                opts.add(opt.optString("label", "") to opt.optString("description", ""))
                            }
                        }
                        questionGroups.add(QuestionGroup(qText, opts))
                    }
                }
            } catch (_: Exception) {}

            actionButtonsContainer.columnCount = 1
            var rowIdx = 0
            // Track selected option per question
            val selections = mutableMapOf<Int, String>() // questionIndex -> selected label
            val optionButtons = mutableListOf<Pair<Int, Button>>() // questionIndex, button

            for ((qi, group) in questionGroups.withIndex()) {
                // Add question header
                if (group.question.isNotEmpty()) {
                    val header = TextView(this).apply {
                        text = group.question
                        setTextColor(Color.parseColor("#EBDBB2")) // gbx_fg
                        textSize = 13f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding(dp(8), dp(8), dp(8), dp(4))
                    }
                    val hParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(0, 1f)
                        rowSpec = GridLayout.spec(rowIdx++)
                    }
                    actionButtonsContainer.addView(header, hParams)
                }
                for (opt in group.options) {
                    val optLabel = opt.first
                    val optDesc = opt.second
                    val spannable = SpannableStringBuilder()
                    spannable.append(optLabel)
                    if (optDesc.isNotEmpty()) {
                        spannable.append("\n")
                        val descStart = spannable.length
                        spannable.append(optDesc)
                        spannable.setSpan(
                            ForegroundColorSpan(Color.parseColor("#A89984")),
                            descStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        spannable.setSpan(
                            android.text.style.RelativeSizeSpan(0.85f),
                            descStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    val button = Button(this).apply {
                        text = spannable
                        setTextColor(Color.parseColor("#EBDBB2"))
                        textSize = 13f
                        typeface = android.graphics.Typeface.DEFAULT
                        isAllCaps = false
                        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#3C3836")) // gbx_bg1
                            cornerRadius = dp(12).toFloat()
                            setStroke(1, Color.parseColor("#504945")) // gbx_bg2
                        }
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                    }
                    optionButtons.add(qi to button)
                    button.setOnClickListener {
                        selections[qi] = optLabel
                        // Update visual: highlight selected, dim others for this question
                        for ((bqi, btn) in optionButtons) {
                            val bg = btn.background as? GradientDrawable ?: continue
                            if (bqi == qi) {
                                if (selections[qi] == (btn.tag as? String)) {
                                    bg.setColor(Color.parseColor("#458588")) // gbx_blue = selected
                                    bg.setStroke(1, Color.parseColor("#689D6A")) // gbx_aqua
                                } else {
                                    bg.setColor(Color.parseColor("#3C3836")) // gbx_bg1
                                    bg.setStroke(1, Color.parseColor("#504945"))
                                }
                            }
                        }
                        // Highlight this button
                        (button.background as? GradientDrawable)?.apply {
                            setColor(Color.parseColor("#458588"))
                            setStroke(1, Color.parseColor("#689D6A"))
                        }
                    }
                    button.tag = optLabel
                    val params = GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(0, 1f)
                        rowSpec = GridLayout.spec(rowIdx++)
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    actionButtonsContainer.addView(button, params)
                }
            }

            // Submit button at the bottom
            val submitBtn = Button(this).apply {
                text = "Submit"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isAllCaps = false
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#98971A")) // gbx_green
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    val answer = selections.entries.sortedBy { it.key }
                        .joinToString(", ") { it.value }
                        .ifEmpty { inputField.text.toString().trim() }
                    if (answer.isNotEmpty()) {
                        resolvePermission(request, true, null, answer)
                        showThinking()
                    }
                }
            }
            val submitParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(0, 1f)
                rowSpec = GridLayout.spec(rowIdx++)
                setMargins(dp(4), dp(12), dp(4), dp(4))
            }
            actionButtonsContainer.addView(submitBtn, submitParams)
        } else {
            // Normal permission: approve/deny/mode buttons
            actionButtonsContainer.columnCount = 2

            data class ActionBtn(val label: String, val color: Int, val approved: Boolean, val modeChange: String?)
            val buttons = listOf(
                ActionBtn("Approve", Color.parseColor("#98971A"), true, null),
                ActionBtn("Reject", Color.parseColor("#CC241D"), false, null),
                ActionBtn("Approve & Accept Edits", Color.parseColor("#D79921"), true, "acceptEdits"),
                ActionBtn("Approve & Bypass All", Color.parseColor("#D65D0E"), true, "bypassAll")
            )

            for ((i, btn) in buttons.withIndex()) {
                val button = Button(this).apply {
                    text = btn.label
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    isAllCaps = false
                    background = GradientDrawable().apply {
                        setColor(btn.color)
                        cornerRadius = dp(6).toFloat()
                    }
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    setOnClickListener { resolvePermission(request, btn.approved, btn.modeChange) }
                }
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(i % 2, 1f)
                    rowSpec = GridLayout.spec(i / 2)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                actionButtonsContainer.addView(button, params)
            }
        }

        actionButtonsContainer.visibility = View.VISIBLE
        val showInput = request.toolName == "ExitPlanMode" || isQuestion
        if (showInput) {
            findViewById<View>(R.id.rcInputBar).visibility = View.VISIBLE
            inputField.hint = if (isQuestion) "Type custom answer..." else "Plan feedback (optional)..."
        } else {
            findViewById<View>(R.id.rcInputBar).visibility = View.GONE
        }
        scrollToBottom()
    }

    private fun resolvePermission(request: RcMessage.PermissionRequest, approved: Boolean, modeChange: String?, answerText: String? = null) {
        currentPermissionRequest = null
        request.pending = false
        request.approved = approved

        val isQuestion = request.toolName == "AskUserQuestion"
        val isPlanApproval = request.toolName == "ExitPlanMode"

        // Determine reason/answer text
        val reason = when {
            answerText != null -> answerText // Option button clicked
            isQuestion && inputField.text.isNotEmpty() -> inputField.text.toString() // Custom typed answer
            !approved && isPlanApproval && inputField.text.isNotEmpty() -> inputField.text.toString()
            else -> null
        }

        // Update the permission card in the adapter
        val pos = adapter.findPositionById(request.id)
        if (pos >= 0) adapter.notifyItemChanged(pos)

        // Send response to service -> orchestrator
        sendBroadcast(Intent(ListenerService.ACTION_RC_PERMISSION_RESP).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
            putExtra("rc_request_id", request.requestId)
            putExtra("rc_approved", approved)
            if (modeChange != null) putExtra("rc_mode_change", modeChange)
            if (reason != null) putExtra("rc_reason", reason)
        })

        // Clear input
        if (isPlanApproval || isQuestion) {
            inputField.text.clear()
            DraftStore.clear(this, sessionId)
            lastSavedDraft = ""
            inputField.hint = "Send a message..."
        }

        // Update mode selector chip if mode changed
        if (modeChange != null) {
            currentMode = modeChange
            updateModeSelector()
        }

        // Hide action buttons, show input bar
        actionButtonsContainer.visibility = View.GONE
        findViewById<View>(R.id.rcInputBar).visibility = View.VISIBLE

        // Show next prompt if queued, or show thinking (stop button visible)
        if (permissionQueue.isEmpty()) {
            showThinking()
        }
        showNextPermissionPrompt()
    }

    private fun animateSendButton(show: Boolean) {
        sendButton.animate().cancel()
        if (show) {
            sendButton.scaleX = 0f
            sendButton.scaleY = 0f
            sendButton.visibility = View.VISIBLE
            sendButton.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            sendButton.animate()
                .scaleX(0f).scaleY(0f)
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { sendButton.visibility = View.GONE }
                .start()
        }
    }

    private fun animateMicButton(show: Boolean) {
        micButton.animate().cancel()
        if (show) {
            micButton.scaleX = 0f
            micButton.scaleY = 0f
            micButton.visibility = View.VISIBLE
            micButton.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            micButton.animate()
                .scaleX(0f).scaleY(0f)
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { micButton.visibility = View.GONE }
                .start()
        }
    }

    private fun setupVoiceRecordButton() {
        micButton.overlay = recordingOverlay

        recordingOverlay.listener = object : RecordingOverlay.Listener {
            override fun onRecordEnd() { finishVoiceRecording() }
            override fun onRecordCancel() { cancelVoiceRecording() }
        }

        micButton.listener = object : VoiceRecordButton.VoiceRecordListener {
            override fun onRecordStart() {
                if (ContextCompat.checkSelfPermission(this@RemoteControlActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                    micButton.cancelRecording()
                    return
                }
                // Fade out input bar contents
                findViewById<View>(R.id.rcInputBar).let { bar ->
                    for (i in 0 until (bar as android.view.ViewGroup).childCount) {
                        bar.getChildAt(i).animate().alpha(0f).setDuration(150).start()
                    }
                }
                voiceRecorder = ChatVoiceRecorder(this@RemoteControlActivity).also { recorder ->
                    recorder.startRecording(object : ChatVoiceRecorder.RecordingListener {
                        override fun onPartialTranscript(text: String) {
                            runOnUiThread { inputField.setText(text) }
                        }
                        override fun onFinalTranscript(text: String) {
                            runOnUiThread { inputField.setText(text) }
                        }
                        override fun onError(message: String) {
                            runOnUiThread {
                                android.widget.Toast.makeText(this@RemoteControlActivity, "Recording error: $message", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onAmplitude(amplitude: Float) {
                            runOnUiThread { recordingOverlay.setAmplitude(amplitude) }
                        }
                    })
                }
            }

            override fun onRecordEnd() { finishVoiceRecording() }
            override fun onRecordCancel() { cancelVoiceRecording() }
        }
    }

    private fun finishVoiceRecording() {
        recordingOverlay.hide()
        restoreRcInputBar()
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        Thread {
            val text = recorder.stopRecording()
            runOnUiThread {
                if (!text.isNullOrBlank()) {
                    inputField.setText(text)
                    inputField.setSelection(text.length)
                }
            }
        }.start()
    }

    private fun cancelVoiceRecording() {
        recordingOverlay.hide()
        restoreRcInputBar()
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        recorder.cancelRecording()
    }

    private fun restoreRcInputBar() {
        findViewById<View>(R.id.rcInputBar).let { bar ->
            for (i in 0 until (bar as android.view.ViewGroup).childCount) {
                bar.getChildAt(i).animate().alpha(1f).setDuration(150).start()
            }
        }
    }

    private fun showThinking() {
        animateSendButton(show = false)
        if (inputField.text.isBlank()) animateMicButton(show = true)
        thinkingStartTime = System.currentTimeMillis()
        setRcStatus(RcUiStatus.Sending())
    }

    /** No-op for status indicator -- was used to hide spinner on intermediate events. */
    private fun hideThinkingAnimation() {
        // Status indicator persists across intermediate events; only transitions on
        // explicit thinking-start, thinking-end, error, or session end.
    }

    /** Fully hide status + stop button (turn completed or session ended) */
    private fun hideThinking() {
        stopButton.visibility = View.GONE
        // Restore send/mic button visibility based on input text
        if (inputField.text.isNotBlank()) {
            animateSendButton(show = true)
            animateMicButton(show = false)
        } else {
            animateSendButton(show = false)
            animateMicButton(show = true)
        }
        // Preserve "Thought for X" if we already have one; only clear if Sending/Thinking still showing.
        if (rcUiStatus is RcUiStatus.Sending) {
            setRcStatus(RcUiStatus.Idle)
        } else {
            // Stop the ticker but keep the last Thought label visible
            thinkingHandler.removeCallbacks(thinkingRunnable)
        }
    }

    private fun showError(errorText: String, source: String = "system") {
        adapter.addMessage(RcMessage.ErrorMessage(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            errorText = errorText,
            source = source
        ))
        scrollToBottom()
    }

    private fun showSlashPopupFromHandlers(handlers: List<SlashCommandHandler>) {
        val container = slashPopupContainer ?: return
        val popup = slashPopup ?: return
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        container.removeAllViews()

        val grayColor = ContextCompat.getColor(this, R.color.gbx_gray)
        val aquaColor = ContextCompat.getColor(this, R.color.gbx_aqua)

        for (handler in handlers) {
            val row = TextView(this).apply {
                val ssb = SpannableStringBuilder()
                val cmdText = "/${handler.name}"
                ssb.append(cmdText)
                ssb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, cmdText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ForegroundColorSpan(aquaColor), 0, cmdText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.append("  ")
                val descStart = ssb.length
                ssb.append(handler.description)
                ssb.setSpan(ForegroundColorSpan(grayColor), descStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = ssb
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    inputField.setText("/${handler.name} ")
                    inputField.setSelection(inputField.text.length)
                    hideSlashPopup()
                }
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                }
                background = bg
                isClickable = true
                isFocusable = true
            }
            container.addView(row)
        }

        popup.visibility = View.VISIBLE
        popup.scrollTo(0, 0)
    }

    private fun buildSlashPopup() {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        slashPopupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        slashPopup = ScrollView(this).apply {
            addView(slashPopupContainer)
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@RemoteControlActivity, R.color.gbx_bg0_hard))
                cornerRadius = dp(8).toFloat()
                setStroke(1, ContextCompat.getColor(this@RemoteControlActivity, R.color.gbx_bg2))
            }
            elevation = dp(4).toFloat()
        }

        // Insert popup above the input bar inside the root layout
        val inputBar = findViewById<View>(R.id.rcInputBar)
        val parent = inputBar.parent as? ViewGroup ?: return
        val inputBarIndex = parent.indexOfChild(inputBar)

        val maxHeight = dp(220) // ~5 items visible
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
        slashPopup!!.layoutParams = params

        // Enforce max height
        slashPopup!!.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > maxHeight) {
                slashPopup!!.layoutParams.height = maxHeight
                slashPopup!!.requestLayout()
            }
        }

        parent.addView(slashPopup, inputBarIndex)
    }

    private fun hideSlashPopup() {
        slashPopup?.visibility = View.GONE
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0 && isUserNearBottom) {
            recyclerView.scrollToPosition(count - 1)
        }
    }

    private fun updateContextUsage(pct: Int, costUsd: Double) {
        contextPctValue = pct
        if (costUsd >= 0) totalCostUsd = costUsd
        if (sessionId.isNotEmpty()) {
            com.repository.listener.config.RcSessionMetaStore.setContextPct(this, sessionId, pct)
            if (costUsd >= 0) {
                com.repository.listener.config.RcSessionMetaStore.setCostUsd(this, sessionId, costUsd)
            }
        }
        contextPctText.visibility = View.VISIBLE
        contextPctText.text = "${pct}%"
        val color = when {
            pct < 50 -> ContextCompat.getColor(this, R.color.gbx_green)
            pct < 80 -> ContextCompat.getColor(this, R.color.gbx_yellow)
            else -> ContextCompat.getColor(this, R.color.gbx_red)
        }
        contextPctText.setTextColor(color)
    }

    fun getTotalCostUsd(): Double = totalCostUsd

    fun getAdapter(): RcDetailAdapter = adapter

    private fun handleCommandResult(result: CommandResult) {
        when (result) {
            is CommandResult.ShowMessage -> {
                adapter.addMessage(result.msg)
                scrollToBottom()
            }
            is CommandResult.ShowDialog -> result.builder(this)
            is CommandResult.ForwardToDesktop -> {
                // Send as user message to desktop
                adapter.addMessage(RcMessage.TextMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    role = RcMessage.Role.USER,
                    text = result.text,
                    requestId = ""
                ))
                scrollToBottom()
                sendBroadcast(Intent(ListenerService.ACTION_RC_USER_MSG).apply {
                    setPackage(packageName)
                    putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
                    putExtra(ListenerService.EXTRA_RC_DATA, result.text)
                })
                showThinking()
            }
            is CommandResult.Handled -> { /* nothing */ }
        }
    }
}
