package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import org.json.JSONObject

class GlassesSettingsFragment : Fragment() {

    companion object {
        private const val TAG = "GlassesSettingsFragment"
        const val ACTION_ENTER_PAIR_MODE = "com.repository.listener.ENTER_PAIR_MODE"
    }

    private lateinit var connectionDot: View
    private lateinit var txtConnectionStatus: TextView
    private lateinit var txtDeviceName: TextView
    private lateinit var txtBattery: TextView
    private lateinit var txtRetryCountdown: TextView
    private lateinit var throughputSection: LinearLayout
    private lateinit var txtTxRate: TextView
    private lateinit var txtRxRate: TextView
    private lateinit var txtTxTotal: TextView
    private lateinit var txtRxTotal: TextView
    private lateinit var chkAdbDebug: CheckBox
    private lateinit var chkEnableSideloading: CheckBox
    private lateinit var chkWeatherWidget: CheckBox
    private lateinit var btnInfo: View
    private lateinit var btnSettings: View
    private lateinit var btnPairMode: com.google.android.material.button.MaterialButton
    private lateinit var displayPositionView: DisplayPositionView
    private lateinit var btnResetDisplayPosition: View

    private var isConnected = false

    private val glassesStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(ListenerService.EXTRA_GLASSES_CONNECTED, false)
            val connecting = intent.getBooleanExtra(ListenerService.EXTRA_GLASSES_CONNECTING, false)
            val deviceName = intent.getStringExtra(ListenerService.EXTRA_GLASSES_DEVICE_NAME)
            activity?.runOnUiThread {
                updateConnectionStatus(connected, connecting, deviceName)
            }
        }
    }

    private val throughputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val txKbps = intent.getFloatExtra(ListenerService.EXTRA_TX_KBPS, 0f)
            val rxKbps = intent.getFloatExtra(ListenerService.EXTRA_RX_KBPS, 0f)
            val txTotal = intent.getLongExtra(ListenerService.EXTRA_TX_TOTAL, 0)
            val rxTotal = intent.getLongExtra(ListenerService.EXTRA_RX_TOTAL, 0)
            activity?.runOnUiThread {
                updateThroughput(txKbps, rxKbps, txTotal, rxTotal)
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(ListenerService.EXTRA_BATTERY_LEVEL, -1)
            val charging = intent.getBooleanExtra(ListenerService.EXTRA_BATTERY_CHARGING, false)
            activity?.runOnUiThread { updateBattery(level, charging) }
        }
    }

    private val retryCountdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val seconds = intent.getIntExtra(ListenerService.EXTRA_RETRY_SECONDS, -1)
            activity?.runOnUiThread {
                updateRetryCountdown(seconds)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_glasses_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectionDot = view.findViewById(R.id.connectionDot)
        txtConnectionStatus = view.findViewById(R.id.txtConnectionStatus)
        txtDeviceName = view.findViewById(R.id.txtDeviceName)
        txtBattery = view.findViewById(R.id.txtBattery)
        txtRetryCountdown = view.findViewById(R.id.txtRetryCountdown)
        throughputSection = view.findViewById(R.id.throughputSection)
        txtTxRate = view.findViewById(R.id.txtTxRate)
        txtRxRate = view.findViewById(R.id.txtRxRate)
        txtTxTotal = view.findViewById(R.id.txtTxTotal)
        txtRxTotal = view.findViewById(R.id.txtRxTotal)
        chkAdbDebug = view.findViewById(R.id.chkAdbDebug)

        // Make connection dot circular
        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(requireContext(), R.color.gbx_status_disconnected))
        }
        connectionDot.background = dotDrawable

        btnInfo = view.findViewById(R.id.btnInfo)
        btnSettings = view.findViewById(R.id.btnSettings)

        btnInfo.isEnabled = false
        btnSettings.isEnabled = false

        btnInfo.setOnClickListener {
            GlassesInfoDialog(requireContext()).show()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(requireContext(), GlassesSettingsActivity::class.java))
        }

        btnPairMode = view.findViewById(R.id.btnPairMode)
        btnPairMode.setOnClickListener {
            btnPairMode.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gbx_orange))
            btnPairMode.setTextColor(ContextCompat.getColor(requireContext(), R.color.gbx_bg0_hard))
            btnPairMode.text = "Pairing..."
            requireContext().sendBroadcast(Intent(ACTION_ENTER_PAIR_MODE).apply {
                setPackage(requireContext().packageName)
            })
        }

        // ADB debugging toggle
        chkAdbDebug.isChecked = AppConfig.getGlassesAdbEnabled(requireContext())
        chkAdbDebug.isEnabled = false // enabled only when glasses are connected
        chkAdbDebug.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showAdbConfirmDialog()
            } else {
                sendAdbSetting(false)
            }
        }

        // Enable sideloading toggle. Persists the flag, pushes enable_sideloading to the
        // glasses over CH_SETTINGS, and starts/stops the phone's LAN HTTP server that the
        // desktop deploy script targets.
        chkEnableSideloading = view.findViewById(R.id.chkEnableSideloading)
        chkEnableSideloading.isChecked = AppConfig.getSideloadingEnabled(requireContext())
        chkEnableSideloading.setOnCheckedChangeListener { _, isChecked ->
            setSideloadingEnabled(isChecked)
        }

        // Weather widget toggle
        chkWeatherWidget = view.findViewById(R.id.chkWeatherWidget)
        chkWeatherWidget.isChecked = AppConfig.isWeatherEnabled(requireContext())
        chkWeatherWidget.setOnCheckedChangeListener { _, isChecked ->
            val ctx = requireContext()
            AppConfig.setWeatherEnabled(ctx, isChecked)
            if (isChecked) {
                com.repository.listener.network.WeatherScheduler.schedule(ctx)
                com.repository.listener.network.WeatherScheduler.oneShot(ctx)
            } else {
                com.repository.listener.network.WeatherScheduler.cancel(ctx)
                // Send hide frame to glasses so the widget disappears immediately if connected.
                sendHideWeatherFrame()
            }
        }

        // Display position control
        displayPositionView = view.findViewById(R.id.displayPositionView)
        btnResetDisplayPosition = view.findViewById(R.id.btnResetDisplayPosition)
        displayPositionView.normalizedY = AppConfig.getDisplayPositionY(requireContext())
        displayPositionView.isEnabled = false
        btnResetDisplayPosition.isEnabled = false

        displayPositionView.onPositionChanged = { normalizedY ->
            AppConfig.setDisplayPositionY(requireContext(), normalizedY)
            sendDisplayPosition(normalizedY)
        }

        btnResetDisplayPosition.setOnClickListener {
            displayPositionView.normalizedY = 0.5f
            AppConfig.setDisplayPositionY(requireContext(), 0.5f)
            sendDisplayPosition(0.5f)
        }

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
            throughputReceiver,
            IntentFilter(ListenerService.ACTION_GLASSES_THROUGHPUT),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            retryCountdownReceiver,
            IntentFilter(ListenerService.ACTION_GLASSES_RETRY_COUNTDOWN),
            Context.RECEIVER_NOT_EXPORTED
        )
        ctx.registerReceiver(
            batteryReceiver,
            IntentFilter(ListenerService.ACTION_GLASSES_BATTERY),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Sync current state (broadcast may have fired before we registered)
        updateConnectionStatus(
            ListenerService.glassesConnected,
            ListenerService.glassesConnecting,
            ListenerService.glassesDeviceName
        )
        updateBattery(ListenerService.glassesBatteryLevel, ListenerService.glassesBatteryCharging)
    }

    override fun onPause() {
        super.onPause()
        val ctx = requireContext()
        ctx.unregisterReceiver(glassesStateReceiver)
        ctx.unregisterReceiver(throughputReceiver)
        ctx.unregisterReceiver(retryCountdownReceiver)
        ctx.unregisterReceiver(batteryReceiver)
    }

    private fun updateConnectionStatus(connected: Boolean, connecting: Boolean, deviceName: String?) {
        isConnected = connected
        val dotDrawable = connectionDot.background as? GradientDrawable ?: return
        when {
            connected -> {
                dotDrawable.setColor(ContextCompat.getColor(requireContext(), R.color.gbx_status_connected))
                txtConnectionStatus.text = "Connected"
                txtRetryCountdown.visibility = View.GONE
            }
            connecting -> {
                dotDrawable.setColor(ContextCompat.getColor(requireContext(), R.color.gbx_yellow))
                txtConnectionStatus.text = "Connecting..."
                txtRetryCountdown.visibility = View.GONE
            }
            else -> {
                dotDrawable.setColor(ContextCompat.getColor(requireContext(), R.color.gbx_status_disconnected))
                txtConnectionStatus.text = "Disconnected"
            }
        }
        // Reset pair mode button when connected
        if (connected) {
            btnPairMode.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            btnPairMode.setTextColor(ContextCompat.getColor(requireContext(), R.color.gbx_fg))
            btnPairMode.text = "Pair Mode"
        }
        btnInfo.isEnabled = connected
        btnSettings.isEnabled = connected
        chkAdbDebug.isEnabled = connected
        displayPositionView.isEnabled = connected
        btnResetDisplayPosition.isEnabled = connected

        if (deviceName != null && connected) {
            txtDeviceName.text = deviceName
            txtDeviceName.visibility = View.VISIBLE
        } else {
            txtDeviceName.visibility = View.GONE
        }

        if (!connected) {
            txtBattery.visibility = View.GONE
        }
    }

    private fun updateBattery(level: Int, charging: Boolean) {
        if (level < 0) {
            txtBattery.visibility = View.GONE
            return
        }
        txtBattery.visibility = View.VISIBLE
        txtBattery.text = if (charging) "Battery: $level% (Charging)" else "Battery: $level%"
    }

    private fun updateRetryCountdown(seconds: Int) {
        if (isConnected || seconds < 0) {
            txtRetryCountdown.visibility = View.GONE
            return
        }
        txtRetryCountdown.visibility = View.VISIBLE
        txtRetryCountdown.text = "Attempting in ${seconds}s..."
    }

    private fun updateThroughput(txKbps: Float, rxKbps: Float, txTotal: Long, rxTotal: Long) {
        throughputSection.visibility = View.VISIBLE
        txtTxRate.text = "%.1f KB/s".format(txKbps)
        txtRxRate.text = "%.1f KB/s".format(rxKbps)
        txtTxTotal.text = formatBytes(txTotal)
        txtRxTotal.text = formatBytes(rxTotal)
    }

    private fun showAdbConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Warning")
            .setMessage(
                "ADB debugging is only for development purposes. " +
                "Enabling ADB debugging may lead to risks such as data leakage or corruption, " +
                "installation of unknown apps without notification and etc."
            )
            .setPositiveButton("Confirm enable") { _, _ ->
                sendAdbSetting(true)
            }
            .setNegativeButton("Cancel") { _, _ ->
                chkAdbDebug.setOnCheckedChangeListener(null)
                chkAdbDebug.isChecked = false
                chkAdbDebug.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) showAdbConfirmDialog() else sendAdbSetting(false)
                }
            }
            .setOnCancelListener {
                chkAdbDebug.setOnCheckedChangeListener(null)
                chkAdbDebug.isChecked = false
                chkAdbDebug.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) showAdbConfirmDialog() else sendAdbSetting(false)
                }
            }
            .show()
    }

    /** Push settings to glasses listener via CH_SETTINGS (CXR-S removed). */
    private fun sendAppSettings(vararg pairs: Pair<String, String>) {
        val svc = ListenerService.phoneBtHostInstance ?: run {
            LogCollector.w(TAG, "sendAppSettings: phoneBtHostInstance null; dropped")
            return
        }
        val json = JSONObject().apply {
            pairs.forEach { (key, value) -> put(key, value) }
        }.toString()
        try {
            svc.sendSettings(json)
        } catch (e: Exception) {
            LogCollector.e(TAG, "sendAppSettings failed: ${e.message}")
        }
    }

    private fun sendAdbSetting(enabled: Boolean) {
        AppConfig.setGlassesAdbEnabled(requireContext(), enabled)
        val value = if (enabled) "1" else "0"
        try {
            sendAppSettings("settings_adb_enable" to value)
            LogCollector.i(TAG, "ADB debugging ${if (enabled) "enabled" else "disabled"} (sent settings_adb_enable=$value)")
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to send ADB setting: ${e.message}")
        }
    }

    private fun setSideloadingEnabled(enabled: Boolean) {
        val ctx = requireContext()
        // Persist + start/stop the phone's LAN HTTP server atomically (phone-local, no glasses required).
        ListenerService.applySideloadingState(ctx, enabled)
        // Push the flag to the glasses as a JSON bool (matches GlassesConfig.optBoolean).
        val svc = ListenerService.phoneBtHostInstance
        if (svc != null) {
            try {
                svc.sendSettings(JSONObject().put("enable_sideloading", enabled).toString())
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to send enable_sideloading: ${e.message}")
            }
        } else {
            LogCollector.w(TAG, "setSideloadingEnabled: phoneBtHostInstance null; flag persisted, BT push skipped")
        }
        LogCollector.i(TAG, "Sideloading ${if (enabled) "enabled" else "disabled"}")
    }

    private fun sendDisplayPosition(normalizedY: Float) {
        // settings_screen_ui_bottom_margin adds bottom padding to all launcher pages,
        // pushing content upward. Higher value = content higher on display.
        // normalizedY: 0.0=top, 1.0=bottom -> invert for bottom margin
        val maxBottomMargin = 200
        val bottomMargin = ((1.0f - normalizedY) * maxBottomMargin).toInt()
        sendAppSettings("settings_screen_ui_bottom_margin" to bottomMargin.toString())
        LogCollector.i(TAG, "Set display position: bottomMargin=$bottomMargin (normalized=$normalizedY)")
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    private fun sendHideWeatherFrame() {
        val ctx = requireContext()
        ctx.sendBroadcast(Intent(ListenerService.ACTION_WEATHER_HIDE).setPackage(ctx.packageName))
    }
}
