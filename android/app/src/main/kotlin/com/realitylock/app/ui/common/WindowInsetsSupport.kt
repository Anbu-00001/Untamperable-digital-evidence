package com.realitylock.app.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * Window-inset helpers shared by every scrolling surface in the app.
 *
 * ## Why any of this exists
 *
 * The app targets SDK 36, and Android 15 (SDK 35) onwards **enforces
 * edge-to-edge**: the activity draws behind the status and navigation bars
 * whether or not it asks to, and the old opt-out
 * (`windowOptOutEdgeToEdgeEnforcement`) is gone. An app that handles no insets
 * does not get a safe layout by default — it gets content underneath the system
 * bars. On the project's CPH2591 that showed up as the last of the capture
 * details sitting under the gesture back button, unreachable because no amount
 * of scrolling could bring it out from behind the bar.
 *
 * ## The rule this file encodes
 *
 * Insets are split by the kind of element that consumes them:
 *
 * - **Fixed chrome** (the title and the tab row) takes the TOP and HORIZONTAL
 *   insets as ordinary padding. There is nothing to scroll under a status bar,
 *   so keeping chrome clear of it is simply correct.
 * - **Scrolling content** takes the BOTTOM inset as *content* padding —
 *   [scrollableBottomInset] — never as padding on the scroll container itself.
 *
 * That second distinction is the whole point, and it is easy to get backwards.
 * Padding the container shrinks the viewport: it carves out a dead strip that
 * never scrolls, so content still collides with the bar on its way past and the
 * screen loses height permanently. Padding the *content* leaves the viewport
 * full-height and running behind the translucent bar, so the last item scrolls
 * up until it clears the bar and then stops. That is the behaviour users expect
 * from an edge-to-edge app, and it is the one that actually fixes the bug.
 *
 * ## Horizontal insets, not just the bottom
 *
 * [safeDrawing] is used rather than `navigationBars` because the navigation bar
 * is NOT always at the bottom: in landscape with three-button navigation it sits
 * on one side, where `calculateBottomPadding()` reports zero and content would
 * quietly slide underneath it. `safeDrawing` also covers display cutouts, which
 * land on the side in landscape for the same reason.
 */

/**
 * The bottom inset a scrolling container must add to its **content** so the last
 * item can be scrolled clear of the navigation bar.
 *
 * Reads what is *left* rather than the raw system inset: `Modifier
 * .windowInsetsPadding` consumes whatever it applies, so once the chrome above
 * has taken the top and horizontal sides, this reports only the bottom. Callers
 * therefore cannot double-count, and this stays correct no matter how deeply the
 * screen is nested.
 */
@Composable
fun scrollableBottomInset(): Dp =
    WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom)
        .asPaddingValues()
        .calculateBottomPadding()

/**
 * The insets fixed chrome should consume: the top bar area plus both sides.
 *
 * Deliberately excludes the bottom. That one belongs to whichever scrollable is
 * on screen, via [scrollableBottomInset] — taking it here instead would reserve
 * a permanent dead strip above the navigation bar and undo the fix.
 */
val chromeInsets: WindowInsets
    @Composable get() =
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
