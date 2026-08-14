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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.realitylock.app.forensics.PlainLanguage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.forensics.ExifAnalyzer
import com.realitylock.app.forensics.DeepfakeClassifier
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

    // Defaults ON, which is the whole point: the reader who needs the explanation
    // is exactly the reader who would not know to go looking for a switch. Someone
    // fluent in ELA can turn it off once; someone who is not would otherwise be
    // left inventing a meaning for "max error 47", and the meaning people invent
    // on a screen about faked photographs is never the reassuring one.
    //
    // rememberSaveable so the choice survives rotation and process death — having
    // to turn it off again after every rotation would be its own small insult.
    var plainEnglish by rememberSaveable { mutableStateOf(true) }

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
                PlainEnglishToggle(
                    checked = plainEnglish,
                    onCheckedChange = { plainEnglish = it },
                )
                state.classifier?.let { ClassifierCard(it, plainEnglish) }
                // The disclaimer sits here, immediately above the heuristics it
                // qualifies, rather than at the top of the screen. It used to be
                // first because ELA was first; now the definite answer leads and
                // the caveat belongs with the thing being caveated.
                DisclaimerCard()
                ReportView(state.report!!, plainEnglish)
                if (plainEnglish) GlossaryCard()
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

/**
 * The switch that turns every technical line on this screen into ordinary English.
 *
 * A single screen-level control rather than a "what does this mean?" expander on
 * each section. Per-section expanders sound more discoverable and are not: they
 * ask the reader to admit, one section at a time, that they did not follow it.
 * One switch asks once.
 */
@Composable
private fun PlainEnglishToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = RealityLockThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.analyze_plain_english),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * One plain-English passage, set apart from the finding it explains.
 *
 * Indented and muted on purpose: it must read as an aside about the result rather
 * than as another result. Same-weight body text next to a measurement is how you
 * end up with people quoting the explanation as though it were a finding.
 */
@Composable
private fun Explainer(text: String) {
    val colors = RealityLockThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceAlt)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
    }
}

/** The handful of words the screen uses that a reader has no reason to know. */
@Composable
private fun GlossaryCard() {
    val colors = RealityLockThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceAlt)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.analyze_glossary_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
        )
        PlainLanguage.glossary.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.term,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.ink,
                )
                Text(
                    entry.meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun ReportView(
    report: com.realitylock.app.forensics.ForensicAnalyzer.AuthenticityReport,
    plainEnglish: Boolean,
) {
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
    if (plainEnglish) {
        Spacer(Modifier.height(4.dp))
        Explainer(
            PlainLanguage.ela(
                resaveQuality = report.ela.resaveQuality,
                maxError = report.ela.maxError,
                meanError = report.ela.meanError,
            ),
        )
    }

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
            // Each flag gets its explanation directly beneath it rather than
            // pooled at the end. A flag reads as an accusation until you know
            // how ordinary its innocent causes are, and that has to arrive
            // attached to the specific flag, not several lines later.
            if (plainEnglish) {
                Explainer(PlainLanguage.exifFlag(finding.code))
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    // Descriptive facts (never framed as a verdict).
    ExifFacts(report.exif)
    Text(stringResource(R.string.analyze_exif_note), style = MaterialTheme.typography.bodySmall)
    if (plainEnglish) {
        Spacer(Modifier.height(4.dp))
        Explainer(PlainLanguage.exif(report.exif))
    }
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

/**
 * The experimental Meso-4 result (ADR-0010).
 *
 * ## Why it is worded this defensively
 *
 * This card sits on the same screen as cryptographic results that are exact. A
 * reader who has just been told "these bytes are unchanged since capture, signed
 * by hardware-backed key X" will carry that register straight into whatever
 * appears next. So this section has to work against its own surroundings:
 *
 *  - **No verdict.** No "fake", no "real", no percentage presented as a
 *    probability of manipulation. The raw score is shown as a number, and the
 *    band describes where it fell — not what it means.
 *  - **The accuracy line is not fine print.** No accuracy has been measured on
 *    this project's data, and that fact is given the same weight as the score.
 *  - **"Does not affect any proof" is stated explicitly**, because proximity on a
 *    screen implies relationship, and there is none.
 *
 * The no-face case is the common one and reads as a plain statement, not a
 * failure: this model only means anything on faces, so silence is the correct
 * output for a photograph of a street.
 */
@Composable
private fun ClassifierCard(outcome: DeepfakeClassifier.Outcome, plainEnglish: Boolean) {
    val colors = RealityLockThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceAlt)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.analyze_classifier_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
        )

        when (outcome) {
            is DeepfakeClassifier.Outcome.Scored -> {
                val (accent, bandLabel) = when (outcome.score.band) {
                    DeepfakeClassifier.Band.LEANS_UNMANIPULATED ->
                        colors.pass to stringResource(R.string.analyze_classifier_band_unmanipulated)
                    DeepfakeClassifier.Band.LEANS_MANIPULATED ->
                        colors.warn to stringResource(R.string.analyze_classifier_band_manipulated)
                    DeepfakeClassifier.Band.INCONCLUSIVE ->
                        colors.unknown to stringResource(R.string.analyze_classifier_band_inconclusive)
                }
                Text(bandLabel, style = MaterialTheme.typography.bodyMedium, color = accent)
                Text(
                    stringResource(
                        R.string.analyze_classifier_score,
                        "%.3f".format(outcome.score.realScore),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.inkMuted,
                )
                if (plainEnglish) Explainer(PlainLanguage.classifierScore(outcome.score.realScore))
            }

            DeepfakeClassifier.Outcome.NoFace -> {
                Text(
                    stringResource(R.string.analyze_classifier_no_face),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                if (plainEnglish) Explainer(PlainLanguage.classifierNoFace())
            }

            is DeepfakeClassifier.Outcome.Unavailable -> Text(
                stringResource(R.string.analyze_classifier_unavailable, outcome.reason),
                style = MaterialTheme.typography.bodySmall,
                color = colors.unavailable,
            )
        }

        Text(
            stringResource(R.string.analyze_classifier_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
    }
}
