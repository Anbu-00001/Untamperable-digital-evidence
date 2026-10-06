package com.realitylock.app.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.realitylock.app.certificate.CertificateContent
import com.realitylock.app.certificate.SignatoryBlock
import com.realitylock.app.certificate.StatutoryAnnexureContent
import com.realitylock.app.core.config.CertificateConfig
import java.io.File
import com.realitylock.app.export.EvidenceBundle
import com.realitylock.app.core.config.EvidenceBundleConfig
import com.realitylock.app.core.di.AppContainer
import com.realitylock.app.core.time.ClockCorrelator
import com.realitylock.app.sync.SyncStage
import com.realitylock.app.sync.SyncState
import com.realitylock.app.verify.OfflineProofVerifier
import com.realitylock.app.verify.VerificationClient
import com.realitylock.app.verify.VerificationReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A generated document, held until the user picks where to save it.
 *
 * Carries its own [mimeType] because this now covers three different documents —
 * a PDF certificate, a PDF annexure, and a ZIP evidence bundle. A single
 * hardcoded type would hand the system "save as" dialog a lie about the archive,
 * and the receiving app would be told it had a PDF.
 */
data class PendingCertificate(
    val eventId: String,
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String = CertificateConfig.MIME_TYPE_PDF,
) {
    // ByteArray needs structural equals/hashCode, which data classes do not give it.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PendingCertificate && eventId == other.eventId &&
            fileName == other.fileName && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (31 * eventId.hashCode() + fileName.hashCode()) +
        bytes.contentHashCode()
}

/**
 * The latest verification result for one capture, kept so its trust ring can be
 * drawn without the user pressing anything.
 *
 * [syncStage] is the sync stage the result was produced against. A server verdict
 * made before the media arrived says "incomplete"; once the media is stored that
 * verdict is stale, and the stage change is what triggers a fresh one.
 */
data class CachedVerdict(
    val report: VerificationReport,
    val offline: Boolean,
    val syncStage: SyncStage?,
)

data class ProofsUiState(
    val syncStates: Map<String, SyncState> = emptyMap(),
    /** Latest verdict per event, for the History trust rings. */
    val verdicts: Map<String, CachedVerdict> = emptyMap(),
    /** Events being checked in the background right now. */
    val quietVerifying: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    val syncRequested: Boolean = false,
    /** Event currently being verified, so only its row shows a spinner. */
    val verifyingEventId: String? = null,
    val report: VerificationReport? = null,
    /** Event the displayed [report] belongs to. */
    val reportEventId: String? = null,
    /**
     * True when [report] came from [OfflineProofVerifier] on this phone rather
     * than from the backend. The panel must say so: the two answer different
     * questions, and an offline report can never reach `verified`.
     */
    val reportIsOffline: Boolean = false,
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
class ProofsViewModel(
    private val container: AppContainer,
    /**
     * Where the blocking work goes. Defaults to [Dispatchers.IO], so production
     * behaviour is unchanged; tests substitute a deterministic dispatcher because
     * a hardcoded `Dispatchers.IO` leaves them racing a real thread pool and
     * forces sleeps or polling to observe a result.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val repository = container.eventRepository
    private val syncStateStore = container.syncStateStore

    private val _uiState = MutableStateFlow(ProofsUiState())
    val uiState: StateFlow<ProofsUiState> = _uiState.asStateFlow()

    init {
        refreshSyncStates()
    }

    fun refreshSyncStates() {
        viewModelScope.launch {
            val states = withContext(ioDispatcher) { syncStateStore.all() }
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
            withContext(ioDispatcher) { container.proofSyncEngine.resetForRetry(eventId) }
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
            it.copy(
                verifyingEventId = eventId,
                report = null,
                reportEventId = null,
                reportIsOffline = false,
                verifyError = null,
            )
        }

        viewModelScope.launch {
            val bytes = withContext(ioDispatcher) { repository.readPackageBytes(eventId) }
            if (bytes == null) {
                _uiState.update {
                    it.copy(verifyingEventId = null, verifyError = ERROR_PACKAGE_UNREADABLE)
                }
                return@launch
            }

            val result = withContext(ioDispatcher) { container.verificationClient.verify(bytes) }
            _uiState.update {
                when (result) {
                    is VerificationClient.Result.Ok ->
                        it.copy(
                            verifyingEventId = null,
                            report = result.report,
                            reportEventId = eventId,
                            verdicts = it.verdicts + (eventId to CachedVerdict(
                                result.report, offline = false, syncStage = it.syncStates[eventId]?.stage,
                            )),
                        )
                    is VerificationClient.Result.Unreachable ->
                        it.copy(verifyingEventId = null, verifyError = result.reason)
                }
            }
            // A verification pass also reveals whether the media made it across,
            // so the badges are worth refreshing.
            refreshSyncStates()
        }
    }

    /**
     * Verifies an event entirely on this phone, with no network.
     *
     * Runs [OfflineProofVerifier] over the exact stored package bytes and the
     * device's own copy of the media. It recomputes every hash, the Merkle root,
     * the signature and the attestation chain's internal links — but it cannot
     * anchor that chain to Google's roots, check revocation, or see the
     * server-held RFC 3161 token, so its strongest honest result is `incomplete`.
     * The report carries that in its own limitations, and the panel labels it.
     */
    fun verifyOffline(eventId: String) {
        if (_uiState.value.verifyingEventId != null) return
        _uiState.update {
            it.copy(
                verifyingEventId = eventId,
                report = null,
                reportEventId = null,
                reportIsOffline = false,
                verifyError = null,
            )
        }

        viewModelScope.launch {
            val report = withContext(ioDispatcher) { runOfflineVerifier(eventId) }
            _uiState.update {
                if (report == null) {
                    it.copy(verifyingEventId = null, verifyError = ERROR_PACKAGE_UNREADABLE)
                } else {
                    it.copy(
                        verifyingEventId = null,
                        report = report,
                        reportEventId = eventId,
                        reportIsOffline = true,
                        // A phone-side result never replaces a server verdict on the ring.
                        verdicts = if (it.verdicts[eventId]?.offline == false) {
                            it.verdicts
                        } else {
                            it.verdicts + (eventId to CachedVerdict(report, true, it.syncStates[eventId]?.stage))
                        },
                    )
                }
            }
        }
    }

    /** Blocking; call off the main thread. Null when the stored package is unreadable. */
    private fun runOfflineVerifier(eventId: String): VerificationReport? {
        val bytes = repository.readPackageBytes(eventId) ?: return null
        // A missing media file is not an error here: the verifier reports
        // `mediaHashMatch` as unavailable, which is the true answer.
        val media = repository.findById(eventId)
            ?.let { File(it.mediaFilePath) }
            ?.takeIf { it.isFile }
            ?.let { OfflineProofVerifier.MediaSource.of(it) }
        return OfflineProofVerifier.verify(String(bytes, Charsets.UTF_8), media)
    }

    /**
     * Keeps every History card's trust ring filled in, without any button press.
     *
     * Online, each capture is checked by the backend (the full verdict). Offline —
     * or if the backend cannot be reached — it is checked on this phone instead,
     * and the ring is marked as phone-checked, because that result can never be
     * VERIFIED. The first unreachable answer ends the pass, so a sleeping server
     * costs one wait, not one per capture.
     *
     * A capture is only re-checked when its sync stage has changed since its last
     * result (the media arriving is what turns "incomplete" into something
     * firmer) or when a phone-only result can now be upgraded to a server one.
     */
    fun refreshVerdicts(eventIds: List<String>, online: Boolean) {
        viewModelScope.launch {
            var serverReachable = online
            for (eventId in eventIds) {
                val state = _uiState.value
                val stage = state.syncStates[eventId]?.stage
                val cached = state.verdicts[eventId]
                val current = cached != null && cached.syncStage == stage &&
                    (!serverReachable || !cached.offline)
                if (current || eventId in state.quietVerifying) continue

                _uiState.update { it.copy(quietVerifying = it.quietVerifying + eventId) }
                var result: CachedVerdict? = null
                if (serverReachable) {
                    val bytes = withContext(ioDispatcher) { repository.readPackageBytes(eventId) }
                    if (bytes != null) {
                        when (val answer = withContext(ioDispatcher) { container.verificationClient.verify(bytes) }) {
                            is VerificationClient.Result.Ok ->
                                result = CachedVerdict(answer.report, offline = false, syncStage = stage)
                            is VerificationClient.Result.Unreachable -> serverReachable = false
                        }
                    }
                }
                if (result == null && cached == null) {
                    withContext(ioDispatcher) { runOfflineVerifier(eventId) }?.let {
                        result = CachedVerdict(it, offline = true, syncStage = stage)
                    }
                }
                _uiState.update {
                    it.copy(
                        quietVerifying = it.quietVerifying - eventId,
                        verdicts = result?.let { r -> it.verdicts + (eventId to r) } ?: it.verdicts,
                    )
                }
            }
        }
    }

    fun dismissReport() = _uiState.update {
        it.copy(report = null, reportEventId = null, reportIsOffline = false, verifyError = null)
    }

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
            val outcome = withContext(ioDispatcher) {
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
                        // ...and only a SERVER report. An offline report answers a
                        // smaller question (no root anchoring, no revocation, no
                        // RFC 3161), and the certificate's QR points at the server's
                        // verdict — printing the phone's own check beside it would
                        // let one be read as the other.
                        report = _uiState.value.report?.takeIf {
                            _uiState.value.reportEventId == eventId && !_uiState.value.reportIsOffline
                        },
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

    /**
     * Renders the BSA 2023 s.63 draft annexure and holds it for the save dialog,
     * exactly as [buildCertificate] does for the certificate.
     *
     * Note what is NOT passed in: any report, verdict or check outcome. The
     * annexure is a statutory form about the record's particulars — hash value,
     * algorithm, device, method of production — and a verification verdict is not
     * one of them. Printing "VERIFIED" onto a document a person is about to sign
     * would invite them to adopt this system's conclusion as their own
     * certification, which is the precise confusion research/06 §1.3 warns
     * against.
     *
     * @param labels the device-particulars column headings.
     * @param productionMethod how the record was produced, in order.
     * @param mattersRequiringHumanAttestation what the signatories, not this
     *        system, must attest to. Non-empty by construction.
     * @param signatories blank blocks; never populated by this app.
     */
    fun buildStatutoryAnnexure(
        eventId: String,
        title: String,
        draftNotice: String,
        labels: StatutoryAnnexureContent.DeviceParticularLabels,
        productionMethod: List<String>,
        mattersRequiringHumanAttestation: List<String>,
        signatories: List<SignatoryBlock>,
    ) {
        _uiState.update { it.copy(buildingCertificateFor = eventId, certificateError = null) }

        viewModelScope.launch {
            val outcome = withContext(ioDispatcher) {
                runCatching {
                    val event = repository.findById(eventId)
                        ?: error(ERROR_EVENT_MISSING)
                    val content = StatutoryAnnexureContent.from(
                        event = event,
                        title = title,
                        draftNotice = draftNotice,
                        deviceParticularLabels = labels,
                        productionMethod = productionMethod,
                        mattersRequiringHumanAttestation = mattersRequiringHumanAttestation,
                        signatories = signatories,
                        generatedAtIso = ClockCorrelator.toIso8601Utc(System.currentTimeMillis()),
                    )
                    PendingCertificate(
                        eventId = eventId,
                        bytes = container.statutoryAnnexureRenderer.render(content),
                        // A distinct filename stem, so the two documents cannot be
                        // mistaken for each other in a case file.
                        fileName = CertificateConfig.ANNEXURE_FILENAME_PREFIX +
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

    /**
     * Builds the evidence bundle — the photograph and the signed package — and
     * holds it for the same save dialog the two PDFs use.
     *
     * This is the export that was missing. The certificate and the s.63 annexure
     * are documents *about* a capture; neither contains the capture. Media and
     * package live in app-private storage, so before this there was no way to
     * hand anyone the evidence itself, and uninstalling the app destroyed it.
     * The backend is not a substitute — it is on a free tier with an ephemeral
     * filesystem, and a probe on 2026-08-06 found it holding zero events.
     *
     * Reuses [PendingCertificate] and the existing save flow deliberately: the
     * dialog only ever needed bytes and a filename, so a third document type
     * costs no new UI machinery.
     */
    fun buildEvidenceBundle(eventId: String, exportingAppVersion: String) {
        _uiState.update { it.copy(buildingCertificateFor = eventId, certificateError = null) }

        viewModelScope.launch {
            val outcome = withContext(ioDispatcher) {
                runCatching {
                    val event = repository.findById(eventId)
                        ?: error(ERROR_EVENT_MISSING)
                    // The stored bytes, never a re-serialization: the package was
                    // signed over exactly these bytes, and canonical JSON that
                    // round-trips through a parser can come back byte-different
                    // and silently invalidate the signature it carries.
                    val packageBytes = repository.readPackageBytes(eventId)
                    val mediaBytes = File(event.mediaFilePath)
                        .takeIf { it.isFile }
                        ?.readBytes()

                    val bundle = EvidenceBundle.from(
                        event = event,
                        packageBytes = packageBytes,
                        mediaBytes = mediaBytes,
                        exportingAppVersion = exportingAppVersion,
                        exportedAtIso = ClockCorrelator.toIso8601Utc(System.currentTimeMillis()),
                    )
                    PendingCertificate(
                        eventId = eventId,
                        bytes = container.evidenceBundleExporter.export(bundle),
                        fileName = EvidenceBundleConfig.FILENAME_PREFIX +
                            eventId.take(CertificateConfig.FILENAME_EVENT_ID_CHARS) +
                            EvidenceBundleConfig.FILENAME_EXTENSION,
                        mimeType = EvidenceBundleConfig.MIME_TYPE_ZIP,
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
