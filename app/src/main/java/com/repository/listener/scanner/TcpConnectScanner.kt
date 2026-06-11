package com.repository.listener.scanner

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Pure Kotlin TCP connect scanner fallback when nmap binary is unavailable.
 * Two-phase approach: quick host discovery on common ports, then full port scan on live hosts.
 * Uses coroutine Semaphore to limit concurrent connections.
 */
class TcpConnectScanner {

    companion object {
        private const val TAG = "TcpConnectScanner"
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val DISCOVERY_TIMEOUT_MS = 800
        private const val MAX_CONCURRENT = 200

        // Ports most likely to be open on any device (used for host discovery)
        private val DISCOVERY_PORTS = listOf(80, 443, 22, 53, 8080, 554, 445, 139, 1900, 5353)
    }

    /**
     * Scan the given hosts on the specified ports using TCP connect.
     * If targets have no MAC (generated from subnet), runs a fast host discovery first.
     * @param targets ARP table entries with IP and MAC addresses
     * @param ports Comma-separated port list
     * @return NmapResult with discovered hosts (no service/version info)
     */
    suspend fun scan(
        targets: List<ArpEntry>,
        ports: String
    ): NmapResult = withContext(Dispatchers.IO) {
        if (targets.isEmpty()) {
            Log.i(TAG, "No targets to scan")
            return@withContext NmapResult(emptyList(), NmapScanStats(0, 0, "0"))
        }

        val portList = ports.split(",").mapNotNull { it.trim().toIntOrNull() }
        val startTime = System.currentTimeMillis()

        // If targets are generated (no MAC = subnet brute-force), do host discovery first
        val hasArpData = targets.any { it.mac.isNotEmpty() }
        val liveTargets = if (!hasArpData && targets.size > 20) {
            Log.i(TAG, "Running host discovery on ${targets.size} subnet IPs")
            discoverLiveHosts(targets)
        } else {
            targets
        }

        if (liveTargets.isEmpty()) {
            Log.i(TAG, "No live hosts found during discovery")
            val elapsed = "%.1f".format((System.currentTimeMillis() - startTime) / 1000.0)
            return@withContext NmapResult(emptyList(), NmapScanStats(0, targets.size, elapsed))
        }

        Log.i(TAG, "TCP connect scan: ${liveTargets.size} hosts, ${portList.size} ports")

        val semaphore = Semaphore(MAX_CONCURRENT)

        val hosts = coroutineScope {
            liveTargets.map { entry ->
                async {
                    val openPorts = portList.map { port ->
                        async {
                            semaphore.withPermit {
                                if (isPortOpen(entry.ip, port, CONNECT_TIMEOUT_MS)) port else null
                            }
                        }
                    }.awaitAll().filterNotNull()

                    if (openPorts.isNotEmpty()) {
                        NmapHost(
                            ip = entry.ip,
                            mac = entry.mac,
                            macVendor = "",
                            state = "up",
                            ports = openPorts.map { port ->
                                NmapPort(port, "open", "", "", "")
                            }
                        )
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val elapsed = "%.1f".format((System.currentTimeMillis() - startTime) / 1000.0)
        Log.i(TAG, "TCP connect scan complete: ${hosts.size} hosts with open ports in ${elapsed}s")
        NmapResult(hosts, NmapScanStats(hosts.size, targets.size, elapsed))
    }

    /**
     * Quick host discovery: probe a few common ports on each IP with short timeout.
     * Returns only IPs that responded on at least one port.
     */
    private suspend fun discoverLiveHosts(targets: List<ArpEntry>): List<ArpEntry> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT)
        val startTime = System.currentTimeMillis()

        val liveHosts = targets.map { entry ->
            async {
                val isLive = DISCOVERY_PORTS.map { port ->
                    async {
                        semaphore.withPermit {
                            isPortOpen(entry.ip, port, DISCOVERY_TIMEOUT_MS)
                        }
                    }
                }.awaitAll().any { it }

                if (isLive) entry else null
            }
        }.awaitAll().filterNotNull()

        val elapsed = "%.1f".format((System.currentTimeMillis() - startTime) / 1000.0)
        Log.i(TAG, "Host discovery: ${liveHosts.size}/${targets.size} live hosts found in ${elapsed}s")
        liveHosts
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
