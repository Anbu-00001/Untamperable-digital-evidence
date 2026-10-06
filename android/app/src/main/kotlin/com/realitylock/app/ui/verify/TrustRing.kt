package com.realitylock.app.ui.verify

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.verify.VerificationReport
import com.realitylock.app.verify.VerificationReport.Outcome
import com.realitylock.app.verify.VerificationReport.Verdict

/**
 * A ring of four arcs — Integrity, Signature, Attestation, Context — each
 * coloured by the worst outcome in that check group, with the verdict's icon in
 * the middle.
 *
 * It is deliberately NOT a score. There is no percentage and no single colour for
 * "how trustworthy": four independent groups, each in its own state, because
 * collapsing them is exactly how a ring could read better than the evidence. A
 * group with no result yet is a dim, empty arc — not a pass.
 *
 * [working] shows an orbiting arc while a check is genuinely in flight.
 * [phoneChecked] marks a result produced on this phone rather than by the
 * backend, which can never reach VERIFIED.
 */
@Composable
fun TrustRing(
    report: VerificationReport?,
    modifier: Modifier = Modifier,
    working: Boolean = false,
    phoneChecked: Boolean = false,
    size: Dp = 60.dp,
) {
    val c = RealityLockThemeTokens.colors
    val states: Map<CheckGroupId, Outcome> =
        report?.let { groupChecks(it.checks).associate { g -> g.id to g.state } } ?: emptyMap()

    fun colorFor(id: CheckGroupId): Color = when (states[id]) {
        Outcome.PASS -> c.pass
        Outcome.FAIL -> c.fail
        Outcome.UNAVAILABLE -> c.unavailable
        Outcome.UNKNOWN -> c.unknown
        null -> c.border
    }

    val transition = rememberInfiniteTransition(label = "ringSpin")
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "ringAngle",
    )
    val fill by animateFloatAsState(if (report != null) 1f else 0f, tween(700), label = "ringFill")

    val groups = listOf(CheckGroupId.INTEGRITY, CheckGroupId.SIGNATURE, CheckGroupId.ATTESTATION, CheckGroupId.CONTEXT)
    val description = groups.joinToString(", ") { id ->
        "${id.title} ${states[id]?.name?.lowercase() ?: "not checked"}"
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Trust ring: $description" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2f
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            val gap = 14f
            val sweep = (360f - gap * groups.size) / groups.size
            groups.forEachIndexed { index, id ->
                val start = -90f + gap / 2f + index * (sweep + gap)
                drawArc(
                    c.border.copy(alpha = 0.55f), start, sweep, false,
                    topLeft = Offset(inset, inset), size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (fill > 0f) {
                    drawArc(
                        colorFor(id), start, sweep * fill, false,
                        topLeft = Offset(inset, inset), size = arc,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            if (working) {
                rotate(spin, center) {
                    drawArc(
                        c.primary, -90f, 52f, false,
                        topLeft = Offset(inset, inset), size = arc,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        val (icon, tint) = when (report?.verdict) {
            Verdict.VERIFIED -> RlIcons.ShieldGood to c.pass
            Verdict.FAILED, Verdict.INVALID_FORMAT -> RlIcons.Fail to c.fail
            Verdict.INCOMPLETE -> RlIcons.Warn to c.warn
            Verdict.UNKNOWN -> RlIcons.Unknown to c.unknown
            null -> RlIcons.Lock to c.inkMuted
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.38f))
        if (phoneChecked && report != null) {
            Icon(
                RlIcons.Phone, contentDescription = "Checked on this phone", tint = c.inkMuted,
                modifier = Modifier.align(Alignment.BottomEnd).size(size * 0.24f),
            )
        }
    }
}
