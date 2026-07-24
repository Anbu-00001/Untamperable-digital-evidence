package com.realitylock.app.crypto

import android.util.Base64
import com.realitylock.app.core.config.CryptoConfig
import java.security.Signature

/**
 * Signs a proof package's Merkle root with the device's hardware-backed key.
 *
 * ### What exactly gets signed
 * The **raw 32 bytes** of `merkle.root` — not its hex rendering. `SHA256withECDSA`
 * then hashes those bytes internally, so the signature is over
 * `SHA-256(rootBytes)`. Stated here because a verifier that fed the hex string
 * instead would compute a valid-looking signature over the wrong input and fail
 * for reasons indistinguishable from tampering.
 *
 * Signing uses `java.security.Signature` rather than Tink: the private key is
 * a non-exportable handle inside the Android Keystore, and the JCA `Signature`
 * API is the supported way to use such a key. Tink's advantage is safe key
 * material handling, which is moot when the key material is never in the
 * process to begin with (ADR-0004).
 */
class EventSigner(private val keyManager: SigningKeyManager) {

    /** A signature plus everything a verifier needs to check it. */
    data class SignedRoot(
        /** Base64 DER ECDSA signature over the root bytes. */
        val signatureBase64: String,
        /** Base64 X.509 SubjectPublicKeyInfo of the verifying key. */
        val publicKeyBase64: String,
        /** Base64 DER certificates, leaf first; null when unattested. */
        val attestationChainBase64: List<String>?,
        val tier: SigningKeyManager.SecurityTier,
        val attestation: SigningKeyManager.AttestationOutcome,
    )

    /** Signs [rootHashHex], the `merkle.root` of the package being assembled. */
    fun sign(rootHashHex: String): SignedRoot {
        val identity = keyManager.getOrCreate()
        val rootBytes = Hashing.fromHex(rootHashHex)
        require(rootBytes.size == CryptoConfig.HASH_SIZE_BYTES) {
            "merkle.root must be ${CryptoConfig.HASH_SIZE_BYTES} bytes, was ${rootBytes.size}"
        }

        val signatureBytes = Signature.getInstance(CryptoConfig.SIGNATURE_ALGORITHM).run {
            initSign(identity.privateKey)
            update(rootBytes)
            sign()
        }

        val chain = (identity.attestation as? SigningKeyManager.AttestationOutcome.Available)
            ?.certificateChainDer
            ?.map(::base64)

        return SignedRoot(
            signatureBase64 = base64(signatureBytes),
            publicKeyBase64 = base64(identity.publicKey.encoded),
            attestationChainBase64 = chain,
            tier = identity.tier,
            attestation = identity.attestation,
        )
    }

    /**
     * Verifies a signature on-device. The authoritative check is the backend's,
     * but this lets the app prove locally that what it just wrote is verifiable
     * — and gives the tamper-detection test something to assert against.
     */
    fun verify(rootHashHex: String, signatureBase64: String, publicKeyBase64: String): Boolean =
        runCatching {
            val keySpec = java.security.spec.X509EncodedKeySpec(decode(publicKeyBase64))
            val publicKey = java.security.KeyFactory
                .getInstance(CryptoConfig.KEY_ALGORITHM)
                .generatePublic(keySpec)
            Signature.getInstance(CryptoConfig.SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(Hashing.fromHex(rootHashHex))
                verify(decode(signatureBase64))
            }
        }.getOrDefault(false)

    private companion object {
        /**
         * NO_WRAP so the encoding is a single line — the schema declares these
         * fields `contentEncoding: base64`, and embedded newlines would survive
         * into the JSON and change the canonical bytes.
         */
        fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    }
}
