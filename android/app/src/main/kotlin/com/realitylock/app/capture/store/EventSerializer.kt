package com.realitylock.app.capture.store

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.model.DeviceData
import com.realitylock.app.capture.model.EventMetadata
import com.realitylock.app.capture.model.LocationData
import com.realitylock.app.capture.model.MediaData
import com.realitylock.app.capture.model.MotionData
import com.realitylock.app.capture.model.TimestampData
import com.realitylock.app.core.config.ProofPackageConstants
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts [CapturedEvent] to/from the JSON sidecar written next to the media
 * (ADR-0003).
 *
 * Mapping is written out explicitly rather than driven by reflection: this JSON
 * is a *proof artifact*, so the field names must provably match
 * `docs/design/proof-package.schema.json` and must not silently change if a
 * Kotlin property is renamed.
 *
 * Phase 2 writes the `eventId` / `media` / `metadata` subset. Phase 3 adds
 * `merkle` and `signature`, at which point the document becomes a complete,
 * schema-valid proof package. `EventSerializerSchemaTest` enforces that the
 * Phase-2 output is already a valid *prefix* of that package — i.e. every field
 * it does emit conforms, and it emits nothing the schema forbids.
 */
object EventSerializer {

    fun toJson(event: CapturedEvent): String = buildJson(event).toString(INDENT_SPACES)

    fun buildJson(event: CapturedEvent): JSONObject = JSONObject().apply {
        put(KEY_SCHEMA_URN, ProofPackageConstants.SCHEMA_URN)
        put(KEY_SCHEMA_VERSION, ProofPackageConstants.SCHEMA_VERSION)
        put(KEY_EVENT_ID, event.eventId)
        put(KEY_MEDIA, mediaToJson(event.media))
        put(KEY_METADATA, metadataToJson(event.metadata))
        put(KEY_CANONICALIZATION, ProofPackageConstants.JSON_CANONICALIZATION_SCHEME)
    }

    /**
     * [mediaFilePath] is supplied by the caller rather than read from the
     * document: it is device-local state that the proof package deliberately
     * does not carry (see [CapturedEvent.mediaFilePath]). The repository owns
     * the on-disk layout and so reconstructs the path from the event id.
     */
    fun fromJson(raw: String, mediaFilePath: String): CapturedEvent {
        val root = JSONObject(raw)
        return CapturedEvent(
            eventId = root.getString(KEY_EVENT_ID),
            mediaFilePath = mediaFilePath,
            media = mediaFromJson(root.getJSONObject(KEY_MEDIA)),
            metadata = metadataFromJson(root.getJSONObject(KEY_METADATA)),
        )
    }

    // ---- media ----

    private fun mediaToJson(media: MediaData) = JSONObject().apply {
        put(KEY_MIME_TYPE, media.mimeType)
        put(KEY_BYTE_LENGTH, media.byteLength)
        putOrNull(KEY_SHA256, media.sha256)
        putOrNull(KEY_STORAGE_REF, media.storageRef)
    }

    private fun mediaFromJson(json: JSONObject) = MediaData(
        mimeType = json.getString(KEY_MIME_TYPE),
        byteLength = json.getLong(KEY_BYTE_LENGTH),
        sha256 = json.optStringOrNull(KEY_SHA256),
        storageRef = json.optStringOrNull(KEY_STORAGE_REF),
    )

    // ---- metadata ----

    private fun metadataToJson(metadata: EventMetadata) = JSONObject().apply {
        put(KEY_LOCATION, metadata.location?.let(::locationToJson) ?: JSONObject.NULL)
        put(KEY_TIMESTAMP, timestampToJson(metadata.timestamp))
        put(KEY_MOTION, metadata.motion?.let(::motionToJson) ?: JSONObject.NULL)
        put(KEY_DEVICE, deviceToJson(metadata.device))
    }

    private fun metadataFromJson(json: JSONObject) = EventMetadata(
        location = json.optJSONObject(KEY_LOCATION)?.let(::locationFromJson),
        timestamp = timestampFromJson(json.getJSONObject(KEY_TIMESTAMP)),
        motion = json.optJSONObject(KEY_MOTION)?.let(::motionFromJson),
        device = deviceFromJson(json.getJSONObject(KEY_DEVICE)),
    )

    private fun locationToJson(location: LocationData) = JSONObject().apply {
        put(KEY_LATITUDE, location.latitude)
        put(KEY_LONGITUDE, location.longitude)
        put(KEY_ACCURACY_METERS, location.accuracyMeters.toDouble())
        putOrNull(KEY_ALTITUDE_METERS, location.altitudeMeters)
        putOrNull(KEY_PROVIDER, location.provider)
        putOrNull(KEY_FIX_AGE_MILLIS, location.fixAgeMillis)
        put(KEY_IS_MOCK, location.isMock)
    }

    private fun locationFromJson(json: JSONObject) = LocationData(
        latitude = json.getDouble(KEY_LATITUDE),
        longitude = json.getDouble(KEY_LONGITUDE),
        accuracyMeters = json.getDouble(KEY_ACCURACY_METERS).toFloat(),
        altitudeMeters = json.optDoubleOrNull(KEY_ALTITUDE_METERS),
        provider = json.optStringOrNull(KEY_PROVIDER),
        fixAgeMillis = json.optLongOrNull(KEY_FIX_AGE_MILLIS),
        isMock = json.optBoolean(KEY_IS_MOCK, false),
    )

    private fun timestampToJson(timestamp: TimestampData) = JSONObject().apply {
        put(KEY_WALL_CLOCK_MILLIS, timestamp.wallClockMillis)
        put(KEY_ISO_8601, timestamp.iso8601)
        put(KEY_ELAPSED_REALTIME_NANOS, timestamp.elapsedRealtimeNanos)
        putOrNull(KEY_WALL_CLOCK_OFFSET_MILLIS, timestamp.wallClockOffsetMillis)
        putOrNull(KEY_GPS_TIME_MILLIS, timestamp.gpsTimeMillis)
    }

    private fun timestampFromJson(json: JSONObject) = TimestampData(
        wallClockMillis = json.getLong(KEY_WALL_CLOCK_MILLIS),
        iso8601 = json.getString(KEY_ISO_8601),
        elapsedRealtimeNanos = json.getLong(KEY_ELAPSED_REALTIME_NANOS),
        wallClockOffsetMillis = json.optLongOrNull(KEY_WALL_CLOCK_OFFSET_MILLIS),
        gpsTimeMillis = json.optLongOrNull(KEY_GPS_TIME_MILLIS),
    )

    private fun motionToJson(motion: MotionData) = JSONObject().apply {
        put(KEY_ACCELEROMETER, motion.accelerometer.toJsonArray())
        putOrNull(KEY_GYROSCOPE, motion.gyroscope?.toJsonArray())
        putOrNull(KEY_SAMPLE_ELAPSED_REALTIME_NANOS, motion.sampleElapsedRealtimeNanos)
    }

    private fun motionFromJson(json: JSONObject) = MotionData(
        accelerometer = json.getJSONArray(KEY_ACCELEROMETER).toFloatList(),
        gyroscope = json.optJSONArray(KEY_GYROSCOPE)?.toFloatList(),
        sampleElapsedRealtimeNanos = json.optLongOrNull(KEY_SAMPLE_ELAPSED_REALTIME_NANOS),
    )

    private fun deviceToJson(device: DeviceData) = JSONObject().apply {
        put(KEY_INSTALL_ID, device.installId)
        put(KEY_MODEL, device.model)
        put(KEY_MANUFACTURER, device.manufacturer)
        put(KEY_SDK_INT, device.sdkInt)
        put(KEY_APP_VERSION_NAME, device.appVersionName)
        put(KEY_APP_VERSION_CODE, device.appVersionCode)
    }

    private fun deviceFromJson(json: JSONObject) = DeviceData(
        installId = json.getString(KEY_INSTALL_ID),
        model = json.getString(KEY_MODEL),
        manufacturer = json.getString(KEY_MANUFACTURER),
        sdkInt = json.getInt(KEY_SDK_INT),
        appVersionName = json.getString(KEY_APP_VERSION_NAME),
        appVersionCode = json.getInt(KEY_APP_VERSION_CODE),
    )

    // ---- helpers ----

    private fun JSONObject.putOrNull(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").ifEmpty { null }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key)

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key)

    private fun List<Float>.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach { array.put(it.toDouble()) } }

    private fun JSONArray.toFloatList(): List<Float> =
        (0 until length()).map { getDouble(it).toFloat() }

    private const val INDENT_SPACES = 2

    // Field names — mirror docs/design/proof-package.schema.json exactly.
    private const val KEY_SCHEMA_URN = "schemaUrn"
    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_EVENT_ID = "eventId"
    private const val KEY_MEDIA = "media"
    private const val KEY_METADATA = "metadata"
    private const val KEY_CANONICALIZATION = "canonicalization"

    private const val KEY_MIME_TYPE = "mimeType"
    private const val KEY_BYTE_LENGTH = "byteLength"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_STORAGE_REF = "storageRef"

    private const val KEY_LOCATION = "location"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_MOTION = "motion"
    private const val KEY_DEVICE = "device"

    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_ACCURACY_METERS = "accuracyMeters"
    private const val KEY_ALTITUDE_METERS = "altitudeMeters"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_FIX_AGE_MILLIS = "fixAgeMillis"
    private const val KEY_IS_MOCK = "isMock"

    private const val KEY_WALL_CLOCK_MILLIS = "wallClockMillis"
    private const val KEY_ISO_8601 = "iso8601"
    private const val KEY_ELAPSED_REALTIME_NANOS = "elapsedRealtimeNanos"
    private const val KEY_WALL_CLOCK_OFFSET_MILLIS = "wallClockOffsetMillis"
    private const val KEY_GPS_TIME_MILLIS = "gpsTimeMillis"

    private const val KEY_ACCELEROMETER = "accelerometer"
    private const val KEY_GYROSCOPE = "gyroscope"
    private const val KEY_SAMPLE_ELAPSED_REALTIME_NANOS = "sampleElapsedRealtimeNanos"

    private const val KEY_INSTALL_ID = "installId"
    private const val KEY_MODEL = "model"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_SDK_INT = "sdkInt"
    private const val KEY_APP_VERSION_NAME = "appVersionName"
    private const val KEY_APP_VERSION_CODE = "appVersionCode"
}
