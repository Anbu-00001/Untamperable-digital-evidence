package com.realitylock.app.crypto

import com.realitylock.app.core.config.CryptoConfig

/**
 * Merkle composition for the proof package (ADR-0001: 2-leaf now, 5-leaf as the
 * selective-disclosure target).
 *
 * ### The concatenation is defined precisely, on purpose
 * `PROOF_PACKAGE_SPEC.md` writes the rule as `root = SHA-256(mediaHash ‖
 * metadataHash)`. The `‖` is ambiguous — raw digest bytes or their hex
 * rendering? — and the Android producer and Node verifier must agree exactly or
 * every signature check fails for reasons that look like a crypto bug.
 *
 * **The rule is: concatenate the two raw 32-byte digests, in the fixed order
 * `[media, metadata]`, and hash the resulting 64 bytes.** Not the hex strings,
 * and not sorted or length-prefixed. [CROSS_IMPL_TEST_VECTOR] pins this so both
 * implementations are checked against the same known answer rather than against
 * each other.
 *
 * Order is fixed rather than sorted so the tree is positional: swapping the
 * media and metadata hashes must produce a different root.
 */
object MerkleTree {

    /**
     * 2-leaf root over the media and canonical-metadata digests, both given as
     * lowercase hex. Returns the root as lowercase hex.
     */
    fun root2Leaf(mediaHashHex: String, metadataHashHex: String): String {
        val media = requireDigest(mediaHashHex, "mediaHash")
        val metadata = requireDigest(metadataHashHex, "metadataHash")
        return Hashing.toHex(Hashing.sha256(media + metadata))
    }

    private fun requireDigest(hex: String, label: String): ByteArray {
        val bytes = Hashing.fromHex(hex)
        require(bytes.size == CryptoConfig.HASH_SIZE_BYTES) {
            "$label must be ${CryptoConfig.HASH_SIZE_BYTES} bytes, was ${bytes.size}"
        }
        return bytes
    }

    /**
     * A fixed input/output pair, asserted by both the Kotlin unit tests and the
     * backend's own test. If either implementation ever drifts, one of the two
     * fails immediately and names this constant — rather than surfacing later as
     * an unexplained signature mismatch on a real capture.
     *
     * `media` = SHA-256("reality-lock-media-test-vector")
     * `metadata` = SHA-256("reality-lock-metadata-test-vector")
     * `root` = SHA-256(rawBytes(media) ‖ rawBytes(metadata))
     */
    object CROSS_IMPL_TEST_VECTOR {
        const val MEDIA_INPUT: String = "reality-lock-media-test-vector"
        const val METADATA_INPUT: String = "reality-lock-metadata-test-vector"

        const val MEDIA_HASH: String =
            "0c8655110a97d6ffb2f8ae15d551e4cff818b5e6e05e6260f39842426a942fea"
        const val METADATA_HASH: String =
            "695cadf134d0a6cee0afd0480f31378d211c21189d38f4dcfdf3a5bdfdabb391"
        const val ROOT: String =
            "63e7fd2d0841a4776b1ddba3dc9503c9a91779e862b62bdffdaf828b6c792270"

        /** Same two digests in the wrong order — must NOT equal [ROOT]. */
        const val ROOT_WITH_LEAVES_SWAPPED: String =
            "89e64f139506d0893f4cbca9a77cd223937cb801977704145e1d0165e8feca39"
    }
}
