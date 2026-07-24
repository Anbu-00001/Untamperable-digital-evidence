package com.realitylock.app.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.realitylock.app.core.config.CryptoConfig
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Owns the device's event-signing key: an ECDSA P-256 key pair generated inside
 * the Android Keystore, which the private half never leaves.
 *
 * ### Why the key is long-lived, and why that is enough
 * One key is created per install and reused for every capture. The attestation
 * certificate chain proves *once* that this key lives in secure hardware and
 * cannot be exported; because it cannot be exported, any later signature by it
 * must have been produced by that hardware. Per-event freshness comes from the
 * signature covering that event's Merkle root, not from re-attesting each time
 * — re-attesting per capture would add hundreds of milliseconds to the shutter
 * and burn remotely-provisioned attestation keys (see [AttestationOutcome]).
 *
 * ### Tiers, honestly reported
 * StrongBox (a discrete secure element) is requested first and falls back to the
 * TEE. The tier actually obtained is reported rather than assumed — the OnePlus
 * CPH2591 this was developed against has no StrongBox, so the fallback is the
 * normal path there, and a proof package must not imply the stronger tier.
 */
class SigningKeyManager(
    private val keyStore: KeyStore = KeyStore.getInstance(CryptoConfig.ANDROID_KEYSTORE_PROVIDER)
        .apply { load(null) },
    private val random: SecureRandom = SecureRandom(),
) {

    /** Which secure hardware ended up holding the key. */
    enum class SecurityTier { STRONGBOX, TRUSTED_ENVIRONMENT, UNKNOWN }

    /**
     * What happened when attestation was requested.
     *
     * Attestation is **not** allowed to block a capture. On devices using Remote
     * Key Provisioning (Android 13+ mandates it) the supply of attestation
     * certificates can be exhausted, or the device may never have reached
     * Google's provisioning servers — both surface as
     * `ERROR_ATTESTATION_KEYS_UNAVAILABLE`. Losing the ability to record an
     * event because a certificate pool ran dry would be a far worse failure than
     * recording it with the attestation absent and said so plainly.
     */
    sealed interface AttestationOutcome {
        /** A chain rooted in Google's attestation root was obtained. */
        data class Available(val certificateChainDer: List<ByteArray>) : AttestationOutcome

        /** The platform produced only a self-signed leaf — no hardware proof. */
        data object NotAttested : AttestationOutcome

        /**
         * Attestation failed. [retryable] mirrors `KeyStoreException
         * .isTransientFailure()`: true means a later attempt may succeed (no
         * connectivity yet, provisioning pending), false means it will not
         * (device not registered, server refused).
         */
        data class Failed(val reason: String, val retryable: Boolean) : AttestationOutcome
    }

    /** A ready-to-use signing identity plus what could be proven about it. */
    data class SigningIdentity(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val tier: SecurityTier,
        val attestation: AttestationOutcome,
    )

    /**
     * Returns the existing signing identity, creating it on first use.
     *
     * @param forceRegenerate deletes and recreates the key. Used by diagnostics
     *        to re-run attestation; never during a capture, since a new key
     *        would orphan the signatures of everything already recorded.
     */
    fun getOrCreate(forceRegenerate: Boolean = false): SigningIdentity {
        if (forceRegenerate) {
            runCatching { keyStore.deleteEntry(CryptoConfig.SIGNING_KEY_ALIAS) }
        }
        if (!keyStore.containsAlias(CryptoConfig.SIGNING_KEY_ALIAS)) {
            generate()
        }
        return load()
    }

    /** True once a signing key exists on this device. */
    fun exists(): Boolean = keyStore.containsAlias(CryptoConfig.SIGNING_KEY_ALIAS)

    // -- generation ---------------------------------------------------------

    /** Set when attestation was requested but the platform refused it. */
    private var attestationFailure: AttestationOutcome.Failed? = null

    private fun generate() {
        val challenge = ByteArray(CryptoConfig.ATTESTATION_CHALLENGE_BYTES).also(random::nextBytes)
        attestationFailure = null

        // Preference order: StrongBox+attested → TEE+attested → TEE unattested.
        val attempts = listOf(
            { generateWith(challenge, useStrongBox = true) },
            { generateWith(challenge, useStrongBox = false) },
        )
        for (attempt in attempts) {
            clearPartialKey()
            val result = runCatching(attempt)
            if (result.isSuccess) return
            val failure = result.exceptionOrNull() ?: continue
            // Missing StrongBox is expected on most devices — keep going quietly.
            if (isStrongBoxUnavailable(failure)) continue
            val classified = classifyAttestationFailure(failure)
            if (classified == null) throw failure   // not an attestation problem
            attestationFailure = classified
        }

        // Attestation could not be produced. Record why, then generate an
        // unattested key so captures still work: an event recorded without a
        // hardware proof is far better than no event at all, and the package
        // says which it is rather than implying attestation it never had.
        clearPartialKey()
        generateWith(challenge = null, useStrongBox = false)
    }

    private fun clearPartialKey() {
        runCatching { keyStore.deleteEntry(CryptoConfig.SIGNING_KEY_ALIAS) }
    }

    /**
     * Maps a Keystore generation failure onto [AttestationOutcome.Failed], or
     * null when the failure was not about attestation.
     *
     * The interesting code is `ERROR_ATTESTATION_KEYS_UNAVAILABLE` (16): on
     * Remote Key Provisioning devices the pool of attestation certificates can
     * run dry. `isTransientFailure()` is the platform's own answer to whether
     * retrying can help — no connectivity yet says yes, server refusal says no.
     * Both are exposed only from API 33, so older devices get a generic reason.
     */
    private fun classifyAttestationFailure(t: Throwable): AttestationOutcome.Failed? {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is android.security.KeyStoreException) {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    AttestationOutcome.Failed(
                        reason = "${cause.javaClass.simpleName}(${cause.numericErrorCode}): ${cause.message}",
                        retryable = cause.isTransientFailure,
                    )
                } else {
                    AttestationOutcome.Failed(
                        reason = cause.message ?: cause.javaClass.simpleName,
                        retryable = false,
                    )
                }
            }
            cause = cause.cause
        }
        // Some OEMs surface attestation refusal as a bare ProviderException.
        val message = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString("; ")
        return if (message.contains("attestation", ignoreCase = true)) {
            AttestationOutcome.Failed(reason = message, retryable = false)
        } else {
            null
        }
    }

    /** [challenge] null generates an unattested key (the last-resort path). */
    private fun generateWith(challenge: ByteArray?, useStrongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(
            CryptoConfig.SIGNING_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(CryptoConfig.EC_CURVE_P256))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // Evidence capture must work on a locked screen and without a
            // biometric prompt at the shutter, so no auth requirement is set.
            .setUserAuthenticationRequired(false)

        // Requesting attestation is what makes the Keystore emit a
        // Google-rooted certificate chain instead of a self-signed leaf.
        if (challenge != null) builder.setAttestationChallenge(challenge)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        KeyPairGenerator.getInstance(
            CryptoConfig.KEY_ALGORITHM,
            CryptoConfig.ANDROID_KEYSTORE_PROVIDER,
        ).apply {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    private fun isStrongBoxUnavailable(t: Throwable): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && t is StrongBoxUnavailableException

    // -- loading ------------------------------------------------------------

    private fun load(): SigningIdentity {
        val privateKey = keyStore.getKey(CryptoConfig.SIGNING_KEY_ALIAS, null) as PrivateKey
        val chain = keyStore.getCertificateChain(CryptoConfig.SIGNING_KEY_ALIAS).orEmpty()
        val leaf = chain.firstOrNull() as? X509Certificate
            ?: error("Keystore returned no certificate for ${CryptoConfig.SIGNING_KEY_ALIAS}")

        return SigningIdentity(
            privateKey = privateKey,
            publicKey = leaf.publicKey,
            tier = tierOf(privateKey),
            attestation = attestationOf(chain.map { it.encoded }),
        )
    }

    /**
     * A chain longer than the leaf means the Keystore attested the key. A
     * single self-signed certificate means it did not — that is the software
     * fallback, and it proves nothing about hardware.
     */
    private fun attestationOf(chainDer: List<ByteArray>): AttestationOutcome = when {
        chainDer.size > 1 -> AttestationOutcome.Available(chainDer)
        else -> attestationFailure ?: AttestationOutcome.NotAttested
    }

    private fun tierOf(privateKey: PrivateKey): SecurityTier {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return SecurityTier.UNKNOWN
        return runCatching {
            val factory = java.security.KeyFactory.getInstance(
                privateKey.algorithm,
                CryptoConfig.ANDROID_KEYSTORE_PROVIDER,
            )
            val info = factory.getKeySpec(privateKey, android.security.keystore.KeyInfo::class.java)
            when (info.securityLevel) {
                android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX ->
                    SecurityTier.STRONGBOX
                android.security.keystore.KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                    SecurityTier.TRUSTED_ENVIRONMENT
                else -> SecurityTier.UNKNOWN
            }
        }.getOrDefault(SecurityTier.UNKNOWN)
    }
}
