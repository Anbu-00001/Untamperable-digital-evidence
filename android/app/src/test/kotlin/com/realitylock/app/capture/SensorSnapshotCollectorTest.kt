package com.realitylock.app.capture

import com.realitylock.app.capture.SensorSnapshotCollector.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure sample-selection logic. Choosing the sensor reading nearest the
 * camera's capture timestamp (rather than simply the newest) is what keeps the
 * bound motion data honest when the capture callback runs late — both clocks
 * share the `elapsedRealtimeNanos` base, so the comparison is meaningful.
 */
class SensorSnapshotCollectorTest {

    private fun reading(nanos: Long) = Reading(values = listOf(0f, 0f, 0f), elapsedRealtimeNanos = nanos)

    @Test
    fun `returns null when there are no readings`() {
        assertNull(SensorSnapshotCollector.nearestTo(emptyList(), target = 1_000L))
    }

    @Test
    fun `picks the reading closest to the target instant`() {
        val readings = listOf(reading(1_000L), reading(5_000L), reading(9_000L))

        assertEquals(5_000L, SensorSnapshotCollector.nearestTo(readings, target = 5_400L)?.elapsedRealtimeNanos)
        assertEquals(9_000L, SensorSnapshotCollector.nearestTo(readings, target = 8_000L)?.elapsedRealtimeNanos)
        assertEquals(1_000L, SensorSnapshotCollector.nearestTo(readings, target = 0L)?.elapsedRealtimeNanos)
    }

    @Test
    fun `picks a sample from before the target when that is nearer than any after`() {
        // The newest sample is NOT always the right one: here the shutter fired
        // between two samples and the earlier one is closer.
        val readings = listOf(reading(1_000L), reading(10_000L))

        assertEquals(1_000L, SensorSnapshotCollector.nearestTo(readings, target = 2_000L)?.elapsedRealtimeNanos)
    }

    @Test
    fun `handles a single reading`() {
        val readings = listOf(reading(42L))
        assertEquals(42L, SensorSnapshotCollector.nearestTo(readings, target = 1_000_000L)?.elapsedRealtimeNanos)
    }

    // --- skew tolerance ---
    // Observed on an emulator: the "nearest" sample was 4.6 SECONDS from the
    // shutter. Binding that would misrepresent the motion at capture, so a
    // sample must fall inside a tolerance window to be recorded at all.

    private val oneMillisInNanos = SensorSnapshotCollector.NANOS_PER_MILLI

    @Test
    fun `a sample inside the tolerance window is accepted`() {
        val target = 1_000L * oneMillisInNanos
        val sample = reading(target + 100L * oneMillisInNanos) // 100 ms away

        assertTrue(
            SensorSnapshotCollector.isWithinSkew(
                sample,
                target,
                maxSkewNanos = 500L * oneMillisInNanos,
            ),
        )
    }

    @Test
    fun `a sample beyond the tolerance window is rejected`() {
        val target = 1_000L * oneMillisInNanos
        val sample = reading(target + 4_600L * oneMillisInNanos) // the 4.6 s case

        assertFalse(
            SensorSnapshotCollector.isWithinSkew(
                sample,
                target,
                maxSkewNanos = 500L * oneMillisInNanos,
            ),
        )
    }

    @Test
    fun `tolerance is symmetric around the capture instant`() {
        val target = 10_000L * oneMillisInNanos
        val maxSkewNanos = 500L * oneMillisInNanos

        val before = reading(target - 400L * oneMillisInNanos)
        val after = reading(target + 400L * oneMillisInNanos)
        val tooEarly = reading(target - 900L * oneMillisInNanos)

        assertTrue(SensorSnapshotCollector.isWithinSkew(before, target, maxSkewNanos))
        assertTrue(SensorSnapshotCollector.isWithinSkew(after, target, maxSkewNanos))
        assertFalse(SensorSnapshotCollector.isWithinSkew(tooEarly, target, maxSkewNanos))
    }

    @Test
    fun `a sample exactly at the tolerance boundary is accepted`() {
        val target = 5_000L * oneMillisInNanos
        val maxSkewNanos = 500L * oneMillisInNanos
        val boundary = reading(target + maxSkewNanos)

        assertTrue(SensorSnapshotCollector.isWithinSkew(boundary, target, maxSkewNanos))
    }
}
