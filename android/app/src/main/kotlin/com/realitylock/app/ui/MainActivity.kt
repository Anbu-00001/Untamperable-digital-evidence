package com.realitylock.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.realitylock.app.ui.components.CyberBackdrop
import com.realitylock.app.ui.components.LocalAddressResolver
import com.realitylock.app.ui.components.LaunchSplash
import com.realitylock.app.ui.theme.RealityLockTheme
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realitylock.app.RealityLockApplication
import com.realitylock.app.ui.analyze.AnalyzeViewModel
import com.realitylock.app.ui.capture.CaptureScreen
import com.realitylock.app.ui.backup.BackupViewModel
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
        // Light status/navigation icons on the always-dark theme. Without the
        // explicit style, auto-detection follows the SYSTEM theme and a phone in
        // light mode would paint dark icons onto a navy screen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as RealityLockApplication).container
        // Only on a fresh launch, not on rotation: one wake-up per opening is enough.
        if (savedInstanceState == null) container.verificationClient.wake()

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
            // Always dark: the redesign is a single, deliberate "cyber-security"
            // look, and the status palette was tuned to glow on navy.
            RealityLockTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = RealityLockThemeTokens.colors.ink,
                ) {
                    val captureViewModel: CaptureViewModel =
                        viewModel(factory = CaptureViewModel.factory(container))
                    val analyzeViewModel: AnalyzeViewModel =
                        viewModel(factory = AnalyzeViewModel.Factory(container))
                    val proofsViewModel: ProofsViewModel =
                        viewModel(factory = ProofsViewModel.Factory(container))
                    val backupViewModel: BackupViewModel =
                        viewModel(factory = BackupViewModel.Factory(container))
                    // The launch animation plays once per cold start. Saved, so a
                    // rotation does not replay it.
                    var showSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                    CompositionLocalProvider(LocalAddressResolver provides container.addressResolver) {
                        CyberBackdrop {
                            CaptureScreen(
                                viewModel = captureViewModel,
                                analyzeViewModel = analyzeViewModel,
                                proofsViewModel = proofsViewModel,
                                backupViewModel = backupViewModel,
                            )
                            if (showSplash) LaunchSplash(onFinished = { showSplash = false })
                        }
                    }
                }
            }
        }
    }
}
