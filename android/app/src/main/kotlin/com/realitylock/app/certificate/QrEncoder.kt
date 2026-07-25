package com.realitylock.app.certificate

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.realitylock.app.core.config.CertificateConfig

/**
 * Generates the QR verification badge (research/05 §6).
 *
 * Uses `com.google.zxing:core` directly — not `zxing-android-embedded`, which is
 * a *scanner* (a capture Activity, a camera preview, and the camera permission
 * that comes with them). Phase 5 only ever encodes, so the wrapper would be a
 * camera-permission-bearing dependency for a feature that does not exist
 * (ADR-0006 §4).
 *
 * [encodeToMatrix] is pure zxing and therefore runs in ordinary JVM unit tests —
 * including decoding its own output back to the original text, which is a far
 * stronger check than asserting the bitmap is non-null.
 */
object QrEncoder {

    /**
     * Encodes [text] as a QR bit matrix.
     *
     * Error correction is set explicitly rather than left to the default: a
     * certificate is printed, and print gets smudged, folded and photocopied.
     * See [CertificateConfig.QR_ERROR_CORRECTION].
     */
    fun encodeToMatrix(
        text: String,
        sizePx: Int = CertificateConfig.QR_SIZE_PX,
        errorCorrection: ErrorCorrectionLevel = CertificateConfig.QR_ERROR_CORRECTION,
    ): BitMatrix {
        require(text.isNotEmpty()) { "refusing to encode an empty QR payload" }
        require(sizePx > 0) { "QR size must be positive, got $sizePx" }

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to errorCorrection,
            EncodeHintType.MARGIN to CertificateConfig.QR_QUIET_ZONE_MODULES,
            // The payload is a URL: explicit UTF-8 avoids any platform-default
            // charset guessing between encoder and scanner.
            EncodeHintType.CHARACTER_SET to CertificateConfig.QR_CHARACTER_SET,
        )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /** Renders [text] as a QR bitmap ready to draw into the PDF or the UI. */
    fun encodeToBitmap(text: String, sizePx: Int = CertificateConfig.QR_SIZE_PX): Bitmap {
        val matrix = encodeToMatrix(text, sizePx)
        return matrix.toBitmap()
    }

    /**
     * ARGB_8888 rather than a 1-bit config: PdfDocument's canvas and Compose both
     * want a standard bitmap, and at QR sizes the memory difference is irrelevant.
     */
    private fun BitMatrix.toBitmap(): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
