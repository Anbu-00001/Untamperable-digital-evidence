package com.realitylock.app.sync

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.model.DeviceData
import com.realitylock.app.capture.model.EventMetadata
import com.realitylock.app.capture.model.MediaData
import com.realitylock.app.capture.model.MerkleData
import com.realitylock.app.capture.model.MerkleLeaves
import com.realitylock.app.capture.model.TimestampData
import com.realitylock.app.capture.store.EventRepository

/**
 * In-memory [EventRepository] for the sync tests, with the package bytes supplied
 * explicitly.
 *
 * Holding the bytes separately from the parsed event is deliberate: it lets a test
 * assert that the sync layer transmits *those* bytes rather than something
 * re-serialized from the model, which is the property ADR-0006 §2 exists to
 * protect and would be invisible if the fake regenerated the JSON.
 */
class FakeEventRepository(
    private val events: MutableList<CapturedEvent> = mutableListOf(),
    private val packageBytes: MutableMap<String, ByteArray> = mutableMapOf(),
) : EventRepository {

    override fun save(event: CapturedEvent): CapturedEvent {
        events.add(event)
        return event
    }

    override fun list(): List<CapturedEvent> = events.toList()

    override fun findById(eventId: String): CapturedEvent? = events.find { it.eventId == eventId }

    override fun readPackageBytes(eventId: String): ByteArray? = packageBytes[eventId]

    override fun delete(eventId: String): Boolean {
        packageBytes.remove(eventId)
        return events.removeIf { it.eventId == eventId }
    }

    fun add(event: CapturedEvent, bytes: ByteArray?) {
        events.add(event)
        if (bytes != null) packageBytes[event.eventId] = bytes
    }

    companion object {
        /** A minimal but structurally complete event, for sync tests. */
        fun event(
            eventId: String,
            mediaFilePath: String,
            wallClockMillis: Long = 1_784_812_345_678L,
        ): CapturedEvent = CapturedEvent(
            eventId = eventId,
            mediaFilePath = mediaFilePath,
            media = MediaData(
                mimeType = "image/jpeg",
                byteLength = 20,
                sha256 = "a".repeat(64),
            ),
            metadata = EventMetadata(
                location = null,
                timestamp = TimestampData(
                    wallClockMillis = wallClockMillis,
                    iso8601 = "2026-07-23T13:12:25.678Z",
                    elapsedRealtimeNanos = 894_512_000_000_000L,
                    wallClockOffsetMillis = 1_783_917_833_678L,
                ),
                motion = null,
                device = DeviceData(
                    installId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                    model = "CPH2591",
                    manufacturer = "OnePlus",
                    sdkInt = 35,
                    appVersionName = "0.1.0",
                    appVersionCode = 1,
                ),
            ),
            merkle = MerkleData(
                algorithm = "SHA-256",
                scheme = "2-leaf",
                leaves = MerkleLeaves(media = "a".repeat(64), metadata = "b".repeat(64)),
                root = "c".repeat(64),
            ),
        )
    }
}
