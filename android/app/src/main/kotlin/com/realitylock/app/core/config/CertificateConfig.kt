package com.realitylock.app.core.config

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Layout and encoding constants for the exported PDF certificate and its QR badge
 * (research/05 §6–§7). Centralized so no geometry is a magic number inside the
 * drawing code.
 */
object CertificateConfig {

    // ---- QR badge ----------------------------------------------------------

    /** Rendered edge length in pixels. Comfortably scannable in print. */
    const val QR_SIZE_PX: Int = 512

    /**
     * Error-correction level Q (~25% recoverable). A certificate is printed, and
     * print gets folded, smudged and photocopied; the default (L, ~7%) is tuned
     * for pristine screens.
     */
    val QR_ERROR_CORRECTION: ErrorCorrectionLevel = ErrorCorrectionLevel.Q

    /** Quiet-zone width in modules. Below ~4 many scanners stop detecting. */
    const val QR_QUIET_ZONE_MODULES: Int = 2

    const val QR_CHARACTER_SET: String = "UTF-8"

    // ---- Page geometry -----------------------------------------------------
    // PdfDocument works in PostScript points (1/72 inch), so A4 is 595 x 842.

    const val PAGE_WIDTH_POINTS: Int = 595
    const val PAGE_HEIGHT_POINTS: Int = 842
    const val PAGE_MARGIN_POINTS: Float = 42f

    /** Edge length of the QR image as drawn on the page, in points. */
    const val QR_DRAW_SIZE_POINTS: Float = 132f

    // ---- Typography --------------------------------------------------------

    const val TEXT_SIZE_TITLE: Float = 20f
    const val TEXT_SIZE_HEADING: Float = 12f
    const val TEXT_SIZE_BODY: Float = 9.5f
    /** Monospace, for hashes — so a reader can compare digits without slipping. */
    const val TEXT_SIZE_MONO: Float = 8.5f

    const val LINE_SPACING_MULTIPLIER: Float = 1.35f
    const val SECTION_SPACING_POINTS: Float = 14f

    /** Width of the label column in the key/value rows, in points. */
    const val LABEL_COLUMN_WIDTH_POINTS: Float = 132f

    // ---- Output ------------------------------------------------------------

    const val MIME_TYPE_PDF: String = "application/pdf"

    /** Filename stem; the event id and `.pdf` are appended. */
    const val FILENAME_PREFIX: String = "reality-lock-certificate-"
    const val FILENAME_EXTENSION: String = ".pdf"

    /** How much of the event id goes in the filename — enough to be unique here. */
    const val FILENAME_EVENT_ID_CHARS: Int = 8

    // ---- Statutory annexure (BSA 2023 s.63) --------------------------------
    // A separate document from the certificate above, and separately named: the
    // two must never be confused in a case file, because one is this system's
    // own output and the other is a form a person signs.

    const val ANNEXURE_FILENAME_PREFIX: String = "reality-lock-s63-annexure-"

    /**
     * Vertical space reserved for one signatory's block.
     *
     * A block must never be split across a page boundary. A "Signature" rule
     * stranded at the top of page 3, with the role it belongs to left on page 2,
     * is not a cosmetic problem on a document whose entire purpose is recording
     * who attested to what.
     */
    const val SIGNATURE_BLOCK_HEIGHT_POINTS: Float = 96f

    /** Length of a blank rule a person writes on, in points. */
    const val SIGNATURE_RULE_WIDTH_POINTS: Float = 210f

    const val SIGNATURE_RULE_STROKE_WIDTH: Float = 0.7f
}
