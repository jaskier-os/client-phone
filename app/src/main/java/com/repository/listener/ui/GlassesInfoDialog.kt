package com.repository.listener.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.repository.listener.R
import com.repository.listener.util.LogCollector
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.GlassInfoResultCallback
import com.rokid.cxr.client.extend.infos.GlassInfo
import com.rokid.cxr.client.utils.ValueUtil

class GlassesInfoDialog(private val context: Context) {

    companion object {
        private const val TAG = "GlassesInfoDialog"
    }

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_glasses_info, null)

        val txtMac = view.findViewById<TextView>(R.id.txtInfoMac)
        val txtDeviceName = view.findViewById<TextView>(R.id.txtInfoDeviceName)
        val txtBattery = view.findViewById<TextView>(R.id.txtInfoBattery)
        val txtSerial = view.findViewById<TextView>(R.id.txtInfoSerial)
        val txtFirmware = view.findViewById<TextView>(R.id.txtInfoFirmware)
        val permissionsContainer = view.findViewById<LinearLayout>(R.id.permissionsContainer)

        // Populate BT permissions (skip legacy BLUETOOTH/BLUETOOTH_ADMIN on API 31+
        // where they are always "denied" despite BT working via the new runtime permissions)
        val btPermissions = mutableListOf<Pair<String, String>>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            btPermissions.add("BLUETOOTH" to Manifest.permission.BLUETOOTH)
            btPermissions.add("BLUETOOTH_ADMIN" to Manifest.permission.BLUETOOTH_ADMIN)
        }
        btPermissions.add("ACCESS_FINE_LOCATION" to Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermissions.add("BLUETOOTH_SCAN" to Manifest.permission.BLUETOOTH_SCAN)
            btPermissions.add("BLUETOOTH_CONNECT" to Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            btPermissions.add("NEARBY_WIFI_DEVICES" to Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        for ((label, permission) in btPermissions) {
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            val tv = TextView(context).apply {
                text = "$label: ${if (granted) "granted" else "denied"}"
                textSize = 12f
                setTextColor(if (granted) 0xFF00AA00.toInt() else 0xFFAA0000.toInt())
                setPadding(0, 4, 0, 4)
            }
            permissionsContainer.addView(tv)
        }

        // Try to get glasses info via CxrApi
        try {
            CxrApi.getInstance().getGlassInfo(object : GlassInfoResultCallback {
                override fun onGlassInfoResult(status: ValueUtil.CxrStatus?, glassInfo: GlassInfo?) {
                    if (glassInfo != null) {
                        txtBattery.post { txtBattery.text = "Battery: ${glassInfo.batteryLevel}%" }
                        txtSerial.post { txtSerial.text = "Serial: ${glassInfo.deviceId ?: "--"}" }
                        txtFirmware.post { txtFirmware.text = "Firmware: ${glassInfo.systemVersion ?: "--"}" }
                    }
                }
            })
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to get glass info: ${e.message}")
        }

        // Device info: try BluetoothController reflection, fall back to AppConfig
        var mac: String? = null
        var deviceName: String? = null
        try {
            val btCtrl = com.rokid.cxr.client.controllers.BluetoothController.getInstance()
            val pField = btCtrl.javaClass.getDeclaredField("p") // classic MAC
            pField.isAccessible = true
            mac = pField.get(btCtrl) as? String

            val kField = btCtrl.javaClass.getDeclaredField("k") // classic BluetoothDevice
            kField.isAccessible = true
            val device = kField.get(btCtrl) as? android.bluetooth.BluetoothDevice
            if (device != null) {
                @Suppress("MissingPermission")
                deviceName = device.name
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to get BT controller info: ${e.message}")
        }

        // Fallback to AppConfig / BluetoothHelper stored values
        if (mac.isNullOrEmpty()) {
            val saved = com.repository.listener.config.AppConfig.getGlassesMac(context)
            if (saved.isNotEmpty()) mac = saved
        }
        if (mac != null) txtMac.text = "MAC: $mac"
        if (deviceName != null) {
            txtDeviceName.text = "Device: $deviceName"
        } else {
            val savedName = com.repository.listener.config.AppConfig.getGlassesDeviceName(context)
            if (savedName.isNotEmpty()) txtDeviceName.text = "Device: $savedName"
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Glasses Information")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }
}
