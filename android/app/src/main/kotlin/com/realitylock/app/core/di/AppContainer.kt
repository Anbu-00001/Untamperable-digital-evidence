package com.realitylock.app.core.di

import android.content.Context
import com.realitylock.app.capture.CameraController
import com.realitylock.app.capture.CaptureCoordinator
import com.realitylock.app.capture.DeviceInfoProvider
import com.realitylock.app.capture.LocationSource
import com.realitylock.app.capture.MediaFileStore
import com.realitylock.app.capture.SensorSnapshotCollector
import com.realitylock.app.capture.store.EventRepository
import com.realitylock.app.capture.store.FileEventRepository
import com.realitylock.app.core.config.CaptureConfig
import com.realitylock.app.core.device.InstallIdProvider
import com.realitylock.app.core.time.ClockCorrelator
import com.realitylock.app.core.time.SystemClockSource
import java.io.File

/**
 * Manual dependency container, created once by the Application.
 *
 * Deliberately hand-wired rather than using Hilt: Hilt requires KSP, and KSP was
 * removed from this project because it added minutes to every build
 * (ADR-0003). At this project's size the explicit wiring is small, has zero
 * build cost, and makes the object graph obvious to read.
 *
 * Media and its metadata sidecar share one directory so an event's files sit
 * next to each other (`<eventId>.jpg` + `<eventId>.json`).
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val capturesDir: File = File(appContext.filesDir, CaptureConfig.MEDIA_SUBDIR)

    private val mediaFileStore = MediaFileStore(capturesDir)
    private val installIdProvider = InstallIdProvider(appContext)
    private val deviceInfoProvider = DeviceInfoProvider(installIdProvider)
    private val clockCorrelator = ClockCorrelator(SystemClockSource())
    private val locationSource = LocationSource(appContext)

    /** Shared store of captured events. */
    val eventRepository: EventRepository = FileEventRepository(capturesDir)

    /** Sensor collectors are stateful (start/stop), so each screen gets its own. */
    fun createSensorCollector(): SensorSnapshotCollector = SensorSnapshotCollector(appContext)

    fun createCameraController(): CameraController = CameraController(appContext)

    fun createCaptureCoordinator(sensors: SensorSnapshotCollector): CaptureCoordinator =
        CaptureCoordinator(
            clockCorrelator = clockCorrelator,
            sensors = sensors,
            locationSource = locationSource,
            mediaFileStore = mediaFileStore,
            repository = eventRepository,
            deviceInfoProvider = deviceInfoProvider,
        )
}
