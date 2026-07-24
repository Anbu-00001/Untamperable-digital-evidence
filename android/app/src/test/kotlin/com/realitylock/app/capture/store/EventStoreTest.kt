package com.realitylock.app.capture.store

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.model.DeviceData
import com.realitylock.app.capture.model.EventMetadata
import com.realitylock.app.capture.model.LocationData
import com.realitylock.app.capture.model.MediaData
import com.realitylock.app.capture.model.MotionData
import com.realitylock.app.capture.model.TimestampData
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the sidecar store: serialization must round-trip losslessly and the
 * on-disk field names must match `docs/design/proof-package.schema.json`, since
 * the backend verifier reads the same contract.
 */
class EventStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun sampleEvent(
        eventId: String = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        wallClockMillis: Long = 1_784_812_345_678L,
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
        mediaFilePath = "/data/user/0/com.realitylock.app/files/captures/$eventId.jpg",
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

    @Test
    fun `serialization round-trips losslessly`() {
        val original = sampleEvent()
        val restored = EventSerializer.fromJson(EventSerializer.toJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun `round-trips when optional location and motion are absent`() {
        val original = sampleEvent(location = null, motion = null)
        val restored = EventSerializer.fromJson(EventSerializer.toJson(original))

        assertNull(restored.metadata.location)
        assertNull(restored.metadata.motion)
        assertEquals(original, restored)
    }

    @Test
    fun `on-disk field names match the proof-package schema contract`() {
        val json = JSONObject(EventSerializer.toJson(sampleEvent()))

        assertEquals("urn:realitylock:proof-package:1.0.0", json.getString("schemaUrn"))
        assertEquals("RFC8785", json.getString("canonicalization"))

        val metadata = json.getJSONObject("metadata")
        val location = metadata.getJSONObject("location")
        assertTrue(location.has("latitude"))
        assertTrue(location.has("longitude"))
        assertTrue(location.has("accuracyMeters"))
        assertTrue(location.has("isMock"))

        val timestamp = metadata.getJSONObject("timestamp")
        assertTrue(timestamp.has("wallClockMillis"))
        assertTrue(timestamp.has("iso8601"))
        assertTrue(timestamp.has("elapsedRealtimeNanos"))

        val device = metadata.getJSONObject("device")
        assertTrue(device.has("installId"))
        assertTrue(device.has("appVersionCode"))
    }

    @Test
    fun `repository saves and reads back an event`() {
        val repo = FileEventRepository(tempFolder.newFolder("captures"))
        val event = sampleEvent()

        repo.save(event)

        assertEquals(event, repo.findById(event.eventId))
        assertEquals(1, repo.list().size)
    }

    @Test
    fun `findById returns null for an unknown id`() {
        val repo = FileEventRepository(tempFolder.newFolder("captures"))
        assertNull(repo.findById("does-not-exist"))
    }

    @Test
    fun `list returns newest first`() {
        val repo = FileEventRepository(tempFolder.newFolder("captures"))
        repo.save(sampleEvent(eventId = "older", wallClockMillis = 1_000L))
        repo.save(sampleEvent(eventId = "newer", wallClockMillis = 9_000L))

        assertEquals(listOf("newer", "older"), repo.list().map { it.eventId })
    }

    @Test
    fun `delete removes the event`() {
        val repo = FileEventRepository(tempFolder.newFolder("captures"))
        val event = sampleEvent()
        repo.save(event)

        assertTrue(repo.delete(event.eventId))
        assertNull(repo.findById(event.eventId))
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun `delete reports false when nothing existed`() {
        val repo = FileEventRepository(tempFolder.newFolder("captures"))
        assertFalse(repo.delete("does-not-exist"))
    }

    @Test
    fun `a corrupt sidecar is skipped rather than breaking the listing`() {
        val dir = tempFolder.newFolder("captures")
        val repo = FileEventRepository(dir)
        repo.save(sampleEvent(eventId = "good"))
        File(dir, "broken.json").writeText("{ this is not valid json")

        val listed = repo.list()
        assertEquals(1, listed.size)
        assertEquals("good", listed.first().eventId)
        assertNotNull(repo.findById("good"))
    }
}
