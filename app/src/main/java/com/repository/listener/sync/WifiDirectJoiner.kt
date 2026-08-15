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
class WifiDirectJoiner(
    private val context: Context,
    /**
     * When true (the default, used by file sync and sideload) the whole process is bound to the
     * P2P network so every socket routes over p2p0.
     *
     * Long-lived callers MUST pass false and bind their own sockets via [p2pNetwork] instead.
     * A process-wide bind lasts as long as the session, so for a call-length AR stream it would
     * also drag the orchestrator WebSocket and every other network user onto a link with no
     * internet route -- which presents as a server outage, not as a local binding decision.
     */
    private val bindProcessNetwork: Boolean = true,
) {

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
                    if (bindProcessNetwork) {
                        remoteLog?.invoke("$TAG: joined group, owner ip=$ip -- binding process network")
                        // Bind the calling process to the P2P network so HTTP GETs route over p2p0.
                        bindProcessToP2pNetwork()
                    } else {
                        // Resolve the network but leave the process default alone; the caller
                        // binds its own sockets. Pass the owner IP so the lookup can match by
                        // route rather than interface name.
                        boundNetwork = findP2pNetwork(ip)
                        remoteLog?.invoke(
                            "$TAG: joined group, owner ip=$ip -- per-socket bind mode, " +
                                "network=${if (boundNetwork != null) "resolved" else "NOT FOUND"}"
                        )
                    }
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
        // Only undo a bind we actually made. bindProcessToNetwork is process-global, so clearing
        // it unconditionally would unbind a concurrent file-sync/sideload session mid-transfer.
        if (bindProcessNetwork) {
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        }
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
     * Bind the process's default network to the P2P network so HTTP GETs in GlassesSyncClient
     * reach the group owner via the p2p link rather than cellular/primary WiFi.
     *
     * Best-effort by design: on this hardware the group reuses wlan0, so no distinct p2p Network
     * exists and this is a no-op -- file sync and sideload have always worked that way, reaching
     * the owner over the normal routing table.
     */
    private fun bindProcessToP2pNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val n = findP2pNetwork()
            if (n == null) {
                remoteLog?.invoke("$TAG: no p2p* interface found in active networks")
                return
            }
            cm.bindProcessToNetwork(n)
            boundNetwork = n
            remoteLog?.invoke("$TAG: bound process to p2p network")
        } catch (e: Exception) {
            remoteLog?.invoke("$TAG: bindProcessToP2pNetwork failed: ${e.message}")
        }
    }

    /**
     * The P2P [android.net.Network], for callers that bind individual sockets rather than the
     * whole process. Available once [onReady] has fired.
     */
    val p2pNetwork: android.net.Network?
        get() = boundNetwork ?: findP2pNetwork(pendingDetails?.ip)

    /**
     * The Network carrying the WiFi-Direct group.
     *
     * Identified by the ROUTE to the group owner, not by interface name. On this hardware the
     * group owner sits at 192.168.43.1 and the group reuses `wlan0` (the firmware runs with
     * `p2p_no_group_iface=1`), so the obvious `interfaceName.startsWith("p2p")` test matches
     * nothing and the join silently yields no usable Network. Measured on-device: the phone
     * logged "joined group, owner ip=192.168.43.1" while the name-based lookup returned null.
     *
     * @param ownerIp group owner address from WIFI_READY; when null only the name/route heuristics
     *   are available.
     */
    private fun findP2pNetwork(ownerIp: String? = null): android.net.Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val candidates = cm.allNetworks.filter { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@filter false
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }
            val target = ownerIp ?: pendingDetails?.ip

            // 1) A network whose link actually covers the group owner's subnet.
            if (target != null) {
                candidates.firstOrNull { n ->
                    val link = cm.getLinkProperties(n) ?: return@firstOrNull false
                    link.linkAddresses.any { la ->
                        sameSubnet(la.address?.hostAddress, target, la.prefixLength)
                    }
                }?.let { return it }
            }

            // 2) A genuinely separate p2p interface, where the device makes one.
            candidates.firstOrNull { (cm.getLinkProperties(it)?.interfaceName ?: "").startsWith("p2p") }
                ?: run {
                    remoteLog?.invoke("$TAG: no p2p network found (owner=$target)")
                    null
                }
        } catch (e: Exception) {
            remoteLog?.invoke("$TAG: findP2pNetwork failed: ${e.message}")
            null
        }
    }

    /** True when [a] and [b] share the first [prefixLength] bits (IPv4 only). */
    private fun sameSubnet(a: String?, b: String?, prefixLength: Int): Boolean {
        if (a == null || b == null || prefixLength !in 1..32) return false
        val ax = a.split(".").mapNotNull { it.toIntOrNull() }
        val bx = b.split(".").mapNotNull { it.toIntOrNull() }
        if (ax.size != 4 || bx.size != 4) return false
        val ai = (ax[0] shl 24) or (ax[1] shl 16) or (ax[2] shl 8) or ax[3]
        val bi = (bx[0] shl 24) or (bx[1] shl 16) or (bx[2] shl 8) or bx[3]
        val mask = if (prefixLength == 32) -1 else ((-1) shl (32 - prefixLength))
        return (ai and mask) == (bi and mask)
    }

    private fun locationServicesEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }
}
