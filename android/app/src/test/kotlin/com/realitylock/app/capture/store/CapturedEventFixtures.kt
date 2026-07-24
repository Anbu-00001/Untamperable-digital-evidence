package com.realitylock.app.capture.store

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.model.DeviceData
import com.realitylock.app.capture.model.EventMetadata
import com.realitylock.app.capture.model.LocationData
import com.realitylock.app.capture.model.MediaData
import com.realitylock.app.capture.model.MotionData
import com.realitylock.app.capture.model.TimestampData

/**
 * Shared test fixtures for the sidecar store.
 *
 * Values are deliberately written out as literals rather than derived from the
 * production code: a test that computes its expectations from the thing under
 * test cannot detect that thing changing.
 */
object CapturedEventFixtures {

    const val DEFAULT_EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    fun sampleEvent(
        eventId: String = DEFAULT_EVENT_ID,
        wallClockMillis: Long = 1_784_812_345_678L,
        mediaFilePath: String = "/data/user/0/com.realitylock.app/files/captures/$eventId.jpg",
        location: LocationData? = LocationData(
            latitude = 12.9716,
            longitude = 77.5946,
            accuracyMeters = 4.2f,
            altitudeMeters = 920.0,
            provider = "fused",
            fixAgeMillis = 850L,
            isMock = false,
        ),
        motion: MotionData? = MotionData(
            accelerometer = listOf(0.12f, 9.79f, 0.34f),
            gyroscope = listOf(0.001f, -0.002f, 0.0f),
            sampleElapsedRealtimeNanos = 894_511_998_000_000L,
        ),
    ) = CapturedEvent(
        eventId = eventId,
        mediaFilePath = mediaFilePath,
        media = MediaData(mimeType = "image/jpeg", byteLength = 2_483_712L),
        metadata = EventMetadata(
            location = location,
            timestamp = TimestampData(
                wallClockMillis = wallClockMillis,
                iso8601 = "2026-07-23T09:12:25.678Z",
                elapsedRealtimeNanos = 894_512_000_000_000L,
                wallClockOffsetMillis = 1_783_917_833_678L,
                gpsTimeMillis = 1_784_812_345_120L,
            ),
            motion = motion,
            device = DeviceData(
                installId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                model = "CPH2591",
                manufacturer = "OnePlus",
                sdkInt = 35,
                appVersionName = "0.1.0",
                appVersionCode = 1,
            ),
        ),
    )
}
