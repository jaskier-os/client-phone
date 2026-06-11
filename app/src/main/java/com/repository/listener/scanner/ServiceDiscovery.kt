package com.repository.listener.scanner

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.InetSocketAddress

/**
 * Discovers network services via mDNS (NsdManager) and SSDP (UDP multicast).
 */
class ServiceDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "ServiceDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DISCOVERY_TIMEOUT_MS = 8000L

        // mDNS service types to discover
        private val MDNS_TYPES = listOf(
            "_http._tcp.",
            "_https._tcp.",
            "_rtsp._tcp.",
            "_ipp._tcp.",
            "_printer._tcp.",
            "_googlecast._tcp.",
            "_airplay._tcp.",
            "_raop._tcp.",
            "_hap._tcp.",           // HomeKit
            "_companion-link._tcp.",
            "_smb._tcp.",
            "_ssh._tcp.",
            "_rfb._tcp.",           // VNC
            "_pdl-datastream._tcp.",
            "_mqtt._tcp."
        )
    }

    /**
     * Discover mDNS services on the local network.
     * Uses Android's NsdManager for service discovery.
     * @return List of discovered mDNS services
     */
    suspend fun discoverMdns(): List<MdnsService> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<MdnsService>()
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available")
            return@withContext emptyList()
        }

        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        try {
            for (serviceType in MDNS_TYPES) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {
                        Log.d(TAG, "mDNS discovery started for $regType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        synchronized(discovered) {
                            discovered.add(
                                MdnsService(
                                    name = serviceInfo.serviceName ?: "",
                                    type = serviceInfo.serviceType ?: "",
                                    host = serviceInfo.host?.hostAddress ?: "",
                                    port = serviceInfo.port
                                )
                            )
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "mDNS discovery failed for $serviceType: error $errorCode")
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                }

                listeners.add(listener)
                try {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start mDNS discovery for $serviceType: ${e.message}")
                }
            }

            // Let discovery run for the timeout period
            delay(DISCOVERY_TIMEOUT_MS)
        } finally {
            // Stop all discovery listeners
            for (listener in listeners) {
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    // Ignore - might already be stopped
                }
            }
        }

        Log.i(TAG, "mDNS discovered ${discovered.size} services")
        discovered
    }

    /**
     * Discover SSDP/UPnP devices on the local network.
     * Sends M-SEARCH multicast and collects responses.
     * @return List of discovered SSDP devices
     */
    suspend fun discoverSsdp(): List<SsdpDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<SsdpDevice>()

        try {
            val searchMessage = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 3\r\n")
                append("ST: ssdp:all\r\n")
                append("\r\n")
            }

            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = DISCOVERY_TIMEOUT_MS.toInt()

            val messageBytes = searchMessage.toByteArray()
            val targetAddress = InetAddress.getByName(SSDP_ADDRESS)
            val packet = DatagramPacket(messageBytes, messageBytes.size, targetAddress, SSDP_PORT)

            socket.send(packet)
            Log.i(TAG, "SSDP M-SEARCH sent")

            val buffer = ByteArray(4096)
            val seenUsns = mutableSetOf<String>()

            try {
                while (true) {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)

                    val responseText = String(response.data, 0, response.length)
                    val headers = parseSsdpHeaders(responseText)

                    val usn = headers["usn"] ?: headers["USN"] ?: ""
                    if (usn.isNotEmpty() && usn in seenUsns) continue
                    seenUsns.add(usn)

                    devices.add(
                        SsdpDevice(
                            location = headers["location"] ?: headers["LOCATION"] ?: "",
                            server = headers["server"] ?: headers["SERVER"] ?: "",
                            usn = usn
                        )
                    )
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Expected - timeout means discovery period is over
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP discovery failed: ${e.message}", e)
        }

        Log.i(TAG, "SSDP discovered ${devices.size} devices")
        devices
    }

    private fun parseSsdpHeaders(response: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        for (line in response.split("\r\n")) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }
}
