package com.realitylock.app.certificate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.realitylock.app.core.config.CertificateConfig
import java.io.ByteArrayOutputStream

/**
 * Renders a [CertificateContent] to a PDF using Android's built-in
 * `PdfDocument` (research/05 §7) — no third-party PDF library.
 *
 * Returns bytes rather than writing a file, so the caller decides where the
 * document goes (the UI hands it to the system "save as" dialog) and so the
 * output can be asserted on in an instrumented test.
 *
 * The framing text is drawn **before** the technical detail, in a boxed banner.
 * That ordering is deliberate: a reader who skims must see what the document does
 * not claim before they see a hash and a "VERIFIED".
 */
class CertificateRenderer {

    fun render(content: CertificateContent): ByteArray {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(
                CertificateConfig.PAGE_WIDTH_POINTS,
                CertificateConfig.PAGE_HEIGHT_POINTS,
                PAGE_NUMBER,
            ).create()
            val page = document.startPage(pageInfo)
            drawPage(page.canvas, content)
            document.finishPage(page)

            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            // PdfDocument holds native memory; leaking it on an exception path
            // would be invisible until the app started dying under repeat exports.
            document.close()
        }
    }

    private fun drawPage(canvas: Canvas, content: CertificateContent) {
        val left = CertificateConfig.PAGE_MARGIN_POINTS
        val right = CertificateConfig.PAGE_WIDTH_POINTS - CertificateConfig.PAGE_MARGIN_POINTS
        val contentWidth = right - left
        var y = CertificateConfig.PAGE_MARGIN_POINTS + CertificateConfig.TEXT_SIZE_TITLE

        val title = paint(CertificateConfig.TEXT_SIZE_TITLE, bold = true)
        val heading = paint(CertificateConfig.TEXT_SIZE_HEADING, bold = true)
        val body = paint(CertificateConfig.TEXT_SIZE_BODY)
        val mono = paint(CertificateConfig.TEXT_SIZE_MONO, mono = true)
        val muted = paint(CertificateConfig.TEXT_SIZE_BODY).apply { color = Color.DKGRAY }

        canvas.drawText(content.title, left, y, title)
        y += lineHeight(title)

        canvas.drawText("Verdict: ${content.verdictLabel}", left, y, heading)
        y += lineHeight(heading) + CertificateConfig.SECTION_SPACING_POINTS

        // ---- framing banner, first and unmissable ----------------------------
        y = drawFramingBanner(canvas, content.framing, left, y, contentWidth, body)
        y += CertificateConfig.SECTION_SPACING_POINTS

        // ---- event identity --------------------------------------------------
        y = drawSection(canvas, "Event", left, y, heading)
        val rows = listOf(
            "Event ID" to content.eventId,
            "Captured at (UTC)" to content.capturedAtIso,
            "Device" to content.deviceDescription,
            "Location" to content.locationSummary,
        )
        for ((label, value) in rows) {
            y = drawKeyValue(canvas, label, value, left, y, contentWidth, body, body)
        }
        y += CertificateConfig.SECTION_SPACING_POINTS

        // ---- cryptographic detail -------------------------------------------
        y = drawSection(canvas, "Cryptographic record", left, y, heading)
        val cryptoRows = listOf(
            "Hash algorithm" to content.hashAlgorithm,
            "Signature" to content.signatureAlgorithm,
            "Media SHA-256" to content.mediaSha256,
            "Merkle root" to content.merkleRoot,
        )
        for ((label, value) in cryptoRows) {
            y = drawKeyValue(canvas, label, value, left, y, contentWidth, body, mono)
        }
        y += CertificateConfig.SECTION_SPACING_POINTS

        // ---- per-check breakdown --------------------------------------------
        if (content.checkRows.isNotEmpty()) {
            y = drawSection(canvas, "Verification checks", left, y, heading)
            for ((label, outcome) in content.checkRows) {
                y = drawKeyValue(canvas, label, outcome, left, y, contentWidth, body, body)
            }
            y += CertificateConfig.SECTION_SPACING_POINTS
        }

        // ---- advisories ------------------------------------------------------
        if (content.advisories.isNotEmpty()) {
            y = drawSection(canvas, "Advisories", left, y, heading)
            for (advisory in content.advisories) {
                y = drawParagraph(canvas, "• $advisory", left, y, contentWidth, body)
            }
            y += CertificateConfig.SECTION_SPACING_POINTS
        }

        // ---- QR badge, bottom-right -----------------------------------------
        val qrTop = CertificateConfig.PAGE_HEIGHT_POINTS -
            CertificateConfig.PAGE_MARGIN_POINTS -
            CertificateConfig.QR_DRAW_SIZE_POINTS
        val qrLeft = right - CertificateConfig.QR_DRAW_SIZE_POINTS
        val qr = QrEncoder.encodeToBitmap(content.verificationUrl)
        canvas.drawBitmap(
            qr,
            null,
            Rect(
                qrLeft.toInt(),
                qrTop.toInt(),
                right.toInt(),
                (qrTop + CertificateConfig.QR_DRAW_SIZE_POINTS).toInt(),
            ),
            null,
        )
        qr.recycle()

        // The URL in text as well as in the QR: a scanner is a convenience, not a
        // requirement, and a printed page should stay usable without one.
        var footerY = qrTop
        footerY = drawParagraph(
            canvas,
            "Verify at: ${content.verificationUrl}",
            left,
            footerY,
            contentWidth - CertificateConfig.QR_DRAW_SIZE_POINTS - CertificateConfig.SECTION_SPACING_POINTS,
            mono,
        )
        drawParagraph(
            canvas,
            "Generated ${content.generatedAtIso}",
            left,
            footerY,
            contentWidth - CertificateConfig.QR_DRAW_SIZE_POINTS,
            muted,
        )
    }

    /** Draws the caveats inside a visible box, so they read as part of the record. */
    private fun drawFramingBanner(
        canvas: Canvas,
        framing: List<String>,
        left: Float,
        top: Float,
        width: Float,
        paint: Paint,
    ): Float {
        val padding = CertificateConfig.SECTION_SPACING_POINTS / 2f
        val innerWidth = width - 2 * padding
        val lines = framing.flatMap { line ->
            TextWrapper.wrap("• $line", innerWidth, paint::measureText)
        }
        val boxHeight = lines.size * lineHeight(paint) + 2 * padding

        val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = BANNER_BORDER_WIDTH
            color = Color.BLACK
        }
        canvas.drawRect(left, top, left + width, top + boxHeight, border)

        var y = top + padding + paint.textSize
        for (line in lines) {
            canvas.drawText(line, left + padding, y, paint)
            y += lineHeight(paint)
        }
        return top + boxHeight
    }

    private fun drawSection(canvas: Canvas, label: String, left: Float, top: Float, paint: Paint): Float {
        canvas.drawText(label, left, top + paint.textSize, paint)
        return top + lineHeight(paint)
    }

    /**
     * A label/value row where the value wraps within the remaining width. Hashes
     * are long, so wrapping is the normal case rather than the exception.
     */
    private fun drawKeyValue(
        canvas: Canvas,
        label: String,
        value: String,
        left: Float,
        top: Float,
        width: Float,
        labelPaint: Paint,
        valuePaint: Paint,
    ): Float {
        val valueLeft = left + CertificateConfig.LABEL_COLUMN_WIDTH_POINTS
        val valueWidth = width - CertificateConfig.LABEL_COLUMN_WIDTH_POINTS
        val lines = TextWrapper.wrap(value.ifEmpty { "—" }, valueWidth, valuePaint::measureText)

        var y = top + labelPaint.textSize
        canvas.drawText(label, left, y, labelPaint)
        for (line in lines) {
            canvas.drawText(line, valueLeft, y, valuePaint)
            y += lineHeight(valuePaint)
        }
        return y - valuePaint.textSize
    }

    private fun drawParagraph(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        width: Float,
        paint: Paint,
    ): Float {
        var y = top + paint.textSize
        for (line in TextWrapper.wrap(text, width, paint::measureText)) {
            canvas.drawText(line, left, y, paint)
            y += lineHeight(paint)
        }
        return y - paint.textSize
    }

    private fun lineHeight(paint: Paint): Float =
        paint.textSize * CertificateConfig.LINE_SPACING_MULTIPLIER

    private fun paint(size: Float, bold: Boolean = false, mono: Boolean = false): Paint = Paint().apply {
        isAntiAlias = true
        textSize = size
        color = Color.BLACK
        typeface = Typeface.create(
            if (mono) Typeface.MONOSPACE else Typeface.DEFAULT,
            if (bold) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    private companion object {
        const val PAGE_NUMBER = 1
        const val BANNER_BORDER_WIDTH = 0.8f
    }
}
