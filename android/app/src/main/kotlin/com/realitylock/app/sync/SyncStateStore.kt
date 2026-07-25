package com.realitylock.app.sync

import org.json.JSONObject
import java.io.File

/**
 * Persists [SyncState] as one small JSON file per event.
 *
 * Lives in its own directory, **separate from the captures directory**, for two
 * reasons. The obvious one: the captures directory is listed by extension to
 * find events, so a second `.json` beside each package would be scanned as a
 * candidate event. The important one: the captures directory holds nothing but
 * immutable evidence, and sync state is mutable by definition (ADR-0006 §3).
 * Keeping them apart is what makes "the signed package is never rewritten" a
 * property of the layout rather than a promise in a comment.
 *
 * Takes a plain [File] rather than a Context so it is exercised in ordinary JVM
 * unit tests against a temporary directory.
 */
class SyncStateStore(private val baseDir: File) {

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    /**
     * The recorded state, or a fresh [SyncStage.PENDING] one for an event never
     * synced. Returning a default rather than null means callers never have to
     * distinguish "no file yet" from "nothing sent yet" — they are the same thing.
     */
    fun get(eventId: String): SyncState {
        val file = fileFor(eventId)
        if (!file.exists()) return SyncState(eventId)
        return runCatching { fromJson(file.readText()) }
            // A corrupt state file must not strand the event: treat it as
            // un-synced and let the next attempt overwrite it. Worst case the
            // backend answers "already stored", which is idempotent by design.
            .getOrElse { SyncState(eventId) }
    }

    fun put(state: SyncState) {
        fileFor(state.eventId).writeText(toJson(state).toString(INDENT_SPACES))
    }

    /** Drops the state file, e.g. when its event is deleted. */
    fun delete(eventId: String): Boolean = fileFor(eventId).delete()

    /** Every recorded state, keyed by event id. */
    fun all(): Map<String, SyncState> =
        baseDir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            .orEmpty()
            .mapNotNull { f -> runCatching { fromJson(f.readText()) }.getOrNull() }
            .associateBy { it.eventId }

    private fun fileFor(eventId: String) = File(baseDir, eventId + EXTENSION)

    private fun toJson(state: SyncState): JSONObject = JSONObject().apply {
        put(KEY_EVENT_ID, state.eventId)
        put(KEY_STAGE, state.stage.name)
        put(KEY_ATTEMPTS, state.attempts)
        // org.json turns a Kotlin null into JSONObject.NULL, which round-trips
        // correctly through the optional readers below.
        put(KEY_LAST_ATTEMPT, state.lastAttemptWallClockMillis)
        put(KEY_LAST_ERROR, state.lastError)
        put(KEY_STORAGE_REF, state.storageRef)
    }

    private fun fromJson(raw: String): SyncState {
        val json = JSONObject(raw)
        return SyncState(
            eventId = json.getString(KEY_EVENT_ID),
            // An unrecognised stage name (a downgrade, a hand-edited file) means
            // "we do not know how far this got", which is PENDING — never
            // COMPLETE, since wrongly assuming completion would silently drop
            // the event from sync forever.
            stage = runCatching { SyncStage.valueOf(json.getString(KEY_STAGE)) }
                .getOrDefault(SyncStage.PENDING),
            attempts = json.optInt(KEY_ATTEMPTS, 0),
            lastAttemptWallClockMillis =
                if (json.isNull(KEY_LAST_ATTEMPT)) null else json.getLong(KEY_LAST_ATTEMPT),
            lastError = if (json.isNull(KEY_LAST_ERROR)) null else json.getString(KEY_LAST_ERROR),
            storageRef = if (json.isNull(KEY_STORAGE_REF)) null else json.getString(KEY_STORAGE_REF),
        )
    }

    private companion object {
        const val EXTENSION = ".json"
        const val INDENT_SPACES = 2

        const val KEY_EVENT_ID = "eventId"
        const val KEY_STAGE = "stage"
        const val KEY_ATTEMPTS = "attempts"
        const val KEY_LAST_ATTEMPT = "lastAttemptWallClockMillis"
        const val KEY_LAST_ERROR = "lastError"
        const val KEY_STORAGE_REF = "storageRef"
    }
}
