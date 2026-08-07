package com.realitylock.app.verify

import com.realitylock.app.capture.LocationPlausibility
import com.realitylock.app.core.config.CryptoConfig
import com.realitylock.app.core.time.ClockCorrelator
import com.realitylock.app.crypto.Hashing
import com.realitylock.app.crypto.MerkleTree
import com.realitylock.app.crypto.MetadataCanonicalizer
import com.realitylock.app.verify.VerificationReport.Check
import com.realitylock.app.verify.VerificationReport.Outcome
import com.realitylock.app.verify.VerificationReport.Verdict
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

/**
 * Verifies a proof package **on the device, with no network and no backend**.
 *
 * ## Why this exists
 *
 * Until now the only way to verify a package was [VerificationClient], which
 * POSTs it to a server. That makes the evidence exactly as durable as the
 * server, and the server is the least durable part of the system:
 *
 * - `research/01_domain_competitive_landscape.md` §5 records **Serelay Limited
 *   being formally dissolved on 2 March 2025** — a patent-holding, decade-old
 *   startup in this exact space whose verification was a server-side service.
 *   Anyone relying on that server today has no recourse. §11 draws the
 *   conclusion this class implements: make offline verification the *primary*
 *   path and treat the cloud as convenience, not dependency.
 * - `docs/design/adr/ADR-0004-attestation-strategy.md` §3 chose key attestation
 *   over a service-mediated scheme precisely because "a certificate chain
 *   remains verifiable against archived roots years later. For evidence, that
 *   durability is the point."
 * - This project's own deployment is the cautionary tale in miniature: the
 *   backend runs on Render's free tier, which spins down after 15 minutes idle
 *   and has an ephemeral filesystem. A live probe on 2026-08-06 returned
 *   `events: 0` — everything previously synced was simply gone.
 *
 * The phone already holds the package, the media, and every primitive the check
 * needs. Evidence that can only be verified by a server which may not exist is
 * not durable evidence.
 *
 * ## What it does NOT do, and why that is stated loudly
 *
 * A local verifier that reported `VERIFIED` while quietly skipping revocation
 * and root-anchoring would be strictly **worse than no local verifier at all**,
 * because it would look authoritative while answering a smaller question. So:
 *
 * - [VerificationReport.Verdict.VERIFIED] is **unreachable** here. Not clamped
 *   at the end — unreachable by construction: [OFFLINE_DECISIVE_CHECKS] includes
 *   `attestationRootTrusted` and `attestationNotRevoked`, and both are
 *   unconditionally [Outcome.UNAVAILABLE] offline (see [NEVER_AVAILABLE_OFFLINE]).
 *   The best an offline check can honestly reach is `INCOMPLETE`.
 * - Every check that cannot run reports `UNAVAILABLE` with a note naming the
 *   reason, never `PASS`. Absence of evidence must not read as evidence.
 * - [OFFLINE_LIMITATIONS] is added to every report, stating plainly that this
 *   was an offline check and which checks a backend would additionally run.
 *
 * ## What it *does* settle, entirely offline
 *
 * `mediaHashMatch`, `metadataHashMatch`, `merkleRootMatch`, `signatureValid`,
 * `attestationPresent`, `attestationChainValid`, `attestationKeyBinding`,
 * `attestationSecurityLevel` (parsed from the leaf's own extension by
 * [AttestationExtensionParser]) and `timestampPlausible`. Between them those
 * answer the question the verdict actually claims to answer: is this bundle
 * unaltered since capture, and was it signed by the key it names.
 *
 * ## Agreement with the backend
 *
 * Check names, outcomes and semantics mirror `backend/src/services/proofVerifier.js`
 * and `plausibility.js` deliberately, so an offline report and an online one can
 * be laid side by side. Where this class differs it is *stricter*, never looser,
 * and the difference is documented at the point it happens.
 *
 * Hashing, Merkle composition and RFC 8785 canonicalization are reused from
 * `com.realitylock.app.crypto` rather than reimplemented — the producer and the
 * verifier agreeing byte-for-byte is the whole contract, and two copies of it
 * would be two things to drift.
 */
object OfflineProofVerifier {

    /**
     * Supplies the media bytes for the `mediaHashMatch` check.
     *
     * A stream rather than a `ByteArray` so a multi-megabyte video is never
     * resident in memory in full, matching [Hashing.sha256Stream]. Each call to
     * [open] must return a fresh stream; this class closes what it opens.
     */
    fun interface MediaSource {
        fun open(): InputStream

        companion object {
            fun of(file: File): MediaSource = MediaSource { file.inputStream() }

            /** For tests and small in-memory media. */
            fun of(bytes: ByteArray): MediaSource = MediaSource { ByteArrayInputStream(bytes) }
        }
    }

    /**
     * Verifies [packageJson] — the **exact stored bytes** of a proof package.
     *
     * @param media the captured bytes, when the device still has them. Absent
     *   means `mediaHashMatch` is `unavailable`: the package alone cannot prove
     *   anything about bytes it does not contain.
     * @param previousPackageJson the most recent earlier *located* package from
     *   the same install, for the implied-speed cross-check. Absent means "no
     *   history available", which yields `unavailable`, not a pass. The default
     *   — and the normal case for a phone verifying one package in isolation —
     *   is that this is null.
     * @param nowMillis the verifier's clock, injected so the timestamp check is
     *   testable without waiting for real time to pass.
     */
    fun verify(
        packageJson: String,
        media: MediaSource? = null,
        previousPackageJson: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): VerificationReport {
        val pkg = runCatching { JSONObject(packageJson) }.getOrNull()
            ?: return invalidFormat("the document is not well-formed JSON, so it is not a proof package")
        structuralComplaint(pkg)?.let { return invalidFormat(it) }

        val checks = LinkedHashMap<String, Outcome>()
        val notes = mutableListOf<String>()
        val advisories = mutableListOf<String>()

        val merkle = pkg.getJSONObject(KEY_MERKLE)
        val leaves = merkle.getJSONObject(KEY_LEAVES)
        val recordedRoot = merkle.getString(KEY_ROOT)
        val metadata = pkg.getJSONObject(KEY_METADATA)

        // --- schema ----------------------------------------------------------
        // Deliberately NOT `pass`. The real contract is
        // docs/design/proof-package.schema.json, which is not shipped in the APK;
        // what ran here is the structural gate above. Claiming a schema pass on
        // the strength of a weaker check is the exact overclaim this class exists
        // to avoid.
        checks[SCHEMA_VALID] = Outcome.UNAVAILABLE
        notes += "the offline verifier checked that every field verification needs is " +
            "present and well-formed, but it does not carry the JSON Schema itself, so " +
            "full schema conformance was not assessed"

        // --- media leaf ------------------------------------------------------
        checks[MEDIA_HASH_MATCH] = checkMediaLeaf(pkg, leaves, media, notes)

        // --- metadata leaf ---------------------------------------------------
        // Canonicalized from the metadata object as stored, so the verifier
        // hashes what the document actually says rather than a re-derivation of
        // it (ADR-0006 §2).
        val metadataHash = runCatching {
            MetadataCanonicalizer.canonicalHashHex(metadata.toString())
        }.getOrElse { error ->
            checks[METADATA_HASH_MATCH] = Outcome.UNAVAILABLE
            notes += "metadata could not be canonicalized: ${error.message}"
            null
        }
        if (metadataHash != null) {
            val recorded = leaves.getString(KEY_METADATA)
            checks[METADATA_HASH_MATCH] =
                if (metadataHash == recorded) Outcome.PASS else Outcome.FAIL
            if (checks[METADATA_HASH_MATCH] == Outcome.FAIL) {
                notes += "metadata does not hash to the recorded leaf — a field was altered"
            }
        }

        // --- Merkle root -----------------------------------------------------
        if (metadataHash == null) {
            checks[MERKLE_ROOT_MATCH] = Outcome.UNAVAILABLE
        } else {
            val root = MerkleTree.root2Leaf(leaves.getString(KEY_MEDIA), leaves.getString(KEY_METADATA))
            checks[MERKLE_ROOT_MATCH] = if (root == recordedRoot) Outcome.PASS else Outcome.FAIL
            if (checks[MERKLE_ROOT_MATCH] == Outcome.FAIL) {
                notes += "the recorded root is not the composition of the recorded leaves"
            }
        }

        // --- signature -------------------------------------------------------
        val signature = pkg.getJSONObject(KEY_SIGNATURE)
        val signingKeyDer = runCatching {
            Base64.getDecoder().decode(signature.getJSONObject(KEY_PUBLIC_KEY).getString(KEY_VALUE))
        }.getOrNull()
        checks[SIGNATURE_VALID] = checkSignature(signature, signingKeyDer, recordedRoot, notes)

        // --- attestation -----------------------------------------------------
        checks += verifyAttestationChain(signature, signingKeyDer, notes)

        // --- timestamp plausibility ------------------------------------------
        checks[TIMESTAMP_PLAUSIBLE] = checkTimestampPlausible(metadata, nowMillis, notes)

        // --- location plausibility -------------------------------------------
        val previousMetadata = previousPackageJson
            ?.let { raw -> runCatching { JSONObject(raw).getJSONObject(KEY_METADATA) }.getOrNull() }
        checks[LOCATION_PLAUSIBLE] = checkLocationPlausible(metadata, previousMetadata, notes)

        collectAdvisories(pkg, checks, advisories)

        return VerificationReport(
            verdict = verdictFor(checks),
            checks = VerificationReport.sortForDisplay(
                checks.map { (name, outcome) -> Check(name, outcome) },
            ),
            notes = notes.toList(),
            advisories = advisories.toList(),
            limitations = OFFLINE_LIMITATIONS,
            merkleRoot = recordedRoot,
        )
    }

    // ---- individual checks --------------------------------------------------

    private fun checkMediaLeaf(
        pkg: JSONObject,
        leaves: JSONObject,
        media: MediaSource?,
        notes: MutableList<String>,
    ): Outcome {
        if (media == null) {
            // The package alone cannot prove anything about bytes it does not
            // contain. On a phone this is the common case for a package restored
            // from a share sheet without its media.
            notes += "media bytes were not supplied, so the media leaf could not be recomputed"
            return Outcome.UNAVAILABLE
        }

        val measured = runCatching {
            CountingInputStream(media.open()).use { stream ->
                val digest = Hashing.toHex(Hashing.sha256Stream(stream))
                digest to stream.bytesRead
            }
        }.getOrElse { error ->
            // Distinct from FAIL on purpose: a deleted or unreadable file is a
            // statement about this device's storage, not about the package.
            notes += "the media could not be read, so the media leaf was not recomputed: " +
                "${error.message}"
            return Outcome.UNAVAILABLE
        }
        val (actual, byteLength) = measured

        val mediaBlock = pkg.getJSONObject(KEY_MEDIA)
        val declared = mediaBlock.getString(KEY_SHA256)
        val recordedLeaf = leaves.getString(KEY_MEDIA)
        val outcome = if (actual == declared && actual == recordedLeaf) Outcome.PASS else Outcome.FAIL
        if (outcome == Outcome.FAIL) {
            notes += "media digest $actual does not match the recorded leaf"
        }
        val claimedLength = mediaBlock.getLong(KEY_BYTE_LENGTH)
        if (byteLength != claimedLength) {
            notes += "media is $byteLength bytes, package claims $claimedLength"
        }
        return outcome
    }

    /**
     * ECDSA P-256 over the **raw 32 bytes** of `merkle.root`, against the X.509
     * SPKI in `signature.publicKey.value`.
     *
     * The raw bytes, not the hex rendering — a verifier that fed the hex string
     * would fail for reasons indistinguishable from tampering (see `EventSigner`).
     */
    private fun checkSignature(
        signature: JSONObject,
        signingKeyDer: ByteArray?,
        recordedRoot: String,
        notes: MutableList<String>,
    ): Outcome = runCatching {
        requireNotNull(signingKeyDer) { "signature.publicKey.value is not valid base64" }
        val publicKey = KeyFactory.getInstance(CryptoConfig.KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(signingKeyDer))
        Signature.getInstance(CryptoConfig.SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(Hashing.fromHex(recordedRoot))
            verify(Base64.getDecoder().decode(signature.getString(KEY_VALUE)))
        }
    }.fold(
        onSuccess = { ok ->
            if (!ok) notes += "signature does not verify over the recorded root"
            if (ok) Outcome.PASS else Outcome.FAIL
        },
        // FAIL, not UNAVAILABLE, and this matches the backend: a signature block
        // that will not even decode is a defect in the package, not a limit of
        // the verifier.
        onFailure = { error ->
            notes += "signature could not be checked: ${error.message}"
            Outcome.FAIL
        },
    )

    /**
     * The attestation checks that can be settled from the package's own bytes,
     * plus honest `unavailable`s for the two that cannot.
     *
     * Internal linkage plus key binding proves the chain is self-consistent and
     * covers the signing key. It does **not** prove the chain came from Google:
     * an attacker can mint their own CA, issue a leaf over their own software
     * key, and produce a chain that links perfectly and binds correctly. Only
     * `attestationRootTrusted` closes that, and it cannot run here.
     */
    private fun verifyAttestationChain(
        signature: JSONObject,
        signingKeyDer: ByteArray?,
        notes: MutableList<String>,
    ): Map<String, Outcome> {
        val chainBase64 = signature.optJSONArray(KEY_ATTESTATION_CHAIN)
            ?.let { array -> (0 until array.length()).map(array::getString) }
            .orEmpty()

        if (chainBase64.isEmpty()) {
            notes += "no attestation chain — the key is not proven to be hardware-backed"
            // `unavailable`, not `fail`: the chain is absent, which is the absence
            // of evidence, not evidence of a defect. A package from a device that
            // could not attest is still a package whose integrity-since-capture is
            // provable, so this raises an advisory instead of condemning it
            // (ADR-0006 §5).
            return ATTESTATION_CHECKS.associateWith { Outcome.UNAVAILABLE }
        }

        val result = linkedMapOf(ATTESTATION_PRESENT to Outcome.PASS)
        result += NEVER_AVAILABLE_OFFLINE.associateWith { Outcome.UNAVAILABLE }

        val chain = runCatching { parseChain(chainBase64) }.getOrElse { error ->
            notes += "attestation chain could not be parsed: ${error.message}"
            return result + mapOf(
                ATTESTATION_CHAIN_VALID to Outcome.FAIL,
                ATTESTATION_KEY_BINDING to Outcome.UNAVAILABLE,
                ATTESTATION_SECURITY_LEVEL to Outcome.UNAVAILABLE,
            )
        }

        // A lone certificate is not a chain. Guarded explicitly because the
        // linkage loop below runs `size - 1` times, so a single self-signed
        // certificate would skip it entirely and report `pass` having verified
        // precisely nothing.
        if (chain.size < MIN_CHAIN_LENGTH) {
            notes += "attestation chain has only ${chain.size} certificate: a single " +
                "certificate cannot chain to an issuer, so it establishes no hardware backing"
            return result + mapOf(
                ATTESTATION_CHAIN_VALID to Outcome.FAIL,
                ATTESTATION_KEY_BINDING to Outcome.UNAVAILABLE,
                ATTESTATION_SECURITY_LEVEL to Outcome.UNAVAILABLE,
            )
        }

        val linked = (0 until chain.size - 1).all { i ->
            runCatching { chain[i].verify(chain[i + 1].publicKey) }.isSuccess
        }
        result[ATTESTATION_CHAIN_VALID] = if (linked) Outcome.PASS else Outcome.FAIL
        if (!linked) notes += "attestation certificates do not form a valid signature chain"

        // Root anchoring is the check that would turn "self-consistent" into
        // "issued by Google". It needs Google's pinned attestation roots, which
        // are held in backend/data/google-attestation-roots.pem and are NOT
        // shipped in the app. Reported as unavailable rather than quietly
        // omitted, because a reader who is not told will assume it passed.
        notes += "root anchoring was NOT performed: Google's pinned attestation roots are " +
            "not carried in the app, so a chain minted by anyone with their own CA is " +
            "indistinguishable from a genuine one in this report"
        notes += "certificate revocation was NOT checked: Google's status list is a live " +
            "network resource and no offline snapshot was consulted"

        result[ATTESTATION_SECURITY_LEVEL] = evaluateSecurityLevel(chain.first(), notes)

        result[ATTESTATION_KEY_BINDING] = if (signingKeyDer == null) {
            Outcome.UNAVAILABLE
        } else {
            // getEncoded() on a java.security.PublicKey is X.509 SubjectPublicKeyInfo,
            // the same encoding the package carries — so this compares like with like.
            val bound = chain.first().publicKey.encoded.contentEquals(signingKeyDer)
            if (!bound) notes += "the attested key is NOT the key that signed this package"
            if (bound) Outcome.PASS else Outcome.FAIL
        }

        return result
    }

    private fun parseChain(chainBase64: List<String>): List<X509Certificate> {
        val factory = CertificateFactory.getInstance(X509_CERTIFICATE_TYPE)
        return chainBase64.map { encoded ->
            factory.generateCertificate(
                ByteArrayInputStream(Base64.getDecoder().decode(encoded)),
            ) as X509Certificate
        }
    }

    /**
     * What the leaf's own attestation extension says about where the key lives.
     * This one genuinely *can* be answered offline — the bytes are in the
     * package — so it is answered rather than declared unavailable.
     *
     * `fail` when the extension reports **Software**: the device is stating
     * plainly that this key does not live in secure hardware, while the package
     * carries a chain a reader will take as evidence it does.
     *
     * `unavailable` when the extension is absent or will not parse. A parse
     * failure must never be able to invent a security level.
     */
    private fun evaluateSecurityLevel(leaf: X509Certificate, notes: MutableList<String>): Outcome {
        val description = runCatching { AttestationExtensionParser.parse(leaf) }
            .getOrElse { error ->
                notes += "attestation extension could not be parsed: ${error.message}"
                return Outcome.UNAVAILABLE
            }
        if (description == null) {
            notes += "the leaf certificate carries no key attestation extension"
            return Outcome.UNAVAILABLE
        }

        notes += "attested security level: ${description.securityLevel} " +
            "(attestation version ${description.attestationVersion})"
        notes += if (description.verifiedBootState != null) {
            "verified boot state: ${description.verifiedBootState}, bootloader " +
                if (description.deviceLocked == true) "locked" else "UNLOCKED"
        } else {
            "the attestation extension carries no rootOfTrust, so boot state is unknown"
        }

        if (description.securityLevelValue == AttestationExtensionParser.SECURITY_LEVEL_SOFTWARE) {
            notes += "the device reports this key as Software-protected: it does NOT live in " +
                "secure hardware"
            return Outcome.FAIL
        }
        // Anything this build does not recognise is not assumed good.
        return if (
            description.securityLevelValue ==
            AttestationExtensionParser.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
            description.securityLevelValue == AttestationExtensionParser.SECURITY_LEVEL_STRONGBOX
        ) {
            Outcome.PASS
        } else {
            Outcome.UNAVAILABLE
        }
    }

    /**
     * Timestamp plausibility — DECISIVE, so it only fails on things that are
     * genuinely impossible rather than merely odd. Mirrors
     * `backend/src/services/plausibility.js#checkTimestampPlausible`.
     *
     *  1. Self-consistency: the producer computes
     *     `wallClockMillis = floor(elapsedRealtimeNanos / 1e6) + wallClockOffsetMillis`,
     *     so this is an EXACT integer identity. Checking it exactly is what makes
     *     it worth checking — it confirms the recorded instant was derived from
     *     the monotonic clock rather than typed in.
     *  2. The ISO-8601 rendering must denote the same instant as wallClockMillis.
     *  3. A capture cannot come from the future, beyond a skew allowance for an
     *     unsynchronised device clock.
     *
     * `wallClockOffsetMillis` is not schema-required, and it gates the only
     * sub-check with real forensic force — so its absence yields UNAVAILABLE
     * rather than PASS. Otherwise deleting one optional field would disable the
     * strongest part of a decisive check.
     */
    private fun checkTimestampPlausible(
        metadata: JSONObject,
        nowMillis: Long,
        notes: MutableList<String>,
    ): Outcome {
        val ts = metadata.getJSONObject(KEY_TIMESTAMP)
        val wallClockMillis = ts.getLong(KEY_WALL_CLOCK_MILLIS)

        val offset = if (ts.isNull(KEY_WALL_CLOCK_OFFSET_MILLIS)) null else ts.optLong(KEY_WALL_CLOCK_OFFSET_MILLIS)
        if (offset != null) {
            val derived =
                Math.floorDiv(ts.getLong(KEY_ELAPSED_REALTIME_NANOS), ClockCorrelator.NANOS_PER_MILLI) + offset
            if (derived != wallClockMillis) {
                notes += "timestamp is not self-consistent: elapsedRealtimeNanos/1e6 + offset = " +
                    "$derived, but wallClockMillis is $wallClockMillis"
                return Outcome.FAIL
            }
        }

        val iso = ts.getString(KEY_ISO_8601)
        val isoMillis = parseInstantMillis(iso)
        if (isoMillis == null) {
            notes += "iso8601 \"$iso\" is not a parseable instant"
            return Outcome.FAIL
        }
        if (isoMillis != wallClockMillis) {
            notes += "iso8601 $iso denotes $isoMillis, which is not wallClockMillis $wallClockMillis"
            return Outcome.FAIL
        }

        val futureBy = wallClockMillis - nowMillis
        if (futureBy > MAX_FUTURE_SKEW_MILLIS) {
            notes += "capture claims to be $futureBy ms in the future, beyond the " +
                "$MAX_FUTURE_SKEW_MILLIS ms skew allowance"
            return Outcome.FAIL
        }

        if (offset == null) {
            notes += "wallClockOffsetMillis is absent, so the recorded instant could not be " +
                "confirmed as derived from the monotonic clock; the remaining timestamp checks passed"
            return Outcome.UNAVAILABLE
        }
        return Outcome.PASS
    }

    /** `Date.parse`-equivalent, tolerating both `…Z` and explicit-offset forms. */
    private fun parseInstantMillis(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .getOrNull()

    /**
     * Location plausibility — the implied-speed cross-check between this capture
     * and the previous located one.
     *
     * **Normally `unavailable` offline**, and that is the honest answer: this
     * check is a statement about two events, and a phone verifying one package
     * in isolation has only one. Callers that do hold the earlier package can
     * pass it, in which case the check runs for real — reusing
     * [LocationPlausibility], the same arithmetic the capture pipeline uses, so
     * the device's advisory answer and this one cannot differ by implementation.
     *
     * Non-decisive by design (ADR-0006 §5): a first-ever capture legitimately has
     * nothing to compare against and must not be punished for it.
     */
    private fun checkLocationPlausible(
        metadata: JSONObject,
        previousMetadata: JSONObject?,
        notes: MutableList<String>,
    ): Outcome {
        val here = metadata.optJSONObject(KEY_LOCATION)
        if (here == null) {
            notes += "this event recorded no location, so there is nothing to cross-check"
            return Outcome.UNAVAILABLE
        }
        val there = previousMetadata?.optJSONObject(KEY_LOCATION)
        if (there == null) {
            notes += "no earlier located capture was supplied to this offline check, so the " +
                "implied-speed cross-check could not be run — a backend verifier compares " +
                "against the capture history it stores for this install"
            return Outcome.UNAVAILABLE
        }

        val plausible = LocationPlausibility.isPlausible(
            prevLat = there.getDouble(KEY_LATITUDE),
            prevLon = there.getDouble(KEY_LONGITUDE),
            prevWallClockMillis = previousMetadata.getJSONObject(KEY_TIMESTAMP)
                .getLong(KEY_WALL_CLOCK_MILLIS),
            lat = here.getDouble(KEY_LATITUDE),
            lon = here.getDouble(KEY_LONGITUDE),
            wallClockMillis = metadata.getJSONObject(KEY_TIMESTAMP).getLong(KEY_WALL_CLOCK_MILLIS),
        )
        return when (plausible) {
            null -> {
                notes += "the two captures are too close in time for an implied speed to mean anything"
                Outcome.UNAVAILABLE
            }
            true -> Outcome.PASS
            false -> {
                notes += "the implied speed between this capture and the previous one exceeds " +
                    "the plausibility bound — the location may be spoofed"
                Outcome.FAIL
            }
        }
    }

    // ---- advisories ---------------------------------------------------------

    /**
     * Findings a reader must see but that must not, on their own, condemn a
     * package. Kept separate from `notes` (which explain check outcomes) because
     * these are standing caveats about what the package does and does not
     * establish. Mirrors `proofVerifier.js#collectAdvisories`, with the
     * root-trust and revocation wording adjusted to say *why* they are
     * unavailable here — the backend's reasons are transient, this one is
     * structural.
     */
    private fun collectAdvisories(
        pkg: JSONObject,
        checks: Map<String, Outcome>,
        advisories: MutableList<String>,
    ) {
        if (checks[ATTESTATION_PRESENT] == Outcome.UNAVAILABLE) {
            advisories += "No key attestation chain: the signature proves the bundle is " +
                "unaltered since capture, but not that the signing key lives in secure hardware."
        } else {
            advisories += "This offline check did NOT anchor the attestation chain to any " +
                "published Google root — the app does not carry those roots. A chain minted by " +
                "anyone with their own CA looks exactly like this one here, so hardware backing " +
                "is not established by this report."
            // Said out loud because this check fails OPEN: with no list, a revoked
            // certificate looks exactly like a clean one. A reader who is not told
            // will reasonably assume it passed.
            advisories += "Certificate revocation was NOT checked against Google's published " +
                "status list, so a key revoked for compromise would not be detected in this report."
        }

        if (checks[ATTESTATION_SECURITY_LEVEL] == Outcome.FAIL) {
            advisories += "The device reports this key as Software-protected — by its own " +
                "attestation the key does NOT live in secure hardware, whatever the presence " +
                "of a chain suggests."
        }

        val location = pkg.getJSONObject(KEY_METADATA).optJSONObject(KEY_LOCATION)
        if (location != null && location.optBoolean(KEY_IS_MOCK, false)) {
            // Signed by the device, so this is the device itself reporting that
            // the position came from a mock provider. Not a tampering finding — a
            // provenance one, and a serious one.
            advisories += "The device recorded this location as coming from a MOCK provider. " +
                "The signature is genuine; the position it covers is not trustworthy."
        }

        if (checks[LOCATION_PLAUSIBLE] == Outcome.UNAVAILABLE) {
            advisories += "Location could not be cross-checked against an earlier capture — " +
                "this does not indicate a problem, only that no comparison was possible offline."
        }

        val claimed = pkg.optJSONObject(KEY_INTEGRITY)
            ?.optJSONObject(KEY_LOCATION)
            ?.takeIf { !it.isNull(KEY_SPEED_PLAUSIBLE) }
            ?.optBoolean(KEY_SPEED_PLAUSIBLE)
        if (claimed == true && checks[LOCATION_PLAUSIBLE] == Outcome.FAIL) {
            advisories += "The device claimed this location was physically plausible, but the " +
                "verifier recomputed it as implausible. The verifier's result stands — the " +
                "device-side value is unsigned and advisory."
        }
    }

    // ---- verdict ------------------------------------------------------------

    /**
     * Two rules, in order — the backend's (ADR-0006 §5), with a **stricter**
     * decisive set:
     *
     *  1. **Any** check that returned `fail` makes the verdict `failed`. There is
     *     no partial credit for tamper-evidence.
     *  2. Otherwise every check in [OFFLINE_DECISIVE_CHECKS] must have passed.
     *     One that could not be run holds the verdict at `incomplete` rather than
     *     letting absence of evidence read as evidence.
     *
     * Because [OFFLINE_DECISIVE_CHECKS] contains the two checks that are
     * unconditionally unavailable offline, rule 2 can never be satisfied here and
     * `verified` is unreachable. That is the intended and tested behaviour, and
     * it is expressed as a property of the rule rather than as a clamp on the
     * result, so it cannot be lost by someone editing one branch.
     */
    internal fun verdictFor(checks: Map<String, Outcome>): Verdict = when {
        checks.values.any { it == Outcome.FAIL } -> Verdict.FAILED
        OFFLINE_DECISIVE_CHECKS.all { checks[it] == Outcome.PASS } -> Verdict.VERIFIED
        else -> Verdict.INCOMPLETE
    }

    // ---- structural gate ----------------------------------------------------

    /**
     * Why the document is not a proof package, or null if it is one.
     *
     * Mirrors the `required` lists of `docs/design/proof-package.schema.json` for
     * the fields verification actually consumes, and additionally pins the three
     * digest fields to the schema's `sha256hex` pattern — with those checked here
     * the recomputation below cannot throw on malformed hex, so a truncated
     * digest is reported as a bad *document* rather than as a failed *check*.
     */
    private fun structuralComplaint(pkg: JSONObject): String? {
        REQUIRED_TOP_LEVEL.firstOrNull { !pkg.has(it) }
            ?.let { return "the document is missing the required top-level field \"$it\"" }

        val media = pkg.optJSONObject(KEY_MEDIA) ?: return "\"media\" is not an object"
        REQUIRED_MEDIA.firstOrNull { media.isNull(it) }
            ?.let { return "the document is missing \"media.$it\"" }

        val metadata = pkg.optJSONObject(KEY_METADATA) ?: return "\"metadata\" is not an object"
        val timestamp = metadata.optJSONObject(KEY_TIMESTAMP)
            ?: return "the document is missing \"metadata.timestamp\""
        REQUIRED_TIMESTAMP.firstOrNull { timestamp.isNull(it) }
            ?.let { return "the document is missing \"metadata.timestamp.$it\"" }

        val merkle = pkg.optJSONObject(KEY_MERKLE) ?: return "\"merkle\" is not an object"
        val leaves = merkle.optJSONObject(KEY_LEAVES)
            ?: return "the document is missing \"merkle.leaves\""
        for ((label, value) in listOf(
            "merkle.root" to merkle.optString(KEY_ROOT),
            "merkle.leaves.media" to leaves.optString(KEY_MEDIA),
            "merkle.leaves.metadata" to leaves.optString(KEY_METADATA),
        )) {
            if (!SHA256_HEX.matches(value)) {
                return "\"$label\" is not a lowercase 64-character SHA-256 hex digest"
            }
        }

        val signature = pkg.optJSONObject(KEY_SIGNATURE) ?: return "\"signature\" is not an object"
        if (signature.isNull(KEY_VALUE)) return "the document is missing \"signature.value\""
        val publicKey = signature.optJSONObject(KEY_PUBLIC_KEY)
            ?: return "the document is missing \"signature.publicKey\""
        if (publicKey.isNull(KEY_VALUE)) return "the document is missing \"signature.publicKey.value\""

        return null
    }

    /**
     * A report for something that is not a proof package at all. Every check is
     * `unavailable` rather than `fail`: nothing was verified, and saying a media
     * hash "failed" on a document with no media block would be a false finding.
     */
    private fun invalidFormat(reason: String): VerificationReport = VerificationReport(
        verdict = Verdict.INVALID_FORMAT,
        checks = VerificationReport.DISPLAY_ORDER.map { name ->
            Check(name, if (name == SCHEMA_VALID) Outcome.FAIL else Outcome.UNAVAILABLE)
        },
        notes = listOf(reason),
        advisories = emptyList(),
        limitations = OFFLINE_LIMITATIONS,
        merkleRoot = null,
    )

    // ---- counting stream ----------------------------------------------------

    /**
     * Counts bytes while they stream past, so the media length can be compared
     * against `media.byteLength` without a second pass or a full read into
     * memory.
     */
    private class CountingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead += 1 }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bytesRead += it }
    }

    // ---- names and constants ------------------------------------------------

    // Check names, spelled exactly as backend/src/services/proofVerifier.js emits
    // them and as VerificationReport.DISPLAY_ORDER lists them. An offline report
    // and an online one must be comparable line for line.
    const val SCHEMA_VALID: String = "schemaValid"
    const val MEDIA_HASH_MATCH: String = "mediaHashMatch"
    const val METADATA_HASH_MATCH: String = "metadataHashMatch"
    const val MERKLE_ROOT_MATCH: String = "merkleRootMatch"
    const val SIGNATURE_VALID: String = "signatureValid"
    const val ATTESTATION_PRESENT: String = "attestationPresent"
    const val ATTESTATION_CHAIN_VALID: String = "attestationChainValid"
    const val ATTESTATION_KEY_BINDING: String = "attestationKeyBinding"
    const val ATTESTATION_ROOT_TRUSTED: String = "attestationRootTrusted"
    const val ATTESTATION_NOT_REVOKED: String = "attestationNotRevoked"
    const val ATTESTATION_SECURITY_LEVEL: String = "attestationSecurityLevel"
    const val TIMESTAMP_PLAUSIBLE: String = "timestampPlausible"
    const val LOCATION_PLAUSIBLE: String = "locationPlausible"

    /**
     * The checks that **cannot** be answered without a network or without data
     * the app does not ship, and are therefore always [Outcome.UNAVAILABLE] here.
     *
     * - `attestationRootTrusted` needs Google's pinned attestation roots. They
     *   live in `backend/data/google-attestation-roots.pem` and are deliberately
     *   not in the APK: shipping trust anchors that the app itself could not
     *   update would be its own hazard, and fetching them at verify time would
     *   make them only as trustworthy as the fetch.
     * - `attestationNotRevoked` needs Google's live status list. Revocation data
     *   is only useful when fresh; a snapshot old enough to be wrong fails open,
     *   reporting a since-revoked key as clean.
     */
    val NEVER_AVAILABLE_OFFLINE: List<String> =
        listOf(ATTESTATION_ROOT_TRUSTED, ATTESTATION_NOT_REVOKED)

    private val ATTESTATION_CHECKS = listOf(
        ATTESTATION_PRESENT,
        ATTESTATION_CHAIN_VALID,
        ATTESTATION_KEY_BINDING,
        ATTESTATION_ROOT_TRUSTED,
        ATTESTATION_NOT_REVOKED,
        ATTESTATION_SECURITY_LEVEL,
    )

    /**
     * What must all pass before this verifier may say `verified`.
     *
     * The first five are the backend's `DECISIVE_CHECKS`. The last two are added
     * *because* they cannot run offline: a verdict of `verified` from a check
     * that skipped root-anchoring and revocation would look authoritative while
     * answering a smaller question than the reader believes. Including them makes
     * `verified` unreachable offline, which is the correct and intended ceiling.
     */
    val OFFLINE_DECISIVE_CHECKS: List<String> = listOf(
        MEDIA_HASH_MATCH,
        METADATA_HASH_MATCH,
        MERKLE_ROOT_MATCH,
        SIGNATURE_VALID,
        TIMESTAMP_PLAUSIBLE,
    ) + NEVER_AVAILABLE_OFFLINE

    /**
     * Shipped with every offline verdict. The first entry is the one that
     * matters: a reader must not be able to mistake an offline report for the
     * full check.
     */
    val OFFLINE_LIMITATIONS: List<String> = listOf(
        "This verification ran ENTIRELY ON THIS DEVICE, with no network. It recomputed the " +
            "media and metadata hashes, the Merkle root, the signature over that root, the " +
            "attestation chain's internal linkage and key binding, the attested security " +
            "level, and timestamp self-consistency.",
        "It did NOT anchor the attestation chain to Google's published roots and did NOT " +
            "check certificate revocation — an independent verifier holding those would run " +
            "both, and would also cross-check location against this install's capture history " +
            "and validate the document against the full proof-package JSON Schema.",
        "For that reason an offline check can never return `verified`; `incomplete` with every " +
            "cryptographic check passing is the strongest result it can honestly give.",
        "Proves the media and metadata are unaltered since capture and were signed by one " +
            "specific key held in the capturing device keystore.",
        "Does NOT prove the depicted event was real, unstaged, or correctly described.",
        "Not a standalone legal certificate; BSA 2023 s.63 requires human certification.",
    )

    /**
     * How far ahead of the verifier's own clock a capture may claim to be.
     * Mirrors the backend's `plausibility.maxFutureSkewMillis` default: an
     * NTP-synced device lands within seconds, and this allows for an
     * unsynchronised clock without admitting a forged future date.
     */
    private const val MAX_FUTURE_SKEW_MILLIS = 5L * 60L * 1000L

    /** A chain needs at least a leaf and an issuer to establish anything. */
    private const val MIN_CHAIN_LENGTH = 2

    private const val X509_CERTIFICATE_TYPE = "X.509"

    /** The schema's `sha256hex` pattern. */
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    private val REQUIRED_TOP_LEVEL =
        listOf(KEY_MEDIA, KEY_METADATA, KEY_MERKLE, KEY_SIGNATURE)
    private val REQUIRED_MEDIA = listOf(KEY_BYTE_LENGTH, KEY_SHA256)
    private val REQUIRED_TIMESTAMP =
        listOf(KEY_WALL_CLOCK_MILLIS, KEY_ISO_8601, KEY_ELAPSED_REALTIME_NANOS)
}

// Field names — mirror docs/design/proof-package.schema.json exactly, for the
// same reason EventSerializer spells them out rather than reflecting: this JSON
// is a proof artifact, and its field names must not move because a Kotlin
// property was renamed.
private const val KEY_MEDIA = "media"
private const val KEY_METADATA = "metadata"
private const val KEY_MERKLE = "merkle"
private const val KEY_SIGNATURE = "signature"
private const val KEY_INTEGRITY = "integrity"
private const val KEY_LEAVES = "leaves"
private const val KEY_ROOT = "root"
private const val KEY_VALUE = "value"
private const val KEY_PUBLIC_KEY = "publicKey"
private const val KEY_ATTESTATION_CHAIN = "attestationCertificateChain"
private const val KEY_SHA256 = "sha256"
private const val KEY_BYTE_LENGTH = "byteLength"
private const val KEY_LOCATION = "location"
private const val KEY_LATITUDE = "latitude"
private const val KEY_LONGITUDE = "longitude"
private const val KEY_IS_MOCK = "isMock"
private const val KEY_SPEED_PLAUSIBLE = "speedPlausible"
private const val KEY_TIMESTAMP = "timestamp"
private const val KEY_WALL_CLOCK_MILLIS = "wallClockMillis"
private const val KEY_ISO_8601 = "iso8601"
private const val KEY_ELAPSED_REALTIME_NANOS = "elapsedRealtimeNanos"
private const val KEY_WALL_CLOCK_OFFSET_MILLIS = "wallClockOffsetMillis"
