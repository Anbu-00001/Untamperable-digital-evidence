package com.realitylock.app.core.device

import android.content.Context
import android.content.pm.PackageManager

/**
 * Read-only probe of the hardware features the proof pipeline depends on.
 * Used by the Phase-1 foundation screen to confirm wiring, and later by the
 * capture flow to decide (e.g.) whether a StrongBox-backed key is available
 * or a TEE fallback is required (research/02 §2).
 *
 * These are cheap PackageManager feature checks — no permissions, no crypto,
 * safe to call at any time.
 */
class DeviceCapabilities(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /** Dedicated secure element (StrongBox). Requires API 28+, hence minSdk 28. */
    val hasStrongBox: Boolean
        get() = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    val hasCamera: Boolean
        get() = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    val hasGps: Boolean
        get() = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    val hasAccelerometer: Boolean
        get() = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)

    val hasGyroscope: Boolean
        get() = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
}
