package com.realitylock.app.capture

import com.realitylock.app.core.config.IntegrityConfig
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Physical-plausibility check between two consecutive captures: if the implied
 * speed to travel from the previous fix to this one is faster than anything
 * physically possible, the location is suspect ("teleportation").
 *
 * This is the on-device half of the layered defence. `Location.isMock()` catches
 * casual mock apps; this catches a spoofer who jumps between distant coordinates.
 * Neither is sufficient alone (a rooted device can hide the mock flag, and a
 * spoofer who moves plausibly slowly defeats this), which is stated honestly in
 * the report — but together they raise the bar past trivial fake-GPS apps.
 *
 * Pure and framework-free so the arithmetic is unit-tested without Android.
 */
object LocationPlausibility {

    /**
     * Great-circle distance between two WGS-84 points, in metres (Haversine).
     * ~0.3–0.5% worst-case error vs the ellipsoid — negligible for this purpose.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        // asin form; min guards against a rounding overshoot above 1.0.
        val c = 2 * asin(min(1.0, sqrt(a)))
        return IntegrityConfig.EARTH_RADIUS_METERS * c
    }

    /** Implied average speed in km/h for [distanceMeters] covered in [elapsedMillis]. */
    fun impliedSpeedKmh(distanceMeters: Double, elapsedMillis: Long): Double {
        val hours = elapsedMillis / IntegrityConfig.MILLIS_PER_HOUR
        val km = distanceMeters / IntegrityConfig.METERS_PER_KM
        return km / hours
    }

    /**
     * Whether moving between two fixes is physically plausible.
     *
     * Returns null — "not determinable" — rather than a boolean when the two
     * fixes are too close in time or space to yield a meaningful speed. Reporting
     * `false` there would turn GNSS jitter into a phantom integrity failure.
     */
    fun isPlausible(
        prevLat: Double,
        prevLon: Double,
        prevWallClockMillis: Long,
        lat: Double,
        lon: Double,
        wallClockMillis: Long,
        maxSpeedKmh: Double = IntegrityConfig.MAX_PLAUSIBLE_SPEED_KMH,
    ): Boolean? {
        // Wall-clock, not the monotonic clock: two captures can straddle a
        // reboot, which resets elapsedRealtimeNanos and would make the interval
        // meaningless. A wall-clock change between events is itself recorded
        // (wallClockOffsetMillis), so this stays the right basis for real time.
        val dt = wallClockMillis - prevWallClockMillis
        if (dt < IntegrityConfig.MIN_ELAPSED_MILLIS_FOR_SPEED) return null

        val distance = haversineMeters(prevLat, prevLon, lat, lon)
        if (distance < IntegrityConfig.MIN_DISTANCE_METERS_FOR_SPEED) return true

        return impliedSpeedKmh(distance, dt) <= maxSpeedKmh
    }
}
