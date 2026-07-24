package com.realitylock.app.capture.store

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.core.config.CaptureConfig
import java.io.File

/**
 * Phase-2 [EventRepository] that stores each event as a JSON sidecar beside its
 * media file (ADR-0003), mirroring ProofMode's model: the captured media is
 * never modified, and all proof data lives in adjacent files.
 *
 * Layout under [baseDir]:
 * ```
 *   <eventId>.jpg     original media, untouched
 *   <eventId>.json    metadata sidecar (becomes the proof package in Phase 3)
 * ```
 *
 * Takes a plain [File] rather than a `Context` so it can be exercised in
 * ordinary JVM unit tests against a temporary directory.
 */
class FileEventRepository(private val baseDir: File) : EventRepository {

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    override fun save(event: CapturedEvent): CapturedEvent {
        metadataFile(event.eventId).writeText(EventSerializer.toJson(event))
        return event
    }

    override fun list(): List<CapturedEvent> =
        baseDir.listFiles { file -> file.isFile && file.name.endsWith(METADATA_EXTENSION) }
            .orEmpty()
            // A single corrupt sidecar must not take down the whole history view.
            .mapNotNull { file -> runCatching { EventSerializer.fromJson(file.readText()) }.getOrNull() }
            .sortedByDescending { it.metadata.timestamp.wallClockMillis }

    override fun findById(eventId: String): CapturedEvent? {
        val file = metadataFile(eventId)
        if (!file.exists()) return null
        return runCatching { EventSerializer.fromJson(file.readText()) }.getOrNull()
    }

    override fun delete(eventId: String): Boolean {
        val metadata = metadataFile(eventId)
        val event = findById(eventId)
        val mediaDeleted = event?.mediaFilePath?.let { File(it).delete() } ?: false
        val metadataDeleted = metadata.delete()
        return metadataDeleted || mediaDeleted
    }

    private fun metadataFile(eventId: String) = File(baseDir, eventId + METADATA_EXTENSION)

    companion object {
        /**
         * Sidecar extension. Sourced from [CaptureConfig] so it cannot drift
         * from [CaptureConfig.MEDIA_EXTENSION_JPEG], which defines the other
         * half of the same on-disk layout.
         */
        const val METADATA_EXTENSION = CaptureConfig.METADATA_EXTENSION_JSON
    }
}
