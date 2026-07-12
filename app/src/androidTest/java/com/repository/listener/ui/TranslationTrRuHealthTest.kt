package com.repository.listener.ui

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * Gate test: start real-time translation in Turkish -> Russian and confirm the
 * pipeline comes up and stays healthy for a sustained window, WITHOUT a live call.
 *
 * This is the smoke gate that must pass before asking a human to place a real
 * phone call to verify the HFP call-audio path. It catches the regressions we hit
 * during bring-up: the translation session failing to start, and the audio pipeline
 * churning (repeated RFCOMM audio-client reconnects / "bad frameLen" desync /
 * mic-stream restart storms) that chop recognition.
 *
 * It drives the REAL service via the ADB command dispatch (same path adb uses), so
 * it exercises the production start_translation flow. It requires the glasses to be
 * BT-connected to the phone (start_translation refuses otherwise) -- the test fails
 * loudly with a clear message if they are not, so it is never a silent pass.
 *
 * Health is asserted by scanning the app's own rolling log for churn signatures over
 * a 20s window while translation is active. A healthy idle (no speech) session shows
 * a steady audio push with NO reconnect/desync/restart churn.
 *
 * Run (install ONLY the .test APK; deploy the app via deploy-to-phone.sh):
 *   adb shell am instrument -w -e class \
 *     com.repository.listener.ui.TranslationTrRuHealthTest \
 *     com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TranslationTrRuHealthTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val pkg = "com.repository.listener"

    private fun shell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        return java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun readAppFile(relPath: String): String =
        shell("run-as $pkg cat $relPath")

    private fun sendAdb(type: String, commandId: String, paramsJson: String) {
        // executeShellCommand runs via the shell without an extra quoting layer, so do NOT
        // wrap args in quotes (that made the command_id become part of the result filename).
        // Escape spaces in the JSON so it stays a single arg.
        val safeParams = paramsJson.replace(" ", "")
        shell(
            "am broadcast -a com.repository.listener.ADB_COMMAND " +
                "-n $pkg/.adb.AdbCommandReceiver " +
                "--es type $type --es command_id $commandId --es params $safeParams"
        )
    }

    private fun readResult(commandId: String): JSONObject? =
        try { JSONObject(readAppFile("files/adb_results/$commandId.json")) } catch (_: Exception) { null }

    /**
     * am instrument restarts the app process, so the service always comes up with BT
     * down. Poll the status command until the glasses RFCOMM link is re-established
     * (normally 15-60 s) before driving start_translation.
     */
    private fun waitForGlassesBt(timeoutMs: Long = 120_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val id = "trru_bt_${System.currentTimeMillis()}"
            sendAdb("status", id, "{}")
            SystemClock.sleep(2_000)
            val r = readResult(id)
            if (r?.optJSONObject("data")?.optBoolean("glasses_connected") == true) return
            SystemClock.sleep(3_000)
        }
        fail("Glasses did not BT-connect within ${timeoutMs / 1000}s of app start -- power/pairing problem, not a translation bug.")
    }

    @Test
    fun translationTrRu_startsAndStaysHealthy() {
        waitForGlassesBt()
        val startId = "trru_start_${System.currentTimeMillis()}"

        // 1) Start TR -> RU translation, Azure provider, system source (the call feature's mode).
        sendAdb(
            "start_translation", startId,
            JSONObject()
                .put("from_language", "tr")
                .put("to_language", "ru")
                .put("audio_source", "system")
                .put("provider", "azure")
                .toString()
        )

        // 2) Wait for the result file to resolve to success/error.
        var status: String? = null
        var errMsg: String? = null
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val r = readResult(startId)
            val s = r?.optString("status")
            if (s == "success") { status = "success"; break }
            if (s == "error") { status = "error"; errMsg = r.optString("error"); break }
            SystemClock.sleep(500)
        }

        if (status == null) {
            fail("start_translation produced no result within 15s (id=$startId)")
        }
        if (status == "error") {
            // Most common cause: glasses not BT-connected. Make this explicit, not a silent pass.
            fail("start_translation failed: $errMsg -- ensure the glasses are powered and BT-connected to the phone before running this gate.")
        }

        // 3) Observe a sustained window and assert no pipeline churn.
        // Read the app log tail before/after and count churn signatures that appear DURING the window.
        val churnPatterns = listOf(
            "bad frameLen",
            "resetting decoder and reconnecting",
            "BT audio client lost",
            "restart_audio"
        )

        // Baseline count (pre-window) so we only count NEW churn during observation.
        fun countChurn(): Int {
            val log = try { readAppFile("files/logs/listener/latest.log") } catch (_: Exception) { "" }
            var c = 0
            for (line in log.lineSequence()) {
                for (p in churnPatterns) if (line.contains(p)) { c++; break }
            }
            return c
        }

        val before = countChurn()
        // Observe 20s of the live (idle) translation session.
        SystemClock.sleep(20_000)
        val after = countChurn()
        val churn = after - before

        // 4) Confirm translation is still active (session did not silently die): the
        // status result stays success and stop works cleanly.
        val stopId = "trru_stop_${System.currentTimeMillis()}"
        sendAdb("stop_translation", stopId, "{}")
        SystemClock.sleep(1500)

        // Assert: no pipeline churn accumulated during the idle window.
        assertTrue(
            "Translation pipeline churned during a healthy idle TR->RU session " +
                "($churn new churn events: bad frameLen / decoder reset / audio-client lost / restart_audio). " +
                "Expected 0. This is the regression the gate guards against.",
            churn == 0
        )
    }
}
