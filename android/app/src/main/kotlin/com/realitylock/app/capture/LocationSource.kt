package com.realitylock.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.realitylock.app.capture.model.LocationData
import com.realitylock.app.core.time.ClockCorrelator
import kotlinx.coroutines.tasks.await

/**
 * Supplies the location bound into a proof package.
 *
 * Uses `FusedLocationProviderClient.getCurrentLocation` rather than
 * `requestLocationUpdates` (research/03 §3): the capture needs exactly one
 * accurate fix at the shutter, and the one-shot API manages its own lifecycle
 * so a forgotten callback cannot leak a live location subscription.
 *
 * Callers are responsible for holding `ACCESS_FINE_LOCATION` before invoking
 * these methods — the capture screen requests it explicitly.
 */
class LocationSource(context: Context) {

    private val client =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    /**
     * Requests a fresh high-accuracy fix. Callers should wrap this in a timeout
     * (see [com.realitylock.app.core.config.CaptureConfig.LOCATION_REQUEST_TIMEOUT_MILLIS]);
     * a GPS fix can take several seconds outdoors and longer indoors.
     */
    @SuppressLint("MissingPermission")
    suspend fun awaitCurrentLocation(): Location? {
        val cancellation = CancellationTokenSource()
        return try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token).await()
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * The cached fix, if any. Used to keep the capture screen "warm" so the
     * shutter does not visibly hang waiting on GNSS.
     */
    @SuppressLint("MissingPermission")
    suspend fun lastKnownLocation(): Location? = try {
        client.lastLocation.await()
    } catch (e: SecurityException) {
        null
    }

    companion object {

        /**
         * Maps a platform [Location] to the proof-package model.
         *
         * The fix age is computed against the **monotonic** clock, because
         * `Location.getElapsedRealtimeNanos()` shares the `elapsedRealtimeNanos`
         * base with the camera capture timestamp and sensor events. Using the
         * wall clock here would make the age wrong whenever the system clock
         * was adjusted between the fix and the shutter.
         */
        fun toLocationData(location: Location, captureElapsedRealtimeNanos: Long): LocationData =
            LocationData(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                provider = location.provider,
                fixAgeMillis = fixAgeMillis(location.elapsedRealtimeNanos, captureElapsedRealtimeNanos),
                isMock = location.isMockCompat(),
            )

        /** Milliseconds between a fix and the capture instant, never negative. */
        fun fixAgeMillis(fixElapsedRealtimeNanos: Long, captureElapsedRealtimeNanos: Long): Long {
            val deltaNanos = captureElapsedRealtimeNanos - fixElapsedRealtimeNanos
            if (deltaNanos <= 0L) return 0L
            return deltaNanos / ClockCorrelator.NANOS_PER_MILLI
        }

        /**
         * `Location.isMock()` was added in API 31; below that the equivalent
         * signal is the deprecated `isFromMockProvider`. Either way this is only
         * a first-line check — it can be defeated on a rooted device, which is
         * why research/02 §6 layers additional detection on top.
         */
        @Suppress("DEPRECATION")
        private fun Location.isMockCompat(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider
    }
}
