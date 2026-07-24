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
class FileEventRepository(
    private val baseDir: File,
    /**
     * Must match the extension the `MediaFileStore` sharing this [baseDir] was
     * built with — the repository reconstructs media paths by the same rule.
     * Declared here rather than assumed so the coupling is visible at the
     * single site that constructs both.
     */
    private val mediaExtension: String = CaptureConfig.MEDIA_EXTENSION_JPEG,
) : EventRepository {

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
            .mapNotNull { file -> readEvent(file.nameWithoutExtension) }
            .sortedByDescending { it.metadata.timestamp.wallClockMillis }

    override fun findById(eventId: String): CapturedEvent? = readEvent(eventId)

    override fun delete(eventId: String): Boolean {
        val mediaDeleted = mediaFile(eventId).delete()
        val metadataDeleted = metadataFile(eventId).delete()
        return metadataDeleted || mediaDeleted
    }

    private fun readEvent(eventId: String): CapturedEvent? {
        val file = metadataFile(eventId)
        if (!file.exists()) return null
        return runCatching {
            EventSerializer.fromJson(file.readText(), mediaFile(eventId).absolutePath)
        }.getOrNull()
    }

    private fun metadataFile(eventId: String) = File(baseDir, eventId + METADATA_EXTENSION)

    /**
     * The media path is derived here rather than stored in the sidecar — this
     * is the exact inverse of how `MediaFileStore` names the file, so the
     * layout has a single source of truth and a moved/restored capture
     * directory keeps working.
     */
    private fun mediaFile(eventId: String) = File(baseDir, eventId + mediaExtension)

    companion object {
        /**
         * Sidecar extension. Sourced from [CaptureConfig] so it cannot drift
         * from [CaptureConfig.MEDIA_EXTENSION_JPEG], which defines the other
         * half of the same on-disk layout.
         */
        const val METADATA_EXTENSION = CaptureConfig.METADATA_EXTENSION_JSON
    }
}
