package com.realitylock.app.ui.capture

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.realitylock.app.ui.backup.BackupViewModel
import com.realitylock.app.ui.common.LocalBottomInsetHandled
import com.realitylock.app.ui.common.chromeInsets
import com.realitylock.app.ui.common.scrollableBottomInset
import com.realitylock.app.ui.components.AppHeader
import com.realitylock.app.ui.components.BrandMark
import com.realitylock.app.ui.components.ChipFlow
import com.realitylock.app.ui.components.CyberNavBar
import com.realitylock.app.ui.components.GradientButton
import com.realitylock.app.ui.components.InfoChip
import com.realitylock.app.ui.components.NavDestination
import com.realitylock.app.ui.components.NoticeChip
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.components.brandBrush
import com.realitylock.app.ui.components.rememberIsOnline
import com.realitylock.app.ui.components.rememberPulse
import com.realitylock.app.ui.diagnostics.DeviceStatusScreen
import com.realitylock.app.ui.evidence.EvidenceThumbnail
import com.realitylock.app.ui.evidence.EvidenceViewerScreen
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.ui.verify.AuthenticityResultPanel
import com.realitylock.app.ui.verify.ProofExplorerScreen
import com.realitylock.app.ui.verify.ProofsViewModel
import com.realitylock.app.verify.VerificationReport
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    backupViewModel: BackupViewModel,
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
        // Header: logo + two live facts (network, queued captures).
        val online by rememberIsOnline()
        val shellProofs by proofsViewModel.uiState.collectAsState()
        val queued = uiState.events.count {
            shellProofs.syncStates[it.eventId]?.stage != SyncStage.COMPLETE
        }
        AppHeader(online = online, queued = queued)

        // Sync runs in the background, and its progress lives on disk. While anything
        // is still waiting, re-read it every couple of seconds so the counters, the
        // chips and the trust rings catch up by themselves instead of waiting for
        // the user to switch tabs.
        LaunchedEffect(queued > 0) {
            while (queued > 0) {
                delay(SYNC_POLL_MILLIS)
                proofsViewModel.refreshSyncStates()
            }
        }

        // Tab content. The bar below owns the bottom system inset, so lists in
        // here are told not to pad for it a second time.
        Box(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(LocalBottomInsetHandled provides true) {
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

                    else -> DeviceStatusScreen(backupViewModel = backupViewModel)
                }
            }
        }

        CyberNavBar(
            destinations = listOf(
                NavDestination(RlIcons.Capture, stringResource(R.string.tab_capture)),
                NavDestination(RlIcons.History, stringResource(R.string.tab_history)),
                NavDestination(RlIcons.Analyze, stringResource(R.string.tab_analyze)),
                NavDestination(RlIcons.Device, stringResource(R.string.tab_device)),
            ),
            selected = selectedTab,
            onSelect = { selectedTab = it },
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            ),
        )
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
    val c = RealityLockThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Applied AFTER verticalScroll, which is what makes it padding on the
            // scrolled CONTENT rather than on the viewport (see WindowInsetsSupport).
            // Zero under the floating nav bar, which pads for the inset itself.
            .padding(horizontal = 16.dp)
            .padding(bottom = scrollableBottomInset() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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

        // Live instruments for the viewfinder HUD. They run only while this tab is
        // composed, and nothing they read is ever saved or signed.
        val radar = rememberRadarState(locationGranted = hasLocationPermission)

        // Shutter flash over the preview, fired on press. Purely a signal that the
        // button registered — it asserts nothing about the capture's outcome.
        val flash = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current

        // The seal overlay shows while a capture is really running, then lingers
        // briefly once it has finished so the per-stage timings can be read.
        var showSeal by remember { mutableStateOf(false) }
        LaunchedEffect(uiState.isCapturing, uiState.sealTotalMillis) {
            when {
                uiState.isCapturing -> showSeal = true
                uiState.sealTotalMillis != null && showSeal -> {
                    delay(SEAL_LINGER_MILLIS)
                    showSeal = false
                }
                else -> showSeal = false
            }
        }

        // Tap the radar or the satellite count to read what they mean; it closes
        // itself after a few seconds so it never sits over a shot.
        var showSkyLegend by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(showSkyLegend) {
            if (showSkyLegend) {
                delay(SKY_LEGEND_MILLIS)
                showSkyLegend = false
            }
        }
        val skyLegendLabel = stringResource(R.string.sky_legend_action)

        val viewfinderShape = RoundedCornerShape(26.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PREVIEW_ASPECT_RATIO)
                .clip(viewfinderShape)
                .border(BorderStroke(1.5.dp, brandBrush()), viewfinderShape),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = flash.value }
                    .background(Color.White),
            )
            ViewfinderBrackets(color = c.primary.copy(alpha = 0.85f))

            // ---- HUD: live readouts, top row ---------------------------------
            val pulse by rememberPulse(900)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 22.dp, top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A pulsing dot and the word LIVE: this is an instrument, not a record.
                HudChip(RlIcons.Bolt, stringResource(R.string.hud_live), c.fail.copy(alpha = 0.6f + 0.4f * pulse))
                val accuracy = radar.accuracyMeters
                HudChip(
                    icon = if (hasLocationPermission) RlIcons.GpsFix else RlIcons.GpsOff,
                    text = when {
                        !hasLocationPermission -> stringResource(R.string.hud_gps_off)
                        accuracy == null -> stringResource(R.string.hud_gps_searching)
                        else -> "±${accuracy.roundToInt()} m"
                    },
                    tint = when {
                        !hasLocationPermission -> c.unavailable
                        accuracy == null -> c.warn
                        accuracy <= GOOD_ACCURACY_METERS -> c.pass
                        else -> c.warn
                    },
                )
            }
            if (hasLocationPermission) {
                HudChip(
                    icon = RlIcons.Satellite,
                    text = "${radar.usedCount}/${radar.satellites.size}",
                    tint = if (radar.usedCount > 0) c.pass else c.inkMuted,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 22.dp, top = 22.dp)
                        .semantics { contentDescription = skyLegendLabel },
                    onClick = { showSkyLegend = !showSkyLegend },
                )
            }

            // ---- HUD: sky plot + tilt bubble, bottom corners -----------------
            SkyRadar(
                state = radar,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 22.dp, bottom = 22.dp)
                    .clip(CircleShape)
                    .clickable { showSkyLegend = !showSkyLegend }
                    .semantics { contentDescription = skyLegendLabel },
            )
            if (radar.hasMotion) {
                HudChip(
                    icon = RlIcons.Steady,
                    text = stringResource(if (radar.steady) R.string.hud_steady else R.string.hud_moving),
                    tint = if (radar.steady) c.primary else c.warn,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = 22.dp),
                )
            }

            // Fully qualified: inside this Box an implicit ColumnScope from the
            // enclosing Column also matches, and Kotlin refuses the ambiguity.
            androidx.compose.animation.AnimatedVisibility(
                visible = showSkyLegend && !showSeal,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
            ) {
                SkyLegend(state = radar, onDismiss = { showSkyLegend = false })
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showSeal,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(420)),
            ) {
                SealOverlay(stages = uiState.stages, totalMillis = uiState.sealTotalMillis)
            }
        }

        // The confirming buzz waits for the RECORD, not the press: it fires only
        // when a new signed event actually exists, so the phone never "confirms" a
        // capture that then failed to hash, sign or persist.
        val recordedId = uiState.lastEvent?.eventId
        var confirmedId by rememberSaveable { mutableStateOf(recordedId) }
        LaunchedEffect(recordedId) {
            if (recordedId != null && recordedId != confirmedId) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                confirmedId = recordedId
            }
        }

        // ---- Shutter row: last capture, the shutter, and the live notice ------
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ShutterButton(
                busy = uiState.isCapturing,
                enabled = true,
                contentDescription = stringResource(R.string.capture_action),
                onClick = {
                    scope.launch {
                        flash.snapTo(SHUTTER_FLASH_ALPHA)
                        flash.animateTo(0f, tween(SHUTTER_FLASH_MILLIS))
                    }
                    viewModel.capture(includeLocation = hasLocationPermission)
                },
            )
            uiState.lastEvent?.let { last ->
                EvidenceThumbnail(
                    event = last,
                    size = 56.dp,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }

        // The legal notice, shortened to an icon and a few words; the full
        // sentence — including that the HUD is a live readout, not evidence — is
        // one tap away.
        NoticeChip(
            icon = RlIcons.Privacy,
            short = stringResource(R.string.capture_notice_short),
            full = stringResource(R.string.capture_notice_full),
            tint = c.info,
        )

        if (!hasLocationPermission) {
            NoticeChip(
                icon = RlIcons.GpsOff,
                short = stringResource(R.string.capture_location_denied_short),
                full = stringResource(R.string.capture_location_denied_warning),
                tint = c.warn,
            )
            TextButton(onClick = onRequestPermissions) {
                Text(stringResource(R.string.capture_grant_location))
            }
        }

        uiState.error?.let { error ->
            // The localized wording lives here, not in the ViewModel; the
            // platform's own message is shown when it has one to offer.
            NoticeChip(
                icon = RlIcons.Fail,
                short = stringResource(R.string.capture_failed),
                full = error.detail,
                tint = c.fail,
                startExpanded = true,
            )
            TextButton(onClick = viewModel::dismissError) {
                Text(stringResource(R.string.capture_dismiss_error))
            }
        }

        // What was just recorded, as chips: sealed, which hash, where.
        uiState.lastEvent?.let { event ->
            ChipFlow {
                InfoChip(RlIcons.ShieldGood, stringResource(R.string.capture_sealed), c.pass)
                InfoChip(RlIcons.Hash, event.merkle?.root?.take(HASH_CHIP_LENGTH) ?: "—", c.primary)
                val location = event.metadata.location
                InfoChip(
                    if (location == null) RlIcons.GpsOff else RlIcons.Location,
                    if (location == null) {
                        stringResource(R.string.capture_no_gps)
                    } else {
                        "±${location.accuracyMeters.roundToInt()} m"
                    },
                    if (location == null) c.unavailable else c.info,
                )
            }
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
    val c = RealityLockThemeTokens.colors
    Column(
        modifier = Modifier.padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandMark(size = 84.dp)
        Text(
            stringResource(R.string.permissions_title),
            style = MaterialTheme.typography.titleLarge,
            color = c.ink,
        )
        // One chip per permission: what it is, and whether it is required. The
        // un-bundled, itemised wording the consent rules call for (research/06 §3)
        // is unchanged — it is one tap away on each chip.
        NoticeChip(
            RlIcons.Capture,
            stringResource(R.string.permissions_camera_short),
            stringResource(R.string.permissions_camera_rationale),
            c.primary,
        )
        NoticeChip(
            RlIcons.Location,
            stringResource(R.string.permissions_location_short),
            stringResource(R.string.permissions_location_rationale),
            c.info,
        )
        NoticeChip(
            RlIcons.Motion,
            stringResource(R.string.permissions_motion_short),
            stringResource(R.string.permissions_motion_note),
            c.unavailable,
        )
        GradientButton(
            text = stringResource(
                if (hasLocationPermission) R.string.permissions_grant_camera else R.string.permissions_grant_all,
            ),
            icon = RlIcons.Lock,
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth(),
        )
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

    // The Merkle explorer, drawn over the list like the full-size viewer.
    var exploringEventId by remember { mutableStateOf<String?>(null) }

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

    exploringEventId?.let { openId ->
        events.firstOrNull { it.eventId == openId }?.let { openEvent ->
            ProofExplorerScreen(event = openEvent, onClose = { exploringEventId = null })
            return@HistoryTab
        }
        exploringEventId = null
    }

    if (events.isEmpty()) {
        HistoryEmpty()
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

    // Keep every card's trust ring filled in. Re-runs when a capture's sync stage
    // changes (the media arriving firms up an "incomplete") or the network does.
    val online by rememberIsOnline()
    val stageKey = events.joinToString { "${it.eventId}:${proofsState.syncStates[it.eventId]?.stage}" }
    LaunchedEffect(stageKey, online) {
        proofsViewModel.refreshVerdicts(events.take(MAX_AUTO_VERIFY).map { it.eventId }, online)
    }
    LaunchedEffect(proofsState.syncRequested) {
        if (proofsState.syncRequested) {
            delay(SYNC_NOTICE_MILLIS)
            proofsViewModel.dismissSyncNotice()
        }
    }

    val colors = RealityLockThemeTokens.colors
    val listState = rememberLazyListState()

    // A verdict opens directly beneath its card, which can be below the fold. Bring
    // that card to the top so tapping Verify visibly does something and the result
    // is read against the capture it describes. Keyed on the event, not the report,
    // so closing the panel or a quiet background refresh never moves the list.
    val resultEventId = proofsState.reportEventId.takeIf { proofsState.report != null }
    val headerItems = 2 + (if (proofsState.verifyError != null) 1 else 0) +
        (if (proofsState.certificateError != null) 1 else 0)
    LaunchedEffect(resultEventId) {
        val index = events.indexOfFirst { it.eventId == resultEventId }
        if (resultEventId != null && index >= 0) {
            listState.animateScrollToItem(headerItems + index)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // As contentPadding (not padding on the list) the viewport stays full
        // height and the last card scrolls fully clear of the bar below.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp + scrollableBottomInset(),
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HistoryStats(
                captures = events.size,
                synced = events.count { proofsState.syncStates[it.eventId]?.stage == SyncStage.COMPLETE },
                verified = events.count { proofsState.verdicts[it.eventId]?.report?.verdict == VerificationReport.Verdict.VERIFIED },
                alerts = events.count {
                    when (proofsState.verdicts[it.eventId]?.report?.verdict) {
                        VerificationReport.Verdict.FAILED, VerificationReport.Verdict.INVALID_FORMAT -> true
                        else -> false
                    }
                },
            )
        }

        item {
            SyncStrip(
                waiting = events.count { proofsState.syncStates[it.eventId]?.stage != SyncStage.COMPLETE },
                syncRequested = proofsState.syncRequested,
                onSyncNow = proofsViewModel::requestSync,
            )
        }

        proofsState.verifyError?.let { reason ->
            item {
                NoticeChip(
                    RlIcons.CloudOff,
                    stringResource(R.string.chip_verifier_unreachable),
                    stringResource(R.string.verify_unreachable, reason),
                    colors.warn,
                )
            }
        }

        proofsState.certificateError?.let { reason ->
            item {
                NoticeChip(
                    RlIcons.Fail,
                    stringResource(R.string.chip_save_failed),
                    stringResource(R.string.certificate_save_failed, reason),
                    colors.fail,
                )
            }
        }

        items(events, key = { it.eventId }) { event ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CaptureCard(
                    event = event,
                    syncState = proofsState.syncStates[event.eventId],
                    verdict = proofsState.verdicts[event.eventId],
                    ringWorking = event.eventId in proofsState.quietVerifying,
                    isVerifying = proofsState.verifyingEventId == event.eventId,
                    isBuildingCertificate = proofsState.buildingCertificateFor == event.eventId,
                    onVerify = { proofsViewModel.verify(event.eventId) },
                    onVerifyOffline = { proofsViewModel.verifyOffline(event.eventId) },
                    onExplore = { exploringEventId = event.eventId },
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
                    onDelete = { onDelete(event.eventId) },
                    onOpenViewer = { viewingEventId = event.eventId },
                )

                // The result sits directly beneath the event it describes, so a
                // verdict can never be read against the wrong capture.
                if (proofsState.reportEventId == event.eventId) {
                    proofsState.report?.let { report ->
                        AuthenticityResultPanel(
                            report = report,
                            onClose = proofsViewModel::dismissReport,
                            checkedOnDevice = proofsState.reportIsOffline,
                        )
                    }
                }
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

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private const val PREVIEW_ASPECT_RATIO = 3f / 4f

/** Peak opacity and fade of the shutter flash — a cue that the press registered. */
private const val SHUTTER_FLASH_ALPHA = 0.85f
private const val SHUTTER_FLASH_MILLIS = 220
private const val HASH_CHIP_LENGTH = 10
private const val GOOD_ACCURACY_METERS = 25f
private const val SEAL_LINGER_MILLIS = 2_400L

/** How long the satellite legend stays up if nobody taps it away. */
private const val SKY_LEGEND_MILLIS = 9_000L
private const val MAX_AUTO_VERIFY = 12
private const val SYNC_NOTICE_MILLIS = 4_000L
private const val SYNC_POLL_MILLIS = 2_000L

