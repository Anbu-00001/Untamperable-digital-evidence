package com.realitylock.app.forensics

import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Reads a candidate image's EXIF metadata and reports *suggestive*
 * inconsistencies. Uses `androidx.exifinterface` from an [InputStream], which is
 * the reliable path for a user-picked `content://` image on modern Android.
 *
 * None of these findings is a verdict. See [ExifRules] and the UI disclaimer:
 * metadata can be edited, stripped, or fabricated, so a flag raises a question
 * and clean metadata answers none.
 */
class ExifAnalyzer {

    /** One suggestive observation. [triggered] false means "checked, nothing to flag". */
    data class Finding(
        val code: Code,
        val triggered: Boolean,
        /** The concrete value behind the finding, when there is one to show. */
        val detail: String? = null,
    ) {
        enum class Code { EDITOR_SOFTWARE, MODIFY_AFTER_ORIGINAL, MAKERNOTE_ABSENT, GPS_PRESENT, NO_EXIF }
    }

    /** Descriptive facts plus the list of checks that ran. */
    data class ExifReport(
        val make: String?,
        val model: String?,
        val software: String?,
        val dateTimeOriginal: String?,
        val hasExif: Boolean,
        val findings: List<Finding>,
    ) {
        /** Findings that actually fired — the ones worth a human's attention. */
        val flags: List<Finding> get() = findings.filter { it.triggered }
    }

    fun analyze(input: InputStream): ExifReport {
        val exif = ExifInterface(input)

        val make = exif.getAttribute(ExifInterface.TAG_MAKE)
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)
        val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)
        val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val modifyDateTime = exif.getAttribute(ExifInterface.TAG_DATETIME)

        // "Has EXIF" ≈ at least one core tag present. A total absence is itself
        // worth noting (many editors strip everything), but proves nothing.
        val hasExif = listOf(make, model, software, dateTimeOriginal, modifyDateTime)
            .any { !it.isNullOrBlank() } || exif.hasThumbnail()

        val findings = mutableListOf<Finding>()

        if (!hasExif) {
            findings.add(Finding(Finding.Code.NO_EXIF, triggered = true))
        }

        findings.add(
            Finding(
                Finding.Code.EDITOR_SOFTWARE,
                triggered = ExifRules.isEditorSoftware(software),
                detail = software,
            ),
        )

        ExifRules.modifyAfterOriginal(modifyDateTime, dateTimeOriginal)?.let { later ->
            findings.add(
                Finding(
                    Finding.Code.MODIFY_AFTER_ORIGINAL,
                    triggered = later,
                    detail = if (later) "modified $modifyDateTime, captured $dateTimeOriginal" else null,
                ),
            )
        }

        // MakerNote presence is a weak signal; report it only when there is other
        // EXIF but no maker note (a stripped maker note on an otherwise-populated
        // image). Absent entirely, it says nothing.
        if (hasExif) {
            val makerNote = exif.getAttribute(ExifInterface.TAG_MAKER_NOTE)
            findings.add(
                Finding(Finding.Code.MAKERNOTE_ABSENT, triggered = makerNote.isNullOrBlank()),
            )
        }

        findings.add(
            Finding(Finding.Code.GPS_PRESENT, triggered = exif.latLong != null),
        )

        return ExifReport(make, model, software, dateTimeOriginal, hasExif, findings)
    }
}
