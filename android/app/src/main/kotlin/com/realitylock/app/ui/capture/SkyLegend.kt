package com.realitylock.app.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.theme.RealityLockThemeTokens

/**
 * What the sky plot and the "used/in view" count mean, shown on tap.
 *
 * The numbers come straight from the phone's GNSS status — [RadarState] — so the
 * heading reads the same values as the chip. The one inference, that having no
 * satellite in use is normal indoors, is worded as "usually" and only shown when
 * the count really is zero.
 */
@Composable
fun SkyLegend(state: RadarState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .background(Color(0xF00B1228), shape)
            .border(1.dp, c.primary.copy(alpha = 0.45f), shape)
            .clickable(onClick = onDismiss)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(RlIcons.Satellite, null, tint = c.primary, modifier = Modifier.size(22.dp))
            Text(
                stringResource(R.string.sky_counts, state.satellites.size, state.usedCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = c.ink,
            )
        }
        LegendRow(stringResource(R.string.sky_used)) { Dot(c.pass, 10.dp) }
        LegendRow(stringResource(R.string.sky_unused)) { Dot(c.inkMuted, 10.dp) }
        LegendRow(stringResource(R.string.sky_strong)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(c.inkMuted, 6.dp)
                Dot(c.inkMuted, 11.dp)
            }
        }
        LegendRow(stringResource(R.string.sky_tilt)) { Dot(c.primary, 10.dp) }
        LegendRow(stringResource(R.string.sky_sweep)) {
            Box(
                Modifier
                    .size(width = 18.dp, height = 4.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, c.primary)), CircleShape),
            )
        }
        if (state.usedCount == 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.info.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Icon(RlIcons.Info, null, tint = c.info, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.sky_indoors),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink,
                )
            }
        }
    }
}

@Composable
private fun LegendRow(text: String, marker: @Composable () -> Unit) {
    val c = RealityLockThemeTokens.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(width = 28.dp, height = 14.dp), contentAlignment = Alignment.Center) { marker() }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink)
    }
}

@Composable
private fun Dot(color: Color, size: Dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}
