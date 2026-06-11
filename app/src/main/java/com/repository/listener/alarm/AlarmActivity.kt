package com.repository.listener.alarm

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.repository.listener.R
import com.repository.listener.util.LogCollector

class AlarmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_TIME = "time"
        const val EXTRA_TITLE = "title"
        private const val TAG = "AlarmActivity"
    }

    private var alarmId = -1

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // gbx_bg0_hard for status/nav bars
        window.statusBarColor = 0xFF1D2021.toInt()
        window.navigationBarColor = 0xFF1D2021.toInt()

        alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val timeStr = intent.getStringExtra(EXTRA_TIME) ?: "Alarm"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        LogCollector.i(TAG, "Alarm overlay shown: id=$alarmId $timeStr $title")

        // gbx_bg canvas background
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF282828.toInt()) // gbx_bg
            setPadding(dp(24), dp(64), dp(24), dp(32))
        }

        // Orange "ALARM" label -- gbx_orange, 16sp bold sans-serif
        val labelView = TextView(this).apply {
            text = "ALARM"
            textSize = 16f
            setTextColor(0xFFFE8019.toInt()) // gbx_orange
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(labelView)

        // Time -- 72sp monospace, gbx_fg
        val timeView = TextView(this).apply {
            text = timeStr
            textSize = 72f
            setTextColor(0xFFEBDBB2.toInt()) // gbx_fg
            typeface = Typeface.MONOSPACE
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        layout.addView(timeView)

        // Title -- 24sp monospace, gbx_gray
        if (title.isNotEmpty() && title != timeStr) {
            val titleView = TextView(this).apply {
                text = title
                textSize = 24f
                setTextColor(0xFFA89984.toInt()) // gbx_gray
                typeface = Typeface.MONOSPACE
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }
            layout.addView(titleView)
        }

        // Spacer
        layout.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        // Buttons -- full width, 8dp gap, matching design system
        val buttonColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Dismiss -- gbx_red bg, 12dp radius, 16sp sans-serif
        val dismissBtn = Button(this).apply {
            text = "Dismiss"
            textSize = 16f
            setTextColor(0xFFEBDBB2.toInt()) // gbx_fg
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(0xFFCC241D.toInt()) // gbx_red
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onDismiss() }
        }
        buttonColumn.addView(dismissBtn)

        // Snooze -- gbx_bg1 bg, 12dp radius, 16sp sans-serif, 8dp top margin
        val snoozeBtn = Button(this).apply {
            text = "Snooze (5 min)"
            textSize = 16f
            setTextColor(0xFFEBDBB2.toInt()) // gbx_fg
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(0xFF3C3836.toInt()) // gbx_bg1
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(8)
            layoutParams = params
            setOnClickListener { onSnooze() }
        }
        buttonColumn.addView(snoozeBtn)

        layout.addView(buttonColumn)

        setContentView(layout)
    }

    private fun onDismiss() {
        LogCollector.i(TAG, "Alarm dismissed by user: id=$alarmId")
        sendBroadcast(Intent(AlarmReceiver.ACTION_DISMISS).apply {
            setClassName(packageName, "com.repository.listener.alarm.AlarmReceiver")
            putExtra("alarm_id", alarmId)
        })
        finish()
    }

    private fun onSnooze() {
        LogCollector.i(TAG, "Alarm snoozed by user: id=$alarmId")
        sendBroadcast(Intent(AlarmReceiver.ACTION_SNOOZE).apply {
            setClassName(packageName, "com.repository.listener.alarm.AlarmReceiver")
            putExtra("alarm_id", alarmId)
        })
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Don't allow back to dismiss without explicit action
    }
}
