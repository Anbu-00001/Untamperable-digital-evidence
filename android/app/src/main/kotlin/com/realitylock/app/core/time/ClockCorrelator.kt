package com.realitylock.app.core.time

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Reconciles the device's monotonic clocks with the wall clock (what a human or
 * court reads), per research/03 §4.
 *
 * Why this exists: `SensorEvent.timestamp` and `ImageProxy.imageInfo.timestamp`
 * are monotonic, not wall-clock. Taking one paired reading of both clocks at
 * bundle time yields an offset that converts a monotonic timestamp into
 * wall-clock time.
 *
 * The offset must be sampled close to capture and not cached for long, because
 * the wall clock can be adjusted mid-session; a stale offset would silently skew
 * every derived timestamp.
 *
 * **Two monotonic clocks, not one.** Sensors use `CLOCK_BOOTTIME`
 * (`elapsedRealtimeNanos`, counts through deep sleep). Cameras use whichever
 * base they declare in `SENSOR_INFO_TIMESTAMP_SOURCE`: `REALTIME` means
 * `CLOCK_BOOTTIME`, but `UNKNOWN` means `CLOCK_MONOTONIC`, which *pauses* during
 * deep sleep. Mixing the two silently backdates a capture by the device's
 * accumulated sleep time — observed as a **9.66-day error** on a OnePlus
 * CPH2591. [deepSleepOffsetNanos] measures that gap so
 * [toElapsedRealtimeNanos] can normalise camera timestamps onto the sensor base
 * before any correlation happens.
 */
class ClockCorrelator(private val clock: ClockSource) {

    /**
     * Samples both clocks once and returns `wallClock - monotonic` in millis.
     * Capture this at bundle time and store it in the proof package so a
     * verifier can re-derive the relationship.
     */
    fun snapshotOffsetMillis(): Long =
        clock.wallClockMillis() - (clock.elapsedRealtimeNanos() / NANOS_PER_MILLI)

    /**
     * Converts a monotonic timestamp (sensor event / camera capture) to wall
     * clock using an offset previously obtained from [snapshotOffsetMillis].
     */
    fun toWallClockMillis(elapsedRealtimeNanos: Long, offsetMillis: Long): Long =
        (elapsedRealtimeNanos / NANOS_PER_MILLI) + offsetMillis

    /**
     * Nanoseconds the device has spent in deep sleep since boot — the gap
     * between `CLOCK_BOOTTIME` and `CLOCK_MONOTONIC`.
     *
     * Sample this at bundle time and pass it to [toElapsedRealtimeNanos] to lift
     * a `CLOCK_MONOTONIC` camera timestamp onto the sensor/boot-time base. It is
     * non-decreasing and is zero on a device that has never slept, which is
     * precisely why this bug is invisible on an emulator or a freshly booted
     * handset and severe on a phone that has been idle for days.
     */
    fun deepSleepOffsetNanos(): Long = clock.elapsedRealtimeNanos() - clock.uptimeNanos()

    /**
     * Normalises a raw camera capture timestamp onto the `elapsedRealtimeNanos`
     * (boot-time) base that sensors and [snapshotOffsetMillis] both use.
     *
     * @param rawCaptureNanos `ImageProxy.imageInfo.timestamp`, in whatever base
     *   the camera declares.
     * @param isRealtimeSource true when the camera reported
     *   `SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME`; false for `UNKNOWN`
     *   (`CLOCK_MONOTONIC`), the case that needs correcting.
     * @param deepSleepOffsetNanos from [deepSleepOffsetNanos], sampled at capture.
     */
    fun toElapsedRealtimeNanos(
        rawCaptureNanos: Long,
        isRealtimeSource: Boolean,
        deepSleepOffsetNanos: Long,
    ): Long = if (isRealtimeSource) rawCaptureNanos else rawCaptureNanos + deepSleepOffsetNanos

    /** Current monotonic reading — the ordering-safe timestamp for an event. */
    fun nowElapsedRealtimeNanos(): Long = clock.elapsedRealtimeNanos()

    /** Current wall-clock reading. */
    fun nowWallClockMillis(): Long = clock.wallClockMillis()

    companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * Renders epoch millis as a UTC ISO-8601 instant (e.g.
         * `2026-07-23T09:12:25.678Z`), matching the `date-time` format the
         * proof-package schema requires. java.time is available without
         * desugaring at minSdk 28.
         */
        fun toIso8601Utc(epochMillis: Long): String =
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))
    }
}
