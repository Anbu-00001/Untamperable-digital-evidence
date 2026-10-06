package com.realitylock.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seal animation draws these numbers, so they must be exactly the elapsed time. */
class StageTrackerTest {

    private var clock = 0L
    private val tracker = StageTracker { clock }

    @Test
    fun `a stage lasts until the next one begins`() {
        tracker.begin(CaptureStage.SHUTTER); clock = 600
        tracker.begin(CaptureStage.LOCATE); clock = 10_600
        tracker.begin(CaptureStage.HASH); clock = 10_627
        tracker.begin(CaptureStage.SIGN); clock = 10_653
        tracker.begin(CaptureStage.SEAL); clock = 10_700
        tracker.finish()

        val timings = tracker.snapshot().associateBy { it.stage }
        assertEquals(600L, timings.getValue(CaptureStage.SHUTTER).durationMillis)
        assertEquals(10_000L, timings.getValue(CaptureStage.LOCATE).durationMillis)
        assertEquals(27L, timings.getValue(CaptureStage.HASH).durationMillis)
        assertEquals(26L, timings.getValue(CaptureStage.SIGN).durationMillis)
        assertEquals(47L, timings.getValue(CaptureStage.SEAL).durationMillis)
        assertEquals(10_700L, tracker.totalMillis())
    }

    @Test
    fun `the running stage has no duration yet and is marked active`() {
        tracker.begin(CaptureStage.SHUTTER); clock = 100
        tracker.begin(CaptureStage.HASH); clock = 150
        val last = tracker.snapshot().last()
        assertEquals(CaptureStage.HASH, last.stage)
        assertNull(last.durationMillis)
        assertTrue(last.active)
        assertNull(tracker.totalMillis())
    }

    @Test
    fun `a stage that never began is simply absent, not reported as instant`() {
        // Location denied: LOCATE is never begun, and must not appear as a 0 ms stage.
        tracker.begin(CaptureStage.SHUTTER); clock = 300
        tracker.begin(CaptureStage.HASH); clock = 340
        tracker.finish()
        assertTrue(tracker.snapshot().none { it.stage == CaptureStage.LOCATE })
    }

    @Test
    fun `beginning the same stage twice keeps the first start`() {
        tracker.begin(CaptureStage.SHUTTER); clock = 50
        tracker.begin(CaptureStage.SHUTTER); clock = 120
        tracker.finish()
        assertEquals(120L, tracker.snapshot().single().durationMillis)
    }
}
