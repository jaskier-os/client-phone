package com.repository.listener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.repository.listener.R
import com.repository.listener.audio.VoiceSegmentData

class VoiceSegmentAdapter(
    private val onSegmentClick: (VoiceSegmentData) -> Unit,
    private val onTranscribeClick: (VoiceSegmentData) -> Unit
) : RecyclerView.Adapter<VoiceSegmentAdapter.ViewHolder>() {

    private var segments: List<VoiceSegmentData> = emptyList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtTime: TextView = view.findViewById(R.id.txtSegTime)
        val txtText: TextView = view.findViewById(R.id.txtSegText)
        val btnTranscribe: MaterialButton = view.findViewById(R.id.btnSegTranscribe)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_voice_segment, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val seg = segments[position]
        holder.txtTime.text = formatTime(seg.startMs)

        when {
            seg.transcription == "[transcribing...]" -> {
                holder.txtText.text = "Transcribing..."
                holder.txtText.alpha = 0.5f
                holder.btnTranscribe.visibility = View.GONE
            }
            seg.transcription.isNotEmpty() -> {
                holder.txtText.text = seg.transcription
                holder.txtText.alpha = 1f
                holder.btnTranscribe.visibility = View.GONE
            }
            else -> {
                holder.txtText.text = "(not transcribed)"
                holder.txtText.alpha = 0.5f
                holder.btnTranscribe.visibility = View.VISIBLE
                holder.btnTranscribe.isEnabled = true
                holder.btnTranscribe.text = "Transcribe"
                holder.btnTranscribe.setOnClickListener {
                    holder.btnTranscribe.isEnabled = false
                    holder.btnTranscribe.text = "..."
                    holder.txtText.text = "Transcribing..."
                    onTranscribeClick(seg)
                }
            }
        }

        holder.itemView.setOnClickListener { onSegmentClick(seg) }
    }

    override fun getItemCount(): Int = segments.size

    fun setData(newSegments: List<VoiceSegmentData>) {
        // Filter out segments still in progress (no endMs yet)
        segments = newSegments.filter { it.endMs > it.startMs }
        notifyDataSetChanged()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
