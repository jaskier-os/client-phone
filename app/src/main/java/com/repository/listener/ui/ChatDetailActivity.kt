package com.repository.listener.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.config.DraftStore
import com.repository.listener.network.ChatHistoryClient
import com.repository.listener.service.ListenerService
import com.repository.listener.ui.views.ChatVoiceRecorder
import com.repository.listener.ui.views.RecordingOverlay
import com.repository.listener.ui.views.VoiceRecordButton
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

class ChatDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatDetailActivity"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_CONVERSATION_TITLE = "conversation_title"
        const val EXTRA_CONVERSATION_CLOSED = "conversation_closed"
        private const val THINKING_TIMEOUT_MS = 120_000L
    }

    private lateinit var adapter: ChatDetailAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var topBarNormal: FrameLayout
    private lateinit var topBarSelection: FrameLayout
    private lateinit var selectionCount: TextView
    private lateinit var titleText: TextView
    private lateinit var btnSend: ImageView
    private lateinit var btnMic: VoiceRecordButton
    private lateinit var btnScrollToBottom: ImageView
    private lateinit var btnPin: ImageView
    private lateinit var recordingOverlay: RecordingOverlay

    @Volatile private var voiceRecorder: ChatVoiceRecorder? = null
    private var client: ChatHistoryClient? = null
    private var conversationId: String = ""
    private var lastSavedDraft: String = ""
    private val draftRunnable = object : Runnable {
        override fun run() {
            val text = messageInput.text.toString()
            if (text != lastSavedDraft) {
                lastSavedDraft = text
                DraftStore.set(this@ChatDetailActivity, draftKey(), text)
            }
            handler.postDelayed(this, 100)
        }
    }
    private var pendingImageBase64: String? = null
    private var isClosed: Boolean = true
    private var isCreatingChat = false
    private var isWaitingForResponse = false
    private var receiversRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private var thinkingTimeoutRunnable: Runnable? = null
    private var loadingActive = false
    private var loadingIndex = 0
    private val loadingRunnable = object : Runnable {
        override fun run() {
            if (!loadingActive) return
            val dots = ".".repeat((loadingIndex % 3) + 1)
            loadingIndex++
            updateLoadingIndicator(dots)
            handler.postDelayed(this, 500)
        }
    }

    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_CHAT_MESSAGE) ?: return
            try {
                val obj = JSONObject(json)
                val requestId = obj.getString("requestId")
                val roleStr = obj.getString("role")
                val text = obj.getString("text")

                val role = when (roleStr) {
                    "USER" -> ChatMessage.Role.USER
                    "ASSISTANT" -> ChatMessage.Role.ASSISTANT
                    "TOOL" -> ChatMessage.Role.TOOL
                    "SYSTEM" -> ChatMessage.Role.SYSTEM
                    "METADATA" -> ChatMessage.Role.METADATA
                    else -> ChatMessage.Role.SYSTEM
                }

                val msg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = role,
                    text = text,
                    requestId = requestId
                )

                runOnUiThread {
                    // Stop tool animation when response arrives
                    if (role == ChatMessage.Role.ASSISTANT) {
                        adapter.stopToolAnimation(requestId)
                        setChatResponding(false)
                        isWaitingForResponse = false
                        thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
                        updateSendButtonState()
                        // Upsert by requestId so the final response replaces the
                        // bubble that streaming (or a history reload) already
                        // rendered for this turn instead of duplicating it.
                        adapter.upsertAssistantMessage(requestId, text)
                    } else {
                        adapter.addMessage(msg)
                    }
                    scrollToBottom()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse chat message: ${e.message}")
            }
        }
    }

    private val streamingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_STREAMING_TEXT) ?: return
            try {
                val obj = JSONObject(json)
                val requestId = obj.getString("requestId")
                val partialText = obj.getString("partialText")

                runOnUiThread {
                    setChatResponding(false)
                    stopLoading()
                    adapter.removeLoadingMessages()

                    val messages = adapter.getMessages()
                    val lastMsg = messages.lastOrNull()

                    if (lastMsg != null && lastMsg.role == ChatMessage.Role.ASSISTANT && lastMsg.requestId == requestId) {
                        adapter.updateLastMessage(partialText)
                    } else {
                        adapter.addMessage(ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.ASSISTANT,
                            text = partialText,
                            requestId = requestId
                        ))
                    }
                    scrollToBottom()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse streaming text: ${e.message}")
            }
        }
    }

    private val toolStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_TOOL_STATUS) ?: return
            try {
                val obj = JSONObject(json)
                val requestId = obj.getString("requestId")
                val toolName = obj.getString("toolName")
                val status = obj.optString("status", "")

                runOnUiThread {
                    if (status == "complete" || status == "error") {
                        adapter.stopToolAnimation(requestId)
                    } else {
                        val toolArgs = obj.optJSONObject("toolArgs")
                        val toolCallId = obj.optString("toolCallId", "")
                        val argSummary = toolArgs?.let {
                            it.optString("query", "").ifEmpty { null }
                                ?: it.optString("url", "").ifEmpty { null }
                                ?: it.optString("command", "").ifEmpty { null }
                                ?: it.optString("prompt", "").ifEmpty { null }
                                ?: it.optString("path", "").ifEmpty { null }
                        }
                        val displayText = if (!argSummary.isNullOrEmpty()) {
                            "$toolName: ${argSummary.take(60)}"
                        } else toolName
                        val upsertId = toolCallId.ifEmpty { requestId }
                        adapter.upsertToolMessage(upsertId, requestId, displayText)
                        scrollToBottom()
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse tool status: ${e.message}")
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ListenerService.EXTRA_STATE) ?: return
            runOnUiThread {
                when (state) {
                    "RESPONDING" -> startLoading()
                    "IDLE" -> stopLoading()
                }
            }
        }
    }

    private val sessionResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runOnUiThread {
                stopLoading()
                adapter.submitMessages(emptyList())
                isWaitingForResponse = false
                thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
                updateSendButtonState()
            }
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingImageBase64 = encodeImageUri(uri)
                if (pendingImageBase64 != null) {
                    Toast.makeText(this, "Image attached", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_detail)

        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""
        val title = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: ""
        isClosed = intent.getBooleanExtra(EXTRA_CONVERSATION_CLOSED, true)

        recyclerView = findViewById(R.id.messagesRecycler)
        messageInput = findViewById(R.id.messageInput)
        topBarNormal = findViewById(R.id.topBarNormal)
        topBarSelection = findViewById(R.id.topBarSelection)
        selectionCount = findViewById(R.id.selectionCount)
        titleText = findViewById(R.id.titleText)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)
        btnScrollToBottom = findViewById(R.id.btnScrollToBottom)
        btnPin = findViewById(R.id.btnPin)
        recordingOverlay = findViewById(R.id.recordingOverlay)

        titleText.text = title

        // Pin button
        if (conversationId.isNotEmpty()) {
            updatePinIcon()
            btnPin.setOnClickListener {
                val pinned = AppConfig.togglePinChat(this, conversationId)
                updatePinIcon()
                Toast.makeText(this, if (pinned) "Pinned" else "Unpinned", Toast.LENGTH_SHORT).show()
            }
        } else {
            btnPin.visibility = View.GONE
        }

        adapter = ChatDetailAdapter(
            onMessageClicked = { _, msg ->
                copyToClipboard(msg.text)
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            },
            onSelectionChanged = { count ->
                if (count > 0) {
                    topBarNormal.visibility = View.GONE
                    topBarSelection.visibility = View.VISIBLE
                    selectionCount.text = "$count selected"
                } else {
                    topBarSelection.visibility = View.GONE
                    topBarNormal.visibility = View.VISIBLE
                }
            }
        )

        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        // Back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Selection mode buttons
        findViewById<TextView>(R.id.btnCopySelection).setOnClickListener {
            val selected = adapter.getSelectedMessages()
            if (selected.isNotEmpty()) {
                val text = selected.joinToString("\n\n") { msg ->
                    val prefix = if (msg.role == ChatMessage.Role.USER) "User" else "Assistant"
                    "$prefix: ${msg.text}"
                }
                copyToClipboard(text)
                Toast.makeText(this, "Copied ${selected.size} messages", Toast.LENGTH_SHORT).show()
                adapter.clearSelection()
            }
        }

        findViewById<TextView>(R.id.btnCloseSelection).setOnClickListener {
            adapter.clearSelection()
        }

        // Attach button
        findViewById<ImageView>(R.id.btnAttach).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
            }
            imagePickerLauncher.launch(intent)
        }

        // Send button
        btnSend.setOnClickListener {
            sendMessage()
        }

        // Mic button voice recording
        setupVoiceRecordButton()

        // Send/mic button toggle based on text input
        messageInput.addTextChangedListener(object : TextWatcher {
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
            }
        })

        // Scroll-to-bottom button
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastCompletelyVisibleItemPosition()
                val total = adapter.itemCount
                val isAtBottom = lastVisible >= total - 2
                if (isAtBottom) {
                    if (btnScrollToBottom.visibility == View.VISIBLE) {
                        btnScrollToBottom.animate().alpha(0f).setDuration(200).withEndAction {
                            btnScrollToBottom.visibility = View.GONE
                        }.start()
                    }
                } else {
                    if (btnScrollToBottom.visibility != View.VISIBLE) {
                        btnScrollToBottom.visibility = View.VISIBLE
                        btnScrollToBottom.alpha = 0f
                        btnScrollToBottom.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
        })

        btnScrollToBottom.setOnClickListener {
            scrollToBottom()
        }

        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    adapter.clearSelection()
                } else {
                    finish()
                }
            }
        })

        // Init client and load messages
        val wsUrl = AppConfig.getOrchestratorUrl(this)
        val apiKey = AppConfig.getApiKey(this)
        client = ChatHistoryClient(wsUrl, apiKey)

        if (conversationId.isEmpty()) {
            titleText.text = "New chat"
            isClosed = false
        } else {
            loadConversation()
        }

        // Restore draft
        val draft = DraftStore.get(this, draftKey())
        if (draft.isNotEmpty()) {
            messageInput.setText(draft)
            messageInput.setSelection(draft.length)
            lastSavedDraft = draft
        }
    }

    private fun draftKey(): String =
        conversationId.ifEmpty { DraftStore.KEY_NEW_CHAT }

    override fun onResume() {
        super.onResume()
        if (!isClosed) registerBroadcastReceivers()
        handler.post(draftRunnable)
        // Recover from stale thinking state: if we went through onPause while
        // waiting for a response, the WS broadcast with the answer was likely
        // dropped (receivers were unregistered). Reload conversation to pick up
        // the response from the server, and reset thinking state if it arrived.
        if (isWaitingForResponse && conversationId.isNotEmpty()) {
            loadConversation()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(draftRunnable)
        // Final save on pause
        val text = messageInput.text.toString()
        if (text != lastSavedDraft) {
            DraftStore.set(this, draftKey(), text)
        }
        unregisterBroadcastReceivers()
        stopLoading()
        thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        val recorder = voiceRecorder
        voiceRecorder = null
        recorder?.cancelRecording()
        btnMic.cancelRecording()
        recordingOverlay.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun animateSendButton(show: Boolean) {
        btnSend.animate().cancel()
        if (show) {
            btnSend.scaleX = 0f
            btnSend.scaleY = 0f
            btnSend.visibility = View.VISIBLE
            btnSend.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            btnSend.animate()
                .scaleX(0f).scaleY(0f)
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { btnSend.visibility = View.GONE }
                .start()
        }
    }

    private fun animateMicButton(show: Boolean) {
        btnMic.animate().cancel()
        if (show) {
            btnMic.scaleX = 0f
            btnMic.scaleY = 0f
            btnMic.visibility = View.VISIBLE
            btnMic.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            btnMic.animate()
                .scaleX(0f).scaleY(0f)
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { btnMic.visibility = View.GONE }
                .start()
        }
    }

    private fun setupVoiceRecordButton() {
        btnMic.overlay = recordingOverlay

        recordingOverlay.listener = object : RecordingOverlay.Listener {
            override fun onRecordEnd() { finishRecording() }
            override fun onRecordCancel() { cancelVoiceRecording() }
        }

        btnMic.listener = object : VoiceRecordButton.VoiceRecordListener {
            override fun onRecordStart() {
                if (ContextCompat.checkSelfPermission(this@ChatDetailActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
                    btnMic.cancelRecording()
                    return
                }
                voiceRecorder = ChatVoiceRecorder(this@ChatDetailActivity).also { recorder ->
                    recorder.startRecording(object : ChatVoiceRecorder.RecordingListener {
                        override fun onPartialTranscript(text: String) {
                            runOnUiThread { messageInput.setText(text) }
                        }
                        override fun onFinalTranscript(text: String) {
                            runOnUiThread { messageInput.setText(text) }
                        }
                        override fun onError(message: String) {
                            runOnUiThread {
                                Toast.makeText(this@ChatDetailActivity, "Recording error: $message", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onAmplitude(amplitude: Float) {
                            runOnUiThread { recordingOverlay.setAmplitude(amplitude) }
                        }
                    })
                }
            }
            override fun onRecordEnd() { finishRecording() }
            override fun onRecordCancel() { cancelVoiceRecording() }
        }
    }

    private fun finishRecording() {
        recordingOverlay.hide()
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        Thread {
            val text = recorder.stopRecording()
            runOnUiThread {
                if (!text.isNullOrBlank()) {
                    messageInput.setText(text)
                    messageInput.setSelection(text.length)
                }
            }
        }.start()
    }

    private fun cancelVoiceRecording() {
        recordingOverlay.hide()
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        recorder.cancelRecording()
    }

    private fun registerBroadcastReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        registerReceiver(chatReceiver, IntentFilter(ListenerService.ACTION_CHAT_MESSAGE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(streamingReceiver, IntentFilter(ListenerService.ACTION_STREAMING_TEXT), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(toolStatusReceiver, IntentFilter(ListenerService.ACTION_TOOL_STATUS), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(stateReceiver, IntentFilter(ListenerService.ACTION_STATE_UPDATE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(sessionResetReceiver, IntentFilter(ListenerService.ACTION_SESSION_RESET), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterBroadcastReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        unregisterReceiver(chatReceiver)
        unregisterReceiver(streamingReceiver)
        unregisterReceiver(toolStatusReceiver)
        unregisterReceiver(stateReceiver)
        unregisterReceiver(sessionResetReceiver)
    }

    private fun startLoading() {
        if (loadingActive) return
        loadingActive = true
        loadingIndex = 0
        handler.post(loadingRunnable)
    }

    private fun stopLoading() {
        loadingActive = false
        handler.removeCallbacks(loadingRunnable)
        adapter.removeLoadingMessages()
    }

    private fun setChatResponding(responding: Boolean) {
        if (conversationId.isEmpty()) return
        if (responding) {
            ListenerService.respondingChatIds.add(conversationId)
        } else {
            ListenerService.respondingChatIds.remove(conversationId)
        }
        sendBroadcast(Intent(ListenerService.ACTION_CHAT_RESPONDING).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_RESPONDING_CONVERSATION_ID, conversationId)
            putExtra(ListenerService.EXTRA_IS_RESPONDING, responding)
        })
    }

    private fun updateLoadingIndicator(text: String) {
        val messages = adapter.getMessages()
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == ChatMessage.Role.SYSTEM && lastMsg.requestId == "loading") {
            adapter.updateLastMessage(text)
        } else {
            adapter.addMessage(ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatMessage.Role.SYSTEM,
                text = text,
                requestId = "loading"
            ))
        }
        scrollToBottom()
    }

    private fun loadConversation() {
        client?.getChatDetail(conversationId) { result ->
            if (isFinishing || isDestroyed) return@getChatDetail
            result.onSuccess { detail ->
                val messages = mutableListOf<ChatMessage>()
                var hasPendingResponse = false
                for (turn in detail.turns) {
                    if (!turn.userText.isNullOrEmpty()) {
                        messages.add(ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.USER,
                            text = turn.userText,
                            requestId = turn.requestId
                        ))
                    }
                    for (tc in turn.toolCalls) {
                        val argSummary = tc.arguments?.let { args ->
                            args.optString("query", "").ifEmpty { null }
                                ?: args.optString("url", "").ifEmpty { null }
                                ?: args.optString("command", "").ifEmpty { null }
                                ?: args.optString("prompt", "").ifEmpty { null }
                                ?: args.optString("path", "").ifEmpty { null }
                        }
                        val displayText = if (!argSummary.isNullOrEmpty()) "${tc.name}: ${argSummary.take(60)}" else tc.name
                        messages.add(ChatMessage(
                            id = tc.id.ifEmpty { UUID.randomUUID().toString() },
                            role = ChatMessage.Role.TOOL,
                            text = displayText,
                            requestId = turn.requestId,
                            isAnimating = false
                        ))
                    }
                    if (!turn.responseText.isNullOrEmpty()) {
                        messages.add(ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.ASSISTANT,
                            text = turn.responseText,
                            requestId = turn.requestId
                        ))
                    }
                    // Detect pending response: user sent text but no AI response yet
                    if (!turn.userText.isNullOrEmpty() && turn.responseText.isNullOrEmpty()) {
                        hasPendingResponse = true
                    }
                }
                adapter.submitMessages(messages)
                scrollToBottom()
                // If the last turn is still awaiting a response, show thinking state
                // and arm the safety timeout so we never get permanently stuck
                if (hasPendingResponse) {
                    setChatResponding(true)
                    if (!isWaitingForResponse) {
                        isWaitingForResponse = true
                        updateSendButtonState()
                        thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
                        thinkingTimeoutRunnable = Runnable {
                            if (isWaitingForResponse) {
                                LogCollector.w(TAG, "Thinking timeout fired (from loadConversation) -- force-clearing")
                                setChatResponding(false)
                                isWaitingForResponse = false
                                updateSendButtonState()
                                runOnUiThread { adapter.removeLoadingMessages() }
                                loadConversation()
                            }
                        }.also { handler.postDelayed(it, THINKING_TIMEOUT_MS) }
                    }
                    startLoading()
                } else {
                    setChatResponding(false)
                    isWaitingForResponse = false
                    thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    updateSendButtonState()
                }
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Failed to load conversation: ${err.message}")
                Toast.makeText(this, "Failed to load conversation", Toast.LENGTH_SHORT).show()
                isWaitingForResponse = false
                thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
                updateSendButtonState()
            }
        }
    }

    private fun updateSendButtonState() {
        val canSend = !isWaitingForResponse
        btnSend.isEnabled = canSend
        btnSend.alpha = if (canSend) 1.0f else 0.4f
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty() && pendingImageBase64 == null) return
        if (isCreatingChat) return
        if (isWaitingForResponse) return

        messageInput.text.clear()
        DraftStore.clear(this, draftKey())
        lastSavedDraft = ""
        val imageBase64 = pendingImageBase64
        pendingImageBase64 = null

        // Add user message immediately
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.USER,
            text = text.ifEmpty { "(image)" },
            requestId = ""
        )
        adapter.addMessage(userMsg)
        scrollToBottom()

        // Mark chat as responding
        setChatResponding(true)
        isWaitingForResponse = true
        updateSendButtonState()

        // Safety timeout -- if no response clears thinking state within the limit,
        // force-clear it so the UI never gets stuck permanently
        thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        thinkingTimeoutRunnable = Runnable {
            if (isWaitingForResponse) {
                LogCollector.w(TAG, "Thinking timeout fired after ${THINKING_TIMEOUT_MS}ms -- force-clearing thinking state")
                setChatResponding(false)
                isWaitingForResponse = false
                updateSendButtonState()
                runOnUiThread { adapter.removeLoadingMessages() }
                loadConversation()
            }
        }.also { handler.postDelayed(it, THINKING_TIMEOUT_MS) }

        // Add loading placeholder
        val loadingMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.SYSTEM,
            text = "...",
            requestId = "loading"
        )
        adapter.addMessage(loadingMsg)
        scrollToBottom()

        val deviceId = AppConfig.getDeviceId(this)
        val deviceType = "phone"

        // Always ensure receivers are registered before sending so WS responses
        // that arrive before HTTP completes are not silently dropped
        if (!isClosed) registerBroadcastReceivers()

        if (conversationId.isEmpty()) {
            isCreatingChat = true
            client?.createChat(text, imageBase64, deviceId, deviceType) { result ->
                isCreatingChat = false
                if (isFinishing || isDestroyed) return@createChat
                result.onSuccess { response ->
                    DraftStore.clear(this@ChatDetailActivity, DraftStore.KEY_NEW_CHAT)
                    conversationId = response.conversationId
                    titleText.text = text.take(40).ifEmpty { "New chat" }
                    isClosed = false
                    registerBroadcastReceivers()
                    loadConversation()
                }
                result.onFailure { err ->
                    LogCollector.e(TAG, "Failed to create chat: ${err.message}")
                    Toast.makeText(this, "Failed to send: ${err.message}", Toast.LENGTH_SHORT).show()
                    setChatResponding(false)
                    isWaitingForResponse = false
                    thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    updateSendButtonState()
                    runOnUiThread { adapter.removeLoadingMessages() }
                }
            }
        } else {
            client?.sendMessage(conversationId, text, imageBase64, deviceId, deviceType) { result ->
                if (isFinishing || isDestroyed) return@sendMessage
                result.onSuccess {
                    if (isClosed) {
                        isClosed = false
                        registerBroadcastReceivers()
                        // Only reload full history for the closed-to-active transition;
                        // once receivers are active, streaming handles new messages
                        loadConversation()
                    } else {
                        // Already active -- just remove loading placeholder,
                        // broadcast receivers handle the response
                        runOnUiThread { adapter.removeLoadingMessages() }
                    }
                }
                result.onFailure { err ->
                    LogCollector.e(TAG, "Failed to send message: ${err.message}")
                    Toast.makeText(this, "Failed to send: ${err.message}", Toast.LENGTH_SHORT).show()
                    setChatResponding(false)
                    isWaitingForResponse = false
                    thinkingTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    updateSendButtonState()
                    loadConversation()
                }
            }
        }
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) {
            recyclerView.scrollToPosition(count - 1)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("chat message", text))
    }

    private fun updatePinIcon() {
        val isPinned = AppConfig.getPinnedChatIds(this).contains(conversationId)
        btnPin.setImageResource(if (isPinned) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        btnPin.setColorFilter(
            if (isPinned) getColor(R.color.gbx_orange) else getColor(R.color.gbx_gray)
        )
    }

    private fun encodeImageUri(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // Scale down if needed
            val maxDim = 1024
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true
                )
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to encode image: ${e.message}")
            null
        }
    }
}
