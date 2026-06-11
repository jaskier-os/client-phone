package com.repository.listener.sync

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Phone-side mirror of the glasses manifest. Persists what we've pulled locally so we can
 * diff against the glasses on every reconnect / HELLO.
 */
class LocalManifest(private val rootDir: File) {

    companion object {
        private const val TAG = "LocalManifest"
        private const val FILE_NAME = ".local_manifest.json"

        fun sha256OfFile(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val bytes = md.digest()
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }

    data class Entry(
        val id: String,
        val relPath: String,
        val sha256: String,
        val size: Long,
        val mtime: Long,
        val kind: String,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val store: File get() = File(rootDir, FILE_NAME)

    @Synchronized
    fun load() {
        entries.clear()
        if (!store.exists()) return
        try {
            val text = store.readText(Charsets.UTF_8)
            val root = JSONObject(text)
            val arr = root.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(
                    id = o.getString("id"),
                    relPath = o.getString("relPath"),
                    sha256 = o.getString("sha256"),
                    size = o.getLong("size"),
                    mtime = o.getLong("mtime"),
                    kind = o.optString("kind", "photo"),
                )
                entries[e.id] = e
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            entries.clear()
        }
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.values.toList()

    @Synchronized
    fun get(id: String): Entry? = entries[id]

    @Synchronized
    fun upsert(entry: Entry) {
        entries[entry.id] = entry
        save()
    }

    @Synchronized
    fun remove(id: String) {
        if (entries.remove(id) != null) save()
    }

    @Synchronized
    fun stateHash(): String {
        if (entries.isEmpty()) return sha256Bytes(ByteArray(0))
        val sb = StringBuilder()
        val sorted = entries.values.sortedBy { it.id }
        for (e in sorted) {
            sb.append(e.id).append('\t').append(e.sha256).append('\t').append(e.size).append('\n')
        }
        return sha256Bytes(sb.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun replaceFromRemote(remote: List<Entry>) {
        val incomingIds = remote.map { it.id }.toSet()
        // Drop locally-tracked entries no longer on glasses (source of truth).
        entries.keys.filter { it !in incomingIds }.forEach { entries.remove(it) }
        // Note: we do NOT insert remote entries that haven't been pulled yet --
        // diff is computed externally by comparing remote snapshot to local snapshot.
        save()
    }

    private fun save() {
        try {
            if (!rootDir.exists()) rootDir.mkdirs()
            val arr = JSONArray()
            for (e in entries.values) {
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("relPath", e.relPath)
                    put("sha256", e.sha256)
                    put("size", e.size)
                    put("mtime", e.mtime)
                    put("kind", e.kind)
                })
            }
            val root = JSONObject().apply {
                put("version", 1)
                put("entries", arr)
            }
            val tmp = File(rootDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(store)) {
                store.delete()
                tmp.renameTo(store)
            }
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    private fun sha256Bytes(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(data)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

}
