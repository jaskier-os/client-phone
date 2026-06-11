package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.repository.listener.util.LogCollector
import java.util.LinkedList
import java.util.UUID

data class ServiceInfo(
    val serviceUuid: UUID,
    val characteristics: List<CharacteristicInfo>
)

data class CharacteristicInfo(
    val uuid: UUID,
    val properties: Int,
    val supportsNotify: Boolean,
    val supportsRead: Boolean,
    val supportsWrite: Boolean
)

@SuppressLint("MissingPermission")
class WowMouseGattClient(
    private val context: Context
) {

    companion object {
        private const val TAG = "WowMouseGatt"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TIMEOUT_CONNECT_MS = 10_000L
        private const val TIMEOUT_DISCOVER_MS = 5_000L
    }

    interface Callback {
        fun onConnected()
        fun onServicesDiscovered(services: List<ServiceInfo>)
        fun onRawNotification(serviceUuid: UUID, charUuid: UUID, data: ByteArray)
        fun onMouseEvent(event: MouseEvent)
        fun onDisconnected()
        fun onFailed(reason: String)
    }

    private enum class State {
        IDLE, CONNECTING, DISCOVERING_SERVICES, ENUMERATING, SUBSCRIBING, STREAMING
    }

    private val handler = Handler(Looper.getMainLooper())
    private val parser = WowMouseParser()
    private var gatt: BluetoothGatt? = null
    private var state = State.IDLE
    private var callback: Callback? = null
    private var timeoutRunnable: Runnable? = null
    private var closed = false

    private val notifyQueue = LinkedList<BluetoothGattCharacteristic>()
    private val discoveredServices = mutableListOf<ServiceInfo>()
    // Map from characteristic UUID to its parent service UUID for notification callbacks
    private val charToServiceMap = mutableMapOf<UUID, UUID>()

    private fun log(msg: String) {
        LogCollector.i(TAG, msg)
    }

    fun connect(device: BluetoothDevice, callback: Callback) {
        this.callback = callback
        this.closed = false
        this.discoveredServices.clear()
        this.charToServiceMap.clear()
        this.notifyQueue.clear()

        state = State.CONNECTING
        log("Connecting to ${device.name ?: device.address} (TRANSPORT_LE, autoConnect=false)")

        startTimeout(TIMEOUT_CONNECT_MS) {
            log("Connection timeout after ${TIMEOUT_CONNECT_MS}ms")
            fail("Connection timed out")
        }

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        closed = true
        cancelTimeout()
        closeGatt()
        state = State.IDLE
        callback = null
    }

    val isConnected: Boolean
        get() = state == State.STREAMING || state == State.SUBSCRIBING || state == State.ENUMERATING

    private fun fail(reason: String) {
        if (closed) return
        closeGatt()
        state = State.IDLE
        log("Failed: $reason")
        callback?.onFailed(reason)
    }

    private fun closeGatt() {
        cancelTimeout()
        gatt?.let { g ->
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
        gatt = null
    }

    private fun startTimeout(ms: Long, onTimeout: () -> Unit) {
        cancelTimeout()
        val r = Runnable { onTimeout() }
        timeoutRunnable = r
        handler.postDelayed(r, ms)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun enumerateServices(g: BluetoothGatt) {
        state = State.ENUMERATING
        val services = g.services

        for (service in services) {
            val charInfos = mutableListOf<CharacteristicInfo>()
            for (char in service.characteristics) {
                val props = char.properties
                val supportsNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val supportsRead = (props and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                val supportsWrite = (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

                charInfos.add(CharacteristicInfo(char.uuid, props, supportsNotify, supportsRead, supportsWrite))

                log("  Service ${service.uuid} | Char ${char.uuid} | props=0x${props.toString(16)} notify=$supportsNotify read=$supportsRead write=$supportsWrite")

                if (supportsNotify) {
                    notifyQueue.add(char)
                    charToServiceMap[char.uuid] = service.uuid
                }
            }
            discoveredServices.add(ServiceInfo(service.uuid, charInfos))
        }

        log("Enumerated ${services.size} services, ${notifyQueue.size} notify characteristics queued")
        callback?.onServicesDiscovered(discoveredServices)

        subscribeNext(g)
    }

    private fun subscribeNext(g: BluetoothGatt) {
        if (closed) return

        val char = notifyQueue.poll()
        if (char == null) {
            state = State.STREAMING
            log("All notify subscriptions complete, now streaming")
            return
        }

        state = State.SUBSCRIBING
        log("Subscribing to notifications on ${char.uuid}")

        g.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        } else {
            log("No CCCD descriptor on ${char.uuid}, skipping write (notification enabled locally)")
            handler.post { subscribeNext(g) }
        }
    }

    @Suppress("DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            handler.post {
                if (closed) return@post

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    cancelTimeout()
                    log("Connected, discovering services...")
                    callback?.onConnected()

                    state = State.DISCOVERING_SERVICES
                    startTimeout(TIMEOUT_DISCOVER_MS) {
                        log("Service discovery timeout")
                        fail("Service discovery timed out")
                    }
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cancelTimeout()
                    val wasConnected = state != State.CONNECTING
                    closeGatt()
                    state = State.IDLE
                    if (wasConnected) {
                        log("Disconnected (status=$status)")
                        callback?.onDisconnected()
                    } else {
                        log("Connection failed (status=$status)")
                        fail("GATT connection failed (status=$status)")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (closed) return
            handler.post {
                if (closed || state != State.DISCOVERING_SERVICES) return@post
                cancelTimeout()

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Service discovery failed (status=$status)")
                    return@post
                }

                log("Discovered ${g.services.size} services")
                enumerateServices(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed) return
            handler.post {
                if (closed) return@post

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("CCCD write OK for ${descriptor.characteristic.uuid}")
                } else {
                    log("CCCD write failed for ${descriptor.characteristic.uuid} (status=$status)")
                }
                subscribeNext(g)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (closed) return
            val data = characteristic.value ?: return
            val charUuid = characteristic.uuid
            val serviceUuid = charToServiceMap[charUuid] ?: UUID(0, 0)
            val hex = data.joinToString(" ") { "%02x".format(it) }

            log("NOTIFY ${charUuid}: [$hex] (${data.size} bytes)")

            callback?.onRawNotification(serviceUuid, charUuid, data)

            val event = parser.parse(data)
            if (event != null) {
                callback?.onMouseEvent(event)
            }
        }
    }
}
