package com.realitylock.app.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.realitylock.app.RealityLockApplication
import com.realitylock.app.core.config.SyncConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background sync of captured proof packages (research/03 §8).
 *
 * A deliberately thin adapter: all the logic lives in [ProofSyncEngine], so what
 * happens on a flaky connection is decided by code that runs in ordinary unit
 * tests. This class only translates the engine's outcome into WorkManager's
 * success/retry/failure vocabulary.
 *
 * Constrained on `NetworkType.CONNECTED`, which is what makes the offline story
 * work: capture with no connectivity and the request simply waits, then runs by
 * itself once a network appears. The Android 15/16 background-work changes
 * tightened foreground-service and raw `JobScheduler` quotas; a deferrable
 * WorkManager job like this is explicitly unaffected, since WorkManager owns the
 * job lifecycle on the app's behalf.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as RealityLockApplication).container
        val outcome = container.proofSyncEngine.syncAll()

        Log.i(
            TAG,
            "sync pass: synced=${outcome.synced.size} alreadyComplete=${outcome.alreadyComplete} " +
                "failed=${outcome.failed.size} retryable=${outcome.retryable}",
        )

        when {
            // Something may yet succeed: let WorkManager back off and re-run.
            outcome.retryable -> Result.retry()
            // Nothing retryable left. Note this is `success` even when some events
            // were abandoned: the *work* completed and there is nothing further to
            // attempt, and returning failure would only obscure that. The per-event
            // FAILED stage and its reason are what surface in the UI.
            else -> Result.success()
        }
    }

    companion object {
        private const val TAG = "RealityLockSync"

        /**
         * Requests a sync pass. Safe and cheap to call after every capture and on
         * app start.
         *
         * `APPEND_OR_REPLACE` rather than `KEEP`: if a pass is already running when
         * a new capture arrives, KEEP would drop the request and that capture would
         * wait for some later trigger. Appending guarantees another pass follows the
         * current one.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    SyncConfig.BACKOFF_DELAY_SECONDS,
                    SyncConfig.BACKOFF_TIME_UNIT,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                SyncConfig.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
