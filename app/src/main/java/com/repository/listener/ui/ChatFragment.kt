package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.util.UUID

class ChatFragment : Fragment() {

    companion object {
        private const val TAG = "ChatFragment"
    }

    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var loadingActive = false
    private var loadingIndex = 0
    private val loadingRunnable = object : Runnable {
        override fun run() {
            if (!loadingActive) return
            val dots = ".".repeat((loadingIndex % 3) + 1)
            loadingIndex++
            updateLoadingMessage(dots)
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

                activity?.runOnUiThread {
                    chatAdapter.addMessage(msg)
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

                activity?.runOnUiThread {
                    // Stop loading on first streaming text
                    stopLoading()
                    chatAdapter.removeLoadingMessages()

                    val messages = chatAdapter.getMessages()
                    val lastMsg = messages.lastOrNull()

                    if (lastMsg != null && lastMsg.role == ChatMessage.Role.ASSISTANT && lastMsg.requestId == requestId) {
                        chatAdapter.updateLastMessage(partialText)
                    } else {
                        chatAdapter.addMessage(ChatMessage(
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

                activity?.runOnUiThread {
                    chatAdapter.addMessage(ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.TOOL,
                        text = toolName,
                        requestId = requestId
                    ))
                    scrollToBottom()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse tool status: ${e.message}")
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ListenerService.EXTRA_STATE) ?: return
            activity?.runOnUiThread {
                when (state) {
                    "RESPONDING" -> startLoading()
                    "IDLE" -> stopLoading()
                }
            }
        }
    }

    private val sessionResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            activity?.runOnUiThread {
                stopLoading()
                chatAdapter.clear()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatRecycler = view.findViewById(R.id.chatRecycler)
        chatAdapter = ChatAdapter()

        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        chatRecycler.layoutManager = layoutManager
        chatRecycler.adapter = chatAdapter
    }

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        ctx.registerReceiver(
            chatReceiver,
            IntentFilter(ListenerService.ACTION_CHAT_MESSAGE),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            streamingReceiver,
            IntentFilter(ListenerService.ACTION_STREAMING_TEXT),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            toolStatusReceiver,
            IntentFilter(ListenerService.ACTION_TOOL_STATUS),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            stateReceiver,
            IntentFilter(ListenerService.ACTION_STATE_UPDATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            sessionResetReceiver,
            IntentFilter(ListenerService.ACTION_SESSION_RESET),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        val ctx = requireContext()
        ctx.unregisterReceiver(chatReceiver)
        ctx.unregisterReceiver(streamingReceiver)
        ctx.unregisterReceiver(toolStatusReceiver)
        ctx.unregisterReceiver(stateReceiver)
        ctx.unregisterReceiver(sessionResetReceiver)
        stopLoading()
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
        chatAdapter.removeLoadingMessages()
    }

    private fun updateLoadingMessage(text: String) {
        val messages = chatAdapter.getMessages()
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == ChatMessage.Role.SYSTEM && lastMsg.requestId == "loading") {
            chatAdapter.updateLastMessage(text)
        } else {
            chatAdapter.addMessage(ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatMessage.Role.SYSTEM,
                text = text,
                requestId = "loading"
            ))
        }
        scrollToBottom()
    }

    private fun scrollToBottom() {
        val itemCount = chatAdapter.itemCount
        if (itemCount > 0) {
            chatRecycler.scrollToPosition(itemCount - 1)
        }
    }
}
