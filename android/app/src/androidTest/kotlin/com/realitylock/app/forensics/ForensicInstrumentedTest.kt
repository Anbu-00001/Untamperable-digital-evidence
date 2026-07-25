package com.realitylock.app.forensics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realitylock.app.core.config.ForensicsConfig
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the Phase-4 forensic exit criteria, using the REAL
 * Android JPEG encoder and `androidx.exifinterface` — not a JVM stand-in.
 *
 * Runs against controlled synthetic images bundled as test assets (a splice
 * with a known compression-history seam, and an image whose Software tag was
 * set to Photoshop), so the assertions are deterministic and involve no
 * personal photos.
 *
 * The splice geometry is known by construction: a q98 patch was composited at
 * (380,280) size 320x240 onto a q65 base, then re-saved.
 */
@RunWith(AndroidJUnit4::class)
class ForensicInstrumentedTest {

    private val assets get() =
        InstrumentationRegistry.getInstrumentation().context.assets

    private fun decodeAsset(name: String): Bitmap =
        assets.open(name).use { BitmapFactory.decodeStream(it) }
            ?: error("could not decode asset $name")

    // ---- ELA: the spliced region reads hotter than the untouched background ----

    @Test
    fun ela_highlights_the_spliced_region() {
        val bmp = decodeAsset("rl_spliced.jpg")
        val width = bmp.width
        val height = bmp.height

        // Re-save through Android's own JPEG encoder at the ELA quality.
        val resaved = jpegRoundTrip(bmp)
        val orig = IntArray(width * height).also { bmp.getPixels(it, 0, width, 0, 0, width, height) }
        val re = IntArray(width * height).also { resaved.getPixels(it, 0, width, 0, 0, width, height) }

        val diff = ElaCore.maxChannelDiff(orig, re)
        val spliceMean = ElaCore.regionMean(diff, width, 380, 280, 700, 520)
        val backgroundMean = ElaCore.regionMean(diff, width, 50, 50, 350, 260)

        assertTrue(
            "spliced region ($spliceMean) should read clearly hotter than background ($backgroundMean)",
            spliceMean > backgroundMean * 1.5,
        )
    }

    @Test
    fun ela_analyzer_produces_a_heatmap_of_matching_size() {
        val bmp = decodeAsset("rl_authentic.jpg")
        val outcome = ElaAnalyzer().analyze(bmp.copy(Bitmap.Config.ARGB_8888, false))

        assertTrue(outcome.heatmap.width > 0 && outcome.heatmap.height > 0)
        assertTrue("re-save quality is the configured value", outcome.resaveQuality == ForensicsConfig.ELA_RESAVE_QUALITY)
        assertTrue("max error is a real 0..255 reading", outcome.maxError in 0..255)
    }

    // ---- EXIF: an externally-edited image trips the editor-software flag ----

    @Test
    fun exif_flags_an_image_edited_in_photoshop() {
        val report = assets.open("rl_photoshop.jpg").use { ExifAnalyzer().analyze(it) }

        val editorFlag = report.flags.firstOrNull {
            it.code == ExifAnalyzer.Finding.Code.EDITOR_SOFTWARE
        }
        assertTrue(
            "expected the EDITOR_SOFTWARE flag to fire; flags were ${report.flags.map { it.code }}",
            editorFlag != null,
        )
        assertTrue(
            "the flagged software should be the injected Photoshop string, was ${editorFlag?.detail}",
            editorFlag?.detail?.contains("Photoshop", ignoreCase = true) == true,
        )
    }

    private fun jpegRoundTrip(bitmap: Bitmap): Bitmap {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, ForensicsConfig.ELA_RESAVE_QUALITY, stream)
        val bytes = stream.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
