package com.realitylock.app.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.realitylock.app.capture.model.MotionData
import com.realitylock.app.core.config.CaptureConfig
import kotlin.math.abs

/**
 * Maintains a short rolling history of accelerometer/gyroscope readings so a
 * capture can bind the motion context that actually coincided with the shutter.
 *
 * Sensors are event-driven — there is no "read the sensor now" call — so the
 * collector is started when the capture screen opens and keeps a small buffer.
 * Because `SensorEvent.timestamp` and CameraX's `ImageProxy.imageInfo.timestamp`
 * share the `elapsedRealtimeNanos` clock base (research/03 §4), we can select
 * the sample *nearest the actual capture instant* rather than merely the most
 * recent one, which matters when the callback runs tens of ms after the shutter.
 */
class SensorSnapshotCollector(
    context: Context,
    private val bufferSize: Int = CaptureConfig.MOTION_BUFFER_SIZE,
    private val maxSkewMillis: Long = CaptureConfig.MOTION_MAX_SKEW_MILLIS,
) : SensorEventListener {

    /** A single sensor sample stamped on the monotonic clock. */
    data class Reading(
        val values: List<Float>,
        val elapsedRealtimeNanos: Long,
    )

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val lock = Any()
    private val accelReadings = ArrayDeque<Reading>()
    private val gyroReadings = ArrayDeque<Reading>()

    /** True when the device exposes at least an accelerometer. */
    val isAvailable: Boolean get() = accelerometer != null

    /** Begin buffering. Call when the capture screen becomes visible. */
    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, CaptureConfig.SENSOR_SAMPLING_PERIOD)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, CaptureConfig.SENSOR_SAMPLING_PERIOD)
        }
    }

    /** Stop buffering and release listeners. Call when the screen is hidden. */
    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(lock) {
            accelReadings.clear()
            gyroReadings.clear()
        }
    }

    /**
     * Returns the motion sample closest to [targetElapsedRealtimeNanos] (the
     * camera's capture timestamp), or null when no sample lies close enough to
     * honestly describe that instant (see [CaptureConfig.MOTION_MAX_SKEW_MILLIS]).
     */
    fun snapshotNearest(targetElapsedRealtimeNanos: Long): MotionData? {
        val accel: Reading?
        val gyro: Reading?
        synchronized(lock) {
            accel = nearestTo(accelReadings.toList(), targetElapsedRealtimeNanos)
            gyro = nearestTo(gyroReadings.toList(), targetElapsedRealtimeNanos)
        }
        val maxSkewNanos = maxSkewMillis * NANOS_PER_MILLI
        if (accel == null || !isWithinSkew(accel, targetElapsedRealtimeNanos, maxSkewNanos)) {
            return null
        }
        // The gyroscope is included only if it, too, is close to the shutter.
        // Null (not an empty list) when it is not: absence of a reading must
        // stay distinguishable from a measured zero rotation.
        val gyroValues = gyro
            ?.takeIf { isWithinSkew(it, targetElapsedRealtimeNanos, maxSkewNanos) }
            ?.values
        return MotionData(
            accelerometer = accel.values,
            gyroscope = gyroValues,
            sampleElapsedRealtimeNanos = accel.elapsedRealtimeNanos,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        val reading = Reading(
            values = event.values.take(VECTOR_COMPONENTS),
            elapsedRealtimeNanos = event.timestamp,
        )
        val target = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelReadings
            Sensor.TYPE_GYROSCOPE -> gyroReadings
            else -> return
        }
        synchronized(lock) {
            target.addLast(reading)
            while (target.size > bufferSize) target.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** x, y, z. */
        const val VECTOR_COMPONENTS: Int = 3

        /** Nanoseconds per millisecond. */
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * Pure selection logic, extracted so it can be unit tested without the
         * sensor framework: the reading whose timestamp is closest to [target].
         */
        fun nearestTo(readings: List<Reading>, target: Long): Reading? =
            readings.minByOrNull { abs(it.elapsedRealtimeNanos - target) }

        /** True when [reading] sits within [maxSkewNanos] of [target]. */
        fun isWithinSkew(reading: Reading, target: Long, maxSkewNanos: Long): Boolean =
            abs(reading.elapsedRealtimeNanos - target) <= maxSkewNanos
    }
}
