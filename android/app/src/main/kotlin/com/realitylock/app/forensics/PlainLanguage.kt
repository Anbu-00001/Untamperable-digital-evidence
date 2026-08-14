package com.realitylock.app.forensics

import kotlin.math.roundToInt

/**
 * Turns the Analyze screen's technical output into ordinary English.
 *
 * ## Why this exists
 *
 * The Analyze screen was readable only if you already knew what it was saying.
 * `re-saved at q95 · max error 47 · mean error 3.21` is precise and, to almost
 * everyone, meaningless — and a reader who cannot decode a line does not treat it
 * as neutral. They guess. On a screen about whether a photograph has been faked,
 * the guess is rarely "this is fine", so unexplained jargon actively pushes people
 * toward the wrong conclusion.
 *
 * That makes plain language a *correctness* feature here, not a courtesy. The
 * project's whole position is that it refuses to overclaim (ADR-0005); a number
 * nobody can read overclaims by default, because the reader supplies a meaning
 * more dramatic than the evidence supports.
 *
 * ## What these explanations must do
 *
 * Every one of them ends by saying what the result does **not** establish. That is
 * deliberate and it is the hard half: it is easy to explain ELA in a way that
 * makes a bright patch sound like a confession. The innocent explanation is given
 * the same weight as the suspicious one everywhere below, because for these
 * signals the innocent explanation is usually the right one.
 *
 * ## Why plain Kotlin strings rather than string resources
 *
 * This is interpretive prose that interpolates measured values, and the screen
 * already splits that way — labels live in `strings.xml`, while the interpretive
 * text in `ProofVerdictCard` is built in Kotlin. Following the existing split
 * keeps the two kinds of text where a reader expects them.
 *
 * The functions take **primitives rather than the report objects** so this file
 * can be tested on the plain JVM: [ElaAnalyzer.ElaOutcome] carries a `Bitmap`, and
 * accepting it here would drag Robolectric into tests that are otherwise pure.
 */
object PlainLanguage {

    /** A term the screen uses that a reader has no reason to already know. */
    data class Term(val term: String, val meaning: String)

    // ---- Error Level Analysis ------------------------------------------------

    /**
     * Explains the ELA line, quoting this image's own numbers.
     *
     * @param resaveQuality the JPEG quality the comparison copy was written at
     * @param maxError largest per-pixel disagreement found, 0..255
     * @param meanError average disagreement across the whole image
     */
    fun ela(resaveQuality: Int, maxError: Int, meanError: Double): String {
        val average = "%.1f".format(meanError)

        // Guard the ratio rather than printing "Infinity" at someone. A mean of
        // zero means the two copies were identical everywhere, which is a real
        // outcome for a synthetic or already-maximally-compressed image.
        val comparison = when {
            meanError <= 0.0 ->
                "The two copies came out identical, so there is nothing to compare."
            maxError <= 0 ->
                "The two copies came out identical, so there is nothing to compare."
            else -> {
                val ratio = (maxError / meanError).roundToInt()
                "The brightest single point is roughly ${ratio}x the average for the picture."
            }
        }

        return "Every time a JPEG is saved, it throws a little detail away. This test " +
            "saved a second copy of your picture at quality $resaveQuality out of 100, " +
            "then compared it against the one you picked, pixel by pixel. The bright " +
            "areas on the map are simply where the two copies disagreed most.\n\n" +
            "For this image the largest disagreement anywhere was $maxError on a 0–255 " +
            "scale, and the average across the whole picture was $average. $comparison\n\n" +
            "Why that is not an answer: something pasted in from another photo has been " +
            "saved a different number of times from everything around it, so it can " +
            "stand out. But so do sharp edges, lettering and fine texture, which are " +
            "bright for completely innocent reasons. Compare like with like — a bright " +
            "patch in the middle of a smooth wall is worth a second look, while a bright " +
            "outline around every object in the frame is just how JPEG works."
    }

    // ---- EXIF ----------------------------------------------------------------

    /** Explains what EXIF is, then what this particular file's metadata amounts to. */
    fun exif(report: ExifAnalyzer.ExifReport): String {
        val opening =
            "EXIF is a block of notes the camera tucks inside the picture file — which " +
                "camera took it, when, what the settings were, sometimes where you were " +
                "standing. You never see it when you look at the photo, and nothing " +
                "protects it: free tools can rewrite or erase any of it in seconds.\n\n"

        val thisFile = when {
            !report.hasExif ->
                "This file carries no camera notes at all. Screenshots never had any, " +
                    "and most messaging apps strip them from everything that passes " +
                    "through, so this is extremely common and tells you very little."

            report.flags.isEmpty() ->
                "Nothing in this file's notes looked inconsistent. That is worth almost " +
                    "nothing on its own — anyone who wanted to mislead you would simply " +
                    "have written notes that look right, which takes no skill."

            else ->
                "Some of this file's notes are worth a closer look; they are listed " +
                    "above. Each one raises a question rather than answering it."
        }

        val closing =
            "\n\nThe asymmetry matters: metadata that looks wrong is a reason to ask a " +
                "question, but metadata that looks right proves nothing whatsoever."

        return opening + thisFile + closing
    }

    /**
     * The ordinary-English meaning of one EXIF flag.
     *
     * Exhaustive over [ExifAnalyzer.Finding.Code] on purpose — a `when` with no
     * `else` means adding a new flag code will not compile until someone has
     * written the sentence a reader will see.
     */
    fun exifFlag(code: ExifAnalyzer.Finding.Code): String = when (code) {
        ExifAnalyzer.Finding.Code.EDITOR_SOFTWARE ->
            "The file names the last program that wrote it, and that program is photo " +
                "editing software. This tells you the picture was opened and saved by an " +
                "editor. It does not tell you that anything in the picture was changed — " +
                "opening a photo, straightening it and saving leaves exactly the same " +
                "trace as a deliberate forgery."

        ExifAnalyzer.Finding.Code.MODIFY_AFTER_ORIGINAL ->
            "The file records two separate times: when the photograph was taken, and when " +
                "the file was last written. They disagree, so the file was saved again at " +
                "some point after the picture was taken. Editing does that — and so does " +
                "rotating it, re-compressing it to save space, or an app that quietly " +
                "re-saves everything it touches."

        ExifAnalyzer.Finding.Code.MAKERNOTE_ABSENT ->
            "Alongside the standard notes, cameras write a private block of their own — " +
                "the MakerNote — in a format only the manufacturer fully understands. " +
                "Editing software generally cannot recreate it, so it tends to disappear " +
                "when a photo is edited and re-saved. Here the ordinary camera notes are " +
                "present but that private block is missing, which is mildly unusual. It " +
                "is a hint and no more: plenty of harmless things strip it too."

        ExifAnalyzer.Finding.Code.NO_EXIF ->
            "There is no camera metadata in this file at all. Screenshots never have any, " +
                "and most chat apps remove it in transit, so this is one of the least " +
                "informative things the screen can tell you."

        ExifAnalyzer.Finding.Code.GPS_PRESENT ->
            "The file contains the coordinates of where the photo was taken. This is not a " +
                "sign of tampering — it is a privacy warning. If you send this file to " +
                "anyone, you send the location along with it."
    }

    // ---- Classifier ----------------------------------------------------------

    /** Explains the raw Meso-4 output for [realScore], including what it is not. */
    fun classifierScore(realScore: Float): String {
        val shown = "%.3f".format(realScore)
        val leaning = when {
            realScore >= 0.8f -> "which is toward the unedited end"
            realScore <= 0.2f -> "which is toward the manipulated end"
            else -> "which sits in the middle, where the model is not leaning either way"
        }

        return "A small neural network looked at the face and produced a single number " +
            "between 0 and 1. Higher means the face resembled the ordinary, unedited " +
            "faces it was trained on; lower means it resembled the manipulated ones. " +
            "This image scored $shown, $leaning.\n\n" +
            "Three things this number is not. It is not a percentage. It is not the " +
            "probability that the photo is fake. And it is not calibrated for photographs " +
            "like yours — it learned from one research collection of face swaps, so a " +
            "picture unlike that collection can land anywhere at all.\n\n" +
            "For scale: on this project's own test images, cropping in closer moved this " +
            "same number by more than half its range, and reversed which pictures scored " +
            "highest. Treat it as something to look into, never as a finding."
    }

    /** Why nothing was scored, in words that do not read as a failure. */
    fun classifierNoFace(): String =
        "This model was trained only on faces, so it is only run when one is found. On a " +
            "photograph of a street, a document or a receipt it would still print a " +
            "confident-looking number, and that number would mean nothing at all. " +
            "Staying quiet is the honest output here, not a malfunction."

    // ---- Glossary ------------------------------------------------------------

    /**
     * The remaining words on this screen that assume knowledge a reader may not
     * have. Kept short deliberately: a glossary long enough to feel thorough is
     * long enough that nobody reads it.
     */
    val glossary: List<Term> = listOf(
        Term(
            "Triage aid",
            "Something that tells you where to look, not what to conclude. A nurse doing " +
                "triage decides who gets seen first; they are not making the diagnosis.",
        ),
        Term(
            "Heuristic",
            "A rule of thumb — right often enough to be useful, and wrong often enough " +
                "that you should never rest a conclusion on it by itself.",
        ),
        Term(
            "Metadata",
            "Information about the file that is not the picture itself: times, camera " +
                "name, settings, sometimes location.",
        ),
        Term(
            "Hash, or fingerprint",
            "A short code calculated from every byte of a file. Change one pixel and the " +
                "code changes completely, which is how this app can tell an untouched " +
                "capture from an altered copy.",
        ),
        Term(
            "Signed",
            "The phone's security chip sealed that fingerprint at the moment of capture. " +
                "Anyone can check the seal, but only this phone could have produced it.",
        ),
    )
}
