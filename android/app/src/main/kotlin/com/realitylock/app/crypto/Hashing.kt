package com.realitylock.app.crypto

import com.realitylock.app.core.config.CryptoConfig
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 primitives for the proof pipeline.
 *
 * Everything here is deliberately pure and framework-free so it runs in plain
 * JVM unit tests — the hash contract is the part the Android producer and the
 * Node verifier must agree on byte-for-byte, so it is the part that most needs
 * cheap, fast tests.
 *
 * **Hex is lowercase**, matching the schema's `sha256hex` pattern
 * `^[0-9a-f]{64}$`. Digest *bytes* (not hex strings) are what get concatenated
 * during Merkle composition — see [MerkleTree].
 */
object Hashing {

    /** SHA-256 of [bytes] as raw digest bytes. */
    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance(CryptoConfig.HASH_ALGORITHM).digest(bytes)

    /** SHA-256 of [text]'s UTF-8 encoding — used for canonical JSON. */
    fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))

    /**
     * SHA-256 of a stream, read in fixed-size chunks so media is never held in
     * memory in full. The caller owns closing [input].
     */
    fun sha256Stream(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance(CryptoConfig.HASH_ALGORITHM)
        val buffer = ByteArray(CryptoConfig.HASH_STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }

    /** SHA-256 of a file's contents, streamed. */
    fun sha256File(file: File): ByteArray = file.inputStream().use(::sha256Stream)

    /** Lowercase hex, as the schema requires. */
    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /**
     * Parses lowercase or uppercase hex back into bytes. Needed because Merkle
     * composition works on digest bytes while the proof package carries hex.
     */
    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length, was ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], HEX_RADIX)
            val lo = Character.digit(hex[i * 2 + 1], HEX_RADIX)
            require(hi >= 0 && lo >= 0) { "not a hex string: $hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    private const val HEX_RADIX = 16
    private const val HEX_DIGITS = "0123456789abcdef"
}
