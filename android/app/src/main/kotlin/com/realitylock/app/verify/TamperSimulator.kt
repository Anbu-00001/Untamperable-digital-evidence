package com.realitylock.app.verify

import com.realitylock.app.core.config.CryptoConfig
import com.realitylock.app.crypto.Hashing
import com.realitylock.app.crypto.MerkleTree
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Random

/**
 * The "try to tamper" demonstration behind the proof explorer.
 *
 * It does exactly what an attacker would have to get away with, on a COPY held in
 * memory: flip one bit of the photo (or of the canonical record), re-hash it,
 * re-derive the Merkle root, and ask the stored signature whether it still holds.
 * The hashing and the ECDSA check are the real ones — the same SHA-256, the same
 * [MerkleTree.root2Leaf] and the same `SHA256withECDSA` verification the backend
 * and [OfflineProofVerifier] run — so the outcome is computed, not staged.
 *
 * Nothing is written anywhere. The stored photo, the stored record and the sync
 * state are never touched; the UI says "simulation" in so many words.
 */
object TamperSimulator {

    enum class Target { PHOTO, RECORD }

    /** What the package itself claims; everything else is recomputed from the bytes. */
    data class Claims(
        val mediaLeafHex: String,
        val metadataLeafHex: String,
        val rootHex: String,
        val signatureBase64: String,
        val publicKeyBase64: String,
    )

    /** The untouched package, re-checked right now on this phone. */
    data class Baseline(
        val recomputedMediaHex: String?,
        val recomputedMetadataHex: String,
        val mediaOk: Boolean?,
        val metadataOk: Boolean,
        val rootOk: Boolean,
        val signatureOk: Boolean,
    )

    data class Outcome(
        val target: Target,
        val flippedBit: Long,
        val totalBits: Long,
        val recomputedLeafHex: String,
        val tamperedLeafHex: String,
        /** Hex positions (of 64) that differ between the honest and the tampered digest. */
        val changedDigits: Int,
        val tamperedRootHex: String,
        val rootStillMatches: Boolean,
        val signatureStillValid: Boolean,
    )

    /** `mediaBytes` is null when the photo is no longer on this phone. */
    fun baseline(claims: Claims, mediaBytes: ByteArray?, canonicalMetadata: String): Baseline {
        val media = mediaBytes?.let { Hashing.toHex(Hashing.sha256(it)) }
        val metadata = Hashing.toHex(Hashing.sha256(canonicalMetadata))
        return Baseline(
            recomputedMediaHex = media,
            recomputedMetadataHex = metadata,
            mediaOk = media?.let { it == claims.mediaLeafHex },
            metadataOk = metadata == claims.metadataLeafHex,
            rootOk = runCatching {
                MerkleTree.root2Leaf(claims.mediaLeafHex, claims.metadataLeafHex) == claims.rootHex
            }.getOrDefault(false),
            signatureOk = verifySignature(claims.rootHex, claims.signatureBase64, claims.publicKeyBase64),
        )
    }

    /** Null when the chosen target has no bytes to flip. */
    fun simulate(
        target: Target,
        claims: Claims,
        mediaBytes: ByteArray?,
        canonicalMetadata: String,
        random: Random = Random(),
    ): Outcome? {
        val original: ByteArray = when (target) {
            Target.PHOTO -> mediaBytes ?: return null
            Target.RECORD -> canonicalMetadata.toByteArray(Charsets.UTF_8)
        }
        if (original.isEmpty()) return null

        val totalBits = original.size.toLong() * 8L
        val bit = (random.nextLong() and Long.MAX_VALUE) % totalBits
        val tampered = original.copyOf()
        tampered[(bit / 8).toInt()] = (tampered[(bit / 8).toInt()].toInt() xor (1 shl (bit % 8).toInt())).toByte()

        val honest = Hashing.toHex(Hashing.sha256(original))
        val altered = Hashing.toHex(Hashing.sha256(tampered))
        val newRoot = when (target) {
            Target.PHOTO -> MerkleTree.root2Leaf(altered, claims.metadataLeafHex)
            Target.RECORD -> MerkleTree.root2Leaf(claims.mediaLeafHex, altered)
        }
        return Outcome(
            target = target,
            flippedBit = bit,
            totalBits = totalBits,
            recomputedLeafHex = honest,
            tamperedLeafHex = altered,
            changedDigits = honest.indices.count { honest[it] != altered[it] },
            tamperedRootHex = newRoot,
            rootStillMatches = newRoot == claims.rootHex,
            signatureStillValid = verifySignature(newRoot, claims.signatureBase64, claims.publicKeyBase64),
        )
    }

    /** `SHA256withECDSA` over the raw root bytes against the package's own public key. */
    fun verifySignature(rootHex: String, signatureBase64: String, publicKeyBase64: String): Boolean =
        runCatching {
            val key = KeyFactory.getInstance(CryptoConfig.KEY_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
            Signature.getInstance(CryptoConfig.SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(Hashing.fromHex(rootHex))
                verify(Base64.getDecoder().decode(signatureBase64))
            }
        }.getOrDefault(false)
}
