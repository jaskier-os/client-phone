package com.repository.listener.ui

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.audio.AudioFileManager
import com.repository.listener.audio.AudioRecording
import java.text.SimpleDateFormat
import java.util.*

class AudioRecordingAdapter(
    private val onItemClick: (AudioRecording) -> Unit,
    private val onItemLongClick: (AudioRecording) -> Unit
) : RecyclerView.Adapter<AudioRecordingAdapter.ViewHolder>() {

    private var recordings: List<AudioRecording> = emptyList()
    private var audioFileManager: AudioFileManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtDate: TextView = view.findViewById(R.id.txtDate)
        val txtSize: TextView = view.findViewById(R.id.txtSize)
        val txtTimeRange: TextView = view.findViewById(R.id.txtTimeRange)
        val txtItemDuration: TextView = view.findViewById(R.id.txtItemDuration)
        val txtVoiceCount: TextView = view.findViewById(R.id.txtVoiceCount)
        val amplitudePreview: View = view.findViewById(R.id.amplitudePreview)
        var currentBitmap: Bitmap? = null
        var generation: Int = 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.amplitudePreview.background = null
        holder.currentBitmap?.recycle()
        holder.currentBitmap = null
        holder.generation++
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.generation++
        val recording = recordings[position]
        val meta = recording.metadata

        // Date or name
        if (meta.name.isNotEmpty()) {
            holder.txtDate.text = meta.name
        } else {
            try {
                val inputFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                val displayFmt = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
                val date = inputFmt.parse(meta.startTime)
                holder.txtDate.text = if (date != null) displayFmt.format(date) else meta.startTime
            } catch (e: Exception) {
                holder.txtDate.text = meta.startTime
            }
        }

        // Size
        val sizeBytes = recording.audioFile.length()
        holder.txtSize.text = formatSize(sizeBytes)

        // Time range
        try {
            val inputFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
            val start = inputFmt.parse(meta.startTime)
            val end = inputFmt.parse(meta.endTime)
            if (start != null && end != null) {
                holder.txtTimeRange.text = "${timeFmt.format(start)} - ${timeFmt.format(end)}"
            }
        } catch (e: Exception) {
            holder.txtTimeRange.text = ""
        }

        // Duration
        holder.txtItemDuration.text = formatDuration(meta.durationMs)

        // Voice count
        val voiceCount = meta.voiceSegments.size
        holder.txtVoiceCount.text = "$voiceCount voice parts"

        // Mini amplitude preview
        drawMiniAmplitude(holder, holder.amplitudePreview, recording)

        // Click handlers
        holder.itemView.setOnClickListener { onItemClick(recording) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(recording)
            true
        }
    }

    override fun getItemCount(): Int = recordings.size

    fun setData(newRecordings: List<AudioRecording>, manager: AudioFileManager) {
        recordings = newRecordings
        audioFileManager = manager
        notifyDataSetChanged()
    }

    private fun drawMiniAmplitude(holder: ViewHolder, view: View, recording: AudioRecording) {
        view.post {
            if (view.width == 0 || view.height == 0) return@post
            val manager = audioFileManager ?: return@post
            val w = view.width
            val h = view.height
            val expectedGeneration = holder.generation

            Thread {
                val amplitudeData = manager.loadAmplitudeData(recording.amplitudeFile)
                if (amplitudeData.isEmpty()) return@Thread

                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val maxAmp = amplitudeData.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
                val centerY = h / 2f
                val samplesPerPx = amplitudeData.size.toFloat() / w

                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#66FE8019") // orange 40%
                    style = Paint.Style.FILL
                }

                val path = Path()
                path.moveTo(0f, centerY)
                for (x in 0 until w) {
                    val sampleIdx = (x * samplesPerPx).toInt().coerceIn(0, amplitudeData.size - 1)
                    val amp = amplitudeData[sampleIdx].toFloat() / maxAmp
                    path.lineTo(x.toFloat(), centerY - amp * (h / 2f - 2))
                }
                for (x in w - 1 downTo 0) {
                    val sampleIdx = (x * samplesPerPx).toInt().coerceIn(0, amplitudeData.size - 1)
                    val amp = amplitudeData[sampleIdx].toFloat() / maxAmp
                    path.lineTo(x.toFloat(), centerY + amp * (h / 2f - 2))
                }
                path.close()
                canvas.drawPath(path, paint)

                // Draw voice segments as green bands
                val voicePaint = Paint().apply {
                    color = Color.parseColor("#4D98971A") // green 30%
                    style = Paint.Style.FILL
                }
                val meta = recording.metadata
                for (seg in meta.voiceSegments) {
                    val x1 = (seg.startMs.toFloat() / meta.durationMs * w).coerceIn(0f, w.toFloat())
                    val x2 = (seg.endMs.toFloat() / meta.durationMs * w).coerceIn(0f, w.toFloat())
                    canvas.drawRect(x1, 0f, x2, h.toFloat(), voicePaint)
                }

                mainHandler.post {
                    if (holder.generation != expectedGeneration) {
                        bitmap.recycle()
                        return@post
                    }
                    view.background = null
                    holder.currentBitmap?.recycle()
                    holder.currentBitmap = bitmap
                    view.background = android.graphics.drawable.BitmapDrawable(view.resources, bitmap)
                }
            }.start()
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
