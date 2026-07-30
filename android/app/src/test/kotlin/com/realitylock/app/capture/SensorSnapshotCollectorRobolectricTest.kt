package com.realitylock.app.capture

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/**
 * The collector's **framework-facing** half.
 *
 * [SensorSnapshotCollectorTest] covers the static selection helpers and never
 * constructs a collector at all, so everything between `registerListener` and a
 * returned `MotionData` — the rolling buffer, its eviction, and the
 * accelerometer/gyroscope routing — has no test. That is the path a real capture
 * actually takes, and it is what is asserted here.
 *
 * Buffer size and skew are passed explicitly rather than taken from
 * `CaptureConfig`, so tuning a shipped default cannot quietly change what these
 * assertions mean.
 */
// A plain Application, not the project's RealityLockApplication. The real one
// builds the whole DI graph in onCreate(), and `SigningKeyManager` opens the
// Android Keystore there — which no JVM-side sandbox provides, so every test in
// this class would die in application startup before reaching its subject. The
// collector needs a Context and nothing else from the graph, so substituting the
// framework's own Application removes an irrelevant dependency rather than
// hiding a failure.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SensorSnapshotCollectorRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var sensorManager: SensorManager
    private lateinit var shadow: ShadowSensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var gyroscope: Sensor

    @Before
    fun setUp() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadow = shadowOf(sensorManager)
        // Both sensors must exist before the collector is built: it resolves them
        // in its initialiser, so adding them afterwards would leave it holding nulls.
        accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        gyroscope = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE)
        shadow.addSensor(accelerometer)
        shadow.addSensor(gyroscope)
    }

    private fun collector(bufferSize: Int = 4, maxSkewMillis: Long = 500L) =
        SensorSnapshotCollector(context, bufferSize = bufferSize, maxSkewMillis = maxSkewMillis)

    private fun millis(value: Long) = value * SensorSnapshotCollector.NANOS_PER_MILLI

    private fun send(sensor: Sensor, atNanos: Long, x: Float, y: Float, z: Float) {
        shadow.sendSensorEventToListeners(
            SensorEventBuilder.newBuilder()
                .setSensor(sensor)
                .setTimestamp(atNanos)
                .setValues(floatArrayOf(x, y, z))
                .build(),
        )
    }

    @Test
    fun `an injected accelerometer sample reaches the snapshot`() {
        val collector = collector()
        collector.start()
        val shutter = millis(1_000)

        send(accelerometer, shutter, 1f, 2f, 3f)

        val motion = collector.snapshotNearest(shutter)
        assertNotNull("nothing was buffered", motion)
        assertEquals(listOf(1f, 2f, 3f), motion!!.accelerometer)
        assertEquals(shutter, motion.sampleElapsedRealtimeNanos)
    }

    @Test
    fun `events are routed to the buffer matching their sensor type`() {
        val collector = collector()
        collector.start()
        val shutter = millis(1_000)

        send(accelerometer, shutter, 9f, 0f, 0f)
        send(gyroscope, shutter, 0f, 5f, 0f)

        val motion = collector.snapshotNearest(shutter)!!
        // Routing the two streams into each other's buffer would surface right
        // here, as the gyroscope vector appearing in the accelerometer field.
        assertEquals(listOf(9f, 0f, 0f), motion.accelerometer)
        assertEquals(listOf(0f, 5f, 0f), motion.gyroscope)
    }

    @Test
    fun `the rolling buffer evicts the oldest samples past its size`() {
        val collector = collector(bufferSize = 3)
        collector.start()

        // Six samples into a three-deep buffer: 1..3 must be evicted, leaving 4..6.
        (1..6).forEach { i -> send(accelerometer, millis(i * 100L), i.toFloat(), 0f, 0f) }

        // Asking near the evicted start therefore resolves to the oldest
        // *surviving* sample. Without eviction this would answer with sample 1.
        val motion = collector.snapshotNearest(millis(100))
        assertEquals(listOf(4f, 0f, 0f), motion?.accelerometer)
    }

    @Test
    fun `the gyroscope is dropped when only it falls outside the skew window`() {
        val collector = collector(maxSkewMillis = 200L)
        collector.start()
        val shutter = millis(10_000)

        send(accelerometer, shutter, 1f, 1f, 1f)
        send(gyroscope, shutter + millis(900), 7f, 7f, 7f)

        val motion = collector.snapshotNearest(shutter)!!
        assertEquals(listOf(1f, 1f, 1f), motion.accelerometer)
        // null, NOT an empty list: an absent reading has to stay distinguishable
        // from a measured zero rotation.
        assertNull(motion.gyroscope)
    }

    @Test
    fun `no snapshot is produced when every sample is too far from the shutter`() {
        val collector = collector(maxSkewMillis = 200L)
        collector.start()

        send(accelerometer, millis(1_000), 1f, 1f, 1f)

        assertNull(collector.snapshotNearest(millis(5_000)))
    }

    @Test
    fun `stop clears the buffer so a later capture cannot bind a stale sample`() {
        val collector = collector()
        collector.start()
        val shutter = millis(1_000)
        send(accelerometer, shutter, 1f, 1f, 1f)
        assertNotNull(collector.snapshotNearest(shutter))

        collector.stop()

        assertNull("a sample survived stop()", collector.snapshotNearest(shutter))
    }

    @Test
    fun `samples arriving after stop are not recorded`() {
        val collector = collector()
        collector.start()
        collector.stop()

        val shutter = millis(1_000)
        send(accelerometer, shutter, 1f, 1f, 1f)

        assertNull(collector.snapshotNearest(shutter))
    }

    @Test
    fun `a device exposing no accelerometer reports itself unavailable`() {
        shadow.removeSensor(accelerometer)

        assertFalse(collector().isAvailable)
    }
}
