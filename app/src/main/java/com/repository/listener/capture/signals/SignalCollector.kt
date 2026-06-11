package com.repository.listener.capture.signals

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Signal data for a single Yandex Locator API request.
 *
 * Any of the three lists may be empty. If [isEmpty] returns true the caller
 * MUST NOT hit the Locator API -- there is nothing to locate on and we would
 * just be burning rate-limit budget and retries.
 */
data class LocatorSignals(
    val wifi: List<JSONObject>,
    val cell: List<JSONObject>,
    val publicIp: String?,
) {
    fun isEmpty(): Boolean = wifi.isEmpty() && cell.isEmpty() && publicIp.isNullOrBlank()

    fun toRequestJson(): JSONObject {
        val root = JSONObject()
        if (wifi.isNotEmpty()) {
            root.put("wifi", JSONArray(wifi))
        }
        if (cell.isNotEmpty()) {
            root.put("cell", JSONArray(cell))
        }
        if (!publicIp.isNullOrBlank()) {
            root.put("ip", JSONArray(listOf(JSONObject().put("address", publicIp))))
        }
        return root
    }

    fun summary(): String = "wifi=${wifi.size} cell=${cell.size} ip=${if (publicIp.isNullOrBlank()) "0" else "1"}"
}

/**
 * Gathers the Locator API input payload: nearby Wi-Fi BSSIDs, cell tower info,
 * and the device's public IP. Safe to call from any thread -- the collect
 * function hops to Dispatchers.IO internally.
 *
 * NOTE: we read WifiManager.scanResults WITHOUT calling startScan(). Android 9+
 * throttles active scans to ~4 per 2 minutes for foreground apps (and blocks
 * them entirely for background apps), so the cached scan list is the only
 * reliable source. If the cache is empty we still try cell + IP.
 */
object SignalCollector {

    private const val TAG = "SignalCollector"

    suspend fun collect(context: Context): LocatorSignals = withContext(Dispatchers.IO) {
        // Local reads (WiFi + cell) are cheap; the public IP fetch is a network
        // call that can take 100-500ms. Run them concurrently so total latency
        // is bounded by max(local, network) rather than sum.
        coroutineScope {
            val ipJob = async { fetchPublicIp() }
            val wifi = collectWifi(context)
            val cell = collectCell(context)
            val ip = ipJob.await()
            val signals = LocatorSignals(wifi = wifi, cell = cell, publicIp = ip)
            Log.d(TAG, "COLLECT ${signals.summary()}")
            LogCollector.i(TAG, "Signals collected: ${signals.summary()}")
            signals
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectWifi(context: Context): List<JSONObject> {
        if (!hasFineLocation(context)) return emptyList()
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return emptyList()
            @Suppress("DEPRECATION")
            val scans = wifi.scanResults ?: emptyList()
            if (scans.isEmpty()) {
                Log.d(TAG, "WIFI empty scan cache")
                return emptyList()
            }
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            scans.mapNotNull { sr ->
                val bssid = sr.BSSID?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val normBssid = bssid.replace(":", "").replace("-", "").lowercase()
                if (normBssid.length != 12) return@mapNotNull null
                // sr.timestamp is in microseconds since boot
                val ageMs = ((nowNanos / 1_000L) - sr.timestamp) / 1_000L
                JSONObject().apply {
                    put("bssid", normBssid)
                    put("signal_strength", sr.level)
                    put("age", ageMs.coerceAtLeast(0L))
                }
            }.take(50)  // cap payload size
        } catch (e: Exception) {
            Log.w(TAG, "WIFI collect failed: ${e.message}")
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectCell(context: Context): List<JSONObject> {
        if (!hasFineLocation(context)) return emptyList()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            // READ_PHONE_STATE is declared in the manifest but may still be denied at runtime.
            // getAllCellInfo does NOT strictly require it on modern Android but we keep the
            // guard to avoid SecurityException spam on older OEM forks.
            Log.d(TAG, "CELL skip: READ_PHONE_STATE denied")
            return emptyList()
        }
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return emptyList()
            val all: List<CellInfo> = tm.allCellInfo ?: emptyList()
            if (all.isEmpty()) {
                Log.d(TAG, "CELL allCellInfo empty")
                return emptyList()
            }
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            all.mapNotNull { ci ->
                if (!ci.isRegistered) return@mapNotNull null
                // CellInfo.timeStamp is deprecated API 30+ in favor of getTimestampMillis(),
                // but we target API 29 so this is the only available accessor.
                @Suppress("DEPRECATION")
                val ageMs = ((nowNanos - ci.timeStamp) / 1_000_000L).coerceAtLeast(0L)
                when (ci) {
                    is CellInfoLte -> buildLte(ci, ageMs)
                    is CellInfoGsm -> buildGsm(ci, ageMs)
                    is CellInfoWcdma -> buildWcdma(ci, ageMs)
                    else -> null
                }
            }.take(10)
        } catch (e: SecurityException) {
            Log.w(TAG, "CELL security exception: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "CELL collect failed: ${e.message}")
            emptyList()
        }
    }

    private fun buildGsm(ci: CellInfoGsm, ageMs: Long): JSONObject? {
        val id = ci.cellIdentity
        val mcc = id.mccString?.toIntOrNull() ?: return null
        val mnc = id.mncString?.toIntOrNull() ?: return null
        val lac = id.lac.takeIf { it != Int.MAX_VALUE } ?: return null
        val cid = id.cid.takeIf { it != Int.MAX_VALUE } ?: return null
        val dbm = ci.cellSignalStrength.dbm
        return JSONObject().put("gsm", JSONObject().apply {
            put("mcc", mcc)
            put("mnc", mnc)
            put("lac", lac)
            put("cid", cid)
            put("signal_strength", dbm)
            put("age", ageMs)
        })
    }

    private fun buildLte(ci: CellInfoLte, ageMs: Long): JSONObject? {
        val id = ci.cellIdentity
        val mcc = id.mccString?.toIntOrNull() ?: return null
        val mnc = id.mncString?.toIntOrNull() ?: return null
        val tac = id.tac.takeIf { it != Int.MAX_VALUE } ?: return null
        val ciNum = id.ci.takeIf { it != Int.MAX_VALUE } ?: return null
        val dbm = ci.cellSignalStrength.dbm
        val pci = id.pci.takeIf { it != Int.MAX_VALUE }
        return JSONObject().put("lte", JSONObject().apply {
            put("mcc", mcc)
            put("mnc", mnc)
            put("tac", tac)
            put("ci", ciNum)
            if (pci != null) put("pci", pci)
            put("signal_strength", dbm)
            put("age", ageMs)
        })
    }

    private fun buildWcdma(ci: CellInfoWcdma, ageMs: Long): JSONObject? {
        val id = ci.cellIdentity
        val mcc = id.mccString?.toIntOrNull() ?: return null
        val mnc = id.mncString?.toIntOrNull() ?: return null
        val lac = id.lac.takeIf { it != Int.MAX_VALUE } ?: return null
        val cid = id.cid.takeIf { it != Int.MAX_VALUE } ?: return null
        val psc = id.psc.takeIf { it != Int.MAX_VALUE }
        val dbm = ci.cellSignalStrength.dbm
        return JSONObject().put("wcdma", JSONObject().apply {
            put("mcc", mcc)
            put("mnc", mnc)
            put("lac", lac)
            put("cid", cid)
            if (psc != null) put("psc", psc)
            put("signal_strength", dbm)
            put("age", ageMs)
        })
    }

    private fun fetchPublicIp(): String? = try {
        val url = java.net.URL("https://api.ipify.org")
        val conn = url.openConnection()
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.getInputStream().use { stream ->
            stream.bufferedReader().use { reader ->
                reader.readText().trim()
            }
        }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "IP fetch failed: ${e.message}")
        null
    }

    private fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
