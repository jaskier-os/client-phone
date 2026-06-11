package com.repository.listener.ui

import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.repository.listener.R
import com.repository.listener.alarm.AlarmItem
import com.repository.listener.alarm.AlarmStore
import com.repository.listener.service.ListenerService
import java.util.Calendar

class AlarmFragment : Fragment() {

    private lateinit var adapter: AlarmAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView

    private val alarmChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ListenerService.ACTION_ALARM_CHANGED) {
                refreshList()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_alarm, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.alarmRecyclerView)
        emptyText = view.findViewById(R.id.alarmEmptyText)

        adapter = AlarmAdapter(
            onToggle = { item, enabled -> toggleAlarm(item, enabled) },
            onDelete = { item -> deleteAlarm(item) },
            onClick = { item -> showEditAlarmDialog(item) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.addAlarmFab).setOnClickListener {
            showCreateAlarmDialog()
        }

        requireContext().registerReceiver(
            alarmChangeReceiver,
            IntentFilter(ListenerService.ACTION_ALARM_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireContext().unregisterReceiver(alarmChangeReceiver)
    }

    private fun refreshList() {
        val alarms = AlarmStore.getAll(requireContext())
        adapter.submitList(alarms)
        if (alarms.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun toggleAlarm(item: AlarmItem, enabled: Boolean) {
        val trigger = if (enabled) AlarmStore.computeNextTrigger(item.hour, item.minute) else item.triggerTimeMillis
        val updated = item.copy(enabled = enabled, triggerTimeMillis = trigger)
        AlarmStore.save(requireContext(), updated)
        refreshList()
    }

    private fun deleteAlarm(item: AlarmItem) {
        AlarmStore.delete(requireContext(), item.id)
        refreshList()
    }

    private fun showCreateAlarmDialog() {
        val cal = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, hour, minute ->
            showTitleDialog(null, hour, minute)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    private fun showEditAlarmDialog(item: AlarmItem) {
        TimePickerDialog(requireContext(), { _, hour, minute ->
            showTitleDialog(item, hour, minute)
        }, item.hour, item.minute, true).show()
    }

    private fun showTitleDialog(existingAlarm: AlarmItem?, hour: Int, minute: Int) {
        val ctx = requireContext()
        val dp = { value: Int -> (value * resources.displayMetrics.density + 0.5f).toInt() }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val label = TextView(ctx).apply {
            text = if (existingAlarm != null) "Edit Alarm" else "New Alarm"
            textSize = 16f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_orange))
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(label)

        val timeLabel = TextView(ctx).apply {
            text = String.format("%02d:%02d", hour, minute)
            textSize = 24f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(timeLabel)

        val titleInput = EditText(ctx).apply {
            hint = "Label (optional)"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.gbx_gray))
            if (existingAlarm != null) setText(existingAlarm.title)
        }
        container.addView(titleInput)

        MaterialAlertDialogBuilder(ctx)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim()
                val trigger = AlarmStore.computeNextTrigger(hour, minute)
                val alarm = if (existingAlarm != null) {
                    existingAlarm.copy(hour = hour, minute = minute, title = title, triggerTimeMillis = trigger, enabled = true)
                } else {
                    AlarmItem(
                        id = 0,
                        hour = hour,
                        minute = minute,
                        title = title,
                        enabled = true,
                        triggerTimeMillis = trigger,
                        createdAt = System.currentTimeMillis()
                    )
                }
                AlarmStore.save(requireContext(), alarm)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
