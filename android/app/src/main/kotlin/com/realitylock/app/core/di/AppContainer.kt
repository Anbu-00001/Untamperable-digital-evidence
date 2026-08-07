package com.realitylock.app.core.di

import android.content.Context
import com.realitylock.app.capture.CameraController
import com.realitylock.app.capture.CaptureCoordinator
import com.realitylock.app.capture.DeviceInfoProvider
import com.realitylock.app.capture.LocationSource
import com.realitylock.app.capture.MediaFileStore
import com.realitylock.app.capture.SensorSnapshotCollector
import com.realitylock.app.forensics.ForensicAnalyzer
import com.realitylock.app.capture.store.EventRepository
import com.realitylock.app.capture.store.FileEventRepository
import com.realitylock.app.certificate.CertificateRenderer
import com.realitylock.app.certificate.StatutoryAnnexureRenderer
import com.realitylock.app.export.EvidenceBundleExporter
import com.realitylock.app.core.config.AppConfig
import com.realitylock.app.core.config.CaptureConfig
import com.realitylock.app.core.config.SyncConfig
import com.realitylock.app.core.device.InstallIdProvider
import com.realitylock.app.core.time.ClockCorrelator
import com.realitylock.app.crypto.EventSigner
import com.realitylock.app.crypto.SigningKeyManager
import com.realitylock.app.core.time.SystemClockSource
import com.realitylock.app.sync.ProofSyncEngine
import com.realitylock.app.sync.ProofUploader
import com.realitylock.app.sync.SyncStateStore
import com.realitylock.app.sync.SyncWorker
import com.realitylock.app.verify.VerificationClient
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Manual dependency container, created once by the Application.
 *
 * Deliberately hand-wired rather than using Hilt: Hilt requires KSP, and KSP was
 * removed from this project because it added minutes to every build
 * (ADR-0003). At this project's size the explicit wiring is small, has zero
 * build cost, and makes the object graph obvious to read.
 *
 * Media and its metadata sidecar share one directory so an event's files sit
 * next to each other (`<eventId>.jpg` + `<eventId>.json`).
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val capturesDir: File = File(appContext.filesDir, CaptureConfig.MEDIA_SUBDIR)

    private val mediaFileStore = MediaFileStore(capturesDir)
    private val installIdProvider = InstallIdProvider(appContext)
    private val deviceInfoProvider = DeviceInfoProvider(installIdProvider)
    private val clockCorrelator = ClockCorrelator(SystemClockSource())
    private val locationSource = LocationSource(appContext)

    /**
     * One signing key per install, created lazily on first capture. Shared so
     * every event is signed by the same attested key.
     */
    private val signingKeyManager = SigningKeyManager()
    private val eventSigner = EventSigner(signingKeyManager)

    /** Shared store of captured events. */
    val eventRepository: EventRepository = FileEventRepository(capturesDir)

    // ---- Phase 5: sync, verification, certificate --------------------------

    /**
     * Mutable sync bookkeeping, in its OWN directory. Keeping it out of
     * [capturesDir] is what makes "the signed package file is never rewritten" a
     * property of the on-disk layout rather than a promise (ADR-0006 §3).
     */
    val syncStateStore = SyncStateStore(File(appContext.filesDir, SyncConfig.SYNC_STATE_SUBDIR))

    /**
     * One OkHttp client for the whole app: it owns the connection and thread pools,
     * and creating one per request would throw that reuse away.
     */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SyncConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SyncConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SyncConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val proofSyncEngine = ProofSyncEngine(
        repository = eventRepository,
        syncStateStore = syncStateStore,
        uploader = ProofUploader(httpClient, AppConfig.backendBaseUrl),
    )

    val verificationClient = VerificationClient(httpClient, AppConfig.backendBaseUrl)

    val certificateRenderer = CertificateRenderer()

    // A distinct renderer, not a mode of the one above: the two documents make
    // opposite claims (see StatutoryAnnexureRenderer's header), and merging them
    // would invite a single PDF that reads as though this system certified
    // something under BSA 2023 s.63 — which it cannot do.
    val statutoryAnnexureRenderer = StatutoryAnnexureRenderer()

    // Exports the evidence ITSELF, as opposed to the two renderers above, which
    // produce documents *about* it. Until this existed the app could hand a
    // reader a certificate and a statutory annexure but never the photograph and
    // signed package they both describe — and app-private storage meant nothing
    // else could reach them either.
    val evidenceBundleExporter = EvidenceBundleExporter()

    /**
     * Requests a background sync pass.
     *
     * Exposed as a lambda so ViewModels can trigger sync without holding a
     * `Context` — the Application context lives here, where it belongs.
     */
    val requestSync: () -> Unit = { SyncWorker.enqueue(appContext) }

    /**
     * Deletes an event and its sync bookkeeping together, so a later capture
     * cannot inherit a stale state file under a recycled id.
     */
    fun deleteEvent(eventId: String): Boolean {
        syncStateStore.delete(eventId)
        return eventRepository.delete(eventId)
    }

    /** Sensor collectors are stateful (start/stop), so each screen gets its own. */
    fun createSensorCollector(): SensorSnapshotCollector = SensorSnapshotCollector(appContext)

    fun createCameraController(): CameraController = CameraController(appContext)

    /**
     * The forensic analyzer is intentionally constructed here in isolation — it
     * gets only a Context, never the coordinator/signer, so an analysed image
     * has no route into the signing pipeline.
     */
    fun createForensicAnalyzer(): ForensicAnalyzer = ForensicAnalyzer(appContext)

    fun createCaptureCoordinator(sensors: SensorSnapshotCollector): CaptureCoordinator =
        CaptureCoordinator(
            clockCorrelator = clockCorrelator,
            sensors = sensors,
            locationSource = locationSource,
            mediaFileStore = mediaFileStore,
            repository = eventRepository,
            deviceInfoProvider = deviceInfoProvider,
            eventSigner = eventSigner,
        )
}
