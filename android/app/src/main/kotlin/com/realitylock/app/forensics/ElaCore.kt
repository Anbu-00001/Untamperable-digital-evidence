package com.realitylock.app.forensics

import com.realitylock.app.core.config.ForensicsConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The pure arithmetic of Error Level Analysis, separated from Android's Bitmap
 * APIs so it can be unit-tested on a plain JVM.
 *
 * ELA re-saves a JPEG at a fixed quality and looks at where the *per-pixel*
 * re-compression error differs. A region with a different compression history
 * settles to a different error level. This object does the diff, the summary
 * statistics, and the heat-map amplification; the JPEG round-trip lives in the
 * Android-side [ElaAnalyzer].
 *
 * Pixels are ARGB ints, as `Bitmap.getPixels` produces.
 */
object ElaCore {

    /** Per-pixel maximum absolute difference across R,G,B (0..255). */
    fun maxChannelDiff(original: IntArray, resaved: IntArray): IntArray {
        require(original.size == resaved.size) {
            "pixel arrays differ in length: ${original.size} vs ${resaved.size}"
        }
        return IntArray(original.size) { i ->
            val a = original[i]
            val b = resaved[i]
            val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val db = abs((a and 0xFF) - (b and 0xFF))
            max(dr, max(dg, db))
        }
    }

    /** Largest per-pixel difference in [diff]. */
    fun maxOf(diff: IntArray): Int = diff.maxOrNull() ?: 0

    /** Mean per-pixel difference across [diff]. */
    fun meanOf(diff: IntArray): Double =
        if (diff.isEmpty()) 0.0 else diff.sumOf { it.toLong() }.toDouble() / diff.size

    /**
     * Mean difference inside a rectangular region of a [width]-wide image.
     * Used to compare a suspected region against the rest of the frame — the
     * only honest way to read ELA (compare like with like), per FotoForensics.
     */
    fun regionMean(diff: IntArray, width: Int, left: Int, top: Int, right: Int, bottom: Int): Double {
        val l = left.coerceAtLeast(0)
        val t = top.coerceAtLeast(0)
        val r = right.coerceAtMost(width)
        val height = diff.size / width
        val b = bottom.coerceAtMost(height)
        if (r <= l || b <= t) return 0.0
        var sum = 0L
        var count = 0
        for (y in t until b) {
            val rowStart = y * width
            for (x in l until r) {
                sum += diff[rowStart + x]
                count++
            }
        }
        return if (count == 0) 0.0 else sum.toDouble() / count
    }

    /**
     * Amplifies [diff] into a grayscale ARGB heat-map. Gain is the larger of a
     * configured floor and an auto-scale to the observed maximum, so a
     * near-uniform authentic image renders mostly dark rather than being blown
     * up into meaningless noise.
     */
    fun toHeatmapPixels(diff: IntArray): IntArray {
        val maxDiff = maxOf(diff)
        val autoGain = if (maxDiff > 0) ForensicsConfig.MAX_CHANNEL_VALUE / maxDiff else 1
        val gain = max(ForensicsConfig.ELA_MIN_DISPLAY_GAIN, autoGain)
        return IntArray(diff.size) { i ->
            val v = min(ForensicsConfig.MAX_CHANNEL_VALUE, diff[i] * gain)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
}
