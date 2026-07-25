package com.realitylock.app.forensics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.realitylock.app.core.config.ForensicsConfig
import java.io.ByteArrayOutputStream

/**
 * Runs Error Level Analysis on a candidate [Bitmap] using Android's own JPEG
 * encoder, and returns a heat-map plus summary statistics.
 *
 * **What this proves: nothing on its own.** ELA highlights where JPEG
 * re-compression error differs; bright regions merely warrant a closer look.
 * High-contrast edges, text and fine texture are naturally bright in authentic
 * photos, and a single re-save (sharing through any messaging app) erases the
 * signal. It cannot label an image real or fake. The UI states this next to
 * every result; the [ElaOutcome] carries statistics, never a verdict.
 */
class ElaAnalyzer {

    /** Heat-map plus the raw error statistics a viewer can weigh for themselves. */
    data class ElaOutcome(
        val heatmap: Bitmap,
        /** Largest per-pixel re-compression error (0..255). */
        val maxError: Int,
        /** Mean per-pixel error across the frame. */
        val meanError: Double,
        /** JPEG quality used for the re-save (documented so the map is reproducible). */
        val resaveQuality: Int,
    )

    fun analyze(source: Bitmap): ElaOutcome {
        val working = scaleToWorkingSize(source)

        val resaved = jpegRoundTrip(working, ForensicsConfig.ELA_RESAVE_QUALITY)
        try {
            val width = working.width
            val height = working.height
            val origPixels = IntArray(width * height)
            val resavedPixels = IntArray(width * height)
            working.getPixels(origPixels, 0, width, 0, 0, width, height)
            resaved.getPixels(resavedPixels, 0, width, 0, 0, width, height)

            val diff = ElaCore.maxChannelDiff(origPixels, resavedPixels)
            val heatmapPixels = ElaCore.toHeatmapPixels(diff)
            val heatmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            heatmap.setPixels(heatmapPixels, 0, width, 0, 0, width, height)

            return ElaOutcome(
                heatmap = heatmap,
                maxError = ElaCore.maxOf(diff),
                meanError = ElaCore.meanOf(diff),
                resaveQuality = ForensicsConfig.ELA_RESAVE_QUALITY,
            )
        } finally {
            if (resaved !== working) resaved.recycle()
            if (working !== source) working.recycle()
        }
    }

    /** Re-encodes [bitmap] as JPEG at [quality] and decodes it back. */
    private fun jpegRoundTrip(bitmap: Bitmap, quality: Int): Bitmap {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("failed to decode the re-saved JPEG")
    }

    private fun scaleToWorkingSize(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        val limit = ForensicsConfig.ELA_MAX_WORKING_EDGE_PX
        if (longest <= limit) return source
        val scale = limit.toDouble() / longest
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}
