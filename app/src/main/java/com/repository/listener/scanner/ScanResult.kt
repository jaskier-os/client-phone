package com.repository.listener.scanner

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data classes for network scan results.
 * The final ScanResult is serialized to JSON and sent to the server via WebSocket.
 */

data class WifiInfo(
    val ssid: String,
    val bssid: String,
    val security: String,
    val gatewayIp: String,
    val ownIp: String,
    val subnet: String,
    val dns: List<String>,
    val publicIp: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ssid", ssid)
        put("bssid", bssid)
        put("security", security)
        put("gatewayIp", gatewayIp)
        put("ownIp", ownIp)
        put("subnet", subnet)
        put("dns", JSONArray(dns))
        put("publicIp", publicIp)
    }
}

data class ArpEntry(
    val ip: String,
    val mac: String,
    val iface: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ip", ip)
        put("mac", mac)
        put("interface", iface)
    }
}

data class NmapPort(
    val port: Int,
    val state: String,
    val service: String,
    val version: String,
    val product: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("port", port)
        put("state", state)
        put("service", service)
        put("version", version)
        put("product", product)
    }
}

data class NmapHost(
    val ip: String,
    val mac: String,
    val macVendor: String,
    val state: String,
    val ports: List<NmapPort>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ip", ip)
        put("mac", mac)
        put("macVendor", macVendor)
        put("state", state)
        put("ports", JSONArray().apply { ports.forEach { put(it.toJson()) } })
    }
}

data class NmapScanStats(
    val hostsUp: Int,
    val hostsTotal: Int,
    val elapsed: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hostsUp", hostsUp)
        put("hostsTotal", hostsTotal)
        put("elapsed", elapsed)
    }
}

data class NmapResult(
    val hosts: List<NmapHost>,
    val scanStats: NmapScanStats
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hosts", JSONArray().apply { hosts.forEach { put(it.toJson()) } })
        put("scanStats", scanStats.toJson())
    }
}

data class MdnsService(
    val name: String,
    val type: String,
    val host: String,
    val port: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("type", type)
        put("host", host)
        put("port", port)
    }
}

data class SsdpDevice(
    val location: String,
    val server: String,
    val usn: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("location", location)
        put("server", server)
        put("usn", usn)
    }
}

data class BluetoothDeviceInfo(
    val address: String,
    val name: String,
    val rssi: Int,
    val estimatedDistanceM: Double,
    val type: String,
    val majorClass: String,
    val minorClass: String,
    val serviceUuids: List<String>,
    val manufacturerData: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("address", address)
        put("name", name)
        put("rssi", rssi)
        put("estimatedDistanceM", estimatedDistanceM)
        put("type", type)
        put("majorClass", majorClass)
        put("minorClass", minorClass)
        put("serviceUuids", JSONArray(serviceUuids))
        put("manufacturerData", manufacturerData)
    }
}

data class ScanResult(
    val wifi: WifiInfo?,
    val arpTable: List<ArpEntry>,
    val nmapScan: NmapResult?,
    val mdnsServices: List<MdnsService>,
    val ssdpDevices: List<SsdpDevice>,
    val bluetoothDevices: List<BluetoothDeviceInfo>,
    val scanDurationMs: Long,
    val scanMethod: String = "nmap"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        wifi?.let { put("wifi", it.toJson()) }
        put("arpTable", JSONArray().apply { arpTable.forEach { put(it.toJson()) } })
        nmapScan?.let { put("nmapScan", it.toJson()) }
        put("mdnsServices", JSONArray().apply { mdnsServices.forEach { put(it.toJson()) } })
        put("ssdpDevices", JSONArray().apply { ssdpDevices.forEach { put(it.toJson()) } })
        if (bluetoothDevices.isNotEmpty()) {
            put("bluetoothDevices", JSONArray().apply { bluetoothDevices.forEach { put(it.toJson()) } })
        }
        put("scanDurationMs", scanDurationMs)
        put("scanMethod", scanMethod)
    }
}
