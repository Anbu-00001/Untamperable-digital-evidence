package com.realitylock.app.crypto

import android.util.Base64
import android.util.Log
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * One-shot diagnostic that creates (or loads) the signing key and reports what
 * the platform was actually willing to prove about it.
 *
 * This exists because the whole Phase-3 design rests on an assumption that can
 * only be settled on real hardware: that this device produces a genuine,
 * Google-rooted attestation chain. Some OEMs ship broken implementations, and
 * on Remote Key Provisioning devices the certificate pool can be empty. Finding
 * that out from a diagnostic is cheap; finding out from a failed demo is not.
 *
 * The full chain is written to logcat under [LOG_TAG] so it can be pulled off
 * the device and fed to the backend verifier during development.
 */
class AttestationProbe(private val keyManager: SigningKeyManager = SigningKeyManager()) {

    data class Result(
        val tier: SigningKeyManager.SecurityTier,
        val attested: Boolean,
        val chainLength: Int,
        /** Subject/issuer summary of each certificate, leaf first. */
        val chainSummary: List<String>,
        val detail: String,
    )

    /**
     * @param exportTo optional file to write the base64 certificate chain to.
     *   Logcat drops or truncates multi-kilobyte lines, so a file is the
     *   reliable way to get the chain off the device for backend verification.
     */
    fun run(forceRegenerate: Boolean = false, exportTo: java.io.File? = null): Result {
        val identity = runCatching { keyManager.getOrCreate(forceRegenerate) }
            .getOrElse { t ->
                Log.e(LOG_TAG, "signing key unavailable", t)
                return Result(
                    tier = SigningKeyManager.SecurityTier.UNKNOWN,
                    attested = false,
                    chainLength = 0,
                    chainSummary = emptyList(),
                    detail = "key generation failed: ${t.message ?: t.javaClass.simpleName}",
                )
            }

        val attestation = identity.attestation
        val chainDer = (attestation as? SigningKeyManager.AttestationOutcome.Available)
            ?.certificateChainDer
            .orEmpty()

        val summary = chainDer.mapNotNull { der ->
            runCatching {
                val cert = CertificateFactory.getInstance(X509_TYPE)
                    .generateCertificate(der.inputStream()) as X509Certificate
                "subject=${cert.subjectX500Principal.name} issuer=${cert.issuerX500Principal.name}"
            }.getOrNull()
        }

        val detail = when (attestation) {
            is SigningKeyManager.AttestationOutcome.Available ->
                "attested with ${chainDer.size} certificates"
            is SigningKeyManager.AttestationOutcome.NotAttested ->
                "self-signed leaf only — no hardware proof"
            is SigningKeyManager.AttestationOutcome.Failed ->
                "attestation failed (retryable=${attestation.retryable}): ${attestation.reason}"
        }

        Log.i(LOG_TAG, "tier=${identity.tier} $detail")
        summary.forEachIndexed { i, s -> Log.i(LOG_TAG, "cert[$i] $s") }

        if (exportTo != null) {
            runCatching {
                val certs = chainDer.joinToString(",\n") {
                    "    \"${Base64.encodeToString(it, Base64.NO_WRAP)}\""
                }
                exportTo.writeText(
                    """
                    {
                      "tier": "${identity.tier}",
                      "attested": ${attestation is SigningKeyManager.AttestationOutcome.Available},
                      "detail": ${org.json.JSONObject.quote(detail)},
                      "certificateChainBase64": [
                    $certs
                      ]
                    }
                    """.trimIndent(),
                )
                Log.i(LOG_TAG, "chain exported to ${exportTo.absolutePath}")
            }.onFailure { Log.w(LOG_TAG, "could not export chain", it) }
        }

        return Result(
            tier = identity.tier,
            attested = attestation is SigningKeyManager.AttestationOutcome.Available,
            chainLength = chainDer.size,
            chainSummary = summary,
            detail = detail,
        )
    }

    companion object {
        const val LOG_TAG: String = "RealityLockAttest"
        private const val X509_TYPE = "X.509"
    }
}
