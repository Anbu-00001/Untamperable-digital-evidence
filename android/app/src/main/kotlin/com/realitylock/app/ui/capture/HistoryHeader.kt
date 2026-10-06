package com.realitylock.app.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.ui.components.BrandMark
import com.realitylock.app.ui.components.GlassCard
import com.realitylock.app.ui.components.GradientButton
import com.realitylock.app.ui.components.NoticeChip
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.components.StatTile
import com.realitylock.app.ui.theme.RealityLockThemeTokens

/** Four counters across the top of History: how many captures, synced, verified, in alert. */
@Composable
fun HistoryStats(captures: Int, synced: Int, verified: Int, alerts: Int, modifier: Modifier = Modifier) {
    val c = RealityLockThemeTokens.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(RlIcons.Photo, captures, stringResource(R.string.stat_captures), c.primary, Modifier.weight(1f))
        StatTile(RlIcons.CloudDone, synced, stringResource(R.string.stat_synced), c.info, Modifier.weight(1f))
        StatTile(RlIcons.ShieldGood, verified, stringResource(R.string.stat_verified), c.pass, Modifier.weight(1f))
        StatTile(
            RlIcons.Warn, alerts, stringResource(R.string.stat_alerts),
            if (alerts > 0) c.fail else c.unavailable, Modifier.weight(1f),
        )
    }
}

/**
 * Sync at a glance: how many captures are waiting, a button to push them now,
 * and the offline-first promise as a one-line chip (its full wording one tap away).
 */
@Composable
fun SyncStrip(waiting: Int, syncRequested: Boolean, onSyncNow: () -> Unit, modifier: Modifier = Modifier) {
    val c = RealityLockThemeTokens.colors
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (waiting == 0) RlIcons.CloudDone else RlIcons.CloudUp,
                contentDescription = null,
                tint = if (waiting == 0) c.pass else c.warn,
                modifier = Modifier.size(28.dp),
            )
            Text(
                if (waiting == 0) stringResource(R.string.sync_all_done) else stringResource(R.string.sync_waiting, waiting),
                style = MaterialTheme.typography.titleSmall,
                color = c.ink,
                modifier = Modifier.weight(1f),
            )
            if (waiting > 0) {
                GradientButton(
                    text = stringResource(if (syncRequested) R.string.sync_queued_short else R.string.sync_now),
                    icon = RlIcons.Syncing,
                    onClick = onSyncNow,
                    enabled = !syncRequested,
                )
            }
        }
        NoticeChip(
            icon = RlIcons.Phone,
            short = stringResource(R.string.sync_saved_first_short),
            full = stringResource(R.string.sync_offline_note),
            tint = c.info,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** Shown before the first capture exists. */
@Composable
fun HistoryEmpty(modifier: Modifier = Modifier) {
    val c = RealityLockThemeTokens.colors
    Column(
        modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandMark(size = 96.dp)
        Text(
            stringResource(R.string.history_empty),
            style = MaterialTheme.typography.titleMedium,
            color = c.ink,
        )
        Text(
            stringResource(R.string.history_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkMuted,
        )
    }
}
