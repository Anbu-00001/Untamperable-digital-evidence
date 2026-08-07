package com.realitylock.app.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.realitylock.app.backup.BackupFailure
import com.realitylock.app.backup.BackupPassResult
import com.realitylock.app.backup.BackupStage
import com.realitylock.app.core.di.AppContainer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the durable-backup section: which folder, how many captures have a
 * verified second copy there, and what went wrong if anything did.
 *
 * The counts are recomputed from the state store on every refresh rather than
 * held incrementally. A cached "23 backed up" that drifts from what is actually
 * in the folder would be the same class of lie the whole feature exists to
 * prevent, and the store is a handful of small files.
 */
class BackupViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    data class UiState(
        val destinationName: String? = null,
        val hasDestination: Boolean = false,
        /** Why the destination is unusable, or null when it is fine. */
        val blockedBy: BackupFailure? = null,
        val totalEvents: Int = 0,
        /** Captures with a verified copy **at the current destination**. */
        val backedUp: Int = 0,
        val failed: Int = 0,
        val running: Boolean = false,
        val lastPass: BackupPassResult? = null,
        /** Set when the system refused to grant a durable hold on the folder. */
        val grantRefused: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(ioDispatcher) { readSnapshot() }
            _uiState.update {
                it.copy(
                    destinationName = snapshot.destinationName,
                    hasDestination = snapshot.hasDestination,
                    blockedBy = snapshot.blockedBy,
                    totalEvents = snapshot.totalEvents,
                    backedUp = snapshot.backedUp,
                    failed = snapshot.failed,
                    lastPass = snapshot.lastPass,
                )
            }
        }
    }

    /**
     * Records the folder the user picked in the system chooser.
     *
     * Previous per-event state is cleared, because it describes copies in a
     * folder that is no longer the answer to "where are my backups". Keeping it
     * would also carry over attempt counters, so an event that failed five times
     * against a removed SD card would arrive at the new folder already exhausted.
     */
    fun onDestinationChosen(uri: Uri) {
        viewModelScope.launch {
            val granted = withContext(ioDispatcher) {
                val ok = container.backupDestination.remember(uri)
                if (ok) container.backupStateStore.clearAll()
                ok
            }
            _uiState.update { it.copy(grantRefused = !granted) }
            refresh()
            if (granted) runBackupNow()
        }
    }

    fun forgetDestination() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                container.backupDestination.clear()
                container.backupStateStore.clearAll()
            }
            refresh()
        }
    }

    /** Runs a pass over every stored capture. */
    fun runBackupNow() {
        if (_uiState.value.running) return
        _uiState.update { it.copy(running = true, grantRefused = false) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                container.evidenceBackupEngine.runPass(
                    container.eventRepository.list().map { it.eventId },
                )
            }
            _uiState.update { it.copy(running = false, lastPass = result) }
            refresh()
        }
    }

    private class Snapshot(
        val destinationName: String?,
        val hasDestination: Boolean,
        val blockedBy: BackupFailure?,
        val totalEvents: Int,
        val backedUp: Int,
        val failed: Int,
        val lastPass: BackupPassResult?,
    )

    private fun readSnapshot(): Snapshot {
        val destinationId = container.backupDestination.destinationId
        val states = container.backupStateStore.all()
        return Snapshot(
            destinationName = container.backupDestination.displayName(),
            hasDestination = destinationId != null,
            blockedBy = container.backupDestination.currentFailure(),
            totalEvents = container.eventRepository.list().size,
            // `isBackedUpTo` and not `stage == BACKED_UP`: a verified copy in a
            // folder the user has since replaced is not a backup to the current
            // destination, and must not be counted as one.
            backedUp = states.values.count { it.isBackedUpTo(destinationId) },
            failed = states.values.count { it.stage == BackupStage.FAILED },
            lastPass = container.backupStateStore.lastPass(),
        )
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return BackupViewModel(container) as T
        }
    }
}
