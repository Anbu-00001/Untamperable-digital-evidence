package com.realitylock.app.core.time

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the monotonic/wall-clock correlation described in
 * research/03 §4. This is the subtlest arithmetic in the capture pipeline, so
 * it is covered before any device code depends on it.
 */
class ClockCorrelatorTest {

    /** Controllable [ClockSource] so both clocks can be moved independently. */
    private class FakeClock(
        var wallMillis: Long,
        var monotonicNanos: Long,
    ) : ClockSource {
        override fun wallClockMillis(): Long = wallMillis
        override fun elapsedRealtimeNanos(): Long = monotonicNanos
    }

    @Test
    fun `offset is wall clock minus monotonic millis`() {
        val clock = FakeClock(wallMillis = 1_784_812_345_678L, monotonicNanos = 894_512_000_000_000L)
        val correlator = ClockCorrelator(clock)

        val expected = 1_784_812_345_678L - (894_512_000_000_000L / 1_000_000L)
        assertEquals(expected, correlator.snapshotOffsetMillis())
    }

    @Test
    fun `monotonic timestamp converts back to the wall clock it was sampled with`() {
        val clock = FakeClock(wallMillis = 1_784_812_345_678L, monotonicNanos = 894_512_000_000_000L)
        val correlator = ClockCorrelator(clock)

        val offset = correlator.snapshotOffsetMillis()
        assertEquals(
            clock.wallMillis,
            correlator.toWallClockMillis(clock.monotonicNanos, offset),
        )
    }

    @Test
    fun `a later monotonic reading maps to a proportionally later wall time`() {
        val clock = FakeClock(wallMillis = 1_000_000L, monotonicNanos = 5_000_000_000L)
        val correlator = ClockCorrelator(clock)
        val offset = correlator.snapshotOffsetMillis()

        // 250 ms further along the monotonic clock.
        val laterNanos = clock.monotonicNanos + 250L * ClockCorrelator.NANOS_PER_MILLI
        assertEquals(
            clock.wallMillis + 250L,
            correlator.toWallClockMillis(laterNanos, offset),
        )
    }

    @Test
    fun `offset sampled before a wall-clock jump still converts earlier events correctly`() {
        // This is the reason the proof package stores BOTH the monotonic value
        // and the offset: if the user (or NTP) moves the wall clock after
        // capture, previously captured events must not silently shift.
        val clock = FakeClock(wallMillis = 1_784_812_345_678L, monotonicNanos = 894_512_000_000_000L)
        val correlator = ClockCorrelator(clock)

        val offsetAtCapture = correlator.snapshotOffsetMillis()
        val captureMonotonic = clock.monotonicNanos
        val captureWall = correlator.toWallClockMillis(captureMonotonic, offsetAtCapture)

        // Wall clock jumps forward an hour; monotonic clock is unaffected.
        clock.wallMillis += 3_600_000L
        val offsetAfterJump = correlator.snapshotOffsetMillis()

        // The original event still resolves to its original wall time...
        assertEquals(captureWall, correlator.toWallClockMillis(captureMonotonic, offsetAtCapture))
        // ...and the jump is detectable by comparing offsets.
        assertNotEquals(offsetAtCapture, offsetAfterJump)
        assertEquals(3_600_000L, offsetAfterJump - offsetAtCapture)
    }

    @Test
    fun `iso8601 rendering is UTC and round-trips to the same epoch millis`() {
        val millis = 1_784_812_345_678L
        val iso = ClockCorrelator.toIso8601Utc(millis)

        assertTrue("expected a UTC instant ending in Z, got $iso", iso.endsWith("Z"))
        assertEquals(millis, Instant.parse(iso).toEpochMilli())
    }
}
