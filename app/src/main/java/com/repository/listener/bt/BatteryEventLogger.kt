package com.repository.listener.bt

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Appends one row per BLE BATTERY_LEVEL notify to
 *   filesDir/logs/glasses_battery.log
 *
 * Row format (tab-separated):
 *   <host_epoch_ms>\t<device_epoch_ns>\t<level>
 *
 * Pull via:
 *   adb -s <phone> shell run-as com.repository.listener \
 *       cat files/logs/glasses_battery.log
 *
 * Or over WiFi P2P using the existing log-pull infrastructure
 * (see AI/clients/phone/test/adb/pull_glasses_log.sh for the pattern).
 */
class BatteryEventLogger(private val context: Context) {
    private val file: File by lazy {
        File(context.filesDir, "logs/glasses_battery.log").apply {
            parentFile?.mkdirs()
        }
    }

    fun record(level: Int, epochNanos: Long) {
        val hostEpochMs = System.currentTimeMillis()
        val line = "$hostEpochMs\t$epochNanos\t$level\n"
        try {
            file.appendText(line)
        } catch (e: Exception) {
            Log.w("BatteryEventLogger", "append failed: ${e.message}")
        }
    }
}
