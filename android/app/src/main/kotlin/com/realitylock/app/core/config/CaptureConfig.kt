package com.realitylock.app.core.config

import android.hardware.SensorManager

/**
 * Centralized tuning parameters for the capture pipeline. Every value the
 * capture code depends on lives here rather than as a literal at the call site,
 * so behaviour can be tuned (and reviewed) in one place.
 *
 * Rationale for the choices: research/03 §2–§4.
 */
object CaptureConfig {

    /** MIME type of stills produced by the CameraX ImageCapture use case. */
    const val MIME_TYPE_JPEG: String = "image/jpeg"

    /** App-private subdirectory holding captured media. */
    const val MEDIA_SUBDIR: String = "captures"

    /** File extension for captured stills. */
    const val MEDIA_EXTENSION_JPEG: String = ".jpg"

    /**
     * Sensor sampling rate. SENSOR_DELAY_GAME (~20 ms) is fast enough to have a
     * fresh accelerometer/gyroscope reading at the shutter without the battery
     * cost of SENSOR_DELAY_FASTEST.
     */
    val SENSOR_SAMPLING_PERIOD: Int = SensorManager.SENSOR_DELAY_GAME

    /**
     * How many recent sensor samples to retain. Sensors are event-driven (there
     * is no "read now" call), so the collector keeps a small rolling buffer and
     * the capture takes the most recent sample.
     */
    const val MOTION_BUFFER_SIZE: Int = 64

    /**
     * A location fix older than this is considered too stale to bind to a
     * capture; the UI surfaces it rather than silently attaching bad data.
     */
    const val LOCATION_MAX_FIX_AGE_MILLIS: Long = 30_000L

    /** Upper bound on waiting for a fresh high-accuracy fix at capture time. */
    const val LOCATION_REQUEST_TIMEOUT_MILLIS: Long = 10_000L
}
