package com.realitylock.app.ui.verify

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.verify.VerificationReport

/**
 * The "Authenticity Result" surface from Slide 9 — a verdict **and** the
 * per-check breakdown that produced it.
 *
 * The breakdown is not an expandable detail view; it is the substance. A single
 * "Valid / Tampered" badge would hide the difference between "the media was
 * altered" and "the backend has no copy of the media to check", which is the
 * difference between an accusation and an unanswered question.
 *
 * The limitations block is always rendered, including on a `VERIFIED` result, so
 * the screen can never be screenshotted as proof of more than it establishes.
 */
@Composable
fun AuthenticityResultPanel(
    report: VerificationReport,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            // No scroll of its own: this panel is placed inside the history
            // list, and a nested scroll container would fight the parent.
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.verify_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.verify_close)) }
            }

            Text(
                stringResource(report.verdict.labelRes()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = report.verdict.tint(),
            )
            Text(
                stringResource(report.verdict.bodyRes()),
                style = MaterialTheme.typography.bodyMedium,
            )

            report.merkleRoot?.let { root ->
                Text(
                    root,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(2.dp))
            SectionTitle(R.string.verify_checks_title)
            for (check in report.checks) {
                CheckRow(check)
            }

            if (report.advisories.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                SectionTitle(R.string.verify_advisories_title)
                for (advisory in report.advisories) {
                    Text("• $advisory", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (report.notes.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                SectionTitle(R.string.verify_notes_title)
                for (note in report.notes) {
                    Text("• $note", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Never conditional. A passing verdict is exactly when a reader is
            // most likely to over-read it.
            if (report.limitations.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SectionTitle(R.string.verify_limitations_title)
                        for (limitation in report.limitations) {
                            Text("• $limitation", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun CheckRow(check: VerificationReport.Check) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            checkLabel(check.name),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            stringResource(check.outcome.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (check.outcome == VerificationReport.Outcome.FAIL) {
                FontWeight.Bold
            } else {
                FontWeight.Normal
            },
            color = check.outcome.tint(),
        )
    }
}

/**
 * Maps a backend check key to its English label.
 *
 * An explicit mapping rather than a `getResources().getIdentifier()` lookup on
 * the key: identifier lookup is discouraged (it defeats resource shrinking and
 * fails silently), and being explicit makes the set of checks the UI knows about
 * greppable.
 *
 * An unrecognised key falls through to the key itself rather than being dropped —
 * a newer backend reporting an extra check must still be visible, even unlabelled.
 */
@Composable
fun checkLabel(name: String): String {
    val res = when (name) {
        "schemaValid" -> R.string.check_schemaValid
        "mediaHashMatch" -> R.string.check_mediaHashMatch
        "metadataHashMatch" -> R.string.check_metadataHashMatch
        "merkleRootMatch" -> R.string.check_merkleRootMatch
        "signatureValid" -> R.string.check_signatureValid
        "attestationPresent" -> R.string.check_attestationPresent
        "attestationChainValid" -> R.string.check_attestationChainValid
        "attestationKeyBinding" -> R.string.check_attestationKeyBinding
        "timestampPlausible" -> R.string.check_timestampPlausible
        "locationPlausible" -> R.string.check_locationPlausible
        else -> null
    }
    return res?.let { stringResource(it) } ?: name
}

@StringRes
private fun VerificationReport.Verdict.labelRes(): Int = when (this) {
    VerificationReport.Verdict.VERIFIED -> R.string.verify_verdict_verified
    VerificationReport.Verdict.FAILED -> R.string.verify_verdict_failed
    VerificationReport.Verdict.INCOMPLETE -> R.string.verify_verdict_incomplete
    VerificationReport.Verdict.INVALID_FORMAT -> R.string.verify_verdict_invalid_format
    VerificationReport.Verdict.UNKNOWN -> R.string.verify_verdict_unknown
}

@StringRes
private fun VerificationReport.Verdict.bodyRes(): Int = when (this) {
    VerificationReport.Verdict.VERIFIED -> R.string.verify_verdict_verified_body
    VerificationReport.Verdict.FAILED -> R.string.verify_verdict_failed_body
    VerificationReport.Verdict.INCOMPLETE -> R.string.verify_verdict_incomplete_body
    VerificationReport.Verdict.INVALID_FORMAT -> R.string.verify_verdict_invalid_format_body
    VerificationReport.Verdict.UNKNOWN -> R.string.verify_verdict_unknown_body
}

@StringRes
private fun VerificationReport.Outcome.labelRes(): Int = when (this) {
    VerificationReport.Outcome.PASS -> R.string.verify_outcome_pass
    VerificationReport.Outcome.FAIL -> R.string.verify_outcome_fail
    VerificationReport.Outcome.UNAVAILABLE -> R.string.verify_outcome_unavailable
    VerificationReport.Outcome.UNKNOWN -> R.string.verify_outcome_unknown
}

/**
 * `UNAVAILABLE` is deliberately neutral grey, not amber or red: it means "not
 * checkable", and colouring it as a warning would nudge a reader towards reading
 * absence of evidence as evidence.
 */
@Composable
private fun VerificationReport.Outcome.tint(): Color = when (this) {
    VerificationReport.Outcome.PASS -> MaterialTheme.colorScheme.primary
    VerificationReport.Outcome.FAIL -> MaterialTheme.colorScheme.error
    VerificationReport.Outcome.UNAVAILABLE,
    VerificationReport.Outcome.UNKNOWN,
    -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun VerificationReport.Verdict.tint(): Color = when (this) {
    VerificationReport.Verdict.VERIFIED -> MaterialTheme.colorScheme.primary
    VerificationReport.Verdict.FAILED,
    VerificationReport.Verdict.INVALID_FORMAT,
    -> MaterialTheme.colorScheme.error
    VerificationReport.Verdict.INCOMPLETE,
    VerificationReport.Verdict.UNKNOWN,
    -> MaterialTheme.colorScheme.onSurfaceVariant
}
