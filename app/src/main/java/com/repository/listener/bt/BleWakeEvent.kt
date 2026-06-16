package com.repository.listener.bt

/**
 * Wake-event byte codes shared with the glasses (mirrored in
 * clients/glasses/app/.../listener/bt/BleWakeEvent.kt). Keep in sync.
 */
object BleWakeEvent {
    // glasses -> phone
    const val WAKE_WORD: Byte            = 0x01
    const val BUTTON_PRESS: Byte         = 0x02
    const val WEAR_CHANGED: Byte         = 0x03

    // either direction (generic)
    const val RFCOMM_REQUEST: Byte       = 0x10

    // phone -> glasses
    const val TTS_PENDING: Byte          = 0x11
    const val NOTIFICATION_PENDING: Byte = 0x12

    // phone -> glasses cold-start: ask bt-manager to startForegroundService the
    // glasses ListenerService. Mirrors bt-manager BleWakeEvent.LAUNCH_LISTENER.
    // Used when RFCOMM relay connect fails repeatedly (listener likely dead).
    const val LAUNCH_LISTENER: Byte      = 0x07

    // glasses -> phone, telemetry. byte[1] of payload carries SoC% (0..100).
    const val BATTERY_LEVEL: Byte        = 0x30

    // ping/pong reachability probe (either direction). For PING/PONG the byte[1] of payload
    // carries a requestId; bytes[2..10] carry the sender's nanoTime() (LE u64).
    // HELLO is glasses -> phone, advertising the link came up.
    const val BLE_PING: Byte             = 0x20
    const val BLE_PONG: Byte             = 0x21
    const val BLE_HELLO: Byte            = 0x22

    const val PAYLOAD_SIZE = 10
}
