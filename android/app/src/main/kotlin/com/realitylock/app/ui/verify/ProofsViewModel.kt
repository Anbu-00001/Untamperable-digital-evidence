package com.realitylock.app.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.realitylock.app.certificate.CertificateContent
import com.realitylock.app.core.config.CertificateConfig
import com.realitylock.app.core.di.AppContainer
import com.realitylock.app.core.time.ClockCorrelator
import com.realitylock.app.sync.SyncState
import com.realitylock.app.verify.VerificationClient
import com.realitylock.app.verify.VerificationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A generated certificate, held until the user picks where to save it. */
data class PendingCertificate(val eventId: String, val bytes: ByteArray, val fileName: String) {
    // ByteArray needs structural equals/hashCode, which data classes do not give it.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PendingCertificate && eventId == other.eventId &&
            fileName == other.fileName && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (31 * eventId.hashCode() + fileName.hashCode()) +
        bytes.contentHashCode()
}

data class ProofsUiState(
    val syncStates: Map<String, SyncState> = emptyMap(),
    val isSyncing: Boolean = false,
    val syncRequested: Boolean = false,
    /** Event currently being verified, so only its row shows a spinner. */
    val verifyingEventId: String? = null,
    val report: VerificationReport? = null,
    /** Event the displayed [report] belongs to. */
    val reportEventId: String? = null,
    val verifyError: String? = null,
    val buildingCertificateFor: String? = null,
    val pendingCertificate: PendingCertificate? = null,
    val certificateError: String? = null,
)

/**
 * Drives the Phase-5 surfaces: sync status, the Authenticity Result, and the PDF
 * certificate.
 *
 * Separate from `CaptureViewModel` because it has a different lifetime and a
 * different job — one owns a camera, this one owns network results. Merging them
 * would have the capture screen holding verification state it never uses.
 *
 * The user-facing wording is supplied by the UI ([verify] takes the framing lines
 * and check labels as parameters) because a ViewModel has no `Context` with which
 * to resolve a string resource, and none of this text may be an inline literal.
 */
class ProofsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.eventRepository
    private val syncStateStore = container.syncStateStore

    private val _uiState = MutableStateFlow(ProofsUiState())
    val uiState: StateFlow<ProofsUiState> = _uiState.asStateFlow()

    init {
        refreshSyncStates()
    }

    fun refreshSyncStates() {
        viewModelScope.launch {
            val states = withContext(Dispatchers.IO) { syncStateStore.all() }
            _uiState.update { it.copy(syncStates = states) }
        }
    }

    /**
     * Asks WorkManager for a sync pass. Returns immediately — the work is
     * constrained on connectivity and may legitimately not run for a while, which
     * is exactly the offline-first behaviour we want and what the UI says.
     */
    fun requestSync() {
        container.requestSync()
        _uiState.update { it.copy(syncRequested = true) }
    }

    fun dismissSyncNotice() = _uiState.update { it.copy(syncRequested = false) }

    /** Clears a FAILED stage so the event is attempted again, then requests a pass. */
    fun retrySync(eventId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.proofSyncEngine.resetForRetry(eventId) }
            refreshSyncStates()
            requestSync()
        }
    }

    /**
     * Verifies an event against the backend.
     *
     * Sends the stored package bytes verbatim; the server uses its own copy of the
     * media if the event has been synced, and otherwise honestly reports the media
     * check as not checkable.
     */
    fun verify(eventId: String) {
        if (_uiState.value.verifyingEventId != null) return
        _uiState.update {
            it.copy(verifyingEventId = eventId, report = null, reportEventId = null, verifyError = null)
        }

        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { repository.readPackageBytes(eventId) }
            if (bytes == null) {
                _uiState.update {
                    it.copy(verifyingEventId = null, verifyError = ERROR_PACKAGE_UNREADABLE)
                }
                return@launch
            }

            val result = withContext(Dispatchers.IO) { container.verificationClient.verify(bytes) }
            _uiState.update {
                when (result) {
                    is VerificationClient.Result.Ok ->
                        it.copy(verifyingEventId = null, report = result.report, reportEventId = eventId)
                    is VerificationClient.Result.Unreachable ->
                        it.copy(verifyingEventId = null, verifyError = result.reason)
                }
            }
            // A verification pass also reveals whether the media made it across,
            // so the badges are worth refreshing.
            refreshSyncStates()
        }
    }

    fun dismissReport() =
        _uiState.update { it.copy(report = null, reportEventId = null, verifyError = null) }

    /**
     * Renders the PDF and holds it until the UI's save dialog returns a
     * destination. Nothing is written to storage without the user choosing where.
     *
     * @param framing the what-this-proves lines; a certificate cannot be built
     *        without them (see [CertificateContent.requireFraming]).
     */
    fun buildCertificate(
        eventId: String,
        title: String,
        verdictLabeller: (VerificationReport.Verdict) -> String,
        notVerifiedLabel: String,
        checksAbsentNotice: String,
        framing: List<String>,
        checkLabeller: (String) -> String,
    ) {
        _uiState.update { it.copy(buildingCertificateFor = eventId, certificateError = null) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val event = repository.findById(eventId)
                        ?: error(ERROR_EVENT_MISSING)
                    val content = CertificateContent.from(
                        event = event,
                        // Whatever report is on screen for THIS event. A report for
                        // a different event must never be printed onto this one.
                        // `CertificateContent.from` derives the verdict label from
                        // this same guarded value, so the guard now covers the
                        // verdict too — it previously covered only the check rows,
                        // while the label came pre-resolved from the composable and
                        // could belong to another event entirely.
                        report = _uiState.value.report
                            ?.takeIf { _uiState.value.reportEventId == eventId },
                        title = title,
                        verdictLabeller = verdictLabeller,
                        notVerifiedLabel = notVerifiedLabel,
                        checksAbsentNotice = checksAbsentNotice,
                        framing = framing,
                        verificationUrl = container.verificationClient.verificationUrl(eventId),
                        generatedAtIso = ClockCorrelator.toIso8601Utc(System.currentTimeMillis()),
                        checkLabeller = checkLabeller,
                    )
                    PendingCertificate(
                        eventId = eventId,
                        bytes = container.certificateRenderer.render(content),
                        fileName = CertificateConfig.FILENAME_PREFIX +
                            eventId.take(CertificateConfig.FILENAME_EVENT_ID_CHARS) +
                            CertificateConfig.FILENAME_EXTENSION,
                    )
                }
            }

            _uiState.update {
                outcome.fold(
                    onSuccess = { pending ->
                        it.copy(buildingCertificateFor = null, pendingCertificate = pending)
                    },
                    onFailure = { error ->
                        it.copy(
                            buildingCertificateFor = null,
                            certificateError = error.message ?: error.javaClass.simpleName,
                        )
                    },
                )
            }
        }
    }

    fun clearPendingCertificate() = _uiState.update { it.copy(pendingCertificate = null) }

    fun reportCertificateError(reason: String) =
        _uiState.update { it.copy(pendingCertificate = null, certificateError = reason) }

    fun dismissCertificateError() = _uiState.update { it.copy(certificateError = null) }

    private companion object {
        const val ERROR_PACKAGE_UNREADABLE = "the stored proof package could not be read"
        const val ERROR_EVENT_MISSING = "the event is no longer stored on this device"
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            ProofsViewModel(container) as T
    }
}
