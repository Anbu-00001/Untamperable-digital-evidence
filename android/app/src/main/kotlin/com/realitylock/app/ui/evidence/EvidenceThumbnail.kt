package com.realitylock.app.ui.evidence

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.ui.theme.RealityLockThemeTokens

/** Sizes shared by the evidence surfaces. */
object EvidenceDefaults {

    /** Thumbnail edge in a history row. */
    val ThumbnailSize: Dp = 72.dp

    /**
     * The floor for anything tappable. 48dp is Material's figure and clears the
     * 44dp accessibility minimum with room to spare; using the larger number
     * means a target never sits *exactly* on the line where rounding could put
     * it under.
     */
    val MinTouchTarget: Dp = 48.dp
}

/**
 * A small, cheap preview of a captured photograph, for use inside a
 * `LazyColumn` row.
 *
 * ## What makes it list-safe
 *
 * Two things, and both matter:
 *
 * 1. **The cache is read synchronously, in composition.** A row scrolled back
 *    into view finds its bitmap with a hash lookup and draws it in the very
 *    first frame — the initial state is already `Loaded`, so there is no
 *    coroutine round-trip and no placeholder flash on the way back up the list.
 * 2. **A miss decodes off the main thread**, downsampled to the thumbnail's own
 *    pixel size rather than the photograph's. A 12 MP capture shown at 72dp is
 *    subsampled by roughly 16x before a single pixel is allocated.
 *
 * ## What it deliberately does not say
 *
 * Nothing about authenticity. A thumbnail is an index entry; the verification
 * standing of an event belongs on the surface that has room to state it
 * properly, which is [EvidenceViewerScreen]. Putting a tick beside a 72dp square
 * would be a verdict rendered at a size that cannot carry its own caveats.
 *
 * @param event the capture to preview. Its `mediaFilePath` is device-local state
 *        reconstructed by the repository, never part of the proof package.
 * @param onClick opens the full viewer. Null renders a non-interactive preview,
 *        which is also what keeps it out of the tab order when there is nowhere
 *        to go.
 */
@Composable
fun EvidenceThumbnail(
    event: CapturedEvent,
    modifier: Modifier = Modifier,
    size: Dp = EvidenceDefaults.ThumbnailSize,
    onClick: (() -> Unit)? = null,
) {
    val colors = RealityLockThemeTokens.colors
    // Coerced rather than asserted: a caller passing a smaller size gets a
    // legible, tappable square instead of a target under the 44dp minimum.
    val boxSize = size.coerceAtLeast(EvidenceDefaults.MinTouchTarget)
    val edgePx = with(LocalDensity.current) { boxSize.roundToPx() }
    val cacheKey = remember(event.mediaFilePath, edgePx) {
        EvidenceThumbnailCache.key(event.mediaFilePath, edgePx)
    }

    // The cache read happens inside `remember`'s initialiser, so a warm entry is
    // the state this composable is *born* in — not something it transitions to
    // after a frame.
    var state by remember(cacheKey) {
        mutableStateOf<EvidenceImage>(
            EvidenceThumbnailCache.get(cacheKey)
                ?.let(EvidenceImage::Loaded)
                ?: EvidenceImage.Loading,
        )
    }

    LaunchedEffect(cacheKey) {
        if (state is EvidenceImage.Loaded) return@LaunchedEffect
        val result = EvidenceImageDecoder.decode(event.mediaFilePath, edgePx, edgePx)
        if (result is EvidenceImage.Loaded) EvidenceThumbnailCache.put(cacheKey, result.bitmap)
        state = result
    }

    val shape = RoundedCornerShape(THUMBNAIL_CORNER_DP.dp)
    val openLabel = stringResource(R.string.evidence_open)
    var boxModifier = modifier
        .size(boxSize)
        .clip(shape)
        .background(colors.surfaceAlt)
        .border(1.dp, colors.border, shape)
    if (onClick != null) {
        boxModifier = boxModifier.clickable(
            onClickLabel = openLabel,
            role = Role.Button,
            onClick = onClick,
        )
    }

    Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
        when (val current = state) {
            is EvidenceImage.Loaded -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                // Names the subject and its capture time, and stops there. It
                // must not read as an endorsement of the image.
                contentDescription = stringResource(
                    R.string.evidence_thumbnail_content_description,
                    event.metadata.timestamp.iso8601,
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            EvidenceImage.Loading -> ThumbnailGlyph(
                glyph = LOADING_GLYPH,
                tint = colors.inkMuted,
                description = stringResource(R.string.evidence_loading),
            )

            // Both failure states are drawn, never left blank: an empty square in
            // a list is read as "still loading" forever.
            EvidenceImage.Missing -> ThumbnailGlyph(
                glyph = PROBLEM_GLYPH,
                tint = colors.fail,
                description = stringResource(R.string.evidence_thumbnail_missing),
            )

            is EvidenceImage.Unreadable -> ThumbnailGlyph(
                glyph = PROBLEM_GLYPH,
                tint = colors.fail,
                description = stringResource(R.string.evidence_thumbnail_unreadable),
            )
        }
    }
}

/**
 * A glyph standing in for an image, with the spoken description supplied
 * separately.
 *
 * `contentDescription` overrides the glyph in the accessibility tree on purpose:
 * TalkBack announcing "warning sign" tells a user nothing, while "image file
 * missing" tells them exactly what happened. The glyph is for sighted users; the
 * sentence is for everyone else.
 */
@Composable
private fun ThumbnailGlyph(glyph: String, tint: Color, description: String) {
    Text(
        text = glyph,
        color = tint,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

private const val THUMBNAIL_CORNER_DP = 8
private const val LOADING_GLYPH = "…"
private const val PROBLEM_GLYPH = "⚠"
