package com.repository.listener.scanner

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads the system ARP table from /proc/net/arp (no root needed).
 * Provides quick LAN host + MAC address discovery.
 */
class ArpTableReader {

    companion object {
        private const val TAG = "ArpTableReader"
        private const val ARP_FILE = "/proc/net/arp"
    }

    /**
     * Read and parse the ARP table.
     * @return List of ARP entries (IP, MAC, interface)
     */
    suspend fun read(): List<ArpEntry> = withContext(Dispatchers.IO) {
        try {
            val file = File(ARP_FILE)
            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "Cannot read $ARP_FILE")
                return@withContext emptyList()
            }

            val lines = file.readLines()
            if (lines.size <= 1) {
                Log.i(TAG, "ARP table is empty")
                return@withContext emptyList()
            }

            // Skip header line: IP address, HW type, Flags, HW address, Mask, Device
            val entries = lines.drop(1).mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 6) {
                    val ip = parts[0]
                    val mac = parts[3]
                    val iface = parts[5]

                    // Filter out incomplete entries (00:00:00:00:00:00)
                    if (mac != "00:00:00:00:00:00" && ip.isNotEmpty()) {
                        ArpEntry(ip, mac.uppercase(), iface)
                    } else null
                } else null
            }

            Log.i(TAG, "Read ${entries.size} ARP entries")
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ARP table: ${e.message}", e)
            emptyList()
        }
    }
}
