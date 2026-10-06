package com.realitylock.app.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realitylock.app.R
import com.realitylock.app.ui.theme.RealityLockThemeTokens

/**
 * The app's frame: the logo mark, the status header, the floating navigation bar
 * and the launch splash. Nothing here is decoration for its own sake — the header
 * pills show two facts that matter during a demo and are both real: whether the
 * phone has a network right now, and how many captures are still waiting to sync.
 */

/**
 * The shield-and-keyhole mark, drawn as a path so it scales crisply and can
 * draw itself on. [progress] 0..1 traces the outline; the keyhole fades in as it
 * completes.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 34.dp, progress: Float = 1f) {
    val c = RealityLockThemeTokens.colors
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            lineTo(w * 0.9f, h * 0.2f)
            cubicTo(w * 0.9f, h * 0.55f, w * 0.75f, h * 0.8f, w * 0.5f, h * 0.96f)
            cubicTo(w * 0.25f, h * 0.8f, w * 0.1f, h * 0.55f, w * 0.1f, h * 0.2f)
            close()
        }
        val brush = Brush.linearGradient(listOf(c.primary, c.accent), Offset.Zero, Offset(w, h))
        val stroke = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (progress >= 1f) {
            drawPath(shield, Brush.linearGradient(listOf(c.primary.copy(alpha = 0.22f), c.accent.copy(alpha = 0.10f)), Offset.Zero, Offset(w, h)))
            drawPath(shield, brush, style = stroke)
        } else {
            val measure = PathMeasure().apply { setPath(shield, false) }
            val partial = Path()
            measure.getSegment(0f, measure.length * progress, partial, true)
            drawPath(partial, brush, style = stroke)
        }
        val keyAlpha = ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f)
        if (keyAlpha > 0f) {
            drawCircle(c.ink.copy(alpha = keyAlpha), radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.43f))
            drawRoundRect(
                c.ink.copy(alpha = keyAlpha),
                topLeft = Offset(w * 0.47f, h * 0.47f),
                size = Size(w * 0.06f, h * 0.2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
            )
        }
    }
}

/** True while the phone has a network that claims internet access. */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val online = remember { mutableStateOf(true) }
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun current(): Boolean =
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        online.value = current()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online.value = current() }
            override fun onLost(network: Network) { online.value = current() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online.value = current()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return online
}

/** Logo, wordmark, and the two live status pills. */
@Composable
fun AppHeader(online: Boolean, queued: Int, modifier: Modifier = Modifier) {
    val c = RealityLockThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandMark(size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                color = c.ink,
                maxLines = 1,
            )
        }
        val netTint = if (online) c.pass else c.warn
        InfoChip(
            icon = if (online) RlIcons.Online else RlIcons.Offline,
            text = stringResource(if (online) R.string.status_online else R.string.status_offline),
            tint = netTint,
        )
        if (queued > 0) {
            InfoChip(RlIcons.CloudUp, queued.toString(), c.info)
        }
    }
}

data class NavDestination(val icon: ImageVector, val label: String)

/**
 * A floating pill with four icons. Only the selected destination shows its word,
 * so the bar stays quiet and the active tab is unmissable.
 */
@Composable
fun CyberNavBar(
    destinations: List<NavDestination>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface.copy(alpha = 0.97f), shape)
            .border(1.dp, c.border, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEachIndexed { index, destination ->
            val active = index == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .then(
                        if (active) Modifier.background(brandBrush(), RoundedCornerShape(24.dp)) else Modifier,
                    )
                    .clickable { onSelect(index) }
                    .animateContentSize()
                    .padding(horizontal = if (active) 18.dp else 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = destination.label,
                    tint = if (active) c.primaryText else c.inkMuted,
                    modifier = Modifier.size(24.dp),
                )
                if (active) {
                    Text(
                        destination.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = c.primaryText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A short launch animation: the shield draws itself, the wordmark fades up, and
 * the whole thing fades out. It runs once per cold start (see the caller) and
 * blocks nothing — the real UI is already composed underneath.
 */
@Composable
fun LaunchSplash(onFinished: () -> Unit) {
    val c = RealityLockThemeTokens.colors
    val progress = remember { Animatable(0f) }
    val visible = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(450)
        visible.value = false
        kotlinx.coroutines.delay(350)
        onFinished()
    }
    AnimatedVisibility(visible = visible.value, exit = fadeOut(tween(300))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.bg),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BrandMark(size = 120.dp, progress = progress.value)
                Spacer(Modifier.size(18.dp))
                Text(
                    stringResource(R.string.app_name).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 4.sp,
                    color = c.ink.copy(alpha = progress.value),
                )
                Text(
                    stringResource(R.string.splash_tagline),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.primary.copy(alpha = progress.value),
                )
            }
        }
    }
}

/** A slow pulse 0..1, for glows and "live" dots. */
@Composable
fun rememberPulse(millis: Int = 1400): State<Float> {
    val transition = rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(millis, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
}

/** Soft glow behind an element, in [color], breathing with [rememberPulse]. */
fun Modifier.glow(color: Color, radius: Dp = 18.dp, alpha: Float = 0.35f): Modifier =
    this.drawBehind {
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = size.minDimension / 2f + radius.toPx(),
            ),
            radius = size.minDimension / 2f + radius.toPx(),
        )
    }
