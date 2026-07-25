package com.realitylock.app.capture.model

/**
 * In-memory domain model of a captured event. These types mirror
 * `docs/design/proof-package.schema.json` field-for-field so the app producer
 * and the backend verifier cannot drift apart. Phase 2 populates media +
 * metadata; Phase 3 adds the hashes, Merkle root, and signature.
 *
 * Note: motion vectors are `List<Float>`, not `FloatArray`, so the data classes
 * get correct structural `equals`/`hashCode` (arrays compare by reference).
 */
data class CapturedEvent(
    val eventId: String,
    /**
     * Absolute on-device path to the media. Device-local state, deliberately
     * **not** part of the serialized proof package: it is not evidence, and
     * shipping it would leak the device's filesystem layout to every recipient.
     * The repository reconstructs it from [eventId] on read.
     */
    val mediaFilePath: String,
    val media: MediaData,
    val metadata: EventMetadata,
    /** Merkle composition over the media and metadata leaves; null pre-Phase-3. */
    val merkle: MerkleData? = null,
    /** ECDSA signature over [MerkleData.root]; null pre-Phase-3. */
    val signature: SignatureData? = null,
    /**
     * Advisory device/location integrity signals (Phase 4). Deliberately NOT
     * inside the signed Merkle root in v1: the authoritative plausibility check
     * is the verifier's, recomputed from the signed metadata of consecutive
     * events (research/02 §8), so a tamperer editing this block gains nothing.
     * The one integrity signal that must be signed — `isMock` — already lives in
     * the signed [EventMetadata.location].
     */
    val integrity: IntegrityData? = null,
)

data class IntegrityData(
    val location: LocationIntegrityData? = null,
)

data class LocationIntegrityData(
    /** Mirror of the signed `metadata.location.isMock`. */
    val isMock: Boolean,
    /** Names of the mock/integrity checks that actually ran (never dead ones). */
    val mockDetectionChecks: List<String>,
    /**
     * Whether raw-GNSS *signal* integrity was verified. Always false in v1: only
     * a capability probe runs; the C/N0-AGC spoofing analysis is future work.
     */
    val gnssChecked: Boolean,
    /**
     * Whether the implied speed from the previous located event is physically
     * possible. Null when there is no prior event, or the two are too close in
     * time/space to judge — never a false "implausible".
     */
    val speedPlausible: Boolean?,
)

data class MerkleData(
    val algorithm: String,
    val scheme: String,
    val leaves: MerkleLeaves,
    /** Lowercase hex; this is the value that gets signed. */
    val root: String,
)

data class MerkleLeaves(
    val media: String,
    val metadata: String,
)

data class SignatureData(
    val algorithm: String,
    /** Base64 DER ECDSA signature over the raw bytes of the Merkle root. */
    val value: String,
    val publicKey: PublicKeyData,
    /**
     * Base64 DER certificates, leaf first, chaining to a Google attestation
     * root. Null when the platform could not attest — recorded as absent rather
     * than implying hardware backing that was never proven (ADR-0004).
     */
    val attestationCertificateChain: List<String>? = null,
)

data class PublicKeyData(
    val format: String,
    val curve: String,
    /** Base64 X.509 SubjectPublicKeyInfo. */
    val value: String,
)

data class MediaData(
    val mimeType: String,
    val byteLength: Long,
    /** Lowercase hex SHA-256 of the media bytes; computed in Phase 3. */
    val sha256: String? = null,
    /** Cloud object reference; populated after upload in Phase 5. */
    val storageRef: String? = null,
)

data class EventMetadata(
    val location: LocationData?,
    val timestamp: TimestampData,
    val motion: MotionData?,
    val device: DeviceData,
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val provider: String? = null,
    /** How stale the fix was relative to the shutter, in millis. */
    val fixAgeMillis: Long? = null,
    /** Location.isMock()/isFromMockProvider() at capture (research/02 §6). */
    val isMock: Boolean = false,
)

data class TimestampData(
    val wallClockMillis: Long,
    val iso8601: String,
    /** Monotonic capture instant; shared clock base with sensors + CameraX. */
    val elapsedRealtimeNanos: Long,
    /** wallClock - monotonic, sampled at bundle time (research/03 §4). */
    val wallClockOffsetMillis: Long? = null,
    /** Location.getTime() UTC — an independent cross-check on the device clock. */
    val gpsTimeMillis: Long? = null,
)

data class MotionData(
    val accelerometer: List<Float>,
    /**
     * Null when there is no usable gyroscope reading. Deliberately nullable
     * rather than an empty list: `[]` would violate the schema's `vector3`
     * (`minItems: 3`), and `[0,0,0]` would assert a measured zero rotation that
     * was never taken.
     */
    val gyroscope: List<Float>? = null,
    val sampleElapsedRealtimeNanos: Long? = null,
)

data class DeviceData(
    /** Locally-generated install UUID — never IMEI/ANDROID_ID (research/03 §5). */
    val installId: String,
    val model: String,
    val manufacturer: String,
    val sdkInt: Int,
    val appVersionName: String,
    val appVersionCode: Int,
)
