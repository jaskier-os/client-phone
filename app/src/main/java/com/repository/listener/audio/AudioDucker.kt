package com.repository.listener.audio

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.repository.listener.util.LogCollector

/**
 * Ducks the phone streams that carry "other glasses audio" -- A2DP media
 * (STREAM_MUSIC) and HFP call audio (STREAM_VOICE_CALL, STREAM_BLUETOOTH_SCO) --
 * while glasses TTS is playing. The TTS playback path is a separate AudioTrack
 * on the glasses (delivered via a BT data channel, not these streams), so it
 * is not affected by ducking.
 *
 * Declarative: glasses send absolute duck/unduck state, no reference counting.
 */
class AudioDucker(context: Context) {

    companion object {
        private const val TAG = "AudioDucker"
        private const val DUCK_FRACTION = 0.4f
        private const val DUCK_RAMP_MS = 300L
        private const val UNDUCK_RAMP_MS = 500L

        private val TARGET_STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_VOICE_CALL,
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var ducked = false
    private val savedVolumes = IntArray(TARGET_STREAMS.size) { -1 }
    private val animators = arrayOfNulls<ValueAnimator>(TARGET_STREAMS.size)

    /**
     * Set desired duck state. Re-entrant safe: a second duck while already
     * ducked just prolongs the duck (no volume change). Only the first duck
     * saves the original volume; only unduck restores it.
     */
    fun setDucked(duck: Boolean) {
        LogCollector.i(TAG, "setDucked($duck) current=$ducked")
        if (duck) {
            if (ducked) return  // already ducked -- prolong, don't re-duck
            ducked = true
            mainHandler.post { applyDuck() }
        } else {
            if (!ducked) return
            ducked = false
            mainHandler.post { applyRestore() }
        }
    }

    private fun applyDuck() {
        for (i in TARGET_STREAMS.indices) {
            // Only save volume on first duck -- never overwrite with an
            // already-ducked value if applyDuck somehow runs twice.
            if (savedVolumes[i] >= 0) continue
            val stream = TARGET_STREAMS[i]
            val current = try {
                audioManager.getStreamVolume(stream)
            } catch (e: Exception) {
                LogCollector.e(TAG, "getStreamVolume($stream) failed: ${e.message}")
                continue
            }
            savedVolumes[i] = current
            val target = (current * DUCK_FRACTION).toInt().coerceAtLeast(0)
            LogCollector.i(TAG, "Ducking stream $stream: $current -> $target")
            animateStreamVolume(i, current, target, DUCK_RAMP_MS)
        }
    }

    private fun applyRestore() {
        for (i in TARGET_STREAMS.indices) {
            val saved = savedVolumes[i]
            if (saved < 0) continue
            val stream = TARGET_STREAMS[i]
            val current = try {
                audioManager.getStreamVolume(stream)
            } catch (e: Exception) {
                LogCollector.e(TAG, "getStreamVolume($stream) failed: ${e.message}")
                continue
            }
            LogCollector.i(TAG, "Restoring stream $stream: $current -> $saved")
            animateStreamVolume(i, current, saved, UNDUCK_RAMP_MS)
            savedVolumes[i] = -1
        }
    }

    private fun animateStreamVolume(index: Int, from: Int, to: Int, durationMs: Long) {
        animators[index]?.cancel()
        if (from == to) return
        val stream = TARGET_STREAMS[index]
        animators[index] = ValueAnimator.ofInt(from, to).apply {
            duration = durationMs
            addUpdateListener { anim ->
                try {
                    audioManager.setStreamVolume(stream, anim.animatedValue as Int, 0)
                } catch (e: Exception) {
                    LogCollector.e(TAG, "setStreamVolume($stream) failed: ${e.message}")
                }
            }
            start()
        }
    }

    fun reset() {
        for (i in TARGET_STREAMS.indices) {
            animators[i]?.cancel()
            animators[i] = null
            val saved = savedVolumes[i]
            if (saved >= 0) {
                try {
                    audioManager.setStreamVolume(TARGET_STREAMS[i], saved, 0)
                } catch (_: Exception) {}
            }
            savedVolumes[i] = -1
        }
        ducked = false
    }
}
