package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.repository.listener.R
import com.repository.listener.service.ListenerService
import java.util.concurrent.Executors

class GlassesFilesFragment : Fragment() {

    private lateinit var statusBar: LinearLayout
    private lateinit var txtSyncStatus: TextView
    private lateinit var spinnerSync: ProgressBar
    private lateinit var btnRefresh: MaterialButton
    private lateinit var progressSync: ProgressBar
    private lateinit var recyclerCatalogue: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var chipGroupFilter: ChipGroup

    private enum class Filter { ALL, PHOTOS, VIDEOS }
    private var activeFilter: Filter = Filter.ALL

    private var lastScan: Quad = Quad(emptyList(), emptyList(), emptyList(), emptyList())

    // Selection bar
    private lateinit var selectionBar: LinearLayout
    private lateinit var btnCloseSelection: ImageButton
    private lateinit var txtSelectionCount: TextView
    private lateinit var btnShare: ImageButton
    private lateinit var btnDelete: ImageButton

    private var catalogueAdapter: FileCatalogueAdapter? = null
    private var isGlassesConnected = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var syncWatchdog: Runnable? = null
    private var isSyncing = false
    private var isDeletingFromGlasses = false

    private val loadExecutor = Executors.newSingleThreadExecutor()

    private val glassesStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(ListenerService.EXTRA_GLASSES_CONNECTED, false)
            activity?.runOnUiThread {
                isGlassesConnected = connected
                updateRefreshState()
            }
        }
    }

    private val syncStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ListenerService.EXTRA_SYNC_STATE) ?: return
            val message = intent.getStringExtra(ListenerService.EXTRA_SYNC_MESSAGE) ?: ""
            val current = intent.getIntExtra(ListenerService.EXTRA_SYNC_CURRENT, 0)
            val total = intent.getIntExtra(ListenerService.EXTRA_SYNC_TOTAL, 0)
            activity?.runOnUiThread {
                updateSyncUI(state, message, current, total)
            }
        }
    }

    private val deleteStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ListenerService.EXTRA_SYNC_STATE) ?: return
            val message = intent.getStringExtra(ListenerService.EXTRA_SYNC_MESSAGE) ?: ""
            val current = intent.getIntExtra(ListenerService.EXTRA_SYNC_CURRENT, 0)
            val total = intent.getIntExtra(ListenerService.EXTRA_SYNC_TOTAL, 0)
            activity?.runOnUiThread {
                updateDeleteUI(state, message, current, total)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_glasses_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusBar = view.findViewById(R.id.statusBar)
        txtSyncStatus = view.findViewById(R.id.txtSyncStatus)
        spinnerSync = view.findViewById(R.id.spinnerSync)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        progressSync = view.findViewById(R.id.progressSync)
        recyclerCatalogue = view.findViewById(R.id.recyclerCatalogue)
        txtEmpty = view.findViewById(R.id.txtEmpty)
        chipGroupFilter = view.findViewById(R.id.chipGroupFilter)

        chipGroupFilter.setOnCheckedStateChangeListener { _, ids ->
            activeFilter = when (ids.firstOrNull()) {
                R.id.chipPhotos -> Filter.PHOTOS
                R.id.chipVideos -> Filter.VIDEOS
                else -> Filter.ALL
            }
            renderCatalogue()
        }

        // Selection bar
        selectionBar = view.findViewById(R.id.selectionBar)
        btnCloseSelection = view.findViewById(R.id.btnCloseSelection)
        txtSelectionCount = view.findViewById(R.id.txtSelectionCount)
        btnShare = view.findViewById(R.id.btnShare)
        btnDelete = view.findViewById(R.id.btnDelete)

        catalogueAdapter = FileCatalogueAdapter(requireContext().contentResolver) { file ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(toGrantableUri(file.uri), file.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                // No app available to handle this file type
            }
        }

        catalogueAdapter?.onSelectionModeChanged = { active ->
            if (active) {
                statusBar.visibility = View.GONE
                selectionBar.visibility = View.VISIBLE
            } else {
                statusBar.visibility = View.VISIBLE
                selectionBar.visibility = View.GONE
            }
        }

        catalogueAdapter?.onSelectionChanged = { count ->
            txtSelectionCount.text = "$count selected"
            val hasSelection = count > 0
            btnShare.isEnabled = hasSelection
            btnDelete.isEnabled = hasSelection && !isDeletingFromGlasses
        }

        val layoutManager = GridLayoutManager(requireContext(), 3)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return catalogueAdapter?.getSpanSize(position) ?: 1
            }
        }
        recyclerCatalogue.layoutManager = layoutManager
        recyclerCatalogue.adapter = catalogueAdapter

        btnRefresh.setOnClickListener {
            val ctx = requireContext()
            when {
                isSyncing -> {
                    Toast.makeText(ctx, "Sync already in progress", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // Optimistic UI: flip to syncing state immediately so the user sees feedback
                    // even if the first broadcast travels through a slow queue. If no progress
                    // arrives within the watchdog window, the FSM timeout-watchdog below resets us.
                    // No isGlassesConnected gate: the broadcast handler in ListenerService kicks
                    // a BLE wake + RFCOMM reconnect when disconnected, then forceSync() runs as
                    // soon as the link comes up (dirty=true / onBtConnected path in GlassesSyncClient).
                    // Watchdog allows 20s -- BR/EDR page + RFCOMM connect from a cold BLE-only state
                    // can take 5-15s on this hardware; 8s used to fire spuriously.
                    isSyncing = true
                    spinnerSync.visibility = View.VISIBLE
                    txtSyncStatus.text = if (isGlassesConnected) "Starting sync..." else "Waking glasses..."
                    updateRefreshState()
                    syncWatchdog?.let { mainHandler.removeCallbacks(it) }
                    val w = Runnable {
                        if (isSyncing && !isAdded) return@Runnable
                        val starting = txtSyncStatus.text == "Starting sync..." ||
                            txtSyncStatus.text == "Waking glasses..."
                        if (isSyncing && starting) {
                            isSyncing = false
                            spinnerSync.visibility = View.GONE
                            txtSyncStatus.text = "Sync did not start"
                            Toast.makeText(ctx, "Sync did not start -- check BT link", Toast.LENGTH_SHORT).show()
                            updateRefreshState()
                        }
                    }
                    syncWatchdog = w
                    mainHandler.postDelayed(w, 20_000L)
                    Toast.makeText(ctx, "Sync requested", Toast.LENGTH_SHORT).show()
                    ctx.sendBroadcast(Intent(ListenerService.ACTION_START_GLASSES_SYNC).apply {
                        setPackage(ctx.packageName)
                    })
                }
            }
        }

        btnCloseSelection.setOnClickListener {
            catalogueAdapter?.exitSelectionMode()
        }

        btnDelete.setOnClickListener {
            deleteFromBoth()
        }

        btnShare.setOnClickListener {
            shareSelected()
        }

        loadCatalogue()
    }

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        ctx.registerReceiver(
            glassesStateReceiver,
            IntentFilter(ListenerService.ACTION_GLASSES_STATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            syncStatusReceiver,
            IntentFilter(ListenerService.ACTION_GLASSES_SYNC_STATUS),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            deleteStatusReceiver,
            IntentFilter(ListenerService.ACTION_GLASSES_DELETE_STATUS),
            Context.RECEIVER_NOT_EXPORTED
        )

        isGlassesConnected = ListenerService.glassesConnected
        updateRefreshState()
        loadCatalogue()

        if (isGlassesConnected && !isSyncing) {
            ctx.sendBroadcast(Intent(ListenerService.ACTION_START_GLASSES_SYNC).apply {
                setPackage(ctx.packageName)
            })
        }
    }

    override fun onPause() {
        super.onPause()
        val ctx = requireContext()
        ctx.unregisterReceiver(glassesStateReceiver)
        ctx.unregisterReceiver(syncStatusReceiver)
        ctx.unregisterReceiver(deleteStatusReceiver)
        syncWatchdog?.let { mainHandler.removeCallbacks(it) }
        syncWatchdog = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        catalogueAdapter?.shutdown()
        catalogueAdapter = null
    }

    private fun updateRefreshState() {
        // Enable regardless of BT state: the click handler triggers a BLE wake +
        // RFCOMM reconnect when disconnected, so the user can press it any time.
        btnRefresh.isEnabled = !isSyncing
    }

    private fun updateSyncUI(state: String, message: String, current: Int, total: Int) {
        // Any progress event means the sync actually started -- cancel the watchdog toast.
        syncWatchdog?.let { mainHandler.removeCallbacks(it) }
        syncWatchdog = null

        when (state) {
            "idle" -> {
                isSyncing = false
                spinnerSync.visibility = View.GONE
                progressSync.visibility = View.GONE
                updateRefreshState()
                loadCatalogue()
            }
            "connecting", "listing" -> {
                isSyncing = true
                txtSyncStatus.text = if (state == "connecting") "Connecting..." else "Listing files..."
                spinnerSync.visibility = View.VISIBLE
                progressSync.visibility = View.GONE
                updateRefreshState()
            }
            "merging" -> {
                isSyncing = true
                txtSyncStatus.text = message.ifEmpty { "Merging AR overlays..." }
                spinnerSync.visibility = View.VISIBLE
                progressSync.visibility = View.GONE
                updateRefreshState()
            }
            "downloading" -> {
                isSyncing = true
                spinnerSync.visibility = View.VISIBLE
                if (total > 0) {
                    txtSyncStatus.text = "Downloading $current/$total..."
                    progressSync.visibility = View.VISIBLE
                    progressSync.isIndeterminate = false
                    progressSync.max = total
                    progressSync.progress = current
                } else {
                    txtSyncStatus.text = message.ifEmpty { "Downloading..." }
                    progressSync.visibility = View.GONE
                }
                updateRefreshState()
            }
            "done" -> {
                isSyncing = false
                spinnerSync.visibility = View.GONE
                progressSync.visibility = View.GONE
                val syncedCount = if (total > 0) total else current
                txtSyncStatus.text = if (syncedCount > 0) "Synced $syncedCount new files"
                                    else message.ifEmpty { "Sync complete" }
                updateRefreshState()
                loadCatalogue()
            }
            "failed" -> {
                isSyncing = false
                spinnerSync.visibility = View.GONE
                progressSync.visibility = View.GONE
                txtSyncStatus.text = "Sync failed: ${message.ifEmpty { "unknown error" }}"
                updateRefreshState()
            }

            // --- new GlassesSyncClient FSM states (uppercase) ---
            "HANDSHAKING", "FETCHING_MANIFEST", "DIFFING", "OPENING_WIFI", "JOINING", "CLOSING" -> {
                isSyncing = true
                txtSyncStatus.text = when (state) {
                    "HANDSHAKING" -> "Checking for changes..."
                    "FETCHING_MANIFEST" -> "Reading glasses catalogue..."
                    "DIFFING" -> "Computing diff..."
                    "OPENING_WIFI" -> "Opening WiFi Direct..."
                    "JOINING" -> "Joining WiFi Direct..."
                    "CLOSING" -> "Finishing..."
                    else -> state
                }
                spinnerSync.visibility = View.VISIBLE
                progressSync.visibility = View.GONE
                updateRefreshState()
            }
            "PULLING" -> {
                isSyncing = true
                spinnerSync.visibility = View.VISIBLE
                if (total > 0) {
                    txtSyncStatus.text = "Pulling ${current}/${total}..."
                    progressSync.visibility = View.VISIBLE
                    progressSync.isIndeterminate = false
                    progressSync.max = total
                    progressSync.progress = current
                } else {
                    txtSyncStatus.text = message.ifEmpty { "Pulling files..." }
                    progressSync.visibility = View.GONE
                }
                updateRefreshState()
            }
            "COMPLETE" -> {
                isSyncing = false
                spinnerSync.visibility = View.GONE
                progressSync.visibility = View.GONE
                val synced = if (total > 0) total else current
                val text = if (synced > 0) "Synced $synced file${if (synced == 1) "" else "s"}"
                           else "Already up to date"
                txtSyncStatus.text = text
                Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
                updateRefreshState()
                loadCatalogue()
            }
            "ERROR" -> {
                isSyncing = false
                spinnerSync.visibility = View.GONE
                progressSync.visibility = View.GONE
                val err = message.ifEmpty { "sync error" }
                txtSyncStatus.text = "Sync error: $err"
                Toast.makeText(requireContext(), "Sync error: $err", Toast.LENGTH_LONG).show()
                updateRefreshState()
            }
        }
    }

    private fun updateDeleteUI(state: String, message: String, current: Int, total: Int) {
        when (state) {
            "connecting", "listing", "deleting" -> {
                isDeletingFromGlasses = true
                btnDelete.isEnabled = false
                txtSelectionCount.text = message.ifEmpty { "Deleting from glasses..." }
            }
            "done" -> {
                isDeletingFromGlasses = false
                btnDelete.isEnabled = true
                val adapter = catalogueAdapter ?: return
                val count = adapter.getSelectedFiles().size
                txtSelectionCount.text = "$count selected"
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
            "failed" -> {
                isDeletingFromGlasses = false
                btnDelete.isEnabled = true
                val adapter = catalogueAdapter ?: return
                val count = adapter.getSelectedFiles().size
                txtSelectionCount.text = "$count selected"
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Delete selected files from both devices in a single action.
     *  - Phone: MediaStore ContentResolver delete (local copies).
     *  - Glasses: ACTION_START_GLASSES_DELETE broadcast -> GlassesSyncClient.requestDeleteByRelPath
     *    -> DELETE frame over RFCOMM -> filesync removes the master copy + ack.
     * Optimistic UI: rows are removed immediately; errors on either side surface via Toast.
     */
    private fun deleteFromBoth() {
        val adapter = catalogueAdapter ?: return
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) return
        val ctx = requireContext()
        val count = selected.size
        val filenames = ArrayList(selected.map { it.name })

        // Glasses delete is async; fire immediately -- the RFCOMM DELETE queues through
        // the sync FSM, and glasses' filesync acks when the manifest is updated.
        ctx.sendBroadcast(
            Intent(ListenerService.ACTION_START_GLASSES_DELETE).apply {
                setPackage(ctx.packageName)
                putStringArrayListExtra(ListenerService.EXTRA_DELETE_FILENAMES, filenames)
            }
        )

        // Phone-side delete on the load executor so UI stays responsive.
        // Files pulled by GlassesSyncClient land in the app-private external
        // dir (getExternalFilesDir(PICTURES)/Repository) and are exposed via
        // Uri.fromFile -- ContentResolver.delete doesn't handle file:// at
        // all (returns 0, no exception), so we route by scheme.
        val resolver = ctx.contentResolver
        loadExecutor.execute {
            val deletedUris = mutableSetOf<Uri>()
            var phoneFailures = 0
            for (file in selected) {
                try {
                    val ok = when (file.uri.scheme) {
                        "file" -> {
                            val path = file.uri.path
                            if (path == null) false else {
                                val f = java.io.File(path)
                                !f.exists() || f.delete()
                            }
                        }
                        "content" -> resolver.delete(file.uri, null, null) > 0
                        else -> false
                    }
                    if (ok) deletedUris.add(file.uri) else {
                        phoneFailures++
                        android.util.Log.w("GlassesFilesFragment", "delete returned false: ${file.uri}")
                    }
                } catch (e: Exception) {
                    phoneFailures++
                    android.util.Log.w("GlassesFilesFragment", "delete threw for ${file.uri}: ${e.message}")
                }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (deletedUris.isNotEmpty()) adapter.removeItems(deletedUris)
                val msg = when {
                    phoneFailures == 0 -> "Deleting $count file${if (count == 1) "" else "s"} from both devices"
                    deletedUris.isEmpty() -> "Phone delete failed; glasses delete queued"
                    else -> "Deleting from glasses; $phoneFailures phone-side error(s)"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Wrap a file:// URI (from the app-private Repository dir) into a
     * content:// URI that other apps can read, via our FileProvider.
     * content:// URIs pass through unchanged.
     */
    private fun toGrantableUri(uri: Uri): Uri {
        if (uri.scheme != "file") return uri
        val ctx = context ?: return uri
        val path = uri.path ?: return uri
        return try {
            androidx.core.content.FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                java.io.File(path),
            )
        } catch (e: Exception) {
            android.util.Log.w("GlassesFilesFragment", "toGrantableUri failed: ${e.message}")
            uri
        }
    }

    private fun shareSelected() {
        val adapter = catalogueAdapter ?: return
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) return

        val intent = if (selected.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                val file = selected.first()
                type = file.mimeType
                putExtra(Intent.EXTRA_STREAM, toGrantableUri(file.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(selected.map { toGrantableUri(it.uri) })
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun loadCatalogue() {
        val ctx = context ?: return

        loadExecutor.execute {
            val syncRoot = java.io.File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir,
                "Repository"
            )
            val scan = scanSyncDir(syncRoot)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                lastScan = scan
                renderCatalogue()
            }
        }
    }

    private fun renderCatalogue() {
        val (images, videos, audio, other) = lastScan
        val gallery: List<CatalogueItem.MediaFile> = when (activeFilter) {
            Filter.ALL -> images + videos
            Filter.PHOTOS -> images
            Filter.VIDEOS -> videos
        }.sortedByDescending { fileTimestamp(it) }

        val items = mutableListOf<CatalogueItem>()
        if (gallery.isNotEmpty()) {
            val grouped = groupByDate(gallery)
            for ((label, files) in grouped) {
                items.add(CatalogueItem.Header(label, files.size))
                items.addAll(files)
            }
        }
        // Audio + Other only when no media filter is active.
        if (activeFilter == Filter.ALL) {
            if (audio.isNotEmpty()) {
                items.add(CatalogueItem.Header("Audio", audio.size))
                items.addAll(audio)
            }
            if (other.isNotEmpty()) {
                items.add(CatalogueItem.Header("Other", other.size))
                items.addAll(other)
            }
        }

        catalogueAdapter?.submitFullList(items)

        if (items.isEmpty()) {
            txtEmpty.visibility = View.VISIBLE
            recyclerCatalogue.visibility = View.GONE
            if (!isSyncing) txtSyncStatus.text = "No files synced"
        } else {
            txtEmpty.visibility = View.GONE
            recyclerCatalogue.visibility = View.VISIBLE
            if (!isSyncing) {
                val total = images.size + videos.size + audio.size + other.size
                txtSyncStatus.text = "$total files"
            }
        }
    }

    private fun fileTimestamp(file: CatalogueItem.MediaFile): Long {
        return parseFilenameDate(file.name) ?: file.dateModified
    }

    /**
     * Bucket files into Today / Yesterday / "d MMMM yyyy" date labels,
     * preserving the input order within each bucket. Returns buckets in
     * input order — caller sorts the input newest-first first.
     */
    private fun groupByDate(
        files: List<CatalogueItem.MediaFile>
    ): List<Pair<String, List<CatalogueItem.MediaFile>>> {
        if (files.isEmpty()) return emptyList()
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStartMs = cal.timeInMillis
        val yesterdayStartMs = todayStartMs - 24L * 3600 * 1000
        val olderFmt = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())

        val buckets = linkedMapOf<String, MutableList<CatalogueItem.MediaFile>>()
        for (f in files) {
            val ms = fileTimestamp(f) * 1000L
            val label = when {
                ms >= todayStartMs -> "Today"
                ms >= yesterdayStartMs -> "Yesterday"
                else -> olderFmt.format(java.util.Date(ms))
            }
            buckets.getOrPut(label) { mutableListOf() }.add(f)
        }
        return buckets.entries.map { it.key to it.value }
    }

    /**
     * Scan the app's private Repository dir (where the WiFi-P2P sync writes
     * pulled files) and classify each by extension. Returns (images, videos,
     * audio, other), each sorted newest-first by filename date-stamp then
     * lastModified. Uses file:// URIs; the adapter opens them via
     * ContentResolver.openInputStream which supports file schemes without
     * needing a FileProvider.
     */
    private fun scanSyncDir(root: java.io.File): Quad {
        val images = mutableListOf<CatalogueItem.MediaFile>()
        val videos = mutableListOf<CatalogueItem.MediaFile>()
        val audio = mutableListOf<CatalogueItem.MediaFile>()
        val other = mutableListOf<CatalogueItem.MediaFile>()
        if (!root.isDirectory) return Quad(images, videos, audio, other)
        val children = root.listFiles() ?: return Quad(images, videos, audio, other)
        for (f in children) {
            if (!f.isFile) continue
            if (f.name.startsWith(".")) continue   // hide .local_manifest.json etc.
            if (f.length() == 0L) continue         // skip empty stubs (broken pulls / aborted recordings)
            val ext = f.extension.lowercase()
            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                else -> "application/octet-stream"
            }
            val item = CatalogueItem.MediaFile(
                Uri.fromFile(f), f.name, mime, f.length(), f.lastModified() / 1000
            )
            when {
                mime.startsWith("image/") -> images.add(item)
                mime.startsWith("video/") -> videos.add(item)
                mime.startsWith("audio/") -> audio.add(item)
                else -> other.add(item)
            }
        }
        val cmp = Comparator<CatalogueItem.MediaFile> { a, b ->
            val da = parseFilenameDate(a.name) ?: a.dateModified
            val db = parseFilenameDate(b.name) ?: b.dateModified
            db.compareTo(da)
        }
        images.sortWith(cmp); videos.sortWith(cmp); audio.sortWith(cmp); other.sortWith(cmp)
        return Quad(images, videos, audio, other)
    }

    private data class Quad(
        val images: List<CatalogueItem.MediaFile>,
        val videos: List<CatalogueItem.MediaFile>,
        val audio: List<CatalogueItem.MediaFile>,
        val other: List<CatalogueItem.MediaFile>,
    )

    private fun parseFilenameDate(name: String): Long? {
        // Match YYYYMMDD-HHMMSS in filename
        val match = Regex("(\\d{8})-(\\d{6})").find(name) ?: return null
        return try {
            val datePart = match.groupValues[1] // YYYYMMDD
            val timePart = match.groupValues[2] // HHMMSS
            val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            val date = sdf.parse("$datePart$timePart") ?: return null
            date.time / 1000 // seconds to match DATE_MODIFIED scale
        } catch (_: Exception) {
            null
        }
    }
}
