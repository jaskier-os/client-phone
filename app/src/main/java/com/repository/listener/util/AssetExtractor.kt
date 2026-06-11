package com.repository.listener.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {

    private const val TAG = "AssetExtractor"
    private const val VERSION_MARKER = ".extracted_version"

    /**
     * Recursively extracts an asset directory to the target path.
     * Uses a version marker file to detect when re-extraction is needed
     * (e.g., app update changes bundled assets).
     */
    fun extractAssetDir(context: Context, assetPath: String, targetDir: File) {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        val versionFile = File(targetDir, VERSION_MARKER)
        if (versionFile.exists() && versionFile.readText().trim() == appVersion) {
            Log.i(TAG, "Already extracted (version $appVersion): ${targetDir.absolutePath}")
            return
        }

        // Clean old extraction before re-extracting
        if (targetDir.exists()) {
            Log.i(TAG, "Cleaning old extraction: ${targetDir.absolutePath}")
            targetDir.deleteRecursively()
        }

        extractRecursive(context, assetPath, targetDir)

        // Write version marker
        versionFile.writeText(appVersion)
        Log.i(TAG, "Extracted $assetPath -> ${targetDir.absolutePath} (version $appVersion)")
    }

    private fun extractRecursive(context: Context, assetPath: String, targetDir: File) {
        targetDir.mkdirs()
        val assets = context.assets
        val entries = assets.list(assetPath) ?: return

        if (entries.isEmpty()) {
            // It's a file, copy it
            assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childTarget = File(targetDir, entry)
            val childEntries = assets.list(childAssetPath)

            if (childEntries != null && childEntries.isNotEmpty()) {
                extractRecursive(context, childAssetPath, childTarget)
            } else {
                assets.open(childAssetPath).use { input ->
                    FileOutputStream(childTarget).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
