package com.realitylock.app.capture

import android.content.Context
import android.location.LocationManager
import android.os.Build

/**
 * Reports whether the device *could* support raw-GNSS spoofing analysis
 * (per-satellite C/N0 and AGC). It does **not** perform that analysis.
 *
 * This is a deliberate, honest scope decision (Phase-4 research): a real GNSS
 * spoofing check needs outdoor multi-satellite captures and the AGC field, which
 * is optional and frequently unpopulated on consumer phones — not something two
 * students can demonstrate reliably at a desk. So Phase 4 ships only this
 * capability probe as a diagnostic and future-work marker, and the proof package
 * does **not** claim GNSS integrity was verified (`integrity.location.gnssChecked`
 * stays false). Building the probe but not overclaiming the check is the honest
 * middle path.
 */
class GnssCapabilityProbe(context: Context) {

    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * True when the platform reports raw GNSS measurements are available.
     * `GnssCapabilities.hasMeasurements()` is API 31+; below that we cannot ask,
     * so the capability is reported as unknown-false.
     */
    val supportsRawMeasurements: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { locationManager.gnssCapabilities.hasMeasurements() }.getOrDefault(false)
        } else {
            false
        }
}
