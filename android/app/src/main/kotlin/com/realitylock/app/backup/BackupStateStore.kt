package com.realitylock.app.backup

import org.json.JSONObject
import java.io.File

/**
 * Persists [BackupState] as one small JSON file per event, plus one file for the
 * most recent pass.
 *
 * Same layout reasoning as [com.realitylock.app.sync.SyncStateStore]: a separate
 * directory from `captures/`, because the captures directory is listed by
 * extension to find events and holds nothing but immutable evidence, while
 * backup state is mutable by definition (ADR-0006 §3).
 *
 * Takes a plain [File] rather than a Context so the whole engine is exercised in
 * ordinary JVM unit tests against a temporary directory.
 */
class BackupStateStore(private val baseDir: File) {

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    /**
     * The recorded state, or a fresh [BackupStage.NOT_BACKED_UP] one.
     *
     * A default rather than null: "no file yet" and "no copy written yet" are the
     * same fact, and making callers distinguish them invites one of the two paths
     * to be handled wrongly.
     */
    fun get(eventId: String): BackupState {
        val file = fileFor(eventId)
        if (!file.exists()) return BackupState(eventId)
        return runCatching { fromJson(JSONObject(file.readText())) }
            // A corrupt state file must not strand the event. Treating it as
            // never-backed-up costs one redundant write; treating it as backed
            // up would be the silent-failure this feature exists to prevent.
            .getOrElse { BackupState(eventId) }
    }

    fun put(state: BackupState) {
        fileFor(state.eventId).writeText(toJson(state).toString(INDENT_SPACES))
    }

    fun delete(eventId: String): Boolean = fileFor(eventId).delete()

    /** Every recorded per-event state, keyed by event id. */
    fun all(): Map<String, BackupState> =
        baseDir.listFiles { f ->
            f.isFile &&
                f.name.endsWith(EXTENSION) &&
                // Bookkeeping about the feature, not about an event. Event ids are
                // UUIDs and never start with the prefix, so this cannot hide one.
                !f.name.startsWith(BackupConfig.INTERNAL_FILE_PREFIX)
        }
            .orEmpty()
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            .associateBy { it.eventId }

    fun putLastPass(result: BackupPassResult) {
        File(baseDir, BackupConfig.LAST_PASS_FILE).writeText(
            JSONObject().apply {
                put(KEY_FINISHED_AT, result.finishedAtWallClockMillis)
                put(KEY_BACKED_UP, result.backedUp)
                put(KEY_ALREADY, result.alreadyBackedUp)
                put(KEY_FAILED, result.failed)
                put(KEY_BLOCKED_BY, result.blockedBy?.name)
                put(KEY_DETAIL, result.detail)
            }.toString(INDENT_SPACES),
        )
    }

    fun lastPass(): BackupPassResult? {
        val file = File(baseDir, BackupConfig.LAST_PASS_FILE)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            BackupPassResult(
                finishedAtWallClockMillis = json.getLong(KEY_FINISHED_AT),
                backedUp = json.optInt(KEY_BACKED_UP),
                alreadyBackedUp = json.optInt(KEY_ALREADY),
                failed = json.optInt(KEY_FAILED),
                blockedBy = json.optStringOrNull(KEY_BLOCKED_BY)
                    ?.let { name -> runCatching { BackupFailure.valueOf(name) }.getOrNull() },
                detail = json.optStringOrNull(KEY_DETAIL),
            )
        }.getOrNull()
    }

    /**
     * Forgets every per-event state, for use when the destination changes.
     *
     * The old states describe copies in a folder that is no longer the answer to
     * "where are my backups", and [BackupState.isBackedUpTo] would already refuse
     * to call them backed up. Clearing them keeps the attempt counters honest too:
     * an event that failed five times against an unreachable SD card deserves a
     * fresh five attempts against the folder the user just picked.
     */
    fun clearAll() {
        baseDir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            .orEmpty()
            .forEach { it.delete() }
    }

    private fun fileFor(eventId: String) = File(baseDir, eventId + EXTENSION)

    private fun toJson(state: BackupState): JSONObject = JSONObject().apply {
        put(KEY_EVENT_ID, state.eventId)
        put(KEY_STAGE, state.stage.name)
        put(KEY_ATTEMPTS, state.attempts)
        put(KEY_LAST_ATTEMPT, state.lastAttemptWallClockMillis)
        put(KEY_LAST_ERROR, state.lastError)
        put(KEY_FAILURE, state.failure?.name)
        put(KEY_DESTINATION, state.destinationId)
        put(KEY_FILE_NAME, state.fileName)
        put(KEY_SIZE, state.sizeBytes)
        put(KEY_SHA256, state.sha256)
        put(KEY_BACKED_UP_AT, state.backedUpAtWallClockMillis)
    }

    private fun fromJson(json: JSONObject): BackupState = BackupState(
        eventId = json.getString(KEY_EVENT_ID),
        stage = runCatching { BackupStage.valueOf(json.getString(KEY_STAGE)) }
            .getOrDefault(BackupStage.NOT_BACKED_UP),
        attempts = json.optInt(KEY_ATTEMPTS),
        lastAttemptWallClockMillis = json.optLongOrNull(KEY_LAST_ATTEMPT),
        lastError = json.optStringOrNull(KEY_LAST_ERROR),
        failure = json.optStringOrNull(KEY_FAILURE)
            ?.let { name -> runCatching { BackupFailure.valueOf(name) }.getOrNull() },
        destinationId = json.optStringOrNull(KEY_DESTINATION),
        fileName = json.optStringOrNull(KEY_FILE_NAME),
        sizeBytes = json.optLongOrNull(KEY_SIZE),
        sha256 = json.optStringOrNull(KEY_SHA256),
        backedUpAtWallClockMillis = json.optLongOrNull(KEY_BACKED_UP_AT),
    )

    private companion object {
        const val EXTENSION = ".json"
        const val INDENT_SPACES = 2

        const val KEY_EVENT_ID = "eventId"
        const val KEY_STAGE = "stage"
        const val KEY_ATTEMPTS = "attempts"
        const val KEY_LAST_ATTEMPT = "lastAttemptWallClockMillis"
        const val KEY_LAST_ERROR = "lastError"
        const val KEY_FAILURE = "failure"
        const val KEY_DESTINATION = "destinationId"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_SIZE = "sizeBytes"
        const val KEY_SHA256 = "sha256"
        const val KEY_BACKED_UP_AT = "backedUpAtWallClockMillis"

        const val KEY_FINISHED_AT = "finishedAtWallClockMillis"
        const val KEY_BACKED_UP = "backedUp"
        const val KEY_ALREADY = "alreadyBackedUp"
        const val KEY_FAILED = "failed"
        const val KEY_BLOCKED_BY = "blockedBy"
        const val KEY_DETAIL = "detail"
    }
}

/**
 * `optString` returns the literal string "null" for a JSON null, and `optLong`
 * returns 0. Both would be recorded as real values — a backup dated the epoch,
 * or an error message reading "null" — so absence is unwrapped explicitly.
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key)
