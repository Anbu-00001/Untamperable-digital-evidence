package com.realitylock.app.ui.diagnostics

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.realitylock.app.core.config.AppConfig
import com.realitylock.app.core.config.CryptoConfig
import com.realitylock.app.core.config.ProofPackageConstants
import com.realitylock.app.core.device.DeviceCapabilities

/**
 * Diagnostics view: build configuration and the hardware features the proof
 * pipeline depends on.
 *
 * The StrongBox readout is not cosmetic — it determines whether Phase 3 can use
 * a secure-element-backed signing key or must fall back to the TEE
 * (research/02 §2), so it is worth surfacing on any device under test.
 */
@Composable
fun DeviceStatusScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val capabilities = remember { DeviceCapabilities(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Build configuration", style = MaterialTheme.typography.titleMedium)
        StatusRow("App version", "${AppConfig.versionName} (${AppConfig.versionCode})")
        StatusRow("Application ID", AppConfig.applicationId)
        StatusRow("Backend base URL", AppConfig.backendBaseUrl)
        StatusRow("Signature algorithm", CryptoConfig.SIGNATURE_ALGORITHM)
        StatusRow("Play Integrity configured", yesNo(AppConfig.isPlayIntegrityConfigured))

        Spacer(Modifier.height(12.dp))
        Text("Proof schema", style = MaterialTheme.typography.titleMedium)
        Text(ProofPackageConstants.SCHEMA_URN, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
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
