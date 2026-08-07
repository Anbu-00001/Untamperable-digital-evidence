package com.realitylock.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import com.realitylock.app.ui.theme.RealityLockTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realitylock.app.RealityLockApplication
import com.realitylock.app.ui.analyze.AnalyzeViewModel
import com.realitylock.app.ui.capture.CaptureScreen
import com.realitylock.app.ui.capture.CaptureViewModel
import com.realitylock.app.ui.verify.ProofsViewModel

/**
 * Hosts the capture flow. The dependency graph is taken from the Application's
 * [com.realitylock.app.core.di.AppContainer] and handed to the ViewModel through
 * an explicit factory (no DI framework — see ADR-0003 on avoiding KSP).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Declared rather than inherited. Targeting SDK 36 means Android 15+
        // draws this activity behind the system bars whether it asks to or not
        // (the `windowOptOutEdgeToEdgeEnforcement` escape hatch is gone), so the
        // choice is only ever between handling insets deliberately and shipping
        // content stuck under the navigation bar — which is what the CPH2591 was
        // doing to the last of the capture details. Calling it explicitly also
        // makes the transparent system-bar scrims a decision in the diff instead
        // of a platform default that could change again.
        enableEdgeToEdge()
        val container = (application as RealityLockApplication).container

        setContent {
            // RealityLockTheme, not a bare MaterialTheme. It installs MaterialTheme
            // itself AND provides the status palette that carries pass / fail /
            // unavailable / unknown — four states Material's ColorScheme has no
            // slot for (ADR-0008).
            //
            // Applied at the root because that is where a theme belongs. While it
            // was missing, individual screens wrapped themselves defensively and
            // anything that did not — EvidenceThumbnail, on every History card —
            // hit the deliberate `error("RealityLockColors requested outside
            // RealityLockTheme")` and took the tab down. That hard error did its
            // job: it surfaced the gap in an instrumented test instead of shipping
            // a screen with silently wrong colours.
            RealityLockTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val captureViewModel: CaptureViewModel =
                        viewModel(factory = CaptureViewModel.factory(container))
                    val analyzeViewModel: AnalyzeViewModel =
                        viewModel(factory = AnalyzeViewModel.Factory(container))
                    val proofsViewModel: ProofsViewModel =
                        viewModel(factory = ProofsViewModel.Factory(container))
                    CaptureScreen(
                        viewModel = captureViewModel,
                        analyzeViewModel = analyzeViewModel,
                        proofsViewModel = proofsViewModel,
                    )
                }
            }
        }
    }
}
