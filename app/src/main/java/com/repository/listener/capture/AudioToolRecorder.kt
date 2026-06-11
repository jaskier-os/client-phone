package com.repository.listener.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioToolRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioToolRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    fun record(durationSeconds: Int, callback: (JSONObject?) -> Unit) {
        Thread {
            var audioRecord: AudioRecord? = null
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                val recordingsDir = File(context.filesDir, "recordings")
                recordingsDir.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val wavFile = File(recordingsDir, "recording_$timestamp.wav")

                val totalSamples = SAMPLE_RATE * durationSeconds
                val totalBytes = totalSamples * 2 // 16-bit = 2 bytes per sample

                // Write WAV with placeholder header, fill data, then fix header
                FileOutputStream(wavFile).use { fos ->
                    writeWavHeader(fos, 0) // placeholder

                    audioRecord.startRecording()
                    LogCollector.i(TAG, "Recording started for ${durationSeconds}s")

                    val buffer = ShortArray(bufferSize / 2)
                    var bytesWritten = 0

                    while (bytesWritten < totalBytes) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val byteBuffer = ByteArray(read * 2)
                            for (i in 0 until read) {
                                byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                                byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                            }
                            fos.write(byteBuffer)
                            bytesWritten += byteBuffer.size
                        }
                    }

                    audioRecord.stop()
                    LogCollector.i(TAG, "Recording stopped, wrote $bytesWritten bytes")
                }

                // Fix WAV header with actual data size
                RandomAccessFile(wavFile, "rw").use { raf ->
                    val fileSize = wavFile.length()
                    val dataSize = fileSize - 44

                    // RIFF chunk size (offset 4)
                    raf.seek(4)
                    writeIntLE(raf, (fileSize - 8).toInt())

                    // data chunk size (offset 40)
                    raf.seek(40)
                    writeIntLE(raf, dataSize.toInt())
                }

                val result = JSONObject().apply {
                    put("file_path", wavFile.absolutePath)
                    put("duration_ms", durationSeconds * 1000)
                    put("format", "wav")
                }
                callback(result)

            } catch (e: Exception) {
                LogCollector.e(TAG, "Recording failed: ${e.message}")
                callback(null)
            } finally {
                audioRecord?.release()
            }
        }.start()
    }

    private fun writeWavHeader(fos: FileOutputStream, dataSize: Int) {
        val header = ByteArray(44)
        val totalSize = dataSize + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        val blockAlign = 1 * 16 / 8

        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntToArray(header, 4, totalSize)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

        // fmt chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntToArray(header, 16, 16) // chunk size
        writeShortToArray(header, 20, 1) // PCM format
        writeShortToArray(header, 22, 1) // mono
        writeIntToArray(header, 24, SAMPLE_RATE)
        writeIntToArray(header, 28, byteRate)
        writeShortToArray(header, 32, blockAlign)
        writeShortToArray(header, 34, 16) // bits per sample

        // data chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntToArray(header, 40, dataSize)

        fos.write(header)
    }

    private fun writeIntToArray(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = (value shr 8 and 0xFF).toByte()
        array[offset + 2] = (value shr 16 and 0xFF).toByte()
        array[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShortToArray(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun writeIntLE(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xFF)
        raf.write(value shr 8 and 0xFF)
        raf.write(value shr 16 and 0xFF)
        raf.write(value shr 24 and 0xFF)
    }
}
