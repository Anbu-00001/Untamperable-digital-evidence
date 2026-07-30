package com.realitylock.app.capture

import android.app.Application
import android.location.Location
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `LocationSource.toLocationData` and its mock-location branch.
 *
 * These need a real `android.location.Location`, which android.jar's stub cannot
 * provide, so [LocationSourceTest] could only ever reach the pure age/staleness
 * helpers. The mapping itself — and the mock flag inside it — had no test at all.
 *
 * The mock flag is the part that matters. It is a **security** signal: it travels
 * inside the signed Merkle root, so a verifier relies on it to know whether a
 * position was fabricated. `isMockCompat()` reads it two different ways —
 * `isMock` from API 31, the deprecated `isFromMockProvider` below that — and the
 * project's only test device is an API 35 phone, so the pre-31 branch has never
 * executed anywhere. A silent failure there would mean spoofed locations sailing
 * through unflagged on every older device.
 *
 * Each test therefore runs at several API levels, straddling the 31 boundary the
 * branch turns on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocationMockDetectionRobolectricTest {

    private fun location(provider: String = "gps") = Location(provider).apply {
        latitude = 12.9128
        longitude = 80.1401
        accuracy = 20f
        elapsedRealtimeNanos = CAPTURE_NANOS
    }

    /**
     * Raises the platform's own mock flag, using whichever setter that API level
     * actually has. This deliberately mirrors the version split in the code under
     * test, but it is not circular: this sets the flag through the *platform*
     * setter and the assertion reads it back through *our* mapping, so a mapping
     * that consulted the wrong field would fail rather than agree with itself.
     */
    private fun Location.flagAsMock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock = true
        } else {
            // The public setter did not arrive until S; before that the flag is
            // only reachable through this hidden method. If a future Robolectric
            // stops exposing it this throws, which is the correct outcome — the
            // alternative is a test that quietly stops proving anything.
            Location::class.java
                .getMethod("setIsFromMockProvider", Boolean::class.javaPrimitiveType)
                .invoke(this, true)
        }
    }

    // --- the mock flag, across the API-31 branch boundary ---

    @Test
    @Config(sdk = [28, 30])
    fun `a mock-flagged location is reported as mock below API 31`() {
        val location = location().apply { flagAsMock() }

        val data = LocationSource.toLocationData(location, CAPTURE_NANOS)

        assertTrue(
            "a fabricated position was NOT flagged on API ${Build.VERSION.SDK_INT}",
            data.isMock,
        )
    }

    @Test
    @Config(sdk = [31, 35])
    fun `a mock-flagged location is reported as mock from API 31`() {
        val location = location().apply { flagAsMock() }

        val data = LocationSource.toLocationData(location, CAPTURE_NANOS)

        assertTrue(
            "a fabricated position was NOT flagged on API ${Build.VERSION.SDK_INT}",
            data.isMock,
        )
    }

    @Test
    @Config(sdk = [28, 30, 31, 35])
    fun `a genuine location is not reported as mock on any supported API`() {
        // The false-positive direction. Without it, a mapping hardwired to `true`
        // would pass every test above while making the signal worthless.
        val data = LocationSource.toLocationData(location(), CAPTURE_NANOS)

        assertFalse(
            "a genuine position was flagged as mock on API ${Build.VERSION.SDK_INT}",
            data.isMock,
        )
    }

    // --- the rest of the mapping ---

    @Test
    fun `the platform fields are carried into the proof model`() {
        val data = LocationSource.toLocationData(location(provider = "fused"), CAPTURE_NANOS)

        assertEquals(12.9128, data.latitude, 1e-9)
        assertEquals(80.1401, data.longitude, 1e-9)
        assertEquals(20f, data.accuracyMeters)
        assertEquals("fused", data.provider)
    }

    @Test
    fun `altitude is null when the fix carries none, rather than zero`() {
        // A fix without altitude must not be recorded as "at sea level" — an
        // absent measurement has to stay distinguishable from a measured one.
        val data = LocationSource.toLocationData(location(), CAPTURE_NANOS)

        assertNull(data.altitudeMeters)
    }

    @Test
    fun `altitude is carried through when the fix has one`() {
        val located = location().apply { altitude = -58.7 }

        val data = LocationSource.toLocationData(located, CAPTURE_NANOS)

        assertEquals(-58.7, data.altitudeMeters!!, 1e-9)
    }

    @Test
    fun `fix age is measured against the monotonic clock, not the wall clock`() {
        val fixNanos = CAPTURE_NANOS - 250L * ClockNanos.PER_MILLI
        val located = location().apply { elapsedRealtimeNanos = fixNanos }

        val data = LocationSource.toLocationData(located, CAPTURE_NANOS)

        assertEquals(250L, data.fixAgeMillis)
    }

    private object ClockNanos {
        const val PER_MILLI: Long = 1_000_000L
    }

    private companion object {
        const val CAPTURE_NANOS: Long = 5_000_000_000L
    }
}
