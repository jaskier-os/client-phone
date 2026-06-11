package com.repository.listener.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector

/**
 * Exported BroadcastReceiver that accepts ADB commands for testing and automation.
 *
 * Usage from PC:
 *   adb shell am broadcast -a com.repository.listener.ADB_COMMAND \
 *     --es type "status" \
 *     --es command_id "cmd001" \
 *     --es params "{}"
 *
 * Writes a pending result file immediately, then dispatches to ListenerService
 * which handles the command and writes the final result.
 */
class AdbCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AdbCommandReceiver"
        const val ACTION_ADB_COMMAND = "com.repository.listener.ADB_COMMAND"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADB_COMMAND) return

        val type = intent.getStringExtra("type") ?: run {
            LogCollector.e(TAG, "ADB command missing 'type' extra")
            return
        }
        val commandId = intent.getStringExtra("command_id")
            ?: "adb_${System.currentTimeMillis()}"
        val params = intent.getStringExtra("params") ?: "{}"

        LogCollector.i(TAG, "ADB command received: type=$type, id=$commandId")

        // Write pending result immediately so the caller can confirm receipt
        AdbResultWriter.writePending(context, commandId, type)

        // Dispatch to ListenerService
        val serviceIntent = Intent(context, ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("type", type)
            putExtra("command_id", commandId)
            putExtra("params", params)
        }

        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to start ListenerService: ${e.message}")
            AdbResultWriter.writeError(context, commandId, type,
                "Failed to start service: ${e.message}")
        }
    }
}
