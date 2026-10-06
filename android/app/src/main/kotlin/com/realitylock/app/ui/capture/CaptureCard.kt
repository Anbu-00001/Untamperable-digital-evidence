package com.realitylock.app.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.capture.LocationSource
import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.sync.SyncStage
import com.realitylock.app.sync.SyncState
import com.realitylock.app.ui.components.ChipFlow
import com.realitylock.app.ui.components.GlassCard
import com.realitylock.app.ui.components.GradientButton
import com.realitylock.app.ui.components.InfoChip
import com.realitylock.app.ui.components.NoticeChip
import com.realitylock.app.ui.components.PlaceLine
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.evidence.EvidenceThumbnail
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.ui.verify.CachedVerdict
import com.realitylock.app.ui.verify.TrustRing
import com.realitylock.app.verify.VerificationReport.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One capture, as a card you can read at a glance: the photo, when it was taken,
 * a trust ring for its verification, a few icon chips, where it was — and the
 * actions.
 *
 * This replaces a column of "label: value" rows. Everything those rows said is
 * still here; it is just carried by icons, colour and short words, with the long
 * explanations one tap away (see [NoticeChip] and [PlaceLine]).
 */
@Composable
fun CaptureCard(
    event: CapturedEvent,
    syncState: SyncState?,
    verdict: CachedVerdict?,
    ringWorking: Boolean,
    isVerifying: Boolean,
    isBuildingCertificate: Boolean,
    onVerify: () -> Unit,
    onVerifyOffline: () -> Unit,
    onExplore: () -> Unit,
    onRetrySync: () -> Unit,
    onExportCertificate: () -> Unit,
    onExportAnnexure: () -> Unit,
    onExportBundle: () -> Unit,
    onDelete: () -> Unit,
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RealityLockThemeTokens.colors
    val accent: Color? = when (verdict?.report?.verdict) {
        Verdict.VERIFIED -> c.pass
        Verdict.FAILED, Verdict.INVALID_FORMAT -> c.fail
        Verdict.INCOMPLETE -> c.warn
        else -> null
    }

    GlassCard(modifier = modifier.fillMaxWidth(), accent = accent) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvidenceThumbnail(event = event, size = 72.dp, onClick = onOpenViewer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatCaptureTime(event.metadata.timestamp.iso8601),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                )
                ChipFlow {
                    SyncChip(syncState)
                    event.merkle?.let { InfoChip(RlIcons.Hash, it.root.take(8), c.primary) }
                }
            }
            TrustRing(
                report = verdict?.report,
                working = ringWorking || isVerifying,
                phoneChecked = verdict?.offline == true,
                size = 62.dp,
            )
        }

        Column(
            Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val location = event.metadata.location
            if (location != null) {
                PlaceLine(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracyMeters,
                    isMock = location.isMock,
                )
                if (location.isMock) {
                    NoticeChip(
                        RlIcons.Warn,
                        stringResource(R.string.chip_mock_location),
                        stringResource(R.string.event_mock_location_detected),
                        c.fail,
                    )
                }
                // A stale fix is kept, not discarded — but it is never presented as
                // if it described the capture instant.
                if (LocationSource.isFixStale(location.fixAgeMillis)) {
                    NoticeChip(
                        RlIcons.Time,
                        stringResource(R.string.chip_old_fix),
                        stringResource(R.string.event_location_stale, (location.fixAgeMillis ?: 0L) / 1000L),
                        c.warn,
                    )
                }
            }

            ChipFlow {
                if (location == null) InfoChip(RlIcons.GpsOff, stringResource(R.string.chip_no_gps), c.unavailable)
                val signature = event.signature
                when {
                    signature == null -> InfoChip(RlIcons.Lock, stringResource(R.string.chip_unsigned), c.warn)
                    signature.attestationCertificateChain != null ->
                        InfoChip(RlIcons.Key, stringResource(R.string.chip_signed), c.pass)
                    else -> InfoChip(RlIcons.Key, stringResource(R.string.chip_signed), c.unavailable)
                }
                InfoChip(
                    RlIcons.Motion,
                    stringResource(if (event.metadata.motion == null) R.string.chip_no_motion else R.string.chip_motion),
                    if (event.metadata.motion == null) c.unavailable else c.info,
                )
            }

            // The reason is shown, not swallowed: a stalled sync with no
            // explanation is indistinguishable from a broken app. A RETRYABLE
            // failure is explained first (usually a server waking from idle) with
            // the raw reason kept one tap away.
            syncState?.lastError?.let { error ->
                val retrying = syncState.stage != SyncStage.FAILED
                NoticeChip(
                    icon = if (retrying) RlIcons.Syncing else RlIcons.SyncFailed,
                    short = stringResource(if (retrying) R.string.sync_retrying_short else R.string.chip_sync_failed),
                    full = (if (retrying) stringResource(R.string.sync_retrying_explained) + "\n\n" else "") + error,
                    tint = if (retrying) c.warn else c.fail,
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GradientButton(
                text = stringResource(if (isVerifying) R.string.verify_running else R.string.verify_action),
                icon = RlIcons.VerifiedUser,
                onClick = onVerify,
                enabled = !isVerifying,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onExplore, modifier = Modifier.size(52.dp)) {
                Icon(
                    RlIcons.Tree,
                    contentDescription = stringResource(R.string.action_explore),
                    tint = c.primary,
                )
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(52.dp)) {
                    Icon(RlIcons.More, contentDescription = null, tint = c.inkMuted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    CardMenuItem(RlIcons.Phone, stringResource(R.string.verify_offline_action), !isVerifying) {
                        menuOpen = false; onVerifyOffline()
                    }
                    // Separate items, never variants of one: the certificate reports
                    // what this system computed, the annexure is a draft form a person
                    // completes and signs, and the bundle holds the photograph itself
                    // (research/06 §1.3).
                    CardMenuItem(
                        RlIcons.Record,
                        stringResource(if (isBuildingCertificate) R.string.certificate_generating else R.string.certificate_action),
                        !isBuildingCertificate,
                    ) { menuOpen = false; onExportCertificate() }
                    CardMenuItem(
                        RlIcons.Legal,
                        stringResource(if (isBuildingCertificate) R.string.annexure_generating else R.string.annexure_action),
                        !isBuildingCertificate,
                    ) { menuOpen = false; onExportAnnexure() }
                    CardMenuItem(
                        RlIcons.Folder,
                        stringResource(if (isBuildingCertificate) R.string.bundle_generating else R.string.bundle_action),
                        !isBuildingCertificate,
                    ) { menuOpen = false; onExportBundle() }
                    if (syncState?.stage == SyncStage.FAILED) {
                        CardMenuItem(RlIcons.Refresh, stringResource(R.string.sync_retry), true) {
                            menuOpen = false; onRetrySync()
                        }
                    }
                    CardMenuItem(RlIcons.Delete, stringResource(R.string.history_delete), true, destructive = true) {
                        menuOpen = false; onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun CardMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val c = RealityLockThemeTokens.colors
    DropdownMenuItem(
        enabled = enabled,
        leadingIcon = { Icon(icon, null, tint = if (destructive) c.fail else c.primary) },
        text = { Text(text, color = if (destructive) c.fail else c.ink) },
        onClick = onClick,
    )
}

@Composable
private fun SyncChip(state: SyncState?) {
    val c = RealityLockThemeTokens.colors
    when (state?.stage ?: SyncStage.PENDING) {
        SyncStage.COMPLETE -> InfoChip(RlIcons.CloudDone, stringResource(R.string.chip_synced), c.pass)
        SyncStage.PACKAGE_STORED -> InfoChip(RlIcons.CloudUp, stringResource(R.string.chip_uploading), c.info)
        SyncStage.PENDING -> InfoChip(RlIcons.CloudUp, stringResource(R.string.chip_queued), c.warn)
        SyncStage.FAILED -> InfoChip(RlIcons.SyncFailed, stringResource(R.string.chip_sync_failed), c.fail)
    }
}

/** `2026-08-15T06:49:53.119Z` -> `15 Aug · 12:19` in the phone's own time zone. */
internal fun formatCaptureTime(iso8601: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(iso8601))
}.getOrDefault(iso8601)
