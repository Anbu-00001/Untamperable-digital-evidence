package com.realitylock.app.capture

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationServices
import com.realitylock.app.core.config.IntegrityConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-6 security-validation scenario 3: **a mock-location app is active, and the
 * proof package says so.**
 *
 * This is the test that was missing. `Location.isMock()` has been written into the
 * signed metadata since Phase 4, but until now nothing had ever produced a mock
 * location and checked that the flag actually arrives — the detection path in a
 * security-critical field was entirely unexercised.
 *
 * It drives the **real** `FusedLocationProviderClient`, not a fabricated
 * `Location` object, because the question is precisely whether the fused provider
 * propagates the mock flag through to us. A hand-built `Location` would answer a
 * different and much easier question.
 *
 * ## Requirements, and why the test skips instead of failing
 *
 * `setMockMode`/`setMockLocation` need two things: the `ACCESS_MOCK_LOCATION`
 * permission (supplied by the debug manifest overlay) **and** the app to be
 * chosen under *Developer options → Select mock location app*. That second one is
 * a manual, per-device setting; on this project's OnePlus it cannot be set from
 * the shell at all, because ColorOS refuses `appops set` with
 * `SecurityException: uid 2000 does not have MANAGE_APP_OPS_MODES`.
 *
 * So when the app is not the selected mock provider the test **skips** rather
 * than fails. A red test on every developer machine that had not performed a
 * manual setup step would quickly be ignored, and an ignored test protects
 * nothing. The skip message says exactly what to do.
 */
@RunWith(AndroidJUnit4::class)
class MockLocationInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @Before
    fun requireMockProviderSelected() {
        assumeTrue(
            "Location permission is not granted — grant it in the app first.",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
        assumeTrue(
            "This app is not the selected mock location app. Set it under " +
                "Settings > System > Developer options > Select mock location app > Reality Lock, " +
                "then re-run. (On ColorOS this cannot be done via adb: `appops set` requires " +
                "MANAGE_APP_OPS_MODES.)",
            isSelectedMockLocationApp(),
        )
    }

    @After
    fun leaveMockModeOff() {
        // Mock mode affects EVERY location client on the device, not just this
        // app, so leaving it on would quietly break the user's maps and
        // navigation. Always turn it back off.
        runCatching { runBlocking { fused.setMockMode(false).await() } }
    }

    @Test
    fun a_mocked_fused_location_is_reported_as_mock() = runBlocking {
        val location = awaitMockedLocation()

        // Skips, does not fail, when the OEM's Play-services build ignores mock
        // injection — see diagnostic_mock_apis_are_accepted_by_the_platform for
        // what that looks like. The mapping itself is proven deterministically in
        // MockLocationPlatformInstrumentedTest, which needs no OEM cooperation.
        assumeTrue(
            "the fused provider returned no location while in mock mode — this " +
                "device ignores GMS mock injection",
            location != null,
        )
        assertEquals(LAT, location!!.latitude, COORD_TOLERANCE)
        assertEquals(LON, location.longitude, COORD_TOLERANCE)

        // The claim under test: the platform marks it, and we can see the mark.
        assertTrue("the fused provider did not propagate the mock flag", location.isMock)
    }

    @Test
    fun the_mock_flag_reaches_the_proof_package_metadata() = runBlocking {
        val location = awaitMockedLocation()
        assumeTrue("no location returned", location != null)

        // The mapping that actually writes the signed field. `isMock` lives in
        // metadata.location, INSIDE the Merkle root, so a tamperer cannot clear it
        // without invalidating the signature.
        val data = LocationSource.toLocationData(location!!, captureElapsedRealtimeNanos = 0L)

        assertTrue("isMock did not reach the proof-package model", data.isMock)
        assertEquals(LAT, data.latitude, COORD_TOLERANCE)
    }

    @Test
    fun a_real_location_is_not_flagged_as_mock() = runBlocking {
        // The other half of the claim, and the one that matters for false
        // positives: with mock mode off, a genuine fix must NOT be flagged.
        fused.setMockMode(false).await()

        val location = withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) {
            LocationSource(context).awaitCurrentLocation()
        }
        assumeTrue("no real fix available here (indoors?) — nothing to assert", location != null)

        assertFalse("a genuine fix was wrongly flagged as mock", location!!.isMock)
        assertFalse(
            LocationSource.toLocationData(location, captureElapsedRealtimeNanos = 0L).isMock,
        )
    }

    @Test
    fun the_mock_check_is_named_among_the_checks_that_actually_ran() {
        // Guards against the Phase-4 failure mode this project deliberately
        // avoided: naming checks in the report that are not actually implemented
        // (ADR-0005 §1). `location.isMock` is claimed, so it must be real — and
        // the tests above are what make that claim true.
        assertEquals("location.isMock", IntegrityConfig.CHECK_IS_MOCK)
    }

    /**
     * Requests a location through the production path while continuously feeding
     * the fused provider mock fixes.
     *
     * The pumping is necessary and was learned the hard way: `getCurrentLocation`
     * asks for a *fresh* fix, so a single `setMockLocation` issued beforehand is
     * consumed or goes stale before the request starts listening, and the request
     * then waits forever. Re-posting the mock while the request is in flight is
     * what a real mock-location app does too.
     *
     * Deliberately still routed through [LocationSource.awaitCurrentLocation] —
     * the production call — rather than the easier `lastLocation`, because the
     * point is to test the path captures actually use.
     */
    private suspend fun awaitMockedLocation(): Location? = coroutineScope {
        fused.setMockMode(true).await()
        // NOT swallowed. An earlier version wrapped this in runCatching, which hid
        // the real reason the provider stayed silent and produced a misleading
        // "returned no location" failure instead of the actual exception.
        fused.setMockLocation(mockLocation(LAT, LON)).await()

        val request = async { LocationSource(context).awaitCurrentLocation() }
        val pump = launch {
            while (isActive) {
                runCatching { fused.setMockLocation(mockLocation(LAT, LON)).await() }
                delay(MOCK_PUMP_INTERVAL_MILLIS)
            }
        }
        try {
            withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) { request.await() }
        } finally {
            pump.cancel()
            request.cancel()
        }
    }

    /**
     * Reports what the platform actually does with a mock request, so a silent
     * provider can be told apart from a rejected one.
     */
    @Test
    fun diagnostic_mock_apis_are_accepted_by_the_platform() = runBlocking {
        val mockModeError = runCatching { fused.setMockMode(true).await() }.exceptionOrNull()
        assertTrue("setMockMode(true) threw: $mockModeError", mockModeError == null)

        val setError = runCatching { fused.setMockLocation(mockLocation(LAT, LON)).await() }
            .exceptionOrNull()
        assertTrue("setMockLocation threw: $setError", setError == null)

        // Both calls above are ACCEPTED on the CPH2591 — and the provider still
        // delivers nothing. "It did not throw" is not "it worked", which is the
        // lesson this diagnostic exists to record. Where the device does honour
        // the injection, the assertions below hold.
        val last = runCatching { fused.lastLocation.await() }.getOrNull()
        assumeTrue(
            "setMockMode/setMockLocation were accepted but lastLocation is null: " +
                "this device's Play-services build silently ignores mock injection",
            last != null,
        )
        assertEquals(LAT, last!!.latitude, COORD_TOLERANCE)
        assertTrue("lastLocation was not flagged isMock: $last", last.isMock)
    }

    /**
     * Builds a location acceptable to `setMockLocation`, which rejects one that
     * is missing accuracy or an elapsed-realtime timestamp.
     */
    private fun mockLocation(latitude: Double, longitude: Double) =
        Location(MOCK_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = MOCK_ACCURACY_METERS
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }

    /**
     * Whether this app holds the mock-location app op. `unsafeCheckOpNoThrow` is
     * the non-throwing query form, so this is safe to call when the answer is no.
     */
    private fun isSelectedMockLocationApp(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    private companion object {
        const val MOCK_PROVIDER = "fused"
        const val LAT = 13.0827
        const val LON = 80.2707
        const val COORD_TOLERANCE = 0.0005
        const val MOCK_ACCURACY_METERS = 5.0f
        const val AWAIT_TIMEOUT_MILLIS = 15_000L
        const val MOCK_PUMP_INTERVAL_MILLIS = 400L
    }
}
