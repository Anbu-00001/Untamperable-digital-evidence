package com.realitylock.app.verify

import com.realitylock.app.verify.VerificationReport.Outcome
import com.realitylock.app.verify.VerificationReport.Verdict
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair

/**
 * The offline verifier, tested mostly on what it **refuses** to say.
 *
 * A verifier is only worth having if it says no when it should. The positive
 * case is one test; the rest of this file is tampering, absence, and the
 * specific overclaims a local verifier is tempted into — above all the one in
 * `never returns VERIFIED`, which is the property that makes an on-device check
 * safe to show a user at all.
 */
class OfflineProofVerifierTest {

    private val keyPair: KeyPair = TestProofPackages.newKeyPair()

    private fun verify(
        packageJson: String,
        media: ByteArray? = TestProofPackages.MEDIA_BYTES,
        previousPackageJson: String? = null,
        nowMillis: Long = TestProofPackages.NOW_MILLIS,
    ): VerificationReport = OfflineProofVerifier.verify(
        packageJson = packageJson,
        media = media?.let { OfflineProofVerifier.MediaSource.of(it) },
        previousPackageJson = previousPackageJson,
        nowMillis = nowMillis,
    )

    private fun VerificationReport.outcome(name: String): Outcome =
        checks.firstOrNull { it.name == name }?.outcome
            ?: throw AssertionError("no check named $name in ${checks.map { it.name }}")

    private fun VerificationReport.saysAnything(vararg fragments: String): Boolean {
        val haystack = (notes + advisories + limitations).joinToString(" ").lowercase()
        return fragments.all { haystack.contains(it.lowercase()) }
    }

    // ---- the honest ceiling -------------------------------------------------

    /**
     * The single most important assertion in this file. A local verifier that
     * reported VERIFIED while skipping root anchoring and revocation would look
     * authoritative while answering a smaller question than the reader believes —
     * strictly worse than having no local verifier at all.
     */
    @Test
    fun `a perfect package with a full attestation chain still is not VERIFIED`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(keyPair.public),
            ),
        )

        assertEquals(Verdict.INCOMPLETE, report.verdict)
        assertNotEquals(Verdict.VERIFIED, report.verdict)
        // ...and it is incomplete for the stated reason, not by accident.
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.MEDIA_HASH_MATCH))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.METADATA_HASH_MATCH))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.MERKLE_ROOT_MATCH))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.SIGNATURE_VALID))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
    }

    @Test
    fun `no package shape whatsoever produces a VERIFIED verdict`() {
        val shapes = listOf(
            "no chain" to TestProofPackages.build(keyPair = keyPair),
            "valid chain" to TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(keyPair.public),
            ),
            "strongbox chain" to TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(
                    keyPair.public,
                    securityLevel = AttestationExtensionParser.SECURITY_LEVEL_STRONGBOX,
                ),
            ),
        )

        for ((label, json) in shapes) {
            assertNotEquals(label, Verdict.VERIFIED, verify(json).verdict)
        }
    }

    @Test
    fun `root trust and revocation are UNAVAILABLE for every package shape`() {
        val shapes = listOf(
            TestProofPackages.build(keyPair = keyPair),
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(keyPair.public),
            ),
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.unlinkedChain(keyPair.public, keyPair.private),
            ),
        )

        for (json in shapes) {
            val report = verify(json)
            for (name in OfflineProofVerifier.NEVER_AVAILABLE_OFFLINE) {
                assertEquals(name, Outcome.UNAVAILABLE, report.outcome(name))
            }
        }
    }

    /**
     * The verdict rule itself is not a blanket "always incomplete" clamp — it
     * would report VERIFIED if every decisive check genuinely passed. What makes
     * that unreachable is that two of those checks cannot run offline, which is a
     * property of the checks, not a special case in the verdict.
     */
    @Test
    fun `the verdict rule is a real rule, not a hardcoded downgrade`() {
        val allPassed = VerificationReport.DISPLAY_ORDER.associateWith { Outcome.PASS }
        assertEquals(Verdict.VERIFIED, OfflineProofVerifier.verdictFor(allPassed))

        val rootTrustMissing = allPassed + (OfflineProofVerifier.ATTESTATION_ROOT_TRUSTED to Outcome.UNAVAILABLE)
        assertEquals(Verdict.INCOMPLETE, OfflineProofVerifier.verdictFor(rootTrustMissing))

        val revocationMissing = allPassed + (OfflineProofVerifier.ATTESTATION_NOT_REVOKED to Outcome.UNAVAILABLE)
        assertEquals(Verdict.INCOMPLETE, OfflineProofVerifier.verdictFor(revocationMissing))

        // A failure anywhere outranks everything.
        val oneFailure = allPassed + (OfflineProofVerifier.LOCATION_PLAUSIBLE to Outcome.FAIL)
        assertEquals(Verdict.FAILED, OfflineProofVerifier.verdictFor(oneFailure))
    }

    @Test
    fun `every report states that it was an offline check and names what was skipped`() {
        val report = verify(TestProofPackages.build(keyPair = keyPair))

        assertTrue(report.limitations.isNotEmpty())
        assertTrue(report.saysAnything("entirely on this device"))
        assertTrue(report.saysAnything("revocation"))
        assertTrue(report.saysAnything("google's published roots"))
        assertTrue(report.saysAnything("can never return `verified`"))
    }

    // ---- tampering ----------------------------------------------------------

    @Test
    fun `a flipped media bit fails mediaHashMatch and the whole package`() {
        val report = verify(
            TestProofPackages.build(keyPair = keyPair),
            media = TestProofPackages.flipOneBit(TestProofPackages.MEDIA_BYTES),
        )

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.MEDIA_HASH_MATCH))
        assertEquals(Verdict.FAILED, report.verdict)
        assertTrue(report.saysAnything("does not match the recorded leaf"))
    }

    @Test
    fun `media of a different length is reported alongside the digest mismatch`() {
        val report = verify(
            TestProofPackages.build(keyPair = keyPair),
            media = TestProofPackages.MEDIA_BYTES + "extra".toByteArray(),
        )

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.MEDIA_HASH_MATCH))
        assertTrue(report.saysAnything("package claims"))
    }

    @Test
    fun `edited metadata fails metadataHashMatch`() {
        val original = TestProofPackages.build(keyPair = keyPair)
        val report = verify(TestProofPackages.editLatitude(original, 13.0))

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.METADATA_HASH_MATCH))
        assertEquals(Verdict.FAILED, report.verdict)
        assertTrue(report.saysAnything("a field was altered"))
    }

    /**
     * Editing metadata must not be rescued by the fact that the recorded leaves
     * still compose to the recorded root — the Merkle check answers a different
     * question and is expected to keep passing here.
     */
    @Test
    fun `edited metadata leaves the Merkle composition intact, which is why both checks exist`() {
        val original = TestProofPackages.build(keyPair = keyPair)
        val report = verify(TestProofPackages.editLatitude(original, 13.0))

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.METADATA_HASH_MATCH))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.MERKLE_ROOT_MATCH))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.SIGNATURE_VALID))
    }

    @Test
    fun `a root that is not the composition of the recorded leaves fails merkleRootMatch`() {
        val original = JSONObject(TestProofPackages.build(keyPair = keyPair))
        val leaves = original.getJSONObject("merkle").getJSONObject("leaves")
        // Swap the two leaves: the tree is positional, so the same digests in the
        // wrong order must not reach the same root.
        val media = leaves.getString("media")
        leaves.put("media", leaves.getString("metadata"))
        leaves.put("metadata", media)

        val report = verify(original.toString())

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.MERKLE_ROOT_MATCH))
        assertEquals(Verdict.FAILED, report.verdict)
    }

    @Test
    fun `a signature made with a different key fails signatureValid`() {
        val impostor = TestProofPackages.newKeyPair()
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                // Signed by the impostor, but the package still names the real key.
                signingKey = impostor.private,
                declaredPublicKey = keyPair.public,
            ),
        )

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.SIGNATURE_VALID))
        assertEquals(Verdict.FAILED, report.verdict)
        assertTrue(report.saysAnything("does not verify over the recorded root"))
    }

    /**
     * The other half of the wrong-key case: swapping in the impostor's *public*
     * key makes the signature check pass, and must be caught by key binding
     * instead. Without that binding, any genuine chain could be stapled onto a
     * package signed by an entirely different key.
     */
    @Test
    fun `a chain that does not cover the signing key fails attestationKeyBinding`() {
        val stranger = TestProofPackages.newKeyPair()
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(stranger.public),
            ),
        )

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.SIGNATURE_VALID))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.ATTESTATION_CHAIN_VALID))
        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.ATTESTATION_KEY_BINDING))
        assertEquals(Verdict.FAILED, report.verdict)
        assertTrue(report.saysAnything("is NOT the key that signed this package"))
    }

    @Test
    fun `a signature value that is not decodable fails rather than throwing`() {
        val report = verify(
            TestProofPackages.replace(
                TestProofPackages.build(keyPair = keyPair),
                block = "signature",
                field = "value",
                value = "not base64 at all !!!",
            ),
        )

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.SIGNATURE_VALID))
        assertEquals(Verdict.FAILED, report.verdict)
    }

    @Test
    fun `a public key that is not a P-256 SPKI fails rather than throwing`() {
        val report = verify(
            JSONObject(TestProofPackages.build(keyPair = keyPair)).also { pkg ->
                pkg.getJSONObject("signature").getJSONObject("publicKey").put("value", "AAAA")
            }.toString(),
        )

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.SIGNATURE_VALID))
    }

    // ---- absence is not failure ---------------------------------------------

    @Test
    fun `absent media is UNAVAILABLE, never FAIL`() {
        val report = verify(TestProofPackages.build(keyPair = keyPair), media = null)

        assertEquals(Outcome.UNAVAILABLE, report.outcome(OfflineProofVerifier.MEDIA_HASH_MATCH))
        assertNotEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.MEDIA_HASH_MATCH))
        assertEquals(Verdict.INCOMPLETE, report.verdict)
        assertTrue(report.saysAnything("media bytes were not supplied"))
    }

    @Test
    fun `an absent attestation chain is UNAVAILABLE, never FAIL`() {
        val report = verify(TestProofPackages.build(keyPair = keyPair))

        for (name in listOf(
            OfflineProofVerifier.ATTESTATION_PRESENT,
            OfflineProofVerifier.ATTESTATION_CHAIN_VALID,
            OfflineProofVerifier.ATTESTATION_KEY_BINDING,
            OfflineProofVerifier.ATTESTATION_ROOT_TRUSTED,
            OfflineProofVerifier.ATTESTATION_NOT_REVOKED,
            OfflineProofVerifier.ATTESTATION_SECURITY_LEVEL,
        )) {
            assertEquals(name, Outcome.UNAVAILABLE, report.outcome(name))
        }
        // A package from a device that could not attest is still a package whose
        // integrity-since-capture is provable, so this must not condemn it.
        assertEquals(Verdict.INCOMPLETE, report.verdict)
        assertTrue(report.saysAnything("no key attestation chain"))
    }

    @Test
    fun `unreadable media is UNAVAILABLE, not a tampering finding`() {
        val report = OfflineProofVerifier.verify(
            packageJson = TestProofPackages.build(keyPair = keyPair),
            media = OfflineProofVerifier.MediaSource { error("the file is gone") },
            nowMillis = TestProofPackages.NOW_MILLIS,
        )

        assertEquals(Outcome.UNAVAILABLE, report.outcome(OfflineProofVerifier.MEDIA_HASH_MATCH))
        assertEquals(Verdict.INCOMPLETE, report.verdict)
    }

    @Test
    fun `schemaValid is UNAVAILABLE rather than claiming a schema pass it did not run`() {
        val report = verify(TestProofPackages.build(keyPair = keyPair))

        assertEquals(Outcome.UNAVAILABLE, report.outcome(OfflineProofVerifier.SCHEMA_VALID))
        assertNotEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.SCHEMA_VALID))
    }

    // ---- attestation chain --------------------------------------------------

    @Test
    fun `a well-formed chain over the signing key passes presence, linkage and binding`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(keyPair.public),
            ),
        )

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.ATTESTATION_PRESENT))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.ATTESTATION_CHAIN_VALID))
        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.ATTESTATION_KEY_BINDING))
    }

    /**
     * A self-minted CA produces exactly this chain, which is why linkage passing
     * must be accompanied by an advisory that hardware backing is NOT established.
     */
    @Test
    fun `a self-minted chain links and binds, and the report says so is not enough`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(keyPair.public),
            ),
        )

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.ATTESTATION_CHAIN_VALID))
        assertTrue(report.saysAnything("hardware backing", "not established"))
        assertTrue(report.saysAnything("revoked for compromise would not be detected"))
    }

    @Test
    fun `a single certificate is not a chain`() {
        val chain = TestProofPackages.chainFor(keyPair.public)
        val report = verify(
            TestProofPackages.build(keyPair = keyPair, attestationChain = listOf(chain.first())),
        )

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.ATTESTATION_PRESENT))
        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.ATTESTATION_CHAIN_VALID))
        assertEquals(Outcome.UNAVAILABLE, report.outcome(OfflineProofVerifier.ATTESTATION_KEY_BINDING))
        assertTrue(report.saysAnything("cannot chain to an issuer"))
    }

    @Test
    fun `certificates that do not sign one another fail attestationChainValid`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.unlinkedChain(keyPair.public, keyPair.private),
            ),
        )

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.ATTESTATION_CHAIN_VALID))
        assertEquals(Verdict.FAILED, report.verdict)
        assertTrue(report.saysAnything("do not form a valid signature chain"))
    }

    @Test
    fun `a chain that is not parseable fails rather than throwing`() {
        val report = verify(
            JSONObject(TestProofPackages.build(keyPair = keyPair)).also { pkg ->
                pkg.getJSONObject("signature").put(
                    "attestationCertificateChain",
                    org.json.JSONArray(listOf("bm90LWEtY2VydGlmaWNhdGU=", "bm9wZQ==")),
                )
            }.toString(),
        )

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.ATTESTATION_PRESENT))
        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.ATTESTATION_CHAIN_VALID))
    }

    // ---- security level, parsed offline -------------------------------------

    @Test
    fun `a TrustedEnvironment key passes attestationSecurityLevel, parsed on device`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(
                    keyPair.public,
                    securityLevel = AttestationExtensionParser.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                ),
            ),
        )

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.ATTESTATION_SECURITY_LEVEL))
        assertTrue(report.saysAnything("attested security level: TrustedEnvironment"))
    }

    @Test
    fun `a Software-protected key FAILS, because the device itself says the key is not in hardware`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(
                    keyPair.public,
                    securityLevel = AttestationExtensionParser.SECURITY_LEVEL_SOFTWARE,
                ),
            ),
        )

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.ATTESTATION_SECURITY_LEVEL))
        assertEquals(Verdict.FAILED, report.verdict)
        assertTrue(report.saysAnything("does NOT live in secure hardware"))
    }

    @Test
    fun `a leaf with no attestation extension is UNAVAILABLE, not a pass`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(keyPair.public, withExtension = false),
            ),
        )

        assertEquals(
            Outcome.UNAVAILABLE,
            report.outcome(OfflineProofVerifier.ATTESTATION_SECURITY_LEVEL),
        )
        assertTrue(report.saysAnything("carries no key attestation extension"))
    }

    // ---- timestamp ----------------------------------------------------------

    @Test
    fun `a self-consistent timestamp passes`() {
        val report = verify(TestProofPackages.build(keyPair = keyPair))

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
    }

    @Test
    fun `a wallClockMillis that was not derived from the monotonic clock FAILS`() {
        val metadata = TestProofPackages.metadata(
            // One millisecond off the exact derivation identity.
            wallClockMillis = TestProofPackages.WALL_CLOCK_MILLIS + 1,
        )
        val report = verify(TestProofPackages.build(keyPair = keyPair, metadataJson = metadata))

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
        assertEquals(Verdict.FAILED, report.verdict)
        assertTrue(report.saysAnything("not self-consistent"))
    }

    @Test
    fun `an iso8601 that denotes a different instant FAILS`() {
        val metadata = TestProofPackages.metadata(iso8601 = "2026-07-23T09:12:25.678Z")
        val report = verify(TestProofPackages.build(keyPair = keyPair, metadataJson = metadata))

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
        assertTrue(report.saysAnything("is not wallClockMillis"))
    }

    @Test
    fun `an unparseable iso8601 FAILS`() {
        val metadata = TestProofPackages.metadata(iso8601 = "last Tuesday")
        val report = verify(TestProofPackages.build(keyPair = keyPair, metadataJson = metadata))

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
        assertTrue(report.saysAnything("is not a parseable instant"))
    }

    @Test
    fun `a capture from beyond the skew allowance FAILS`() {
        val report = verify(
            TestProofPackages.build(keyPair = keyPair),
            // The verifier's clock sits an hour before the claimed capture.
            nowMillis = TestProofPackages.WALL_CLOCK_MILLIS - 3_600_000L,
        )

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
        assertTrue(report.saysAnything("in the future"))
    }

    @Test
    fun `a small clock skew is tolerated rather than treated as forgery`() {
        val report = verify(
            TestProofPackages.build(keyPair = keyPair),
            nowMillis = TestProofPackages.WALL_CLOCK_MILLIS - 30_000L,
        )

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
    }

    /**
     * `wallClockOffsetMillis` is optional in the schema and gates the only
     * sub-check with real forensic force. If its absence produced PASS, deleting
     * one field would disable the strongest part of a decisive check.
     */
    @Test
    fun `deleting the optional offset yields UNAVAILABLE, never PASS`() {
        val metadata = TestProofPackages.metadata(wallClockOffsetMillis = null)
        val report = verify(TestProofPackages.build(keyPair = keyPair, metadataJson = metadata))

        assertEquals(Outcome.UNAVAILABLE, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
        assertNotEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.TIMESTAMP_PLAUSIBLE))
        assertEquals(Verdict.INCOMPLETE, report.verdict)
    }

    // ---- location -----------------------------------------------------------

    @Test
    fun `with no capture history, locationPlausible is UNAVAILABLE and says why`() {
        val report = verify(TestProofPackages.build(keyPair = keyPair))

        assertEquals(Outcome.UNAVAILABLE, report.outcome(OfflineProofVerifier.LOCATION_PLAUSIBLE))
        assertNotEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.LOCATION_PLAUSIBLE))
        assertTrue(report.saysAnything("cross-check", "could not be run"))
        assertTrue(report.saysAnything("does not indicate a problem"))
    }

    @Test
    fun `an implied speed of thousands of km per hour FAILS when history is supplied`() {
        val previous = TestProofPackages.build(
            keyPair = keyPair,
            metadataJson = TestProofPackages.metadata(
                wallClockMillis = TestProofPackages.WALL_CLOCK_MILLIS - 60_000L,
                latitude = TestProofPackages.DELHI_LAT,
                longitude = TestProofPackages.DELHI_LON,
            ),
        )
        val report = verify(TestProofPackages.build(keyPair = keyPair), previousPackageJson = previous)

        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.LOCATION_PLAUSIBLE))
        assertEquals(Verdict.FAILED, report.verdict)
        assertTrue(report.saysAnything("may be spoofed"))
    }

    @Test
    fun `a stationary device passes the implied-speed cross-check`() {
        val previous = TestProofPackages.build(
            keyPair = keyPair,
            metadataJson = TestProofPackages.metadata(
                wallClockMillis = TestProofPackages.WALL_CLOCK_MILLIS - 60_000L,
            ),
        )
        val report = verify(TestProofPackages.build(keyPair = keyPair), previousPackageJson = previous)

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.LOCATION_PLAUSIBLE))
    }

    @Test
    fun `a capture with no location is UNAVAILABLE for the cross-check, not FAIL`() {
        val metadata = TestProofPackages.metadata(includeLocation = false)
        val report = verify(TestProofPackages.build(keyPair = keyPair, metadataJson = metadata))

        assertEquals(Outcome.UNAVAILABLE, report.outcome(OfflineProofVerifier.LOCATION_PLAUSIBLE))
        assertTrue(report.saysAnything("recorded no location"))
    }

    @Test
    fun `a mock-provider location raises an advisory without condemning the signature`() {
        val metadata = TestProofPackages.metadata(isMock = true)
        val report = verify(TestProofPackages.build(keyPair = keyPair, metadataJson = metadata))

        assertEquals(Outcome.PASS, report.outcome(OfflineProofVerifier.SIGNATURE_VALID))
        assertTrue(report.advisories.any { it.contains("MOCK provider") })
    }

    // ---- documents that are not proof packages ------------------------------

    @Test
    fun `text that is not JSON is INVALID_FORMAT, and no check claims to have run`() {
        val report = verify("this is not a proof package")

        assertEquals(Verdict.INVALID_FORMAT, report.verdict)
        assertEquals(Outcome.FAIL, report.outcome(OfflineProofVerifier.SCHEMA_VALID))
        assertTrue(
            report.checks.filter { it.name != OfflineProofVerifier.SCHEMA_VALID }
                .all { it.outcome == Outcome.UNAVAILABLE },
        )
        assertNull(report.merkleRoot)
    }

    @Test
    fun `a document missing the merkle block is INVALID_FORMAT`() {
        val stripped = JSONObject(TestProofPackages.build(keyPair = keyPair))
            .also { it.remove("merkle") }
            .toString()

        assertEquals(Verdict.INVALID_FORMAT, verify(stripped).verdict)
    }

    @Test
    fun `a truncated merkle root is rejected as a bad document, not a failed check`() {
        val report = verify(
            TestProofPackages.replace(
                TestProofPackages.build(keyPair = keyPair),
                block = "merkle",
                field = "root",
                value = "deadbeef",
            ),
        )

        assertEquals(Verdict.INVALID_FORMAT, report.verdict)
        assertTrue(report.saysAnything("64-character SHA-256 hex digest"))
    }

    @Test
    fun `a document missing signature dot publicKey is INVALID_FORMAT`() {
        val stripped = TestProofPackages.remove(
            TestProofPackages.build(keyPair = keyPair),
            block = "signature",
            field = "publicKey",
        )

        assertEquals(Verdict.INVALID_FORMAT, verify(stripped).verdict)
    }

    // ---- report shape -------------------------------------------------------

    @Test
    fun `every check named in DISPLAY_ORDER is reported, in that order`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(keyPair.public),
            ),
        )

        assertEquals(VerificationReport.DISPLAY_ORDER, report.checks.map { it.name })
    }

    @Test
    fun `the report carries the merkle root it verified against`() {
        val json = TestProofPackages.build(keyPair = keyPair)
        val expected = JSONObject(json).getJSONObject("merkle").getString("root")

        assertEquals(expected, verify(json).merkleRoot)
    }

    @Test
    fun `no check is ever reported as UNKNOWN`() {
        val report = verify(
            TestProofPackages.build(
                keyPair = keyPair,
                attestationChain = TestProofPackages.chainFor(keyPair.public),
            ),
        )

        assertTrue(report.checks.none { it.outcome == Outcome.UNKNOWN })
    }
}
