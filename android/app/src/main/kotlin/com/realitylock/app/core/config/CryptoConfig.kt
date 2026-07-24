package com.realitylock.app.core.config

import android.security.keystore.KeyProperties

/**
 * Single source of truth for every cryptographic parameter in the proof
 * pipeline. No algorithm string, curve name, or key alias is ever written
 * inline anywhere else in the codebase — feature code references these
 * constants so a change happens in exactly one place.
 *
 * Rationale for each choice: research/02_cryptography_security_architecture.md
 * and docs/design/adr/ADR-0001-merkle-tree-leaves.md.
 */
object CryptoConfig {

    /** Hash for media bytes, canonical metadata, and Merkle composition (02 §1). */
    const val HASH_ALGORITHM: String = "SHA-256"
    const val HASH_SIZE_BYTES: Int = 32

    /** JCA signature algorithm — ECDSA over NIST P-256 (02 §2). */
    const val SIGNATURE_ALGORITHM: String = "SHA256withECDSA"

    /** Key algorithm ("EC") sourced from the SDK constant, plus the P-256 curve. */
    val KEY_ALGORITHM: String = KeyProperties.KEY_ALGORITHM_EC
    const val EC_CURVE_P256: String = "secp256r1"

    /** Android Keystore provider and the alias under which the signing key lives. */
    const val ANDROID_KEYSTORE_PROVIDER: String = "AndroidKeyStore"
    const val SIGNING_KEY_ALIAS: String = "reality_lock_event_signing_key"

    /**
     * Merkle leaf strategy (ADR-0001). v1 IMPLEMENTS the 2-leaf root
     * [media, metadata]; the 5-leaf design [media, location, timestamp,
     * motion, device] is the documented target enabling selective disclosure.
     */
    const val MERKLE_LEAVES_IMPLEMENTED: Int = 2
    const val MERKLE_LEAVES_TARGET: Int = 5

    private const val MERKLE_SCHEME_SUFFIX: String = "-leaf"

    /**
     * The `merkle.scheme` values the proof-package schema accepts
     * (`enum: ["2-leaf", "5-leaf"]`). Derived from the leaf counts above rather
     * than written out again, so the string and the number cannot disagree —
     * Phase 3 writes one of these into every package.
     */
    const val MERKLE_SCHEME_IMPLEMENTED: String = "$MERKLE_LEAVES_IMPLEMENTED$MERKLE_SCHEME_SUFFIX"
    const val MERKLE_SCHEME_TARGET: String = "$MERKLE_LEAVES_TARGET$MERKLE_SCHEME_SUFFIX"

    /** `signature.publicKey.format` — Java's `PublicKey.getEncoded()` is X.509 SPKI. */
    const val PUBLIC_KEY_FORMAT: String = "X.509"

    /**
     * Bytes of randomness in the Key Attestation challenge, proving the
     * attestation was produced for this install rather than replayed. 32 bytes
     * matches the hash size and stays far below the platform limit that raises
     * `ERROR_ATTESTATION_CHALLENGE_TOO_LARGE`.
     */
    const val ATTESTATION_CHALLENGE_BYTES: Int = 32

    /**
     * Streaming buffer for media hashing. Media is hashed in chunks so a large
     * video never has to be resident in memory (research/02 §8 Step 2).
     */
    const val HASH_STREAM_BUFFER_BYTES: Int = 8 * 1024
}
