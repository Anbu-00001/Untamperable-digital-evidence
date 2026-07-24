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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
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
        Text(
            stringResource(R.string.device_section_build),
            style = MaterialTheme.typography.titleMedium,
        )
        StatusRow(
            stringResource(R.string.device_app_version),
            stringResource(
                R.string.device_app_version_format,
                AppConfig.versionName,
                AppConfig.versionCode,
            ),
        )
        StatusRow(stringResource(R.string.device_application_id), AppConfig.applicationId)
        StatusRow(stringResource(R.string.device_backend_base_url), AppConfig.backendBaseUrl)
        StatusRow(
            stringResource(R.string.device_signature_algorithm),
            CryptoConfig.SIGNATURE_ALGORITHM,
        )
        StatusRow(
            stringResource(R.string.device_play_integrity_configured),
            yesNo(AppConfig.isPlayIntegrityConfigured),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.device_section_schema),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(ProofPackageConstants.SCHEMA_URN, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.device_section_capabilities),
            style = MaterialTheme.typography.titleMedium,
        )
        StatusRow(stringResource(R.string.device_strongbox), yesNo(capabilities.hasStrongBox))
        StatusRow(stringResource(R.string.device_camera), yesNo(capabilities.hasCamera))
        StatusRow(stringResource(R.string.device_gps), yesNo(capabilities.hasGps))
        StatusRow(
            stringResource(R.string.device_accelerometer),
            yesNo(capabilities.hasAccelerometer),
        )
        StatusRow(stringResource(R.string.device_gyroscope), yesNo(capabilities.hasGyroscope))
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

@Composable
private fun yesNo(value: Boolean): String =
    stringResource(if (value) R.string.common_yes else R.string.common_no)
