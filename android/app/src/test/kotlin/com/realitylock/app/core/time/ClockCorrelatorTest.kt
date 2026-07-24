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

    /** Controllable [ClockSource] so all three clocks move independently. */
    private class FakeClock(
        var wallMillis: Long,
        var monotonicNanos: Long,
        /** Defaults to no accumulated deep sleep (boot-time == uptime). */
        var uptimeNanosValue: Long = monotonicNanos,
    ) : ClockSource {
        override fun wallClockMillis(): Long = wallMillis
        override fun elapsedRealtimeNanos(): Long = monotonicNanos
        override fun uptimeNanos(): Long = uptimeNanosValue
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

    // ---- camera timestamp base normalisation ----
    // Regression cover for a defect found on a real OnePlus CPH2591: its camera
    // reports SENSOR_INFO_TIMESTAMP_SOURCE = UNKNOWN (CLOCK_MONOTONIC), which
    // pauses during deep sleep. Treating that as boot-time stamped a capture
    // 9.66 days in the past while looking entirely authoritative.

    /** Deep sleep the observed device had accumulated: 834,704 s ≈ 9.66 days. */
    private val observedDeepSleepNanos = 834_704L * 1_000L * ClockCorrelator.NANOS_PER_MILLI

    @Test
    fun `deep sleep offset is boot time minus uptime`() {
        val clock = FakeClock(
            wallMillis = 1_784_909_736_000L,
            monotonicNanos = 1_956_852_000_000_000L,
            uptimeNanosValue = 1_122_148_000_000_000L,
        )

        assertEquals(
            1_956_852_000_000_000L - 1_122_148_000_000_000L,
            ClockCorrelator(clock).deepSleepOffsetNanos(),
        )
    }

    @Test
    fun `a realtime-source camera timestamp is used unchanged`() {
        val correlator = ClockCorrelator(FakeClock(0L, 0L))
        val raw = 1_956_852_000_000_000L

        assertEquals(
            raw,
            correlator.toElapsedRealtimeNanos(
                rawCaptureNanos = raw,
                isRealtimeSource = true,
                deepSleepOffsetNanos = observedDeepSleepNanos,
            ),
        )
    }

    @Test
    fun `a monotonic-source camera timestamp is lifted onto the boot-time base`() {
        val correlator = ClockCorrelator(FakeClock(0L, 0L))
        val rawMonotonic = 1_122_148_000_000_000L

        assertEquals(
            rawMonotonic + observedDeepSleepNanos,
            correlator.toElapsedRealtimeNanos(
                rawCaptureNanos = rawMonotonic,
                isRealtimeSource = false,
                deepSleepOffsetNanos = observedDeepSleepNanos,
            ),
        )
    }

    @Test
    fun `the observed 9-day backdating does not happen once normalised`() {
        // Reproduces the real capture: wall clock 2026-07-24T16:15:36Z, boot-time
        // 1,956,852 s, camera timestamp on CLOCK_MONOTONIC at 1,122,148 s.
        val nowWallMillis = 1_784_909_736_000L
        val clock = FakeClock(
            wallMillis = nowWallMillis,
            monotonicNanos = 1_956_852_000_000_000L,
            uptimeNanosValue = 1_122_148_000_000_000L,
        )
        val correlator = ClockCorrelator(clock)
        val rawCameraNanos = 1_122_148_000_000_000L

        val offset = correlator.snapshotOffsetMillis()

        // Before the fix: the raw value was fed straight in.
        val buggyWallMillis = correlator.toWallClockMillis(rawCameraNanos, offset)
        val errorDays = (nowWallMillis - buggyWallMillis).toDouble() / MILLIS_PER_DAY
        assertTrue(
            "expected the unnormalised path to be ~9.7 days off, was $errorDays days",
            errorDays > 9.0 && errorDays < 10.0,
        )

        // After the fix: the capture resolves to now.
        val corrected = correlator.toElapsedRealtimeNanos(
            rawCaptureNanos = rawCameraNanos,
            isRealtimeSource = false,
            deepSleepOffsetNanos = correlator.deepSleepOffsetNanos(),
        )
        assertEquals(nowWallMillis, correlator.toWallClockMillis(corrected, offset))
    }

    @Test
    fun `normalisation is a no-op on a device that has never slept`() {
        // Why the bug was invisible on an emulator and on a freshly booted phone.
        val monotonic = 5_000_000_000L
        val clock = FakeClock(wallMillis = 1_000_000L, monotonicNanos = monotonic)
        val correlator = ClockCorrelator(clock)

        assertEquals(0L, correlator.deepSleepOffsetNanos())
        assertEquals(
            monotonic,
            correlator.toElapsedRealtimeNanos(monotonic, isRealtimeSource = false, deepSleepOffsetNanos = 0L),
        )
    }

    @Test
    fun `iso8601 rendering is UTC and round-trips to the same epoch millis`() {
        val millis = 1_784_812_345_678L
        val iso = ClockCorrelator.toIso8601Utc(millis)

        assertTrue("expected a UTC instant ending in Z, got $iso", iso.endsWith("Z"))
        assertEquals(millis, Instant.parse(iso).toEpochMilli())
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
