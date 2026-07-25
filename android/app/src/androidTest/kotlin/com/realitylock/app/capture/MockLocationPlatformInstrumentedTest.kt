package com.realitylock.app.capture

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-6 security-validation scenario 3, via the **platform** `LocationManager`
 * test-provider API rather than Play services.
 *
 * ## Why this exists alongside [MockLocationInstrumentedTest]
 *
 * On the project's OnePlus CPH2591 (ColorOS), Play services' own mock API is
 * accepted and then **silently ignored**: `setMockMode(true)` and
 * `setMockLocation(...)` both complete without throwing, and `lastLocation` still
 * returns null. That is a genuinely useful finding — "the call did not throw" is
 * not "the call worked" — but it means the GMS route cannot demonstrate our
 * detection on this hardware.
 *
 * `LocationManager.addTestProvider` is the platform mechanism underneath, and it
 * is what sets the flag that `Location.isMock()` reports. Since the thing under
 * test is **our** code — that we read that flag and carry it into the signed
 * metadata — testing against the platform provider tests exactly the right thing,
 * without depending on an OEM's Play-services behaviour.
 *
 * Requires the app to be the selected mock location app (Developer options), which
 * is also what `addTestProvider` checks; skips cleanly when it is not.
 */
@RunWith(AndroidJUnit4::class)
class MockLocationPlatformInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var providerAdded = false

    @After
    fun removeTestProvider() {
        // Leaving a test provider registered would corrupt real location for every
        // app on the device until reboot.
        if (providerAdded) {
            runCatching { locationManager.removeTestProvider(PROVIDER) }
            providerAdded = false
        }
    }

    private fun addProvider(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val allowed = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        if (!allowed) return false

        return runCatching {
            // Replace any stale registration from an interrupted run.
            runCatching { locationManager.removeTestProvider(PROVIDER) }
            locationManager.addTestProvider(
                PROVIDER,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ false,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE,
            )
            locationManager.setTestProviderEnabled(PROVIDER, true)
            providerAdded = true
            true
        }.getOrDefault(false)
    }

    private fun mockLocation() = Location(PROVIDER).apply {
        latitude = LAT
        longitude = LON
        accuracy = ACCURACY_METERS
        altitude = 0.0
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    /**
     * The deterministic version, and the one that actually pins our contract.
     *
     * `Location.setMock(boolean)` is public API from API 31, so a mock-flagged
     * `Location` can be built directly. That is the right scope: **our** job is to
     * read the flag and carry it into the signed metadata; setting the flag is the
     * platform's job. Whether a particular OEM's Play-services build honours
     * third-party mock injection is that OEM's behaviour, not our correctness, and
     * making our test depend on it made the test flaky rather than strict.
     *
     * Runs on every device, needs no Developer-options setup, and fails loudly if
     * anyone ever drops `isMock` from the mapping.
     */
    @Test
    fun a_mock_flagged_location_reaches_the_signed_metadata() {
        val flagged = mockLocation().apply { setMock(true) }
        assumeTrue("Location.setMock had no effect on this platform", flagged.isMock)

        // isMock sits in metadata.location, INSIDE the signed Merkle root, so it
        // cannot be cleared afterwards without invalidating the signature.
        val data = LocationSource.toLocationData(flagged, captureElapsedRealtimeNanos = 0L)

        assertTrue("isMock did not reach the proof-package model", data.isMock)
        assertEquals(LAT, data.latitude, COORD_TOLERANCE)
        assertEquals(LON, data.longitude, COORD_TOLERANCE)
    }

    @Test
    fun an_unflagged_location_is_not_reported_as_mock() {
        // The false-positive direction: the signal is worthless if it fires on
        // genuine fixes.
        val plain = mockLocation()
        assertFalse(plain.isMock)
        assertFalse(
            LocationSource.toLocationData(plain, captureElapsedRealtimeNanos = 0L).isMock,
        )
    }

    /**
     * Attempts real end-to-end injection through the platform's test-provider API.
     *
     * Skips where the device will not deliver it. On the CPH2591 `addTestProvider`
     * and `setTestProviderLocation` both succeed and `getLastKnownLocation` then
     * returns null — ColorOS accepts the calls and ignores them, exactly as its
     * Play-services build does. That is worth recording, but it is an OEM
     * limitation, not a defect in this project, so it must not fail the build.
     */
    @Test
    fun injected_platform_location_is_flagged_where_the_device_permits_it() {
        assumeTrue(
            "Not the selected mock location app, or the platform refused " +
                "addTestProvider. Set Settings > System > Developer options > " +
                "Select mock location app > Reality Lock.",
            addProvider(),
        )

        locationManager.setTestProviderLocation(PROVIDER, mockLocation())
        val injected = locationManager.getLastKnownLocation(PROVIDER)

        assumeTrue(
            "this device accepted the test provider but delivered no location " +
                "(ColorOS ignores mock injection) — the mapping is covered by " +
                "a_mock_flagged_location_reaches_the_signed_metadata instead",
            injected != null,
        )

        assertEquals(LAT, injected!!.latitude, COORD_TOLERANCE)
        assertTrue("the platform did not flag an injected location as mock", injected.isMock)
        assertTrue(
            LocationSource.toLocationData(injected, captureElapsedRealtimeNanos = 0L).isMock,
        )
    }

    @Test
    fun a_location_from_a_real_provider_is_not_flagged() {
        // The false-positive direction. A genuine fix must not be flagged, or the
        // signal would be worthless.
        val real = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        assumeTrue("no real fix cached on this device — nothing to assert", real != null)
        assumeTrue("cached fix is itself mock (a mock app is active) — skip", !real!!.isMock)

        assertFalse(
            "a genuine fix was wrongly flagged as mock",
            LocationSource.toLocationData(real, captureElapsedRealtimeNanos = 0L).isMock,
        )
    }

    private companion object {
        // A distinct provider name, so the test never disturbs the real "gps" or
        // "fused" providers the device relies on.
        const val PROVIDER = "reality_lock_test_provider"
        const val LAT = 13.0827
        const val LON = 80.2707
        const val COORD_TOLERANCE = 0.0005
        const val ACCURACY_METERS = 5.0f
    }
}
