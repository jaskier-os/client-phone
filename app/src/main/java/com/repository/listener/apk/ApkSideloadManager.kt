package com.repository.listener.apk

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.repository.listener.bt.BluetoothHelper
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.controllers.WifiController
import com.rokid.cxr.client.utils.ValueUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ApkSideloadManager(
    private val context: Context,
    private val getConnectionState: () -> BluetoothHelper.ConnectionState,
    private val log: (String) -> Unit,
    private val statusCallback: (running: Boolean, url: String?, lastUpload: String?, installStatus: String?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var p2pRetryCount = 0
    private var p2pConnected = false
    private var p2pFlowCompleted = false
    private var p2pTimeoutRunnable: Runnable? = null

    @Volatile var p2pPhoneIp: String? = null
        private set

    private var lastDeployedPackage: String? = null
    private var lastDeployedActivity: String? = null
    @Volatile var p2pGlassesIp: String? = null
        private set

    private var p2pBeaconThread: Thread? = null
    @Volatile private var p2pBeaconRunning = false

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile var uploading = false
        private set
    @Volatile private var lastUploadInfo: String? = null
    @Volatile private var lastInstallStatus: String? = null
    private val pendingInstallComplete = AtomicReference<((Boolean, String) -> Unit)?>(null)

    private fun completeInstall(success: Boolean, message: String) {
        pendingInstallComplete.getAndSet(null)?.invoke(success, message)
    }

    fun uploadApkFile(file: File, onComplete: ((Boolean, String) -> Unit)? = null) {
        uploading = true
        pendingInstallComplete.set(onComplete)

        // Extract package info for post-install actions
        try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageArchiveInfo(file.absolutePath, 0)
            if (pkgInfo != null) {
                lastDeployedPackage = pkgInfo.packageName
                val appInfo = pkgInfo.applicationInfo
                appInfo?.sourceDir = file.absolutePath
                appInfo?.publicSourceDir = file.absolutePath
                val launchIntent = pm.getLaunchIntentForPackage(pkgInfo.packageName)
                lastDeployedActivity = launchIntent?.component?.className
                    ?: "${pkgInfo.packageName}.MainActivity"
                log("[Sideload] Extracted package: $lastDeployedPackage, activity: $lastDeployedActivity")
            }
        } catch (e: Exception) {
            log("[Sideload] Failed to extract package info: ${e.message}")
        }

        val existingGlassesIp = p2pGlassesIp
        if (existingGlassesIp != null) {
            log("[Sideload] Reusing existing P2P connection (glasses IP: $existingGlassesIp)")
            uploadApkHttp(file, existingGlassesIp)
            return
        }
        startP2pFlow { glassesIp -> uploadApkHttp(file, glassesIp) }
    }

    @SuppressLint("MissingPermission")
    private fun startP2pFlow(onConnected: (glassesIp: String) -> Unit) {
        if (getConnectionState() != BluetoothHelper.ConnectionState.CONNECTED) {
            log("[Sideload] Aborted: BT not connected")
            uploading = false
            completeInstall(false, "BT not connected")
            return
        }

        // WiFi P2P requires Location Services on Android 10+
        val locationMode = try {
            android.provider.Settings.Secure.getInt(context.contentResolver, android.provider.Settings.Secure.LOCATION_MODE)
        } catch (_: Exception) { 0 }
        if (locationMode == 0) {
            log("[Sideload] Location Services OFF -- enabling programmatically for P2P")
            try {
                // Use Settings.Secure to enable location (requires WRITE_SECURE_SETTINGS granted via ADB)
                android.provider.Settings.Secure.putInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                )
                log("[Sideload] Location Services enabled")
            } catch (e: Exception) {
                log("[Sideload] Failed to enable Location Services: ${e.message}")
                log("[Sideload] Grant permission: adb shell pm grant com.repository.listener android.permission.WRITE_SECURE_SETTINGS")
                uploading = false
                completeInstall(false, "Location Services unavailable")
                return
            }
        }
        p2pFlowCompleted = false
        p2pRetryCount = 0
        p2pConnected = false
        p2pTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        log("[Sideload] === Phase 1: P2P cleanup ===")

        cleanupP2p()

        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            log("[Sideload] WifiP2pManager not available")
            uploading = false
            completeInstall(false, "WifiP2pManager not available")
            return
        }
        val ch = mgr.initialize(context, Looper.getMainLooper(), null)
        if (ch == null) {
            log("[Sideload] WifiP2pManager.Channel is null")
            uploading = false
            completeInstall(false, "P2P channel is null")
            return
        }
        p2pManager = mgr
        p2pChannel = ch

        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("[Sideload] stopPeerDiscovery OK") }
            override fun onFailure(r: Int) { log("[Sideload] stopPeerDiscovery fail=$r") }
        })
        mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("[Sideload] cancelConnect OK") }
            override fun onFailure(r: Int) { log("[Sideload] cancelConnect fail=$r") }
        })
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("[Sideload] removeGroup OK") }
            override fun onFailure(r: Int) { log("[Sideload] removeGroup fail=$r") }
        })

        // Delete persistent groups
        try {
            val delMethod = WifiP2pManager::class.java.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java
            )
            for (netId in 0..31) {
                delMethod.invoke(mgr, ch, netId, null)
            }
            log("[Sideload] deletePersistentGroup 0..31 done")
        } catch (e: Exception) {
            log("[Sideload] deletePersistentGroup: ${e.message}")
        }

        mainHandler.postDelayed({
            sendSyncStartAndDoOwnP2p(onConnected)
        }, 3000)
    }

    @SuppressLint("MissingPermission")
    private fun sendSyncStartAndDoOwnP2p(onConnected: (glassesIp: String) -> Unit) {
        log("[Sideload] === Phase 2: Sync_Start + neuter WifiController ===")

        try {
            CxrApi.getInstance().initWifiP2P(object : WifiP2PStatusCallback {
                override fun onConnected() {
                    log("[Sideload] (SDK WifiController connected - ignoring)")
                }
                override fun onDisconnected() {
                    log("[Sideload] (SDK WifiController disconnected - ignoring)")
                }
                override fun onFailed(error: ValueUtil.CxrWifiErrorCode?) {
                    log("[Sideload] (SDK WifiController failed: $error - ignoring)")
                }
                override fun onP2pDeviceAvailable(p0: String?, p1: String?, p2: String?) {
                    log("[Sideload] (SDK P2P device available: $p0 $p1 $p2 - ignoring)")
                }
            })
            log("[Sideload] initWifiP2P called (Sync_Start sent)")
        } catch (e: Exception) {
            log("[Sideload] initWifiP2P failed: ${e.message}")
        }

        mainHandler.postDelayed({
            if (getConnectionState() != BluetoothHelper.ConnectionState.CONNECTED) {
                log("[Sideload] Aborted phase 2: BT disconnected")
                cleanupP2p()
                uploading = false
                completeInstall(false, "BT disconnected during P2P setup")
                return@postDelayed
            }
            neuterSdkWifiController()
            mainHandler.postDelayed({ neuterSdkWifiController() }, 2000)

            log("[Sideload] Waiting 3s for glasses P2P group setup...")
            mainHandler.postDelayed({
                if (getConnectionState() != BluetoothHelper.ConnectionState.CONNECTED) {
                    log("[Sideload] Aborted phase 3: BT disconnected")
                    cleanupP2p()
                    uploading = false
                    completeInstall(false, "BT disconnected during P2P discovery")
                    return@postDelayed
                }
                startOwnP2pDiscovery(onConnected)
            }, 3000)
        }, 4000)
    }

    private fun neuterSdkWifiController() {
        log("[Sideload] Neutering SDK WifiController...")
        try {
            val wc = WifiController.getInstance()
            val wcClass = wc.javaClass

            // Field layout from decompiled WifiController (CXR-M SDK):
            //   d=Context, j=Handler, k=WifiP2pManager, l=Channel,
            //   f=Callback, q=timeout Runnable, t=BroadcastReceiver,
            //   o=isConnecting(bool), p=hasInitiatedConnection(bool)

            // 1. Unregister BroadcastReceiver (field t) using Context (field d)
            try {
                val tField = wcClass.getDeclaredField("t")
                tField.isAccessible = true
                val receiver = tField.get(wc) as? BroadcastReceiver
                if (receiver != null) {
                    val dField = wcClass.getDeclaredField("d")
                    dField.isAccessible = true
                    val ctx = dField.get(wc) as? Context
                    ctx?.unregisterReceiver(receiver)
                    log("[Sideload] Unregistered SDK BroadcastReceiver")
                }
            } catch (e: Exception) {
                log("[Sideload] Unregister receiver: ${e.message}")
            }

            // 2. Cancel timeout runnable (field q) via handler (field j)
            try {
                val jField = wcClass.getDeclaredField("j")
                jField.isAccessible = true
                val handler = jField.get(wc) as? Handler
                val qField = wcClass.getDeclaredField("q")
                qField.isAccessible = true
                val runnable = qField.get(wc) as? Runnable
                if (handler != null && runnable != null) {
                    handler.removeCallbacks(runnable)
                }
                handler?.removeCallbacksAndMessages(null)
            } catch (e: Exception) {
                log("[Sideload] Cancel timeout: ${e.message}")
            }

            // 3. Null out callback (field f)
            try {
                val fField = wcClass.getDeclaredField("f")
                fField.isAccessible = true
                fField.set(wc, null)
            } catch (e: Exception) {
                log("[Sideload] Null callback: ${e.message}")
            }

            // 4. Null out WifiP2pManager (field k) and Channel (field l)
            try {
                val kField = wcClass.getDeclaredField("k")
                kField.isAccessible = true
                kField.set(wc, null)
                val lField = wcClass.getDeclaredField("l")
                lField.isAccessible = true
                lField.set(wc, null)
            } catch (e: Exception) {
                log("[Sideload] Null manager/channel: ${e.message}")
            }

            // 5. Set flags to prevent re-entry: o=isConnecting, p=hasInitiatedConnection
            try {
                val oField = wcClass.getDeclaredField("o")
                oField.isAccessible = true
                oField.setBoolean(wc, true)
                val pField = wcClass.getDeclaredField("p")
                pField.isAccessible = true
                pField.setBoolean(wc, true)
            } catch (e: Exception) {
                log("[Sideload] Set flags: ${e.message}")
            }

            log("[Sideload] SDK WifiController neutered")
        } catch (e: Exception) {
            log("[Sideload] Neuter failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOwnP2pDiscovery(onConnected: (glassesIp: String) -> Unit) {
        log("[Sideload] === Phase 3: Own P2P discovery ===")

        val mgr = p2pManager
        val ch = p2pChannel
        if (mgr == null || ch == null) {
            log("[Sideload] P2P manager/channel is null")
            uploading = false
            completeInstall(false, "P2P manager/channel is null")
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        log("[Sideload] Peers changed, requesting peer list...")
                        mgr.requestPeers(ch) { peerList ->
                            val peers = peerList?.deviceList ?: emptyList()
                            log("[Sideload] Found ${peers.size} peer(s)")
                            for (peer in peers) {
                                log("[Sideload]   ${peer.deviceName} (${peer.deviceAddress}) status=${peer.status}")
                            }
                            val target = peers.firstOrNull {
                                it.status == WifiP2pDevice.AVAILABLE &&
                                it.deviceName.contains("Glasses", ignoreCase = true)
                            }
                            if (target != null && !p2pConnected) {
                                p2pConnected = true
                                log("[Sideload] Connecting to ${target.deviceName}")
                                connectToP2pDevice(target, onConnected)
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                        )
                        log("[Sideload] Connection changed: connected=${networkInfo?.isConnected}")
                        if (networkInfo?.isConnected == true) {
                            mgr.requestConnectionInfo(ch) { info ->
                                if (info?.groupFormed == true) {
                                    val ip = info.groupOwnerAddress?.hostAddress
                                    log("[Sideload] Group formed! GroupOwner IP: $ip")
                                    if (ip != null) {
                                        p2pFlowCompleted = true
                                        p2pTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                                        try { mgr.stopPeerDiscovery(ch, null) } catch (_: Exception) {}
                                        onConnected(ip)
                                    }
                                }
                            }
                        } else if (p2pGlassesIp != null) {
                            log("[Sideload] P2P connection lost, clearing")
                            p2pGlassesIp = null
                            p2pPhoneIp = null
                            stopP2pBeacon()
                            p2pConnected = false
                        }
                    }
                }
            }
        }
        p2pReceiver = receiver

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)

        mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("[Sideload] cancelConnect OK (cleared stale)") }
            override fun onFailure(r: Int) { log("[Sideload] cancelConnect fail=$r") }
        })

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("[Sideload] discoverPeers started") }
            override fun onFailure(reason: Int) {
                log("[Sideload] discoverPeers FAILED: reason=$reason")
            }
        })

        // Timeout: 30s, retry up to 3 times
        val timeoutRunnable = Runnable {
            if (p2pFlowCompleted) return@Runnable
            if (!p2pConnected) {
                log("[Sideload] Discovery timeout after 30s")
                p2pRetryCount++
                if (p2pRetryCount < 3) {
                    log("[Sideload] Retrying P2P (attempt ${p2pRetryCount + 1}/3)...")
                    cleanupP2p()
                    mainHandler.postDelayed({
                        startP2pFlow(onConnected)
                    }, 2000)
                } else {
                    p2pRetryCount = 0
                    log("[Sideload] P2P failed after 3 attempts")
                    uploading = false
                    completeInstall(false, "P2P connection failed after 3 attempts")
                }
            }
        }
        p2pTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 30000)
    }

    @SuppressLint("MissingPermission")
    private fun connectToP2pDevice(device: WifiP2pDevice, onConnected: (glassesIp: String) -> Unit) {
        val mgr = p2pManager
        val ch = p2pChannel
        if (mgr == null || ch == null) return

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = android.net.wifi.WpsInfo.PBC
            groupOwnerIntent = 0
        }

        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("[Sideload] connect() initiated, waiting for CONNECTION_CHANGED")
            }
            override fun onFailure(reason: Int) {
                log("[Sideload] connect() FAILED: reason=$reason")
                p2pConnected = false
            }
        })
    }

    private fun uploadApkHttp(apkFile: File, glassesIp: String) {
        log("[Sideload] === Uploading APK via HTTP to $glassesIp:8848 ===")
        acquireWakeLock()
        registerInstallCallback()

        Thread {
            try {
                val p2pIp = getP2pInterfaceIp()
                log("[Sideload] P2P interface IP: $p2pIp")

                val clientBuilder = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.MINUTES)
                    .readTimeout(30, TimeUnit.SECONDS)

                // Bind to P2P interface so traffic doesn't route through cellular
                if (p2pIp != null) {
                    val localAddr = InetAddress.getByName(p2pIp)
                    clientBuilder.socketFactory(object : javax.net.SocketFactory() {
                        override fun createSocket(): java.net.Socket {
                            return java.net.Socket().apply { bind(java.net.InetSocketAddress(localAddr, 0)) }
                        }
                        override fun createSocket(host: String, port: Int): java.net.Socket {
                            return java.net.Socket(host, port, localAddr, 0)
                        }
                        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): java.net.Socket {
                            return java.net.Socket(host, port, localAddr, 0)
                        }
                        override fun createSocket(host: InetAddress, port: Int): java.net.Socket {
                            return java.net.Socket(host, port, localAddr, 0)
                        }
                        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): java.net.Socket {
                            return java.net.Socket(host, port, localAddr, 0)
                        }
                    })
                }

                val client = clientBuilder.build()
                val baseUrl = "http://$glassesIp:8848"

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "upfile",
                        apkFile.name,
                        apkFile.asRequestBody("application/vnd.android.package-archive".toMediaType())
                    )
                    .build()

                val url = "$baseUrl/server/upload"
                log("[Sideload] POST $url (${apkFile.length()} bytes)")

                val request = Request.Builder()
                    .url(url)
                    .addHeader("appVersion", "1.0")
                    .addHeader("apiVersion", "1.0")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                log("[Sideload] Upload response: ${response.code} - $responseBody")

                mainHandler.post {
                    uploading = false
                    if (response.isSuccessful) {
                        log("[Sideload] APK upload SUCCESS")
                        p2pFlowCompleted = true
                        p2pGlassesIp = glassesIp
                        val phoneIp = getP2pInterfaceIp()
                        p2pPhoneIp = phoneIp
                        if (phoneIp != null) {
                            startP2pBeacon(phoneIp, glassesIp)
                        }
                        val size = apkFile.length()
                        val sizeStr = when {
                            size < 1024 -> "$size B"
                            size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
                            else -> "%.1f MB".format(size / (1024.0 * 1024.0))
                        }
                        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                        lastUploadInfo = "Last upload: $sizeStr at $ts"
                        statusCallback(true, null, lastUploadInfo, "Waiting for install...")
                    } else {
                        log("[Sideload] APK upload FAILED: HTTP ${response.code}")
                        lastUploadInfo = "Upload failed: HTTP ${response.code}"
                        statusCallback(true, null, lastUploadInfo, null)
                        completeInstall(false, "Upload failed: HTTP ${response.code}")
                    }
                    releaseWakeLock()
                }
            } catch (e: Exception) {
                log("[Sideload] Upload exception: ${e.message}")
                mainHandler.post {
                    uploading = false
                    lastUploadInfo = "Upload error: ${e.message}"
                    statusCallback(true, null, lastUploadInfo, null)
                    completeInstall(false, "Upload error: ${e.message}")
                    releaseWakeLock()
                }
            }
        }.start()
    }

    private fun registerInstallCallback() {
        try {
            val cxrApi = CxrApi.getInstance()
            val cField = cxrApi.javaClass.getDeclaredField("c")
            cField.isAccessible = true
            cField.set(cxrApi, object : ApkStatusCallback {
                override fun onUploadApkSucceed() {
                    log("[Sideload] onUploadApkSucceed")
                }
                override fun onUploadApkFailed() {
                    log("[Sideload] onUploadApkFailed")
                }
                override fun onInstallApkSucceed() {
                    log("[Sideload] APK install SUCCESS on glasses")
                    lastInstallStatus = "Installed successfully"
                    completeInstall(true, "Installed successfully")
                    mainHandler.post {
                        statusCallback(true, null, lastUploadInfo, lastInstallStatus)
                    }

                    // Post-install actions
                    val openAfter = com.repository.listener.config.AppConfig.getOpenAfterInstall(context)
                    val openCloseAfter = com.repository.listener.config.AppConfig.getOpenCloseAfterInstall(context)
                    log("[Sideload] Post-install check: openAfter=$openAfter openCloseAfter=$openCloseAfter pkg=$lastDeployedPackage act=$lastDeployedActivity phoneIp=$p2pPhoneIp")

                    if (openAfter || openCloseAfter) {
                        val pkg = lastDeployedPackage
                        val act = lastDeployedActivity
                        log("[Sideload] Post-install: pkg=$pkg act=$act")
                        if (pkg != null && act != null) {
                            try {
                                val appInfo = com.rokid.cxr.client.extend.infos.RKAppInfo(pkg, act)
                                CxrApi.getInstance().openApp(appInfo, object : ApkStatusCallback {
                                    override fun onOpenAppSucceed() {
                                        log("[Sideload] Post-install: opened $pkg on glasses")
                                        if (openCloseAfter) {
                                            mainHandler.postDelayed({
                                                try {
                                                    val launcherInfo = com.rokid.cxr.client.extend.infos.RKAppInfo(
                                                        "com.rokid.glass.launcher",
                                                        "com.rokid.glass.launcher.LauncherActivity"
                                                    )
                                                    CxrApi.getInstance().openApp(
                                                        launcherInfo,
                                                        object : ApkStatusCallback {
                                                            override fun onOpenAppSucceed() {
                                                                log("[Sideload] Post-install: closed $pkg (opened launcher)")
                                                            }
                                                            override fun onOpenAppFailed() {
                                                                log("[Sideload] Post-install: failed to open launcher")
                                                            }
                                                            override fun onUploadApkSucceed() {}
                                                            override fun onUploadApkFailed() {}
                                                            override fun onInstallApkSucceed() {}
                                                            override fun onInstallApkFailed() {}
                                                            override fun onUninstallApkSucceed() {}
                                                            override fun onUninstallApkFailed() {}
                                                        }
                                                    )
                                                } catch (e: Exception) {
                                                    log("[Sideload] Post-install close failed: ${e.message}")
                                                }
                                            }, 3000)
                                        }
                                    }
                                    override fun onOpenAppFailed() {
                                        log("[Sideload] Post-install: openApp failed for $pkg")
                                    }
                                    override fun onUploadApkSucceed() {}
                                    override fun onUploadApkFailed() {}
                                    override fun onInstallApkSucceed() {}
                                    override fun onInstallApkFailed() {}
                                    override fun onUninstallApkSucceed() {}
                                    override fun onUninstallApkFailed() {}
                                })
                            } catch (e: Exception) {
                                log("[Sideload] Post-install openApp failed: ${e.message}")
                            }
                        }
                    }
                }
                override fun onInstallApkFailed() {
                    log("[Sideload] APK install FAILED on glasses")
                    lastInstallStatus = "Install failed"
                    completeInstall(false, "Install failed on glasses")
                    mainHandler.post {
                        statusCallback(true, null, lastUploadInfo, lastInstallStatus)
                    }
                }
                override fun onUninstallApkSucceed() {}
                override fun onUninstallApkFailed() {}
                override fun onOpenAppSucceed() {}
                override fun onOpenAppFailed() {}
            })
            log("[Sideload] Install callback registered")
        } catch (e: Exception) {
            log("[Sideload] Failed to register install callback: ${e.message}")
            completeInstall(false, "Failed to register install callback: ${e.message}")
        }
    }

    fun startP2pBeacon(phoneIp: String, glassesIp: String) {
        stopP2pBeacon()
        p2pBeaconRunning = true
        p2pBeaconThread = Thread {
            try {
                val msg = "RELAY:$phoneIp:5030".toByteArray()
                val targetAddr = InetAddress.getByName(glassesIp)
                val socket = DatagramSocket()
                log("[Sideload] Beacon: RELAY:$phoneIp:5030 -> $glassesIp:5031")
                while (p2pBeaconRunning) {
                    try {
                        val packet = DatagramPacket(msg, msg.size, targetAddr, 5031)
                        socket.send(packet)
                    } catch (_: Exception) {}
                    Thread.sleep(1000)
                }
                socket.close()
            } catch (e: Exception) {
                log("[Sideload] Beacon error: ${e.message}")
            }
        }
        p2pBeaconThread?.isDaemon = true
        p2pBeaconThread?.start()
    }

    fun stopP2pBeacon() {
        p2pBeaconRunning = false
        p2pBeaconThread?.interrupt()
        p2pBeaconThread = null
    }

    private fun getP2pInterfaceIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.name.startsWith("p2p")) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            log("[Sideload] Failed to get P2P interface IP: ${e.message}")
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun cleanupP2p() {
        try {
            p2pReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        p2pReceiver = null

        val mgr = p2pManager
        val ch = p2pChannel
        if (mgr != null && ch != null) {
            try { mgr.stopPeerDiscovery(ch, null) } catch (_: Exception) {}
            try { mgr.cancelConnect(ch, null) } catch (_: Exception) {}
            try { mgr.removeGroup(ch, null) } catch (_: Exception) {}
        }
    }

    fun cleanup() {
        uploading = false
        stopP2pBeacon()
        cleanupP2p()
        try { CxrApi.getInstance().deinitWifiP2P() } catch (_: Exception) {}
        p2pGlassesIp = null
        p2pPhoneIp = null
        releaseWakeLock()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "listener:sideload")
        }
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }
}
