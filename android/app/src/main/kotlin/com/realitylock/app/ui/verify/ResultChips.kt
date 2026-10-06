package com.realitylock.app.ui.verify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.ui.components.NoticeChip
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.ui.verify.NoticeDigest.Glyph
import com.realitylock.app.verify.VerificationReport.Verdict

private fun Glyph.icon() = when (this) {
    Glyph.UNCHANGED -> RlIcons.ShieldGood
    Glyph.HARDWARE -> RlIcons.Key
    Glyph.BOOT_STATE -> RlIcons.Phone
    Glyph.NOT_REAL -> RlIcons.Blocked
    Glyph.LEGAL -> RlIcons.Legal
    Glyph.PHONE_ONLY -> RlIcons.Phone
    Glyph.NO_ROOT -> RlIcons.CloudOff
    Glyph.NO_VERIFIED -> RlIcons.Blocked
    Glyph.NO_ATTESTATION -> RlIcons.Key
    Glyph.LOCATION -> RlIcons.GpsOff
    Glyph.MOCK -> RlIcons.Warn
    Glyph.GENERIC -> RlIcons.Info
}

/**
 * What the verdict does and does not establish, as one icon chip per caveat.
 *
 * Always rendered, even on a pass — an absent ceiling is exactly how a screenshot
 * of a green result gets over-read. The chips are only shorter: tapping one shows
 * the verifier's own sentence unchanged.
 */
@Composable
fun LimitationChips(limitations: List<String>) {
    val c = RealityLockThemeTokens.colors
    // When the verifier supplied none, fall back to a stated floor rather than disappearing.
    val items = limitations.ifEmpty {
        listOf(
            "This establishes only what the checks above state. It does not establish " +
                "that what the camera was pointed at was true.",
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(RlIcons.Info, null, tint = c.info, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.verify_limitations_title),
                style = MaterialTheme.typography.titleSmall,
                color = c.info,
            )
        }
        for (item in items) {
            val digest = NoticeDigest.limitation(item)
            NoticeChip(digest.glyph.icon(), digest.short, item, c.info)
        }
    }
}

/** Findings that must be seen but do not, alone, condemn the package. */
@Composable
fun AdvisoryChips(advisories: List<String>) {
    val c = RealityLockThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(RlIcons.Warn, null, tint = c.warn, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.verify_advisories_title),
                style = MaterialTheme.typography.titleSmall,
                color = c.warn,
            )
        }
        for (item in advisories) {
            val digest = NoticeDigest.advisory(item)
            NoticeChip(digest.glyph.icon(), digest.short, item, c.warn)
        }
    }
}

/** The verifier's explanatory notes, collapsed into one chip. */
@Composable
fun NotesChip(notes: List<String>) {
    NoticeChip(
        icon = RlIcons.Record,
        short = stringResource(R.string.verify_notes_short, notes.size),
        full = notes.joinToString("\n\n") { "— $it" },
        tint = RealityLockThemeTokens.colors.unavailable,
    )
}

/** The Merkle root as a short chip; the full digest is one tap away. */
@Composable
fun RootChip(root: String) {
    NoticeChip(
        icon = RlIcons.Hash,
        short = stringResource(R.string.verify_root_short, root.take(12)),
        full = root,
        tint = RealityLockThemeTokens.colors.primary,
    )
}

/** The verdict's one-line headline; the full body and caution are one tap away. */
@androidx.annotation.StringRes
fun Verdict.shortRes(checkedOnDevice: Boolean): Int = when (this) {
    Verdict.VERIFIED -> R.string.verdict_short_verified
    Verdict.FAILED -> R.string.verdict_short_failed
    Verdict.INCOMPLETE ->
        if (checkedOnDevice) R.string.verdict_short_incomplete_offline else R.string.verdict_short_incomplete
    Verdict.INVALID_FORMAT -> R.string.verdict_short_invalid
    Verdict.UNKNOWN -> R.string.verdict_short_unknown
}

@Composable
fun VerdictHeadline(verdict: Verdict, checkedOnDevice: Boolean, fullBody: String, modifier: Modifier = Modifier) {
    val style = verdict.style()
    val c = RealityLockThemeTokens.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(style.glyph, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = style.fg)
            Column {
                Text(
                    stringResource(style.labelRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = style.fg,
                )
                Text(
                    stringResource(verdict.shortRes(checkedOnDevice)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = c.ink,
                )
            }
        }
        NoticeChip(
            icon = RlIcons.Info,
            short = stringResource(R.string.verify_more_detail),
            full = listOfNotNull(fullBody, verdict.caution()).joinToString("\n\n"),
            tint = style.fg,
        )
    }
}
