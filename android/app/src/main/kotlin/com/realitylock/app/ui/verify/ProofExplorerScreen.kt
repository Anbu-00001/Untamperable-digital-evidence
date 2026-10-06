package com.realitylock.app.ui.verify

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.store.EventSerializer
import com.realitylock.app.crypto.MetadataCanonicalizer
import com.realitylock.app.ui.common.scrollableBottomInset
import com.realitylock.app.ui.components.ChipFlow
import com.realitylock.app.ui.components.GlassCard
import com.realitylock.app.ui.components.GradientButton
import com.realitylock.app.ui.components.IconBadge
import com.realitylock.app.ui.components.InfoChip
import com.realitylock.app.ui.components.NoticeChip
import com.realitylock.app.ui.components.RlIcons
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import com.realitylock.app.verify.TamperSimulator
import com.realitylock.app.verify.TamperSimulator.Target
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NodeId { KEY, SIGNATURE, ROOT, PHOTO, RECORD }

private enum class Visual { NEUTRAL, OK, BROKEN }

/**
 * The proof, drawn: a photo hash and a record hash combine into a Merkle root,
 * the root is signed, the signature comes from a hardware key. Tap any node for
 * its full hash; press "flip one bit" to watch what tampering does.
 *
 * On open it re-checks the untouched package on this phone — real SHA-256, a real
 * ECDSA verification — and colours the nodes from that, so a green node means
 * "recomputed just now and it matched", not "drawn green". The key node stays
 * neutral: whether its attestation chain leads to Google is a question for the
 * backend's Verify, and this screen does not pretend to answer it.
 */
@Composable
fun ProofExplorerScreen(event: CapturedEvent, onClose: () -> Unit) {
    val c = RealityLockThemeTokens.colors
    val merkle = event.merkle
    val signature = event.signature
    val scope = rememberCoroutineScope()

    var mediaBytes by remember(event.eventId) { mutableStateOf<ByteArray?>(null) }
    var baseline by remember(event.eventId) { mutableStateOf<TamperSimulator.Baseline?>(null) }
    var outcome by remember(event.eventId) { mutableStateOf<TamperSimulator.Outcome?>(null) }
    var target by remember(event.eventId) { mutableStateOf(Target.PHOTO) }
    var selected by remember(event.eventId) { mutableStateOf(NodeId.ROOT) }
    var working by remember(event.eventId) { mutableStateOf(false) }

    val canonical = remember(event.eventId) {
        runCatching { MetadataCanonicalizer.canonicalize(EventSerializer.metadataJson(event.metadata)) }
            .getOrNull()
    }
    val claims = remember(event.eventId) {
        if (merkle == null || signature == null) null else TamperSimulator.Claims(
            mediaLeafHex = merkle.leaves.media,
            metadataLeafHex = merkle.leaves.metadata,
            rootHex = merkle.root,
            signatureBase64 = signature.value,
            publicKeyBase64 = signature.publicKey.value,
        )
    }

    LaunchedEffect(event.eventId) {
        if (claims == null || canonical == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val bytes = runCatching { File(event.mediaFilePath).readBytes() }.getOrNull()
            mediaBytes = bytes
            baseline = TamperSimulator.baseline(claims, bytes, canonical)
        }
    }

    fun visual(node: NodeId): Visual {
        val sim = outcome
        if (sim != null) {
            val rootBroken = !sim.rootStillMatches
            return when (node) {
                NodeId.PHOTO -> if (sim.target == Target.PHOTO) Visual.BROKEN else baseline.leafVisual(photo = true)
                NodeId.RECORD -> if (sim.target == Target.RECORD) Visual.BROKEN else baseline.leafVisual(photo = false)
                NodeId.ROOT -> if (rootBroken) Visual.BROKEN else Visual.OK
                NodeId.SIGNATURE -> if (!sim.signatureStillValid) Visual.BROKEN else Visual.OK
                NodeId.KEY -> Visual.NEUTRAL
            }
        }
        val b = baseline ?: return Visual.NEUTRAL
        return when (node) {
            NodeId.PHOTO -> baseline.leafVisual(photo = true)
            NodeId.RECORD -> baseline.leafVisual(photo = false)
            NodeId.ROOT -> if (b.rootOk) Visual.OK else Visual.BROKEN
            NodeId.SIGNATURE -> if (b.signatureOk) Visual.OK else Visual.BROKEN
            NodeId.KEY -> Visual.NEUTRAL
        }
    }

    fun hashOf(node: NodeId): String? = when (node) {
        NodeId.PHOTO -> outcome?.takeIf { it.target == Target.PHOTO }?.tamperedLeafHex ?: merkle?.leaves?.media
        NodeId.RECORD -> outcome?.takeIf { it.target == Target.RECORD }?.tamperedLeafHex ?: merkle?.leaves?.metadata
        NodeId.ROOT -> outcome?.tamperedRootHex ?: merkle?.root
        NodeId.SIGNATURE -> signature?.value?.take(64)
        NodeId.KEY -> signature?.publicKey?.value?.take(64)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(RlIcons.Close, contentDescription = stringResource(R.string.verify_close), tint = c.ink)
            }
            IconBadge(RlIcons.Tree, c.primary, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.action_explore),
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = scrollableBottomInset() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (merkle == null || signature == null || claims == null) {
                NoticeChip(
                    RlIcons.Warn,
                    stringResource(R.string.explorer_unsigned),
                    stringResource(R.string.explorer_unsigned_full),
                    c.warn,
                    startExpanded = true,
                )
                return@Column
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TreeNode(NodeId.KEY, RlIcons.Key, stringResource(R.string.explorer_key), visual(NodeId.KEY), selected == NodeId.KEY) { selected = NodeId.KEY }
                    Edge(visual(NodeId.SIGNATURE))
                    TreeNode(NodeId.SIGNATURE, RlIcons.Sign, stringResource(R.string.explorer_signature), visual(NodeId.SIGNATURE), selected == NodeId.SIGNATURE) { selected = NodeId.SIGNATURE }
                    Edge(visual(NodeId.ROOT))
                    TreeNode(NodeId.ROOT, RlIcons.Tree, stringResource(R.string.explorer_root), visual(NodeId.ROOT), selected == NodeId.ROOT) { selected = NodeId.ROOT }
                    Branch(visual(NodeId.PHOTO), visual(NodeId.RECORD))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            TreeNode(NodeId.PHOTO, RlIcons.Photo, stringResource(R.string.explorer_photo), visual(NodeId.PHOTO), selected == NodeId.PHOTO) { selected = NodeId.PHOTO }
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            TreeNode(NodeId.RECORD, RlIcons.Record, stringResource(R.string.explorer_record), visual(NodeId.RECORD), selected == NodeId.RECORD) { selected = NodeId.RECORD }
                        }
                    }
                }
            }

            // ---- the selected node's full hash -------------------------------
            GlassCard(Modifier.fillMaxWidth(), accent = c.info) {
                Text(
                    stringResource(selected.meaningRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
                hashOf(selected)?.let { hash ->
                    Spacer(Modifier.height(8.dp))
                    HashBlock(hash, compareWith = if (selected == NodeId.PHOTO && outcome?.target == Target.PHOTO) outcome?.recomputedLeafHex
                        else if (selected == NodeId.RECORD && outcome?.target == Target.RECORD) outcome?.recomputedLeafHex else null)
                }
            }

            // ---- try to tamper ----------------------------------------------
            GlassCard(Modifier.fillMaxWidth(), accent = c.fail) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(RlIcons.Bolt, c.fail, size = 34.dp)
                    Text(
                        stringResource(R.string.explorer_tamper_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = c.ink,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetPill(RlIcons.Photo, stringResource(R.string.explorer_photo), target == Target.PHOTO, mediaBytes != null, Modifier.weight(1f)) {
                        target = Target.PHOTO; outcome = null
                    }
                    TargetPill(RlIcons.Record, stringResource(R.string.explorer_record), target == Target.RECORD, true, Modifier.weight(1f)) {
                        target = Target.RECORD; outcome = null
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradientButton(
                        text = stringResource(R.string.explorer_flip),
                        icon = RlIcons.Bolt,
                        enabled = !working && canonical != null && (target == Target.RECORD || mediaBytes != null),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            working = true
                            scope.launch {
                                outcome = withContext(Dispatchers.Default) {
                                    TamperSimulator.simulate(target, claims, mediaBytes, canonical.orEmpty())
                                }
                                selected = if (target == Target.PHOTO) NodeId.PHOTO else NodeId.RECORD
                                working = false
                            }
                        },
                    )
                    if (outcome != null) {
                        IconButton(onClick = { outcome = null }) {
                            Icon(RlIcons.Refresh, contentDescription = stringResource(R.string.explorer_reset), tint = c.primary)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                NoticeChip(
                    RlIcons.Info,
                    stringResource(R.string.explorer_sim_short),
                    stringResource(R.string.explorer_sim_full),
                    c.info,
                )
            }

            AnimatedVisibility(
                visible = outcome != null,
                enter = fadeIn(tween(250)) + slideInVertically { it / 4 },
            ) {
                outcome?.let { sim -> OutcomeCard(sim) }
            }
        }
    }
}

@Composable
private fun OutcomeCard(sim: TamperSimulator.Outcome) {
    val c = RealityLockThemeTokens.colors
    GlassCard(Modifier.fillMaxWidth(), accent = c.fail) {
        ChipFlow {
            InfoChip(RlIcons.Bolt, stringResource(R.string.explorer_bit, sim.flippedBit + 1, sim.totalBits), c.warn)
            InfoChip(RlIcons.Hash, stringResource(R.string.explorer_digits_changed, sim.changedDigits), c.fail)
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.explorer_before), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        HashBlock(sim.recomputedLeafHex)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.explorer_after), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        HashBlock(sim.tamperedLeafHex, compareWith = sim.recomputedLeafHex)
        Spacer(Modifier.height(12.dp))
        // The consequences, one chip each — computed, not asserted.
        ChipFlow {
            InfoChip(RlIcons.Fail, stringResource(R.string.explorer_leaf_changed), c.fail)
            InfoChip(
                if (sim.rootStillMatches) RlIcons.Pass else RlIcons.Fail,
                stringResource(if (sim.rootStillMatches) R.string.explorer_root_same else R.string.explorer_root_changed),
                if (sim.rootStillMatches) c.pass else c.fail,
            )
            InfoChip(
                if (sim.signatureStillValid) RlIcons.Pass else RlIcons.Fail,
                stringResource(if (sim.signatureStillValid) R.string.explorer_sig_ok else R.string.explorer_sig_broken),
                if (sim.signatureStillValid) c.pass else c.fail,
            )
        }
    }
}

@Composable
private fun TargetPill(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = RealityLockThemeTokens.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) c.primarySoft else c.surfaceAlt, shape)
            .border(1.dp, if (selected) c.primary else c.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) c.primary else c.unavailable, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.ink else c.unavailable)
    }
}

@Composable
private fun TreeNode(id: NodeId, icon: ImageVector, title: String, visual: Visual, selected: Boolean, onClick: () -> Unit) {
    val c = RealityLockThemeTokens.colors
    val tint by animateColorAsState(visual.color(), tween(450), label = "nodeTint")
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(tint.copy(alpha = if (selected) 0.22f else 0.12f), shape)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, tint.copy(alpha = if (selected) 1f else 0.5f)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (visual == Visual.BROKEN) RlIcons.Fail else icon,
            contentDescription = null, tint = tint, modifier = Modifier.size(26.dp),
        )
        Text(title, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1)
    }
}

@Composable
private fun Edge(child: Visual) {
    val color by animateColorAsState(child.color(), tween(450), label = "edge")
    Box(Modifier.width(3.dp).height(18.dp).background(color))
}

/** The Y from the root down to its two leaves, each arm coloured by its leaf. */
@Composable
private fun Branch(left: Visual, right: Visual) {
    val l by animateColorAsState(left.color(), tween(450), label = "armL")
    val r by animateColorAsState(right.color(), tween(450), label = "armR")
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 3.dp.toPx()
        drawLine(l, Offset(w / 2f, 0f), Offset(w / 4f, h), stroke, StrokeCap.Round)
        drawLine(r, Offset(w / 2f, 0f), Offset(w * 3f / 4f, h), stroke, StrokeCap.Round)
    }
}

/** 64 hex digits as four rows of sixteen, with differing digits (vs [compareWith]) in red. */
@Composable
private fun HashBlock(hash: String, compareWith: String? = null) {
    val c = RealityLockThemeTokens.colors
    val text: AnnotatedString = buildAnnotatedString {
        hash.chunked(16).forEachIndexed { row, chunk ->
            chunk.forEachIndexed { col, ch ->
                val index = row * 16 + col
                val differs = compareWith != null && index < compareWith.length && compareWith[index] != ch
                withStyle(
                    SpanStyle(
                        color = if (differs) c.fail else c.ink,
                        fontWeight = if (differs) FontWeight.Bold else FontWeight.Normal,
                    ),
                ) { append(ch) }
            }
            if (row < hash.chunked(16).lastIndex) append('\n')
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Composable
private fun Visual.color(): Color {
    val c = RealityLockThemeTokens.colors
    return when (this) {
        Visual.OK -> c.pass
        Visual.BROKEN -> c.fail
        Visual.NEUTRAL -> c.info
    }
}

private fun TamperSimulator.Baseline?.leafVisual(photo: Boolean): Visual {
    val b = this ?: return Visual.NEUTRAL
    val ok = if (photo) b.mediaOk else b.metadataOk
    return when (ok) {
        true -> Visual.OK
        false -> Visual.BROKEN
        // The photo is not on this phone, so nothing could be recomputed.
        null -> Visual.NEUTRAL
    }
}

private fun NodeId.meaningRes(): Int = when (this) {
    NodeId.KEY -> R.string.explorer_key_meaning
    NodeId.SIGNATURE -> R.string.explorer_signature_meaning
    NodeId.ROOT -> R.string.explorer_root_meaning
    NodeId.PHOTO -> R.string.explorer_photo_meaning
    NodeId.RECORD -> R.string.explorer_record_meaning
}
