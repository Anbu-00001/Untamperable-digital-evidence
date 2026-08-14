package com.realitylock.app.forensics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the plain-English layer.
 *
 * The point of these is narrower than "the strings are non-empty". Two things can
 * go wrong here that nothing else would catch:
 *
 *  1. **The prose drifts away from the code it describes.** If the classifier's
 *     band thresholds move, the explanation would keep confidently describing the
 *     old ones. [theLeaningWordingCannotDriftFromTheBandThresholds] ties them
 *     together so that cannot happen silently.
 *  2. **Degenerate numbers reach the reader.** The ELA text divides, and a reader
 *     shown "Infinity times the average" on a screen about faked photographs has
 *     been handed something worse than no explanation.
 */
class PlainLanguageTest {

    // ---- ELA ---------------------------------------------------------------

    @Test
    fun elaQuotesTheImagesOwnNumbers() {
        val text = PlainLanguage.ela(resaveQuality = 95, maxError = 47, meanError = 3.21)

        assertTrue("should name the quality it re-saved at", "95" in text)
        assertTrue("should quote the max error", "47" in text)
        assertTrue("should quote the mean error", "3.2" in text)
        // 47 / 3.21 = 14.64 -> 15
        assertTrue("should express the peak as a multiple of the average: $text", "15x" in text)
    }

    @Test
    fun elaNeverShowsInfinityOrNaNWhenTheCopiesMatchExactly() {
        // A mean of zero is a real outcome — a synthetic or already maximally
        // compressed image can survive the round trip untouched. Dividing by it
        // must not reach the screen.
        for (max in listOf(0, 12)) {
            val text = PlainLanguage.ela(resaveQuality = 95, maxError = max, meanError = 0.0)
            assertFalse("leaked Infinity for maxError=$max", "Infinity" in text)
            assertFalse("leaked NaN for maxError=$max", "NaN" in text)
        }
        // And the zero-max case, which divides fine but describes nothing.
        val text = PlainLanguage.ela(resaveQuality = 95, maxError = 0, meanError = 4.0)
        assertFalse("Infinity" in text)
        assertFalse("NaN" in text)
    }

    @Test
    fun elaGivesTheInnocentExplanationToo() {
        val text = PlainLanguage.ela(resaveQuality = 95, maxError = 200, meanError = 2.0)
        // A bright-patch explanation that omits why bright patches are usually
        // harmless is worse than none: it turns "worth a look" into "caught".
        assertTrue("must say sharp edges are naturally bright", "edges" in text)
    }

    // ---- EXIF --------------------------------------------------------------

    @Test
    fun everyExifFlagCodeHasAnExplanation() {
        // Exhaustive by construction — `exifFlag` has no `else` branch, so this
        // failing means a code was added without prose a reader could use.
        for (code in ExifAnalyzer.Finding.Code.values()) {
            val text = PlainLanguage.exifFlag(code)
            assertTrue("$code has no explanation", text.isNotBlank())
            assertTrue("$code's explanation is too thin to help: $text", text.length > 80)
        }
    }

    @Test
    fun theGpsFlagIsFramedAsPrivacyNotTampering() {
        val text = PlainLanguage.exifFlag(ExifAnalyzer.Finding.Code.GPS_PRESENT)
        // Coordinates in a file say nothing about manipulation. Listing them among
        // the other flags already risks implying they do, so the wording has to
        // actively correct for that.
        assertTrue(
            "GPS must be framed as privacy, not tampering: $text",
            "not a" in text && "tampering" in text,
        )
    }

    @Test
    fun exifExplanationAdaptsToWhetherTheFileHasAnyMetadata() {
        val without = PlainLanguage.exif(report(hasExif = false))
        val withClean = PlainLanguage.exif(report(hasExif = true))

        assertTrue("absent metadata should be named as such", "no camera notes" in without)
        assertTrue("should give the ordinary reason it is missing", "Screenshots" in without)
        assertFalse("the two states must not read identically", without == withClean)

        // The load-bearing asymmetry: clean metadata is not evidence of anything.
        assertTrue("proves nothing" in withClean)
    }

    @Test
    fun exifExplanationDoesNotClaimCleanMetadataIsReassuring() {
        val text = PlainLanguage.exif(report(hasExif = true))
        assertTrue(
            "clean metadata must be explicitly devalued: $text",
            "worth almost" in text && "nothing" in text,
        )
    }

    // ---- Classifier --------------------------------------------------------

    @Test
    fun theLeaningWordingCannotDriftFromTheBandThresholds() {
        // Cross-checks the prose against the enum that actually drives the UI.
        // Without this, moving a threshold in DeepfakeClassifier would leave the
        // explanation describing bands that no longer exist — and every individual
        // score would still look perfectly reasonable.
        val expected = mapOf(
            DeepfakeClassifier.Band.LEANS_UNMANIPULATED to "unedited end",
            DeepfakeClassifier.Band.LEANS_MANIPULATED to "manipulated end",
            DeepfakeClassifier.Band.INCONCLUSIVE to "not leaning either way",
        )

        for (score in listOf(0.0f, 0.19f, 0.2f, 0.21f, 0.5f, 0.79f, 0.8f, 0.81f, 1.0f)) {
            val band = DeepfakeClassifier.Score(realScore = score, faceConfidence = 1f).band
            val phrase = expected.getValue(band)
            val text = PlainLanguage.classifierScore(score)
            assertTrue(
                "score $score is band $band but the text did not say \"$phrase\": $text",
                phrase in text,
            )
        }
    }

    @Test
    fun theScoreIsShownToThreeDecimalsAndDisownedAsAProbability() {
        val text = PlainLanguage.classifierScore(0.9897f)
        assertTrue("should quote the score as displayed: $text", "0.990" in text)
        // These three denials are the reason the card is allowed to exist at all
        // (ADR-0010). Losing them is losing the fence, not losing a sentence.
        assertTrue("not a percentage" in text)
        assertTrue("probability" in text)
        assertTrue("never as a finding" in text)
    }

    @Test
    fun noFaceIsExplainedAsCorrectBehaviourNotFailure() {
        val text = PlainLanguage.classifierNoFace()
        assertTrue("silence must not read as a bug: $text", "not a malfunction" in text)
        assertTrue("should say why a score would be meaningless", "mean nothing" in text)
    }

    // ---- Glossary ----------------------------------------------------------

    @Test
    fun glossaryTermsAreUniqueAndActuallyExplained() {
        val terms = PlainLanguage.glossary
        assertTrue(terms.isNotEmpty())
        assertEquals(
            "duplicate glossary terms",
            terms.size,
            terms.map { it.term.lowercase() }.distinct().size,
        )
        for (t in terms) {
            assertTrue("blank term", t.term.isNotBlank())
            assertTrue("\"${t.term}\" is defined too thinly: ${t.meaning}", t.meaning.length > 40)
            // A definition that opens by restating its own term explains nothing.
            assertFalse(
                "\"${t.term}\" is defined in terms of itself",
                t.meaning.lowercase().startsWith(t.term.lowercase()),
            )
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun report(hasExif: Boolean) = ExifAnalyzer.ExifReport(
        make = if (hasExif) "OPPO" else null,
        model = if (hasExif) "CPH2591" else null,
        software = null,
        dateTimeOriginal = if (hasExif) "2026:08:13 20:00:00" else null,
        hasExif = hasExif,
        findings = emptyList(),
    )
}
