package com.repository.listener.ui

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.network.ChatHistoryClient
import com.repository.listener.network.CopilotChatDetail
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only Copilot session detail. Renders the persisted turns as a polished
 * transcript (interlocutor/wearer bubbles + reply/note callout cards) and offers
 * a beautiful PDF export via the share action. No compose/input bar.
 */
class CopilotChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CopilotChatActivity"
        const val EXTRA_CONVERSATION_ID = "copilot_conversation_id"
        const val EXTRA_TITLE = "copilot_title"
    }

    private lateinit var adapter: CopilotChatAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var rootView: View

    private var client: ChatHistoryClient? = null
    private var conversationId: String = ""
    private var titleText: String = ""
    private var detail: CopilotChatDetail? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""
        titleText = intent.getStringExtra(EXTRA_TITLE) ?: "Copilot"

        rootView = buildContentView()
        setContentView(rootView)

        adapter = CopilotChatAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        client = ChatHistoryClient(
            AppConfig.getOrchestratorUrl(this),
            AppConfig.getApiKey(this)
        )

        load()
    }

    private fun load() {
        if (conversationId.isEmpty()) {
            showEmpty("No conversation")
            return
        }
        showLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    client?.getCopilotChatDetail(conversationId)
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to load copilot detail: ${e.message}")
                    null
                }
            }
            showLoading(false)
            if (result == null) {
                showEmpty("Could not load conversation")
                return@launch
            }
            detail = result
            if (result.turns.isEmpty()) {
                showEmpty("No turns in this session")
                return@launch
            }
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submit(result.turns)
        }
    }

    private fun onShareClicked() {
        val d = detail
        if (d == null) {
            Snackbar.make(rootView, "Nothing to share yet", Snackbar.LENGTH_SHORT).show()
            return
        }
        val progress = Snackbar.make(rootView, "Building PDF...", Snackbar.LENGTH_INDEFINITE)
        progress.show()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    CopilotPdfExporter.export(this@CopilotChatActivity, d)
                } catch (e: Exception) {
                    LogCollector.e(TAG, "PDF export failed: ${e.message}")
                    null
                }
            }
            progress.dismiss()
            if (file == null) {
                Snackbar.make(rootView, "Failed to build PDF", Snackbar.LENGTH_LONG).show()
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(
                    this@CopilotChatActivity,
                    "${applicationContext.packageName}.fileprovider",
                    file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share Copilot session"))
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to share PDF: ${e.message}")
                Snackbar.make(rootView, "Failed to share PDF", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // --- UI construction ---

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.gbx_bg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(buildTopBar())

        val body = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        recyclerView = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(16))
            visibility = View.GONE
        }
        body.addView(recyclerView)

        loadingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            addView(ProgressBar(this@CopilotChatActivity).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(color(R.color.gbx_orange))
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            addView(TextView(this@CopilotChatActivity).apply {
                text = "Loading..."
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(color(R.color.gbx_gray))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            })
        }
        body.addView(loadingContainer)

        emptyView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(R.color.gbx_gray))
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        body.addView(emptyView)

        root.addView(body)
        return root
    }

    private fun buildTopBar(): View {
        val bar = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
            setBackgroundColor(color(R.color.gbx_bg0_hard))
        }

        // 1dp bottom border.
        bar.addView(View(this).apply {
            setBackgroundColor(color(R.color.gbx_bg2))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { gravity = Gravity.BOTTOM }
        })

        // Back arrow.
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(color(R.color.gbx_orange))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = borderlessRipple()
            layoutParams = FrameLayout.LayoutParams(
                dp(48), FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            setOnClickListener { finish() }
        })

        // Title + Copilot chip stacked.
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                0, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        titleBlock.addView(TextView(this).apply {
            text = titleText
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(color(R.color.gbx_fg))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titleBlock.addView(TextView(this).apply {
            text = "Copilot"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(color(R.color.gbx_bg0_hard))
            setPadding(dp(8), dp(1), dp(8), dp(1))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(color(R.color.gbx_aqua))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })

        // Use a horizontal container so title block sits between back and share.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), 0, dp(4), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        row.addView(titleBlock)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_share)
            setColorFilter(color(R.color.gbx_orange))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = borderlessRipple()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = "Share as PDF"
            setOnClickListener { onShareClicked() }
        })
        bar.addView(row)

        return bar
    }

    private fun borderlessRipple(): android.graphics.drawable.Drawable? {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val d = a.getDrawable(0)
        a.recycle()
        return d
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.GONE
        }
    }

    private fun showEmpty(message: String) {
        emptyView.text = message
        emptyView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        loadingContainer.visibility = View.GONE
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
}
