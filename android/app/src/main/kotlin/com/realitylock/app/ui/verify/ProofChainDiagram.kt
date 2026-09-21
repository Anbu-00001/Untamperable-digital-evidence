package com.realitylock.app.ui.verify

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.verify.VerificationReport
import com.realitylock.app.verify.VerificationReport.Outcome

/**
 * One link of the chain a proof package stands on, and the check that tested it.
 *
 * The chain is the actual construction in `crypto/MerkleTree.kt` (ADR-0001):
 * photo digest and record digest combine into the Merkle root, the root is what
 * the key signs, and attestation says where that key lived. Drawing it lets a
 * reader see *which* link broke instead of only that something did.
 */
data class ChainLink(val label: String, val caption: String, val outcome: Outcome)

/**
 * The five links, each coloured by a real check result — never decoratively.
 *
 * A link whose check the report does not contain is [Outcome.UNAVAILABLE]: the
 * diagram may never show a link as tested when nothing tested it. The hardware
 * link takes the WORST outcome of the whole attestation group, by the same
 * max-over-severity rule the group cards use, so it can only ever over-warn.
 */
fun proofChainLinks(report: VerificationReport): List<ChainLink> {
    val byName = report.checks.associate { it.name to it.outcome }
    fun outcome(name: String) = byName[name] ?: Outcome.UNAVAILABLE
    val attestation = groupChecks(report.checks)
        .firstOrNull { it.id == CheckGroupId.ATTESTATION }
        ?.state
        ?: Outcome.UNAVAILABLE
    return listOf(
        ChainLink("Photo", "hash", outcome("mediaHashMatch")),
        ChainLink("Record", "hash", outcome("metadataHashMatch")),
        ChainLink("Root", "Merkle", outcome("merkleRootMatch")),
        ChainLink("Signed", "ECDSA", outcome("signatureValid")),
        ChainLink("Key", "hardware", attestation),
    )
}

/** How many reveal steps the diagram consumes, so the group cards can follow it. */
const val PROOF_CHAIN_REVEAL_STEPS: Int = 5

/**
 * Photo + Record → Root → Signed → Key, drawn as status-coloured nodes.
 *
 * [revealed] is how many links are visible yet. The animation is a staged REVEAL
 * of results the verifier has already returned: each link appears already in its
 * final state, and nothing is ever drawn as "checking" or passes through a state
 * it does not have. An unrevealed link is simply absent, not a neutral pill.
 */
@Composable
fun ProofChainDiagram(
    report: VerificationReport,
    revealed: Int,
    modifier: Modifier = Modifier,
) {
    val colors = RealityLockThemeTokens.colors
    val links = proofChainLinks(report)
    val description = links.joinToString("; ") { link ->
        "${link.label} ${link.caption}: ${link.outcome.name.lowercase()}"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Proof chain. $description" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "How the proof holds together",
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
        )
        Text(
            "Each link is coloured by the check that tested it.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The two leaves stack: they are siblings that combine, not a sequence.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChainNode(links[0], visible = revealed > 0)
                ChainNode(links[1], visible = revealed > 1)
            }
            for (index in 2 until links.size) {
                Connector(visible = revealed > index)
                ChainNode(links[index], visible = revealed > index)
            }
        }
    }
}

@Composable
private fun Connector(visible: Boolean) {
    val colors = RealityLockThemeTokens.colors
    Text(
        "→",
        color = if (visible) colors.inkMuted else colors.border,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ChainNode(link: ChainLink, visible: Boolean) {
    val colors = RealityLockThemeTokens.colors
    // Fixed footprint whether or not the node is visible yet, so the row does not
    // reflow while the reveal plays.
    Box(modifier = Modifier.width(54.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = visible, enter = fadeIn() + scaleIn(initialScale = 0.6f)) {
            val style = link.outcome.style()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(style.bg, CircleShape)
                        .border(1.5.dp, style.fg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        style.glyph,
                        color = style.fg,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    link.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                )
                // The outcome word as well as the colour: never colour alone (ADR-0008).
                Text(
                    stringResource(style.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = style.fg,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}
