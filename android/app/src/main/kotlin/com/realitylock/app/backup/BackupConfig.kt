package com.realitylock.app.backup

import java.util.concurrent.TimeUnit

/**
 * Tuning and naming for evidence backup, declared once.
 *
 * Deliberately kept in this package rather than added to
 * `com.realitylock.app.core.config`, where the app's other config objects live:
 * that package is shared ground and this feature was built alongside concurrent
 * work in neighbouring packages. Nothing here is referenced from outside
 * `backup`/`ui.backup`, so the locality costs nothing. Move it to `core.config`
 * if a second feature ever needs these values.
 */
object BackupConfig {

    /**
     * Directory holding per-event backup bookkeeping, under `filesDir`.
     *
     * A separate directory from both `captures/` and `sync/`, for the same
     * reason [com.realitylock.app.sync.SyncStateStore] keeps its own: the
     * captures directory is listed by extension to find events, and it holds
     * nothing but immutable evidence. Backup state is mutable by definition
     * (ADR-0006 §3).
     */
    const val STATE_SUBDIR: String = "backup-state"

    /** File inside [STATE_SUBDIR] recording the most recent pass, for the UI. */
    const val LAST_PASS_FILE: String = "_last-pass.json"

    /**
     * Prefix marking a file in the state directory as bookkeeping about the
     * whole feature rather than about one event. Event ids are UUIDs and never
     * begin with it, so the two can never collide.
     */
    const val INTERNAL_FILE_PREFIX: String = "_"

    // ---- Destination preference ------------------------------------------

    const val PREFS_NAME: String = "reality_lock_backup"
    const val KEY_TREE_URI: String = "destination_tree_uri"

    // ---- File naming at the destination ----------------------------------

    /**
     * A single `.zip` extension, not `.evidence.zip`. Some DocumentsProviders
     * normalise a created document's name against the MIME type and append an
     * extension when they think one is missing; a double extension is exactly
     * the shape that provokes it. The engine refuses to record success when the
     * provider hands back a name other than the one requested, so a
     * provider-appended extension would turn into a hard, permanent failure for
     * every capture. One extension avoids the argument entirely.
     */
    const val BUNDLE_NAME_SUFFIX: String = "-evidence.zip"

    /**
     * Suffix for the staging document a bundle is written to before it is
     * promoted to [BUNDLE_NAME_SUFFIX].
     *
     * The staging step is what stops a half-written archive from ever carrying
     * the real name. If the process is killed mid-write, or the SD card is
     * pulled, what survives in the folder is an obviously-incomplete `.part`
     * file rather than a truncated ZIP that a human browsing the folder would
     * reasonably take for a backup.
     */
    const val STAGING_NAME_SUFFIX: String = "-evidence.zip.part"

    const val BUNDLE_MIME_TYPE: String = "application/zip"

    /** Staging documents are typed as opaque bytes: they are not valid ZIPs yet. */
    const val STAGING_MIME_TYPE: String = "application/octet-stream"

    /**
     * Bundles are written in chunks rather than one `write` call, so a failing
     * stream fails partway instead of at the very end, and so a large archive
     * is not handed to the provider as one enormous buffer.
     */
    const val WRITE_CHUNK_BYTES: Int = 64 * 1024

    // ---- WorkManager -----------------------------------------------------

    /** Unique work name, so repeated enqueues cannot pile up duplicate passes. */
    const val WORK_NAME: String = "reality-lock-evidence-backup"

    /** First retry delay; WorkManager doubles it on each successive failure. */
    const val BACKOFF_DELAY_SECONDS: Long = 30
    val BACKOFF_TIME_UNIT: TimeUnit = TimeUnit.SECONDS

    /**
     * After this many failed attempts an event stops being retried automatically
     * and is reported as failed, so an event that can never be written cannot
     * retry forever. Lower than sync's limit because the failures here are
     * mostly physical (card removed, folder deleted, disk full) and do not clear
     * up on their own the way a flaky network does. The capture is never lost —
     * the internal copy is untouched — and the user can retry explicitly.
     */
    const val MAX_ATTEMPTS: Int = 5

    /** File name for an event's finished bundle at the destination. */
    fun bundleFileName(eventId: String): String = safeName(eventId) + BUNDLE_NAME_SUFFIX

    /** File name for an event's in-progress bundle at the destination. */
    fun stagingFileName(eventId: String): String = safeName(eventId) + STAGING_NAME_SUFFIX

    /**
     * Event ids are UUIDs today, but this layer turns one into a *file name* in a
     * directory the app does not own. Anything outside this set — a separator, a
     * `..`, a NUL — is replaced rather than passed through, so a malformed id
     * cannot reach outside the chosen folder.
     */
    private fun safeName(eventId: String): String =
        eventId.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }
            .joinToString("")
            .ifEmpty { "unnamed" }
}
