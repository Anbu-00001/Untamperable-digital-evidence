package com.realitylock.app.forensics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The classifier, on real hardware, against the model authors' own reference
 * images.
 *
 * This suite exists to catch the failure that unit tests structurally cannot: the
 * model loading, running and *agreeing with the numbers this project published
 * about it*. A conversion can be perfect and the Android side still score
 * differently — wrong channel order, wrong normalisation, wrong resampling filter
 * — and every one of those produces plausible-looking output rather than an error.
 *
 * The reference scores were measured off-device against the same `.tflite`, using
 * nearest-neighbour resampling on the full (already face-cropped) images:
 *
 *     df00204   0.0414      real00240   0.9897
 *     df01254   0.0487      real00772   0.9977
 *
 * They are NOT asserted exactly here. This path additionally runs the face gate
 * and crops to it, so the model sees a slightly different framing than the
 * whole-image reference did. What must hold is the property the scores exist to
 * demonstrate — that fakes land low and real images land high, with a wide gap.
 */
@RunWith(AndroidJUnit4::class)
class DeepfakeClassifierInstrumentedTest {

    private lateinit var classifier: DeepfakeClassifier

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val created = DeepfakeClassifier.create(targetContext)
        assertNotNull(
            "the model asset did not load — check `noCompress += \"tflite\"` in build.gradle.kts, " +
                "which is what lets LiteRT memory-map it out of the APK",
            created,
        )
        classifier = created!!
    }

    @After
    fun tearDown() {
        if (::classifier.isInitialized) classifier.close()
    }

    /** Reference images live in the TEST apk's assets, not the app's. */
    private fun reference(name: String): Bitmap =
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }

    private fun scoreOf(name: String): Float {
        val outcome = classifier.classify(reference(name))
        assertTrue(
            "$name did not produce a score: $outcome",
            outcome is DeepfakeClassifier.Outcome.Scored,
        )
        return (outcome as DeepfakeClassifier.Outcome.Scored).score.realScore
    }

    @Test
    fun deepfakeReferenceImagesScoreLow() {
        for (name in listOf("df00204.jpg", "df01254.jpg")) {
            val score = scoreOf(name)
            assertTrue("$name scored $score, expected < 0.5 (0 = deepfake)", score < 0.5f)
        }
    }

    @Test
    fun authenticReferenceImagesScoreHigh() {
        for (name in listOf("real00240.jpg", "real00772.jpg")) {
            val score = scoreOf(name)
            assertTrue("$name scored $score, expected > 0.5 (1 = real)", score > 0.5f)
        }
    }

    @Test
    fun theConventionIsNotInverted() {
        // The single most dangerous silent failure available here. Reversing the
        // convention flips every reading in the app while every score still looks
        // entirely reasonable, and no other assertion in this file would notice.
        val worstReal = listOf("real00240.jpg", "real00772.jpg").minOf { scoreOf(it) }
        val bestFake = listOf("df00204.jpg", "df01254.jpg").maxOf { scoreOf(it) }

        assertTrue(
            "convention appears INVERTED: best fake $bestFake >= worst real $worstReal. " +
                "Higher must mean more REAL.",
            worstReal > bestFake,
        )
        // A wide margin, not a coin-flip margin. A narrow one would mean the model
        // loaded but the preprocessing is wrong.
        assertTrue(
            "separation is only ${worstReal - bestFake}; preprocessing is probably wrong",
            worstReal - bestFake > 0.5f,
        )
    }

    @Test
    fun bandsFollowTheScore() {
        assertEquals(
            DeepfakeClassifier.Band.LEANS_MANIPULATED,
            DeepfakeClassifier.Score(0.05f, 1f).band,
        )
        assertEquals(
            DeepfakeClassifier.Band.LEANS_UNMANIPULATED,
            DeepfakeClassifier.Score(0.99f, 1f).band,
        )
        assertEquals(
            DeepfakeClassifier.Band.INCONCLUSIVE,
            DeepfakeClassifier.Score(0.5f, 1f).band,
        )
    }

    @Test
    fun anImageWithNoFaceIsNotScored() {
        // The gate's whole purpose. A flat grey rectangle has no face, and the
        // honest output is silence rather than a confident number about nothing.
        val blank = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.rgb(128, 128, 128))
        }

        val outcome = classifier.classify(blank)

        assertTrue(
            "a faceless image produced $outcome; it must not be scored",
            outcome is DeepfakeClassifier.Outcome.NoFace ||
                outcome is DeepfakeClassifier.Outcome.Unavailable,
        )
    }

    @Test
    fun aTinyImageIsReportedUnavailableNotScored() {
        val tiny = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        val outcome = classifier.classify(tiny)

        assertTrue(
            "an unusably small image produced $outcome",
            outcome is DeepfakeClassifier.Outcome.Unavailable,
        )
    }

    @Test
    fun resamplingFilterIsPinnedToNearestNeighbour() {
        // Guards the constant against a well-meaning "improvement". Android's own
        // documentation recommends filter = true, and switching it moves scores by
        // 0.0284 — more than the int8 quantisation error the int8 build was
        // rejected over. If this fails, the reference numbers no longer describe
        // what the app computes.
        assertEquals(
            "RESAMPLING_FILTER must stay false (nearest neighbour)",
            false,
            DeepfakeClassifier.RESAMPLING_FILTER,
        )
    }
}
