package com.repository.listener.ui

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.repository.listener.R
import com.repository.listener.audio.AudioFileManager
import com.repository.listener.audio.AudioRecording
import com.repository.listener.audio.GlassesAudioArchiver
import com.repository.listener.service.ListenerService
import com.repository.listener.ui.util.applyPressPulse
import com.repository.listener.util.LogCollector
import org.json.JSONObject

class AudioRecordingListFragment : Fragment() {

    companion object {
        private const val TAG = "AudioRecordingList"
        private const val REFRESH_INTERVAL_MS = 5000L
    }

    private lateinit var audioFileManager: AudioFileManager
    private lateinit var adapter: AudioRecordingAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusDot: ImageView
    private lateinit var txtStatus: TextView
    private lateinit var txtDuration: TextView
    private lateinit var txtStorageInfo: TextView
    private lateinit var btnRecordToggle: ImageButton
    private lateinit var btnAlwaysRecord: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private var sortNewestFirst = true

    // Track previous state to detect actual transitions (avoids spam from periodic 10s status pushes).
    private var prevGlassesRecordingActive: Boolean? = null
    private var prevGlassesConnected: Boolean? = null
    private var prevBatteryLevel: Int = -1

    private fun toast(msg: String, long: Boolean = false) {
        val ctx = context ?: return
        android.widget.Toast.makeText(
            ctx,
            msg,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadRecordings()
            updateStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleRecordingStateBroadcast()
            updateStatus()
        }
    }

    private fun handleRecordingStateBroadcast() {
        val nowActive = ListenerService.glassesRecordingActive
        val nowConnected = ListenerService.glassesConnected
        val nowBattery = ListenerService.glassesBatteryLevel
        val nowWorn = ListenerService.glassesWornMirror

        // Connection drop -> "Glasses disconnected"
        if (prevGlassesConnected == true && !nowConnected) {
            toast("Glasses disconnected")
        }

        // Battery low edge: cross from >=5 to <5 with valid level
        if (prevBatteryLevel >= 5 && nowBattery in 0..4) {
            toast("Glasses battery low - recording stopped", long = true)
        }

        // Recording-state transitions
        val prev = prevGlassesRecordingActive
        if (prev != null && prev != nowActive) {
            if (nowActive) {
                toast("Recording started")
            } else {
                // alwaysRecord is wear-gated: a wear-removal transition while alwaysRecord
                // is enabled (and on-demand is not) is the "glasses removed" reason.
                val wornDropWhileAlwaysRecord = !nowWorn &&
                    ListenerService.alwaysRecordEnabled &&
                    !ListenerService.onDemandActive
                val reason = when {
                    nowBattery in 0..4 -> "battery low"
                    !nowConnected -> "glasses disconnected"
                    wornDropWhileAlwaysRecord -> "glasses removed"
                    else -> "stopped"
                }
                toast("Recording stopped: $reason")
            }
        }

        prevGlassesRecordingActive = nowActive
        prevGlassesConnected = nowConnected
        prevBatteryLevel = nowBattery
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_audio_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        audioFileManager = AudioFileManager(requireContext())

        // Top bar
        view.findViewById<ImageButton>(R.id.btnListBack).setOnClickListener {
            activity?.finish()
        }

        // Status card
        statusCard = view.findViewById(R.id.statusCard)
        statusDot = view.findViewById(R.id.statusDot)
        txtStatus = view.findViewById(R.id.txtStatus)
        txtDuration = view.findViewById(R.id.txtDuration)
        txtStorageInfo = view.findViewById(R.id.txtStorageInfo)

        statusCard.setOnClickListener {
            if (ListenerService.glassesConnected) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, AudioRecordingViewerFragment.newInstance(live = true))
                    .addToBackStack(null)
                    .commit()
            }
        }

        // Sort toggle
        view.findViewById<ImageButton>(R.id.btnSort).setOnClickListener {
            sortNewestFirst = !sortNewestFirst
            loadRecordings()
        }

        // Delete unpinned button
        view.findViewById<ImageButton>(R.id.btnDeleteUnpinned).setOnClickListener {
            confirmDeleteUnpinned()
        }

        // Recording control buttons
        btnRecordToggle = view.findViewById(R.id.btnRecordToggle)
        btnAlwaysRecord = view.findViewById(R.id.btnAlwaysRecord)

        btnRecordToggle.setOnClickListener {
            val svc = ListenerService.phoneBtHostInstance ?: run {
                LogCollector.w(TAG, "btnRecordToggle: phoneBtHostInstance null")
                return@setOnClickListener
            }
            val newOnDemand = !ListenerService.onDemandActive
            val json = JSONObject()
            json.put("on_demand_recording_active", newOnDemand)
            ListenerService.onDemandActive = newOnDemand
            svc.sendSettings(json.toString())
            updateStatus()
            if (newOnDemand) {
                // On-demand bypasses wear -- recording starts immediately regardless of head state.
                toast("Recording started")
            } else {
                toast("Recording stopped, syncing...")
                ListenerService.requestImmediateSync()
            }
            LogCollector.i(TAG, "On-demand recording toggled -> $newOnDemand")
        }

        btnAlwaysRecord.setOnClickListener {
            val svc = ListenerService.phoneBtHostInstance ?: run {
                LogCollector.w(TAG, "btnAlwaysRecord: phoneBtHostInstance null")
                return@setOnClickListener
            }
            val newState = !ListenerService.alwaysRecordEnabled
            val json = JSONObject().put("always_record_enabled", newState).toString()
            svc.sendSettings(json)
            ListenerService.alwaysRecordEnabled = newState
            updateStatus()
            toast(if (newState) "Always Record enabled" else "Always Record disabled")
            LogCollector.i(TAG, "Always-record toggled -> $newState")
        }

        // Press-pulse animation on all top-bar / control buttons
        listOf(
            view.findViewById<ImageButton>(R.id.btnListBack),
            view.findViewById<ImageButton>(R.id.btnAlwaysRecord),
            view.findViewById<ImageButton>(R.id.btnDeleteUnpinned),
            view.findViewById<ImageButton>(R.id.btnSort),
            btnRecordToggle
        ).forEach { it.applyPressPulse() }

        // RecyclerView
        recyclerView = view.findViewById(R.id.recyclerRecordings)
        adapter = AudioRecordingAdapter(
            onItemClick = { recording -> openViewer(recording) },
            onItemLongClick = { recording -> confirmDelete(recording) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Schedule retention job
        audioFileManager.scheduleRetentionJob()
    }

    override fun onResume() {
        super.onResume()
        // Seed previous-state baseline so the very first broadcast doesn't trigger a phantom toast.
        prevGlassesRecordingActive = ListenerService.glassesRecordingActive
        prevGlassesConnected = ListenerService.glassesConnected
        prevBatteryLevel = ListenerService.glassesBatteryLevel
        handler.post(refreshRunnable)
        val filter = IntentFilter(ListenerService.ACTION_RECORDING_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(recordingStateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
        pulseAnimator?.cancel()
        pulseAnimator = null
        try { requireContext().unregisterReceiver(recordingStateReceiver) } catch (_: Exception) {}
    }

    private fun loadRecordings() {
        val allRecordings = audioFileManager.listRecordings()
        val recordings = if (sortNewestFirst) allRecordings else allRecordings.reversed()
        adapter.setData(recordings, audioFileManager)

        // Storage info
        val totalBytes = audioFileManager.getTotalStorageUsed()
        val count = recordings.size
        txtStorageInfo.text = "$count recordings, ${formatSize(totalBytes)} total"
        txtStorageInfo.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun updateStatus() {
        val archiver = ListenerService.audioArchiver
        val audioFlowing = archiver?.isAudioFlowing() == true
        val archiverActive = archiver?.isRecording() == true
        val glassesRecording = ListenerService.glassesRecordingActive

        val orange = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.gbx_orange)
        val gray = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.gbx_gray)
        val red = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.gbx_red)
        val white = android.graphics.Color.WHITE

        // Status indicator dot: red when actually recording, gray when idle
        statusDot.visibility = View.VISIBLE
        statusDot.imageTintList = android.content.res.ColorStateList.valueOf(
            if (glassesRecording) red else gray
        )

        val glassesConnected = ListenerService.glassesConnected
        val battery = ListenerService.glassesBatteryLevel
        val onDemand = ListenerService.onDemandActive
        val alwaysOn = ListenerService.alwaysRecordEnabled
        val worn = ListenerService.glassesWornMirror

        when {
            glassesRecording -> {
                txtStatus.text = if (onDemand) "Recording (on-demand)" else "Recording"
                if (pulseAnimator == null) {
                    pulseAnimator = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.4f).apply {
                        duration = 750
                        repeatMode = ObjectAnimator.REVERSE
                        repeatCount = ObjectAnimator.INFINITE
                        start()
                    }
                }
            }
            !glassesConnected -> {
                txtStatus.text = "Glasses disconnected"
                pulseAnimator?.cancel(); pulseAnimator = null
                statusDot.alpha = 1f
            }
            battery in 0..4 -> {
                txtStatus.text = "Glasses battery low"
                pulseAnimator?.cancel(); pulseAnimator = null
                statusDot.alpha = 1f
            }
            alwaysOn && !worn -> {
                txtStatus.text = "Always Record armed (put glasses on)"
                pulseAnimator?.cancel(); pulseAnimator = null
                statusDot.alpha = 1f
            }
            alwaysOn -> {
                txtStatus.text = "Always Record armed"
                pulseAnimator?.cancel(); pulseAnimator = null
                statusDot.alpha = 1f
            }
            else -> {
                txtStatus.text = "Not recording"
                pulseAnimator?.cancel(); pulseAnimator = null
                statusDot.alpha = 1f
            }
        }

        txtDuration.visibility = if (audioFlowing) View.VISIBLE else View.GONE

        // Recording control buttons
        val batteryOk = battery < 0 || battery >= 5
        val connected = glassesConnected

        // btnRecordToggle - orange filled circle when ON, gray icon when OFF
        btnRecordToggle.isEnabled = batteryOk && connected
        btnRecordToggle.setImageResource(R.drawable.ic_fiber_manual_record)
        if (ListenerService.onDemandActive) {
            btnRecordToggle.setBackgroundResource(R.drawable.bg_toggle_active)
            btnRecordToggle.imageTintList = android.content.res.ColorStateList.valueOf(white)
        } else {
            btnRecordToggle.setBackgroundResource(android.R.color.transparent)
            btnRecordToggle.imageTintList = android.content.res.ColorStateList.valueOf(gray)
        }
        btnRecordToggle.alpha = if (btnRecordToggle.isEnabled) 1f else 0.3f

        // btnAlwaysRecord - orange filled circle when ON, gray icon when OFF
        btnAlwaysRecord.isEnabled = connected
        btnAlwaysRecord.setImageResource(R.drawable.ic_clock)
        if (ListenerService.alwaysRecordEnabled) {
            btnAlwaysRecord.setBackgroundResource(R.drawable.bg_toggle_active)
            btnAlwaysRecord.imageTintList = android.content.res.ColorStateList.valueOf(white)
        } else {
            btnAlwaysRecord.setBackgroundResource(android.R.color.transparent)
            btnAlwaysRecord.imageTintList = android.content.res.ColorStateList.valueOf(gray)
        }
        btnAlwaysRecord.alpha = if (btnAlwaysRecord.isEnabled) 1f else 0.3f
    }

    private fun openViewer(recording: AudioRecording) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AudioRecordingViewerFragment.newInstance(recording.audioFile.absolutePath))
            .addToBackStack(null)
            .commit()
    }

    private fun togglePin(recording: AudioRecording, position: Int) {
        audioFileManager.togglePin(recording)
        loadRecordings()
    }

    private fun confirmDeleteUnpinned() {
        val unpinnedCount = audioFileManager.listRecordings().count { !it.metadata.pinned }
        if (unpinnedCount == 0) {
            android.widget.Toast.makeText(requireContext(), "No unpinned recordings to delete", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Unpinned")
            .setMessage("Delete $unpinnedCount unpinned recordings?")
            .setPositiveButton("Delete") { _, _ ->
                val count = audioFileManager.deleteAllUnpinned()
                loadRecordings()
                android.widget.Toast.makeText(requireContext(), "Deleted $count recordings", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(recording: AudioRecording) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Recording")
            .setMessage("Delete ${recording.audioFile.name}?")
            .setPositiveButton("Delete") { _, _ ->
                audioFileManager.deleteRecording(recording)
                loadRecordings()
                LogCollector.i(TAG, "Deleted recording: ${recording.audioFile.name}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
