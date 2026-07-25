package com.realitylock.app.forensics

import com.realitylock.app.core.config.ForensicsConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The pure decision logic for EXIF-consistency checks, separated from
 * `androidx.exifinterface` so it can be unit-tested on a plain JVM.
 *
 * Every rule here produces a *suggestive* flag, never a verdict. EXIF is
 * trivially editable — the same tools that read it can rewrite it — so a flag
 * means "the metadata is internally inconsistent or names an editor", not "the
 * image was manipulated", and clean/absent metadata is not evidence of
 * authenticity. The UI wording says exactly this.
 */
object ExifRules {

    /** EXIF timestamps are "yyyy:MM:dd HH:mm:ss". */
    private val EXIF_DATETIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * True when [software] names a known image editor. Suggestive only: the tag
     * is freely settable and often absent even on edited images, and a camera or
     * benign resize tool can legitimately set it.
     */
    fun isEditorSoftware(software: String?): Boolean {
        val s = software?.trim()?.lowercase() ?: return false
        return ForensicsConfig.EDITOR_SOFTWARE_MARKERS.any { it in s }
    }

    /**
     * True when the file's modify time is later than the original capture time —
     * consistent with editing, but also with lossless rotation or format
     * conversion. Null when either timestamp is missing or unparseable (no
     * signal), never a false positive.
     */
    fun modifyAfterOriginal(modifyDateTime: String?, dateTimeOriginal: String?): Boolean? {
        val modify = parse(modifyDateTime) ?: return null
        val original = parse(dateTimeOriginal) ?: return null
        return modify.isAfter(original)
    }

    private fun parse(value: String?): LocalDateTime? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        return runCatching { LocalDateTime.parse(v, EXIF_DATETIME) }.getOrNull()
    }
}
