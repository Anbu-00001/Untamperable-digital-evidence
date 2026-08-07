package com.realitylock.app.backup

/**
 * Decides which captures still need a second copy, attempts them, and records
 * honestly what happened.
 *
 * Holds no Android types on purpose — the destination is behind [BackupTarget]
 * and the bytes behind [EvidenceBundleSource] — so every rule below is covered by
 * ordinary JVM unit tests rather than only on a device.
 *
 * ## The rule that shapes everything else
 *
 * An event is only "backed up" when a verified copy exists **at the current
 * destination** ([BackupState.isBackedUpTo]). Not "a copy was written once", not
 * "a write succeeded" — those are the claims that let a user stop worrying about
 * evidence that is not actually anywhere. Everything here is arranged so that the
 * green state cannot be reached by any path except bytes that were written, read
 * back, and matched.
 */
class EvidenceBackupEngine(
    private val stateStore: BackupStateStore,
    private val destination: DestinationStatus,
    private val target: BackupTarget,
    private val bundles: EvidenceBundleSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * The parts of [BackupDestination] this engine needs, narrowed to an
     * interface so the engine has no Android dependency.
     */
    interface DestinationStatus {
        /** Identity of the folder in use, or null when none is chosen. */
        val destinationId: String?

        /** Why the destination is unusable right now, or null when it is fine. */
        fun currentFailure(): BackupFailure?
    }

    /**
     * Attempts a backup for every event in [eventIds] that needs one.
     *
     * A destination-level problem short-circuits the whole pass. Blaming each of
     * forty captures individually for one revoked folder permission would bury
     * the single thing the user has to fix under forty identical errors, so the
     * pass records [BackupPassResult.blockedBy] and writes no per-event state.
     */
    fun runPass(eventIds: List<String>): BackupPassResult {
        val blocked = destination.currentFailure()
        if (blocked != null) {
            return BackupPassResult(clock(), blockedBy = blocked).also(stateStore::putLastPass)
        }

        val currentDestination = destination.destinationId
        var backedUp = 0
        var already = 0
        var failed = 0

        for (eventId in eventIds) {
            val state = stateStore.get(eventId)

            if (state.isBackedUpTo(currentDestination)) {
                already++
                continue
            }

            // A state describing a *different* folder is not this destination's
            // history. Starting it fresh is what gives an event a full attempt
            // budget against the folder the user just picked, instead of
            // inheriting failures from a card that is no longer in the phone.
            val forThisDestination =
                if (state.destinationId == currentDestination) state
                else BackupState(eventId, destinationId = currentDestination)

            if (forThisDestination.stage == BackupStage.FAILED &&
                forThisDestination.attempts >= BackupConfig.MAX_ATTEMPTS
            ) {
                failed++
                continue
            }

            if (attempt(eventId, forThisDestination, currentDestination) == BackupStage.BACKED_UP) {
                backedUp++
            } else {
                failed++
            }
        }

        return BackupPassResult(
            finishedAtWallClockMillis = clock(),
            backedUp = backedUp,
            alreadyBackedUp = already,
            failed = failed,
        ).also(stateStore::putLastPass)
    }

    /** One event, one attempt. Returns the stage it ended in. */
    private fun attempt(
        eventId: String,
        previous: BackupState,
        currentDestination: String?,
    ): BackupStage {
        val attempts = previous.attempts + 1
        val now = clock()

        val bytes = runCatching { bundles.bundleFor(eventId) }.getOrElse { error ->
            return record(
                previous.copy(
                    stage = stageFor(BackupFailure.BUNDLE_UNAVAILABLE, attempts),
                    attempts = attempts,
                    lastAttemptWallClockMillis = now,
                    lastError = error.message ?: error::class.java.simpleName,
                    failure = BackupFailure.BUNDLE_UNAVAILABLE,
                    destinationId = currentDestination,
                ),
            )
        }

        if (bytes.isEmpty()) {
            // An empty archive would be written and verified perfectly happily,
            // and would sit in the folder proving nothing. Refused explicitly.
            return record(
                previous.copy(
                    stage = stageFor(BackupFailure.BUNDLE_UNAVAILABLE, attempts),
                    attempts = attempts,
                    lastAttemptWallClockMillis = now,
                    lastError = "the exporter produced an empty bundle",
                    failure = BackupFailure.BUNDLE_UNAVAILABLE,
                    destinationId = currentDestination,
                ),
            )
        }

        return when (val outcome = target.writeBundle(eventId, bytes)) {
            is BackupOutcome.Written -> record(
                previous.copy(
                    stage = BackupStage.BACKED_UP,
                    attempts = attempts,
                    lastAttemptWallClockMillis = now,
                    lastError = null,
                    failure = null,
                    destinationId = currentDestination,
                    fileName = outcome.fileName,
                    sizeBytes = outcome.sizeBytes,
                    sha256 = outcome.sha256,
                    backedUpAtWallClockMillis = now,
                ),
            )

            is BackupOutcome.Failed -> record(
                previous.copy(
                    stage = stageFor(outcome.failure, attempts),
                    attempts = attempts,
                    lastAttemptWallClockMillis = now,
                    lastError = outcome.message,
                    failure = outcome.failure,
                    destinationId = currentDestination,
                    // Deliberately cleared. Carrying over the size and digest of
                    // an *earlier* successful write would leave a FAILED state
                    // advertising a verified copy.
                    fileName = null,
                    sizeBytes = null,
                    sha256 = null,
                    backedUpAtWallClockMillis = null,
                ),
            )
        }
    }

    /**
     * FAILED means "automatic retrying has stopped", so it is reached either by
     * exhausting the attempt budget or by a failure that repeating cannot fix —
     * a name conflict just creates another copy nobody is looking for.
     */
    private fun stageFor(failure: BackupFailure, attempts: Int): BackupStage =
        if (!failure.retryable || attempts >= BackupConfig.MAX_ATTEMPTS) BackupStage.FAILED
        else BackupStage.NOT_BACKED_UP

    private fun record(state: BackupState): BackupStage {
        stateStore.put(state)
        return state.stage
    }

    /** Drops both the copy at the destination and the bookkeeping for an event. */
    fun forget(eventId: String) {
        runCatching { target.deleteBundle(eventId) }
        stateStore.delete(eventId)
    }
}
