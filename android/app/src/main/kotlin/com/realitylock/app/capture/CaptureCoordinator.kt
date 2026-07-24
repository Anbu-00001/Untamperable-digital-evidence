package com.realitylock.app.capture

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.model.EventMetadata
import com.realitylock.app.capture.model.MediaData
import com.realitylock.app.capture.model.TimestampData
import com.realitylock.app.capture.store.EventRepository
import com.realitylock.app.core.config.CaptureConfig
import com.realitylock.app.core.time.ClockCorrelator
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates a single capture: takes the frame produced by [CameraController]
 * and binds the surrounding context into one [CapturedEvent], then persists it.
 *
 * Ordering matters and follows research/02 §8:
 *  1. normalise the camera timestamp onto the sensor/boot-time clock base,
 *  2. sample the clock offset as close to the shutter as possible,
 *  3. select the sensor sample nearest the frame's capture instant,
 *  4. resolve location (bounded by a timeout so the UI cannot hang),
 *  5. write the immutable media file,
 *  6. persist the metadata sidecar.
 *
 * Phase 3 extends this with hashing, the Merkle root, and the signature; the
 * shape assembled here is already the proof package's media+metadata subset.
 */
class CaptureCoordinator(
    private val clockCorrelator: ClockCorrelator,
    private val sensors: SensorSnapshotCollector,
    private val locationSource: LocationSource,
    private val mediaFileStore: MediaFileStore,
    private val repository: EventRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Records [frame] as a persisted event.
     *
     * @param includeLocation false when the user has not granted location
     *        permission — the event is still recorded, with location omitted
     *        and the omission visible rather than silently faked.
     */
    suspend fun record(frame: CapturedFrame, includeLocation: Boolean): CapturedEvent {
        val eventId = eventIdFactory()

        // 1. Normalise the camera's timestamp onto the boot-time base that
        //    sensors and the wall-clock offset both use. Skipping this silently
        //    backdates the capture by the device's accumulated deep sleep on any
        //    camera that reports CLOCK_MONOTONIC (see ClockCorrelator).
        val captureElapsedRealtimeNanos = clockCorrelator.toElapsedRealtimeNanos(
            rawCaptureNanos = frame.rawTimestampNanos,
            isRealtimeSource = frame.isRealtimeTimestampSource,
            deepSleepOffsetNanos = clockCorrelator.deepSleepOffsetNanos(),
        )

        // 2. Correlate that instant with wall-clock time.
        val offsetMillis = clockCorrelator.snapshotOffsetMillis()
        val wallClockMillis =
            clockCorrelator.toWallClockMillis(captureElapsedRealtimeNanos, offsetMillis)

        // 3. Motion sample closest to the shutter (now genuinely a shared base).
        val motion = sensors.snapshotNearest(captureElapsedRealtimeNanos)

        // 4. Location, bounded so a slow GNSS fix cannot stall the capture.
        val platformLocation = if (includeLocation) {
            withTimeoutOrNull(CaptureConfig.LOCATION_REQUEST_TIMEOUT_MILLIS) {
                locationSource.awaitCurrentLocation()
            }
        } else {
            null
        }
        val location = platformLocation?.let {
            LocationSource.toLocationData(it, captureElapsedRealtimeNanos)
        }

        // 5. Write the media exactly once; it is never modified afterwards.
        val mediaFile = mediaFileStore.write(eventId, frame.jpegBytes)

        val event = CapturedEvent(
            eventId = eventId,
            mediaFilePath = mediaFile.absolutePath,
            media = MediaData(
                mimeType = CaptureConfig.MIME_TYPE_JPEG,
                byteLength = frame.jpegBytes.size.toLong(),
            ),
            metadata = EventMetadata(
                location = location,
                timestamp = TimestampData(
                    wallClockMillis = wallClockMillis,
                    iso8601 = ClockCorrelator.toIso8601Utc(wallClockMillis),
                    elapsedRealtimeNanos = captureElapsedRealtimeNanos,
                    wallClockOffsetMillis = offsetMillis,
                    gpsTimeMillis = platformLocation?.time,
                ),
                motion = motion,
                device = deviceInfoProvider.current(),
            ),
        )

        // 6. Persist the metadata sidecar next to the media.
        return repository.save(event)
    }
}
