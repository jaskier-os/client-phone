package com.repository.listener.scanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Collects WiFi network information: SSID, BSSID, gateway, subnet, public IP, etc.
 * Uses ConnectivityManager and WifiManager (no root needed).
 */
class WifiInfoCollector(private val context: Context) {

    companion object {
        private const val TAG = "WifiInfoCollector"
    }

    /**
     * Collect current WiFi network information.
     * @return WifiInfo or null if not connected to WiFi
     */
    suspend fun collect(): WifiInfo? = withContext(Dispatchers.IO) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

            if (wifiManager == null || connectivityManager == null) {
                Log.e(TAG, "WiFi or Connectivity manager not available")
                return@withContext null
            }

            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                Log.w(TAG, "Not connected to WiFi")
                return@withContext null
            }

            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            @Suppress("DEPRECATION")
            val dhcpInfo = wifiManager.dhcpInfo

            val ssid = wifiInfo?.ssid?.removeSurrounding("\"") ?: "unknown"
            val bssid = wifiInfo?.bssid ?: "unknown"

            // Gateway IP from DHCP info
            val gatewayIp = intToIp(dhcpInfo?.gateway ?: 0)
            val ownIp = intToIp(dhcpInfo?.ipAddress ?: 0)
            val netmask = intToIp(dhcpInfo?.netmask ?: 0)
            val subnet = calculateSubnet(ownIp, netmask)
            val dns = listOfNotNull(
                dhcpInfo?.dns1?.let { if (it != 0) intToIp(it) else null },
                dhcpInfo?.dns2?.let { if (it != 0) intToIp(it) else null }
            )

            // Determine security type
            val security = getSecurityType()

            // Fetch public IP
            val publicIp = fetchPublicIp()

            WifiInfo(
                ssid = ssid,
                bssid = bssid,
                security = security,
                gatewayIp = gatewayIp,
                ownIp = ownIp,
                subnet = subnet,
                dns = dns,
                publicIp = publicIp
            ).also {
                Log.i(TAG, "WiFi info collected: SSID=$ssid, gateway=$gatewayIp, own=$ownIp, subnet=$subnet")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect WiFi info: ${e.message}", e)
            null
        }
    }

    /**
     * Get the subnet in CIDR notation from device IP and netmask.
     */
    fun calculateSubnet(ip: String, netmask: String): String {
        try {
            val ipParts = ip.split(".").map { it.toInt() }
            val maskParts = netmask.split(".").map { it.toInt() }
            if (ipParts.size != 4 || maskParts.size != 4) return "$ip/24"

            val maskInt = maskParts.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }

            // If netmask is zero (common on Android 10+ with deprecated DhcpInfo), assume /24
            if (maskInt == 0L) {
                val networkAddr = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}.0"
                return "$networkAddr/24"
            }

            val networkParts = ipParts.zip(maskParts).map { (i, m) -> i and m }
            val networkAddr = networkParts.joinToString(".")
            val cidr = java.lang.Long.bitCount(maskInt)

            return "$networkAddr/$cidr"
        } catch (e: Exception) {
            return "$ip/24"
        }
    }

    private fun getSecurityType(): String {
        // On Android 10+, direct security type detection requires scanning results
        // Return a reasonable default based on what we can detect
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo
            when {
                info == null -> "unknown"
                else -> "WPA2/WPA3" // Most modern networks use WPA2+
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun fetchPublicIp(): String {
        return try {
            val url = java.net.URL("https://api.ipify.org")
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.getInputStream().bufferedReader().readText().trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch public IP: ${e.message}")
            "unknown"
        }
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
