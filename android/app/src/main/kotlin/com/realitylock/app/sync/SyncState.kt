package com.realitylock.app.sync

/**
 * How far a captured event has got towards being stored on the backend.
 *
 * Two steps rather than one because the package and its media travel separately:
 * the package is small JSON and goes first, the media is a raw upload that the
 * server accepts only after it has the package to check the digest against. A
 * capture stuck between the two is a real, distinguishable state and is worth
 * naming.
 */
enum class SyncStage {
    /** Captured, nothing sent yet. */
    PENDING,

    /** The proof package is stored on the backend; the media is not. */
    PACKAGE_STORED,

    /** Package and media are both stored. Nothing further to do. */
    COMPLETE,

    /**
     * Permanently rejected, or retried past [com.realitylock.app.core.config.SyncConfig.MAX_ATTEMPTS].
     * The capture is NOT lost — it remains on the device, and the failure reason
     * is kept so the user is told what happened instead of seeing a silent stall.
     */
    FAILED,
}

/**
 * Mutable sync bookkeeping for one event.
 *
 * Stored in its own sidecar (`sync/<eventId>.json`), deliberately **not** inside
 * the proof package. The package is a signed document: it is written once at
 * capture and never rewritten, so anything that changes afterwards has to live
 * somewhere else (ADR-0006 §3).
 */
data class SyncState(
    val eventId: String,
    val stage: SyncStage = SyncStage.PENDING,
    /** Upload attempts so far, used to stop retrying a hopeless event. */
    val attempts: Int = 0,
    val lastAttemptWallClockMillis: Long? = null,
    /** Human-readable reason for the most recent failure; null when fine. */
    val lastError: String? = null,
    /** Content-addressed reference the backend returned (`sha256:…`). */
    val storageRef: String? = null,
) {
    val isComplete: Boolean get() = stage == SyncStage.COMPLETE

    /** True when another attempt is still worth making. */
    val isRetryable: Boolean
        get() = stage != SyncStage.COMPLETE && stage != SyncStage.FAILED
}
