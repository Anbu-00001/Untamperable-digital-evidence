package com.realitylock.app.capture

import com.realitylock.app.core.config.IntegrityConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure location-plausibility math. Expected distances are known
 * reference values (great-circle distances between well-known cities), not
 * numbers derived from the code under test.
 */
class LocationPlausibilityTest {

    // Reference coordinates.
    private val chennai = doubleArrayOf(13.0827, 80.2707)
    private val bangalore = doubleArrayOf(12.9716, 77.5946)
    private val newYork = doubleArrayOf(40.7128, -74.0060)

    @Test
    fun `haversine matches the known Chennai-Bangalore distance`() {
        // Published great-circle distance is ~290 km; allow 1% for the mean-radius model.
        val d = LocationPlausibility.haversineMeters(
            chennai[0], chennai[1], bangalore[0], bangalore[1],
        )
        assertEquals(290_000.0, d, 3_000.0)
    }

    @Test
    fun `haversine matches the known Chennai-New York distance`() {
        // ~13,473 km great-circle (independently computed reference).
        val d = LocationPlausibility.haversineMeters(
            chennai[0], chennai[1], newYork[0], newYork[1],
        )
        assertEquals(13_473_000.0, d, 20_000.0)
    }

    @Test
    fun `distance from a point to itself is zero`() {
        assertEquals(0.0, LocationPlausibility.haversineMeters(chennai[0], chennai[1], chennai[0], chennai[1]), 0.001)
    }

    @Test
    fun `implied speed is distance over time`() {
        // 100 km in 1 hour = 100 km/h.
        assertEquals(100.0, LocationPlausibility.impliedSpeedKmh(100_000.0, 3_600_000L), 0.001)
    }

    // --- plausibility ---

    @Test
    fun `driving across a city in an hour is plausible`() {
        val result = LocationPlausibility.isPlausible(
            chennai[0], chennai[1], prevWallClockMillis = 0L,
            bangalore[0], bangalore[1], wallClockMillis = 3_600_000L, // 290 km in 1 h ≈ 290 km/h...
        )
        // 290 km in 1 h is ~290 km/h — under the 1500 threshold, plausible.
        assertTrue(result == true)
    }

    @Test
    fun `teleporting across the planet in a minute is implausible`() {
        // Chennai to New York (13,800 km) in 60 s ⇒ ~828,000 km/h.
        val result = LocationPlausibility.isPlausible(
            chennai[0], chennai[1], prevWallClockMillis = 0L,
            newYork[0], newYork[1], wallClockMillis = 60_000L,
        )
        assertFalse(result!!)
    }

    @Test
    fun `jet-stream-boosted air travel is not falsely flagged`() {
        // ~1300 km/h ground speed is real on eastbound long-haul flights; must pass.
        // 1300 km covered in 1 hour.
        val startLat = 0.0
        val startLon = 0.0
        // ~1300 km east along the equator ≈ 11.68° of longitude.
        val result = LocationPlausibility.isPlausible(
            startLat, startLon, prevWallClockMillis = 0L,
            0.0, 11.68, wallClockMillis = 3_600_000L,
        )
        assertTrue("1300 km/h air travel must not be flagged as teleportation", result == true)
    }

    @Test
    fun `two near-simultaneous fixes yield no verdict, not a false positive`() {
        // Below the minimum time gap: dividing by ~0 would fake a huge speed.
        val result = LocationPlausibility.isPlausible(
            chennai[0], chennai[1], prevWallClockMillis = 1_000L,
            newYork[0], newYork[1], wallClockMillis = 1_500L, // 500 ms apart
        )
        assertNull("too little time elapsed to judge speed", result)
    }

    @Test
    fun `tiny GPS jitter in place is plausible, not teleportation`() {
        // Same spot, a few metres of scatter, a minute apart.
        val result = LocationPlausibility.isPlausible(
            chennai[0], chennai[1], prevWallClockMillis = 0L,
            chennai[0] + 0.0001, chennai[1] + 0.0001, wallClockMillis = 60_000L,
        )
        assertTrue("sub-threshold movement must be plausible", result == true)
    }

    @Test
    fun `the teleportation threshold is the researched 1500 km per hour`() {
        assertEquals(1_500.0, IntegrityConfig.MAX_PLAUSIBLE_SPEED_KMH, 0.0)
    }
}
