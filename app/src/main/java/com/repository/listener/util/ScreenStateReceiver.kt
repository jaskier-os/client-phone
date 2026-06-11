package com.repository.listener.util

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenStateReceiver : BroadcastReceiver() {

    interface ScreenStateListener {
        fun onScreenOff()
        fun onScreenUnlocked()
    }

    private var listener: ScreenStateListener? = null

    fun setListener(listener: ScreenStateListener) {
        this.listener = listener
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                listener?.onScreenOff()
            }
            Intent.ACTION_USER_PRESENT -> {
                listener?.onScreenUnlocked()
            }
            Intent.ACTION_SCREEN_ON -> {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (!km.isKeyguardLocked) {
                    listener?.onScreenUnlocked()
                }
            }
        }
    }
}
