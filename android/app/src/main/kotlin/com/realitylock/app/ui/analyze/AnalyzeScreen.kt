package com.realitylock.app.ui.analyze

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import com.realitylock.app.ui.common.scrollableBottomInset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.forensics.ExifAnalyzer
import com.realitylock.app.forensics.ProofLookup
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight

/**
 * "Explainable Authenticity Heuristic" screen: the user picks a candidate image
 * and sees an ELA heat-map and EXIF-consistency flags.
 *
 * The disclaimer is not fine print — it is the first thing on the screen and
 * frames everything below it. These are triage aids, never a real/fake verdict
 * (Phase-4 research; ADR-0005). Nothing here signs or stores anything.
 */
@Composable
fun AnalyzeScreen(viewModel: AnalyzeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.analyze(uri) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Padding applied after verticalScroll pads the scrolled CONTENT,
            // not the viewport, so the last row can be scrolled clear of the
            // navigation bar instead of sitting under it. See
            // ui/common/WindowInsetsSupport.
            .padding(16.dp)
            .padding(bottom = scrollableBottomInset()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { picker.launch("image/*") },
            enabled = !state.analyzing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.analyze_pick_image))
        }

        // The headline answer, above everything else. It arrives before the
        // heuristics finish and stays on screen even if they fail.
        state.proof?.let { ProofVerdictCard(it) }

        when {
            state.analyzing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.analyze_running))
            }

            state.error != null -> Text(
                state.error ?: "",
                color = MaterialTheme.colorScheme.error,
            )

            state.report != null -> {
                // The disclaimer sits here, immediately above the heuristics it
                // qualifies, rather than at the top of the screen. It used to be
                // first because ELA was first; now the definite answer leads and
                // the caveat belongs with the thing being caveated.
                DisclaimerCard()
                ReportView(state.report!!)
            }
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.analyze_disclaimer_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.analyze_disclaimer_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReportView(report: com.realitylock.app.forensics.ForensicAnalyzer.AuthenticityReport) {
    // Source and ELA map, side by side, so "compare edges with edges" is natural.
    Text(stringResource(R.string.analyze_ela_title), style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.analyze_source), style = MaterialTheme.typography.labelSmall)
            Image(
                bitmap = report.preview.asImageBitmap(),
                contentDescription = stringResource(R.string.analyze_source),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.analyze_ela_map), style = MaterialTheme.typography.labelSmall)
            Image(
                bitmap = report.ela.heatmap.asImageBitmap(),
                contentDescription = stringResource(R.string.analyze_ela_map),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
    }
    Text(
        stringResource(
            R.string.analyze_ela_stats,
            report.ela.resaveQuality,
            report.ela.maxError,
            report.ela.meanError,
        ),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    Text(stringResource(R.string.analyze_ela_note), style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.height(8.dp))

    // EXIF
    Text(stringResource(R.string.analyze_exif_title), style = MaterialTheme.typography.titleMedium)
    val fired = report.exif.flags
    if (fired.isEmpty()) {
        Text(stringResource(R.string.analyze_exif_none), style = MaterialTheme.typography.bodySmall)
    } else {
        fired.forEach { finding ->
            Text(
                "• " + exifFindingText(finding),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    // Descriptive facts (never framed as a verdict).
    ExifFacts(report.exif)
    Text(stringResource(R.string.analyze_exif_note), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ExifFacts(report: ExifAnalyzer.ExifReport) {
    val make = report.make ?: stringResource(R.string.analyze_absent)
    val model = report.model ?: stringResource(R.string.analyze_absent)
    val software = report.software ?: stringResource(R.string.analyze_absent)
    val captured = report.dateTimeOriginal ?: stringResource(R.string.analyze_absent)
    Text(
        stringResource(R.string.analyze_exif_facts, make, model, software, captured),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun exifFindingText(finding: ExifAnalyzer.Finding): String = when (finding.code) {
    ExifAnalyzer.Finding.Code.EDITOR_SOFTWARE ->
        stringResource(R.string.analyze_flag_editor, finding.detail ?: "")
    ExifAnalyzer.Finding.Code.MODIFY_AFTER_ORIGINAL ->
        stringResource(R.string.analyze_flag_modified, finding.detail ?: "")
    ExifAnalyzer.Finding.Code.MAKERNOTE_ABSENT ->
        stringResource(R.string.analyze_flag_makernote)
    ExifAnalyzer.Finding.Code.NO_EXIF ->
        stringResource(R.string.analyze_flag_no_exif)
    ExifAnalyzer.Finding.Code.GPS_PRESENT ->
        stringResource(R.string.analyze_flag_gps)
}

/**
 * The answer this screen leads with.
 *
 * ## Why the wording is what it is
 *
 * The common outcome is [ProofLookup.Result.NoProof], and it is very easy to
 * write that in a way that sounds like an accusation. "Not verified" and
 * "unverified image" both read, to someone anxious about a photo, as "this is
 * probably fake". The screen is required to avoid that: ADR-0005 and ADR-0006 §5
 * both turn on absence of evidence not being evidence of a defect.
 *
 * So the no-proof case states a fact about *this app* — no capture of ours has
 * these bytes — and then says the useful thing out loud: nothing here suggests
 * the image is fake, and no software can tell you whether a photograph is
 * genuine from the file alone.
 *
 * The match case is the opposite problem. It is a strong claim and must be kept
 * exact: the bytes are unchanged since capture, and that is all. A capture
 * stored before signing completed reports separately, because calling it proven
 * would be the same overclaim the verifier refuses.
 */
@Composable
private fun ProofVerdictCard(result: ProofLookup.Result) {
    val colors = RealityLockThemeTokens.colors

    val (accent, title, body) = when (result) {
        is ProofLookup.Result.Matched ->
            if (result.signed) {
                Triple(
                    colors.pass,
                    "This is a Reality Lock capture",
                    "These bytes match capture ${result.event.eventId.take(8)}, recorded " +
                        "${result.event.metadata.timestamp.iso8601}. The image is " +
                        "unchanged since it was captured and signed. Open it in History " +
                        "to check the full proof.",
                )
            } else {
                Triple(
                    colors.warn,
                    "A capture of ours, but not signed",
                    "These bytes match capture ${result.event.eventId.take(8)}, but that " +
                        "capture carries no signature, so there is nothing to verify it " +
                        "against.",
                )
            }

        ProofLookup.Result.NoProof -> Triple(
            colors.neutral,
            "No Reality Lock proof for this image",
            "This app did not capture this image, so it cannot vouch for where or " +
                "when it came from. That is not a sign the image is fake — it is the " +
                "normal result for any photo taken with another app. Nothing below " +
                "can tell you whether a photograph is genuine.",
        )

        is ProofLookup.Result.Unreadable -> Triple(
            colors.unavailable,
            "Could not check this image",
            "${result.reason}. The check did not run, which is different from it " +
                "finding nothing.",
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Text(body, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
    }
}
