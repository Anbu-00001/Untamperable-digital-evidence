package com.realitylock.app.sync

import com.realitylock.app.capture.store.EventRepository
import com.realitylock.app.core.config.SyncConfig
import java.io.File

/**
 * Drains the local queue: for every captured event not yet fully stored on the
 * backend, sends the package and then the media.
 *
 * Deliberately free of WorkManager and Android framework types. [SyncWorker] is
 * a thin adapter that calls this, which is what lets the whole retry/advance/
 * give-up decision tree be unit-tested against a real HTTP server instead of
 * only being exercised on a device.
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
     * @param failed events abandoned permanently or past the attempt limit
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

            when (val result = syncOne(event.eventId, File(event.mediaFilePath), state)) {
                is StepOutcome.Complete -> synced.add(event.eventId)
                is StepOutcome.Retry -> retryable = true
                is StepOutcome.Abandoned -> failed.add(event.eventId)
                is StepOutcome.Advanced -> {
                    // The package landed but the media has not yet; ask to be run
                    // again rather than reporting success prematurely.
                    retryable = true
                    result.let { }
                }
            }
        }

        return Outcome(synced, alreadyComplete, failed, retryable)
    }

    private sealed interface StepOutcome {
        object Complete : StepOutcome
        object Retry : StepOutcome
        object Advanced : StepOutcome
        object Abandoned : StepOutcome
    }

    private fun syncOne(eventId: String, mediaFile: File, previous: SyncState): StepOutcome {
        val attempt = previous.copy(
            attempts = previous.attempts + 1,
            lastAttemptWallClockMillis = nowMillis(),
        )

        // Step 1 — the package, unless a previous attempt already stored it.
        if (attempt.stage == SyncStage.PENDING) {
            val bytes = readPackageBytes(eventId)
                ?: return abandon(attempt, "proof package sidecar is unreadable")

            when (val result = uploader.uploadPackage(bytes)) {
                is ProofUploader.Result.Success ->
                    syncStateStore.put(attempt.copy(stage = SyncStage.PACKAGE_STORED, lastError = null))
                is ProofUploader.Result.Transient -> return retryOrGiveUp(attempt, result.reason)
                is ProofUploader.Result.Permanent -> return abandon(attempt, result.reason)
            }
        }

        // Step 2 — the media. Reached on the same pass as step 1 whenever
        // possible, so a healthy connection completes an event in one run.
        val afterPackage = syncStateStore.get(eventId).copy(
            attempts = attempt.attempts,
            lastAttemptWallClockMillis = attempt.lastAttemptWallClockMillis,
        )
        return when (val result = uploader.uploadMedia(eventId, mediaFile)) {
            is ProofUploader.Result.Success -> {
                syncStateStore.put(
                    afterPackage.copy(
                        stage = SyncStage.COMPLETE,
                        lastError = null,
                        storageRef = result.storageRef ?: afterPackage.storageRef,
                    ),
                )
                StepOutcome.Complete
            }
            is ProofUploader.Result.Transient -> retryOrGiveUp(afterPackage, result.reason)
            is ProofUploader.Result.Permanent -> abandon(afterPackage, result.reason)
        }
    }

    /**
     * Records a retryable failure — unless the event has now been tried too many
     * times, in which case it is abandoned so it cannot retry forever. The
     * capture itself is never deleted; only the automatic retrying stops.
     */
    private fun retryOrGiveUp(state: SyncState, reason: String): StepOutcome {
        if (state.attempts >= SyncConfig.MAX_ATTEMPTS) {
            return abandon(state, "gave up after ${state.attempts} attempts: $reason")
        }
        syncStateStore.put(state.copy(lastError = reason))
        return StepOutcome.Retry
    }

    private fun abandon(state: SyncState, reason: String): StepOutcome {
        syncStateStore.put(state.copy(stage = SyncStage.FAILED, lastError = reason))
        return StepOutcome.Abandoned
    }

    /**
     * Reads the stored sidecar verbatim. This is the byte-exactness guarantee in
     * practice: what was hashed and signed at capture is what goes on the wire.
     */
    private fun readPackageBytes(eventId: String): ByteArray? =
        runCatching { packageFileFor(eventId).readBytes() }.getOrNull()

    private fun packageFileFor(eventId: String): File =
        File(packagesDir, eventId + com.realitylock.app.core.config.CaptureConfig.METADATA_EXTENSION_JSON)

    /**
     * Where the sidecars live. Derived from the media path of a stored event
     * rather than injected separately, so it cannot disagree with the repository's
     * own layout.
     */
    private val packagesDir: File
        get() = File(repository.list().firstOrNull()?.mediaFilePath ?: ".").parentFile
            ?: File(".")
}
