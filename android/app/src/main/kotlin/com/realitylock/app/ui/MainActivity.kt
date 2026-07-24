package com.realitylock.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realitylock.app.RealityLockApplication
import com.realitylock.app.ui.capture.CaptureScreen
import com.realitylock.app.ui.capture.CaptureViewModel

/**
 * Hosts the capture flow. The dependency graph is taken from the Application's
 * [com.realitylock.app.core.di.AppContainer] and handed to the ViewModel through
 * an explicit factory (no DI framework — see ADR-0003 on avoiding KSP).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RealityLockApplication).container

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val captureViewModel: CaptureViewModel =
                        viewModel(factory = CaptureViewModel.factory(container))
                    CaptureScreen(viewModel = captureViewModel)
                }
            }
        }
    }
}
