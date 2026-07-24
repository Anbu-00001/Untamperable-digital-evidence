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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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
            "Reality Lock",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Capture") })
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("History (${uiState.events.size})") },
            )
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Device") })
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
                "Location is not granted — events will be recorded without a location proof.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRequestPermissions) { Text("Grant location") }
        }

        Button(
            onClick = { viewModel.capture(includeLocation = hasLocationPermission) },
            enabled = !uiState.isCapturing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isCapturing) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(8.dp))
                Text("Capturing…")
            } else {
                Text("Capture event")
            }
        }

        uiState.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
        }

        uiState.lastEvent?.let { event ->
            Text("Last capture", style = MaterialTheme.typography.titleMedium)
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
        Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
        Text(
            "Camera (required) — captures the photo that the proof is built from. " +
                "Media is captured in-app only; existing gallery photos can never be imported.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Location (optional) — records where the event happened so the proof can " +
                "include a location claim. Without it, captures still work and the " +
                "location is recorded as absent, never guessed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Motion sensors are read only while this screen is open, and only around " +
                "the moment of capture.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onRequestPermissions) {
            Text(if (hasLocationPermission) "Grant camera access" else "Grant access")
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
            Text("No events captured yet.", style = MaterialTheme.typography.bodyMedium)
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
            DetailRow("Event ID", event.eventId.take(EVENT_ID_PREVIEW_LENGTH) + "…")
            DetailRow("Size", "${event.media.byteLength} bytes")

            val location = event.metadata.location
            DetailRow(
                "Location",
                if (location == null) {
                    "not recorded"
                } else {
                    "%.5f, %.5f (±%.0fm)".format(
                        location.latitude,
                        location.longitude,
                        location.accuracyMeters,
                    )
                },
            )
            if (location?.isMock == true) {
                Text(
                    "Mock location detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val motion = event.metadata.motion
            DetailRow("Motion", if (motion == null) "not recorded" else "accel + gyro captured")
            DetailRow("Hash", event.media.sha256 ?: "pending (Phase 3)")

            onDelete?.let {
                TextButton(onClick = it) { Text("Delete") }
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
