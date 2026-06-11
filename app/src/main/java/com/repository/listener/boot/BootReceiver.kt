package com.repository.listener.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.repository.listener.alarm.AlarmStore
import com.repository.listener.service.ListenerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Starting ListenerService after: ${intent.action}")
            val serviceIntent = Intent(context, ListenerService::class.java)
            context.startForegroundService(serviceIntent)
            AlarmStore.rescheduleAll(context)
        }
    }
}
