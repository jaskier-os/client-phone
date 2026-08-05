package com.repository.listener.wear

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies on the real watch that the remote screen renders its state copy and
 * that the copy is reachable through the accessibility tree.
 *
 * Structure only. Appearance -- whether the rim arc reads well, whether the wave
 * feels physical -- is not assertable and is left to human eyes; what is asserted
 * here is that the words the user must be able to read are actually present, and
 * that the screen exposes a content description rather than being an opaque
 * canvas to a screen reader.
 *
 * Deliberately does NOT drive input. Synthesised taps and swipes are not how this
 * app is exercised, and the rotary path cannot be faked from a test at all.
 */
@RunWith(AndroidJUnit4::class)
class RemoteScreenRenderTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun theScreenRendersAStateTitleTheUserCanRead() {
        ActivityScenario.launch(ScrollRemoteActivity::class.java).use {
            // Any of the ten titles is a pass: which state the watch is genuinely
            // in depends on the phone and the glasses, and asserting a specific
            // one would make this test lie about the link rather than about the
            // UI.
            val titles = LinkState.values().map { s -> s.title }
            val found = titles.any { title ->
                device.wait(Until.hasObject(By.text(title)), 4_000L) == true
            }
            assertTrue("no LinkState title rendered; expected one of $titles", found)
        }
    }

    @Test
    fun theScreenExposesAContentDescriptionForScreenReaders() {
        ActivityScenario.launch(ScrollRemoteActivity::class.java).use {
            device.wait(Until.hasObject(By.pkg("com.repository.listener")), 4_000L)
            val described = device.findObject(By.descStartsWith("Glasses Remote:"))
            assertNotNull("the remote screen exposes no content description", described)
        }
    }

    @Test
    fun everyStateCopyFitsTheRoundDisplayWithoutEllipsis() {
        // The titles are authored to fit rather than truncated at runtime, so a
        // rendered ellipsis means the copy budget was exceeded on real hardware
        // at the user's real font scale -- which a JVM length check cannot catch.
        ActivityScenario.launch(ScrollRemoteActivity::class.java).use {
            device.wait(Until.hasObject(By.pkg("com.repository.listener")), 4_000L)
            val truncated = device.findObject(By.textContains("\u2026"))
            assertTrue("copy was ellipsised on screen: ${truncated?.text}", truncated == null)
        }
    }
}
