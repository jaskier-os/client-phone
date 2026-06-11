package com.repository.listener.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.repository.listener.R

class RemoteSessionBottomSheet : BottomSheetDialogFragment() {

    private fun color(resId: Int) = ContextCompat.getColor(requireContext(), resId)
    private val COLOR_BG get() = color(R.color.gbx_bg)
    private val COLOR_BG1 get() = color(R.color.gbx_bg1)
    private val COLOR_FG get() = color(R.color.gbx_fg)
    private val COLOR_GRAY get() = color(R.color.gbx_gray)
    private val COLOR_GREEN get() = color(R.color.gbx_green)
    private val COLOR_AQUA get() = color(R.color.gbx_aqua)

    var dirs: List<String> = emptyList()
    var onDirSelected: ((String) -> Unit)? = null
    var isLoading: Boolean = false
    var errorMessage: String? = null

    private var container: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        this.container = root
        buildContent(root)
        return root
    }

    private fun buildContent(root: LinearLayout) {
        root.removeAllViews()

        // Title
        root.addView(TextView(requireContext()).apply {
            text = "Start Remote Session"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(COLOR_FG)
            setPadding(48, 16, 48, 24)
        })

        if (isLoading) {
            root.addView(ProgressBar(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_GRAY)
                setPadding(0, 24, 0, 24)
            })
            return
        }

        if (errorMessage != null) {
            root.addView(TextView(requireContext()).apply {
                text = errorMessage
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFfb4934.toInt()) // gruvbox red
                setPadding(48, 8, 48, 24)
            })
            return
        }

        // Directory items
        for (dir in dirs) {
            val shortName = dir.substringAfterLast('/')
            root.addView(createDirItem(shortName, dir))
        }

        // Custom directory option
        root.addView(createCustomDirItem())
    }

    private fun createDirItem(label: String, fullPath: String): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 20)
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(COLOR_BG1),
                ColorDrawable(COLOR_BG),
                null
            )
            setOnClickListener {
                onDirSelected?.invoke(fullPath)
                dismiss()
            }

            addView(TextView(context).apply {
                text = label
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(COLOR_AQUA)
            })
            addView(TextView(context).apply {
                text = fullPath
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(COLOR_GRAY)
            })
        }
    }

    private fun createCustomDirItem(): View {
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 20)
        }

        // Separator
        wrapper.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { bottomMargin = 16 }
            setBackgroundColor(COLOR_BG1)
        })

        val inputRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val input = EditText(requireContext()).apply {
            hint = "Custom path..."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(COLOR_FG)
            setHintTextColor(COLOR_GRAY)
            setBackgroundColor(COLOR_BG1)
            setPadding(24, 16, 24, 16)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val goBtn = TextView(requireContext()).apply {
            text = "GO"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(COLOR_GREEN)
            setPadding(24, 16, 24, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    onDirSelected?.invoke(path)
                    dismiss()
                }
            }
        }

        inputRow.addView(input)
        inputRow.addView(goBtn)
        wrapper.addView(inputRow)
        return wrapper
    }

    fun showError(msg: String) {
        errorMessage = msg
        isLoading = false
        container?.let { buildContent(it) }
    }

    fun showDirs(newDirs: List<String>) {
        dirs = newDirs
        isLoading = false
        errorMessage = null
        container?.let { buildContent(it) }
    }
}
