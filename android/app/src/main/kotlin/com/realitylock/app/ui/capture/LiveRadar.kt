package com.realitylock.app.ui.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.realitylock.app.ui.theme.RealityLockThemeTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One satellite as the phone's GNSS chip reports it right now.
 */
data class SkyObject(
    val azimuthDegrees: Float,
    val elevationDegrees: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
)

/**
 * Live instrument readings for the capture viewfinder.
 *
 * Everything here is read from the phone's own hardware as it changes: the
 * accelerometer, the fused location provider's reported accuracy, and the GNSS
 * satellite list. It is a LIVE VIEW, not part of the evidence — what a capture
 * records is the single fix taken at the shutter, by [com.realitylock.app.capture.CaptureCoordinator].
 * The viewfinder says so in its notice, and nothing here is ever saved or signed.
 *
 * Fields are Compose state so a [Canvas] can read them in its draw phase and
 * repaint without recomposing the screen at sensor rate.
 */
@Stable
class RadarState {
    /** Roll and pitch as a fraction of gravity, -1..1; 0,0 is level. */
    var tiltX by mutableFloatStateOf(0f)
    var tiltY by mutableFloatStateOf(0f)

    /** True when the last second of accelerometer data has been calm. */
    var steady by mutableStateOf(false)
    var hasMotion by mutableStateOf(false)

    /** Reported horizontal accuracy radius in metres; null until a fix arrives. */
    var accuracyMeters by mutableStateOf<Float?>(null)
    var satellites by mutableStateOf<List<SkyObject>>(emptyList())
    var usedCount by mutableIntStateOf(0)
}

/**
 * Starts the sensors while this is composed and stops them the instant it leaves.
 *
 * Location updates are requested ONLY to drive this display, only while the
 * viewfinder is on screen, and only when the user has granted location — they are
 * the same permission and the same provider the capture itself uses. The GNSS
 * callback needs the receiver to be running, which these updates cause.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberRadarState(locationGranted: Boolean): RadarState {
    val context = LocalContext.current
    val state = remember { RadarState() }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val window = FloatArray(WINDOW)
        var filled = 0
        var cursor = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val ax = event.values[0]
                val az = event.values[2]
                val magnitude = sqrt(ax * ax + event.values[1] * event.values[1] + az * az)
                window[cursor] = magnitude
                cursor = (cursor + 1) % WINDOW
                if (filled < WINDOW) filled++
                // Low-pass so the bubble drifts instead of twitching.
                state.tiltX += (-ax / SensorManager.GRAVITY_EARTH - state.tiltX) * SMOOTHING
                state.tiltY += (az / SensorManager.GRAVITY_EARTH - state.tiltY) * SMOOTHING
                if (filled == WINDOW) {
                    val mean = window.average().toFloat()
                    val variance = window.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / WINDOW
                    state.steady = sqrt(variance) < STEADY_STDDEV
                }
                state.hasMotion = true
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (accel != null) sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    DisposableEffect(locationGranted) {
        if (!locationGranted) return@DisposableEffect onDispose { }

        val fused = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { state.accuracyMeters = it.accuracy }
            }
        }
        runCatching {
            fused.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MILLIS).build(),
                callback,
                Looper.getMainLooper(),
            )
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val sats = ArrayList<SkyObject>(status.satelliteCount)
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    val elevation = status.getElevationDegrees(i)
                    if (elevation < 0f) continue
                    val inFix = status.usedInFix(i)
                    if (inFix) used++
                    sats += SkyObject(status.getAzimuthDegrees(i), elevation, status.getCn0DbHz(i), inFix)
                }
                state.satellites = sats
                state.usedCount = used
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnss)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
            }
        }
        onDispose {
            runCatching { fused.removeLocationUpdates(callback) }
            runCatching { lm.unregisterGnssStatusCallback(gnss) }
            state.satellites = emptyList()
            state.usedCount = 0
            state.accuracyMeters = null
        }
    }
    return state
}

/**
 * The sky plot: a compass ring, elevation rings, every satellite the phone can
 * see as a dot (bright = used for the position fix, dim = tracked only, bigger =
 * stronger signal), a sweeping beam, and the tilt bubble in the middle.
 *
 * The beam is a pure animation — it does not claim to scan anything. The dots
 * and the bubble are the data.
 */
@Composable
fun SkyRadar(state: RadarState, modifier: Modifier = Modifier, size: Dp = 132.dp) {
    val c = RealityLockThemeTokens.colors
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepAngle",
    )
    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 2f - 2.dp.toPx()
        val line = Stroke(width = 1.dp.toPx())

        drawCircle(Color(0xAA060A18), radius = radius, center = center)
        // Elevation rings at 60 / 30 / 0 degrees, then the horizon.
        for (fraction in listOf(1f / 3f, 2f / 3f, 1f)) {
            drawCircle(c.primary.copy(alpha = 0.28f), radius = radius * fraction, center = center, style = line)
        }
        // Cross hairs.
        drawLine(c.primary.copy(alpha = 0.22f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        drawLine(c.primary.copy(alpha = 0.22f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())

        // Sweeping beam.
        rotate(sweep, center) {
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.85f to Color.Transparent,
                    1f to c.primary.copy(alpha = 0.55f),
                    center = center,
                ),
                radius = radius,
                center = center,
            )
        }

        // Satellites: azimuth clockwise from north, elevation 90 at the centre.
        for (sat in state.satellites) {
            val r = radius * (1f - sat.elevationDegrees / 90f)
            val a = sat.azimuthDegrees * (PI.toFloat() / 180f)
            val p = Offset(center.x + r * sin(a), center.y - r * cos(a))
            val dot = (2.2f + (sat.cn0DbHz / 50f).coerceIn(0f, 1f) * 2.6f).dp.toPx()
            val color = if (sat.usedInFix) c.pass else c.inkMuted
            drawCircle(color.copy(alpha = 0.30f), radius = dot * 2.2f, center = p)
            drawCircle(color, radius = dot, center = p)
        }

        // Tilt bubble: the dot a spirit level would show. Cyan when calm, amber when not.
        val travel = radius * 0.45f
        val bubble = Offset(
            center.x + state.tiltX.coerceIn(-1f, 1f) * travel,
            center.y + state.tiltY.coerceIn(-1f, 1f) * travel,
        )
        val bubbleColor = if (state.steady) c.primary else c.warn
        drawCircle(bubbleColor.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = bubble)
        drawCircle(bubbleColor, radius = 4.dp.toPx(), center = bubble)
        drawCircle(c.ink.copy(alpha = 0.55f), radius = 2.dp.toPx(), center = center)
    }
}

private const val WINDOW = 16
private const val SMOOTHING = 0.18f

/** Standard deviation of acceleration magnitude, m/s^2, below which the phone reads as held still. */
private const val STEADY_STDDEV = 0.18f
private const val UPDATE_INTERVAL_MILLIS = 2_000L
