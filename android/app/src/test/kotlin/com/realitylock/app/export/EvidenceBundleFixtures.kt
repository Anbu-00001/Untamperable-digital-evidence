package com.realitylock.app.export

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.model.DeviceData
import com.realitylock.app.capture.model.EventMetadata
import com.realitylock.app.capture.model.LocationData
import com.realitylock.app.capture.model.MediaData
import com.realitylock.app.capture.model.MerkleData
import com.realitylock.app.capture.model.MerkleLeaves
import com.realitylock.app.capture.model.MotionData
import com.realitylock.app.capture.model.PublicKeyData
import com.realitylock.app.capture.model.SignatureData
import com.realitylock.app.capture.model.TimestampData
import java.security.MessageDigest

/**
 * Fixtures for the evidence-bundle exporter.
 *
 * The stored package JSON here is written as a **hand-formatted string**, not
 * produced by `EventSerializer`, and that is the point. It carries irregular
 * whitespace, an unpadded key, a non-ASCII character and a trailing newline —
 * all things any re-serialization would tidy up. A test that fed the exporter
 * canonical output could not tell "the bytes were copied" from "the bytes were
 * re-encoded and happened to come out the same".
 *
 * Hashes are computed with the JDK's own [MessageDigest] rather than with
 * `com.realitylock.app.crypto.Hashing`, so the expectation comes from an
 * independent implementation of SHA-256 rather than from this project's wrapper
 * around it.
 */
object EvidenceBundleFixtures {

    const val EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    const val OTHER_EVENT_ID = "9c8b7a65-4321-4dcb-8a90-1f2e3d4c5b6a"

    const val EXPORTED_AT_ISO = "2026-08-07T11:22:33Z"
    const val EXPORTING_APP_VERSION = "0.9.1 (14)"
    const val CAPTURING_APP_VERSION = "0.1.0 (1)"

    const val MERKLE_ROOT = "5f0c1b2a3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8"
    const val METADATA_LEAF = "11111111111111111111111111111111111111111111111111111111111111aa"
    const val MEDIA_LEAF = "22222222222222222222222222222222222222222222222222222222222222bb"

    /**
     * Stand-in for JPEG bytes. Deliberately includes a CR/LF pair (any newline
     * translation on the write path would corrupt it), a NUL, and 0xFF — the
     * three things a stream that thought it was handling text would damage.
     */
    val MEDIA_BYTES: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x0D, 0x0A, 0x00, 0x1A,
        0x52, 0x4C, 0x0A, 0x0D,
        0x7F, 0x80.toByte(), 0xFF.toByte(), 0xD9.toByte(),
    )

    val MEDIA_SHA256: String = sha256Hex(MEDIA_BYTES)

    /** Different content, so its hash cannot accidentally match [MEDIA_SHA256]. */
    val TAMPERED_MEDIA_BYTES: ByteArray = MEDIA_BYTES.copyOf().also { it[4] = 0x42 }

    /**
     * The proof package exactly as it would sit on disk — irregular spacing,
     * an em dash, and a trailing newline all preserved.
     */
    fun packageBytes(
        eventId: String = EVENT_ID,
        mediaSha256: String = MEDIA_SHA256,
        includeSignature: Boolean = true,
    ): ByteArray = packageJson(eventId, mediaSha256, includeSignature).toByteArray(Charsets.UTF_8)

    fun packageJson(
        eventId: String = EVENT_ID,
        mediaSha256: String = MEDIA_SHA256,
        includeSignature: Boolean = true,
    ): String {
        val signatureBlock = if (includeSignature) {
            """,
              "signature":{ "algorithm" : "SHA256withECDSA",
                "value":"MEUCIQD0RmFrOm5hVGVzdEZpeHR1cmVTaWduYXR1cmVCeXRlcw==",
                "publicKey":{"format":"X.509","curve":"secp256r1","value":"MFkwEwYHKoZIzj0CAQ=="},
                "attestationCertificateChain":null }"""
        } else {
            ""
        }
        // Formatting below is intentionally scruffy. Do not "fix" it.
        //
        // One value in it is NOT free to change, though: `iso8601` must render
        // `wallClockMillis` exactly. The two were four hours apart when this
        // fixture was written — inherited from a defect in
        // docs/design/examples/proof-package.example.json that was fixed on
        // 2026-08-07 — which modelled a package the backend's
        // checkTimestampPlausible rejects outright. 1784812345678 ms is
        // 2026-07-23T13:12:25.678Z. A fixture for an evidence exporter should
        // not itself be inadmissible evidence.
        return """{"schemaUrn":"urn:realitylock:proof-package:1.0.0",
          "schemaVersion" :"1.0.0",
            "eventId":"$eventId",
          "media" : {"mimeType":"image/jpeg",   "byteLength":${MEDIA_BYTES.size},
             "sha256":"$mediaSha256","storageRef":null},
          "metadata":{"location":{"latitude":12.9716000,"longitude":77.5946,
              "accuracyMeters":4.2,"altitudeMeters":920.0,"provider":"fused",
              "fixAgeMillis":850,"isMock":false},
            "timestamp":{"wallClockMillis":1784812345678,"iso8601":"2026-07-23T13:12:25.678Z",
              "elapsedRealtimeNanos":894512000000000,"wallClockOffsetMillis":1783917833678,
              "gpsTimeMillis":1784812345120},
            "motion":{"accelerometer":[0.12,9.79,0.34],"gyroscope":[0.001,-0.002,0.0],
              "sampleElapsedRealtimeNanos":894511998000000},
            "device":{"installId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","model":"CPH2591 — dev unit",
              "manufacturer":"OnePlus","sdkInt":35,"appVersionName":"0.1.0","appVersionCode":1}},
          "canonicalization":"RFC8785",
          "merkle":{"algorithm":"SHA-256","scheme":"2-leaf",
            "leaves":{"media":"$MEDIA_LEAF","metadata":"$METADATA_LEAF"},"root":"$MERKLE_ROOT"}$signatureBlock
        }
        """
    }

    /** A fully signed event, matching [packageBytes]'s defaults. */
    fun signedEvent(
        eventId: String = EVENT_ID,
        mediaFilePath: String = "/data/user/0/com.realitylock.app/files/captures/$eventId.jpg",
        merkle: MerkleData? = MerkleData(
            algorithm = "SHA-256",
            scheme = "2-leaf",
            leaves = MerkleLeaves(media = MEDIA_LEAF, metadata = METADATA_LEAF),
            root = MERKLE_ROOT,
        ),
        signature: SignatureData? = SignatureData(
            algorithm = "SHA256withECDSA",
            value = "MEUCIQD0RmFrOm5hVGVzdEZpeHR1cmVTaWduYXR1cmVCeXRlcw==",
            publicKey = PublicKeyData(
                format = "X.509",
                curve = "secp256r1",
                value = "MFkwEwYHKoZIzj0CAQ==",
            ),
        ),
    ) = CapturedEvent(
        eventId = eventId,
        mediaFilePath = mediaFilePath,
        media = MediaData(
            mimeType = "image/jpeg",
            byteLength = MEDIA_BYTES.size.toLong(),
            sha256 = MEDIA_SHA256,
        ),
        metadata = EventMetadata(
            location = LocationData(
                latitude = 12.9716,
                longitude = 77.5946,
                accuracyMeters = 4.2f,
                altitudeMeters = 920.0,
                provider = "fused",
                fixAgeMillis = 850L,
                isMock = false,
            ),
            timestamp = TimestampData(
                wallClockMillis = 1_784_812_345_678L,
                iso8601 = "2026-07-23T13:12:25.678Z",
                elapsedRealtimeNanos = 894_512_000_000_000L,
                wallClockOffsetMillis = 1_783_917_833_678L,
                gpsTimeMillis = 1_784_812_345_120L,
            ),
            motion = MotionData(
                accelerometer = listOf(0.12f, 9.79f, 0.34f),
                gyroscope = listOf(0.001f, -0.002f, 0.0f),
                sampleElapsedRealtimeNanos = 894_511_998_000_000L,
            ),
            device = DeviceData(
                installId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                model = "CPH2591 — dev unit",
                manufacturer = "OnePlus",
                sdkInt = 35,
                appVersionName = "0.1.0",
                appVersionCode = 1,
            ),
        ),
        merkle = merkle,
        signature = signature,
    )

    /** The default happy-path bundle. */
    fun bundle(
        event: CapturedEvent = signedEvent(),
        packageBytes: ByteArray? = packageBytes(),
        mediaBytes: ByteArray? = MEDIA_BYTES,
        exportingAppVersion: String = EXPORTING_APP_VERSION,
        exportedAtIso: String = EXPORTED_AT_ISO,
    ): EvidenceBundle = EvidenceBundle.from(
        event = event,
        packageBytes = packageBytes,
        mediaBytes = mediaBytes,
        exportingAppVersion = exportingAppVersion,
        exportedAtIso = exportedAtIso,
    )

    /** SHA-256 via the JDK, so tests do not grade the app's own hash wrapper. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
