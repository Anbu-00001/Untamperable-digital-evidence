package com.realitylock.app.capture.store

import com.realitylock.app.capture.model.MotionData
import com.realitylock.app.capture.store.CapturedEventFixtures.sampleEvent
import com.realitylock.app.core.config.CaptureConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the sidecar store's round-tripping and filesystem behaviour.
 *
 * Conformance to `docs/design/proof-package.schema.json` is asserted separately
 * in [EventSerializerSchemaTest], which validates against the real schema file.
 */
class EventStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    /**
     * The media path is no longer stored in the sidecar — the repository
     * reconstructs it — so a reloaded event carries the path derived from the
     * directory it was read from.
     */
    private fun reloadedIn(dir: File, event: com.realitylock.app.capture.model.CapturedEvent) =
        event.copy(
            mediaFilePath = File(dir, event.eventId + CaptureConfig.MEDIA_EXTENSION_JPEG).absolutePath,
        )

    @Test
    fun `serialization round-trips losslessly`() {
        val original = sampleEvent()
        val restored = EventSerializer.fromJson(EventSerializer.toJson(original), original.mediaFilePath)

        assertEquals(original, restored)
    }

    @Test
    fun `round-trips when optional location and motion are absent`() {
        val original = sampleEvent(location = null, motion = null)
        val restored = EventSerializer.fromJson(EventSerializer.toJson(original), original.mediaFilePath)

        assertNull(restored.metadata.location)
        assertNull(restored.metadata.motion)
        assertEquals(original, restored)
    }

    @Test
    fun `an absent gyroscope round-trips as null, not an empty list`() {
        val original = sampleEvent(
            motion = MotionData(
                accelerometer = listOf(0.12f, 9.79f, 0.34f),
                gyroscope = null,
                sampleElapsedRealtimeNanos = 894_511_998_000_000L,
            ),
        )
        val restored = EventSerializer.fromJson(EventSerializer.toJson(original), original.mediaFilePath)

        assertNull(
            "an absent gyroscope reading must not become an empty list or [0,0,0]",
            restored.metadata.motion?.gyroscope,
        )
        assertEquals(original, restored)
    }

    @Test
    fun `the sidecar does not persist the on-device media path`() {
        val json = EventSerializer.toJson(sampleEvent())

        assertFalse(json.contains("mediaFilePath"))
    }

    @Test
    fun `repository saves and reads back an event`() {
        val dir = tempFolder.newFolder("captures")
        val repo = FileEventRepository(dir)
        val event = sampleEvent()

        repo.save(event)

        assertEquals(reloadedIn(dir, event), repo.findById(event.eventId))
        assertEquals(1, repo.list().size)
    }

    @Test
    fun `the reconstructed media path points beside the sidecar`() {
        val dir = tempFolder.newFolder("captures")
        val repo = FileEventRepository(dir)
        val event = sampleEvent()
        repo.save(event)

        val reloaded = repo.findById(event.eventId)

        assertEquals(
            File(dir, event.eventId + CaptureConfig.MEDIA_EXTENSION_JPEG).absolutePath,
            reloaded?.mediaFilePath,
        )
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
    fun `delete removes both the sidecar and the media`() {
        val dir = tempFolder.newFolder("captures")
        val repo = FileEventRepository(dir)
        val event = sampleEvent()
        repo.save(event)
        val media = File(dir, event.eventId + CaptureConfig.MEDIA_EXTENSION_JPEG)
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(repo.delete(event.eventId))

        assertFalse("the media file must be deleted with its sidecar", media.exists())
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
