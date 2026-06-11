package com.repository.listener.scanner

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Coordinator for all network scanning phases.
 * Runs WiFi info collection, ARP table reading, port scanning, mDNS, and SSDP discovery.
 * Falls back to TCP connect scanning when nmap binary is unavailable.
 */
class NetworkScanner(private val context: Context) {

    companion object {
        private const val TAG = "NetworkScanner"
        private const val BT_SCAN_DURATION_MS = 10_000L
        val DEFAULT_PORTS = listOf(
            // Network infra
            22, 23, 53, 80, 443, 8080, 8443,
            // Cameras/CCTV
            554, 8554, 3702, 37777, 34567, 9000,
            // Smart TVs
            8008, 8001, 8002, 3000, 3001, 7000, 1925,
            // IoT/Smart Home
            1883, 8883, 5683, 49152, 49153, 49154, 10001,
            // Printers
            631, 9100, 515,
            // File sharing
            445, 139, 548, 2049, 21,
            // Media
            1900, 5353, 32400, 8096,
            // Remote access
            3389, 5900, 62078,
            // Databases
            3306, 5432, 6379, 27017,
            // General
            5000, 8888, 9090
        ).joinToString(",")
    }

    private val wifiInfoCollector = WifiInfoCollector(context)
    private val arpTableReader = ArpTableReader()
    private val nmapExecutor = NmapExecutor(context)
    private val tcpConnectScanner = TcpConnectScanner()
    private val serviceDiscovery = ServiceDiscovery(context)
    private val bluetoothScanner = BluetoothScanner(context)

    /**
     * Run a full network scan.
     * @param scanConfig Optional configuration from server (ports, flags)
     * @return ScanResult containing all scan data
     */
    suspend fun scan(scanConfig: JSONObject? = null): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Starting network scan")

        // Start Bluetooth scan early -- it runs independently of WiFi
        val btAvailable = bluetoothScanner.isAvailable()
        Log.i(TAG, "Bluetooth scanner available: $btAvailable")

        // Phase 1: Collect WiFi info (fast)
        val wifiInfo = wifiInfoCollector.collect()
        if (wifiInfo == null) {
            Log.e(TAG, "Not connected to WiFi -- running Bluetooth-only scan")
            val btDevices = if (btAvailable) bluetoothScanner.scan(BT_SCAN_DURATION_MS) else emptyList()
            return@withContext ScanResult(
                wifi = null,
                arpTable = emptyList(),
                nmapScan = null,
                mdnsServices = emptyList(),
                ssdpDevices = emptyList(),
                bluetoothDevices = btDevices,
                scanDurationMs = System.currentTimeMillis() - startTime,
                scanMethod = "none"
            )
        }

        Log.i(TAG, "WiFi info collected, subnet: ${wifiInfo.subnet}")

        // Fix subnet if DHCP returned invalid data (0.0.0.0/0)
        val effectiveSubnet = if (wifiInfo.subnet.startsWith("0.0.0.0")) {
            val parts = wifiInfo.gatewayIp.split(".")
            if (parts.size == 4) {
                "${parts[0]}.${parts[1]}.${parts[2]}.0/24".also {
                    Log.w(TAG, "Subnet invalid (${wifiInfo.subnet}), derived from gateway: $it")
                }
            } else wifiInfo.subnet
        } else {
            wifiInfo.subnet
        }

        // Extract nmap binary and check availability
        val nmapAvailable = nmapExecutor.extract()

        // Determine ports to scan
        val ports = scanConfig?.optString("ports", "")?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PORTS

        // Phase 2-4: Run remaining phases in parallel (including Bluetooth)
        val result = coroutineScope {
            val arpDeferred = async { arpTableReader.read() }
            val mdnsDeferred = async { serviceDiscovery.discoverMdns() }
            val ssdpDeferred = async { serviceDiscovery.discoverSsdp() }
            val btDeferred = if (btAvailable) async { bluetoothScanner.scan(BT_SCAN_DURATION_MS) } else null

            var arpTable = arpDeferred.await()
            Log.i(TAG, "ARP table: ${arpTable.size} entries")

            // If ARP table is empty, stimulate it with UDP broadcast to populate cache
            if (arpTable.isEmpty() && wifiInfo.gatewayIp.isNotEmpty() && wifiInfo.gatewayIp != "0.0.0.0") {
                Log.i(TAG, "ARP table empty, stimulating with subnet UDP sweep")
                stimulateArp(wifiInfo.gatewayIp)
                arpTable = arpTableReader.read()
                Log.i(TAG, "Post-stimulation ARP table: ${arpTable.size} entries")
            }

            // Choose scanner based on nmap availability
            val (nmapResult, scanMethod) = if (nmapAvailable) {
                Log.i(TAG, "Using nmap scanner on $effectiveSubnet")
                val result = nmapExecutor.scan(effectiveSubnet, ports)
                result to "nmap"
            } else {
                if (arpTable.isNotEmpty()) {
                    Log.i(TAG, "nmap unavailable, using TCP connect scanner on ${arpTable.size} ARP hosts")
                    val result = tcpConnectScanner.scan(arpTable, ports)
                    result to "tcp_connect"
                } else {
                    // Last resort: generate targets from gateway /24
                    val subnetTargets = generateSubnetTargets(wifiInfo.gatewayIp, wifiInfo.ownIp)
                    if (subnetTargets.isNotEmpty()) {
                        Log.i(TAG, "nmap unavailable and ARP empty, scanning ${subnetTargets.size} derived subnet IPs")
                        val result = tcpConnectScanner.scan(subnetTargets, ports)
                        result to "tcp_connect"
                    } else {
                        Log.w(TAG, "nmap unavailable and no targets available")
                        NmapResult(emptyList(), NmapScanStats(0, 0, "0")) to "tcp_connect_no_targets"
                    }
                }
            }
            Log.i(TAG, "Port scan complete: ${nmapResult.hosts.size} hosts (method=$scanMethod)")

            val mdnsServices = mdnsDeferred.await()
            val ssdpDevices = ssdpDeferred.await()
            val btDevices = btDeferred?.await() ?: emptyList()
            Log.i(TAG, "Bluetooth scan: ${btDevices.size} devices")

            ScanResult(
                wifi = wifiInfo,
                arpTable = arpTable,
                nmapScan = nmapResult,
                mdnsServices = mdnsServices,
                ssdpDevices = ssdpDevices,
                bluetoothDevices = btDevices,
                scanDurationMs = System.currentTimeMillis() - startTime,
                scanMethod = scanMethod
            )
        }

        Log.i(TAG, "Network scan complete in ${result.scanDurationMs}ms")
        result
    }

    /**
     * Send UDP packets to all /24 hosts to trigger ARP resolution,
     * then wait briefly for responses to populate the ARP cache.
     */
    private suspend fun stimulateArp(gatewayIp: String) {
        val parts = gatewayIp.split(".")
        if (parts.size != 4) return
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"

        withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 100
                val data = ByteArray(1) { 0 }
                for (i in 1..254) {
                    try {
                        val addr = InetAddress.getByName("$prefix.$i")
                        socket.send(DatagramPacket(data, data.size, addr, 9)) // port 9 = discard
                    } catch (e: Exception) { /* ignore individual failures */ }
                }
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "ARP stimulation failed: ${e.message}")
            }
        }

        // Wait for ARP responses to arrive
        delay(2000)
    }

    /**
     * Generate target ArpEntry list from gateway's /24 subnet as last resort.
     */
    private fun generateSubnetTargets(gatewayIp: String, ownIp: String): List<ArpEntry> {
        val parts = gatewayIp.split(".")
        if (parts.size != 4) return emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        return (1..254)
            .map { "$prefix.$it" }
            .filter { it != ownIp }
            .map { ArpEntry(it, "", "") }
    }
}
