package com.realitylock.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.places.AddressResolver
import com.realitylock.app.places.PlaceName
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import java.util.Locale

/** Provided once at the root; null in previews and tests, which then show coordinates only. */
val LocalAddressResolver = staticCompositionLocalOf<AddressResolver?> { null }

/**
 * The place name for a pair of coordinates, or null while it is being looked up
 * or when no real geocoder could answer. Never a placeholder, never a guess.
 */
@Composable
fun rememberPlace(latitude: Double, longitude: Double, enabled: Boolean = true): State<PlaceName?> {
    val resolver = LocalAddressResolver.current
    val place = remember(latitude, longitude) { mutableStateOf<PlaceName?>(null) }
    LaunchedEffect(latitude, longitude, enabled, resolver) {
        if (enabled && resolver != null) place.value = resolver.resolve(latitude, longitude)
    }
    return place
}

/**
 * A capture's location: the place name (when a real lookup found one) over the
 * exact coordinates and accuracy.
 *
 * The coordinates are the evidence and are always shown. The place name is a
 * third party's reading of them, so it appears only if a geocoder answered, and
 * the "i" says plainly that it is not part of the signed record. A location the
 * platform flagged as a mock gets no place name at all — naming where a spoofed
 * position "is" would dress up a position nobody should trust.
 */
@Composable
fun PlaceLine(
    latitude: Double,
    longitude: Double,
    accuracyMeters: Float?,
    isMock: Boolean,
    modifier: Modifier = Modifier,
    /** Decimal places shown for the coordinates; the viewer deliberately shows fewer. */
    decimals: Int = 5,
) {
    val c = RealityLockThemeTokens.colors
    val place by rememberPlace(latitude, longitude, enabled = !isMock)
    var expanded by rememberSaveable(latitude, longitude) { mutableStateOf(false) }
    val coordinates = String.format(Locale.ROOT, "%.${decimals}f, %.${decimals}f", latitude, longitude)
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (place != null) Modifier.clickable { expanded = !expanded } else Modifier)
            .animateContentSize()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(
            icon = if (isMock) RlIcons.Warn else RlIcons.Location,
            tint = if (isMock) c.fail else c.info,
            size = 34.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val resolved = place
            if (resolved != null) {
                Text(
                    resolved.short,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = c.ink,
                )
            }
            Text(
                buildString {
                    append(coordinates)
                    if (accuracyMeters != null) append("  ·  ±${accuracyMeters.toInt()} m")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (resolved != null) c.inkMuted else c.ink,
            )
            AnimatedVisibility(visible = expanded && resolved != null, enter = fadeIn()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text(resolved?.full.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.ink)
                    Text(
                        stringResource(
                            R.string.place_unsigned_note,
                            stringResource(
                                if (resolved?.source == PlaceName.Source.OPENSTREETMAP) {
                                    R.string.place_source_osm
                                } else {
                                    R.string.place_source_device
                                },
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkMuted,
                    )
                }
            }
        }
        if (place != null) {
            Icon(
                if (expanded) RlIcons.Collapse else RlIcons.Info,
                contentDescription = null,
                tint = c.inkMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
