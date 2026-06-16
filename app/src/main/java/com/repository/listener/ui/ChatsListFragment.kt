package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONObject
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.network.ChatHistoryClient
import com.repository.listener.network.ChatSummary
import com.repository.listener.network.CopilotSummary
import com.repository.listener.network.LiveSession
import com.repository.listener.network.RemoteSessionClient
import com.repository.listener.service.ListenerService
import com.repository.listener.ui.rc.RemoteControlActivity
import com.repository.listener.util.LogCollector

class ChatsListFragment : Fragment() {

    companion object {
        private const val TAG = "ChatsListFragment"
        private const val RETRY_DELAY_MS = 5000L
        private const val POLL_INTERVAL_MS = 10000L
        private const val COLOR_ERROR_TEXT = 0xFFfb4934.toInt()
        private val TYPE_LABELS = arrayOf("All", "Chats", "Remote Sessions")
        private val TYPE_VALUES = arrayOf("all", "chats", "rc")

        /**
         * When true, the list shows only active RC sessions grouped by folder.
         * When false ("All"), shows everything (active + ended) in chronological
         * order with date headers.
         *
         * Process-wide so the service-side chatlist_dump ADB hook reads the exact
         * same selection the UI rendered with.
         */
        @Volatile
        var showOnlyOpen: Boolean = false

        /**
         * Set by onResume / cleared by onPause. Used by the service-level ADB
         * chatlist_select_folder command to ask the fragment to rebuild the list
         * after mutating showOnlyOpen. May be null when the fragment isn't
         * attached -- in that case the new selection still takes effect on the
         * next displayList().
         */
        @Volatile
        var refreshCallback: (() -> Unit)? = null
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyContainer: LinearLayout
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var searchInput: EditText
    private lateinit var fab: FloatingActionButton
    private lateinit var fabNewChat: FloatingActionButton
    private lateinit var filterTypeSpinner: Spinner
    private lateinit var rcFolderChipGroup: ChipGroup
    private lateinit var adapter: ChatsListAdapter
    // (lastRenderedFolders diff-cache removed -- rebuildFolderChips always rebuilds.
    // Diff caching caused orphan chips to persist when the cached list and the
    // current ChipGroup view drifted out of sync (fragment view recreation, etc.).)
    private var client: ChatHistoryClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false
    private var countdownSeconds = 0
    private var liveSessions: List<LiveSession> = emptyList()
    private var lastChats: List<ChatSummary> = emptyList()
    private var copilotChats: List<CopilotSummary> = emptyList()
    private var isSearching = false
    private val searchDebounceRunnable = Runnable { performSearch() }
    private val rcSessions = mutableMapOf<String, ChatListItem.RemoteControlSession>()
    private var currentOffset = 0
    private var totalChats = 0
    private var isLoadingMore = false
    private var filterType = "all" // "all", "chats", "rc"
    private var rcReceiversRegistered = false
    private var thinkingReceiversRegistered = false
    private var suppressTypeSpinner = false
    private var swipingInProgress = false

    private val rcSessionStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getStringExtra(ListenerService.EXTRA_RC_SESSION_ID) ?: return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                val workDir = obj.optString("workDir", "").takeIf { it.isNotEmpty() && it != "null" }
                val name = obj.optString("name", "").ifEmpty { null }
                val existing = rcSessions[sessionId]
                if (existing != null) {
                    rcSessions[sessionId] = existing.copy(
                        status = "active",
                        workDir = workDir ?: existing.workDir,
                        sessionName = name ?: existing.sessionName,
                        turning = false
                    )
                } else if (workDir != null) {
                    rcSessions[sessionId] = ChatListItem.RemoteControlSession(
                        sessionId = sessionId,
                        workDir = workDir,
                        status = "active",
                        lastMessage = null,
                        startedAt = System.currentTimeMillis(),
                        sessionName = name,
                        turning = false
                    )
                }
                if (loaded && !isSearching) {
                    displayList(lastChats, liveSessions)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to parse RC session start: ${e.message}")
            }
        }
    }

    private val rcSessionEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getStringExtra(ListenerService.EXTRA_RC_SESSION_ID) ?: return
            rcSessions[sessionId]?.let {
                rcSessions[sessionId] = it.copy(status = "ended", turning = false)
            }
            if (loaded && !isSearching) {
                displayList(lastChats, liveSessions)
            }
        }
    }

    private val rcMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getStringExtra(ListenerService.EXTRA_RC_SESSION_ID) ?: return
            val data = intent.getStringExtra(ListenerService.EXTRA_RC_DATA) ?: return
            try {
                val obj = JSONObject(data)
                val text = obj.optString("text", "").trim()
                val isFinal = obj.optBoolean("isFinal", true)
                // Service stamps unread into the broadcast payload alongside isFinal.
                // Treat it as authoritative; missing key falls back to existing value.
                val hasUnread = obj.has("unread")
                val unreadFromMsg = obj.optBoolean("unread", false)
                rcSessions[sessionId]?.let { rc ->
                    val name = if (rc.sessionName == null && text.isNotEmpty()) text.take(50) else rc.sessionName
                    val lastMessage = if (text.isNotEmpty()) text.take(80) else rc.lastMessage
                    // Orange-pulsing while turning; green/red post-finish handled by adapter from the unread flag.
                    rcSessions[sessionId] = rc.copy(
                        lastMessage = lastMessage,
                        sessionName = name,
                        turning = !isFinal,
                        unread = if (hasUnread) unreadFromMsg else rc.unread
                    )
                    if (loaded && !isSearching) {
                        displayList(lastChats, liveSessions)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private val rcUnreadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getStringExtra(ListenerService.EXTRA_RC_SESSION_ID) ?: return
            val unread = intent.getBooleanExtra(ListenerService.EXTRA_RC_UNREAD, false)
            rcSessions[sessionId]?.let { rc ->
                if (rc.unread == unread) return
                rcSessions[sessionId] = rc.copy(unread = unread)
                if (loaded && !isSearching) {
                    displayList(lastChats, liveSessions)
                }
            }
        }
    }

    private val chatRespondingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (loaded && !isSearching) {
                displayList(lastChats, liveSessions)
            }
        }
    }

    private val rcThinkingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getStringExtra(ListenerService.EXTRA_RC_SESSION_ID) ?: return
            rcSessions[sessionId]?.let { rc ->
                rcSessions[sessionId] = rc.copy(turning = true)
            }
            if (loaded && !isSearching) {
                displayList(lastChats, liveSessions)
            }
        }
    }

    private val rcThinkingClearReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // rcMessageReceiver (listening to the same action) already drives
            // the turning flag off of isFinal; this is kept purely to trigger
            // a redraw in case message parsing threw.
            if (loaded && !isSearching) {
                displayList(lastChats, liveSessions)
            }
        }
    }

    private val retryRunnable = Runnable { loadChats() }

    private val pollRunnable = object : Runnable {
        override fun run() {
            loadSessions()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdownSeconds <= 0) return
            countdownSeconds--
            loadingText.text = "Retrying in ${countdownSeconds}s..."
            if (countdownSeconds > 0) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_chats_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.chatsRecycler)
        emptyContainer = view.findViewById(R.id.emptyContainer)
        loadingContainer = view.findViewById(R.id.loadingContainer)
        loadingText = view.findViewById(R.id.loadingText)
        searchInput = view.findViewById(R.id.searchInput)
        fab = view.findViewById(R.id.fabRemoteSession)
        fabNewChat = view.findViewById(R.id.fabNewChat)
        filterTypeSpinner = view.findViewById(R.id.filterTypeSpinner)
        rcFolderChipGroup = view.findViewById(R.id.rcFolderChipGroup)

        adapter = ChatsListAdapter(
            onChatClick = { chat ->
                val intent = Intent(requireContext(), ChatDetailActivity::class.java).apply {
                    putExtra(ChatDetailActivity.EXTRA_CONVERSATION_ID, chat.id)
                    putExtra(ChatDetailActivity.EXTRA_CONVERSATION_TITLE, chat.firstUserMessage ?: "(no messages)")
                    putExtra(ChatDetailActivity.EXTRA_CONVERSATION_CLOSED, chat.closed)
                }
                startActivity(intent)
            },
            onChatLongClick = { chat ->
                showChatMenu(chat)
            },
            onSessionLongClick = { session ->
                showSessionMenu(session)
            },
            onCopilotClick = { summary ->
                // The detail screen is owned by another module; launch by class
                // name + string-literal extras to avoid a compile dependency.
                val intent = Intent()
                    .setClassName(requireContext(), "com.repository.listener.ui.CopilotChatActivity")
                    .putExtra("copilot_conversation_id", summary.id)
                    .putExtra("copilot_title", summary.title)
                startActivity(intent)
            },
            onRcSessionClick = { rcSession ->
                // Tell the service this session has been read so the dot flips red
                // and the folder-chip badge decrements. Idempotent service-side.
                if (rcSession.unread) {
                    val markIntent = Intent(ListenerService.ACTION_RC_MARK_READ).apply {
                        setPackage(requireContext().packageName)
                        putExtra(ListenerService.EXTRA_RC_SESSION_ID, rcSession.sessionId)
                    }
                    requireContext().sendBroadcast(markIntent)
                }
                val intent = Intent(requireContext(), RemoteControlActivity::class.java).apply {
                    putExtra(RemoteControlActivity.EXTRA_SESSION_ID, rcSession.sessionId)
                    putExtra(RemoteControlActivity.EXTRA_WORK_DIR, rcSession.workDir)
                    putExtra(RemoteControlActivity.EXTRA_SESSION_STATUS, rcSession.status)
                    // Pass permission mode so the UI doesn't flash the wrong default
                    liveSessions.firstOrNull { it.sessionId == rcSession.sessionId }
                        ?.permissionMode?.let { mode ->
                            val phoneMode = when (mode) {
                                "ask_on_potentially_safe" -> "default"
                                "acceptAll" -> "acceptEdits"
                                else -> mode
                            }
                            putExtra(RemoteControlActivity.EXTRA_PERMISSION_MODE, phoneMode)
                        }
                }
                startActivity(intent)
            }
        )

        // Search bar with debounce
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchDebounceRunnable)
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    isSearching = false
                    handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                    displayList(lastChats, liveSessions)
                } else {
                    isSearching = true
                    handler.removeCallbacks(pollRunnable)
                    handler.postDelayed(searchDebounceRunnable, 400)
                }
            }
        })

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Swipe-to-end for active RC sessions.
        val cornerRadius = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
        )
        val swipeCallback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            0, androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT
        ) {
            private val redColor = ContextCompat.getColor(requireContext(), R.color.gbx_red)
            private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics
                )
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                letterSpacing = 0.05f
            }
            private val textWidth = textPaint.measureText("END SESSION")
            // Threshold where text starts fading in (px).
            private val fadeStart get() = textWidth + 80f
            // Threshold where text is fully opaque.
            private val fadeFull get() = textWidth + 160f

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val item = adapter.getItemAt(vh.adapterPosition)
                if (item is ChatListItem.RemoteControlSession && item.status == "active") {
                    return super.getSwipeDirs(rv, vh)
                }
                return 0
            }

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE) {
                    swipingInProgress = true
                } else if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_IDLE) {
                    swipingInProgress = false
                }
            }

            override fun onChildDraw(
                c: android.graphics.Canvas, rv: RecyclerView,
                vh: RecyclerView.ViewHolder, dX: Float, dY: Float,
                actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (dX == 0f) {
                    super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                    return
                }
                val v = vh.itemView
                val absDx = kotlin.math.abs(dX)
                // Card margins: the bg_chat_card has 12dp side margins.
                val margin = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics
                )
                val top = v.top.toFloat()
                val bottom = v.bottom.toFloat()
                val left = v.left.toFloat() + margin
                val right = v.right.toFloat() - margin

                // Background opacity ramps from 0 to full as the user drags.
                val bgAlpha = (absDx / (right - left)).coerceIn(0f, 1f)
                bgPaint.color = redColor
                bgPaint.alpha = (bgAlpha * 255).toInt()

                val rect = android.graphics.RectF(left, top, right, bottom)
                c.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

                // Text fades in after the card has moved enough to reveal it.
                val label = "END SESSION"
                val textBaseline = top + (bottom - top + textPaint.textSize) / 2f - 2f
                val textAlpha = ((absDx - fadeStart) / (fadeFull - fadeStart)).coerceIn(0f, 1f)

                if (textAlpha > 0f) {
                    textPaint.alpha = (textAlpha * 255).toInt()
                    if (dX > 0) {
                        // Swiping right: text anchored at left, slides in slightly.
                        val slideOffset = (1f - textAlpha) * 20f
                        c.drawText(label, left + 48f + slideOffset, textBaseline, textPaint)
                    } else {
                        // Swiping left: text anchored at right, slides in slightly.
                        val slideOffset = (1f - textAlpha) * 20f
                        c.drawText(label, right - textWidth - 48f - slideOffset, textBaseline, textPaint)
                    }
                }

                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                swipingInProgress = false
                @Suppress("DEPRECATION")
                val pos = vh.adapterPosition
                LogCollector.i(TAG, "onSwiped: pos=$pos direction=$direction")
                val item = adapter.getItemAt(pos) as? ChatListItem.RemoteControlSession
                if (item == null) {
                    adapter.notifyItemChanged(pos)
                    return
                }
                val ctx = context ?: return
                // Remove from adapter with animation first, then from local state.
                adapter.removeItemAt(pos)
                rcSessions.remove(item.sessionId)
                // Also remove the folder header if this was the last session in its group.
                if (showOnlyOpen) {
                    // Check if the preceding item is a FolderHeader with no sessions after it.
                    val prevPos = pos - 1
                    if (prevPos > -1) {
                        val prev = adapter.getItemAt(prevPos)
                        if (prev is ChatListItem.FolderHeader) {
                            val next = adapter.getItemAt(pos) // pos now points to what was after the removed item
                            if (next == null || next is ChatListItem.FolderHeader) {
                                adapter.removeItemAt(prevPos)
                            }
                        }
                    }
                }
                // Call orchestrator HTTP DELETE to properly end the session.
                val wsUrl = AppConfig.getOrchestratorUrl(ctx)
                val apiKey = AppConfig.getApiKey(ctx)
                val deviceId = AppConfig.getDeviceId(ctx)
                val remoteClient = RemoteSessionClient(wsUrl, apiKey, deviceId)
                remoteClient.endRcSession(item.sessionId) { result ->
                    result.onFailure { err ->
                        LogCollector.e(TAG, "End RC session failed: ${err.message}")
                        val rootView = view ?: return@onFailure
                        Snackbar.make(rootView, "End session failed: ${err.message}", Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(ctx, R.color.gbx_bg1))
                            .setTextColor(COLOR_ERROR_TEXT)
                            .show()
                    }
                }
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val totalItems = layoutManager.itemCount
                if (!isLoadingMore && lastVisible >= totalItems - 5 && currentOffset < totalChats) {
                    loadMoreChats()
                }
            }
        })

        // Type filter dropdown
        val typeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, TYPE_LABELS)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        filterTypeSpinner.adapter = typeAdapter
        suppressTypeSpinner = true
        filterTypeSpinner.setSelection(0)
        filterTypeSpinner.post { suppressTypeSpinner = false }
        filterTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (suppressTypeSpinner) return
                val newType = TYPE_VALUES[position]
                if (newType == filterType) return
                filterType = newType
                currentOffset = 0
                lastChats = emptyList()
                loadChats()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Static two-chip filter: "All" and "Only open".
        rebuildFolderChips()

        fab.setOnClickListener { onFabClicked() }
        fab.setOnLongClickListener {
            showPermissionModePicker()
            true
        }

        fabNewChat.setOnClickListener {
            val intent = Intent(requireContext(), ChatDetailActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Expose a refresher so ADB chatlist_select_folder can trigger a rebuild.
        refreshCallback = {
            handler.post {
                if (loaded && !isSearching) displayList(lastChats, liveSessions)
            }
        }
        loaded = false
        showLoading("Loading...")
        loadChats()
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        registerRcReceivers()
        registerThinkingReceivers()
    }

    override fun onPause() {
        super.onPause()
        refreshCallback = null
        cancelRetry()
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(searchDebounceRunnable)
        unregisterRcReceivers()
        unregisterThinkingReceivers()
    }

    private fun registerRcReceivers() {
        if (rcReceiversRegistered) return
        rcReceiversRegistered = true
        val ctx = requireContext()
        ctx.registerReceiver(
            rcSessionStartReceiver,
            IntentFilter(ListenerService.ACTION_RC_SESSION_START),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            rcSessionEndReceiver,
            IntentFilter(ListenerService.ACTION_RC_SESSION_END),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            rcMessageReceiver,
            IntentFilter(ListenerService.ACTION_RC_MESSAGE),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            rcUnreadReceiver,
            IntentFilter(ListenerService.ACTION_RC_UNREAD_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterRcReceivers() {
        if (!rcReceiversRegistered) return
        rcReceiversRegistered = false
        val ctx = requireContext()
        ctx.unregisterReceiver(rcSessionStartReceiver)
        ctx.unregisterReceiver(rcSessionEndReceiver)
        ctx.unregisterReceiver(rcMessageReceiver)
        ctx.unregisterReceiver(rcUnreadReceiver)
    }

    private fun registerThinkingReceivers() {
        if (thinkingReceiversRegistered) return
        thinkingReceiversRegistered = true
        val ctx = requireContext()
        ctx.registerReceiver(
            chatRespondingReceiver,
            IntentFilter(ListenerService.ACTION_CHAT_RESPONDING),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            rcThinkingReceiver,
            IntentFilter(ListenerService.ACTION_RC_THINKING),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            rcThinkingClearReceiver,
            IntentFilter(ListenerService.ACTION_RC_MESSAGE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterThinkingReceivers() {
        if (!thinkingReceiversRegistered) return
        thinkingReceiversRegistered = false
        val ctx = requireContext()
        ctx.unregisterReceiver(chatRespondingReceiver)
        ctx.unregisterReceiver(rcThinkingReceiver)
        ctx.unregisterReceiver(rcThinkingClearReceiver)
    }

    private fun showSessionMenu(session: LiveSession) {
        val ctx = context ?: return
        val options = arrayOf("Open", "Close")
        MaterialAlertDialogBuilder(ctx)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(ctx, RemoteControlActivity::class.java).apply {
                            putExtra(RemoteControlActivity.EXTRA_SESSION_ID, session.sessionId)
                            putExtra(RemoteControlActivity.EXTRA_WORK_DIR, session.workDir)
                        }
                        startActivity(intent)
                    }
                    1 -> closeSession(session)
                }
            }
            .show()
    }

    private fun closeSession(session: LiveSession) {
        val ctx = context ?: return
        val rootView = view ?: return
        val wsUrl = AppConfig.getOrchestratorUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)
        val deviceId = AppConfig.getDeviceId(ctx)
        val remoteClient = RemoteSessionClient(wsUrl, apiKey, deviceId)

        remoteClient.stopSession(session.pid) { result ->
            result.onSuccess {
                Snackbar.make(rootView, "Session closed", Snackbar.LENGTH_SHORT)
                    .setBackgroundTint(ContextCompat.getColor(ctx, R.color.gbx_bg1))
                    .setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                    .show()
                loadSessions()
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Close session failed: ${err.message}")
                Snackbar.make(rootView, "Failed: ${err.message}", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(ctx, R.color.gbx_bg1))
                    .setTextColor(COLOR_ERROR_TEXT)
                    .show()
            }
        }
    }

    private fun showChatMenu(chat: ChatSummary) {
        val ctx = context ?: return
        val rootView = view ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Delete chat?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val wsUrl = AppConfig.getOrchestratorUrl(ctx)
                val apiKey = AppConfig.getApiKey(ctx)
                val deleteClient = ChatHistoryClient(wsUrl, apiKey)
                deleteClient.deleteChat(chat.id) { result ->
                    result.onSuccess {
                        Snackbar.make(rootView, "Chat deleted", Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(ContextCompat.getColor(ctx, R.color.gbx_bg1))
                            .setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                            .show()
                        loadChats()
                    }
                    result.onFailure { err ->
                        LogCollector.e(TAG, "Delete chat failed: ${err.message}")
                        Snackbar.make(rootView, "Failed: ${err.message}", Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(ctx, R.color.gbx_bg1))
                            .setTextColor(COLOR_ERROR_TEXT)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSearch() {
        val query = searchInput.text?.toString()?.trim() ?: return
        if (query.isEmpty()) return
        val ctx = context ?: return
        val wsUrl = AppConfig.getOrchestratorUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)
        val deviceId = AppConfig.getDeviceId(ctx)

        var chatResults: List<ChatSummary>? = null
        var rcResults: List<ChatListItem.RemoteControlSession>? = null
        var pending = 0

        fun mergeAndDisplay() {
            val chats = chatResults ?: emptyList()
            val rcList = rcResults ?: emptyList()
            val searchRcSessions = mutableMapOf<String, ChatListItem.RemoteControlSession>()
            for (rc in rcList) searchRcSessions[rc.sessionId] = rc
            val items = buildSearchResults(chats, searchRcSessions)
            loadingContainer.visibility = View.GONE
            if (items.isEmpty()) {
                recyclerView.visibility = View.GONE
                emptyContainer.visibility = View.VISIBLE
            } else {
                emptyContainer.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.pinnedIds = context?.let { AppConfig.getPinnedChatIds(it) } ?: emptySet()
                adapter.submitList(items)
            }
        }

        if (filterType != "rc") {
            pending++
            val searchClient = ChatHistoryClient(wsUrl, apiKey)
            searchClient.searchChats(query) { result ->
                result.onSuccess { response -> chatResults = response.conversations }
                result.onFailure { chatResults = emptyList() }
                pending--
                if (pending == 0) mergeAndDisplay()
            }
        } else {
            chatResults = emptyList()
        }

        if (filterType != "chats") {
            pending++
            val remoteClient = RemoteSessionClient(wsUrl, apiKey, deviceId)
            remoteClient.searchRcSessions(query) { result ->
                result.onSuccess { sessions ->
                    val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                    rcResults = sessions
                        .map { rc ->
                            val startedAt = try {
                                isoFmt.parse(rc.createdAt.replace("Z", "").substringBefore("."))?.time ?: System.currentTimeMillis()
                            } catch (_: Exception) { System.currentTimeMillis() }
                            ChatListItem.RemoteControlSession(
                                sessionId = rc.sessionId,
                                workDir = rc.workDir,
                                status = rc.status,
                                lastMessage = null,
                                startedAt = startedAt,
                                sessionName = rc.title
                            )
                        }
                }
                result.onFailure { rcResults = emptyList() }
                pending--
                if (pending == 0) mergeAndDisplay()
            }
        } else {
            rcResults = emptyList()
        }

        if (pending == 0) mergeAndDisplay()
    }

    private fun buildSearchResults(
        chats: List<ChatSummary>,
        searchRcSessions: Map<String, ChatListItem.RemoteControlSession>
    ): List<ChatListItem> {
        val chatItems = chats.map { ChatListItem.Conversation(it) to it.lastActivityAt }
        val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val rcItems = searchRcSessions.values.map { rc ->
            rc to isoFmt.format(java.util.Date(rc.startedAt))
        }
        return (chatItems + rcItems)
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun displayList(chats: List<ChatSummary>, sessions: List<LiveSession>) {
        if (swipingInProgress) return
        loadingContainer.visibility = View.GONE
        val items = buildMergedList(chats, sessions)
        if (items.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyContainer.visibility = View.VISIBLE
        } else {
            emptyContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.pinnedIds = context?.let { AppConfig.getPinnedChatIds(it) } ?: emptySet()
            adapter.respondingChatIds = ListenerService.respondingChatIds.toSet()
            adapter.thinkingRcSessionIds = ListenerService.thinkingRcStartTimes.keys.toSet()
            adapter.submitList(items)
        }
    }

    private fun onFabClicked() {
        val ctx = context ?: return
        val wsUrl = AppConfig.getOrchestratorUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)
        val deviceId = AppConfig.getDeviceId(ctx)
        val remoteClient = RemoteSessionClient(wsUrl, apiKey, deviceId)

        val sheet = RemoteSessionBottomSheet().apply {
            isLoading = true
        }
        sheet.show(childFragmentManager, "remote_session")

        remoteClient.getDirs { result ->
            result.onSuccess { response ->
                if (!response.available) {
                    sheet.showError("PC agent is not connected")
                    return@onSuccess
                }
                sheet.onDirSelected = { workDir ->
                    showConversationPicker(remoteClient, workDir, AppConfig.getDefaultPermissionMode(ctx))
                }
                sheet.showDirs(response.dirs)
            }
            result.onFailure { err ->
                sheet.showError("Failed to connect: ${err.message}")
            }
        }
    }

    /**
     * After a folder is chosen, list its prior conversations so the user can
     * resume one or start fresh. Resuming passes the conversation's sessionId to
     * startSession, which makes pc-agent spawn the CLI with --resume.
     */
    private fun showConversationPicker(remoteClient: RemoteSessionClient, workDir: String, permissionMode: String?) {
        val picker = ConversationPickerBottomSheet().apply {
            this.workDir = workDir
            isLoading = true
            onNewConversation = {
                startRemoteSession(remoteClient, workDir, permissionMode, null)
            }
            onConversationSelected = { conv ->
                startRemoteSession(remoteClient, workDir, permissionMode, conv.sessionId)
            }
        }
        picker.show(childFragmentManager, "conversation_picker")

        remoteClient.listConversations(workDir) { result ->
            result.onSuccess { list -> picker.showConversations(list) }
            result.onFailure { err -> picker.showError("Failed to load conversations: ${err.message}") }
        }
    }

    private fun startRemoteSession(remoteClient: RemoteSessionClient, workDir: String, permissionMode: String?, resumeSessionId: String? = null) {
        val ctx = context ?: return
        val rootView = view ?: return
        Snackbar.make(rootView, if (resumeSessionId != null) "Resuming session..." else "Starting session...", Snackbar.LENGTH_INDEFINITE)
            .setBackgroundTint(ContextCompat.getColor(ctx, R.color.gbx_bg1))
            .setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
            .show()

        remoteClient.startSession(workDir, permissionMode, resumeSessionId) { result ->
            result.onSuccess { response ->
                val intent = Intent(requireContext(), RemoteControlActivity::class.java).apply {
                    putExtra(RemoteControlActivity.EXTRA_SESSION_ID, response.sessionId)
                    putExtra(RemoteControlActivity.EXTRA_WORK_DIR, response.workDir)
                }
                startActivity(intent)
                loadSessions()
                Snackbar.make(rootView, "Session started", Snackbar.LENGTH_SHORT)
                    .setBackgroundTint(ContextCompat.getColor(ctx, R.color.gbx_bg1))
                    .setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                    .show()
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Remote session start failed: ${err.message}")
                Snackbar.make(rootView, "Failed: ${err.message}", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(ctx, R.color.gbx_bg1))
                    .setTextColor(COLOR_ERROR_TEXT)
                    .show()
            }
        }
    }

    /**
     * Picker for the default RC permission mode (orchestrator-side names persisted verbatim).
     * Index 0 = "(default)" -- no value set, orchestrator applies its canonical default.
     * Triggered via long-press on the remote-session FAB.
     */
    private fun showPermissionModePicker() {
        val ctx = context ?: return
        val labels = arrayOf(
            "(default)",
            "Ask on potentially unsafe (ask_on_potentially_safe)",
            "Accept edits (acceptAll)",
            "Bypass all prompts (bypassAll)",
            "Plan only (plan)"
        )
        val values = arrayOf<String?>(
            null,
            "ask_on_potentially_safe",
            "acceptAll",
            "bypassAll",
            "plan"
        )
        val current = AppConfig.getDefaultPermissionMode(ctx)
        val checked = values.indexOf(current).let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Default permission mode")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppConfig.setDefaultPermissionMode(ctx, values[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSessions() {
        val ctx = context ?: return
        val wsUrl = AppConfig.getOrchestratorUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)
        val deviceId = AppConfig.getDeviceId(ctx)
        val remoteClient = RemoteSessionClient(wsUrl, apiKey, deviceId)

        remoteClient.listSessions { result ->
            result.onSuccess { sessions ->
                liveSessions = sessions.filter { it.alive }
                rebuildList()
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Failed to load sessions: ${err.message}")
            }
        }

        remoteClient.listRcSessions { result ->
            result.onSuccess { sessions ->
                for (rc in sessions) {
                    val existing = rcSessions[rc.sessionId]
                    if (existing == null) {
                        val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }
                        val startedAt = try {
                            isoFmt.parse(rc.createdAt.replace("Z", "").substringBefore("."))?.time ?: System.currentTimeMillis()
                        } catch (_: Exception) { System.currentTimeMillis() }

                        // Default to "ended" for REST-ingested sessions; the
                        // rcDumpState reconciliation pass below promotes any
                        // session that the service knows is genuinely active.
                        rcSessions[rc.sessionId] = ChatListItem.RemoteControlSession(
                            sessionId = rc.sessionId,
                            workDir = rc.workDir,
                            status = "ended",
                            lastMessage = null,
                            startedAt = startedAt,
                            sessionName = rc.title
                        )
                    } else if (rc.title != null && existing.sessionName != rc.title) {
                        // Title became available (or changed) since last fetch --
                        // refresh the in-memory copy so the chip stops showing
                        // the workDir-basename fallback.
                        rcSessions[rc.sessionId] = existing.copy(sessionName = rc.title)
                    }
                }
                // Reconcile with service-level state: rcDumpState is the
                // authoritative source populated by live WS events (which
                // may have arrived before this fragment registered its
                // receivers). Promotes REST-"ended" sessions to their real
                // status so folder chips appear reliably.
                for ((sid, dump) in ListenerService.rcDumpState) {
                    val rc = rcSessions[sid] ?: continue
                    if (dump.status == "active" && rc.status != "active") {
                        rcSessions[sid] = rc.copy(
                            status = "active",
                            workDir = dump.workDir.ifEmpty { rc.workDir },
                            turning = dump.turning,
                            unread = dump.unread
                        )
                    }
                }
                if (loaded && !isSearching) {
                    displayList(lastChats, liveSessions)
                }
            }
            result.onFailure { err ->
                LogCollector.e(TAG, "Failed to load RC sessions: ${err.message}")
            }
        }
    }

    private fun loadChats() {
        val ctx = context ?: return
        val wsUrl = AppConfig.getOrchestratorUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)
        client = ChatHistoryClient(wsUrl, apiKey)

        loadSessions()
        loadCopilotChats()

        if (filterType != "rc") {
            client?.listChats(limit = 30, offset = 0) { result ->
                result.onSuccess { response ->
                    loaded = true
                    cancelRetry()
                    lastChats = response.conversations
                    totalChats = response.total
                    currentOffset = response.conversations.size
                    if (!isSearching) displayList(lastChats, liveSessions)
                }
                result.onFailure { err ->
                    LogCollector.e(TAG, "Failed to load chats: ${err.message}")
                    if (liveSessions.isNotEmpty() && !loaded) {
                        loaded = true
                        cancelRetry()
                        displayList(emptyList(), liveSessions)
                    } else if (!loaded) {
                        scheduleRetry()
                    }
                }
            }
        } else {
            loaded = true
            cancelRetry()
            lastChats = emptyList()
            totalChats = 0
            currentOffset = 0
            displayList(lastChats, liveSessions)
        }
    }

    private fun rebuildList() {
        if (!loaded) return
        if (isSearching) return
        displayList(lastChats, liveSessions)
    }

    /**
     * Fetch Copilot sessions off the main thread (ChatHistoryClient suspend
     * funcs run their HTTP call via OkHttp's async dispatcher; the await
     * resumes here on the main thread). Errors return emptyList() inside the
     * client, so this never throws. Re-renders the merged list on success.
     */
    private fun loadCopilotChats() {
        val c = client ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = c.getCopilotChats()
            copilotChats = result
            if (loaded && !isSearching) {
                displayList(lastChats, liveSessions)
            }
        }
    }

    private fun loadMoreChats() {
        if (isLoadingMore || isSearching || filterType == "rc") return
        val ctx = context ?: return
        isLoadingMore = true
        val wsUrl = AppConfig.getOrchestratorUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)
        val chatClient = ChatHistoryClient(wsUrl, apiKey)
        chatClient.listChats(limit = 30, offset = currentOffset) { result ->
            isLoadingMore = false
            result.onSuccess { response ->
                lastChats = lastChats + response.conversations
                currentOffset = lastChats.size
                if (!isSearching) displayList(lastChats, liveSessions)
            }
        }
    }

    private fun buildMergedList(
        chats: List<ChatSummary>,
        sessions: List<LiveSession>
    ): List<ChatListItem> {
        val pinnedIds = context?.let { AppConfig.getPinnedChatIds(it) } ?: emptySet()

        if (showOnlyOpen) {
            // "Only open" chip: show active RC sessions grouped by folder.
            return groupActiveByFolder(rcSessions.values)
        }

        // "All" chip: include every RC session (active AND ended). Format
        // startedAt as an ISO string so it sorts next to chats/session items,
        // whose timestamps are already ISO strings.
        val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val allRcItems = if (filterType == "chats") emptyList() else {
            rcSessions.values
                .sortedByDescending { it.startedAt }
                .map { rc -> (rc as ChatListItem) to isoFmt.format(java.util.Date(rc.startedAt)) }
        }

        val pinned = if (filterType == "rc") emptyList() else {
            chats.filter { pinnedIds.contains(it.id) }
                .sortedByDescending { it.lastActivityAt }
                .map { ChatListItem.Conversation(it) }
        }

        val unpinnedChats = if (filterType == "rc") emptyList() else {
            chats.filter { !pinnedIds.contains(it.id) }
                .map { ChatListItem.Conversation(it) to it.lastActivityAt }
        }
        val sessionItems = if (filterType == "chats") emptyList() else {
            sessions
                .filter { session -> !rcSessions.containsKey(session.sessionId) }
                .map { ChatListItem.RemoteSession(it) to it.startedAt }
        }
        // Copilot sessions interleave with chats by recency. They are
        // conversation-like, so they show under "All" and "Chats" but not the
        // RC-only filter.
        val copilotItems = if (filterType == "rc") emptyList() else {
            copilotChats.map {
                (ChatListItem.CopilotConversation(it) as ChatListItem) to it.lastActivityAt
            }
        }
        val sortedRest = (unpinnedChats + sessionItems + allRcItems + copilotItems)
            .sortedByDescending { it.second }

        val rest: List<ChatListItem> = if (filterType != "all") {
            sortedRest.map { it.first }
        } else {
            // Inject gallery-style date headers ("Today", "Yesterday",
            // "13 May 2026") between buckets, based on each item's
            // last-activity timestamp.
            val out = mutableListOf<ChatListItem>()
            var lastBucket: String? = null
            val nowMs = System.currentTimeMillis()
            for ((item, iso) in sortedRest) {
                val ms = parseIsoToMs(iso) ?: 0L
                val bucket = dateBucketLabel(ms, nowMs)
                if (bucket != lastBucket) {
                    out.add(ChatListItem.DateHeader(bucket))
                    lastBucket = bucket
                }
                out.add(item)
            }
            out
        }

        return pinned + rest
    }

    private val isoParserStrict = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private fun parseIsoToMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        // Prefer Instant.parse: handles offsets (+00:00), missing millis, etc.
        try { return java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) {}
        return try { isoParserStrict.parse(iso)?.time } catch (_: Exception) { null }
    }

    private val dayMonthFmt by lazy {
        java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
    }
    private val dayMonthYearFmt by lazy {
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
    }

    private fun dateBucketLabel(ms: Long, nowMs: Long): String {
        if (ms <= 0L) return "Earlier"
        val tz = java.util.TimeZone.getDefault()
        val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = ms }
        val today = java.util.Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val sameDay = cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay) return "Today"
        val yesterday = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR)
        if (isYesterday) return "Yesterday"
        val sameYear = cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
        val date = java.util.Date(ms)
        return if (sameYear) dayMonthFmt.format(date) else dayMonthYearFmt.format(date)
    }

    /**
     * Build the static two-chip filter bar: "All" and "Only open".
     * Called once from onViewCreated.
     */
    private fun rebuildFolderChips() {
        if (!::rcFolderChipGroup.isInitialized) return
        val ctx = requireContext()
        rcFolderChipGroup.removeAllViews()

        fun makeChip(label: String, isOpen: Boolean): Chip {
            return Chip(ctx).apply {
                text = label
                id = View.generateViewId()
                isCheckable = true
                isClickable = true
                setChipBackgroundColorResource(R.color.gbx_bg1)
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                chipStrokeWidth = 1f
                tag = isOpen
            }
        }

        val allChip = makeChip("All", false)
        val openChip = makeChip("Only open", true)
        rcFolderChipGroup.addView(allChip)
        rcFolderChipGroup.addView(openChip)

        fun syncChecked() {
            allChip.isChecked = !showOnlyOpen
            openChip.isChecked = showOnlyOpen
        }

        allChip.setOnClickListener {
            if (!showOnlyOpen) { allChip.isChecked = true; return@setOnClickListener }
            showOnlyOpen = false
            syncChecked()
            if (loaded && !isSearching) displayList(lastChats, liveSessions)
        }
        openChip.setOnClickListener {
            if (showOnlyOpen) { openChip.isChecked = true; return@setOnClickListener }
            showOnlyOpen = true
            syncChecked()
            if (loaded && !isSearching) displayList(lastChats, liveSessions)
        }
        syncChecked()
    }

    private fun scheduleRetry() {
        recyclerView.visibility = View.GONE
        emptyContainer.visibility = View.GONE
        loadingContainer.visibility = View.VISIBLE

        countdownSeconds = (RETRY_DELAY_MS / 1000).toInt()
        loadingText.text = "Retrying in ${countdownSeconds}s..."
        handler.postDelayed(countdownRunnable, 1000)
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun cancelRetry() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(countdownRunnable)
    }

    private fun showLoading(text: String) {
        recyclerView.visibility = View.GONE
        emptyContainer.visibility = View.GONE
        loadingContainer.visibility = View.VISIBLE
        loadingText.text = text
    }
}
