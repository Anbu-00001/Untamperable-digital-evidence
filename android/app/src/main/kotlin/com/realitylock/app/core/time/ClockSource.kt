package com.realitylock.app.core.time

import android.os.SystemClock

/**
 * Abstraction over Android's two distinct clocks, so the correlation logic in
 * [ClockCorrelator] can be unit-tested on a plain JVM without the framework.
 *
 * Android exposes two clocks that matter to Reality Lock (research/03 §4):
 *  - **wall clock** (`System.currentTimeMillis`): human/legally meaningful, but
 *    can jump backwards or forwards (NTP sync, user edits the date).
 *  - **monotonic clock** (`SystemClock.elapsedRealtimeNanos`): never jumps and
 *    keeps counting through deep sleep. Sensor events and CameraX capture
 *    timestamps are both stamped against THIS clock, which is why it is the
 *    reliable basis for ordering and correlation.
 */
interface ClockSource {
    /** Milliseconds since the Unix epoch (wall clock; may jump). */
    fun wallClockMillis(): Long

    /** Nanoseconds since boot (monotonic; shared base with sensors + CameraX). */
    fun elapsedRealtimeNanos(): Long
}

/** Production [ClockSource] backed by the real Android clocks. */
class SystemClockSource : ClockSource {
    override fun wallClockMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
