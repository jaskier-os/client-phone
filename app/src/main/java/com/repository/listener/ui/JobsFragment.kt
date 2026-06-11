package com.repository.listener.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.repository.listener.R
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class JobsFragment : Fragment() {

    companion object {
        private const val TAG = "JobsFragment"
    }

    private lateinit var adapter: JobListAdapter
    private val items = mutableListOf<JobItem>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var progressBar: ProgressBar

    private val jobResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ListenerService.ACTION_JOB_RESULT) {
                val json = intent.getStringExtra(ListenerService.EXTRA_JOB_DATA) ?: return
                parseJobResult(json)
            }
        }
    }

    private val jobNotificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ListenerService.ACTION_JOB_NOTIFICATION) {
                sendJobListRequest()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_jobs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.jobsRecyclerView)
        emptyText = view.findViewById(R.id.jobsEmptyText)
        progressBar = view.findViewById(R.id.jobsProgress)

        adapter = JobListAdapter(
            onDelete = { item -> sendJobDelete(item) },
            onClick = { item -> onJobClicked(item) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.addJobFab).setOnClickListener {
            showCreateJobDialog()
        }

        val ctx = requireContext()
        ctx.registerReceiver(
            jobResultReceiver,
            IntentFilter(ListenerService.ACTION_JOB_RESULT),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            jobNotificationReceiver,
            IntentFilter(ListenerService.ACTION_JOB_NOTIFICATION),
            Context.RECEIVER_NOT_EXPORTED
        )

        progressBar.visibility = View.VISIBLE
        sendJobListRequest()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireContext().unregisterReceiver(jobResultReceiver)
        requireContext().unregisterReceiver(jobNotificationReceiver)
    }

    private fun sendJobListRequest() {
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_JOB_LIST_REQ).apply {
            setPackage(requireContext().packageName)
        })
    }

    private fun sendJobDelete(item: JobItem) {
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_JOB_DELETE).apply {
            setPackage(requireContext().packageName)
            putExtra("job_id", item.id)
        })
    }

    private fun sendJobCreate(name: String, prompt: String, scheduledAt: Long) {
        val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date(scheduledAt))
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_JOB_CREATE).apply {
            setPackage(requireContext().packageName)
            putExtra("job_name", name)
            putExtra("job_prompt", prompt)
            putExtra("job_scheduled_at", isoDate)
        })
    }

    private fun sendJobUpdate(id: String, name: String, prompt: String, scheduledAt: Long) {
        val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date(scheduledAt))
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_JOB_UPDATE).apply {
            setPackage(requireContext().packageName)
            putExtra("job_id", id)
            putExtra("job_name", name)
            putExtra("job_prompt", prompt)
            putExtra("job_scheduled_at", isoDate)
        })
    }

    private fun onJobClicked(item: JobItem) {
        when (item.status) {
            "completed", "needs_input", "failed" -> {
                if (!item.conversationId.isNullOrBlank()) {
                    startActivity(Intent(requireContext(), ChatDetailActivity::class.java).apply {
                        putExtra(ChatDetailActivity.EXTRA_CONVERSATION_ID, item.conversationId)
                        putExtra(ChatDetailActivity.EXTRA_CONVERSATION_TITLE, "Job: ${item.name}")
                        putExtra(ChatDetailActivity.EXTRA_CONVERSATION_CLOSED, false)
                    })
                }
            }
            "pending" -> showEditJobDialog(item)
        }
    }

    private fun showCreateJobDialog() {
        val ctx = requireContext()
        val bg1 = ContextCompat.getColor(ctx, R.color.gbx_bg1)
        val fg = ContextCompat.getColor(ctx, R.color.gbx_fg)
        val gray = ContextCompat.getColor(ctx, R.color.gbx_gray)
        val orange = ContextCompat.getColor(ctx, R.color.gbx_orange)
        val green = ContextCompat.getColor(ctx, R.color.gbx_green)

        val selectedCalendar = Calendar.getInstance()
        var scheduledAt = 0L

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(8))
        }

        val title = TextView(ctx).apply {
            text = "New Job"
            setTextColor(orange)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        val nameInputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg1)
            cornerRadius = dp(24).toFloat()
        }
        val nameInput = EditText(ctx).apply {
            hint = "Job name"
            setHintTextColor(gray)
            setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            background = nameInputBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = true
        }
        container.addView(nameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val promptInputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg1)
            cornerRadius = dp(24).toFloat()
        }
        val promptInput = EditText(ctx).apply {
            hint = "What should AI do?"
            setHintTextColor(gray)
            setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            background = promptInputBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = false
            minLines = 3
            maxLines = 6
        }
        container.addView(promptInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val dateButton = TextView(ctx).apply {
            text = "Pick date & time"
            setTextColor(orange)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            val dateBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(bg1)
                cornerRadius = dp(24).toFloat()
            }
            background = dateBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        dateButton.setOnClickListener {
            val now = Calendar.getInstance()
            DatePickerDialog(ctx, { _, year, month, day ->
                selectedCalendar.set(Calendar.YEAR, year)
                selectedCalendar.set(Calendar.MONTH, month)
                selectedCalendar.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(ctx, { _, hour, minute ->
                    selectedCalendar.set(Calendar.HOUR_OF_DAY, hour)
                    selectedCalendar.set(Calendar.MINUTE, minute)
                    selectedCalendar.set(Calendar.SECOND, 0)
                    scheduledAt = selectedCalendar.timeInMillis
                    dateButton.text = dateFormat.format(Date(scheduledAt))
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }
        container.addView(dateButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            setTextColor(gray)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg1)
        }
        val createBtn = TextView(ctx).apply {
            text = "Create"
            setTextColor(green)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg1)
        }
        buttonRow.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })
        buttonRow.addView(createBtn)
        container.addView(buttonRow)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(container)
            .create()

        cancelBtn.setOnClickListener { dialog.dismiss() }
        createBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val prompt = promptInput.text.toString().trim()
            if (name.isNotEmpty() && prompt.isNotEmpty() && scheduledAt > 0) {
                sendJobCreate(name, prompt, scheduledAt)
                dialog.dismiss()
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            nameInput.requestFocus()
            nameInput.postDelayed({
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(nameInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }

        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun showEditJobDialog(item: JobItem) {
        val ctx = requireContext()
        val bg1 = ContextCompat.getColor(ctx, R.color.gbx_bg1)
        val fg = ContextCompat.getColor(ctx, R.color.gbx_fg)
        val gray = ContextCompat.getColor(ctx, R.color.gbx_gray)
        val orange = ContextCompat.getColor(ctx, R.color.gbx_orange)
        val green = ContextCompat.getColor(ctx, R.color.gbx_green)

        val selectedCalendar = Calendar.getInstance().apply {
            timeInMillis = item.scheduledAt
        }
        var scheduledAt = item.scheduledAt

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(8))
        }

        val title = TextView(ctx).apply {
            text = "Edit Job"
            setTextColor(orange)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        val nameInputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg1)
            cornerRadius = dp(24).toFloat()
        }
        val nameInput = EditText(ctx).apply {
            hint = "Job name"
            setText(item.name)
            setHintTextColor(gray)
            setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            background = nameInputBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = true
        }
        container.addView(nameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val promptInputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg1)
            cornerRadius = dp(24).toFloat()
        }
        val promptInput = EditText(ctx).apply {
            hint = "What should AI do?"
            setText(item.prompt)
            setHintTextColor(gray)
            setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            background = promptInputBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = false
            minLines = 3
            maxLines = 6
        }
        container.addView(promptInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val dateButton = TextView(ctx).apply {
            text = dateFormat.format(Date(scheduledAt))
            setTextColor(orange)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            val dateBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(bg1)
                cornerRadius = dp(24).toFloat()
            }
            background = dateBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        dateButton.setOnClickListener {
            DatePickerDialog(ctx, { _, year, month, day ->
                selectedCalendar.set(Calendar.YEAR, year)
                selectedCalendar.set(Calendar.MONTH, month)
                selectedCalendar.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(ctx, { _, hour, minute ->
                    selectedCalendar.set(Calendar.HOUR_OF_DAY, hour)
                    selectedCalendar.set(Calendar.MINUTE, minute)
                    selectedCalendar.set(Calendar.SECOND, 0)
                    scheduledAt = selectedCalendar.timeInMillis
                    dateButton.text = dateFormat.format(Date(scheduledAt))
                }, selectedCalendar.get(Calendar.HOUR_OF_DAY), selectedCalendar.get(Calendar.MINUTE), true).show()
            }, selectedCalendar.get(Calendar.YEAR), selectedCalendar.get(Calendar.MONTH), selectedCalendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        container.addView(dateButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            setTextColor(gray)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg1)
        }
        val saveBtn = TextView(ctx).apply {
            text = "Save"
            setTextColor(green)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg1)
        }
        buttonRow.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })
        buttonRow.addView(saveBtn)
        container.addView(buttonRow)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(container)
            .create()

        cancelBtn.setOnClickListener { dialog.dismiss() }
        saveBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val prompt = promptInput.text.toString().trim()
            if (name.isNotEmpty() && prompt.isNotEmpty() && scheduledAt > 0) {
                sendJobUpdate(item.id, name, prompt, scheduledAt)
                dialog.dismiss()
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        }

        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun parseJobResult(json: String) {
        progressBar.visibility = View.GONE
        try {
            val arr = JSONArray(json)
            items.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(JobItem(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    prompt = obj.optString("prompt", ""),
                    scheduledAt = parseDate(obj.opt("scheduledAt")),
                    status = obj.optString("status", "pending"),
                    result = if (obj.isNull("result")) null else obj.optString("result"),
                    error = if (obj.isNull("error")) null else obj.optString("error"),
                    conversationId = if (obj.isNull("conversationId")) null else obj.optString("conversationId"),
                    createdAt = parseDate(obj.opt("createdAt"))
                ))
            }
            adapter.submitList(items.toList())
            updateEmptyState()
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse job result: ${e.message}")
        }
    }

    private fun updateEmptyState() {
        if (items.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    /** Parse a date value that may be a Long (epoch millis), a String (ISO date), or a MongoDB $date object. */
    private fun parseDate(value: Any?): Long {
        if (value == null) return 0
        if (value is Long) return value
        if (value is Number) return value.toLong()
        if (value is String) {
            return try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value)?.time ?: 0
            } catch (_: Exception) {
                try { value.toLong() } catch (_: Exception) { 0 }
            }
        }
        // MongoDB $date object serialized as JSONObject with $date key
        if (value is JSONObject) {
            val dateStr = value.optString("\$date")
            if (dateStr.isNotEmpty()) return parseDate(dateStr)
        }
        return 0
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
