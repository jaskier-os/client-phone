package com.repository.listener.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.pow

/**
 * Passive Bluetooth scanner for Classic and BLE device discovery.
 * Runs both scan types in parallel for a configurable duration, deduplicates by address,
 * and estimates distance from RSSI using the log-distance path loss model.
 */
class BluetoothScanner(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothScanner"
        private const val DEFAULT_TX_POWER = -59
        private const val PATH_LOSS_EXPONENT = 2.0
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    fun isAvailable(): Boolean {
        if (adapter == null || !adapter.isEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Run Classic + BLE scans in parallel for the given duration.
     * Returns a deduplicated list of discovered devices.
     */
    suspend fun scan(durationMs: Long = 10_000): List<BluetoothDeviceInfo> {
        if (!isAvailable()) {
            Log.w(TAG, "Bluetooth not available or missing permissions")
            return emptyList()
        }

        Log.i(TAG, "Starting Bluetooth scan (${durationMs}ms)")

        val devices = mutableMapOf<String, BluetoothDeviceInfo>()

        coroutineScope {
            val classicDeferred = async { scanClassic(durationMs) }
            val bleDeferred = async { scanBle(durationMs) }

            val classicResults = classicDeferred.await()
            val bleResults = bleDeferred.await()

            // Add classic results first
            for (device in classicResults) {
                devices[device.address] = device
            }
            // BLE results override classic (richer data)
            for (device in bleResults) {
                val existing = devices[device.address]
                if (existing != null) {
                    // Merge: upgrade type to "dual", keep BLE data
                    devices[device.address] = device.copy(
                        type = "dual",
                        name = device.name.takeIf { it != "Unknown" } ?: existing.name
                    )
                } else {
                    devices[device.address] = device
                }
            }
        }

        Log.i(TAG, "Bluetooth scan complete: ${devices.size} devices found")
        return devices.values.toList()
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanClassic(durationMs: Long): List<BluetoothDeviceInfo> =
        withContext(Dispatchers.IO) {
            val btAdapter = adapter ?: return@withContext emptyList()
            val results = mutableListOf<BluetoothDeviceInfo>()

            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.action != BluetoothDevice.ACTION_FOUND) return
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            ?: return
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                        val info = buildDeviceInfo(device, rssi, "classic", emptyList(), "")
                        synchronized(results) { results.add(info) }
                    }
                }

                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                context.registerReceiver(receiver, filter)

                // Cancel classic discovery when we're done
                cont.invokeOnCancellation {
                    try {
                        btAdapter.cancelDiscovery()
                        context.unregisterReceiver(receiver)
                    } catch (_: Exception) {}
                }

                btAdapter.startDiscovery()

                // Wait for scan duration then stop
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        btAdapter.cancelDiscovery()
                        context.unregisterReceiver(receiver)
                    } catch (_: Exception) {}

                    if (cont.isActive) cont.resume(results)
                }, durationMs)
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun scanBle(durationMs: Long): List<BluetoothDeviceInfo> =
        withContext(Dispatchers.IO) {
            val leScanner: BluetoothLeScanner = adapter?.bluetoothLeScanner
                ?: return@withContext emptyList()

            val results = mutableListOf<BluetoothDeviceInfo>()

            suspendCancellableCoroutine { cont ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: BleScanResult) {
                        val device = result.device
                        val rssi = result.rssi
                        val record = result.scanRecord

                        val serviceUuids = record?.serviceUuids
                            ?.map { it.uuid.toString() }
                            ?: emptyList()

                        val mfgData = buildManufacturerDataHex(record?.manufacturerSpecificData)

                        val txPower = record?.txPowerLevel ?: DEFAULT_TX_POWER

                        val info = buildDeviceInfo(device, rssi, "ble", serviceUuids, mfgData, txPower)
                        synchronized(results) { results.add(info) }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.e(TAG, "BLE scan failed with error code: $errorCode")
                        if (cont.isActive) cont.resume(results)
                    }
                }

                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

                leScanner.startScan(null, settings, callback)

                cont.invokeOnCancellation {
                    try { leScanner.stopScan(callback) } catch (_: Exception) {}
                }

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { leScanner.stopScan(callback) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(results)
                }, durationMs)
            }
        }

    @SuppressLint("MissingPermission")
    private fun buildDeviceInfo(
        device: BluetoothDevice,
        rssi: Int,
        type: String,
        serviceUuids: List<String>,
        manufacturerData: String,
        txPower: Int = DEFAULT_TX_POWER
    ): BluetoothDeviceInfo {
        val name = try { device.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }
        val address = device.address ?: "00:00:00:00:00:00"
        val btClass = try { device.bluetoothClass } catch (_: SecurityException) { null }
        val majorClass = btClass?.let { resolveMajorClass(it.majorDeviceClass) } ?: "Unknown"
        val minorClass = btClass?.let { resolveMinorClass(it.majorDeviceClass, it.deviceClass) } ?: "Unknown"
        val distance = estimateDistance(rssi, txPower)

        return BluetoothDeviceInfo(
            address = address,
            name = name,
            rssi = rssi,
            estimatedDistanceM = distance,
            type = type,
            majorClass = majorClass,
            minorClass = minorClass,
            serviceUuids = serviceUuids,
            manufacturerData = manufacturerData
        )
    }

    private fun buildManufacturerDataHex(data: android.util.SparseArray<ByteArray>?): String {
        if (data == null || data.size() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until data.size()) {
            val key = data.keyAt(i)
            val value = data.valueAt(i)
            if (sb.isNotEmpty()) sb.append(";")
            sb.append(String.format("%04X:", key))
            sb.append(value.joinToString("") { String.format("%02X", it) })
        }
        return sb.toString()
    }

    /**
     * Estimate distance in meters from RSSI using log-distance path loss model.
     * distance = 10 ^ ((txPower - rssi) / (10 * n))
     */
    private fun estimateDistance(rssi: Int, txPower: Int = DEFAULT_TX_POWER): Double {
        if (rssi == 0 || rssi == Short.MIN_VALUE.toInt()) return -1.0
        val ratio = (txPower - rssi).toDouble() / (10.0 * PATH_LOSS_EXPONENT)
        // Round to 1 decimal WITHOUT String.format -- that uses the default locale, which on a
        // Russian-locale device emits a comma ("50,1") and then crashes Double.parseDouble.
        return kotlin.math.round(10.0.pow(ratio) * 10.0) / 10.0
    }

    private fun resolveMajorClass(major: Int): String = when (major) {
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio/Video"
        BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
        BluetoothClass.Device.Major.IMAGING -> "Imaging"
        BluetoothClass.Device.Major.WEARABLE -> "Wearable"
        BluetoothClass.Device.Major.TOY -> "Toy"
        BluetoothClass.Device.Major.HEALTH -> "Health"
        BluetoothClass.Device.Major.NETWORKING -> "Networking"
        BluetoothClass.Device.Major.MISC -> "Miscellaneous"
        BluetoothClass.Device.Major.UNCATEGORIZED -> "Uncategorized"
        else -> "Unknown"
    }

    private fun resolveMinorClass(major: Int, deviceClass: Int): String {
        // Minor class is encoded in bits 2-7 of the device class
        return when (major) {
            BluetoothClass.Device.Major.COMPUTER -> when (deviceClass) {
                BluetoothClass.Device.COMPUTER_DESKTOP -> "Desktop"
                BluetoothClass.Device.COMPUTER_SERVER -> "Server"
                BluetoothClass.Device.COMPUTER_LAPTOP -> "Laptop"
                BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA -> "Handheld"
                BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA -> "PDA"
                BluetoothClass.Device.COMPUTER_WEARABLE -> "Wearable"
                else -> "Computer"
            }
            BluetoothClass.Device.Major.PHONE -> when (deviceClass) {
                BluetoothClass.Device.PHONE_CELLULAR -> "Cellular"
                BluetoothClass.Device.PHONE_CORDLESS -> "Cordless"
                BluetoothClass.Device.PHONE_SMART -> "Smartphone"
                BluetoothClass.Device.PHONE_MODEM_OR_GATEWAY -> "Modem"
                BluetoothClass.Device.PHONE_ISDN -> "ISDN"
                else -> "Phone"
            }
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "Headphones"
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> "Speaker"
                BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE -> "Microphone"
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "Car Audio"
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "HiFi"
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> "Portable Audio"
                BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> "Set-top Box"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> "TV/Display"
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "Handsfree"
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "Headset"
                else -> "Audio/Video"
            }
            BluetoothClass.Device.Major.PERIPHERAL -> when {
                deviceClass and 0x0040 != 0 -> "Keyboard"
                deviceClass and 0x0080 != 0 -> "Pointing Device"
                deviceClass and 0x00C0 != 0 -> "Combo Keyboard/Pointing"
                deviceClass and 0x0010 != 0 -> "Joystick"
                deviceClass and 0x0020 != 0 -> "Gamepad"
                else -> "Peripheral"
            }
            else -> "Unknown"
        }
    }
}
