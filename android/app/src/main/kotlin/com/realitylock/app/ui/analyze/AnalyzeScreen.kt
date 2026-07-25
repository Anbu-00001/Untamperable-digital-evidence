package com.realitylock.app.ui.analyze

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DisclaimerCard()

        Button(
            onClick = { picker.launch("image/*") },
            enabled = !state.analyzing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.analyze_pick_image))
        }

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

            state.report != null -> ReportView(state.report!!)
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
