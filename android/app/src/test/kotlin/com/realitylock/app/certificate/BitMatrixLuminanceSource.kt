package com.realitylock.app.certificate

import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix

/**
 * Adapts a [BitMatrix] to zxing's [LuminanceSource] so an encoded QR can be fed
 * straight back into the decoder in a JVM unit test.
 *
 * zxing ships no such adapter: its own sources read from a camera frame or an
 * `java.awt.BufferedImage`, and AWT is not on the Android unit-test classpath.
 * This is a test fixture, not production code — hence its place under `src/test`.
 */
internal class BitMatrixLuminanceSource(private val matrix: BitMatrix) :
    LuminanceSource(matrix.width, matrix.height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = if (row != null && row.size >= width) row else ByteArray(width)
        for (x in 0 until width) {
            // A set module is black (0); an unset one is white (255). zxing's
            // convention is that low luminance means "dark".
            out[x] = if (matrix.get(x, y)) 0 else WHITE
        }
        return out
    }

    override fun getMatrix(): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                out[offset + x] = if (matrix.get(x, y)) 0 else WHITE
            }
        }
        return out
    }

    private companion object {
        const val WHITE: Byte = -1 // 255 as a signed byte
    }
}
