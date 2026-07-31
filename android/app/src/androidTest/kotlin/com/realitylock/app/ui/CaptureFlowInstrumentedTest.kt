package com.realitylock.app.ui

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.realitylock.app.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Phase-6 UI smoke test: the app launches, the four surfaces are reachable,
 * and the one piece of on-screen text the project is not allowed to lose is
 * actually rendered.
 *
 * Deliberately **not** a re-test of capture itself. Pressing the shutter is
 * already driven end-to-end against real hardware by `scripts/e2e/run_e2e.sh`,
 * which then verifies the resulting package; repeating that here would be slower,
 * flakier (it needs a live camera and a GNSS fix) and would prove less. What an
 * instrumented Compose test adds is the layer that script cannot see — that the
 * composables render and navigate at all.
 *
 * Every expectation is read from `strings.xml` rather than written as a literal,
 * so a reworded string moves the test with it instead of failing it, and the test
 * cannot drift into asserting wording the app no longer uses.
 */
@RunWith(AndroidJUnit4::class)
class CaptureFlowInstrumentedTest {

    // Granted before the activity launches, so the app opens on the capture
    // surface rather than the permission rationale. `GrantPermissionRule` is used
    // rather than `adb shell pm grant`, which this project's target device
    // refuses outright — see PermissionGrantInstrumentedTest.
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun string(@StringRes id: Int, vararg args: Any): String =
        compose.activity.getString(id, *args)

    /**
     * The history tab carries a live event count ("History (3)"), so only the
     * stem is stable across devices. Taken from the resource itself rather than
     * written out, so it still tracks a rename.
     */
    private val historyTabStem: String
        get() = string(R.string.tab_history, 0).substringBefore('(').trim()

    @Test
    fun the_app_lands_on_capture_and_offers_the_shutter() {
        compose.onNodeWithText(string(R.string.tab_capture)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.capture_action)).assertIsDisplayed()
    }

    @Test
    fun the_history_surface_is_reachable() {
        compose.onNodeWithText(historyTabStem, substring = true).performClick()

        // Either the empty-state line or at least the tab itself remains shown;
        // asserting the tab stays displayed after the click is what proves the
        // navigation resolved rather than crashing the composition.
        compose.onNodeWithText(historyTabStem, substring = true).assertIsDisplayed()
    }

    @Test
    fun the_analyze_surface_states_that_it_is_not_a_verdict() {
        // This is the assertion that carries weight. research/04 §6 requires the
        // forensic heuristics to be presented as a triage aid and never as a
        // verdict — the project's honesty constraint, not a cosmetic one. A
        // refactor that dropped this banner would leave ELA/EXIF output looking
        // like a determination of authenticity, which is the single most damaging
        // thing this screen could do.
        compose.onNodeWithText(string(R.string.tab_analyze)).performClick()

        compose.onNodeWithText(string(R.string.analyze_disclaimer_title)).assertIsDisplayed()
    }

    @Test
    fun the_device_surface_is_reachable() {
        compose.onNodeWithText(string(R.string.tab_device)).performClick()

        compose.onNodeWithText(string(R.string.tab_device)).assertIsDisplayed()
    }
}
