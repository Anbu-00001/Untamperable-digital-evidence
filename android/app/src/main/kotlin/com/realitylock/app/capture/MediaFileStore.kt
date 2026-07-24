package com.realitylock.app.capture

import com.realitylock.app.core.config.CaptureConfig
import java.io.File

/**
 * Owns where captured media lives on disk.
 *
 * Media is written to app-private storage under [baseDir] and is **never
 * modified after capture** — every later stage (hashing in Phase 3, upload in
 * Phase 5) reads these bytes rather than rewriting them. That immutability is
 * what makes the recorded hash meaningful.
 *
 * Takes a plain [File] so it can be unit tested against a temp directory.
 */
class MediaFileStore(
    private val baseDir: File,
    private val extension: String = CaptureConfig.MEDIA_EXTENSION_JPEG,
) {

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    /** Destination path for an event's media (does not create the file). */
    fun fileFor(eventId: String): File = File(baseDir, eventId + extension)

    /** Writes the captured bytes and returns the resulting file. */
    fun write(eventId: String, bytes: ByteArray): File =
        fileFor(eventId).apply { writeBytes(bytes) }
}
