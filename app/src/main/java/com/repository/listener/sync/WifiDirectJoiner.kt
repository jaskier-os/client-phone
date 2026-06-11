package com.repository.listener.sync

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper

/**
 * Phone-side WiFi Direct client. Joins the glasses' P2P group via
 * [WifiP2pManager.connect] with a pre-baked [WifiP2pConfig] (API 29+) so the OS does NOT
 * show a "Connect?" pairing dialog -- the SSID + passphrase are already known from
 * glasses' WIFI_READY frame. Binds the calling process's network to the joined group
 * so HTTP requests route over it.
 */
@SuppressLint("MissingPermission")
class WifiDirectJoiner(private val context: Context) {

    companion object { private const val TAG = "WifiDirectJoiner" }

    data class GroupDetails(
        val ssid: String,
        val passphrase: String,
        val ip: String,
        val port: Int,
        val deviceAddress: String?,
    )

    var remoteLog: ((String) -> Unit)? = null
    var onReady: ((String) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    private val p2p: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private val channel by lazy { p2p.initialize(context, Looper.getMainLooper(), null) }
    private val cm by lazy { context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager }

    @Volatile private var active = false
    @Volatile private var pendingDetails: GroupDetails? = null
    @Volatile private var receiverRegistered = false
    @Volatile private var boundNetwork: android.net.Network? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            if (!active) return
            p2p.requestConnectionInfo(channel) { info ->
                if (info != null && info.groupFormed && !info.isGroupOwner) {
                    val ip = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                    remoteLog?.invoke("$TAG: joined group, owner ip=$ip -- binding process network")
                    // Bind the calling process to the P2P network so HTTP GETs route over p2p0.
                    bindProcessToP2pNetwork()
                    onReady?.invoke(ip)
                }
            }
        }
    }

    fun join(details: GroupDetails) {
        if (active) {
            remoteLog?.invoke("$TAG: already active, ignoring join")
            return
        }
        if (!locationServicesEnabled()) {
            onFailed?.invoke("Location Services must be ON for WiFi P2P (Android 10+)")
            return
        }
        active = true
        pendingDetails = details

        // Register CONNECTION_CHANGED receiver BEFORE calling connect (race protection).
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true

        connectP2p(details)
    }

    fun close() {
        if (!active) return
        active = false
        pendingDetails = null
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        boundNetwork = null
        try { p2p.cancelConnect(channel, null) } catch (_: Exception) {}
        try {
            p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { remoteLog?.invoke("$TAG: removeGroup ok") }
                override fun onFailure(reason: Int) { remoteLog?.invoke("$TAG: removeGroup reason=$reason") }
            })
        } catch (_: Exception) {}
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        onClosed?.invoke()
    }

    // ----- internals -----

    /**
     * Pre-baked [WifiP2pConfig] with SSID+passphrase. Per AOSP docs, calling [p2p.connect]
     * with a config whose networkName and passphrase are set does NOT show a pairing
     * dialog -- the OS silently joins because both sides already know the creds.
     */
    private fun connectP2p(details: GroupDetails) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Q has no Builder path. Fall back to discover+connect (may prompt on join).
            legacyDiscoverAndConnect(details)
            return
        }
        val configBuilder = WifiP2pConfig.Builder()
            .setNetworkName(details.ssid)
            .setPassphrase(details.passphrase)
            .enablePersistentMode(false)
        // If we know the GO's device address, pin it -- faster and avoids any ambiguity.
        details.deviceAddress?.takeIf { it.isNotBlank() }?.let { addr ->
            try {
                val mac = android.net.MacAddress.fromString(addr)
                configBuilder.setDeviceAddress(mac)
            } catch (e: Exception) {
                remoteLog?.invoke("$TAG: bad deviceAddress '$addr': ${e.message}")
            }
        }
        val config = configBuilder.build()
        p2p.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { remoteLog?.invoke("$TAG: connect(config) accepted") }
            override fun onFailure(reason: Int) {
                val name = when (reason) { 0 -> "ERROR"; 1 -> "P2P_UNSUPPORTED"; 2 -> "BUSY"; else -> "code=$reason" }
                remoteLog?.invoke("$TAG: connect(config) failed ($name) -- retrying via discover")
                legacyDiscoverAndConnect(details)
            }
        })
    }

    private fun legacyDiscoverAndConnect(details: GroupDetails) {
        val addr = details.deviceAddress
        if (addr.isNullOrEmpty()) {
            onFailed?.invoke("no deviceAddress, cannot legacy-discover")
            return
        }
        p2p.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                @Suppress("DEPRECATION")
                val cfg = WifiP2pConfig().apply { deviceAddress = addr }
                p2p.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { remoteLog?.invoke("$TAG: legacy connect started") }
                    override fun onFailure(reason: Int) { onFailed?.invoke("legacy connect failed $reason") }
                })
            }
            override fun onFailure(reason: Int) { onFailed?.invoke("discoverPeers failed $reason") }
        })
    }

    /**
     * Bind the process's default network to the P2P network so HTTP GETs in
     * GlassesSyncClient reach 192.168.49.1 via p2p0 rather than cellular/primary WiFi.
     * We enumerate active networks, find the one with TRANSPORT_WIFI + p2p interface name.
     */
    private fun bindProcessToP2pNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val nets = cm.allNetworks
            for (n in nets) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val link = cm.getLinkProperties(n) ?: continue
                val iface = link.interfaceName ?: ""
                val isWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                if (isWifi && iface.startsWith("p2p")) {
                    cm.bindProcessToNetwork(n)
                    boundNetwork = n
                    remoteLog?.invoke("$TAG: bound process to network iface=$iface")
                    return
                }
            }
            remoteLog?.invoke("$TAG: no p2p* interface found in active networks")
        } catch (e: Exception) {
            remoteLog?.invoke("$TAG: bindProcessToP2pNetwork failed: ${e.message}")
        }
    }

    private fun locationServicesEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }
}
