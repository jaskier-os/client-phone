package com.repository.listener.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.repository.listener.MainActivity
import com.repository.listener.util.LogCollector

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "alarms"
        const val ACTION_FIRE = "com.repository.listener.ALARM_FIRE"
        const val ACTION_DISMISS = "com.repository.listener.ALARM_DISMISS"
        const val ACTION_SNOOZE = "com.repository.listener.ALARM_SNOOZE"
        const val ACTION_ALARM_FIRED = "com.repository.listener.ALARM_FIRED_TTS"
        private const val TAG = "AlarmReceiver"
        private const val SNOOZE_MINUTES = 5
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("alarm_id", -1)
        if (alarmId == -1) return

        when (intent.action) {
            ACTION_FIRE -> fireAlarm(context, alarmId)
            ACTION_DISMISS -> dismissAlarm(context, alarmId)
            ACTION_SNOOZE -> snoozeAlarm(context, alarmId)
        }
    }

    private fun fireAlarm(context: Context, alarmId: Int) {
        val alarm = AlarmStore.getAll(context).find { it.id == alarmId }
        val timeStr = if (alarm != null) String.format("%02d:%02d", alarm.hour, alarm.minute) else "Alarm"
        val title = alarm?.title?.ifEmpty { timeStr } ?: timeStr

        LogCollector.i(TAG, "Alarm fired: id=$alarmId $timeStr $title")
        ensureChannel(context)

        // Notify ListenerService for glasses TTS + ringtone playback
        context.sendBroadcast(Intent(ACTION_ALARM_FIRED).apply {
            setPackage(context.packageName)
            putExtra("alarm_id", alarmId)
            putExtra("time", timeStr)
            putExtra("title", title)
        })

        val alarmActivityIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmActivity.EXTRA_TIME, timeStr)
            putExtra(AlarmActivity.EXTRA_TITLE, title)
        }

        val fullScreenIntent = PendingIntent.getActivity(
            context, alarmId, alarmActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = PendingIntent.getBroadcast(
            context, alarmId or 0x10000000,
            Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra("alarm_id", alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            context, alarmId or 0x20000000,
            Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra("alarm_id", alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(timeStr)
            .setContentText(title)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreenIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissIntent)
            .addAction(android.R.drawable.ic_popup_reminder, "Snooze", snoozeIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alarmId, notification)
    }

    private fun dismissAlarm(context: Context, alarmId: Int) {
        LogCollector.i(TAG, "Alarm dismissed: id=$alarmId -- deleting")
        // Tell ListenerService to stop ringtone
        context.sendBroadcast(Intent(ACTION_ALARM_FIRED).apply {
            setPackage(context.packageName)
            putExtra("alarm_id", alarmId)
            putExtra("action", "dismiss")
        })
        // Remove the alarm permanently
        AlarmStore.delete(context, alarmId)
        context.sendBroadcast(Intent("com.repository.listener.ALARM_CHANGED").apply {
            setPackage(context.packageName)
        })
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(alarmId)
    }

    private fun snoozeAlarm(context: Context, alarmId: Int) {
        LogCollector.i(TAG, "Alarm snoozed: id=$alarmId (+${SNOOZE_MINUTES}min)")
        // Tell ListenerService to stop ringtone
        context.sendBroadcast(Intent(ACTION_ALARM_FIRED).apply {
            setPackage(context.packageName)
            putExtra("alarm_id", alarmId)
            putExtra("action", "snooze")
        })
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(alarmId)

        val alarm = AlarmStore.getAll(context).find { it.id == alarmId } ?: return
        val snoozeTrigger = System.currentTimeMillis() + SNOOZE_MINUTES * 60 * 1000L
        val snoozed = alarm.copy(triggerTimeMillis = snoozeTrigger)
        AlarmStore.save(context, snoozed)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarm notifications"
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
        }
        nm.createNotificationChannel(channel)
    }
}
