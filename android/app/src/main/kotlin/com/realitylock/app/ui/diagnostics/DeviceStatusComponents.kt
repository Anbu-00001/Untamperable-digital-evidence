package com.realitylock.app.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realitylock.app.ui.theme.RealityLockThemeTokens

/**
 * The presentation vocabulary of the Device status tab.
 *
 * ## The distinction this file exists to enforce
 *
 * A diagnostics screen mixes two kinds of line, and the previous version of this
 * screen rendered them identically — `label ......... value` for everything. That
 * flattening is not merely plain-looking, it is **wrong**, and in the same way
 * this project cares about elsewhere: it invites a reader to read a verdict where
 * none was given.
 *
 * - A **check** ([DeviceRow.Check]) has an outcome. "Is there a camera?" can pass
 *   or fail, and colour plus an icon plus a word is the honest way to say which.
 * - A **fact** ([DeviceRow.Fact]) has no outcome. `0.1.0 (1)` is not "passing";
 *   `com.realitylock.app` did not succeed at anything. Putting a green tick beside
 *   a version string manufactures a reassurance the value cannot support, and a
 *   screen full of green ticks trains the reader to stop reading the ones that
 *   mean something.
 *
 * So facts get a deliberately verdict-free treatment: no icon, no status colour,
 * a neutral monospace value chip that reads as a transcript of a value rather
 * than a judgement of it. Checks get the pill.
 *
 * ## Why the same four states as the verifier
 *
 * [DeviceCheckStatus] mirrors `VerificationReport.Outcome` deliberately — same
 * four states, same colour tokens from `RealityLockColors`, same words. A reader
 * who has learned what a grey "unavailable" means on an authenticity report
 * should not have to learn a second dialect one tab over. In particular
 * `UNAVAILABLE` never means "bad": it means the capability is absent or the
 * check could not be run, which (ADR-0006 §5) is not evidence of a defect.
 *
 * Status is always **icon + colour + word**, never colour alone: roughly one man
 * in twelve cannot separate the pass green from the fail red, and a pill that
 * says "fail" beside a cross stays legible in greyscale and to TalkBack.
 */
enum class DeviceCheckStatus {
    /** The capability is present / configured. */
    PASS,

    /** The capability is definitively absent and the pipeline hard-requires it. */
    FAIL,

    /**
     * The capability is absent but has a documented fallback, is not used yet, or
     * the platform would not let us ask. Grey, never red.
     */
    UNAVAILABLE,

    /** The platform answered with something this app version cannot classify. */
    UNKNOWN,
}

// ---------------------------------------------------------------------------
// Strings.
//
// These live here as Kotlin constants rather than in `res/values/strings.xml`
// ON PURPOSE, and only for this reason: strings.xml is being edited by a
// parallel workstream in this same change set, and adding keys to it would
// collide. Every label that already HAS a `device_*` / `common_*` resource is
// still read from resources — see DeviceStatusScreen. Fold these four status
// words and the notes below into strings.xml the next time that file is free;
// they are ordinary user-visible copy and belong there for localisation.
//
// The four status words match the verifier's own outcome vocabulary
// (`verify_outcome_*`) in meaning; the wording is the capability-side phrasing
// of the same four states ("not checkable" reads as a verifier-report term next
// to a hardware feature).
// ---------------------------------------------------------------------------
private const val LABEL_PASS = "pass"
private const val LABEL_FAIL = "fail"
private const val LABEL_UNAVAILABLE = "unavailable"
private const val LABEL_UNKNOWN = "unknown"

internal val DeviceCheckStatus.label: String
    get() = when (this) {
        DeviceCheckStatus.PASS -> LABEL_PASS
        DeviceCheckStatus.FAIL -> LABEL_FAIL
        DeviceCheckStatus.UNAVAILABLE -> LABEL_UNAVAILABLE
        DeviceCheckStatus.UNKNOWN -> LABEL_UNKNOWN
    }

/** One line of the diagnostics report. */
sealed interface DeviceRow {

    /** A capability with an outcome. Renders label + optional value + status pill. */
    data class Check(
        val label: String,
        val status: DeviceCheckStatus,
        /** Raw platform answer worth showing verbatim, e.g. a security tier name. */
        val value: String? = null,
        /** One short sentence of context. Shown only when there is something to say. */
        val note: String? = null,
    ) : DeviceRow

    /** A configuration value. No outcome, no colour, no icon — see the file KDoc. */
    data class Fact(
        val label: String,
        val value: String,
        val note: String? = null,
    ) : DeviceRow

    /** Explanatory prose (scope caveats, probe detail). Carries no status. */
    data class Info(val text: String) : DeviceRow

    /** A check still resolving off the main thread. */
    data class Progress(val text: String) : DeviceRow
}

/**
 * A titled card of rows.
 *
 * The header carries a tally of the section's checks ("4 pass · 1 unavailable")
 * so the shape of a section is readable before any individual row is — and so a
 * section of pure facts visibly has no tally to give, which is the point again.
 */
@Composable
fun DeviceSection(
    title: String,
    rows: List<DeviceRow>,
    modifier: Modifier = Modifier,
) {
    val colors = RealityLockThemeTokens.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            checkTally(rows)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            }
        }

        // A rule before every row, so one sits under the header and none is left
        // dangling at the card's bottom edge.
        rows.forEach { row ->
            HorizontalDivider(color = colors.border.copy(alpha = 0.6f))
            when (row) {
                is DeviceRow.Check -> CheckRow(row)
                is DeviceRow.Fact -> FactRow(row)
                is DeviceRow.Info -> InfoRow(row.text)
                is DeviceRow.Progress -> ProgressRow(row.text)
            }
        }
    }
}

private fun checkTally(rows: List<DeviceRow>): String? {
    val checks = rows.filterIsInstance<DeviceRow.Check>()
    if (checks.isEmpty()) return null
    return DeviceCheckStatus.entries
        .mapNotNull { status ->
            checks.count { it.status == status }
                .takeIf { it > 0 }
                ?.let { "$it ${status.label}" }
        }
        .joinToString("  ·  ")
}

/**
 * `heightIn(min = 44.dp)` on every row: rows are not clickable today, but the
 * value chips are text-selectable (long-press to copy a base URL off a test
 * device), and 44dp is the floor at which a finger can land on one reliably.
 */
@Composable
private fun CheckRow(row: DeviceRow.Check) {
    val colors = RealityLockThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
            )
            row.value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.inkMuted,
                )
            }
            row.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }
        StatusPill(row.status)
    }
}

@Composable
private fun FactRow(row: DeviceRow.Fact) {
    val colors = RealityLockThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            row.label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkMuted,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.neutralSoft),
        ) {
            // A neutral ledger rule, not a status colour. It says "recorded
            // value" where a check's pill would say "outcome".
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(colors.neutral.copy(alpha = 0.45f)),
            )
            // Selectable so a base URL or application id can be long-pressed and
            // copied off a test device without retyping it from a screenshot.
            SelectionContainer {
                Text(
                    row.value,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.ink,
                )
            }
        }
        row.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
    }
}

@Composable
private fun InfoRow(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = RealityLockThemeTokens.colors.inkMuted,
    )
}

@Composable
private fun ProgressRow(text: String) {
    val colors = RealityLockThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = colors.primary,
            strokeWidth = 2.dp,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
    }
}

/**
 * Icon + colour + word, in that order and never fewer than all three.
 *
 * The soft token is the fill and the strong token is the ink, which is how the
 * pair was designed: the soft colours are ~93-95% lightness in light mode and
 * ~30% in dark, so the pill carries its own contrast in both themes without a
 * per-theme special case here.
 */
@Composable
fun StatusPill(status: DeviceCheckStatus, modifier: Modifier = Modifier) {
    val (ink, fill) = status.pillColors()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(fill)
            .border(1.dp, ink.copy(alpha = 0.35f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusGlyph(status, ink, Modifier.size(12.dp))
        Text(
            status.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (status == DeviceCheckStatus.FAIL) {
                FontWeight.Bold
            } else {
                FontWeight.Medium
            },
            color = ink,
        )
    }
}

/** Returns (ink, fill) for a state, straight from the design tokens. */
@Composable
private fun DeviceCheckStatus.pillColors(): Pair<Color, Color> {
    val c = RealityLockThemeTokens.colors
    return when (this) {
        DeviceCheckStatus.PASS -> c.pass to c.passSoft
        DeviceCheckStatus.FAIL -> c.fail to c.failSoft
        DeviceCheckStatus.UNAVAILABLE -> c.unavailable to c.unavailableSoft
        DeviceCheckStatus.UNKNOWN -> c.unknown to c.unknownSoft
    }
}

/**
 * The four status marks, drawn as vector paths.
 *
 * Drawn rather than imported: `material-icons-extended` is a large dependency to
 * add for four shapes, and this project's build file pins its dependency set
 * deliberately. Four `drawLine`/`drawPath` calls need nothing and scale cleanly
 * to any density.
 *
 * Decorative by design — `contentDescription` is absent because the word beside
 * each mark already carries the state for a screen reader, and announcing
 * "tick, pass" would say it twice.
 */
@Composable
private fun StatusGlyph(status: DeviceCheckStatus, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.16f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (status) {
            DeviceCheckStatus.PASS -> drawPath(
                path = Path().apply {
                    moveTo(0.18f * w, 0.54f * h)
                    lineTo(0.40f * w, 0.77f * h)
                    lineTo(0.84f * w, 0.25f * h)
                },
                color = tint,
                style = stroke,
            )

            DeviceCheckStatus.FAIL -> {
                drawLine(
                    color = tint,
                    start = Offset(0.24f * w, 0.24f * h),
                    end = Offset(0.76f * w, 0.76f * h),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(0.76f * w, 0.24f * h),
                    end = Offset(0.24f * w, 0.76f * h),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            // A minus, not a warning triangle: "absent", with no implication of
            // fault. The colour token is the neutral grey for the same reason.
            DeviceCheckStatus.UNAVAILABLE -> drawLine(
                color = tint,
                start = Offset(0.20f * w, 0.50f * h),
                end = Offset(0.80f * w, 0.50f * h),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )

            // A question mark: hook (left -> over the top -> down the right),
            // then the stem, then the dot.
            DeviceCheckStatus.UNKNOWN -> {
                drawPath(
                    path = Path().apply {
                        arcTo(
                            rect = Rect(
                                left = 0.28f * w,
                                top = 0.08f * h,
                                right = 0.74f * w,
                                bottom = 0.54f * h,
                            ),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = 225f,
                            forceMoveTo = true,
                        )
                        lineTo(0.50f * w, 0.68f * h)
                    },
                    color = tint,
                    style = stroke,
                )
                drawCircle(
                    color = tint,
                    radius = strokeWidth * 0.6f,
                    center = Offset(0.50f * w, 0.86f * h),
                )
            }
        }
    }
}
