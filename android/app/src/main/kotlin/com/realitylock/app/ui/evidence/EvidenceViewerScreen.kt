package com.realitylock.app.ui.evidence

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.ui.common.chromeInsets
import com.realitylock.app.ui.common.scrollableBottomInset
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.verify.VerificationReport

/**
 * The full-screen view of one captured event: the photograph, and the few facts
 * about it that a person looking at a photograph actually needs.
 *
 * ## Why this screen exists
 *
 * Captures are written to app-private internal storage with no `MediaStore`
 * entry, which is the right call for an evidence tool — no other app can read
 * them, and nothing lands in a shared gallery — but it also meant that until now
 * there was **no way at all to look at what had been recorded**. The History tab
 * showed hashes and byte counts of a photograph the user had never seen.
 *
 * ## The rule this screen is built around
 *
 * **Displaying a photograph is not a claim about it.** The natural reading of
 * "the app showed me the image" is "the app stands behind the image", and that
 * inference is wrong: rendering a file proves only that the file decoded. So the
 * verification standing of the event is stated in words, in a block that is
 * always present:
 *
 * - a verdict exists -> it is shown, with what that verdict does and does not
 *   establish;
 * - no verdict exists -> the screen says so outright.
 *
 * There is no third branch where the block is simply absent. Silence next to a
 * photograph reads as approval, and an event nobody has verified must not borrow
 * credibility from the fact that its picture rendered.
 *
 * ## Location precision
 *
 * Shown to three decimal places (~100 m), matching the PDF certificate. The
 * exact coordinate is never rounded in the proof package — this is a display
 * decision about a screen someone may photograph or share, not a change to what
 * was signed.
 *
 * @param event the capture to display.
 * @param verdict the verification verdict **for this event**, or null if this
 *        event has never been verified. Callers must scope it to the event: a
 *        verdict from another capture rendered here would be exactly the
 *        cross-contamination the History tab already had to fix once. From
 *        `ProofsUiState` that is
 *        `report?.takeIf { reportEventId == event.eventId }?.verdict`.
 * @param onClose dismisses the viewer; the host owns navigation.
 */
@Composable
fun EvidenceViewerScreen(
    event: CapturedEvent,
    verdict: VerificationReport.Verdict?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RealityLockThemeTokens.colors

    // The decode target comes from the space this screen was actually given,
    // rather than from the screen metrics, so a tablet pane or a split-screen
    // window subsamples to what it can display instead of to the whole display.
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(colors.bg)) {
        val density = LocalDensity.current
        // Unbounded constraints would make `maxWidth`/`maxHeight` infinite, and
        // `roundToPx()` on an infinite Dp is a crash. A caller placing this in a
        // scrolling parent is a mistake, but not one worth crashing over: fall
        // back to a sane target and still show the photograph.
        val targetWidthPx = if (constraints.hasBoundedWidth) {
            with(density) { maxWidth.roundToPx() }
        } else {
            FALLBACK_TARGET_PX
        }
        val targetHeightPx = if (constraints.hasBoundedHeight) {
            with(density) { maxHeight.roundToPx() }
        } else {
            targetWidthPx
        }

        var image by remember(event.mediaFilePath, targetWidthPx, targetHeightPx) {
            mutableStateOf<EvidenceImage>(EvidenceImage.Loading)
        }
        // The decode itself is a suspend function that switches to Dispatchers.IO
        // internally, so nothing here touches the file on the main thread — and
        // leaving the screen cancels the coroutine with the composition.
        //
        // The bitmap is NOT recycled on the way out. It is held by `remember`,
        // dies with the composition, and its pixels are freed by the GC from the
        // native heap. Calling recycle() would race a frame that is still drawing
        // it. (`EvidenceImageDecoder` documents the one case where recycling is
        // safe, and why.)
        LaunchedEffect(event.mediaFilePath, targetWidthPx, targetHeightPx) {
            image = EvidenceImageDecoder.decode(
                path = event.mediaFilePath,
                targetWidthPx = targetWidthPx,
                targetHeightPx = targetHeightPx,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Fixed chrome takes the top and side insets; the bottom belongs
                // to the scrollable below. See ui/common/WindowInsetsSupport.
                .windowInsetsPadding(chromeInsets),
        ) {
            EvidenceTopBar(onClose = onClose)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Applied AFTER verticalScroll so it pads the scrolled
                    // CONTENT, not the viewport. Padding the viewport would carve
                    // out a dead strip above the navigation bar that never
                    // scrolls; this way the last row scrolls clear of the bar and
                    // stops.
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp + scrollableBottomInset()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EvidencePhotograph(image = image, capturedAtIso = event.metadata.timestamp.iso8601)
                VerificationDisclosure(verdict = verdict)
                CaptureFacts(event = event)
            }
        }
    }
}

@Composable
private fun EvidenceTopBar(onClose: () -> Unit) {
    val colors = RealityLockThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.evidence_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextButton(
            onClick = onClose,
            // Explicit rather than relying on the component default, so the
            // target stays above the 44dp minimum whatever the theme does to
            // button metrics.
            modifier = Modifier.defaultMinSize(
                minWidth = EvidenceDefaults.MinTouchTarget,
                minHeight = EvidenceDefaults.MinTouchTarget,
            ),
        ) {
            Text(stringResource(R.string.evidence_close))
        }
    }
}

/**
 * The photograph, or an explicit account of why it is not there.
 *
 * Every branch draws something. The one outcome this composable will not produce
 * is an empty frame, because an empty frame is indistinguishable from a very
 * dark photograph and the user has no way to tell which they are looking at.
 */
@Composable
private fun EvidencePhotograph(image: EvidenceImage, capturedAtIso: String) {
    val colors = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(PHOTO_CORNER_DP.dp)

    when (image) {
        EvidenceImage.Loading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PLACEHOLDER_ASPECT_RATIO)
                .clip(shape)
                .background(colors.surfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = colors.primary)
                Text(
                    text = stringResource(R.string.evidence_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }

        is EvidenceImage.Loaded -> {
            val bitmap = image.bitmap
            val aspect = remember(bitmap) {
                if (bitmap.height > 0) {
                    bitmap.width.toFloat() / bitmap.height.toFloat()
                } else {
                    PLACEHOLDER_ASPECT_RATIO
                }
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                // States the subject and when it was taken. Deliberately worded
                // as a description of a file, not as a warrant for it — a screen
                // reader user gets the same non-claim a sighted user does.
                contentDescription = stringResource(
                    R.string.evidence_image_content_description,
                    capturedAtIso,
                ),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .clip(shape)
                    .background(colors.surfaceAlt),
            )
        }

        EvidenceImage.Missing -> PhotographProblem(
            titleRes = R.string.evidence_error_missing_title,
            body = stringResource(R.string.evidence_error_missing_body),
        )

        is EvidenceImage.Unreadable -> PhotographProblem(
            titleRes = R.string.evidence_error_unreadable_title,
            body = stringResource(
                R.string.evidence_error_unreadable_body,
                image.reason ?: stringResource(R.string.evidence_error_reason_unknown),
            ),
        )
    }
}

/** The visible, named failure state that replaces a blank frame. */
@Composable
private fun PhotographProblem(@StringRes titleRes: Int, body: String) {
    val colors = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(PHOTO_CORNER_DP.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.failSoft)
            .border(1.dp, colors.fail, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PROBLEM_GLYPH,
                color = colors.fail,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(titleRes),
                color = colors.fail,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(text = body, style = MaterialTheme.typography.bodySmall, color = colors.ink)
    }
}

/**
 * How a verification standing is drawn.
 *
 * "Never verified" gets the `unavailable` grey rather than a red or an amber,
 * for the reason ADR-0006 §5 gives about check outcomes: absence of evidence is
 * not evidence of a defect. Nobody has checked this event, which is neither an
 * accusation nor a reassurance, and the colour has to stay out of both.
 */
@Immutable
private data class DisclosureStyle(
    val glyph: String,
    val fg: Color,
    val bg: Color,
    @param:StringRes val labelRes: Int,
    @param:StringRes val bodyRes: Int,
)

/**
 * Glyphs and tokens deliberately mirror the Authenticity Result panel so one
 * verdict never looks like two different things in two places. This mapping is
 * kept local to the evidence package rather than reaching into `ui/verify`,
 * because a viewer that fails to compile when the verification panel is
 * refactored is a viewer nobody can ship independently.
 */
@Composable
private fun disclosureStyleFor(verdict: VerificationReport.Verdict?): DisclosureStyle {
    val c = RealityLockThemeTokens.colors
    return when (verdict) {
        null -> DisclosureStyle(
            glyph = "—",
            fg = c.unavailable,
            bg = c.unavailableSoft,
            labelRes = R.string.evidence_status_not_verified,
            bodyRes = R.string.evidence_verdict_body_absent,
        )
        VerificationReport.Verdict.VERIFIED -> DisclosureStyle(
            glyph = "✓",
            fg = c.pass,
            bg = c.passSoft,
            labelRes = R.string.verify_verdict_verified,
            bodyRes = R.string.evidence_verdict_body_verified,
        )
        VerificationReport.Verdict.FAILED -> DisclosureStyle(
            glyph = "✕",
            fg = c.fail,
            bg = c.failSoft,
            labelRes = R.string.verify_verdict_failed,
            bodyRes = R.string.evidence_verdict_body_failed,
        )
        VerificationReport.Verdict.INCOMPLETE -> DisclosureStyle(
            glyph = "◐",
            fg = c.warn,
            bg = c.warnSoft,
            labelRes = R.string.verify_verdict_incomplete,
            bodyRes = R.string.evidence_verdict_body_incomplete,
        )
        VerificationReport.Verdict.INVALID_FORMAT -> DisclosureStyle(
            glyph = "▲",
            fg = c.fail,
            bg = c.failSoft,
            labelRes = R.string.verify_verdict_invalid_format,
            bodyRes = R.string.evidence_verdict_body_invalid_format,
        )
        VerificationReport.Verdict.UNKNOWN -> DisclosureStyle(
            glyph = "?",
            fg = c.unknown,
            bg = c.unknownSoft,
            labelRes = R.string.verify_verdict_unknown,
            bodyRes = R.string.evidence_verdict_body_unknown,
        )
    }
}

/**
 * The always-present statement of what showing this image does and does not
 * mean.
 *
 * Never conditionally rendered. The "no verdict" case is the one that most needs
 * saying, and it is precisely the case a `verdict?.let { ... }` would have
 * rendered as nothing at all.
 */
@Composable
private fun VerificationDisclosure(verdict: VerificationReport.Verdict?) {
    val colors = RealityLockThemeTokens.colors
    val style = disclosureStyleFor(verdict)
    val shape = RoundedCornerShape(PHOTO_CORNER_DP.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(style.bg)
            .border(1.dp, style.fg, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.evidence_verification_title),
            style = MaterialTheme.typography.labelMedium,
            color = style.fg,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status is never colour alone. The glyph and the word both carry it,
            // so a greyscale screenshot — how these end up in a case file — and a
            // reader who cannot separate red from green both still get the state.
            Text(
                text = style.glyph,
                color = style.fg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(style.labelRes),
                color = style.fg,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = stringResource(style.bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink,
        )
        HorizontalDivider(color = style.fg.copy(alpha = DIVIDER_ALPHA))
        // The ceiling on the whole screen, present under every verdict including
        // VERIFIED: a rendered image is a rendering of bytes, not proof that the
        // bytes are the originals.
        Text(
            text = stringResource(R.string.evidence_display_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
    }
}

/** Capture time, location and device — the three facts the photograph needs. */
@Composable
private fun CaptureFacts(event: CapturedEvent) {
    val colors = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(PHOTO_CORNER_DP.dp)
    val location = event.metadata.location
    val device = event.metadata.device

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.evidence_section_details),
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkMuted,
        )

        FactRow(
            label = stringResource(R.string.evidence_label_time),
            value = event.metadata.timestamp.iso8601,
        )

        if (location == null) {
            FactRow(
                label = stringResource(R.string.evidence_label_location),
                value = stringResource(R.string.evidence_location_absent),
            )
        } else {
            FactRow(
                label = stringResource(R.string.evidence_label_location),
                value = stringResource(
                    R.string.evidence_location_format,
                    location.latitude,
                    location.longitude,
                ),
                note = stringResource(
                    R.string.evidence_location_accuracy,
                    location.accuracyMeters,
                ),
            )
            Text(
                text = stringResource(R.string.evidence_location_precision_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
            // A mock provider is material to anyone reading this location, so it
            // travels with the location rather than being left to the checks list.
            if (location.isMock) {
                Text(
                    text = stringResource(R.string.evidence_location_mock),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fail,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        FactRow(
            label = stringResource(R.string.evidence_label_device),
            value = stringResource(
                R.string.evidence_device_format,
                device.manufacturer,
                device.model,
                device.sdkInt,
            ),
        )

        FactRow(
            label = stringResource(R.string.evidence_label_event),
            value = event.eventId,
        )
    }
}

@Composable
private fun FactRow(label: String, value: String, note: String? = null) {
    val colors = RealityLockThemeTokens.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkMuted,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        if (note != null) {
            Text(text = note, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
    }
}

/** Reference-density width of a mid-size phone; only ever used as a fallback. */
private const val FALLBACK_TARGET_PX = 1080
private const val PHOTO_CORNER_DP = 12
private const val PLACEHOLDER_ASPECT_RATIO = 4f / 3f
private const val PROBLEM_GLYPH = "⚠"
private const val DIVIDER_ALPHA = 0.3f
