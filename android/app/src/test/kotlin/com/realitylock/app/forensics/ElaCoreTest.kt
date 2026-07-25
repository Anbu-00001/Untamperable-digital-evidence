package com.realitylock.app.forensics

import com.realitylock.app.core.config.ForensicsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure ELA arithmetic with synthetic pixel arrays where the answer is
 * known by construction. The JPEG round-trip itself is Android-only and is
 * exercised on-device; the encoder-independent property that matters — a region
 * with more error reads brighter — is what these lock down.
 */
class ElaCoreTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `identical images have zero difference everywhere`() {
        val pixels = IntArray(16) { argb(100, 150, 200) }
        val diff = ElaCore.maxChannelDiff(pixels, pixels)

        assertEquals(0, ElaCore.maxOf(diff))
        assertEquals(0.0, ElaCore.meanOf(diff), 0.0)
    }

    @Test
    fun `difference is the max across colour channels`() {
        val a = intArrayOf(argb(100, 100, 100))
        val b = intArrayOf(argb(110, 130, 105)) // deltas 10, 30, 5 → max 30
        assertEquals(30, ElaCore.maxChannelDiff(a, b)[0])
    }

    @Test
    fun `a high-error region reads higher than a calm region`() {
        // 4x4 image: left half calm (diff 2), right half hot (diff 40).
        val width = 4
        val orig = IntArray(16) { argb(120, 120, 120) }
        val resaved = IntArray(16) { i ->
            val x = i % width
            if (x < 2) argb(122, 120, 120) else argb(160, 120, 120)
        }
        val diff = ElaCore.maxChannelDiff(orig, resaved)

        val calm = ElaCore.regionMean(diff, width, 0, 0, 2, 4)
        val hot = ElaCore.regionMean(diff, width, 2, 0, 4, 4)
        assertEquals(2.0, calm, 0.001)
        assertEquals(40.0, hot, 0.001)
        assertTrue("the spliced-like region must read hotter", hot > calm * 5)
    }

    @Test
    fun `region bounds are clamped to the image`() {
        val width = 4
        val diff = IntArray(16) { 10 }
        // Ask for a region larger than the image; must not throw or over-read.
        assertEquals(10.0, ElaCore.regionMean(diff, width, -5, -5, 100, 100), 0.001)
    }

    @Test
    fun `heatmap amplifies a tiny difference to a visible value`() {
        // A max diff of 3 would be near-invisible raw; the floor gain lifts it.
        val diff = intArrayOf(0, 1, 3)
        val heat = ElaCore.toHeatmapPixels(diff)

        val brightest = heat[2] and 0xFF
        assertTrue("tiny errors must be amplified to something visible, was $brightest", brightest >= 3 * ForensicsConfig.ELA_MIN_DISPLAY_GAIN)
        assertEquals("zero error stays black", 0, heat[0] and 0xFF)
    }

    @Test
    fun `heatmap never overflows a channel`() {
        val diff = intArrayOf(0, 128, 255)
        val heat = ElaCore.toHeatmapPixels(diff)
        for (p in heat) {
            assertTrue((p and 0xFF) <= ForensicsConfig.MAX_CHANNEL_VALUE)
            assertEquals("opaque", 0xFF, (p ushr 24) and 0xFF)
        }
    }
}
