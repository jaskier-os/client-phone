package com.repository.listener.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.repository.listener.R

class GattLogBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val MAX_HEIGHT_DP = 400
    }

    private fun color(resId: Int) = ContextCompat.getColor(requireContext(), resId)
    private val COLOR_BG get() = color(R.color.gbx_bg)
    private val COLOR_BG1 get() = color(R.color.gbx_bg1)
    private val COLOR_FG get() = color(R.color.gbx_fg)
    private val COLOR_GRAY get() = color(R.color.gbx_gray)

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView

    var initialContent: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Title row: "GATT Log" + close button
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * density).toInt(), 0, (16 * density).toInt(), (12 * density).toInt())
        }

        titleRow.addView(TextView(ctx).apply {
            text = "GATT Log"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(COLOR_FG)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        titleRow.addView(ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x00000000)
            setColorFilter(COLOR_GRAY)
            setOnClickListener { dismiss() }
            val size = (36 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        })

        root.addView(titleRow)

        // Separator
        root.addView(View(ctx).apply {
            setBackgroundColor(COLOR_BG1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            )
        })

        // ScrollView with log text
        scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (MAX_HEIGHT_DP * density).toInt()
            )
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        }

        logTextView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(COLOR_GRAY)
            text = initialContent
        }

        scrollView.addView(logTextView)
        root.addView(scrollView)

        // Scroll to bottom
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

        return root
    }

    fun appendLog(line: String) {
        if (!::logTextView.isInitialized) return
        logTextView.append("\n$line")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun clearLog() {
        if (!::logTextView.isInitialized) return
        logTextView.text = ""
    }
}
