package com.repository.listener.phone

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Reads the phone's contacts and produces a normalized JSON list + content hash.
 * Used to seed the glasses-side caller-ID cache so HFP incoming calls can show a
 * human name (HFP itself only carries the number).
 */
object ContactsRepository {

    data class Snapshot(val json: String, val hash: String, val count: Int)

    /**
     * Build a snapshot of all phone contacts. Each entry: {"n": E164-ish, "d": display}.
     * Returns empty snapshot (still hashable) if READ_CONTACTS is not granted.
     */
    fun build(ctx: Context): Snapshot {
        val arr = JSONArray()
        val seen = HashSet<String>()
        try {
            val cr = ctx.contentResolver
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                proj, null, null, null
            )?.use { c ->
                val iN = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val iD = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                if (iN < 0 || iD < 0) return@use
                while (c.moveToNext()) {
                    val raw = c.getString(iN) ?: continue
                    val disp = c.getString(iD)?.trim().orEmpty()
                    if (disp.isEmpty()) continue
                    val norm = normalize(raw)
                    if (norm.isEmpty()) continue
                    val key = "$norm|$disp"
                    if (!seen.add(key)) continue
                    arr.put(JSONObject().apply {
                        put("n", norm)
                        put("d", disp)
                    })
                }
            }
        } catch (_: SecurityException) {
            // Permission missing -- emit an empty snapshot, glasses will still have a hash to compare.
        } catch (_: Exception) {
        }
        val json = arr.toString()
        val hash = sha256Hex(json)
        return Snapshot(json, hash, arr.length())
    }

    /** Strip everything except digits and a leading +. */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for ((i, ch) in raw.withIndex()) {
            if (ch == '+' && i == 0) sb.append('+')
            else if (ch in '0'..'9') sb.append(ch)
        }
        return sb.toString()
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
