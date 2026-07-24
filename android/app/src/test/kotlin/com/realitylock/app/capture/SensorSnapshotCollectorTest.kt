package com.realitylock.app.capture

import com.realitylock.app.capture.SensorSnapshotCollector.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
