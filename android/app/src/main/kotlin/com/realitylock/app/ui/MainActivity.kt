package com.realitylock.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.realitylock.app.core.config.AppConfig
import com.realitylock.app.core.config.CryptoConfig
import com.realitylock.app.core.config.ProofPackageConstants
import com.realitylock.app.core.device.DeviceCapabilities

/**
 * Phase-1 "foundation status" screen. It has no product features yet — its
 * job is to prove the skeleton is wired correctly end to end: config values
 * resolve from the build, and device capabilities read back. The real capture
 * UI replaces this in Phase 2.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capabilities = DeviceCapabilities(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FoundationStatusScreen(capabilities)
                }
            }
        }
    }
}

@Composable
private fun FoundationStatusScreen(capabilities: DeviceCapabilities) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Reality Lock", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tamper-Evident Event Proof — foundation skeleton (Phase 1)",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))
        Text("Build configuration", style = MaterialTheme.typography.titleMedium)
        StatusRow("App version", "${AppConfig.versionName} (${AppConfig.versionCode})")
        StatusRow("Application ID", AppConfig.applicationId)
        StatusRow("Backend base URL", AppConfig.backendBaseUrl)
        StatusRow("Proof schema", ProofPackageConstants.SCHEMA_URN)
        StatusRow("Signature algorithm", CryptoConfig.SIGNATURE_ALGORITHM)
        StatusRow("Play Integrity configured", yesNo(AppConfig.isPlayIntegrityConfigured))

        Spacer(Modifier.height(16.dp))
        Text("Device capabilities", style = MaterialTheme.typography.titleMedium)
        StatusRow("StrongBox secure element", yesNo(capabilities.hasStrongBox))
        StatusRow("Camera", yesNo(capabilities.hasCamera))
        StatusRow("GPS", yesNo(capabilities.hasGps))
        StatusRow("Accelerometer", yesNo(capabilities.hasAccelerometer))
        StatusRow("Gyroscope", yesNo(capabilities.hasGyroscope))
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"
