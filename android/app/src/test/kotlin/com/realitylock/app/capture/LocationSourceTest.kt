package com.realitylock.app.capture

import com.realitylock.app.core.config.CaptureConfig
import com.realitylock.app.core.time.ClockCorrelator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure location logic — fix-age arithmetic and the staleness
 * judgement — without the Play Services framework.
 *
 * Fix age is computed on the monotonic clock deliberately: it shares a base
 * with the camera capture timestamp, so the age stays correct even if the
 * system wall clock is adjusted between the fix and the shutter.
 */
class LocationSourceTest {

    private val nanosPerMilli = ClockCorrelator.NANOS_PER_MILLI

    // ---- fix age ----

    @Test
    fun `fix age is the gap between the fix and the shutter`() {
        val fix = 1_000L * nanosPerMilli
        val capture = 3_500L * nanosPerMilli

        assertEquals(2_500L, LocationSource.fixAgeMillis(fix, capture))
    }

    @Test
    fun `a fix taken at the capture instant has zero age`() {
        val instant = 42_000L * nanosPerMilli

        assertEquals(0L, LocationSource.fixAgeMillis(instant, instant))
    }

    @Test
    fun `a fix arriving after the shutter never reports a negative age`() {
        // The fused provider can deliver a fix stamped slightly after the
        // capture instant; a negative age would be meaningless in the proof.
        val fix = 5_000L * nanosPerMilli
        val capture = 4_000L * nanosPerMilli

        assertEquals(0L, LocationSource.fixAgeMillis(fix, capture))
    }

    // ---- staleness ----

    @Test
    fun `a fresh fix is not stale`() {
        assertFalse(LocationSource.isFixStale(fixAgeMillis = 1_500L, maxAgeMillis = 30_000L))
    }

    @Test
    fun `a fix older than the tolerance is stale`() {
        assertTrue(LocationSource.isFixStale(fixAgeMillis = 45_000L, maxAgeMillis = 30_000L))
    }

    @Test
    fun `a fix exactly at the tolerance boundary is not stale`() {
        assertFalse(LocationSource.isFixStale(fixAgeMillis = 30_000L, maxAgeMillis = 30_000L))
    }

    @Test
    fun `an unknown fix age is not reported as stale`() {
        // Absent age means "not recorded", which must not be rendered to the
        // user as a positive staleness claim.
        assertFalse(LocationSource.isFixStale(fixAgeMillis = null))
    }

    @Test
    fun `the default tolerance comes from the capture config`() {
        assertTrue(
            LocationSource.isFixStale(CaptureConfig.LOCATION_MAX_FIX_AGE_MILLIS + 1L),
        )
        assertFalse(
            LocationSource.isFixStale(CaptureConfig.LOCATION_MAX_FIX_AGE_MILLIS),
        )
    }
}
