package com.realitylock.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.realitylock.app.ui.theme.RealityLockThemeTokens

/**
 * The visual kit for the redesign: a handful of small, composable pieces that
 * every screen builds from, so the look is decided here once.
 *
 * Two rules run through all of it. Status is never colour alone — every status
 * element is icon + colour + word. And a notice is never deleted to save space:
 * [NoticeChip] shortens it to an icon and a few words and keeps the full sentence
 * one tap away.
 */

/** Deep-navy backdrop with two soft glows, drawn once behind the whole app. */
@Composable
fun CyberBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = RealityLockThemeTokens.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(c.glowA.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.95f, 0f),
                        radius = size.width * 0.95f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(c.glowB.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(0f, size.height * 0.88f),
                        radius = size.width * 1.05f,
                    ),
                )
            },
        content = content,
    )
}

/** The brand gradient, for buttons, rings and highlights. */
@Composable
fun brandBrush(): Brush {
    val c = RealityLockThemeTokens.colors
    return Brush.linearGradient(listOf(c.primary, c.accent))
}

/**
 * A rounded surface with a thin gradient edge. [accent] tints the edge, which is
 * how a card can say "this one is failing" without a word.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(20.dp)
    val edge = Brush.linearGradient(
        listOf((accent ?: c.primary).copy(alpha = 0.55f), c.border.copy(alpha = 0.35f)),
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(c.surface.copy(alpha = 0.94f), shape)
            .border(BorderStroke(1.dp, edge), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** An icon on a soft circle of its own colour. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** A pill holding one icon and a few words. */
@Composable
fun InfoChip(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.14f), shape)
            .border(1.dp, tint.copy(alpha = 0.38f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = c.ink,
            maxLines = 1,
        )
    }
}

/** Chips that wrap onto further lines instead of overflowing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * A notice reduced to an icon and a few words, with the whole sentence one tap
 * away.
 *
 * This is how the redesign cuts text without cutting honesty: the legal and AI
 * caveats stay in the app, word for word, behind the "i". What changes is that
 * nobody has to read a paragraph to know a caveat exists.
 */
@Composable
fun NoticeChip(
    icon: ImageVector,
    short: String,
    full: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
) {
    val c = RealityLockThemeTokens.colors
    var expanded by rememberSaveable(short) { mutableStateOf(startExpanded) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.10f), shape)
            .border(1.dp, tint.copy(alpha = 0.30f), shape)
            .then(if (full != null) Modifier.clickable { expanded = !expanded } else Modifier)
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                short,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = c.ink,
                modifier = Modifier.weight(1f),
            )
            if (full != null) {
                Icon(
                    if (expanded) RlIcons.Collapse else RlIcons.Info,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && full != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                full.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = c.inkMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A big number with an icon and a label; the count animates up when it changes. */
@Composable
fun StatTile(
    icon: ImageVector,
    value: Int,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val c = RealityLockThemeTokens.colors
    val shown by animateIntAsState(value, tween(700), label = "stat")
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(tint.copy(alpha = 0.20f), c.surface.copy(alpha = 0.9f))),
                shape,
            )
            .border(1.dp, tint.copy(alpha = 0.35f), shape)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            shown.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = c.ink,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = c.inkMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** The primary action: brand gradient, icon first, short label. */
@Composable
fun GradientButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(18.dp)
    val fill: Brush = if (enabled) brandBrush() else SolidColor(c.surfaceAlt)
    Row(
        modifier = modifier
            .clip(shape)
            .background(fill, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) c.primaryText else c.inkMuted,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) c.primaryText else c.inkMuted,
        )
    }
}

/** A thin horizontal rule in the border colour. */
@Composable
fun CyberDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(RealityLockThemeTokens.colors.border.copy(alpha = 0.6f)),
    )
}
