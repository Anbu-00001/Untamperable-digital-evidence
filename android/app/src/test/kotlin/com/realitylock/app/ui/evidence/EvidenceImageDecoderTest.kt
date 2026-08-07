package com.realitylock.app.ui.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the subsampling arithmetic, which is the part of the evidence viewer
 * that decides whether a history row costs 1 MB or 16 MB.
 *
 * Pure JVM: `calculateInSampleSize` takes and returns Ints and touches no
 * Android API, precisely so this can be asserted without Robolectric or a
 * device. The decode itself is not testable here — `BitmapFactory` is a stub in
 * unit tests — but the decode is a thin wrapper around this function, and this
 * is where an off-by-one-doubling would hide.
 */
class EvidenceImageDecoderTest {

    @Test
    fun `subsamples a full-resolution capture down to a thumbnail`() {
        // A 12 MP capture in a 216px (72dp @ 3x) slot.
        val sample = EvidenceImageDecoder.calculateInSampleSize(
            sourceWidthPx = 4000,
            sourceHeightPx = 3000,
            targetWidthPx = 216,
            targetHeightPx = 216,
        )

        assertEquals(8, sample)
        // 4000/8 x 3000/8 = 500x375: still covers the 216px slot, and 1/64th of
        // the pixels the full decode would have allocated.
        assertTrue(4000 / sample >= 216)
        assertTrue(3000 / sample >= 216)
    }

    @Test
    fun `never undershoots the requested size`() {
        // The invariant that matters: a downsample saves memory, it must not
        // produce a visibly soft image by decoding smaller than asked.
        val sizes = listOf(1080 to 1920, 4000 to 3000, 3024 to 4032, 640 to 480)
        val targets = listOf(96, 216, 480, 1080, 1440)

        for ((width, height) in sizes) {
            for (target in targets) {
                val sample = EvidenceImageDecoder.calculateInSampleSize(width, height, target, target)
                val resultWidth = width / sample
                val resultHeight = height / sample
                // Either the result still covers the target, or the source was
                // already smaller than the target in that dimension.
                assertTrue(
                    "$width x $height -> target $target gave sample $sample",
                    (resultWidth >= target || width < target) &&
                        (resultHeight >= target || height < target),
                )
            }
        }
    }

    @Test
    fun `does not subsample an image already at or below the target`() {
        assertEquals(1, EvidenceImageDecoder.calculateInSampleSize(800, 600, 1080, 1080))
        assertEquals(1, EvidenceImageDecoder.calculateInSampleSize(1080, 1080, 1080, 1080))
    }

    @Test
    fun `returns a usable factor for a corrupt header rather than throwing`() {
        // BitmapFactory reports -1 dimensions for an unreadable file. The sizing
        // helper must degrade to "decode as-is" and let the decode itself report
        // the failure, not blow up here.
        assertEquals(1, EvidenceImageDecoder.calculateInSampleSize(-1, -1, 1080, 1080))
        assertEquals(1, EvidenceImageDecoder.calculateInSampleSize(0, 0, 1080, 1080))
    }

    @Test
    fun `returns a usable factor for a zero-sized display slot`() {
        // A composable measured before layout can report 0px. Guarded because the
        // loop's termination depends on a positive target.
        assertEquals(1, EvidenceImageDecoder.calculateInSampleSize(4000, 3000, 0, 0))
    }

    @Test
    fun `only ever returns a power of two`() {
        // inSampleSize values that are not powers of two are rounded down by the
        // decoder, so returning one would silently decode larger than intended.
        for (target in 1..512) {
            val sample = EvidenceImageDecoder.calculateInSampleSize(4032, 3024, target, target)
            assertTrue(
                "sample $sample for target $target is not a power of two",
                sample > 0 && sample and (sample - 1) == 0,
            )
        }
    }
}
