package com.realitylock.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colour tokens from the Reality Lock design project, translated for Compose.
 *
 * ## Where these numbers come from
 *
 * The design defines every colour in **oklch**, which CSS understands and Compose
 * does not. Each value below was converted through OKLab -> linear sRGB -> gamma
 * encoding, and the original oklch triple is kept in a trailing comment on every
 * line so a colour can be checked against the design without guesswork. The
 * conversion was scripted, not eyeballed: there are 46 of these, and a hand-typed
 * hex table is a transcription bug waiting to happen.
 *
 * ## Why a separate palette rather than Material3's ColorScheme
 *
 * `ColorScheme` has no slot for the thing this app most needs to express: a check
 * outcome. Reality Lock reports **four** distinct states -- pass, fail,
 * unavailable and unknown -- and Material's error/primary/surface vocabulary
 * cannot carry that without one of them being dishonestly mapped onto another.
 * `unavailable` ("we could not run this check") and `fail` ("this check proved a
 * problem") are the pair that must never collapse; ADR-0006 SS5 exists because
 * absence of evidence is not evidence of a defect.
 *
 * `unknown` is a fourth state on purpose: a newer backend can report a check this
 * app version does not recognise, and the honest rendering is a distinct colour
 * plus a label saying so -- never a silent fold into pass or fail.
 */
data class RealityLockColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val ink: Color,
    val inkMuted: Color,
    val primary: Color,
    val primaryText: Color,
    val primarySoft: Color,
    val pass: Color,
    val passSoft: Color,
    val fail: Color,
    val failSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val unknown: Color,
    val unknownSoft: Color,
    val unavailable: Color,
    val unavailableSoft: Color,
    val neutral: Color,
    val neutralSoft: Color,
    val info: Color,
    val infoSoft: Color,)

private val LightColors = RealityLockColors(
    bg = Color(0xFFF6F9FB),  // oklch(98% 0.004 250)
    surface = Color(0xFFFCFDFF),  // oklch(99.5% 0.002 250)
    surfaceAlt = Color(0xFFEBEFF2),  // oklch(95% 0.006 250)
    border = Color(0xFFD4D8DD),  // oklch(88% 0.008 250)
    ink = Color(0xFF1B2025),  // oklch(24% 0.012 250)
    inkMuted = Color(0xFF595E64),  // oklch(48% 0.012 250)
    primary = Color(0xFF006980),  // oklch(46% 0.13 210)
    primaryText = Color(0xFFFCFCFC),  // oklch(99% 0 0)
    primarySoft = Color(0xFFD2EEF3),  // oklch(93% 0.03 210)
    pass = Color(0xFF207029),  // oklch(48% 0.13 145)
    passSoft = Color(0xFFDBF3DB),  // oklch(94% 0.04 145)
    fail = Color(0xFFBE222A),  // oklch(52% 0.19 25)
    failSoft = Color(0xFFFFE0DC),  // oklch(94% 0.045 25)
    warn = Color(0xFF9A6500),  // oklch(55% 0.12 75)
    warnSoft = Color(0xFFFFECCD),  // oklch(95% 0.045 80)
    unknown = Color(0xFF6C44A4),  // oklch(48% 0.15 300)
    unknownSoft = Color(0xFFF2EAFF),  // oklch(95% 0.035 300)
    unavailable = Color(0xFF66696C),  // oklch(52% 0.006 250)
    unavailableSoft = Color(0xFFE5E8EC),  // oklch(93% 0.006 250)
    neutral = Color(0xFF595E64),  // oklch(48% 0.012 250)
    neutralSoft = Color(0xFFE8EBEF),  // oklch(94% 0.006 250)
    info = Color(0xFF006980),  // oklch(46% 0.13 210)
    infoSoft = Color(0xFFD2EEF3),  // oklch(93% 0.03 210)
)

private val DarkColors = RealityLockColors(
    bg = Color(0xFF0F1215),  // oklch(18% 0.007 250)
    surface = Color(0xFF1A1D21),  // oklch(23% 0.009 250)
    surfaceAlt = Color(0xFF23272B),  // oklch(27% 0.01 250)
    border = Color(0xFF363B41),  // oklch(35% 0.012 250)
    ink = Color(0xFFE6E8EA),  // oklch(93% 0.004 250)
    inkMuted = Color(0xFF9A9FA5),  // oklch(70% 0.01 250)
    primary = Color(0xFF30C2D8),  // oklch(75% 0.12 210)
    primaryText = Color(0xFF070C0D),  // oklch(15% 0.01 210)
    primarySoft = Color(0xFF03343C),  // oklch(30% 0.05 210)
    pass = Color(0xFF6BC670),  // oklch(75% 0.15 145)
    passSoft = Color(0xFF133015),  // oklch(28% 0.06 145)
    fail = Color(0xFFFD736D),  // oklch(72% 0.17 25)
    failSoft = Color(0xFF4B1D1B),  // oklch(30% 0.07 25)
    warn = Color(0xFFE9B452),  // oklch(80% 0.13 80)
    warnSoft = Color(0xFF402C05),  // oklch(31% 0.06 80)
    unknown = Color(0xFFBCA1ED),  // oklch(76% 0.11 300)
    unknownSoft = Color(0xFF322745),  // oklch(30% 0.055 300)
    unavailable = Color(0xFF9C9FA2),  // oklch(70% 0.006 250)
    unavailableSoft = Color(0xFF2A2E33),  // oklch(30% 0.01 250)
    neutral = Color(0xFF9A9FA5),  // oklch(70% 0.01 250)
    neutralSoft = Color(0xFF26292D),  // oklch(28% 0.008 250)
    info = Color(0xFF30C2D8),  // oklch(75% 0.12 210)
    infoSoft = Color(0xFF03343C),  // oklch(30% 0.05 210)
)

/**
 * No default. A composable reading these outside [RealityLockTheme] is a bug, and
 * a silent fallback palette would hide it until it reached a screenshot.
 */
val LocalRealityLockColors = staticCompositionLocalOf<RealityLockColors> {
    error("RealityLockColors requested outside RealityLockTheme")
}

/** Wraps MaterialTheme and adds the status palette above. */
@Composable
fun RealityLockTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalRealityLockColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterialScheme(darkTheme)) {
            content()
        }
    }
}

/**
 * Projects the design palette onto Material's `ColorScheme`.
 *
 * Without this the theme provided the status tokens and then handed Material a
 * stock `lightColorScheme()` / `darkColorScheme()` — so the app rendered in
 * Material's default purple, and the design only appeared on the handful of
 * screens that read the tokens directly. Every `Button`, `Card`, `TabRow`,
 * `Divider` and default `Text` in the app takes its colour from `ColorScheme`,
 * so this mapping is what actually makes the redesign visible.
 *
 * Only the roles the app uses are mapped; the rest keep Material's derived
 * defaults. Mapping a role to a token that was never designed for it would look
 * arbitrary, and Material's own derivation is a better guess than mine.
 */
private fun RealityLockColors.toMaterialScheme(darkTheme: Boolean) =
    (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
        primary = primary,
        onPrimary = primaryText,
        primaryContainer = primarySoft,
        onPrimaryContainer = primary,
        secondary = info,
        onSecondary = primaryText,
        background = bg,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        // Cards and chips sit on this; `surfaceAlt` is the design's own
        // second-level surface, so the elevation story stays the designer's.
        surfaceVariant = surfaceAlt,
        onSurfaceVariant = inkMuted,
        outline = border,
        outlineVariant = border,
        // `fail` rather than Material's red: an error in this app is the same
        // state a failed check reports, and two different reds would imply two
        // different meanings.
        error = fail,
        onError = primaryText,
        errorContainer = failSoft,
        onErrorContainer = fail,
    )

/** Shorthand: `RealityLockTheme.colors.pass`. */
object RealityLockThemeTokens {
    val colors: RealityLockColors
        @Composable @ReadOnlyComposable get() = LocalRealityLockColors.current
}
