package com.realitylock.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answers one narrow but load-bearing question: **can an instrumented test grant
 * itself the camera and location permissions on this project's target device?**
 *
 * It matters because the OnePlus CPH2591 (ColorOS) refuses the shell route
 * outright — `adb shell pm grant` fails with
 * `SecurityException: Neither user 2000 nor current process has
 * GRANT_RUNTIME_PERMISSIONS`, and `pm revoke` and `appops set` fail the same way.
 * If `GrantPermissionRule` were also blocked, then **no** instrumented test could
 * ever exercise the capture pipeline on that hardware, and the Phase-6 "UI smoke
 * tests" row would have to be rewritten around an emulator.
 *
 * `GrantPermissionRule` goes through `UiAutomation.grantRuntimePermission()`,
 * which is a different mechanism from the `pm` shell command — the instrumentation
 * is what holds the privilege, not the shell user. This test establishes whether
 * that distinction actually holds in practice rather than assuming it does.
 *
 * Note what this can and cannot show on a device where the permissions are
 * *already* granted: a pass then proves the rule did not throw or interfere, not
 * that it granted from scratch. The from-scratch case is what the emulator run is
 * for, since `pm revoke` works there.
 */
@RunWith(AndroidJUnit4::class)
class PermissionGrantInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun camera_permission_is_held_under_instrumentation() {
        assertEquals(
            "GrantPermissionRule did not leave CAMERA granted",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA),
        )
    }

    @Test
    fun location_permissions_are_held_under_instrumentation() {
        assertEquals(
            "GrantPermissionRule did not leave ACCESS_FINE_LOCATION granted",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
}
