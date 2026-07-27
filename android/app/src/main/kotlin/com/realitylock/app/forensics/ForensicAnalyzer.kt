package com.realitylock.app.forensics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.realitylock.app.core.config.ForensicsConfig

/**
 * Runs the explainable-authenticity heuristics (ELA + EXIF) on a user-selected
 * candidate image.
 *
 * **This is analysis, not capture.** It produces a heuristic *report* and never
 * a proof package — it does not hash, sign, or persist anything, and there is no
 * path from here into the signing pipeline. That keeps the "no gallery import
 * into the proof flow" rule intact (Phase 2): reading an image to scrutinise it
 * is not the same as passing an imported image off as a live capture.
 */
class ForensicAnalyzer(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val ela = ElaAnalyzer()
    private val exif = ExifAnalyzer()

    /** ELA + EXIF results plus a preview of the (bounded) source image. */
    data class AuthenticityReport(
        val preview: Bitmap,
        val ela: ElaAnalyzer.ElaOutcome,
        val exif: ExifAnalyzer.ExifReport,
    )

    fun analyze(uri: Uri): AuthenticityReport {
        val source = decodeBounded(uri)
            ?: error("could not decode the selected image")

        // EXIF is read from a fresh stream (the decode stream is already consumed).
        //
        // A stream that will not open is an I/O failure, not a forensic finding.
        // This previously fell back to a synthesised `NO_EXIF` report, which told
        // the user "this image has no EXIF metadata" — an affirmative claim about
        // the image — when the truth was that we never managed to look. It fails
        // the same way the decode above does, for the same reason.
        val exifReport = resolver.openInputStream(uri)?.use(exif::analyze)
            ?: error("could not read the selected image to inspect its EXIF metadata")

        // ELA reads pixels from `source` (non-destructively) and builds its own
        // heat-map, so `source` survives to serve as the preview.
        val elaOutcome = ela.analyze(source)

        return AuthenticityReport(preview = source, ela = elaOutcome, exif = exifReport)
    }

    /**
     * Decodes [uri] with the longest edge bounded, so a 12-megapixel phone photo
     * never has to be fully resident. `inSampleSize` only halves powers of two,
     * which is enough to keep memory safe before ELA does its finer scaling.
     */
    private fun decodeBounded(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > ForensicsConfig.ELA_MAX_WORKING_EDGE_PX * 2) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
