package com.realitylock.app.sync

import com.realitylock.app.capture.store.EventRepository
import com.realitylock.app.core.config.SyncConfig
import java.io.File

/**
 * Drains the local queue: for every captured event not yet fully stored on the
 * backend, sends the package and then the media.
 *
 * Deliberately free of WorkManager and Android framework types. [SyncWorker] is
 * a thin adapter over this, which is what lets the whole
 * advance/retry/give-up decision tree be unit-tested against a real HTTP server
 * rather than only ever exercised on a device.
 */
class ProofSyncEngine(
    private val repository: EventRepository,
    private val syncStateStore: SyncStateStore,
    private val uploader: ProofUploader,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * @param synced events that reached [SyncStage.COMPLETE] during this pass
     * @param retryable true when at least one event failed for a reason that may
     *        pass — the caller should ask to be run again later
     * @param failed events abandoned permanently, or past the attempt limit
     */
    data class Outcome(
        val synced: List<String> = emptyList(),
        val alreadyComplete: Int = 0,
        val failed: List<String> = emptyList(),
        val retryable: Boolean = false,
    )

    fun syncAll(): Outcome {
        var alreadyComplete = 0
        val synced = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var retryable = false

        // Oldest first, so a long backlog drains in capture order and the history
        // view fills in the direction a user expects.
        for (event in repository.list().sortedBy { it.metadata.timestamp.wallClockMillis }) {
            val state = syncStateStore.get(event.eventId)
            if (state.isComplete) {
                alreadyComplete += 1
                continue
            }
            if (!state.isRetryable) {
                failed.add(event.eventId)
                continue
            }

            when (syncOne(event.eventId, File(event.mediaFilePath), state)) {
                StepOutcome.COMPLETE -> synced.add(event.eventId)
                StepOutcome.RETRY -> retryable = true
                StepOutcome.ABANDONED -> failed.add(event.eventId)
            }
        }

        return Outcome(synced, alreadyComplete, failed, retryable)
    }

    private enum class StepOutcome { COMPLETE, RETRY, ABANDONED }

    private fun syncOne(eventId: String, mediaFile: File, previous: SyncState): StepOutcome {
        var state = previous.copy(
            attempts = previous.attempts + 1,
            lastAttemptWallClockMillis = nowMillis(),
        )

        // Step 1 — the package, unless an earlier attempt already stored it.
        if (state.stage == SyncStage.PENDING) {
            // The exact bytes on disk: what was hashed and signed at capture is
            // what goes on the wire (ADR-0006 §2).
            val bytes = repository.readPackageBytes(eventId)
                ?: return abandon(state, "proof package sidecar is missing or unreadable")

            when (val result = uploader.uploadPackage(bytes)) {
                is ProofUploader.Result.Success -> {
                    state = state.copy(stage = SyncStage.PACKAGE_STORED, lastError = null)
                    // Persisted before attempting the media, so a crash or a lost
                    // connection between the two steps does not re-upload the
                    // package on the next pass.
                    syncStateStore.put(state)
                }
                is ProofUploader.Result.Transient -> return retryOrGiveUp(state, result.reason)
                is ProofUploader.Result.Permanent -> return abandon(state, result.reason)
            }
        }

        // Step 2 — the media, on the same pass whenever possible, so a healthy
        // connection completes an event in a single run.
        return when (val result = uploader.uploadMedia(eventId, mediaFile)) {
            is ProofUploader.Result.Success -> {
                syncStateStore.put(
                    state.copy(
                        stage = SyncStage.COMPLETE,
                        lastError = null,
                        storageRef = result.storageRef ?: state.storageRef,
                    ),
                )
                StepOutcome.COMPLETE
            }
            is ProofUploader.Result.Transient -> retryOrGiveUp(state, result.reason)
            is ProofUploader.Result.Permanent -> abandon(state, result.reason)
        }
    }

    /**
     * Records a retryable failure — unless the event has now been tried too many
     * times, in which case it is abandoned so it cannot retry forever and drain
     * the battery. The capture itself is never deleted; only automatic retrying
     * stops, and the recorded reason tells the user why.
     */
    private fun retryOrGiveUp(state: SyncState, reason: String): StepOutcome {
        if (state.attempts >= SyncConfig.MAX_ATTEMPTS) {
            return abandon(state, "gave up after ${state.attempts} attempts: $reason")
        }
        syncStateStore.put(state.copy(lastError = reason))
        return StepOutcome.RETRY
    }

    private fun abandon(state: SyncState, reason: String): StepOutcome {
        syncStateStore.put(state.copy(stage = SyncStage.FAILED, lastError = reason))
        return StepOutcome.ABANDONED
    }

    /**
     * Clears the FAILED stage so an abandoned event is picked up again. Used by
     * the explicit "retry" action: giving up automatically must not be permanent
     * from the user's point of view.
     */
    fun resetForRetry(eventId: String) {
        val state = syncStateStore.get(eventId)
        if (state.stage == SyncStage.FAILED) {
            syncStateStore.put(
                // Straight back to PENDING, even if the package had already been
                // stored before the media failed: POST /proof is idempotent, so
                // re-sending it costs one small request and answers "already
                // stored". Tracking which half succeeded would be extra state to
                // get wrong for no gain.
                state.copy(stage = SyncStage.PENDING, attempts = 0, lastError = null),
            )
        }
    }
}
