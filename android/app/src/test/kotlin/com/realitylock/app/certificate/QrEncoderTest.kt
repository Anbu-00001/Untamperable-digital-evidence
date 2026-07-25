package com.realitylock.app.certificate

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.realitylock.app.core.config.CertificateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR badge is tested by **decoding it back**, not by asserting the encoder
 * returned something non-null. A QR code that encodes the wrong URL would pass
 * any weaker check and fail silently in the hands of whoever scanned the printed
 * certificate.
 *
 * zxing core is a pure-JVM library, so all of this runs as an ordinary unit test.
 */
class QrEncoderTest {

    @Test
    fun `an encoded verification URL decodes back to itself`() {
        val url = "https://reality-lock.example.org/verify/3f2504e0-4f89-41d3-9a0c-0305e82c3301"

        val decoded = decode(QrEncoder.encodeToMatrix(url))

        assertEquals(url, decoded)
    }

    @Test
    fun `a long URL still round-trips`() {
        // Deployed backends live on long hostnames; the encoder must pick a QR
        // version that fits rather than truncate.
        val url = "https://reality-lock-backend-verification-service.onrender.com/" +
            "verify/3f2504e0-4f89-41d3-9a0c-0305e82c3301"

        assertEquals(url, decode(QrEncoder.encodeToMatrix(url)))
    }

    @Test
    fun `a localhost dev URL round-trips`() {
        val url = "http://127.0.0.1:3000/verify/3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        assertEquals(url, decode(QrEncoder.encodeToMatrix(url)))
    }

    @Test
    fun `the matrix is square at the requested size`() {
        val matrix = QrEncoder.encodeToMatrix("https://example.org/verify/x", sizePx = 256)

        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
    }

    @Test
    fun `the configured error correction is high enough for print`() {
        // Level L (~7%) is the zxing default and is tuned for pristine screens; a
        // certificate gets printed, folded and photocopied.
        assertTrue(CertificateConfig.QR_ERROR_CORRECTION.bits >= 1)
        assertEquals("Q", CertificateConfig.QR_ERROR_CORRECTION.name)
    }

    @Test
    fun `an empty payload is refused rather than silently encoded`() {
        assertThrows(IllegalArgumentException::class.java) { QrEncoder.encodeToMatrix("") }
    }

    @Test
    fun `a non-positive size is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrEncoder.encodeToMatrix("https://example.org", sizePx = 0)
        }
    }

    /** Reads a [BitMatrix] back through zxing's own decoder. */
    private fun decode(matrix: BitMatrix): String {
        val bitmap = BinaryBitmap(HybridBinarizer(BitMatrixLuminanceSource(matrix)))
        return QRCodeReader().decode(bitmap).text
    }
}
