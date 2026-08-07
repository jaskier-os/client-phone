package com.repository.listener.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan task 3.3 -- what the phone must and must NOT do while the glasses are
 * recognising speech themselves.
 *
 * Getting this wrong in either direction is bad in a different way:
 *
 *  - too permissive: BOTH transcribers run, the phone streams audio to the
 *    server for nothing, and two finals race to deliver the same sentence
 *    twice;
 *  - too restrictive: the phone stops buffering the PCM, and then the "fail"
 *    fallback has nothing to transcribe -- so a local failure becomes a lost
 *    utterance instead of a slightly slower one.
 *
 * The buffer is therefore the one thing that keeps running in local mode.
 */
class GlassesSttLocalGateTest {

    @Test
    fun remoteModeDoesEverythingItDoesToday() {
        val g = GlassesSttGate(localMode = false)
        assertTrue(g.shouldOpenTranscriberStream())
        assertTrue(g.shouldFeedPhoneVad())
        assertTrue(g.shouldArmNoSpeechWatchdog())
        assertTrue(g.shouldBufferPcm())
        assertTrue(g.shouldForwardPartials())
    }

    @Test
    fun localModeOpensNoTranscriberStream() {
        // No WebSocket, no server round trip, no audio leaving the device.
        assertFalse(GlassesSttGate(localMode = true).shouldOpenTranscriberStream())
    }

    @Test
    fun localModeDoesNotFeedThePhoneVad() {
        // Endpointing happens on the glasses in local mode. Running both VADs
        // means two independent opinions about when the wearer stopped talking,
        // and the phone's would finalize a session the glasses still own.
        assertFalse(GlassesSttGate(localMode = true).shouldFeedPhoneVad())
    }

    @Test
    fun localModeDoesNotArmTheNoSpeechWatchdog() {
        // The watchdog fires when the phone's VAD has heard nothing. In local
        // mode it hears nothing BY DESIGN, so arming it would cancel every
        // healthy local session.
        assertFalse(GlassesSttGate(localMode = true).shouldArmNoSpeechWatchdog())
    }

    @Test
    fun localModeStillBuffersThePcm() {
        // The load-bearing one. This buffer IS the fallback: on status=fail the
        // phone batch-transcribes it. Stop buffering and a local failure becomes
        // a lost utterance rather than a slightly later transcript.
        assertTrue(
            "the PCM buffer is what the failure fallback transcribes",
            GlassesSttGate(localMode = true).shouldBufferPcm()
        )
    }

    @Test
    fun localModeForwardsNoPartials() {
        // Local is finals-only in v1: partials would be full re-decodes and the
        // live-partial UI would visibly jump around.
        assertFalse(GlassesSttGate(localMode = true).shouldForwardPartials())
    }

    @Test
    fun everyGateQuestionIsActuallyConsultedByTheService() {
        // An audit found four of these five questions had NO production caller:
        // the service read `localMode` directly, so the tests above were passing
        // against an API nothing used. A test that cannot fail when shipped
        // behaviour changes is worse than no test, because it reads as coverage.
        val src = java.io.File(
            "src/main/java/com/repository/listener/service/ListenerService.kt"
        ).readText()
        for (q in listOf(
            "shouldOpenTranscriberStream",
            "shouldFeedPhoneVad",
            "shouldArmNoSpeechWatchdog",
            "shouldBufferPcm",
            "shouldForwardPartials",
        )) {
            assertTrue(
                "ListenerService must consult $q(); otherwise its test proves nothing",
                src.contains("$q()")
            )
        }
    }

    @Test
    fun theGateDefaultsToRemoteWhenTheGlassesNeverSaidAnything() {
        // An older glasses build, or a BT reconnect before the announcement,
        // must behave exactly as today rather than leaving nobody transcribing.
        assertTrue(GlassesSttGate.default().shouldOpenTranscriberStream())
        assertTrue(GlassesSttGate.default().shouldFeedPhoneVad())
    }
}
