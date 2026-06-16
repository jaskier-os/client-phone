package com.repository.listener.util

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * In-memory log collector that captures app logs for the current session.
 * Mirrors android.util.Log API -- call L.i/e/w/d instead of Log.i/e/w/d
 * to capture logs both to logcat and to the in-memory buffer.
 * Thread-safe, capped at MAX_ENTRIES to prevent OOM.
 *
 * Call [init] once from Application.onCreate() to enable persistent file logging.
 * Logs are written to two streams under filesDir/logs/:
 *   - listener/latest.log  (phone service logs)
 *   - glasses/latest.log   (phone-side glasses-domain logs: BT host, settings UI,
 *                           Telegram notif relay, audio recording viewer)
 * On each init, the previous latest.log is compressed into a timestamped .zip archive.
 * Only the last 14 archives are kept (FIFO). Latest.log is capped at 50MB (FIFO, keeps newest 25MB).
 */
object LogCollector {

    private const val MAX_ENTRIES = 2000
    private const val MAX_SESSIONS = 14
    private const val MAX_LOG_SIZE = 50L * 1024 * 1024
    private const val FIFO_KEEP_SIZE = 25L * 1024 * 1024
    private val entries = ConcurrentLinkedDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sessionFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val writerLock = Any()
    private var listenerWriter: BufferedWriter? = null
    private var glassesWriter: BufferedWriter? = null
    private var listenerLogFile: File? = null
    private var glassesLogFile: File? = null
    private var listenerWriteCount = 0
    private var glassesWriteCount = 0

    fun init(context: Context) {
        val logsDir = File(context.filesDir, "logs")
        val listenerDir = File(logsDir, "listener")
        val glassesDir = File(logsDir, "glasses")
        listenerDir.mkdirs()
        glassesDir.mkdirs()

        val ts = sessionFmt.format(Date())
        rotateAndOpen(listenerDir, ts, { listenerWriter = it }, { listenerLogFile = it })
        rotateAndOpen(glassesDir, ts, { glassesWriter = it }, { glassesLogFile = it })
    }

    private fun rotateAndOpen(dir: File, ts: String, assign: (BufferedWriter) -> Unit, assignFile: (File) -> Unit) {
        try {
            val latest = File(dir, "latest.log")
            if (latest.exists() && latest.length() > 0) {
                val zipFile = File(dir, "$ts.zip")
                zipToArchive(latest, "$ts.log", zipFile)
                latest.delete()
            }
            pruneOldSessions(dir)
            assignFile(latest)
            assign(BufferedWriter(FileWriter(latest, true)))
        } catch (_: Exception) {}
    }

    private fun zipToArchive(source: File, entryName: String, dest: File) {
        try {
            ZipOutputStream(FileOutputStream(dest)).use { zos ->
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(source).use { fis -> fis.copyTo(zos) }
                zos.closeEntry()
            }
        } catch (_: Exception) {}
    }

    private fun pruneOldSessions(dir: File) {
        val archives = dir.listFiles { f -> f.name.endsWith(".zip") } ?: return
        if (archives.size > MAX_SESSIONS) {
            archives.sortedByDescending { it.lastModified() }
                .drop(MAX_SESSIONS)
                .forEach { it.delete() }
        }
    }

    private fun writeToFile(tag: String, line: String) {
        val isGlasses = tag in GLASSES_TAGS
        val writer = if (isGlasses) glassesWriter else listenerWriter
        writer ?: return
        synchronized(writerLock) {
            try {
                writer.write(line)
                writer.newLine()
                writer.flush()
            } catch (_: Exception) {}
            if (isGlasses) { glassesWriteCount++ } else { listenerWriteCount++ }
            val count = if (isGlasses) glassesWriteCount else listenerWriteCount
            if (count % 500 == 0) truncateIfNeeded(isGlasses)
        }
    }

    private fun truncateIfNeeded(isGlasses: Boolean) {
        val file = if (isGlasses) glassesLogFile else listenerLogFile
        file ?: return
        if (!file.exists() || file.length() <= MAX_LOG_SIZE) return
        try {
            val raf = RandomAccessFile(file, "r")
            raf.seek(file.length() - FIFO_KEEP_SIZE)
            raf.readLine()
            val remaining = ByteArray((file.length() - raf.filePointer).toInt())
            raf.readFully(remaining)
            raf.close()
            if (isGlasses) { glassesWriter?.close() } else { listenerWriter?.close() }
            file.writeBytes(remaining)
            val newWriter = BufferedWriter(FileWriter(file, true))
            if (isGlasses) { glassesWriter = newWriter } else { listenerWriter = newWriter }
        } catch (_: Exception) {}
    }

    fun shutdown() {
        synchronized(writerLock) {
            try { listenerWriter?.flush(); listenerWriter?.close() } catch (_: Exception) {}
            try { glassesWriter?.flush(); glassesWriter?.close() } catch (_: Exception) {}
            listenerWriter = null
            glassesWriter = null
        }
    }

    fun clear() {
        entries.clear()
    }

    private fun append(tag: String, level: String, message: String) {
        val ts = fmt.format(Date())
        val line = "$ts $level/$tag: $message"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }
        writeToFile(tag, line)
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        append(tag, "I", message)
    }

    fun e(tag: String, message: String) {
        android.util.Log.e(tag, message)
        append(tag, "E", message)
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        append(tag, "W", message)
    }

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        append(tag, "D", message)
    }

    /** All tags that belong to glasses BT / settings / display. */
    val GLASSES_TAGS = setOf(
        "PhoneBtHost",
        "Rokid Glasses CXR-M",
        "GlassesSettings",
        "GlassesInfoDialog",
        "GlassesFragment",
        "TelegramNotif",
        "AudioRecordingList",
        "AudioViewer"
    )

    /** Tags for glasses file sync. */
    val FILESYNC_TAGS = setOf("GlassesFileSync")

    /** Tags for voice input pipeline components. */
    val VOICE_TAGS = setOf(
        "WakeWordDetector", "VadEngine", "AudioRecorder",
        "TranscriptionClient", "SpeakerVerifier", "PipelineTracer",
        "WebRTCClient", "TtsPlayer", "AudioToolRecorder",
        "SpeechPosTracker", "GlassesAudioArchiver", "WhisperTranscriber",
        "AudioFileManager"
    )

    fun getText(): String = entries.joinToString("\n")

    /** Get logs matching only the given tags. */
    fun getText(includeTags: Set<String>): String =
        entries.filter { line -> includeTags.any { tag -> line.contains("/$tag:") } }
            .joinToString("\n")

    /** Get logs excluding the given tags. */
    fun getTextExcluding(excludeTags: Set<String>): String =
        entries.filter { line -> excludeTags.none { tag -> line.contains("/$tag:") } }
            .joinToString("\n")

    /** Get logs from the last [minutes] minutes matching only the given tags. */
    fun getTextRecent(includeTags: Set<String>, minutes: Int): String {
        val cutoff = fmt.format(Date(System.currentTimeMillis() - minutes * 60_000L))
        return entries.filter { line ->
            includeTags.any { tag -> line.contains("/$tag:") } &&
            line.substring(0, minOf(12, line.length)) >= cutoff
        }.joinToString("\n")
    }

    /** Get logs from the last [minutes] minutes excluding the given tags. */
    fun getTextRecentExcluding(excludeTags: Set<String>, minutes: Int): String {
        val cutoff = fmt.format(Date(System.currentTimeMillis() - minutes * 60_000L))
        return entries.filter { line ->
            excludeTags.none { tag -> line.contains("/$tag:") } &&
            line.substring(0, minOf(12, line.length)) >= cutoff
        }.joinToString("\n")
    }

    fun getEntryCount(): Int = entries.size

    fun getEntryCount(includeTags: Set<String>): Int =
        entries.count { line -> includeTags.any { tag -> line.contains("/$tag:") } }

    fun getEntryCountExcluding(excludeTags: Set<String>): Int =
        entries.count { line -> excludeTags.none { tag -> line.contains("/$tag:") } }
}
