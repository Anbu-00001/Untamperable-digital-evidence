package com.realitylock.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other half of the `GrantPermissionRule` question: can instrumentation grant
 * a permission that is **not already held**?
 *
 * [PermissionGrantInstrumentedTest] can only show that the rule leaves the
 * permission granted — on a device where it was granted beforehand, that proves
 * the rule did not interfere, not that it can grant from scratch. This test
 * revokes first, so the grant has real work to do.
 *
 * Both operations go through `UiAutomation`, where the *instrumentation* holds the
 * privilege, rather than through `adb shell pm`, where the shell user holds it —
 * and on this project's OnePlus the shell user does not have it. That difference
 * is the entire question, and it decides whether Phase 6's Espresso row can run on
 * real hardware or has to move to an emulator.
 *
 * ## Why this is safe to run on a real phone
 *
 * It targets `ACCESS_COARSE_LOCATION` only, restores the permission in [restore]
 * whatever happens, and **skips itself entirely** if the revoke is not permitted —
 * so on a device that refuses, nothing is left changed. If the process were killed
 * mid-test the app would be left without location permission, which is recoverable
 * from Settings, but the revoke happens milliseconds before the restore.
 */
@RunWith(AndroidJUnit4::class)
class PermissionRevokeGrantInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val packageName get() = context.packageName

    private fun held(permission: String) = context.checkSelfPermission(permission)

    @After
    fun restore() {
        // Unconditional: this must run even if an assertion failed, or the device
        // is left in a state the shell cannot repair.
        listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).forEach { permission ->
            runCatching { uiAutomation.grantRuntimePermission(packageName, permission) }
        }
    }

    @Test
    fun instrumentation_can_grant_a_permission_it_first_revoked() {
        val target = Manifest.permission.ACCESS_COARSE_LOCATION

        val revoked = runCatching { uiAutomation.revokeRuntimePermission(packageName, target) }
        assumeTrue(
            "this device does not permit instrumentation to revoke permissions; " +
                "run the from-scratch grant check on an emulator instead",
            revoked.isSuccess && held(target) == PackageManager.PERMISSION_DENIED,
        )

        // Precondition established: the permission really is denied right now.
        assertEquals(PackageManager.PERMISSION_DENIED, held(target))

        uiAutomation.grantRuntimePermission(packageName, target)

        assertEquals(
            "instrumentation could not grant a denied permission — Phase 6's Espresso " +
                "tests cannot self-grant on this hardware",
            PackageManager.PERMISSION_GRANTED,
            held(target),
        )
    }
}
