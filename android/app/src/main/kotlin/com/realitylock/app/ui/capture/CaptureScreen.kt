package com.realitylock.app.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.realitylock.app.R
import com.realitylock.app.capture.LocationSource
import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.ui.diagnostics.DeviceStatusScreen

/**
 * Capture screen: live preview, a shutter that records a tamper-evident event,
 * and the history of what has been recorded.
 *
 * Permissions are requested in an **itemized, un-bundled** way (camera is
 * required; location is optional and separately explained) rather than as one
 * opaque prompt — the consent obligations in research/06 §3 call for exactly
 * that, and an evidence tool should be explicit about what it records.
 */
@Composable
fun CaptureScreen(viewModel: CaptureViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    var hasLocationPermission by remember {
        mutableStateOf(context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION))
    }
    var selectedTab by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasCameraPermission = granted[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasLocationPermission =
            granted[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission
    }

    // Buffer sensor samples only while this screen is on-screen.
    DisposableEffect(Unit) {
        viewModel.onScreenActive()
        onDispose { viewModel.onScreenInactive() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.tab_capture)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.tab_history, uiState.events.size)) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.tab_device)) },
            )
        }

        when (selectedTab) {
            0 -> CaptureTab(
                viewModel = viewModel,
                uiState = uiState,
                hasCameraPermission = hasCameraPermission,
                hasLocationPermission = hasLocationPermission,
                lifecycleOwner = lifecycleOwner,
                onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                },
            )

            1 -> HistoryTab(
                events = uiState.events,
                onDelete = viewModel::deleteEvent,
            )

            else -> DeviceStatusScreen()
        }
    }
}

@Composable
private fun CaptureTab(
    viewModel: CaptureViewModel,
    uiState: CaptureUiState,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasCameraPermission) {
            PermissionRequestPanel(
                hasLocationPermission = hasLocationPermission,
                onRequestPermissions = onRequestPermissions,
            )
            return@Column
        }

        val previewView = remember { PreviewView(context) }
        LaunchedEffect(hasCameraPermission) {
            runCatching { viewModel.cameraController.bind(lifecycleOwner, previewView) }
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PREVIEW_ASPECT_RATIO),
        )

        if (!hasLocationPermission) {
            Text(
                stringResource(R.string.capture_location_denied_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRequestPermissions) {
                Text(stringResource(R.string.capture_grant_location))
            }
        }

        Button(
            onClick = { viewModel.capture(includeLocation = hasLocationPermission) },
            enabled = !uiState.isCapturing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isCapturing) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.capture_in_progress))
            } else {
                Text(stringResource(R.string.capture_action))
            }
        }

        uiState.error?.let { error ->
            // The localized wording lives here, not in the ViewModel; the
            // platform's own message is shown when it has one to offer.
            Text(
                error.detail ?: stringResource(R.string.capture_failed),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = viewModel::dismissError) {
                Text(stringResource(R.string.capture_dismiss_error))
            }
        }

        uiState.lastEvent?.let { event ->
            Text(
                stringResource(R.string.capture_last_capture),
                style = MaterialTheme.typography.titleMedium,
            )
            EventCard(event)
        }
    }
}

/**
 * Explains, per permission, exactly what is recorded and why — the un-bundled
 * consent surface required by research/06 §3.
 */
@Composable
private fun PermissionRequestPanel(
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.permissions_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.permissions_camera_rationale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.permissions_location_rationale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.permissions_motion_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onRequestPermissions) {
            Text(
                stringResource(
                    if (hasLocationPermission) {
                        R.string.permissions_grant_camera
                    } else {
                        R.string.permissions_grant_all
                    },
                ),
            )
        }
    }
}

@Composable
private fun HistoryTab(events: List<CapturedEvent>, onDelete: (String) -> Unit) {
    if (events.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(events, key = { it.eventId }) { event ->
            EventCard(event = event, onDelete = { onDelete(event.eventId) })
        }
    }
}

@Composable
private fun EventCard(event: CapturedEvent, onDelete: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(event.metadata.timestamp.iso8601, style = MaterialTheme.typography.titleSmall)
            Divider()
            DetailRow(
                stringResource(R.string.event_label_id),
                stringResource(
                    R.string.event_id_truncated,
                    event.eventId.take(EVENT_ID_PREVIEW_LENGTH),
                ),
            )
            DetailRow(
                stringResource(R.string.event_label_size),
                stringResource(R.string.event_size_bytes, event.media.byteLength),
            )

            val location = event.metadata.location
            DetailRow(
                stringResource(R.string.event_label_location),
                if (location == null) {
                    stringResource(R.string.event_value_not_recorded)
                } else {
                    stringResource(
                        R.string.event_location_format,
                        location.latitude,
                        location.longitude,
                        location.accuracyMeters,
                    )
                },
            )
            if (location?.isMock == true) {
                Text(
                    stringResource(R.string.event_mock_location_detected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // A stale fix is kept, not discarded — but it is never presented as
            // if it described the capture instant.
            if (LocationSource.isFixStale(location?.fixAgeMillis)) {
                Text(
                    stringResource(
                        R.string.event_location_stale,
                        (location?.fixAgeMillis ?: 0L) / MILLIS_PER_SECOND,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val motion = event.metadata.motion
            DetailRow(
                stringResource(R.string.event_label_motion),
                if (motion == null) {
                    stringResource(R.string.event_value_not_recorded)
                } else {
                    stringResource(R.string.event_motion_captured)
                },
            )
            DetailRow(
                stringResource(R.string.event_label_hash),
                event.media.sha256 ?: stringResource(R.string.event_hash_pending),
            )

            onDelete?.let {
                TextButton(onClick = it) { Text(stringResource(R.string.history_delete)) }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private const val PREVIEW_ASPECT_RATIO = 3f / 4f
private const val EVENT_ID_PREVIEW_LENGTH = 8
private const val MILLIS_PER_SECOND = 1_000L
