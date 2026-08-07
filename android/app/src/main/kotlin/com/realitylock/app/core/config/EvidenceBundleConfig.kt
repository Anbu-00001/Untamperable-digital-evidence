package com.realitylock.app.core.config

/**
 * Naming and MIME constants for the exported evidence bundle.
 *
 * Kept apart from [CertificateConfig] on purpose. That object describes documents
 * *about* a capture — a certificate and a statutory annexure, both of which this
 * system generates and neither of which contains the evidence. This one describes
 * the archive that carries the evidence itself: the signed proof package and the
 * photograph, byte-for-byte as they were signed.
 *
 * The distinction shows up in a case file, which is where it matters. Three
 * documents about one event, all beginning `reality-lock-`, are easy to confuse,
 * and confusing the annexure with the archive means handing over a form instead
 * of the exhibit.
 */
object EvidenceBundleConfig {

    /** Filename stem; a truncated event id and the extension are appended. */
    const val FILENAME_PREFIX: String = "reality-lock-evidence-"

    const val FILENAME_EXTENSION: String = ".zip"

    /**
     * The MIME type handed to the system "save as" dialog.
     *
     * `application/zip` rather than `application/octet-stream` so the picker
     * suggests a sensible destination and the receiving app knows it can open it.
     */
    const val MIME_TYPE_ZIP: String = "application/zip"
}
