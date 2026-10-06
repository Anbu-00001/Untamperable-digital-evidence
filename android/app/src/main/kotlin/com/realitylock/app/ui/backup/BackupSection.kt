package com.realitylock.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.backup.BackupFailure
import com.realitylock.app.ui.components.GlassCard
import com.realitylock.app.ui.components.GradientButton
import com.realitylock.app.ui.components.IconBadge
import com.realitylock.app.ui.components.InfoChip
import com.realitylock.app.ui.components.NoticeChip
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.theme.RealityLockThemeTokens

/**
 * The durable-backup control: pick a folder, see how many captures have a
 * verified copy in it, fix it when it breaks.
 *
 * ## Why this is worth a section of its own
 *
 * Everything else the app stores lives under `filesDir`, which is deleted by an
 * uninstall or a "clear data" tap — the exact action someone under pressure to
 * destroy evidence would be told to take. A copy that survives the app has to
 * live in a folder the app does not own, and since scoped storage that means one
 * the user picks explicitly. There is no way to do this silently, so the UI's job
 * is to make the choice once and then be honest about the result forever after.
 *
 * ## The honesty rules it renders
 *
 * "Backed up" counts only captures with a copy **verified at the current
 * folder**. Change the folder and the count drops to zero, because the old copies
 * are not backups *there* — that is the point of the count, not a bug in it.
 *
 * A destination-level problem (folder gone, card removed, permission revoked) is
 * shown as one line about the folder rather than as N failures, because it is one
 * thing for the user to fix and burying it under forty identical per-capture
 * errors is how a fixable problem becomes a permanent one.
 */
@Composable
fun BackupSection(viewModel: BackupViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val colors = RealityLockThemeTokens.colors

    // ACTION_OPEN_DOCUMENT_TREE. One grant on a tree, not one per file: the
    // persisted-permission table is capped per app (512 on API 30+, 128 below),
    // and a grant per capture would march toward that ceiling and then start
    // failing silently.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onDestinationChosen(uri) }

    // Re-read on every entry into composition, i.e. every time the Device tab is
    // opened. Without this the counts are whatever they were when the ViewModel
    // was constructed — which is app start, before any capture exists — so the
    // section sat there reading "No captures yet" with captures on disk. A
    // backup UI that under-reports is the exact failure this feature exists to
    // prevent, so it re-reads rather than trusting a cached number.
    LaunchedEffect(Unit) { viewModel.refresh() }

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(RlIcons.Backup, colors.primary, size = 36.dp)
            Text(
                stringResource(R.string.backup_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NoticeChip(
                icon = RlIcons.Info,
                short = stringResource(R.string.backup_intro_short),
                full = stringResource(R.string.backup_intro),
                tint = colors.info,
            )

            if (!state.hasDestination) {
                GradientButton(
                    text = stringResource(R.string.backup_choose_folder),
                    icon = RlIcons.Folder,
                    onClick = { picker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.grantRefused) {
                    NoticeChip(
                        RlIcons.Warn,
                        stringResource(R.string.backup_problem_short),
                        stringResource(R.string.backup_grant_refused),
                        colors.fail,
                    )
                }
                return@GlassCard
            }

            // ---- destination -------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(RlIcons.Folder, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Text(
                        state.destinationName ?: stringResource(R.string.backup_folder_unnamed),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = colors.ink,
                    )
                }
                TextButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.backup_change)) }
            }

            // ---- the count -----------------------------------------------------
            val blocked = state.blockedBy
            if (blocked != null) {
                NoticeChip(
                    RlIcons.Warn,
                    stringResource(R.string.backup_problem_short),
                    stringResource(describe(blocked)),
                    colors.fail,
                    startExpanded = true,
                )
            } else {
                val allDone = state.totalEvents > 0 && state.backedUp == state.totalEvents
                InfoChip(
                    icon = if (allDone) RlIcons.ShieldGood else RlIcons.Backup,
                    text = if (state.totalEvents == 0) {
                        stringResource(R.string.backup_no_captures)
                    } else {
                        pluralStringResource(
                            R.plurals.backup_progress,
                            state.totalEvents,
                            state.backedUp,
                            state.totalEvents,
                        )
                    },
                    tint = if (allDone) colors.pass else colors.warn,
                )
            }

            if (state.failed > 0) {
                NoticeChip(
                    RlIcons.Fail,
                    stringResource(R.string.backup_problem_short),
                    stringResource(R.string.backup_failed_count, state.failed),
                    colors.fail,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientButton(
                    text = stringResource(if (state.running) R.string.backup_running else R.string.backup_run_now),
                    icon = RlIcons.Backup,
                    onClick = viewModel::runBackupNow,
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = viewModel::forgetDestination) { Text(stringResource(R.string.backup_forget)) }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * Each of these names something the user can act on. A failure they cannot act on
 * is worse than silence, because it teaches them the warning is noise.
 */
@StringRes
private fun describe(failure: BackupFailure): Int = when (failure) {
    BackupFailure.NO_DESTINATION -> R.string.backup_failure_no_destination
    BackupFailure.DESTINATION_PERMISSION_LOST -> R.string.backup_failure_permission_lost
    BackupFailure.DESTINATION_UNREACHABLE -> R.string.backup_failure_unreachable
    BackupFailure.OUT_OF_SPACE -> R.string.backup_failure_out_of_space
    BackupFailure.BUNDLE_UNAVAILABLE -> R.string.backup_failure_bundle_unavailable
    BackupFailure.WRITE_FAILED -> R.string.backup_failure_write_failed
    BackupFailure.VERIFICATION_FAILED -> R.string.backup_failure_verification_failed
    BackupFailure.NAME_CONFLICT -> R.string.backup_failure_name_conflict
}
