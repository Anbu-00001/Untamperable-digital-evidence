package com.realitylock.app.ui.verify

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.verify.VerificationReport.Outcome
import com.realitylock.app.verify.VerificationReport.Verdict

/**
 * How a status is drawn: glyph, foreground, background, and the label that must
 * always accompany them.
 *
 * The invariant this type exists to enforce is that **status is never colour
 * alone**. Every rendering path here takes a [StatusStyle], and a [StatusStyle]
 * cannot be built without a glyph and a label resource — so there is no way to
 * express "just make it red" in this file. That covers the obvious accessibility
 * case (roughly one in twelve men cannot separate the red from the green) and the
 * less obvious one: a greyscale screenshot of a verdict, which is how these end up
 * in a case file.
 */
@Immutable
data class StatusStyle(
    val glyph: String,
    val fg: Color,
    val bg: Color,
    @param:StringRes val labelRes: Int,
)

/**
 * The four outcome states, each with its own colour — never three.
 *
 * `UNKNOWN` gets the violet `unknown` token rather than being folded into
 * `UNAVAILABLE`'s grey. They are different statements: grey means *we tried and
 * could not*, violet means *the backend answered and this app cannot read the
 * answer*. Only one of those is a defect in the app.
 */
@Composable
fun Outcome.style(): StatusStyle {
    val c = RealityLockThemeTokens.colors
    return when (this) {
        Outcome.PASS -> StatusStyle("✓", c.pass, c.passSoft, R.string.verify_outcome_pass)
        Outcome.FAIL -> StatusStyle("✕", c.fail, c.failSoft, R.string.verify_outcome_fail)
        Outcome.UNAVAILABLE -> StatusStyle(
            glyph = "—",
            fg = c.unavailable,
            bg = c.unavailableSoft,
            labelRes = R.string.verify_outcome_unavailable,
        )
        Outcome.UNKNOWN -> StatusStyle(
            glyph = "?",
            fg = c.unknown,
            bg = c.unknownSoft,
            labelRes = R.string.verify_outcome_unknown,
        )
    }
}

/**
 * Verdict styling. Four of the five states are *not* a pass, and each is wrong in
 * its own way, so each gets its own glyph:
 *
 * - `INCOMPLETE` is amber `◐` — a half-filled circle, deliberately not a tick and
 *   not a cross. Amber rather than grey because the reader has to decide something.
 * - `INVALID_FORMAT` is a red `▲`, not the `✕` of `FAILED`. Both are red because
 *   both mean "do not rely on this", but `FAILED` says a check *proved* alteration
 *   while `INVALID_FORMAT` says nothing inside was ever examined. Sharing a glyph
 *   would merge an accusation with an unread file.
 * - `UNKNOWN` uses the violet unknown token, same as an unrecognised check: the
 *   verifier answered something this app version cannot interpret.
 */
@Composable
fun Verdict.style(): StatusStyle {
    val c = RealityLockThemeTokens.colors
    return when (this) {
        Verdict.VERIFIED -> StatusStyle(
            glyph = "✓",
            fg = c.pass,
            bg = c.passSoft,
            labelRes = R.string.verify_verdict_verified,
        )
        Verdict.FAILED -> StatusStyle(
            glyph = "✕",
            fg = c.fail,
            bg = c.failSoft,
            labelRes = R.string.verify_verdict_failed,
        )
        Verdict.INCOMPLETE -> StatusStyle(
            glyph = "◐",
            fg = c.warn,
            bg = c.warnSoft,
            labelRes = R.string.verify_verdict_incomplete,
        )
        Verdict.INVALID_FORMAT -> StatusStyle(
            glyph = "▲",
            fg = c.fail,
            bg = c.failSoft,
            labelRes = R.string.verify_verdict_invalid_format,
        )
        Verdict.UNKNOWN -> StatusStyle(
            glyph = "?",
            fg = c.unknown,
            bg = c.unknownSoft,
            labelRes = R.string.verify_verdict_unknown,
        )
    }
}

/**
 * The extra sentence a non-pass verdict needs beyond its stock body text.
 *
 * `INCOMPLETE` in particular has to say outright that it is not a pass: it is the
 * verdict most likely to be screenshotted as though it were one, because nothing
 * failed. Returns null for `VERIFIED`, which needs no caveat here — the always-on
 * limitations block already supplies its ceiling.
 */
fun Verdict.caution(): String? = when (this) {
    Verdict.VERIFIED -> null
    Verdict.FAILED -> "A check proved that this package does not hold together."
    Verdict.INCOMPLETE ->
        "This is NOT a pass. Nothing failed, but a decisive check did not run, so " +
            "this package is unproven either way."
    Verdict.INVALID_FORMAT ->
        "The contents were never checked. This does not say the media was altered — " +
            "it says the document could not be read as a proof package at all."
    Verdict.UNKNOWN ->
        "The verifier returned a verdict this app version cannot interpret. Treat it " +
            "as unproven rather than as either outcome."
}

/**
 * The glyph, sized and aligned so rows line up on their labels.
 *
 * Kept out of the accessibility tree is *not* what happens here on purpose: it is
 * always adjacent to its own text label, so a screen reader announcing the glyph
 * and then the word is redundant but never misleading.
 */
@Composable
fun StatusGlyph(style: StatusStyle, modifier: Modifier = Modifier) {
    Text(
        text = style.glyph,
        color = style.fg,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier.width(18.dp),
    )
}

/**
 * A status pill: glyph, word, and — when supplied — a literal fraction.
 *
 * The [trailing] slot is how rule "a group is never a single word" is met. Colour
 * answers *what is the worst thing in here*; the fraction answers *how much of it
 * passed*. "5/6" next to a red FAIL is a far more honest headline than either half
 * alone, and it is the difference between "attestation is broken" and "one of six
 * attestation checks failed".
 */
@Composable
fun StatusChip(style: StatusStyle, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(style.bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .defaultMinSize(minHeight = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = style.glyph,
            color = style.fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(style.labelRes),
            color = style.fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = style.fg,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
