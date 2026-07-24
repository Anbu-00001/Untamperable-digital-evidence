package com.realitylock.app.core.time

import android.os.Build
import android.os.SystemClock

/**
 * Abstraction over Android's clocks, so the correlation logic in
 * [ClockCorrelator] can be unit-tested on a plain JVM without the framework.
 *
 * Three clocks matter to Reality Lock (research/03 §4):
 *  - **wall clock** (`System.currentTimeMillis`): human/legally meaningful, but
 *    can jump backwards or forwards (NTP sync, user edits the date).
 *  - **boot-time / "elapsed realtime"** (`SystemClock.elapsedRealtimeNanos`,
 *    `CLOCK_BOOTTIME`): never jumps and **keeps counting through deep sleep**.
 *    `SensorEvent.timestamp` is stamped against this.
 *  - **uptime** (`SystemClock.uptimeMillis`, `CLOCK_MONOTONIC`): never jumps but
 *    **pauses during deep sleep**.
 *
 * The distinction between the last two is not academic. It was originally
 * assumed here that CameraX's `ImageProxy.imageInfo.timestamp` shared the
 * elapsed-realtime base with sensors. On a OnePlus CPH2591 that assumption is
 * false: the camera reports `SENSOR_INFO_TIMESTAMP_SOURCE = UNKNOWN`, meaning
 * `CLOCK_MONOTONIC`, and the device had accumulated **9.66 days** of deep sleep
 * — so captures were stamped nine days in the past while looking authoritative.
 * See [ClockCorrelator.deepSleepOffsetNanos].
 */
interface ClockSource {
    /** Milliseconds since the Unix epoch (wall clock; may jump). */
    fun wallClockMillis(): Long

    /** Nanoseconds since boot, **including** deep sleep (`CLOCK_BOOTTIME`). */
    fun elapsedRealtimeNanos(): Long

    /** Nanoseconds since boot, **excluding** deep sleep (`CLOCK_MONOTONIC`). */
    fun uptimeNanos(): Long
}

/** Production [ClockSource] backed by the real Android clocks. */
class SystemClockSource : ClockSource {
    override fun wallClockMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    /**
     * `SystemClock.uptimeNanos()` only exists from API 34; below that the
     * millisecond reading is promoted. Millisecond resolution is ample here —
     * this feeds a deep-sleep offset measured in hours or days.
     */
    override fun uptimeNanos(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            SystemClock.uptimeNanos()
        } else {
            SystemClock.uptimeMillis() * ClockCorrelator.NANOS_PER_MILLI
        }
}
