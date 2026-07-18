package com.repository.listener.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.repository.listener.util.LogCollector
import java.net.InetAddress

/**
 * Discovers the desktop relay's LAN-direct audio-relay server over mDNS
 * (`_repo-relay._tcp`) so the phone can connect its audio signaling straight to
 * the PC instead of hairpinning through the cloud orchestrator.
 *
 * Discovery runs continuously while started; the latest resolved host:port is
 * cached. `endpoint()` is a cheap non-blocking read of that cache. Resolution
 * is best-effort: if mDNS is blocked or the PC is not present, the cache stays
 * null and callers fall back to the cloud path.
 */
class DesktopRelayLocator(context: Context) {

    companion object {
        private const val TAG = "DesktopRelayLocator"
        // Must match the desktop advertiser. RFC 6763 caps the service label at
        // 15 bytes, so it is abbreviated rather than "repository-relay".
        private const val SERVICE_TYPE = "_repo-relay._tcp."
    }

    data class Endpoint(val host: String, val port: Int, val resolvedAt: Long)

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager

    // The last resolved endpoint is kept "sticky": we do NOT drop it on a spurious
    // NsdManager onServiceLost (those fire often even while the PC is reachable)
    // nor on a time-based staleness timer. The real reachability check is the
    // actual TCP connect in the audio-relay path, which has a short timeout and
    // transparently falls back to the cloud if the PC is gone. mDNS only provides
    // the address hint; the connection attempt validates it.
    @Volatile
    private var endpoint: Endpoint? = null

    @Volatile
    private var started = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Latest resolved endpoint hint, or null if the PC has never been seen. Cheap, non-blocking. */
    fun endpoint(): Endpoint? = endpoint

    @Synchronized
    fun start() {
        if (started || nsdManager == null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                LogCollector.i(TAG, "Desktop relay discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.').endsWith(SERVICE_TYPE.trimEnd('.'))) {
                    resolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // NsdManager fires this spuriously even while the PC is still up,
                // so we keep the cached endpoint (the TCP connect validates it)
                // and re-resolve to refresh the address if the service is really
                // back with a new IP.
                LogCollector.i(TAG, "Desktop relay lost event (keeping cached endpoint): ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LogCollector.w(TAG, "Desktop relay discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            started = true
        } catch (e: Exception) {
            LogCollector.w(TAG, "discoverServices failed: ${e.message}")
            discoveryListener = null
        }
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        val mgr = nsdManager ?: return
        try {
            mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    LogCollector.w(TAG, "Desktop relay resolve failed: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = hostAddress(info) ?: return
                    val port = info.port
                    if (port <= 0) return
                    endpoint = Endpoint(host, port, System.currentTimeMillis())
                    LogCollector.i(TAG, "Desktop relay resolved: $host:$port")
                }
            })
        } catch (e: Exception) {
            LogCollector.w(TAG, "resolveService failed: ${e.message}")
        }
    }

    private fun hostAddress(info: NsdServiceInfo): String? {
        // Collect all advertised IPv4 addresses (API 34+ exposes the full list;
        // older APIs expose a single resolved host).
        val candidates = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 34) {
            for (a in info.hostAddresses) {
                if (a is java.net.Inet4Address) a.hostAddress?.let { candidates.add(it) }
            }
        }
        if (candidates.isEmpty()) {
            @Suppress("DEPRECATION")
            (info.host as? java.net.Inet4Address)?.hostAddress?.let { candidates.add(it) }
            @Suppress("DEPRECATION")
            if (candidates.isEmpty()) info.host?.hostAddress?.let { candidates.add(it) }
        }
        if (candidates.isEmpty()) return null
        // Prefer an address on the phone's own WiFi subnet (/24) so we never pick
        // a secondary private range the PC exposes that the phone can't route to.
        val myIp = wifiIpv4()
        if (myIp != null) {
            val myPrefix = myIp.substringBeforeLast('.', "")
            candidates.firstOrNull { it.substringBeforeLast('.', "") == myPrefix }?.let { return it }
        }
        return candidates.first()
    }

    /** The phone's current WiFi IPv4 address, or null. Used to prefer a same-subnet PC address. */
    private fun wifiIpv4(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.name.startsWith("wlan") && it.isUp }
                ?.inetAddresses?.toList()
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun stop() {
        val mgr = nsdManager
        val listener = discoveryListener
        if (mgr != null && listener != null) {
            try {
                mgr.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
        endpoint = null
        started = false
    }
}
