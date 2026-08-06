package com.realitylock.app.ui.verify

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.ui.theme.RealityLockTheme
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.verify.VerificationReport
import com.realitylock.app.verify.VerificationReport.Outcome

/**
 * The "Authenticity Result" surface from Slide 9 — a verdict **and** the per-check
 * breakdown that produced it.
 *
 * The breakdown is not an expandable detail view; it is the substance. A single
 * "Valid / Tampered" badge would hide the difference between "the media was
 * altered" and "the backend has no copy of the media to check", which is the
 * difference between an accusation and an unanswered question.
 *
 * ## What changed in this redesign
 *
 * Thirteen flat rows had become a wall — technically complete, practically skimmed,
 * with the six attestation checks drowning everything else by sheer count. The
 * checks are now folded into four named groups (see [CheckGroupId]), each showing
 * the **worst** outcome it contains next to a literal fraction. Nothing is hidden:
 * a group opens to its individual checks, groups that are not clean open by
 * default, and every check the backend reported is inside exactly one group —
 * including checks this app version has never heard of.
 *
 * The rule that governs every choice below: a summary must never read better than
 * the thing it summarises. A green heading over a failed check would be a lie about
 * evidence, so group state is a max over severity and can only ever over-warn.
 *
 * The limitations block is always rendered, including on a `VERIFIED` result, so
 * the screen can never be screenshotted as proof of more than it establishes.
 */
@Composable
fun AuthenticityResultPanel(
    report: VerificationReport,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The status palette lives behind a CompositionLocal with no default -- reading
    // it outside a provider throws by design. The host currently wraps the app in a
    // bare MaterialTheme, so this panel provides the palette itself, deriving
    // light/dark from the surrounding scheme rather than from the system setting so
    // it can never end up dark inside a light host. Nesting inside a host that does
    // provide RealityLockTheme is a no-op: the same input yields the same palette.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    RealityLockTheme(darkTheme = dark) {
        val colors = RealityLockThemeTokens.colors
        val groups = groupChecks(report.checks)
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
        ) {
            Column(
                // No scroll of its own: this panel is placed inside the history
                // list, and a nested scroll container would fight the parent.
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.verify_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                    )
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 44.dp),
                    ) {
                        Text(stringResource(R.string.verify_close))
                    }
                }

                VerdictBlock(report.verdict)

                report.merkleRoot?.let { root -> MerkleRootLine(root) }

                ChecksSection(groups)

                // Amber, filled, exclamation-marked: seen at a glance, but worded
                // and coloured so it cannot be mistaken for a failure.
                if (report.advisories.isNotEmpty()) {
                    CalloutBlock(
                        titleRes = R.string.verify_advisories_title,
                        lead = "Must be seen. These do not, by themselves, condemn the package.",
                        items = report.advisories,
                        marker = "!",
                        fg = colors.warn,
                        bg = colors.warnSoft,
                    )
                }

                // Deliberately containerless: notes explain outcomes already stated
                // above, so they must not compete visually with the two blocks that
                // carry their own weight.
                if (report.notes.isNotEmpty()) {
                    NotesBlock(report.notes)
                }

                // Never conditional. A passing verdict is exactly when a reader is
                // most likely to over-read it, so the ceiling is stated even when
                // the verifier supplied no list of its own.
                LimitationsBlock(report.limitations)
            }
        }
    }
}

/**
 * Verdict headline: big glyph, coloured word, stock body, and — for every verdict
 * that is not a pass — an explicit sentence saying so in plain words.
 *
 * `INCOMPLETE` is the one this exists for. "Nothing failed" reads as success to
 * almost everyone, so the caution line states outright that it is not a pass.
 */
@Composable
private fun VerdictBlock(verdict: VerificationReport.Verdict) {
    val colors = RealityLockThemeTokens.colors
    val style = verdict.style()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(style.bg)
            .border(1.dp, style.fg.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = style.glyph,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = style.fg,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(style.labelRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = style.fg,
                )
                Text(
                    stringResource(verdict.bodyRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                )
            }
        }
        verdict.caution()?.let { caution ->
            Text(
                text = caution,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = style.fg,
            )
        }
    }
}

/** Platform monospace, so a root can be compared character by character. */
@Composable
private fun MerkleRootLine(root: String) {
    val colors = RealityLockThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Merkle root",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkMuted,
        )
        Text(
            root,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.ink,
        )
    }
}

@Composable
private fun ChecksSection(groups: List<CheckGroup>) {
    val colors = RealityLockThemeTokens.colors
    val total = groups.sumOf { it.total }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                stringResource(R.string.verify_checks_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
            )
            Text(
                "$total in ${groups.size} groups",
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkMuted,
            )
        }
        for (group in groups) {
            GroupCard(group)
        }
    }
}

/**
 * One group: a tappable summary row over its individual checks.
 *
 * The summary carries three things at once, and the design does not allow any two
 * of them to be dropped: the state colour (*the worst thing in here*), the state
 * word (so colour is never load-bearing on its own), and the fraction (*how much of
 * it passed*). "FAIL 5/6" is a materially different statement from "FAIL", and a
 * six-check attestation group is exactly where that difference matters.
 *
 * Groups that are not clean start expanded. A reader should not have to go looking
 * for the bad news.
 */
@Composable
private fun GroupCard(group: CheckGroup) {
    val colors = RealityLockThemeTokens.colors
    val style = group.state.style()
    var expanded by rememberSaveable(group.id) { mutableStateOf(group.state != Outcome.PASS) }
    val outline = if (group.state == Outcome.PASS) colors.border else style.fg.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceAlt)
            .border(1.dp, outline, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Collapse group" else "Expand group") {
                    expanded = !expanded
                }
                // 48dp: comfortably over the 44dp floor once padding is applied.
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(style)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.id.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink,
                )
                Text(
                    group.breakdown,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
            StatusChip(style, trailing = group.fraction)
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    group.id.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                for (check in group.checks) {
                    CheckRow(check)
                }
            }
        }
    }
}

/**
 * One check: glyph, label, outcome word, what the check tests, and — when the
 * outcome is anything other than a pass — what that outcome means.
 *
 * The two sentences are kept separate on purpose. "The stored media hashes to the
 * digest recorded at capture" is true whatever happened; "this check could not be
 * run" is the finding. Merging them is how a UI ends up implying that an unrun
 * check found something.
 */
@Composable
private fun CheckRow(check: VerificationReport.Check) {
    val colors = RealityLockThemeTokens.colors
    val style = check.outcome.style()
    val recognised = check.name in VerificationReport.DISPLAY_ORDER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusGlyph(style, modifier = Modifier.padding(top = 2.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    checkLabel(check.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Text(
                    stringResource(style.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = style.fg,
                    fontWeight = if (check.outcome == Outcome.FAIL) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Medium
                    },
                )
            }
            checkDetail(check.name)?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
            outcomeSentence(check.outcome)?.let { sentence ->
                Text(
                    sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = style.fg,
                )
            }
            // A check name this app version does not know is shown verbatim as
            // well as prettified: the raw key is what a reader would have to grep
            // for in the backend, and prettifying it away would hide the evidence
            // that this app is the out-of-date party.
            if (!recognised) {
                Text(
                    check.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

/** Filled, marked, coloured — used for advisories, which must not be missed. */
@Composable
private fun CalloutBlock(
    @StringRes titleRes: Int,
    lead: String,
    items: List<String>,
    marker: String,
    fg: Color,
    bg: Color,
) {
    val colors = RealityLockThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(marker, style = MaterialTheme.typography.titleSmall, color = fg)
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = fg,
            )
        }
        Text(lead, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        for (item in items) {
            Text(
                "• $item",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink,
            )
        }
    }
}

/** Plain, unboxed prose. Notes explain; they do not warn. */
@Composable
private fun NotesBlock(notes: List<String>) {
    val colors = RealityLockThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.verify_notes_title),
            style = MaterialTheme.typography.titleSmall,
            color = colors.inkMuted,
        )
        for (note in notes) {
            Text(
                "— $note",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink,
            )
        }
    }
}

/**
 * The ceiling on what any verdict here establishes. Rendered unconditionally.
 *
 * When the verifier supplies no limitations, this falls back to a stated floor
 * rather than disappearing — an absent ceiling is precisely how a screenshot of
 * this panel gets over-read.
 */
@Composable
private fun LimitationsBlock(limitations: List<String>) {
    val colors = RealityLockThemeTokens.colors
    val items = limitations.ifEmpty {
        listOf(
            "This establishes only what the checks above state. It does not establish " +
                "that what the camera was pointed at was true.",
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.infoSoft)
            .border(1.dp, colors.info.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.verify_limitations_title),
            style = MaterialTheme.typography.titleSmall,
            color = colors.info,
        )
        for (item in items) {
            Text(
                "• $item",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink,
            )
        }
    }
}

/**
 * Maps a backend check key to its English label.
 *
 * An explicit mapping rather than a `getResources().getIdentifier()` lookup on
 * the key: identifier lookup is discouraged (it defeats resource shrinking and
 * fails silently), and being explicit makes the set of checks the UI knows about
 * greppable.
 *
 * Falls back, in order, to a Kotlin-side label for checks that have shipped ahead
 * of `strings.xml`, then to a prettified form of the key itself. An unrecognised
 * key is never dropped — a newer backend reporting an extra check must still be
 * visible, and [CheckRow] additionally prints the raw key beneath it.
 */
@Composable
fun checkLabel(name: String): String {
    val res = when (name) {
        "schemaValid" -> R.string.check_schemaValid
        "mediaHashMatch" -> R.string.check_mediaHashMatch
        "metadataHashMatch" -> R.string.check_metadataHashMatch
        "merkleRootMatch" -> R.string.check_merkleRootMatch
        "signatureValid" -> R.string.check_signatureValid
        "attestationPresent" -> R.string.check_attestationPresent
        "attestationChainValid" -> R.string.check_attestationChainValid
        "attestationKeyBinding" -> R.string.check_attestationKeyBinding
        "timestampPlausible" -> R.string.check_timestampPlausible
        "locationPlausible" -> R.string.check_locationPlausible
        else -> null
    }
    return res?.let { stringResource(it) }
        ?: fallbackCheckLabel(name)
        ?: humaniseCheckName(name)
}

@StringRes
private fun VerificationReport.Verdict.bodyRes(): Int = when (this) {
    VerificationReport.Verdict.VERIFIED -> R.string.verify_verdict_verified_body
    VerificationReport.Verdict.FAILED -> R.string.verify_verdict_failed_body
    VerificationReport.Verdict.INCOMPLETE -> R.string.verify_verdict_incomplete_body
    VerificationReport.Verdict.INVALID_FORMAT -> R.string.verify_verdict_invalid_format_body
    VerificationReport.Verdict.UNKNOWN -> R.string.verify_verdict_unknown_body
}
