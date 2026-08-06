package com.realitylock.app.certificate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.realitylock.app.core.config.CertificateConfig
import java.io.ByteArrayOutputStream

/**
 * Renders a [StatutoryAnnexureContent] to a PDF, using Android's built-in
 * `PdfDocument` for the same reason [CertificateRenderer] does — no third-party
 * PDF library (research/05 §7).
 *
 * ## Why this is not just another page in CertificateRenderer
 *
 * The two documents make opposite claims. The certificate reports what this
 * system computed and is complete the moment it is generated. The annexure is a
 * **draft form**: its load-bearing content is what a *person* will add to it, and
 * it is worthless until countersigned. Rendering them from one class would invite
 * exactly the merge that must not happen — a single PDF that looks like this
 * system certified something under BSA 2023 s.63, which it cannot do
 * (research/06 §1.3).
 *
 * ## Pagination is a correctness concern here, not a layout nicety
 *
 * [CertificateRenderer] draws one page and never checks whether it ran off the
 * bottom, which is safe there only because its content is bounded. This document
 * is not bounded: the device-particulars table, the production method, the
 * matters requiring human attestation and one block per signatory routinely
 * exceed a single A4 page. Content silently drawn past the bottom edge would be
 * *invisible in the PDF while appearing to have been included* — on a statutory
 * form, that is the worst possible failure, so every write goes through [Page],
 * which breaks before it overflows rather than after.
 */
class StatutoryAnnexureRenderer {

    fun render(content: StatutoryAnnexureContent): ByteArray {
        val document = PdfDocument()
        try {
            val page = Page(document)
            drawAnnexure(page, content)
            page.finish()

            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            // PdfDocument holds native memory; leaking it on an exception path
            // would be invisible until the app started dying under repeat exports.
            document.close()
        }
    }

    private fun drawAnnexure(page: Page, content: StatutoryAnnexureContent) {
        val title = paint(CertificateConfig.TEXT_SIZE_TITLE, bold = true)
        val heading = paint(CertificateConfig.TEXT_SIZE_HEADING, bold = true)
        val body = paint(CertificateConfig.TEXT_SIZE_BODY)
        val mono = paint(CertificateConfig.TEXT_SIZE_MONO, mono = true)
        val muted = paint(CertificateConfig.TEXT_SIZE_BODY).apply { color = Color.DKGRAY }

        page.text(content.title, title)
        page.gap(CertificateConfig.SECTION_SPACING_POINTS / 2f)

        // ---- draft notice, first and boxed ----------------------------------
        // Same reasoning as the certificate's framing banner: a reader who skims
        // must learn this is NOT an executed certificate before they read a hash
        // and start treating the document as evidence.
        page.boxedParagraph(content.draftNotice, body)
        page.gap(CertificateConfig.SECTION_SPACING_POINTS)

        // ---- the record being certified -------------------------------------
        page.section("Record certified", heading)
        page.keyValue("Event ID", content.eventId, body, mono)
        page.keyValue("Captured at (UTC)", content.capturedAtIso, body, body)
        page.gap(CertificateConfig.SECTION_SPACING_POINTS)

        // ---- the Schedule's hash particulars --------------------------------
        // The hash value and its algorithm are the fields the Schedule actually
        // asks for; StatutoryAnnexureContent refuses to be built without them.
        page.section("Hash particulars", heading)
        page.keyValue("Hash value", content.merkleRoot, body, mono)
        page.keyValue("Hash algorithm", content.hashAlgorithm, body, body)
        page.keyValue("Media SHA-256", content.mediaSha256, body, mono)
        page.keyValue("Signature algorithm", content.signatureAlgorithm, body, body)
        page.gap(CertificateConfig.SECTION_SPACING_POINTS)

        // ---- device particulars ---------------------------------------------
        page.section("Device particulars", heading)
        for ((label, value) in content.deviceParticulars) {
            page.keyValue(label, value, body, body)
        }
        page.gap(CertificateConfig.SECTION_SPACING_POINTS)

        // ---- how the record was produced ------------------------------------
        page.section("Method of production", heading)
        content.productionMethod.forEachIndexed { index, step ->
            page.paragraph("${index + 1}. $step", body)
        }
        page.gap(CertificateConfig.SECTION_SPACING_POINTS)

        // ---- what the signatories, not this system, must attest to ----------
        page.section("Matters requiring attestation by the signatories", heading)
        page.paragraph(
            "The following cannot be established by the capturing system and must be " +
                "attested from the signatories' own knowledge:",
            muted,
        )
        for (matter in content.mattersRequiringHumanAttestation) {
            page.paragraph("• $matter", body)
        }
        page.gap(CertificateConfig.SECTION_SPACING_POINTS)

        // ---- signature blocks ------------------------------------------------
        page.section("Certification", heading)
        for (signatory in content.signatories) {
            page.signatureBlock(signatory, body, muted)
        }

        page.gap(CertificateConfig.SECTION_SPACING_POINTS / 2f)
        page.paragraph("Draft generated ${content.generatedAtIso}", muted)
    }

    private fun paint(size: Float, bold: Boolean = false, mono: Boolean = false): Paint = Paint().apply {
        isAntiAlias = true
        textSize = size
        color = Color.BLACK
        typeface = Typeface.create(
            if (mono) Typeface.MONOSPACE else Typeface.DEFAULT,
            if (bold) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    /**
     * A cursor over a growing PDF that starts a new page before content would run
     * off the bottom, never after.
     *
     * Every drawing helper asks [ensure] for the vertical space it is about to
     * use. Nothing draws without reserving room first, which is what makes
     * "silently rendered past the page edge" unrepresentable rather than merely
     * unlikely.
     */
    private class Page(private val document: PdfDocument) {
        private val left = CertificateConfig.PAGE_MARGIN_POINTS
        private val right = CertificateConfig.PAGE_WIDTH_POINTS - CertificateConfig.PAGE_MARGIN_POINTS
        private val bottom = CertificateConfig.PAGE_HEIGHT_POINTS - CertificateConfig.PAGE_MARGIN_POINTS
        private val width = right - left

        private var pageNumber = 0
        private var current: PdfDocument.Page? = null
        private var y = 0f

        /** Pages actually emitted. Exposed so a test can assert the break happened. */
        var pagesEmitted = 0
            private set

        init {
            newPage()
        }

        private fun newPage() {
            current?.let { document.finishPage(it) }
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(
                CertificateConfig.PAGE_WIDTH_POINTS,
                CertificateConfig.PAGE_HEIGHT_POINTS,
                pageNumber,
            ).create()
            current = document.startPage(info)
            pagesEmitted = pageNumber
            y = CertificateConfig.PAGE_MARGIN_POINTS
        }

        private val canvas: Canvas get() = requireNonNullPage().canvas

        private fun requireNonNullPage(): PdfDocument.Page =
            current ?: error("the annexure page was used after it was finished")

        /** Reserves [height]; breaks to a new page if it will not fit. */
        private fun ensure(height: Float) {
            if (y + height > bottom) newPage()
        }

        fun finish() {
            current?.let { document.finishPage(it) }
            current = null
        }

        fun gap(points: Float) {
            // A gap must not push the cursor past the bottom and leave the next
            // write starting off-page; ensure() on the next write catches that,
            // but clamping here keeps y meaningful for the pagesEmitted count.
            y = minOf(y + points, bottom)
        }

        fun text(value: String, paint: Paint) {
            ensure(lineHeight(paint))
            canvas.drawText(value, left, y + paint.textSize, paint)
            y += lineHeight(paint)
        }

        fun section(label: String, paint: Paint) {
            // A heading immediately above a page break would leave the section
            // title on one page and all of its content on the next. Reserving two
            // lines keeps the heading with at least its first row.
            ensure(lineHeight(paint) * 2)
            canvas.drawText(label, left, y + paint.textSize, paint)
            y += lineHeight(paint)
        }

        fun paragraph(value: String, paint: Paint) {
            for (line in TextWrapper.wrap(value, width, paint::measureText)) {
                ensure(lineHeight(paint))
                canvas.drawText(line, left, y + paint.textSize, paint)
                y += lineHeight(paint)
            }
        }

        fun keyValue(label: String, value: String, labelPaint: Paint, valuePaint: Paint) {
            val valueLeft = left + CertificateConfig.LABEL_COLUMN_WIDTH_POINTS
            val valueWidth = width - CertificateConfig.LABEL_COLUMN_WIDTH_POINTS
            // An empty value prints an em dash rather than nothing: a blank space
            // on a form reads as "not applicable", while "—" reads as "no value
            // recorded", and those are different statements about the evidence.
            val lines = TextWrapper.wrap(value.ifEmpty { "—" }, valueWidth, valuePaint::measureText)

            ensure(lineHeight(valuePaint) * lines.size)
            var first = true
            for (line in lines) {
                if (first) {
                    canvas.drawText(label, left, y + labelPaint.textSize, labelPaint)
                    first = false
                }
                canvas.drawText(line, valueLeft, y + valuePaint.textSize, valuePaint)
                y += lineHeight(valuePaint)
            }
        }

        fun boxedParagraph(value: String, paint: Paint) {
            val padding = CertificateConfig.SECTION_SPACING_POINTS / 2f
            val lines = TextWrapper.wrap(value, width - 2 * padding, paint::measureText)
            val boxHeight = lines.size * lineHeight(paint) + 2 * padding

            // The box and its text must stay together — a border with no text
            // inside it would be worse than no box at all.
            ensure(boxHeight)

            val border = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = BANNER_BORDER_WIDTH
                color = Color.BLACK
            }
            canvas.drawRect(left, y, left + width, y + boxHeight, border)

            var textY = y + padding
            for (line in lines) {
                canvas.drawText(line, left + padding, textY + paint.textSize, paint)
                textY += lineHeight(paint)
            }
            y += boxHeight
        }

        /**
         * One signatory's block: who must sign, why the law asks them to, and
         * BLANK rules for name, signature and date.
         *
         * The rules are drawn empty and there is nothing here that could fill
         * them — [SignatoryBlock] carries no name, signature or date field, by
         * design. This app generates the material a certifier relies on; it must
         * never present itself as having certified anything.
         */
        fun signatureBlock(signatory: SignatoryBlock, body: Paint, muted: Paint) {
            ensure(CertificateConfig.SIGNATURE_BLOCK_HEIGHT_POINTS)

            canvas.drawText(signatory.role, left, y + body.textSize, body)
            y += lineHeight(body)

            for (line in TextWrapper.wrap(signatory.basis, width, muted::measureText)) {
                canvas.drawText(line, left, y + muted.textSize, muted)
                y += lineHeight(muted)
            }
            y += CertificateConfig.SECTION_SPACING_POINTS / 2f

            val rule = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = CertificateConfig.SIGNATURE_RULE_STROKE_WIDTH
                color = Color.BLACK
            }
            val ruleWidth = CertificateConfig.SIGNATURE_RULE_WIDTH_POINTS
            val secondColumn = left + ruleWidth + CertificateConfig.SECTION_SPACING_POINTS

            // Name and Signature side by side, Date beneath.
            canvas.drawLine(left, y, left + ruleWidth, y, rule)
            canvas.drawLine(secondColumn, y, secondColumn + ruleWidth, y, rule)
            y += muted.textSize + 2f
            canvas.drawText("Name", left, y, muted)
            canvas.drawText("Signature", secondColumn, y, muted)
            y += CertificateConfig.SECTION_SPACING_POINTS

            canvas.drawLine(left, y, left + ruleWidth, y, rule)
            y += muted.textSize + 2f
            canvas.drawText("Date", left, y, muted)
            y += CertificateConfig.SECTION_SPACING_POINTS
        }

        private fun lineHeight(paint: Paint): Float =
            paint.textSize * CertificateConfig.LINE_SPACING_MULTIPLIER

        private companion object {
            const val BANNER_BORDER_WIDTH = 0.8f
        }
    }
}
