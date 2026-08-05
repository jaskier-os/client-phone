package com.repository.listener.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.repository.listener.protocol.RemoteInputProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decodes real wire bytes on the device, through the shipped protocol class.
 *
 * The JVM tests build the bit pattern by calling the same encoder they verify, so
 * they would still pass if the watch and the glasses disagreed about the layout.
 * This one starts from the BYTE the glasses actually put on the wire.
 */
@RunWith(AndroidJUnit4::class)
class RefusalWireTest {

    private fun healthy(): Int {
        val f = RemoteInputProtocol.StatusFlags
        return f.GLASSES_LINK_UP or f.PHONE_SERVICE_ALIVE or f.GLASSES_SINK_ATTACHED
    }

    private fun decode(payload: ByteArray) = LinkState.fromStatus(
        bits = RemoteInputProtocol.StatusFlags.decode(payload),
        bluetoothOn = true, phoneNodeKnown = true,
        everSawPhoneNode = true, statusFresh = true,
    )

    @Test
    fun eachReasonSurvivesTheWireAsItsOwnState() {
        val f = RemoteInputProtocol.StatusFlags
        for (reason in RemoteInputProtocol.RefusalReason.values()) {
            val bits = f.encodeReason(healthy() or f.GLASSES_REFUSING_INPUT, reason)
            val state = decode(byteArrayOf(bits.toByte()))
            assertTrue("$reason did not decode to a refusal: $state", state.isRefusal)
            assertTrue("$reason claimed input was confirmed", !state.inputConfirmed)
        }
    }

    @Test
    fun aHealthyByteWithNoRefusalIsReady() {
        assertEquals(LinkState.READY, decode(byteArrayOf(healthy().toByte())))
    }

    @Test
    fun theReasonBitsDoNotDisturbTheLegacyFlags() {
        // The reason was appended ABOVE the legacy bits so an older reader is
        // unaffected. Prove the low bits still mean what they meant.
        val f = RemoteInputProtocol.StatusFlags
        val bits = f.encodeReason(
            healthy() or f.GLASSES_REFUSING_INPUT,
            RemoteInputProtocol.RefusalReason.LOCKED,
        )
        assertTrue(f.isSet(bits, f.GLASSES_LINK_UP))
        assertTrue(f.isSet(bits, f.PHONE_SERVICE_ALIVE))
        assertTrue(f.isSet(bits, f.GLASSES_SINK_ATTACHED))
        assertTrue("a health bit was clobbered by the reason", !f.isSet(bits, f.LAST_SEND_DROPPED))
    }
}
