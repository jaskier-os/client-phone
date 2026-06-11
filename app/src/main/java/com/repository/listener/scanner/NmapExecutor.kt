package com.repository.listener.scanner

import android.content.Context
import android.util.Log
import com.repository.listener.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

/**
 * Executes nmap binary bundled in assets.
 * Extracts the binary + data files on first run, then invokes via ProcessBuilder.
 * Parses XML output into structured NmapResult.
 */
class NmapExecutor(private val context: Context) {

    companion object {
        private const val TAG = "NmapExecutor"
        private const val ASSET_DIR = "nmap"
        private const val NMAP_BINARY = "nmap"
        private const val HOST_TIMEOUT = "30s"
    }

    private val nmapDir = File(context.filesDir, "nmap")
    private val nmapBin = File(nmapDir, NMAP_BINARY)

    /**
     * Extract nmap binary and data files from assets if not already present.
     */
    suspend fun extract(): Boolean = withContext(Dispatchers.IO) {
        AssetExtractor.extractAssetDir(context, ASSET_DIR, nmapDir)

        if (nmapBin.exists()) {
            nmapBin.setExecutable(true)
            Log.i(TAG, "nmap binary ready at ${nmapBin.absolutePath}")
            true
        } else {
            Log.e(TAG, "nmap binary not found after extraction at ${nmapBin.absolutePath}")
            false
        }
    }

    fun isAvailable(): Boolean = nmapBin.exists() && nmapBin.canExecute()

    /**
     * Run an nmap scan on the given targets.
     * @param targets Target specification (e.g. "192.168.1.0/24")
     * @param ports Comma-separated port list
     * @param extraArgs Additional nmap arguments
     * @return NmapResult with discovered hosts and stats
     */
    suspend fun scan(
        targets: String,
        ports: String,
        extraArgs: List<String> = emptyList()
    ): NmapResult = withContext(Dispatchers.IO) {
        if (!nmapBin.exists() || !nmapBin.canExecute()) {
            Log.e(TAG, "nmap binary not available")
            return@withContext NmapResult(emptyList(), NmapScanStats(0, 0, "0"))
        }

        val args = mutableListOf(
            nmapBin.absolutePath,
            "-sT",                          // TCP connect scan (no root needed)
            "-sV",                          // Service version detection
            "--datadir", nmapDir.absolutePath,
            "-p", ports,
            "-oX", "-",                     // XML output to stdout
            "--host-timeout", HOST_TIMEOUT,
            "-T4",                          // Aggressive timing
            "--open"                        // Only show open ports
        )
        args.addAll(extraArgs)
        args.add(targets)

        Log.i(TAG, "Running nmap: ${args.joinToString(" ")}")

        try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()

            val exitCode = process.waitFor()
            Log.i(TAG, "nmap exit code: $exitCode, stdout: ${stdout.length} chars, stderr: ${stderr.length} chars")

            if (stderr.isNotBlank()) {
                Log.w(TAG, "nmap stderr: ${stderr.take(500)}")
            }

            if (stdout.contains("<?xml")) {
                parseNmapXml(stdout)
            } else {
                Log.e(TAG, "nmap output does not contain XML: ${stdout.take(200)}")
                NmapResult(emptyList(), NmapScanStats(0, 0, "0"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "nmap execution failed: ${e.message}", e)
            NmapResult(emptyList(), NmapScanStats(0, 0, "0"))
        }
    }

    /**
     * Parse nmap XML output into NmapResult.
     */
    private fun parseNmapXml(xml: String): NmapResult {
        val hosts = mutableListOf<NmapHost>()
        var hostsUp = 0
        var hostsTotal = 0
        var elapsed = "0"

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var currentIp = ""
            var currentMac = ""
            var currentMacVendor = ""
            var currentState = ""
            var currentPorts = mutableListOf<NmapPort>()
            var inHost = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "host" -> {
                                inHost = true
                                currentIp = ""
                                currentMac = ""
                                currentMacVendor = ""
                                currentState = ""
                                currentPorts = mutableListOf()
                            }
                            "status" -> {
                                if (inHost) {
                                    currentState = parser.getAttributeValue(null, "state") ?: ""
                                }
                            }
                            "address" -> {
                                if (inHost) {
                                    val addrType = parser.getAttributeValue(null, "addrtype") ?: ""
                                    val addr = parser.getAttributeValue(null, "addr") ?: ""
                                    when (addrType) {
                                        "ipv4" -> currentIp = addr
                                        "mac" -> {
                                            currentMac = addr
                                            currentMacVendor = parser.getAttributeValue(null, "vendor") ?: ""
                                        }
                                    }
                                }
                            }
                            "port" -> {
                                if (inHost) {
                                    val portId = parser.getAttributeValue(null, "portid")?.toIntOrNull() ?: 0
                                    // Read child elements for state and service
                                    var portState = ""
                                    var service = ""
                                    var version = ""
                                    var product = ""

                                    // Parse inner elements until </port>
                                    var depth = 1
                                    while (depth > 0) {
                                        eventType = parser.next()
                                        when (eventType) {
                                            XmlPullParser.START_TAG -> {
                                                depth++
                                                when (parser.name) {
                                                    "state" -> portState = parser.getAttributeValue(null, "state") ?: ""
                                                    "service" -> {
                                                        service = parser.getAttributeValue(null, "name") ?: ""
                                                        product = parser.getAttributeValue(null, "product") ?: ""
                                                        version = parser.getAttributeValue(null, "version") ?: ""
                                                    }
                                                }
                                            }
                                            XmlPullParser.END_TAG -> depth--
                                        }
                                    }

                                    currentPorts.add(NmapPort(portId, portState, service, version, product))
                                }
                            }
                            "runstats" -> {
                                // Parse scan stats
                            }
                            "finished" -> {
                                elapsed = parser.getAttributeValue(null, "elapsed") ?: "0"
                            }
                            "hosts" -> {
                                hostsUp = parser.getAttributeValue(null, "up")?.toIntOrNull() ?: 0
                                hostsTotal = parser.getAttributeValue(null, "total")?.toIntOrNull() ?: 0
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "host" && inHost) {
                            if (currentIp.isNotEmpty() && currentState == "up") {
                                hosts.add(NmapHost(currentIp, currentMac, currentMacVendor, currentState, currentPorts))
                            }
                            inHost = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse nmap XML: ${e.message}", e)
        }

        Log.i(TAG, "Parsed ${hosts.size} hosts from nmap XML")
        return NmapResult(hosts, NmapScanStats(hostsUp, hostsTotal, elapsed))
    }
}
