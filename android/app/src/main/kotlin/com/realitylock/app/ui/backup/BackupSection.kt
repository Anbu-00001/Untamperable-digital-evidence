package com.realitylock.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realitylock.app.backup.BackupFailure
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Durable backup",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
        )
        Text(
            "A second, verified copy of every capture in a folder you choose. " +
                "The app's own copy is deleted if Reality Lock is uninstalled or its " +
                "data cleared — this one is not.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )

        if (!state.hasDestination) {
            Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Choose backup folder")
            }
            if (state.grantRefused) {
                StatusLine(
                    text = "Android refused a durable hold on that folder. Pick a " +
                        "different one — a folder the app cannot keep access to " +
                        "would stop backing up without telling you.",
                    color = colors.fail,
                )
            }
            return@Column
        }

        // ---- destination -----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Folder", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                Text(
                    state.destinationName ?: "(chosen folder)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.ink,
                )
            }
            TextButton(onClick = { picker.launch(null) }) { Text("Change") }
        }

        // ---- the count -------------------------------------------------------
        val blocked = state.blockedBy
        if (blocked != null) {
            StatusLine(text = describe(blocked), color = colors.fail)
        } else {
            val allDone = state.totalEvents > 0 && state.backedUp == state.totalEvents
            StatusLine(
                text = when {
                    state.totalEvents == 0 -> "No captures yet."
                    allDone -> "${state.backedUp} of ${state.totalEvents} captures backed up."
                    else -> "${state.backedUp} of ${state.totalEvents} captures backed up."
                },
                color = if (allDone) colors.pass else colors.warn,
            )
        }

        if (state.failed > 0) {
            StatusLine(
                text = "${state.failed} could not be written after repeated attempts. " +
                    "The originals are untouched inside the app.",
                color = colors.fail,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::runBackupNow,
                enabled = !state.running,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.running) "Backing up…" else "Back up now")
            }
            OutlinedButton(onClick = viewModel::forgetDestination) { Text("Forget") }
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
private fun describe(failure: BackupFailure): String = when (failure) {
    BackupFailure.NO_DESTINATION ->
        "No folder chosen yet."
    BackupFailure.DESTINATION_PERMISSION_LOST ->
        "Reality Lock no longer has permission to write to that folder. " +
            "Choose it again to restore access."
    BackupFailure.DESTINATION_UNREACHABLE ->
        "That folder can't be reached — it may have been deleted, or it may be on " +
            "a memory card that isn't in the phone."
    BackupFailure.OUT_OF_SPACE ->
        "The storage holding that folder is full."
    BackupFailure.BUNDLE_UNAVAILABLE ->
        "Some captures could not be packaged for backup."
    BackupFailure.WRITE_FAILED ->
        "Writing to that folder failed."
    BackupFailure.VERIFICATION_FAILED ->
        "A copy was written but did not read back intact, so it was removed rather " +
            "than left there looking like a backup."
    BackupFailure.NAME_CONFLICT ->
        "That folder renamed the file, so the copy could not be recorded reliably. " +
            "An empty folder used only for Reality Lock avoids this."
}
