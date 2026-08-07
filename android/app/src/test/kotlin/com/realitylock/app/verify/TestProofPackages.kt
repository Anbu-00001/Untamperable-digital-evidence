package com.realitylock.app.verify

import com.realitylock.app.crypto.Hashing
import com.realitylock.app.crypto.MerkleTree
import com.realitylock.app.crypto.MetadataCanonicalizer
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Builds genuinely signed proof packages for the offline-verifier tests.
 *
 * Everything here is real: a real P-256 key pair, a real SHA-256 over real media
 * bytes, a real RFC 8785 canonicalization, and a real ECDSA signature over the
 * raw root bytes. A fixture that stubbed any of those would let the verifier pass
 * its tests while being unable to verify a package the app actually produced.
 *
 * The producer path is mirrored from `EventSigner` and `CaptureCoordinator`, but
 * spelled out here rather than called: `EventSigner` needs the Android Keystore
 * and `android.util.Base64`, neither of which exists in a JVM unit test.
 */
object TestProofPackages {

    const val WALL_CLOCK_MILLIS: Long = 1_784_812_345_678L
    const val ELAPSED_REALTIME_NANOS: Long = 894_512_000_000_000L

    /**
     * `wallClockMillis - floor(elapsedRealtimeNanos / 1e6)`, so the derivation
     * identity `checkTimestampPlausible` asserts holds exactly.
     */
    const val WALL_CLOCK_OFFSET_MILLIS: Long = 1_783_917_833_678L

    /**
     * The UTC rendering of [WALL_CLOCK_MILLIS], as `ClockCorrelator.toIso8601Utc`
     * emits it.
     *
     * Computed, not copied: `docs/design/examples/proof-package.example.json` and
     * `CapturedEventFixtures` both pair this same epoch value with
     * `09:12:25.678Z`, which is four hours out. Those fixtures never had the
     * agreement checked because nothing recomputed it — the backend's
     * `checkTimestampPlausible` would report them `fail`. Using the wrong value
     * here would make the "consistent timestamp passes" test assert the opposite
     * of what it claims.
     */
    const val ISO_8601: String = "2026-07-23T13:12:25.678Z"

    /** A moment safely after the capture, for the "not from the future" sub-check. */
    const val NOW_MILLIS: Long = WALL_CLOCK_MILLIS + 60_000L

    val MEDIA_BYTES: ByteArray = "reality-lock-offline-verifier-test-media".toByteArray()

    const val BANGALORE_LAT: Double = 12.9716
    const val BANGALORE_LON: Double = 77.5946
    const val DELHI_LAT: Double = 28.6139
    const val DELHI_LON: Double = 77.2090

    /** A signing identity plus the package it signed. */
    data class Fixture(val keyPair: KeyPair, val json: String)

    fun newKeyPair(): KeyPair = TestCertificates.newKeyPair()

    /**
     * A complete, schema-shaped proof package.
     *
     * @param signingKey the private key that actually signs. Pass a key other
     *   than [keyPair]'s to produce the wrong-key case.
     * @param declaredPublicKey what the package *claims* signed it.
     */
    @Suppress("LongParameterList")
    fun build(
        keyPair: KeyPair = newKeyPair(),
        mediaBytes: ByteArray = MEDIA_BYTES,
        metadataJson: String = metadata(),
        attestationChain: List<X509Certificate>? = null,
        signingKey: PrivateKey = keyPair.private,
        declaredPublicKey: PublicKey = keyPair.public,
    ): String {
        val mediaHash = Hashing.toHex(Hashing.sha256(mediaBytes))
        val metadataHash = MetadataCanonicalizer.canonicalHashHex(metadataJson)
        val root = MerkleTree.root2Leaf(mediaHash, metadataHash)

        // The RAW 32 bytes of the root, not its hex rendering — see EventSigner.
        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKey)
            update(Hashing.fromHex(root))
            sign()
        }

        return JSONObject().apply {
            put("schemaUrn", "urn:realitylock:proof-package:1.0.0")
            put("schemaVersion", "1.0.0")
            put("eventId", "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
            put(
                "media",
                JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("byteLength", mediaBytes.size.toLong())
                    put("sha256", mediaHash)
                    put("storageRef", JSONObject.NULL)
                },
            )
            put("metadata", JSONObject(metadataJson))
            put("canonicalization", "RFC8785")
            put(
                "merkle",
                JSONObject().apply {
                    put("algorithm", "SHA-256")
                    put("scheme", "2-leaf")
                    put(
                        "leaves",
                        JSONObject().apply {
                            put("media", mediaHash)
                            put("metadata", metadataHash)
                        },
                    )
                    put("root", root)
                },
            )
            put(
                "signature",
                JSONObject().apply {
                    put("algorithm", "SHA256withECDSA")
                    put("value", base64(signatureBytes))
                    put(
                        "publicKey",
                        JSONObject().apply {
                            put("format", "X.509")
                            put("curve", "secp256r1")
                            put("value", base64(declaredPublicKey.encoded))
                        },
                    )
                    put(
                        "attestationCertificateChain",
                        attestationChain
                            ?.let { chain ->
                                JSONArray().also { array ->
                                    chain.forEach { array.put(base64(it.encoded)) }
                                }
                            }
                            ?: JSONObject.NULL,
                    )
                },
            )
        }.toString()
    }

    /** [build], with the key pair returned alongside so tests can re-use it. */
    fun fixture(
        keyPair: KeyPair = newKeyPair(),
        attestationChain: List<X509Certificate>? = null,
        metadataJson: String = metadata(),
    ): Fixture = Fixture(
        keyPair = keyPair,
        json = build(
            keyPair = keyPair,
            attestationChain = attestationChain,
            metadataJson = metadataJson,
        ),
    )

    /**
     * The `metadata` object, as JSON text.
     *
     * Written out rather than assembled through `EventSerializer` so the fixture
     * does not inherit whatever that class happens to do — the metadata leaf is
     * the contract, and the test should pin it independently.
     */
    @Suppress("LongParameterList")
    fun metadata(
        wallClockMillis: Long = WALL_CLOCK_MILLIS,
        iso8601: String = ISO_8601,
        elapsedRealtimeNanos: Long = ELAPSED_REALTIME_NANOS,
        wallClockOffsetMillis: Long? = WALL_CLOCK_OFFSET_MILLIS,
        latitude: Double = BANGALORE_LAT,
        longitude: Double = BANGALORE_LON,
        isMock: Boolean = false,
        includeLocation: Boolean = true,
    ): String {
        val location = if (includeLocation) {
            """{"latitude":$latitude,"longitude":$longitude,"accuracyMeters":4.2,""" +
                """"altitudeMeters":920.0,"provider":"fused","fixAgeMillis":850,""" +
                """"isMock":$isMock}"""
        } else {
            "null"
        }
        val offset = wallClockOffsetMillis?.toString() ?: "null"
        return """
            {
              "location": $location,
              "timestamp": {
                "wallClockMillis": $wallClockMillis,
                "iso8601": "$iso8601",
                "elapsedRealtimeNanos": $elapsedRealtimeNanos,
                "wallClockOffsetMillis": $offset,
                "gpsTimeMillis": null
              },
              "motion": {
                "accelerometer": [0.12, 9.79, 0.34],
                "gyroscope": [0.001, -0.002, 0.0],
                "sampleElapsedRealtimeNanos": 894511998000000
              },
              "device": {
                "installId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                "model": "CPH2591",
                "manufacturer": "OnePlus",
                "sdkInt": 35,
                "appVersionName": "0.1.0",
                "appVersionCode": 1
              }
            }
        """.trimIndent()
    }

    // ---- attestation chains -------------------------------------------------

    /**
     * A two-certificate chain whose leaf attests [attestedKey] and whose root
     * signed the leaf: the shape a real attestation has, minus any anchoring to
     * Google — which is exactly the point the verifier is required to make.
     */
    fun chainFor(
        attestedKey: PublicKey,
        securityLevel: Int = AttestationExtensionParser.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
        withExtension: Boolean = true,
    ): List<X509Certificate> {
        val ca = TestCertificates.newKeyPair()
        val root = TestCertificates.certificate(
            subjectCn = "Test Attestation Root",
            subjectPublicKey = ca.public,
            issuerCn = "Test Attestation Root",
            issuerPrivateKey = ca.private,
            serial = 1L,
        )
        val leaf = TestCertificates.certificate(
            subjectCn = "Android Keystore Key",
            subjectPublicKey = attestedKey,
            issuerCn = "Test Attestation Root",
            issuerPrivateKey = ca.private,
            serial = 2L,
            extensions = if (withExtension) {
                listOf(TestCertificates.attestationExtension(securityLevel))
            } else {
                emptyList()
            },
        )
        return listOf(leaf, root)
    }

    /** Two self-signed certificates that do not sign one another. */
    fun unlinkedChain(attestedKey: PublicKey, attestedKeyOwner: PrivateKey): List<X509Certificate> {
        val leaf = TestCertificates.certificate(
            subjectCn = "Android Keystore Key",
            subjectPublicKey = attestedKey,
            issuerCn = "Android Keystore Key",
            issuerPrivateKey = attestedKeyOwner,
            serial = 7L,
            extensions = listOf(
                TestCertificates.attestationExtension(
                    AttestationExtensionParser.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                ),
            ),
        )
        val stranger = TestCertificates.newKeyPair()
        val other = TestCertificates.certificate(
            subjectCn = "Unrelated Root",
            subjectPublicKey = stranger.public,
            issuerCn = "Unrelated Root",
            issuerPrivateKey = stranger.private,
            serial = 8L,
        )
        return listOf(leaf, other)
    }

    // ---- tampering helpers --------------------------------------------------

    /** Flips one bit of the media, leaving the package byte-identical. */
    fun flipOneBit(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }

    /** Edits a signed metadata field in place, without updating the leaves. */
    fun editLatitude(packageJson: String, latitude: Double): String =
        JSONObject(packageJson).also { pkg ->
            pkg.getJSONObject("metadata").getJSONObject("location").put("latitude", latitude)
        }.toString()

    /** Replaces a value at `<block>.<field>` in the package. */
    fun replace(packageJson: String, block: String, field: String, value: Any): String =
        JSONObject(packageJson).also { it.getJSONObject(block).put(field, value) }.toString()

    /** Removes `<block>.<field>` entirely. */
    fun remove(packageJson: String, block: String, field: String): String =
        JSONObject(packageJson).also { it.getJSONObject(block).remove(field) }.toString()

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
