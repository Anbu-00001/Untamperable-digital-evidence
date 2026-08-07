package com.realitylock.app.ui.diagnostics

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realitylock.app.R
import com.realitylock.app.capture.GnssCapabilityProbe
import com.realitylock.app.core.config.AppConfig
import com.realitylock.app.core.config.CryptoConfig
import com.realitylock.app.core.config.ProofPackageConstants
import com.realitylock.app.core.device.DeviceCapabilities
import com.realitylock.app.crypto.AttestationProbe
import com.realitylock.app.crypto.SigningKeyManager
import com.realitylock.app.ui.backup.BackupSection
import com.realitylock.app.ui.backup.BackupViewModel
import com.realitylock.app.ui.common.scrollableBottomInset
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Diagnostics view: build configuration and the hardware features the proof
 * pipeline depends on.
 *
 * The StrongBox readout is not cosmetic — it determines whether Phase 3 can use
 * a secure-element-backed signing key or must fall back to the TEE
 * (research/02 §2), so it is worth surfacing on any device under test.
 *
 * ## Checks and facts are rendered differently, on purpose
 *
 * Half of what this screen reports is a **capability check** with an outcome
 * (is there a camera; is StrongBox present; can the platform report raw GNSS)
 * and half is a plain **fact** (the version string, the application id, the
 * backend base URL, the signature algorithm name). The old layout gave both the
 * same `label ... value` line, which made a version number look like something
 * that had passed. It had not passed anything; it is simply what it is.
 *
 * So checks get the four-state pill — icon, colour and word — that authenticity
 * reports use, and facts get a neutral monospace value chip with no icon and no
 * status colour at all. See `DeviceStatusComponents.kt` for the reasoning and
 * the rendering.
 *
 * ## When a missing capability is a FAIL and when it is only UNAVAILABLE
 *
 * Red is reserved for capabilities the capture pipeline genuinely cannot work
 * without — camera, GPS, and the two motion sensors whose readings go into every
 * proof package. Everything else that is absent reports grey `unavailable`,
 * because it has a documented fallback or is not used yet:
 *
 * - **StrongBox** — absence is expected on most devices and `SigningKeyManager`
 *   falls back to the TEE. Painting that red would cry wolf on hardware the app
 *   fully supports.
 * - **Raw GNSS** — the spoofing analysis is explicitly future work
 *   (`GnssCapabilityProbe`), so its absence costs the pipeline nothing today.
 *   Below API 31 the platform will not answer at all, which is a different
 *   statement again and gets its own note.
 * - **Play Integrity** — configured by a build-time project number. Not
 *   configured is a build choice, not a device defect.
 *
 * Only `UNKNOWN` (violet) is left for an answer this app cannot classify, which
 * today means a security tier outside the enum it knows.
 */
@Composable
fun DeviceStatusScreen(
    modifier: Modifier = Modifier,
    backupViewModel: BackupViewModel? = null,
) {
    // The defensive `RealityLockTheme` wrapper that used to sit here is gone.
    // It existed because MainActivity still installed a bare MaterialTheme, so
    // this screen had to provide the palette over its own subtree or hit the
    // deliberate `error("RealityLockColors requested outside RealityLockTheme")`.
    // MainActivity now applies the theme at the root, which is where a theme
    // belongs, so the nesting is redundant.
    DeviceStatusContent(modifier, backupViewModel)
}

@Composable
private fun DeviceStatusContent(modifier: Modifier, backupViewModel: BackupViewModel?) {
    val context = LocalContext.current
    val capabilities = remember { DeviceCapabilities(context) }
    val gnssProbe = remember { GnssCapabilityProbe(context) }
    val colors = RealityLockThemeTokens.colors

    // Key generation touches secure hardware and can take a second, so it runs
    // off the main thread and the screen renders without waiting.
    var attestation by remember { mutableStateOf<AttestationProbe.Result?>(null) }
    LaunchedEffect(Unit) {
        attestation = withContext(Dispatchers.IO) {
            AttestationProbe().run(
                exportTo = java.io.File(context.filesDir, ATTESTATION_EXPORT_FILE),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            // Padding applied after verticalScroll pads the scrolled CONTENT,
            // not the viewport, so the last row can be scrolled clear of the
            // navigation bar instead of sitting under it. See
            // ui/common/WindowInsetsSupport.
            .padding(16.dp)
            .padding(bottom = scrollableBottomInset()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            LEGEND,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )

        // Placed first, above the read-only diagnostics below it, because it is
        // the only thing on this screen the user can act on — and the only one
        // where doing nothing loses evidence.
        backupViewModel?.let { BackupSection(viewModel = it) }

        DeviceSection(
            title = stringResource(R.string.device_section_build),
            rows = listOf(
                DeviceRow.Fact(
                    label = stringResource(R.string.device_app_version),
                    value = stringResource(
                        R.string.device_app_version_format,
                        AppConfig.versionName,
                        AppConfig.versionCode,
                    ),
                ),
                DeviceRow.Fact(
                    label = stringResource(R.string.device_application_id),
                    value = AppConfig.applicationId,
                ),
            ),
        )

        DeviceSection(
            title = stringResource(R.string.device_section_capabilities),
            rows = listOf(
                DeviceRow.Check(
                    label = stringResource(R.string.device_camera),
                    status = required(capabilities.hasCamera),
                ),
                DeviceRow.Check(
                    label = stringResource(R.string.device_gps),
                    status = required(capabilities.hasGps),
                ),
                DeviceRow.Check(
                    label = stringResource(R.string.device_accelerometer),
                    status = required(capabilities.hasAccelerometer),
                ),
                DeviceRow.Check(
                    label = stringResource(R.string.device_gyroscope),
                    status = required(capabilities.hasGyroscope),
                ),
                DeviceRow.Check(
                    label = stringResource(R.string.device_strongbox),
                    status = optional(capabilities.hasStrongBox),
                    note = if (capabilities.hasStrongBox) null else NOTE_STRONGBOX_ABSENT,
                ),
            ),
        )

        DeviceSection(
            title = stringResource(R.string.device_section_attestation),
            rows = attestationRows(attestation),
        )

        val gnssSupported = gnssProbe.supportsRawMeasurements
        val canAskForGnss = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        DeviceSection(
            title = stringResource(R.string.device_section_gnss),
            rows = listOf(
                DeviceRow.Check(
                    label = stringResource(R.string.device_gnss_raw_supported),
                    status = optional(gnssSupported),
                    note = when {
                        gnssSupported -> null
                        // Two different statements that both render grey, so the
                        // note is what separates them: "the platform said no"
                        // versus "the platform cannot be asked".
                        canAskForGnss -> NOTE_GNSS_UNSUPPORTED
                        else -> NOTE_GNSS_UNASKABLE
                    },
                ),
                DeviceRow.Info(stringResource(R.string.device_gnss_note)),
            ),
        )

        DeviceSection(
            title = stringResource(R.string.device_section_schema),
            rows = listOf(
                DeviceRow.Fact(
                    label = stringResource(R.string.device_backend_base_url),
                    value = AppConfig.backendBaseUrl,
                ),
                DeviceRow.Fact(
                    label = LABEL_SCHEMA_URN,
                    value = ProofPackageConstants.SCHEMA_URN,
                ),
            ),
        )
    }
}

/**
 * Rows for the attestation card, including the pre-result state.
 *
 * Not a composable of its own so the "checking…" placeholder and the resolved
 * rows go through exactly the same section rendering — the card must not change
 * shape or styling when the probe returns, only content.
 */
@Composable
private fun attestationRows(result: AttestationProbe.Result?): List<DeviceRow> {
    if (result == null) {
        return listOf(DeviceRow.Progress(stringResource(R.string.device_attestation_checking)))
    }
    return listOf(
        DeviceRow.Check(
            label = stringResource(R.string.device_attestation_hardware),
            // A check that ran and returned "no hardware proof" is a fail, not an
            // unavailable: the probe answered, and the answer is a problem for a
            // pipeline whose whole claim rests on hardware-backed keys.
            status = if (result.attested) DeviceCheckStatus.PASS else DeviceCheckStatus.FAIL,
        ),
        DeviceRow.Check(
            label = stringResource(R.string.device_attestation_tier),
            status = when (result.tier) {
                SigningKeyManager.SecurityTier.STRONGBOX,
                SigningKeyManager.SecurityTier.TRUSTED_ENVIRONMENT,
                -> DeviceCheckStatus.PASS
                SigningKeyManager.SecurityTier.UNKNOWN -> DeviceCheckStatus.UNKNOWN
            },
            // The tier name is shown verbatim as well as graded: STRONGBOX and
            // TRUSTED_ENVIRONMENT both pass, and which one it was still matters.
            value = result.tier.name,
        ),
        DeviceRow.Check(
            label = stringResource(R.string.device_play_integrity_configured),
            status = optional(AppConfig.isPlayIntegrityConfigured),
            note = if (AppConfig.isPlayIntegrityConfigured) null else NOTE_PLAY_INTEGRITY_UNSET,
        ),
        // A count and an algorithm name. Neither is a verdict, so neither wears
        // a pill — this is the fact/check split in its clearest form, two rows
        // below three that look completely different.
        DeviceRow.Fact(
            label = stringResource(R.string.device_attestation_chain_length),
            value = result.chainLength.toString(),
        ),
        DeviceRow.Fact(
            label = stringResource(R.string.device_signature_algorithm),
            value = CryptoConfig.SIGNATURE_ALGORITHM,
        ),
        DeviceRow.Info(result.detail),
    )
}

/** Present or the pipeline cannot function: absence is a genuine failure. */
private fun required(present: Boolean): DeviceCheckStatus =
    if (present) DeviceCheckStatus.PASS else DeviceCheckStatus.FAIL

/** Present or degraded-but-fine: absence is reported grey, never red. */
private fun optional(present: Boolean): DeviceCheckStatus =
    if (present) DeviceCheckStatus.PASS else DeviceCheckStatus.UNAVAILABLE

/** Diagnostics export; pull with `adb shell run-as <pkg> cat files/<name>`. */
private const val ATTESTATION_EXPORT_FILE = "attestation-chain.json"

// ---------------------------------------------------------------------------
// Copy that has no `device_*` resource yet. Kotlin constants rather than new
// keys in `res/values/strings.xml` for one reason only: that file is being
// edited by a parallel workstream in this change set and new keys would
// collide. Everything with an existing resource is still read from resources.
// Move these into strings.xml for localisation once the file is free.
// ---------------------------------------------------------------------------
private const val LEGEND =
    "Checks report an outcome — pass, fail, unavailable or unknown. " +
        "Facts are recorded values shown in monospace; they are not verdicts."

private const val LABEL_SCHEMA_URN = "Proof package schema"

private const val NOTE_STRONGBOX_ABSENT =
    "No secure element on this device. Signing keys fall back to the TEE, " +
        "which the pipeline fully supports."

private const val NOTE_PLAY_INTEGRITY_UNSET =
    "No cloud project number was supplied to this build."

private const val NOTE_GNSS_UNSUPPORTED =
    "The platform reports no raw measurement support on this device."

private const val NOTE_GNSS_UNASKABLE =
    "Android 12 (API 31) is required to query this; the platform cannot be asked here."
