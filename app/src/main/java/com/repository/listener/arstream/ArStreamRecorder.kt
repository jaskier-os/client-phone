package com.repository.listener.arstream

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.repository.listener.util.LogCollector
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records a live AR stream session to a single MP4 and publishes it to the phone gallery.
 *
 * Video is NOT re-encoded: the glasses already send H.264 (camera + HUD composited on their side),
 * so the received NALs are muxed straight through with [MediaMuxer]. The SPS/PPS config frame the
 * session already caches becomes csd-0/csd-1 of the track format.
 *
 * Audio is the sample-wise mix of BOTH directions -- the glasses uplink (what their mic heard) and
 * the phone's own outgoing mic -- at 0.5 gain each with clamping, mirroring the glasses' own
 * UplinkMixer. The mix is paced by wall clock so a side that is muted, slow, or absent simply
 * contributes silence instead of stalling or drifting the timeline.
 */
class ArStreamRecorder(private val context: Context) {

    /** Wall clock origin (ns) for both media timelines. */
    @Volatile private var startNs = 0L

    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    @Volatile private var muxing = false

    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private var encoder: MediaCodec? = null
    private var pumpThread: Thread? = null
    private var drainThread: Thread? = null

    /** Per-direction sample queues, drained by the pump. Bounded: a stalled pump must not OOM. */
    private val glassesQ = ArrayBlockingQueue<ShortArray>(QUEUE_FRAMES)
    private val phoneQ = ArrayBlockingQueue<ShortArray>(QUEUE_FRAMES)

    /** Leftovers of a partially consumed chunk, carried to the next pump tick. */
    private var glassesRest: ShortArray? = null
    private var glassesRestOff = 0
    private var phoneRest: ShortArray? = null
    private var phoneRestOff = 0

    /** Video frames seen before the muxer could start (waiting for the AAC output format). */
    private val pendingVideo = ArrayList<Triple<ByteArray, Long, Boolean>>()

    @Volatile private var sawKeyframe = false
    @Volatile private var encodedSamples = 0L

    /**
     * PTS of the first muxed video frame, subtracted from every later frame so the track opens at
     * exactly 0. A track whose first sample sits ~57 ms in has no frame at position 0, and gallery
     * thumbnailers -- which seek to 0 -- render a black or broken tile.
     */
    private var videoPtsBaseUs = -1L
    /**
     * Last PTS handed to each track. MediaMuxer writes the video track on a 1 ms timescale, so two
     * frames a few hundred microseconds apart round to the SAME integer timestamp and the file gets
     * non-monotonic DTS, which strict players (Stagefright, thumbnailers) stall or fail on.
     * Every sample is therefore forced at least [MIN_PTS_STEP_US] past its predecessor.
     */
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    @Volatile var videoFramesWritten = 0L; private set
    @Volatile var audioFramesWritten = 0L; private set

    /** Peak absolute sample of the mixed track -- proves the audio is not digital silence. */
    @Volatile var mixPeak = 0; private set

    private var tmpFile: File? = null
    private var displayName: String = ""
    private var videoWidth = 0
    private var videoHeight = 0

    val elapsedMs: Long
        get() = if (startNs == 0L) 0L else (System.nanoTime() - startNs) / 1_000_000L

    /**
     * @param configFrame the cached codec-config video frame (10-byte header + Annex-B SPS/PPS).
     * @throws IllegalStateException when no config frame is available yet -- without SPS/PPS the
     *   muxer cannot describe the track and the resulting file would be unplayable.
     */
    fun start(configFrame: ByteArray?, width: Int, height: Int) {
        check(running.compareAndSet(false, true)) { "already recording" }
        val csd = configFrame?.let { splitConfig(it) }
            ?: run { running.set(false); throw IllegalStateException("no video config yet") }

        videoWidth = width
        videoHeight = height
        displayName = "$FILE_PREFIX${System.currentTimeMillis()}.mp4"
        val f = File(context.cacheDir, displayName)
        tmpFile = f

        val m = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
            setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
        }
        videoTrack = m.addTrack(vf)
        muxer = m

        val af = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, ArStreamProtocol.AUDIO_SAMPLE_RATE, 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PUMP_SAMPLES * 4)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc

        startNs = System.nanoTime()
        drainThread = thread(name = "ArStreamRec-drain") { drainLoop() }
        pumpThread = thread(name = "ArStreamRec-pump") { pumpLoop() }
        LogCollector.i(TAG, "recording started -> ${f.absolutePath}")
    }

    val isRecording: Boolean get() = running.get() && !stopped.get()

    /** Called on the video socket thread. Config frames are csd, not samples, and are skipped. */
    fun onVideoFrame(frame: ByteArray) {
        if (!running.get() || stopped.get()) return
        if (frame.size <= ArStreamProtocol.VIDEO_HEADER_SIZE) return
        val flags = frame[1].toInt()
        if ((flags and 0x02) != 0) return
        val key = (flags and 0x01) != 0
        // Never open a track with a delta frame: the file would decode to garbage until the first
        // IDR and some players simply refuse it.
        if (!sawKeyframe) {
            if (!key) return
            sawKeyframe = true
        }
        val ptsUs = (System.nanoTime() - startNs) / 1000L
        val body = frame.copyOfRange(ArStreamProtocol.VIDEO_HEADER_SIZE, frame.size)
        synchronized(this) {
            if (!muxing) {
                // The muxer cannot start until the AAC encoder reports its output format, which
                // takes a few tens of ms. Hold frames rather than punching a hole in the video.
                if (pendingVideo.size < PENDING_VIDEO_CAP) pendingVideo.add(Triple(body, ptsUs, key))
                return
            }
            writeVideo(body, ptsUs, key)
        }
    }

    fun onGlassesAudio(pcm: ShortArray) {
        if (!running.get() || stopped.get()) return
        if (!glassesQ.offer(pcm)) { glassesQ.poll(); glassesQ.offer(pcm) }
    }

    fun onPhoneMicAudio(pcm: ShortArray, length: Int) {
        if (!running.get() || stopped.get()) return
        val copy = if (length == pcm.size) pcm.copyOf() else pcm.copyOf(length)
        if (!phoneQ.offer(copy)) { phoneQ.poll(); phoneQ.offer(copy) }
    }

    private fun writeVideo(body: ByteArray, rawPtsUs: Long, key: Boolean) {
        val m = muxer ?: return
        if (videoPtsBaseUs < 0) videoPtsBaseUs = rawPtsUs
        var ptsUs = rawPtsUs - videoPtsBaseUs
        if (ptsUs < 0) ptsUs = 0
        if (ptsUs <= lastVideoPtsUs) ptsUs = lastVideoPtsUs + MIN_PTS_STEP_US
        lastVideoPtsUs = ptsUs
        val info = MediaCodec.BufferInfo().apply {
            set(0, body.size, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        try {
            m.writeSampleData(videoTrack, ByteBuffer.wrap(body), info)
            videoFramesWritten++
        } catch (e: Exception) {
            LogCollector.e(TAG, "video write failed: ${e.message}")
        }
    }

    /**
     * Produce exactly as many mixed samples as wall clock says should exist by now, so the audio
     * timeline cannot drift from the wall-clock video PTS regardless of either side's arrival rate.
     */
    private fun pumpLoop() {
        while (running.get() && !stopped.get()) {
            try {
                val targetSamples = (elapsedMs * ArStreamProtocol.AUDIO_SAMPLE_RATE) / 1000L
                var need = targetSamples - encodedSamples
                // Catch up the FULL wall-clock deficit. Bailing out on a momentarily unavailable
                // input buffer would shorten the audio track relative to the wall-clock video PTS,
                // and that error is cumulative -- it never comes back.
                var stalls = 0
                while (need >= PUMP_SAMPLES && running.get() && !stopped.get()) {
                    if (encodeChunk()) {
                        need -= PUMP_SAMPLES
                        stalls = 0
                    } else if (++stalls > MAX_INPUT_STALLS) {
                        break
                    }
                }
                Thread.sleep(PUMP_TICK_MS)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                LogCollector.e(TAG, "pump: ${e.message}")
                break
            }
        }
    }

    private fun encodeChunk(): Boolean {
        val enc = encoder ?: return false
        val idx = try { enc.dequeueInputBuffer(CODEC_TIMEOUT_US) } catch (e: Exception) { return false }
        if (idx < 0) return false
        val buf = enc.getInputBuffer(idx) ?: return false
        buf.clear()
        var peak = 0
        for (i in 0 until PUMP_SAMPLES) {
            // 0.5 gain per side + clamp: a plain sum of two full-scale mics wraps to a loud buzz.
            val g = nextSample(true)
            val p = nextSample(false)
            var v = ((g * MIX_GAIN) + (p * MIX_GAIN)).toInt()
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toInt()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toInt()
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
            buf.put((v and 0xFF).toByte())
            buf.put(((v shr 8) and 0xFF).toByte())
        }
        if (peak > mixPeak) mixPeak = peak
        val ptsUs = encodedSamples * 1_000_000L / ArStreamProtocol.AUDIO_SAMPLE_RATE
        enc.queueInputBuffer(idx, 0, PUMP_SAMPLES * 2, ptsUs, 0)
        encodedSamples += PUMP_SAMPLES
        return true
    }

    /** One sample from a direction's queue, or 0 when that side is muted / silent / behind. */
    private fun nextSample(glasses: Boolean): Int {
        var rest = if (glasses) glassesRest else phoneRest
        var off = if (glasses) glassesRestOff else phoneRestOff
        if (rest == null || off >= rest.size) {
            rest = (if (glasses) glassesQ else phoneQ).poll()
            off = 0
            if (rest == null) {
                if (glasses) { glassesRest = null; glassesRestOff = 0 }
                else { phoneRest = null; phoneRestOff = 0 }
                return 0
            }
        }
        val v = rest[off].toInt()
        off++
        if (glasses) { glassesRest = rest; glassesRestOff = off }
        else { phoneRest = rest; phoneRestOff = off }
        return v
    }

    private fun drainLoop() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val idx = try {
                enc.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            } catch (e: Exception) {
                break
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(this) {
                    val m = muxer
                    if (m != null && !muxing) {
                        audioTrack = m.addTrack(enc.outputFormat)
                        m.start()
                        muxing = true
                        // Each frame keeps the keyframe flag it actually arrived with: flagging
                        // only the first would leave every later IDR out of the sync-sample table
                        // and make seeking land on delta frames.
                        for ((body, pts, key) in pendingVideo) writeVideo(body, pts, key)
                        pendingVideo.clear()
                        LogCollector.i(TAG, "muxer started, flushed $videoFramesWritten pending video frames")
                    }
                }
                idx >= 0 -> {
                    val out = enc.getOutputBuffer(idx)
                    if (out != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        synchronized(this) {
                            if (muxing) {
                                try {
                                    // Same 1 ms-timescale rounding hazard as video.
                                    if (info.presentationTimeUs <= lastAudioPtsUs) {
                                        info.presentationTimeUs = lastAudioPtsUs + MIN_PTS_STEP_US
                                    }
                                    lastAudioPtsUs = info.presentationTimeUs
                                    muxer?.writeSampleData(audioTrack, out, info)
                                    audioFramesWritten++
                                } catch (e: Exception) {
                                    LogCollector.e(TAG, "audio write failed: ${e.message}")
                                }
                            }
                        }
                    }
                    try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    /** Queue an empty EOS input buffer so the encoder flushes its remaining frames. */
    private fun signalEos() {
        val enc = encoder ?: return
        try {
            val idx = enc.dequeueInputBuffer(EOS_TIMEOUT_US)
            if (idx >= 0) {
                val ptsUs = encodedSamples * 1_000_000L / ArStreamProtocol.AUDIO_SAMPLE_RATE
                enc.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "eos: ${e.message}")
        }
    }

    /**
     * Finalise and publish. Idempotent and safe from onDestroy: an MP4 whose moov atom was never
     * written is unplayable, so stop() must run on EVERY exit path.
     *
     * @return the MediaStore uri, or null when nothing usable was recorded.
     */
    fun stop(): Uri? {
        if (!running.get()) return null
        if (!stopped.compareAndSet(false, true)) return null

        try { pumpThread?.join(THREAD_JOIN_MS) } catch (_: InterruptedException) {}
        // End-of-stream first: the encoder holds about a frame of latency, and dropping it by
        // simply clearing `running` truncates the tail of the audio track.
        signalEos()
        try { drainThread?.join(THREAD_JOIN_MS) } catch (_: InterruptedException) {}
        running.set(false)
        // The second join is what makes releasing the encoder below safe -- a drain thread still
        // inside dequeueOutputBuffer when release() lands crashes natively.
        val d = drainThread
        try { d?.join(THREAD_JOIN_MS) } catch (_: InterruptedException) {}
        if (d != null && d.isAlive) {
            // Never release under a live drain: leak the codec instead of crashing the process.
            LogCollector.e(TAG, "drain thread did not exit; skipping encoder release")
            encoder = null
        }
        pumpThread = null
        drainThread = null

        // Order matters: clear `muxing` and stop/release the muxer under the SAME lock the drain
        // and video threads take, or a writer still inside writeSampleData can be handed a
        // released muxer and crash in native code (an exception guard cannot save a
        // use-after-release).
        val hadMux = synchronized(this) {
            val was = muxing
            muxing = false
            pendingVideo.clear()
            if (was) {
                try { muxer?.stop() } catch (e: Exception) { LogCollector.e(TAG, "muxer stop: ${e.message}") }
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            was
        }

        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null

        val f = tmpFile
        tmpFile = null
        if (f == null || !hadMux || !f.exists() || f.length() == 0L) {
            f?.delete()
            LogCollector.e(TAG, "nothing recorded (muxStarted=$hadMux)")
            return null
        }
        LogCollector.i(
            TAG,
            "recorded ${f.length()} bytes video=$videoFramesWritten audio=$audioFramesWritten peak=$mixPeak"
        )
        return try {
            publish(f)
        } catch (e: Exception) {
            LogCollector.e(TAG, "publish failed: ${e.message}")
            null
        } finally {
            f.delete()
        }
    }

    /** Copy into the phone's normal gallery via MediaStore (no legacy storage permission needed). */
    private fun publish(f: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.WIDTH, videoWidth)
            put(MediaStore.Video.Media.HEIGHT, videoHeight)
            // Wall-clock length: the muxed timeline is wall-clock paced, so this matches the file.
            put(MediaStore.Video.Media.DURATION, elapsedMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri).use { out ->
            if (out == null) { resolver.delete(uri, null, null); return null }
            f.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
        }
        LogCollector.i(TAG, "published $uri as $displayName")
        return uri
    }

    /** Split an Annex-B config frame into (SPS, PPS) for csd-0 / csd-1. */
    private fun splitConfig(frame: ByteArray): Pair<ByteArray, ByteArray>? {
        val body = frame.copyOfRange(ArStreamProtocol.VIDEO_HEADER_SIZE, frame.size)
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < body.size) {
            // 4-byte start code FIRST: testing the 3-byte pattern first matches 00 00 01 at i+1
            // of a 4-byte code, putting the SPS one byte late in csd-0.
            if (body[i] == 0.toByte() && body[i + 1] == 0.toByte() &&
                body[i + 2] == 0.toByte() && body[i + 3] == 1.toByte()
            ) {
                starts.add(i); i += 4
            } else if (body[i] == 0.toByte() && body[i + 1] == 0.toByte() && body[i + 2] == 1.toByte()) {
                starts.add(i); i += 3
            } else i++
        }
        if (starts.size < 2) return null
        val sps = body.copyOfRange(starts[0], starts[1])
        val pps = body.copyOfRange(starts[1], body.size)
        return sps to pps
    }

    companion object {
        private const val TAG = "ArStreamRecorder"
        const val FILE_PREFIX = "arstream_"

        /** 20 ms of 16 kHz mono -- the same granularity both sides already send. */
        private const val PUMP_SAMPLES = 320
        private const val PUMP_TICK_MS = 10L
        private const val QUEUE_FRAMES = 200
        private const val PENDING_VIDEO_CAP = 300
        private const val AAC_BITRATE = 64_000
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val EOS_TIMEOUT_US = 500_000L
        /** ~0.5s of retries at CODEC_TIMEOUT_US before giving up on a wedged encoder. */
        private const val MAX_INPUT_STALLS = 50
        private const val MIX_GAIN = 0.5f
        /** 1 ms: the muxer's own video timescale, so a bumped sample survives its rounding. */
        private const val MIN_PTS_STEP_US = 1_000L
        private const val THREAD_JOIN_MS = 2_000L
    }
}
