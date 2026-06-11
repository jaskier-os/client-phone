package com.repository.listener

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.repository.listener.alarm.AlarmReceiver
import com.repository.listener.config.AppConfig
import com.repository.listener.util.LogCollector
import com.repository.navigation.NavigationManager
import com.repository.navigation.NavigationModule

class ListenerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogCollector.init(this)
        // Load Rokid CXR native libs (libcaps.so + libmutils.so) eagerly.
        // The SDK uses Caps.toString() (which calls native dump()) for debug
        // logging inside CxrApi.sendCustomCmd. If any UI path constructs a
        // Caps before GlassesGattClient's static init has run -- e.g., the
        // GlassesSettingsFragment display-position slider -- the native is
        // unbound and the activity crashes with UnsatisfiedLinkError.
        // Loading here in Application.onCreate guarantees the libs are up
        // before any fragment touches Caps.
        try { System.loadLibrary("mutils") } catch (_: UnsatisfiedLinkError) {}
        try { System.loadLibrary("caps") } catch (_: UnsatisfiedLinkError) {}
        // Resolve + init the active map provider from config (default yandex). With
        // Google selected and a blank MapKit key this still runs -- the Google
        // provider's init does no Yandex work.
        NavigationModule.init(this, AppConfig.getMapProvider(this))
        NavigationManager.getInstance(this).restoreSession(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        // Drop legacy channels so their old (private-lockscreen / low-importance)
        // settings stop shadowing the v3 channel. HyperOS/POCO hides LOW-importance
        // notifications from the lockscreen even with VISIBILITY_PUBLIC -- we need
        // DEFAULT importance to show on lockscreen; setSilent(true) on the builder
        // keeps it from making any sound.
        manager.deleteNotificationChannel("listener_service")
        manager.deleteNotificationChannel("listener_service_v2")

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)

        val alarmChannel = NotificationChannel(
            AlarmReceiver.CHANNEL_ID,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
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
        manager.createNotificationChannel(alarmChannel)

        val rcDoneChannel = NotificationChannel(
            RC_DONE_CHANNEL_ID,
            "Claude Code Done",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a Claude Code session finishes"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200)
            setShowBadge(true)
        }
        manager.createNotificationChannel(rcDoneChannel)
    }

    companion object {
        const val CHANNEL_ID = "listener_service_v3"
        const val RC_DONE_CHANNEL_ID = "rc_turn_complete"
    }
}
