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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import org.json.JSONArray

class TodoSecondaryFragment : Fragment() {

    companion object {
        private const val TAG = "TodoSecondaryFragment"
        private const val POLL_INTERVAL_MS = 15_000L
    }

    private lateinit var adapter: TelegramSavedAdapter
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private val pollHandler = Handler(Looper.getMainLooper())
    private var hasData = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!hasData && isAdded) {
                LogCollector.i(TAG, "Polling for telegram saved messages")
                sendTelegramSavedRequest()
            }
            if (!hasData) {
                pollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private val telegramReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ListenerService.ACTION_TELEGRAM_SAVED_RESULT -> {
                    val json = intent.getStringExtra(ListenerService.EXTRA_TELEGRAM_DATA) ?: return
                    parseTelegramResult(json)
                    loadingIndicator.visibility = View.GONE
                }
                ListenerService.ACTION_TELEGRAM_SAVED_ERROR -> {
                    val error = intent.getStringExtra(ListenerService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                    loadingIndicator.visibility = View.GONE
                    showError(error)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_todo_secondary, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectionBar = view.findViewById(R.id.selectionBar)
        selectionCount = view.findViewById(R.id.selectionCount)

        adapter = TelegramSavedAdapter { count ->
            if (count > 0) {
                selectionBar.visibility = View.VISIBLE
                recyclerView.setPadding(0, 52.dp(), 0, 0)
                selectionCount.text = "$count selected"
            } else {
                selectionBar.visibility = View.GONE
                recyclerView.setPadding(0, 0, 0, 0)
            }
        }

        loadingIndicator = view.findViewById(R.id.loadingIndicator)
        statusText = view.findViewById(R.id.statusText)
        recyclerView = view.findViewById(R.id.telegramRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.btnCopy).setOnClickListener {
            adapter.copySelected(requireContext())
        }
        view.findViewById<View>(R.id.btnShare).setOnClickListener {
            adapter.shareSelected(requireContext())
        }
        view.findViewById<View>(R.id.btnClear).setOnClickListener {
            adapter.clearSelection()
        }

        requireContext().registerReceiver(
            telegramReceiver,
            IntentFilter().apply {
                addAction(ListenerService.ACTION_TELEGRAM_SAVED_RESULT)
                addAction(ListenerService.ACTION_TELEGRAM_SAVED_ERROR)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        // Request telegram saved messages
        loadingIndicator.visibility = View.VISIBLE
        sendTelegramSavedRequest()
        // Start polling until data arrives
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onResume() {
        super.onResume()
        // Re-fetch when tab becomes visible again
        if (!hasData) {
            sendTelegramSavedRequest()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollHandler.removeCallbacks(pollRunnable)
        requireContext().unregisterReceiver(telegramReceiver)
    }

    fun refresh() {
        if (isAdded) sendTelegramSavedRequest()
    }

    private fun sendTelegramSavedRequest() {
        statusText.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        loadingIndicator.visibility = View.VISIBLE
        hasData = false
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_TELEGRAM_SAVED_REQ).apply {
            setPackage(requireContext().packageName)
        })
    }

    private fun parseTelegramResult(json: String) {
        try {
            val arr = JSONArray(json)
            if (arr.length() == 0) {
                showEmpty()
                return
            }
            val messages = mutableListOf<TelegramMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                messages.add(TelegramMessage(
                    id = obj.optInt("id", 0),
                    sender = obj.optString("sender", ""),
                    text = obj.optString("text", ""),
                    date = obj.optString("date", "")
                ))
            }
            statusText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(messages)
            hasData = true
            pollHandler.removeCallbacks(pollRunnable)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse telegram result: ${e.message}")
            showError("Failed to parse messages")
        }
    }

    private fun showError(message: String) {
        recyclerView.visibility = View.GONE
        statusText.text = message
        statusText.visibility = View.VISIBLE
        statusText.setOnClickListener {
            sendTelegramSavedRequest()
        }
    }

    private fun showEmpty() {
        recyclerView.visibility = View.GONE
        statusText.text = "No saved messages"
        statusText.visibility = View.VISIBLE
        statusText.setOnClickListener {
            sendTelegramSavedRequest()
        }
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density + 0.5f).toInt()
}
