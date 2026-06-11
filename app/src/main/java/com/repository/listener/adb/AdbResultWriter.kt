package com.repository.listener.adb

import android.content.Context
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes ADB command results as JSON files to filesDir/adb_results/.
 * Each command gets its own <command_id>.json file. A latest.json symlink
 * is also maintained for quick reads.
 */
object AdbResultWriter {

    private const val TAG = "AdbResultWriter"
    private const val DIR_NAME = "adb_results"
    private const val MAX_RESULTS = 50

    private fun resultsDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
    }

    fun writePending(context: Context, commandId: String, type: String) {
        val json = JSONObject().apply {
            put("command_id", commandId)
            put("type", type)
            put("status", "pending")
            put("timestamp", timestamp())
        }
        writeResult(context, commandId, json)
    }

    fun writeSuccess(context: Context, commandId: String, type: String, data: JSONObject? = null) {
        val json = JSONObject().apply {
            put("command_id", commandId)
            put("type", type)
            put("status", "success")
            put("timestamp", timestamp())
            if (data != null) put("data", data)
        }
        writeResult(context, commandId, json)
    }

    fun writeError(context: Context, commandId: String, type: String, error: String) {
        val json = JSONObject().apply {
            put("command_id", commandId)
            put("type", type)
            put("status", "error")
            put("timestamp", timestamp())
            put("error", error)
        }
        writeResult(context, commandId, json)
    }

    private fun writeResult(context: Context, commandId: String, json: JSONObject) {
        try {
            val dir = resultsDir(context)
            val content = json.toString(2)

            // Write command-specific file
            File(dir, "$commandId.json").writeText(content)

            // Write latest.json
            File(dir, "latest.json").writeText(content)

            LogCollector.i(TAG, "Result written: $commandId (${json.optString("status")})")

            // Prune old results
            pruneOldResults(dir)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to write result for $commandId: ${e.message}")
        }
    }

    private fun pruneOldResults(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "latest.json" }
            ?: return
        if (files.size <= MAX_RESULTS) return

        files.sortBy { it.lastModified() }
        val toDelete = files.size - MAX_RESULTS
        for (i in 0 until toDelete) {
            files[i].delete()
        }
        LogCollector.i(TAG, "Pruned $toDelete old result files")
    }
}
