package com.realitylock.app.capture.store

import com.realitylock.app.capture.model.CapturedEvent

/**
 * Persistence boundary for captured events.
 *
 * Capture code depends only on this interface, so the Phase-2 file-backed
 * implementation can be replaced by a Room-backed one in Phase 5 (when
 * sync-status queries make that worthwhile) without touching the pipeline.
 * See docs/design/adr/ADR-0003-local-event-store.md.
 */
interface EventRepository {

    /** Persists an event, returning it as stored. */
    fun save(event: CapturedEvent): CapturedEvent

    /** All stored events, newest first. */
    fun list(): List<CapturedEvent>

    /** Looks up a single event, or null if it does not exist. */
    fun findById(eventId: String): CapturedEvent?

    /**
     * The stored proof package for an event, as the **exact bytes on disk**, or
     * null if there is no such event.
     *
     * Exists so the sync layer can forward a signed document verbatim instead of
     * re-serializing a parsed [CapturedEvent]: those bytes are what the metadata
     * hash and signature cover, and a re-encode that changed number formatting or
     * escaping would fail verification in a way indistinguishable from tampering
     * (ADR-0006 §2). Reading them is the repository's job because the repository
     * owns the on-disk layout.
     */
    fun readPackageBytes(eventId: String): ByteArray?

    /** Removes an event and its media. Returns true if something was deleted. */
    fun delete(eventId: String): Boolean
}
