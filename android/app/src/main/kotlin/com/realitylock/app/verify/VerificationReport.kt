package com.realitylock.app.verify

/**
 * The backend's verification answer, as the app displays it.
 *
 * Mirrors the `/verify` response shape (research/02 §8 Step 10) rather than
 * reducing it: the per-check breakdown is the product here. Collapsing five
 * checks into one "Valid / Tampered" boolean would throw away exactly the
 * information that makes a verdict inspectable — "the media was altered" and "we
 * had no copy of the media to check" are completely different statements.
 */
data class VerificationReport(
    val verdict: Verdict,
    /** Ordered by [DISPLAY_ORDER] so the sequence is stable across responses. */
    val checks: List<Check>,
    /** Explanations of individual check outcomes. */
    val notes: List<String>,
    /**
     * Findings that must be seen but that do not, alone, condemn the package —
     * a missing attestation chain, a mock-provider location (ADR-0006 §5).
     */
    val advisories: List<String>,
    /** What a passing verdict does and does not establish. Always shown. */
    val limitations: List<String>,
    /** Present when the report came from a stored package. */
    val merkleRoot: String? = null,
) {

    data class Check(val name: String, val outcome: Outcome)

    companion object {
        /**
         * The order checks are displayed in, following `research/02` §8 Step 10:
         * media, metadata, Merkle, signature, attestation, timestamp, location.
         *
         * Declared here rather than taken from the response because **JSON object
         * key order is not dependable** — `org.json.JSONObject` is HashMap-backed
         * and enumerates keys in an arbitrary order, so reading the server's key
         * order produced a different sequence on every run. Presentation order is a
         * UI concern in any case; the backend's job is to say what each check
         * returned, not how to lay it out.
         *
         * A check absent from this list still appears, at the end — a newer backend
         * reporting an extra check must be visible, not silently dropped.
         */
        val DISPLAY_ORDER: List<String> = listOf(
            "schemaValid",
            "mediaHashMatch",
            "metadataHashMatch",
            "merkleRootMatch",
            "signatureValid",
            "attestationPresent",
            "attestationChainValid",
            "attestationKeyBinding",
            "attestationRootTrusted",
            "attestationNotRevoked",
            "attestationSecurityLevel",
            "timestampPlausible",
            "locationPlausible",
        )

        /** Sorts checks into [DISPLAY_ORDER], unknown names last but retained. */
        fun sortForDisplay(checks: List<Check>): List<Check> =
            checks.sortedBy { check ->
                DISPLAY_ORDER.indexOf(check.name).takeIf { it >= 0 } ?: DISPLAY_ORDER.size
            }
    }

    enum class Outcome {
        PASS,
        FAIL,

        /**
         * The check could not be run. Distinct from [FAIL] on purpose: absence of
         * evidence must never read as evidence of a problem, in either direction.
         */
        UNAVAILABLE,

        /** An outcome string this app version does not recognise. */
        UNKNOWN,
        ;

        companion object {
            fun parse(raw: String): Outcome = when (raw.lowercase()) {
                "pass" -> PASS
                "fail" -> FAIL
                "unavailable" -> UNAVAILABLE
                // Deliberately not mapped to PASS or FAIL. A newer backend
                // reporting something we do not know about must not be guessed at.
                else -> UNKNOWN
            }
        }
    }

    enum class Verdict {
        /** Every decisive check passed. */
        VERIFIED,

        /** At least one check failed. */
        FAILED,

        /** No failure, but a decisive check could not be run. */
        INCOMPLETE,

        /** The document was not a valid proof package at all. */
        INVALID_FORMAT,

        UNKNOWN,
        ;

        companion object {
            fun parse(raw: String): Verdict = when (raw.lowercase()) {
                "verified" -> VERIFIED
                "failed" -> FAILED
                "incomplete" -> INCOMPLETE
                "invalid_format" -> INVALID_FORMAT
                else -> UNKNOWN
            }
        }
    }
}
