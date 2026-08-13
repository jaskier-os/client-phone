package com.repository.listener.arstream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArStreamSessionStateTest {

    @Test
    fun bothMicsLiveByDefault() {
        val s = ArStreamSessionState()
        assertTrue(s.shouldSendPhoneMic())
        assertTrue(s.shouldAcceptGlassesAudio())
    }

    @Test
    fun mutingPhoneMicStopsSendingAtTheSource() {
        val s = ArStreamSessionState()
        s.setPhoneMicMuted(true)
        assertFalse(s.shouldSendPhoneMic())
    }

    @Test
    fun mutingGlassesMicStopsUplinkAtTheSource() {
        val s = ArStreamSessionState()
        s.setGlassesMicMuted(true)
        assertFalse(s.shouldAcceptGlassesAudio())
    }

    @Test
    fun mutesAreIndependent() {
        val s = ArStreamSessionState()
        s.setPhoneMicMuted(true)
        assertTrue(s.shouldAcceptGlassesAudio())
        s.setGlassesMicMuted(true)
        s.setPhoneMicMuted(false)
        assertTrue(s.shouldSendPhoneMic())
        assertFalse(s.shouldAcceptGlassesAudio())
    }

    @Test
    fun surfaceRecreationRequiresFreshConfigFrame() {
        val s = ArStreamSessionState()
        s.onDecoderStarted()
        assertFalse(s.needsKeyframe())
        s.onSurfaceDestroyed()
        assertTrue(s.needsKeyframe())
    }
}
