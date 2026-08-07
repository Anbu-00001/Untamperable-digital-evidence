package com.realitylock.app.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import com.realitylock.app.ui.common.chromeInsets
import com.realitylock.app.ui.common.scrollableBottomInset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.realitylock.app.R
import com.realitylock.app.capture.LocationSource
import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.certificate.SignatoryBlock
import com.realitylock.app.certificate.StatutoryAnnexureContent
import com.realitylock.app.core.config.CertificateConfig
import com.realitylock.app.core.config.EvidenceBundleConfig
import com.realitylock.app.sync.SyncStage
import com.realitylock.app.sync.SyncState
import com.realitylock.app.ui.analyze.AnalyzeScreen
import com.realitylock.app.ui.analyze.AnalyzeViewModel
import com.realitylock.app.ui.diagnostics.DeviceStatusScreen
import com.realitylock.app.ui.evidence.EvidenceViewerScreen
import com.realitylock.app.ui.evidence.EvidenceThumbnail
import com.realitylock.app.ui.verify.AuthenticityResultPanel
import com.realitylock.app.ui.verify.ProofsViewModel
import com.realitylock.app.verify.VerificationReport

/**
 * Capture screen: live preview, a shutter that records a tamper-evident event,
 * and the history of what has been recorded.
 *
 * Permissions are requested in an **itemized, un-bundled** way (camera is
 * required; location is optional and separately explained) rather than as one
 * opaque prompt — the consent obligations in research/06 §3 call for exactly
 * that, and an evidence tool should be explicit about what it records.
 */
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    analyzeViewModel: AnalyzeViewModel,
    proofsViewModel: ProofsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    var hasLocationPermission by remember {
        mutableStateOf(context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION))
    }
    var selectedTab by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasCameraPermission = granted[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasLocationPermission =
            granted[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission
    }

    // Buffer sensor samples only while this screen is on-screen.
    DisposableEffect(Unit) {
        viewModel.onScreenActive()
        onDispose { viewModel.onScreenInactive() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Title and tab row are fixed chrome, so they take the top and both
            // side insets as ordinary padding — there is nothing to scroll under
            // a status bar. The BOTTOM inset is deliberately not taken here: it
            // belongs to whichever scrollable is on screen, which applies it as
            // content padding so the last item can be scrolled clear of the
            // navigation bar. Taking it here would reserve a permanent dead
            // strip and reintroduce the bug. See ui/common/WindowInsetsSupport.
            // `windowInsetsPadding` consumes what it applies, so the tabs below
            // see only the bottom inset remaining and cannot double-count.
            .windowInsetsPadding(chromeInsets),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.tab_capture)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.tab_history, uiState.events.size)) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.tab_analyze)) },
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text(stringResource(R.string.tab_device)) },
            )
        }

        when (selectedTab) {
            0 -> CaptureTab(
                viewModel = viewModel,
                uiState = uiState,
                hasCameraPermission = hasCameraPermission,
                hasLocationPermission = hasLocationPermission,
                lifecycleOwner = lifecycleOwner,
                onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                },
            )

            1 -> HistoryTab(
                events = uiState.events,
                onDelete = viewModel::deleteEvent,
                proofsViewModel = proofsViewModel,
            )

            2 -> AnalyzeScreen(viewModel = analyzeViewModel)

            else -> DeviceStatusScreen()
        }
    }
}

@Composable
private fun CaptureTab(
    viewModel: CaptureViewModel,
    uiState: CaptureUiState,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Applied AFTER verticalScroll, which is what makes it padding on the
            // scrolled CONTENT rather than on the viewport. The distinction is
            // the whole fix: padding the viewport would carve out a dead strip
            // above the navigation bar, while padding the content lets the last
            // item scroll up until it clears the bar and then stop.
            .padding(16.dp)
            .padding(bottom = scrollableBottomInset()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasCameraPermission) {
            PermissionRequestPanel(
                hasLocationPermission = hasLocationPermission,
                onRequestPermissions = onRequestPermissions,
            )
            return@Column
        }

        val previewView = remember { PreviewView(context) }
        LaunchedEffect(hasCameraPermission) {
            runCatching { viewModel.cameraController.bind(lifecycleOwner, previewView) }
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PREVIEW_ASPECT_RATIO),
        )

        // Bystander notice, directly beneath the live preview.
        //
        // research/06 §3.2 recommends "a separate, additional notice/consent
        // affordance aimed at bystanders captured incidentally in frame — e.g. a
        // visible on-screen indicator during capture". Under the DPDP Act 2023 a
        // bystander whose face and location are recorded is plausibly a Data
        // Principal in their own right, and they are the one party to a capture
        // who never agreed to anything and cannot see a consent screen.
        //
        // Placed under the preview rather than over it on purpose: an overlay
        // would sit on the framing the user is composing, and a notice that gets
        // in the way is a notice people learn to switch off. It is always shown
        // — there is no dismiss — because it is addressed to someone other than
        // the person holding the phone.
        Text(
            stringResource(R.string.capture_bystander_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!hasLocationPermission) {
            Text(
                stringResource(R.string.capture_location_denied_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRequestPermissions) {
                Text(stringResource(R.string.capture_grant_location))
            }
        }

        Button(
            onClick = { viewModel.capture(includeLocation = hasLocationPermission) },
            enabled = !uiState.isCapturing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isCapturing) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.capture_in_progress))
            } else {
                Text(stringResource(R.string.capture_action))
            }
        }

        uiState.error?.let { error ->
            // The localized wording lives here, not in the ViewModel; the
            // platform's own message is shown when it has one to offer.
            Text(
                error.detail ?: stringResource(R.string.capture_failed),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = viewModel::dismissError) {
                Text(stringResource(R.string.capture_dismiss_error))
            }
        }

        uiState.lastEvent?.let { event ->
            Text(
                stringResource(R.string.capture_last_capture),
                style = MaterialTheme.typography.titleMedium,
            )
            EventCard(event)
        }
    }
}

/**
 * Explains, per permission, exactly what is recorded and why — the un-bundled
 * consent surface required by research/06 §3.
 */
@Composable
private fun PermissionRequestPanel(
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.permissions_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.permissions_camera_rationale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.permissions_location_rationale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.permissions_motion_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onRequestPermissions) {
            Text(
                stringResource(
                    if (hasLocationPermission) {
                        R.string.permissions_grant_camera
                    } else {
                        R.string.permissions_grant_all
                    },
                ),
            )
        }
    }
}

/**
 * The queue/history view, which in Phase 5 is also where sync state, verification
 * and certificate export live — everything about a stored proof, on the proof.
 */
@Composable
private fun HistoryTab(
    events: List<CapturedEvent>,
    onDelete: (String) -> Unit,
    proofsViewModel: ProofsViewModel,
) {
    val proofsState by proofsViewModel.uiState.collectAsState()

    // Which capture is open full-size, if any. Held by id rather than by the
    // event object so the viewer survives the list being refreshed underneath it.
    var viewingEventId by remember { mutableStateOf<String?>(null) }

    // The app version is stamped into the exported bundle's manifest, so a
    // recipient can tell which build produced the archive. Read here because a
    // ViewModel has no Context.
    val bundleContext = LocalContext.current
    val appVersionLabel = remember(bundleContext) {
        runCatching {
            val pkg = bundleContext.packageManager.getPackageInfo(bundleContext.packageName, 0)
            "${pkg.versionName}"
        }.getOrDefault("unknown")
    }

    // Sync badges are read from disk, so they need refreshing when the tab is
    // shown again — a background pass may have completed in the meantime.
    LaunchedEffect(events.size) { proofsViewModel.refreshSyncStates() }

    // Full-size viewer, drawn over the list. Returning early would unmount the
    // LazyColumn and lose its scroll position on every open and close.
    viewingEventId?.let { openId ->
        events.firstOrNull { it.eventId == openId }?.let { openEvent ->
            EvidenceViewerScreen(
                event = openEvent,
                // Only the verdict already on screen for THIS event — the same
                // guard the certificate uses. A verdict belonging to another
                // capture must never be shown against this photograph.
                verdict = proofsState.report
                    ?.takeIf { proofsState.reportEventId == openId }
                    ?.verdict,
                onClose = { viewingEventId = null },
            )
            return@HistoryTab
        }
        // The event vanished (deleted while open) — close rather than linger.
        viewingEventId = null
    }

    if (events.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val certificateFraming = listOf(
        stringResource(R.string.certificate_framing_1),
        stringResource(R.string.certificate_framing_2),
        stringResource(R.string.certificate_framing_3),
        stringResource(R.string.certificate_framing_4),
    )
    val certificateTitle = stringResource(R.string.certificate_title)

    // Every verdict label is resolved up front, but NOT bound to a verdict here.
    // Resolving `proofsState.report`'s label at this level was the bug: this scope
    // is outside `items(events)`, so one event's verdict was reused for every row,
    // and exporting a certificate for a never-verified event printed the previously
    // verified event's result. Which label applies is now decided per event, from
    // the report the ViewModel has already matched to that event.
    val verdictLabels = VerificationReport.Verdict.entries
        .associateWith { stringResource(it.uiLabelRes()) }
    val notVerifiedLabel = stringResource(R.string.certificate_verdict_not_verified)
    val checksAbsentNotice = stringResource(R.string.certificate_checks_absent)

    // Annexure prose, resolved here for the same reason the certificate's is: it
    // is translatable, user-facing text, and a ViewModel has no Context to
    // resolve resources with.
    val annexureTitle = stringResource(R.string.annexure_title)
    val annexureDraftNotice = stringResource(R.string.annexure_draft_notice)
    val annexureLabels = StatutoryAnnexureContent.DeviceParticularLabels(
        make = stringResource(R.string.annexure_label_make),
        model = stringResource(R.string.annexure_label_model),
        platform = stringResource(R.string.annexure_label_platform),
        installId = stringResource(R.string.annexure_label_install_id),
        software = stringResource(R.string.annexure_label_software),
    )
    val annexureMethod = listOf(
        stringResource(R.string.annexure_method_1),
        stringResource(R.string.annexure_method_2),
        stringResource(R.string.annexure_method_3),
        stringResource(R.string.annexure_method_4),
        stringResource(R.string.annexure_method_5),
    )
    val annexureMatters = listOf(
        stringResource(R.string.annexure_attest_1),
        stringResource(R.string.annexure_attest_2),
        stringResource(R.string.annexure_attest_3),
        stringResource(R.string.annexure_attest_4),
        stringResource(R.string.annexure_attest_5),
    )
    // Two blocks, because s.63(4) requires two signatories. The content class
    // refuses fewer; listing them here rather than defaulting inside the model
    // keeps the legal basis of each one in translatable text.
    val annexureSignatories = listOf(
        SignatoryBlock(
            role = stringResource(R.string.annexure_signatory_custodian_role),
            basis = stringResource(R.string.annexure_signatory_custodian_basis),
        ),
        SignatoryBlock(
            role = stringResource(R.string.annexure_signatory_expert_role),
            basis = stringResource(R.string.annexure_signatory_expert_basis),
        ),
    )

    // The system "save as" dialog. Nothing is written to storage unless the user
    // chooses a destination — no storage permission is involved on any API level.
    val context = LocalContext.current
    // `CreateDocument` fixes its MIME type when the launcher is built, so one
    // launcher cannot serve both a PDF and a ZIP. Two are remembered and the
    // pending document picks between them — telling the picker a ZIP archive is
    // a PDF would misname the file and mislead whatever opens it.
    val writePending: (android.net.Uri?) -> Unit = { uri ->
        val pending = proofsState.pendingCertificate
        if (uri == null || pending == null) {
            proofsViewModel.clearPendingCertificate()
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pending.bytes) }
                    ?: error("could not open the chosen file for writing")
            }.fold(
                onSuccess = { proofsViewModel.clearPendingCertificate() },
                onFailure = { proofsViewModel.reportCertificateError(it.message ?: it.toString()) },
            )
        }
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CertificateConfig.MIME_TYPE_PDF),
        writePending,
    )
    val saveZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EvidenceBundleConfig.MIME_TYPE_ZIP),
        writePending,
    )

    // Launch the picker once a document has been rendered, choosing the launcher
    // that matches what was actually produced.
    LaunchedEffect(proofsState.pendingCertificate) {
        proofsState.pendingCertificate?.let { pending ->
            when (pending.mimeType) {
                EvidenceBundleConfig.MIME_TYPE_ZIP -> saveZipLauncher.launch(pending.fileName)
                else -> savePdfLauncher.launch(pending.fileName)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Was `.padding(16.dp)` on the LazyColumn itself, which shrinks the
        // viewport: the bottom 16dp never scrolled, and once the navigation-bar
        // inset was added on top there was no way to bring the last card out
        // from under the bar. As contentPadding the viewport stays full height
        // and runs behind the bar, so the final card scrolls fully clear.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 16.dp + scrollableBottomInset(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SyncSummaryPanel(
                syncRequested = proofsState.syncRequested,
                onSyncNow = proofsViewModel::requestSync,
                onDismiss = proofsViewModel::dismissSyncNotice,
            )
        }

        proofsState.verifyError?.let { reason ->
            item {
                Text(
                    stringResource(R.string.verify_unreachable, reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        proofsState.certificateError?.let { reason ->
            item {
                Text(
                    stringResource(R.string.certificate_save_failed, reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        items(events, key = { it.eventId }) { event ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EventCard(
                    event = event,
                    onDelete = { onDelete(event.eventId) },
                    syncState = proofsState.syncStates[event.eventId],
                    isVerifying = proofsState.verifyingEventId == event.eventId,
                    isBuildingCertificate = proofsState.buildingCertificateFor == event.eventId,
                    onVerify = { proofsViewModel.verify(event.eventId) },
                    onRetrySync = { proofsViewModel.retrySync(event.eventId) },
                    onExportCertificate = {
                        proofsViewModel.buildCertificate(
                            eventId = event.eventId,
                            title = certificateTitle,
                            verdictLabeller = { verdictLabels.getValue(it) },
                            notVerifiedLabel = notVerifiedLabel,
                            checksAbsentNotice = checksAbsentNotice,
                            framing = certificateFraming,
                            checkLabeller = { it },
                        )
                    },
                    onExportAnnexure = {
                        proofsViewModel.buildStatutoryAnnexure(
                            eventId = event.eventId,
                            title = annexureTitle,
                            draftNotice = annexureDraftNotice,
                            labels = annexureLabels,
                            productionMethod = annexureMethod,
                            mattersRequiringHumanAttestation = annexureMatters,
                            signatories = annexureSignatories,
                        )
                    },
                    onExportBundle = {
                        proofsViewModel.buildEvidenceBundle(
                            eventId = event.eventId,
                            exportingAppVersion = appVersionLabel,
                        )
                    },
                    onOpenViewer = { viewingEventId = event.eventId },
                )

                // The result sits directly beneath the event it describes, so a
                // verdict can never be read against the wrong capture.
                if (proofsState.reportEventId == event.eventId) {
                    proofsState.report?.let { report ->
                        AuthenticityResultPanel(
                            report = report,
                            onClose = proofsViewModel::dismissReport,
                        )
                    }
                }
            }
        }
    }
}

/** Offline-first explanation plus a manual "sync now" trigger. */
@Composable
private fun SyncSummaryPanel(
    syncRequested: Boolean,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.sync_offline_note),
                style = MaterialTheme.typography.bodySmall,
            )
            if (syncRequested) {
                Text(
                    stringResource(R.string.sync_queued),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.capture_dismiss_error))
                }
            } else {
                Button(onClick = onSyncNow) { Text(stringResource(R.string.sync_now)) }
            }
        }
    }
}

/** Verdict label for the certificate, mirroring the on-screen wording. */
@StringRes
private fun VerificationReport.Verdict.uiLabelRes(): Int = when (this) {
    VerificationReport.Verdict.VERIFIED -> R.string.verify_verdict_verified
    VerificationReport.Verdict.FAILED -> R.string.verify_verdict_failed
    VerificationReport.Verdict.INCOMPLETE -> R.string.verify_verdict_incomplete
    VerificationReport.Verdict.INVALID_FORMAT -> R.string.verify_verdict_invalid_format
    VerificationReport.Verdict.UNKNOWN -> R.string.verify_verdict_unknown
}

@Composable
private fun EventCard(
    event: CapturedEvent,
    onDelete: (() -> Unit)? = null,
    syncState: SyncState? = null,
    isVerifying: Boolean = false,
    isBuildingCertificate: Boolean = false,
    onVerify: (() -> Unit)? = null,
    onRetrySync: (() -> Unit)? = null,
    onExportCertificate: (() -> Unit)? = null,
    onExportAnnexure: (() -> Unit)? = null,
    onExportBundle: (() -> Unit)? = null,
    /** Opens the full-size viewer. The card shows a thumbnail regardless. */
    onOpenViewer: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The photograph, at last. Until this was here the card listed a
            // timestamp, a hash and a size — a description of evidence with no
            // way to look at it, on media stored where no other app can reach it.
            // Showing it beside its own metadata is also what makes the record
            // presentable to anyone else: a page of hex is not.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvidenceThumbnail(event = event, onClick = onOpenViewer)
                Text(
                    event.metadata.timestamp.iso8601,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Divider()
            DetailRow(
                stringResource(R.string.event_label_id),
                stringResource(
                    R.string.event_id_truncated,
                    event.eventId.take(EVENT_ID_PREVIEW_LENGTH),
                ),
            )
            DetailRow(
                stringResource(R.string.event_label_size),
                stringResource(R.string.event_size_bytes, event.media.byteLength),
            )

            val location = event.metadata.location
            DetailRow(
                stringResource(R.string.event_label_location),
                if (location == null) {
                    stringResource(R.string.event_value_not_recorded)
                } else {
                    stringResource(
                        R.string.event_location_format,
                        location.latitude,
                        location.longitude,
                        location.accuracyMeters,
                    )
                },
            )
            if (location?.isMock == true) {
                Text(
                    stringResource(R.string.event_mock_location_detected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // A stale fix is kept, not discarded — but it is never presented as
            // if it described the capture instant.
            if (LocationSource.isFixStale(location?.fixAgeMillis)) {
                Text(
                    stringResource(
                        R.string.event_location_stale,
                        (location?.fixAgeMillis ?: 0L) / MILLIS_PER_SECOND,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val motion = event.metadata.motion
            DetailRow(
                stringResource(R.string.event_label_motion),
                if (motion == null) {
                    stringResource(R.string.event_value_not_recorded)
                } else {
                    stringResource(R.string.event_motion_captured)
                },
            )
            val merkle = event.merkle
            DetailRow(
                stringResource(R.string.event_label_hash),
                if (merkle == null) {
                    stringResource(R.string.event_hash_pending)
                } else {
                    stringResource(R.string.event_hash_truncated, merkle.root.take(HASH_PREVIEW_LENGTH))
                },
            )
            val signature = event.signature
            DetailRow(
                stringResource(R.string.event_label_signature),
                when {
                    signature == null -> stringResource(R.string.event_value_not_recorded)
                    signature.attestationCertificateChain != null ->
                        stringResource(R.string.event_signature_attested)
                    else -> stringResource(R.string.event_signature_unattested)
                },
            )

            // ---- Phase 5: where this proof stands on the backend ------------
            syncState?.let { state ->
                DetailRow(
                    stringResource(R.string.sync_label),
                    stringResource(state.stage.labelRes()),
                )
                // The reason is shown, not swallowed: a stalled sync with no
                // explanation is indistinguishable from a broken app.
                state.lastError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Verify stays a primary, full-width action; everything else moves
            // into an overflow menu.
            //
            // This is the approved design's "actions are triaged" rule, and it is
            // also a bug fix. Five TextButtons in one un-wrapping Row exceeded the
            // card width, and Compose resolved that by squeezing the fourth to
            // roughly one character — "Export evidence" rendered as a vertical
            // column of single letters running off the card. A Row cannot hold
            // five labels of this length at any phone width, so the fix is fewer
            // things in the row, not a narrower font.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onVerify?.let {
                    Button(
                        onClick = it,
                        enabled = !isVerifying,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (isVerifying) R.string.verify_running else R.string.verify_action,
                            ),
                        )
                    }
                }

                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        // 48dp keeps the target above the accessibility minimum;
                        // the glyph itself is much smaller than the tappable area.
                        modifier = Modifier.size(48.dp),
                    ) {
                        Text(
                            "\u22EE",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        onExportCertificate?.let { action ->
                            DropdownMenuItem(
                                enabled = !isBuildingCertificate,
                                text = {
                                    Text(
                                        stringResource(
                                            if (isBuildingCertificate) {
                                                R.string.certificate_generating
                                            } else {
                                                R.string.certificate_action
                                            },
                                        ),
                                    )
                                },
                                onClick = { menuOpen = false; action() },
                            )
                        }
                        // A separate item, never a variant of the one above. The
                        // certificate reports what this system computed; the
                        // annexure is a draft form a person completes and signs.
                        // One entry emitting both would blur exactly the line BSA
                        // 2023 s.63 draws (research/06 §1.3).
                        onExportAnnexure?.let { action ->
                            DropdownMenuItem(
                                enabled = !isBuildingCertificate,
                                text = {
                                    Text(
                                        stringResource(
                                            if (isBuildingCertificate) {
                                                R.string.annexure_generating
                                            } else {
                                                R.string.annexure_action
                                            },
                                        ),
                                    )
                                },
                                onClick = { menuOpen = false; action() },
                            )
                        }
                        // The evidence itself, as opposed to the two documents
                        // about it. Distinct wording matters: someone handing a
                        // case file over needs to know which of the three
                        // contains the photograph.
                        onExportBundle?.let { action ->
                            DropdownMenuItem(
                                enabled = !isBuildingCertificate,
                                text = {
                                    Text(
                                        stringResource(
                                            if (isBuildingCertificate) {
                                                R.string.bundle_generating
                                            } else {
                                                R.string.bundle_action
                                            },
                                        ),
                                    )
                                },
                                onClick = { menuOpen = false; action() },
                            )
                        }
                        // Only offered when there is something to retry.
                        if (syncState?.stage == SyncStage.FAILED && onRetrySync != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_retry)) },
                                onClick = { menuOpen = false; onRetrySync() },
                            )
                        }
                        onDelete?.let { action ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.history_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = { menuOpen = false; action() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@StringRes
private fun SyncStage.labelRes(): Int = when (this) {
    SyncStage.PENDING -> R.string.sync_stage_pending
    SyncStage.PACKAGE_STORED -> R.string.sync_stage_package_stored
    SyncStage.COMPLETE -> R.string.sync_stage_complete
    SyncStage.FAILED -> R.string.sync_stage_failed
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private const val PREVIEW_ASPECT_RATIO = 3f / 4f
private const val EVENT_ID_PREVIEW_LENGTH = 8
private const val MILLIS_PER_SECOND = 1_000L
private const val HASH_PREVIEW_LENGTH = 16
