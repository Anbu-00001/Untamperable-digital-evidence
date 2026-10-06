package com.realitylock.app.ui.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realitylock.app.capture.CaptureStage
import com.realitylock.app.capture.StageTiming
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.components.brandBrush
import com.realitylock.app.ui.components.rememberPulse
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val STAGE_ORDER = listOf(
    CaptureStage.SHUTTER,
    CaptureStage.LOCATE,
    CaptureStage.HASH,
    CaptureStage.SIGN,
    CaptureStage.SEAL,
)

private fun CaptureStage.icon(): ImageVector = when (this) {
    CaptureStage.SHUTTER -> RlIcons.Capture
    CaptureStage.LOCATE -> RlIcons.Location
    CaptureStage.HASH -> RlIcons.Hash
    CaptureStage.SIGN -> RlIcons.Key
    CaptureStage.SEAL -> RlIcons.Lock
}

private enum class NodeState { PENDING, ACTIVE, DONE, SKIPPED }

/**
 * The capture, drawn as it happens: five nodes on a ring — shutter, locate, hash,
 * sign, seal — each lighting when the pipeline REALLY reaches that stage and
 * showing how long it REALLY took.
 *
 * Nothing here is paced for effect. The stages come from
 * [com.realitylock.app.capture.CaptureCoordinator] as they begin; a stage that
 * was never started (location, when permission was not granted) is shown as
 * skipped rather than quietly ticked off. The only thing added after the fact is
 * that the finished ring stays on screen for a moment so the timings can be read.
 */
@Composable
fun SealOverlay(
    stages: List<StageTiming>,
    totalMillis: Long?,
    modifier: Modifier = Modifier,
) {
    val c = RealityLockThemeTokens.colors
    val finished = totalMillis != null
    val byStage = stages.associateBy { it.stage }
    val lastStarted = stages.lastOrNull()?.stage

    fun stateOf(stage: CaptureStage): NodeState {
        val timing = byStage[stage]
        return when {
            timing != null && timing.active -> NodeState.ACTIVE
            timing != null -> NodeState.DONE
            lastStarted != null && STAGE_ORDER.indexOf(stage) < STAGE_ORDER.indexOf(lastStarted) -> NodeState.SKIPPED
            finished && stage == CaptureStage.LOCATE -> NodeState.SKIPPED
            else -> NodeState.PENDING
        }
    }

    val done = STAGE_ORDER.count { stateOf(it) == NodeState.DONE }
    val countable = STAGE_ORDER.count { stateOf(it) != NodeState.SKIPPED }.coerceAtLeast(1)
    val progress by animateFloatAsState(
        if (finished) 1f else done.toFloat() / countable,
        tween(350),
        label = "sealProgress",
    )

    val spin = rememberInfiniteTransition(label = "spin")
    val spinAngle by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "spinAngle",
    )
    val sealedScale = remember { Animatable(0.4f) }
    LaunchedEffect(finished) {
        if (finished) sealedScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
    }

    val ringSize = 236.dp
    val nodeRadius = 98.dp
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xDD060A18))
            .semantics {
                contentDescription = if (finished) "Capture sealed in $totalMillis milliseconds" else "Sealing capture"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(ringSize + 56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(ringSize)) {
                val stroke = 6.dp.toPx()
                val inset = stroke / 2f + 16.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(
                    c.border.copy(alpha = 0.6f), 0f, 360f, false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke),
                )
                drawArc(
                    Brush.sweepGradient(listOf(c.primary, c.accent, c.primary), center),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (!finished) {
                    // Orbiting dot while work is in flight.
                    rotate(spinAngle, center) {
                        drawCircle(c.primary, 4.dp.toPx(), Offset(center.x, inset - 10.dp.toPx()))
                    }
                }
            }

            // Centre: the lock while working, the shield with a tick once sealed.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (finished) {
                    Icon(
                        RlIcons.ShieldGood, null, tint = c.pass,
                        modifier = Modifier.size(54.dp).scale(sealedScale.value),
                    )
                    Text(formatMillis(totalMillis ?: 0L), style = MaterialTheme.typography.titleMedium, color = c.ink)
                } else {
                    Icon(RlIcons.Lock, null, tint = c.primary, modifier = Modifier.size(40.dp))
                }
            }

            STAGE_ORDER.forEachIndexed { index, stage ->
                val angle = (-90.0 + index * 360.0 / STAGE_ORDER.size) * PI / 180.0
                val dx = (nodeRadius.value * cos(angle)).dp
                val dy = (nodeRadius.value * sin(angle)).dp
                Box(Modifier.offset(dx, dy)) {
                    // The duration sits on the OUTER side of its node so it never lands
                    // on the ring or the shield: above for the top node, beside for the
                    // two upper ones (the ring runs through the space above them),
                    // below for the two at the bottom.
                    SealNode(
                        stage.icon(),
                        stateOf(stage),
                        byStage[stage]?.durationMillis,
                        side = when {
                            index == 0 -> LabelSide.ABOVE
                            cos(angle) > 0.5 && sin(angle) < 0 -> LabelSide.END
                            cos(angle) < -0.5 && sin(angle) < 0 -> LabelSide.START
                            else -> LabelSide.BELOW
                        },
                    )
                }
            }
        }
    }
}

private enum class LabelSide { ABOVE, BELOW, START, END }

private val NODE_SIZE = 38.dp

@Composable
private fun SealNode(icon: ImageVector, state: NodeState, millis: Long?, side: LabelSide) {
    val c = RealityLockThemeTokens.colors
    val pulse by rememberPulse(700)
    val tint = when (state) {
        NodeState.DONE -> c.pass
        NodeState.ACTIVE -> c.primary
        NodeState.SKIPPED -> c.unavailable
        NodeState.PENDING -> c.inkMuted.copy(alpha = 0.5f)
    }
    // The node is the only thing that takes layout space, so its centre sits exactly
    // on the ring; the label floats beside it without moving it.
    Box(Modifier.size(NODE_SIZE)) {
        Box(
            Modifier
                .matchParentSize()
                .scale(if (state == NodeState.ACTIVE) 1f + 0.12f * pulse else 1f)
                .background(c.surface, CircleShape)
                .border(2.dp, tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        val text = when {
            // Real duration once the stage has finished; "skip" when it never ran.
            state == NodeState.SKIPPED -> "skip"
            state == NodeState.DONE && millis != null -> formatMillis(millis)
            else -> return@Box
        }
        val gap = 4.dp
        val (alignment, dx, dy) = when (side) {
            LabelSide.ABOVE -> Triple(Alignment.TopCenter, 0.dp, -(gap + 12.dp))
            LabelSide.BELOW -> Triple(Alignment.BottomCenter, 0.dp, gap + 12.dp)
            LabelSide.END -> Triple(Alignment.CenterStart, NODE_SIZE + gap, 0.dp)
            LabelSide.START -> Triple(Alignment.CenterEnd, -(NODE_SIZE + gap), 0.dp)
        }
        Box(Modifier.matchParentSize().wrapContentSize(alignment, unbounded = true)) {
            Text(
                text,
                fontSize = 10.sp,
                color = c.inkMuted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.offset(dx, dy),
            )
        }
    }
}

/** 626 -> "626 ms", 10729 -> "10.7 s": readable at a glance on a projector. */
internal fun formatMillis(millis: Long): String =
    if (millis < 1_000L) "$millis ms" else "%.1f s".format(millis / 1000.0)

/**
 * The shutter: a gradient ring around a solid disc. It breathes while idle and
 * shows an orbiting arc while a capture is genuinely in flight.
 */
@Composable
fun ShutterButton(
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
) {
    val c = RealityLockThemeTokens.colors
    val pulse by rememberPulse(1600)
    val spin = rememberInfiniteTransition(label = "shutterSpin")
    val angle by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "shutterAngle",
    )
    val press by animateFloatAsState(if (busy) 0.82f else 1f, tween(160), label = "press")
    Box(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription }
            .clip(CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = 5.dp.toPx()
            if (!busy) {
                drawCircle(c.primary.copy(alpha = 0.10f + 0.16f * pulse), radius = this.size.minDimension / 2f)
            }
            drawCircle(
                Brush.linearGradient(listOf(c.primary, c.accent)),
                radius = this.size.minDimension / 2f - stroke / 2f,
                style = Stroke(stroke),
            )
            if (busy) {
                rotate(angle, center) {
                    drawArc(
                        c.ink, -90f, 70f, false,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(this.size.width - stroke, this.size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            drawCircle(
                if (busy) c.primary.copy(alpha = 0.55f) else c.ink,
                radius = (this.size.minDimension / 2f - 13.dp.toPx()) * press,
            )
        }
    }
}

/** A readout pill that stays legible over a camera feed. */
@Composable
fun HudChip(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .background(Color(0x99060A18), shape)
            .border(1.dp, tint.copy(alpha = 0.55f), shape)
            .then(if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Corner brackets framing the shot, the way a viewfinder does. */
@Composable
fun ViewfinderBrackets(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier.fillMaxSize()) {
        val len = 26.dp.toPx()
        val pad = 14.dp.toPx()
        val s = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * len, y), s.width, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * len), s.width, StrokeCap.Round)
        }
        corner(pad, pad, 1f, 1f)
        corner(w - pad, pad, -1f, 1f)
        corner(pad, h - pad, 1f, -1f)
        corner(w - pad, h - pad, -1f, -1f)
    }
}
