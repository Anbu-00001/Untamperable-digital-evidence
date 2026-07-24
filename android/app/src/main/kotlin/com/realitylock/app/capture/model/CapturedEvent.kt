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
    val mediaFilePath: String,
    val media: MediaData,
    val metadata: EventMetadata,
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
    val gyroscope: List<Float>,
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
