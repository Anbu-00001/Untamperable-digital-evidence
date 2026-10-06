package com.realitylock.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
    val infoSoft: Color,
    /**
     * Decorative only — brand gradients and backdrop glow. Never a status colour.
     * Violet is deliberately NOT used for [accent]: `unknown` owns that hue, and a
     * brand accent in the same hue would make "this app cannot read this check"
     * look like decoration.
     */
    val accent: Color,
    val glowA: Color,
    val glowB: Color,
)

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
    accent = Color(0xFF2563EB),
    glowA = Color(0xFF22D3EE),
    glowB = Color(0xFF3B82F6),
)

/**
 * The dark "cyber-security" palette — the one the app actually ships.
 *
 * Deep navy surfaces with a cyan -> electric-blue brand gradient. Every STATUS
 * colour keeps its meaning from the original design (green pass, rose fail,
 * amber incomplete, grey-blue unavailable, violet unknown); only their lightness
 * was tuned to glow on navy while staying above 4.5:1 against [surface].
 */
private val DarkColors = RealityLockColors(
    bg = Color(0xFF060A18),
    surface = Color(0xFF0E1630),
    surfaceAlt = Color(0xFF152045),
    border = Color(0xFF263261),
    ink = Color(0xFFE9EFFF),
    inkMuted = Color(0xFF8A97C0),
    primary = Color(0xFF22D3EE),
    primaryText = Color(0xFF03131A),
    primarySoft = Color(0xFF0A3140),
    pass = Color(0xFF34D399),
    passSoft = Color(0xFF0A3328),
    fail = Color(0xFFFB7185),
    failSoft = Color(0xFF42182A),
    warn = Color(0xFFFBBF24),
    warnSoft = Color(0xFF3B2B08),
    unknown = Color(0xFFA78BFA),
    unknownSoft = Color(0xFF2B2252),
    unavailable = Color(0xFF93A3C8),
    unavailableSoft = Color(0xFF1A2548),
    neutral = Color(0xFF8A97C0),
    neutralSoft = Color(0xFF18224A),
    info = Color(0xFF60A5FA),
    infoSoft = Color(0xFF112B55),
    accent = Color(0xFF3B82F6),
    glowA = Color(0xFF22D3EE),
    glowB = Color(0xFF7C3AED),
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
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(darkTheme),
            typography = CyberTypography,
        ) {
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

/**
 * Bolder headings and tracked-out labels, so the screens read as "dashboard"
 * rather than "form". Body sizes are untouched: legibility at arm's length is the
 * one thing this must not trade away.
 */
private val CyberTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    )
}

/** Shorthand: `RealityLockTheme.colors.pass`. */
object RealityLockThemeTokens {
    val colors: RealityLockColors
        @Composable @ReadOnlyComposable get() = LocalRealityLockColors.current
}
