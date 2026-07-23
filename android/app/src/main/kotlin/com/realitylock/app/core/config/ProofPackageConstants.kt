package com.realitylock.app.core.config

/**
 * Identity and format constants for the tamper-evident proof package.
 * These MUST stay in lock-step with docs/design/proof-package.schema.json
 * and docs/design/PROOF_PACKAGE_SPEC.md — the schema is the contract shared
 * with the backend verifier, this object is its Android-side mirror.
 */
object ProofPackageConstants {

    /** Semantic version of the proof-package format. */
    const val SCHEMA_VERSION: String = "1.0.0"

    /** Stable identifier embedded in every package (matches the schema $id). */
    const val SCHEMA_URN: String = "urn:realitylock:proof-package:1.0.0"

    /** Canonicalization scheme applied to metadata before hashing (RFC 8785 JCS). */
    const val JSON_CANONICALIZATION_SCHEME: String = "RFC8785"
}
