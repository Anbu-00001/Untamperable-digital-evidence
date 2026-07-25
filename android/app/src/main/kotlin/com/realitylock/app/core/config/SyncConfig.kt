package com.realitylock.app.core.config

import java.util.concurrent.TimeUnit

/**
 * Backend endpoints and sync tuning (Phase 5). Every path and timeout lives here
 * rather than as a literal at a call site, so the contract with the backend is
 * declared in one readable place.
 *
 * The paths are relative to [AppConfig.backendBaseUrl], which carries the
 * trailing slash — so these must NOT start with one, or resolving them against
 * the base URL would discard any path prefix the deployment uses.
 */
object SyncConfig {

    /** POST a proof package here; also the prefix for per-event routes. */
    const val PROOF_PATH: String = "proof"

    /** POST/GET the raw media bytes: `proof/<eventId>/media`. */
    const val MEDIA_PATH_SEGMENT: String = "media"

    /** POST a package here for a per-check verification breakdown. */
    const val VERIFY_PATH: String = "verify"

    const val CONTENT_TYPE_JSON: String = "application/json"

    /**
     * Media is uploaded as raw bytes, not base64 inside JSON: base64 would
     * inflate every upload by a third over a mobile connection for nothing.
     */
    const val CONTENT_TYPE_OCTET_STREAM: String = "application/octet-stream"

    /** Subdirectory holding mutable sync state, kept OUT of the captures dir. */
    const val SYNC_STATE_SUBDIR: String = "sync"

    // ---- HTTP timeouts -----------------------------------------------------
    // Deliberately generous: this work is deferrable background sync on a mobile
    // connection, not a user-facing request, so waiting is cheaper than a retry.
    const val CONNECT_TIMEOUT_SECONDS: Long = 15
    const val READ_TIMEOUT_SECONDS: Long = 30
    const val WRITE_TIMEOUT_SECONDS: Long = 60

    // ---- WorkManager -------------------------------------------------------

    /** Unique work name, so enqueuing repeatedly cannot pile up duplicate syncs. */
    const val WORK_NAME: String = "reality-lock-proof-sync"

    /**
     * First retry delay; WorkManager doubles it on each successive failure.
     * 30 s is above the platform's 10 s floor and short enough that a demo does
     * not stall, while still backing off properly on a genuinely down server.
     */
    const val BACKOFF_DELAY_SECONDS: Long = 30
    val BACKOFF_TIME_UNIT: TimeUnit = TimeUnit.SECONDS

    /**
     * After this many failed attempts an event stops being retried and is marked
     * failed, so a permanently unsyncable capture cannot retry forever and drain
     * the battery. The capture itself is never lost — it stays on device, and the
     * user can retry it explicitly.
     */
    const val MAX_ATTEMPTS: Int = 8
}
