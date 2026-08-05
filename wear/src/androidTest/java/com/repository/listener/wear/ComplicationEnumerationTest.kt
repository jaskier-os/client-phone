package com.repository.listener.wear

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.watchface.complications.data.ComplicationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves, against the real PackageManager on the watch, that this app's
 * complication data source satisfies every condition the platform uses to decide
 * whether a provider is offered in a watch-face picker.
 *
 * This exists because "the component is installed" is NOT the same claim as "the
 * picker will offer it": a provider with a blank label, no icon, the wrong
 * permission or a non-intersecting SUPPORTED_TYPES set is installed and silently
 * skipped. Each assertion below is one of those silent-skip conditions, checked
 * through the same intent query the system itself uses.
 */
@RunWith(AndroidJUnit4::class)
class ComplicationEnumerationTest {

    private companion object {
        const val ACTION =
            "android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST"
        const val BIND_PERMISSION =
            "com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER"
        const val SUPPORTED_TYPES_KEY =
            "android.support.wearable.complications.SUPPORTED_TYPES"
        const val UPDATE_PERIOD_KEY =
            "android.support.wearable.complications.UPDATE_PERIOD_SECONDS"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm: PackageManager = context.packageManager

    private val expected = ComponentName(
        context.packageName,
        LinkComplicationService::class.java.name,
    )

    /**
     * Resolves us the way the system does: by querying the complication update
     * action across all packages, not by looking our own component up directly.
     * A direct getServiceInfo would pass even if the intent filter were wrong.
     */
    private fun resolveViaAction(): ServiceInfo? =
        pm.queryIntentServices(
            Intent(ACTION),
            PackageManager.GET_META_DATA or PackageManager.MATCH_ALL,
        )
            .map { it.serviceInfo }
            .firstOrNull {
                it.packageName == expected.packageName && it.name == expected.className
            }

    @Test
    fun serviceIsEnumeratedByTheComplicationUpdateAction() {
        val info = resolveViaAction()
        assertNotNull(
            "LinkComplicationService is not enumerated for $ACTION. " +
                "The picker cannot offer what it cannot enumerate.",
            info,
        )
    }

    @Test
    fun serviceIsExportedAndGuardedByTheBindPermission() {
        val info = requireNotNull(resolveViaAction())
        // Not exported: the watch-face host cannot bind us at all.
        assertTrue("service is not exported", info.exported)
        // Wrong or missing permission: any app could feed the watch face, so the
        // platform refuses to use us.
        assertEquals(BIND_PERMISSION, info.permission)
    }

    @Test
    fun serviceHasANonEmptyPickerLabel() {
        val info = requireNotNull(resolveViaAction())
        // A provider whose label resolves to empty is dropped from the picker list
        // while still being installed and enumerable, so this is checked directly.
        val label = info.loadLabel(pm).toString()
        assertTrue("picker label is blank", label.isNotBlank())
        assertEquals("Glasses Remote", label)
    }

    @Test
    fun serviceHasItsOwnIconDistinctFromTheApplicationIcon() {
        val info = requireNotNull(resolveViaAction())
        assertTrue("no android:icon on the service", info.icon != 0)
        assertNotNull("icon resource does not load", info.loadIcon(pm))
    }

    @Test
    fun supportedTypesCoverTheTypesTheWatchFaceSlotsCanRender() {
        val info = requireNotNull(resolveViaAction())
        val declared = requireNotNull(info.metaData?.getString(SUPPORTED_TYPES_KEY))
            .split(",")
            .map { it.trim() }
            .toSet()

        assertTrue("SUPPORTED_TYPES is empty", declared.isNotEmpty())
        // SHORT_TEXT and LONG_TEXT are what the round Samsung slots actually
        // render; without at least these two we are absent from every slot on the
        // user's current watch face.
        assertTrue("SHORT_TEXT missing", declared.contains("SHORT_TEXT"))
        assertTrue("LONG_TEXT missing", declared.contains("LONG_TEXT"))
    }

    @Test
    fun updatePeriodIsPushOnlySoNothingWakesOnATimer() {
        val info = requireNotNull(resolveViaAction())
        // Read as an int, not a string: the manifest parser types a purely numeric
        // android:value as an int, so getString returns null for it.
        //
        // Any non-zero value here wakes this service on the watch face's schedule
        // all day. 0 means "we will call the update requester". The -1 default
        // distinguishes "declared as 0" from "not declared at all".
        assertEquals(0, info.metaData?.getInt(UPDATE_PERIOD_KEY, -1))
    }

    @Test
    fun previewDataExistsForEveryDeclaredType() {
        val info = requireNotNull(resolveViaAction())
        val declared = requireNotNull(info.metaData?.getString(SUPPORTED_TYPES_KEY))
            .split(",")
            .map { it.trim() }

        // A null preview removes the source from the picker FOR THAT TYPE. The
        // service delegates to this factory, so exercising the factory catches a
        // declared-but-unhandled type before the user finds it missing.
        val factory = ComplicationDataFactory(context)
        val manifestNameToType = mapOf(
            "SHORT_TEXT" to ComplicationType.SHORT_TEXT,
            "LONG_TEXT" to ComplicationType.LONG_TEXT,
            "RANGED_VALUE" to ComplicationType.RANGED_VALUE,
            "SMALL_IMAGE" to ComplicationType.SMALL_IMAGE,
            "ICON" to ComplicationType.MONOCHROMATIC_IMAGE,
        )

        for (name in declared) {
            val type = requireNotNull(manifestNameToType[name]) {
                "manifest declares unknown type $name"
            }
            assertNotNull(
                "no preview data for declared type $name",
                factory.build(type, LinkState.READY),
            )
        }
    }

    @Test
    fun tapTargetActivityIsResolvable() {
        // The complication is useless if its tap action cannot start anything.
        val intent = Intent(context, ScrollRemoteActivity::class.java)
        assertNotNull(
            "ScrollRemoteActivity does not resolve",
            intent.resolveActivity(pm),
        )
    }
}
