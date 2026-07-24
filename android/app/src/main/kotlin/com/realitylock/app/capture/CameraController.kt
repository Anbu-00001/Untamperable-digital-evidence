package com.realitylock.app.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A frame captured in memory together with the instant the sensor produced it.
 *
 * Not a `data class` on purpose: it holds a [ByteArray], and data-class equality
 * would compare arrays by reference, which is a subtle correctness trap.
 */
class CapturedFrame(
    val jpegBytes: ByteArray,
    /**
     * Raw `ImageInfo.timestamp`, in whatever base the camera declares. It is
     * **not** safe to compare against sensor timestamps until normalised —
     * see [isRealtimeTimestampSource] and `ClockCorrelator.toElapsedRealtimeNanos`.
     */
    val rawTimestampNanos: Long,
    /**
     * True when the camera declares `SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME`
     * (`CLOCK_BOOTTIME`, same base as sensors). False means `UNKNOWN`, i.e.
     * `CLOCK_MONOTONIC`, which pauses in deep sleep and must be corrected.
     */
    val isRealtimeTimestampSource: Boolean,
)

/**
 * Wraps CameraX for tamper-evident capture.
 *
 * Two deliberate choices (research/03 §2, research/01 §9):
 *  - **CameraX over raw Camera2**: the requirement here is reliable capture with
 *    a trustworthy timestamp across many devices, not manual sensor control.
 *  - **In-memory capture** via `OnImageCapturedCallback`, so the frame and its
 *    `ImageInfo.timestamp` are obtained before anything touches disk — far more
 *    precise than reading the wall clock inside the callback, which can lag the
 *    shutter by tens of milliseconds.
 *
 * **The camera's clock base is not assumed.** `ImageInfo.timestamp` is only on
 * the same base as `SensorEvent.timestamp` when the camera declares
 * `SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME`. A OnePlus CPH2591 declares
 * `UNKNOWN` (`CLOCK_MONOTONIC`), which pauses during deep sleep — treating it as
 * boot-time backdated captures by the device's accumulated sleep, measured at
 * 9.66 days on a real handset. The source is therefore queried per camera and
 * reported alongside every frame.
 *
 * There is deliberately **no import-from-gallery path**: only frames captured
 * through this controller can become proof packages, which closes the
 * "sign a pre-tampered file" hole that Truepic and eyeWitness both guard against.
 */
class CameraController(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Set in [bind] from the bound camera's declared characteristics. */
    private var isRealtimeTimestampSource: Boolean = false

    /** True once [bind] has completed and a capture can be taken. */
    val isReady: Boolean get() = imageCapture != null

    /** Binds preview + capture use cases to [lifecycleOwner]. */
    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = awaitCameraProvider()
        cameraProvider = provider

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            // Tamper-evidence cares about tight time correlation between the
            // shutter and the sensor snapshot, not maximum JPEG quality.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        provider.unbindAll()
        val camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture,
        )
        isRealtimeTimestampSource = readIsRealtimeTimestampSource(camera.cameraInfo)
        imageCapture = capture
    }

    /**
     * Reads the camera's declared timestamp base. Defaults to `false`
     * (`CLOCK_MONOTONIC`) when it cannot be determined: that is the conservative
     * choice, since assuming boot-time when it is not produces a silently
     * backdated capture, whereas the correction is a no-op on a device that has
     * not slept.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readIsRealtimeTimestampSource(cameraInfo: CameraInfo): Boolean = runCatching {
        Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    }.getOrDefault(false)

    /** Captures one frame. Throws [ImageCaptureException] on camera failure. */
    suspend fun capture(): CapturedFrame {
        val capture = checkNotNull(imageCapture) { "Camera is not bound; call bind() first" }
        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val frame = CapturedFrame(
                                jpegBytes = image.toJpegBytes(),
                                rawTimestampNanos = image.imageInfo.timestamp,
                                isRealtimeTimestampSource = isRealtimeTimestampSource,
                            )
                            continuation.resume(frame)
                        } catch (t: Throwable) {
                            continuation.resumeWithException(t)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }
    }

    /** Releases camera use cases and the capture executor. */
    fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        captureExecutor.shutdown()
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

    private fun ImageProxy.toJpegBytes(): ByteArray {
        val buffer = planes[0].buffer
        return ByteArray(buffer.remaining()).also(buffer::get)
    }
}
