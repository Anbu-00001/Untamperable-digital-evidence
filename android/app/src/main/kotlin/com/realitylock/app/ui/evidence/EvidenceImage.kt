package com.realitylock.app.ui.evidence

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The outcome of trying to load a captured photograph off disk.
 *
 * There are four states rather than a nullable [Bitmap], and the split between
 * [Missing] and [Unreadable] is the one that earns its keep. "The file is gone"
 * and "the file is here but would not decode" are different facts about an
 * evidence record: the first says the capture no longer exists on this device,
 * the second says something is wrong with bytes that are still present. Folding
 * them into `null` would have the UI print one sentence for two situations, and
 * the wrong one half the time.
 *
 * There is deliberately no "empty" state. A viewer that renders nothing when a
 * load fails is indistinguishable from a photograph of a dark room, which is the
 * failure mode this whole type exists to prevent.
 */
sealed interface EvidenceImage {

    /** Decode has been dispatched but has not come back yet. */
    data object Loading : EvidenceImage

    /**
     * A decoded, correctly-oriented bitmap, already downsampled to roughly the
     * size it will be drawn at.
     */
    data class Loaded(val bitmap: Bitmap) : EvidenceImage

    /** No file at the recorded path. */
    data object Missing : EvidenceImage

    /**
     * The file exists but could not be turned into an image. [reason] is the
     * platform's own message where it offered one — shown to the user rather
     * than swallowed, because "could not read the file" with no detail is the
     * kind of message that makes a working app look broken.
     */
    data class Unreadable(val reason: String?) : EvidenceImage
}

/**
 * Decodes captured JPEGs for display, off the main thread and downsampled.
 *
 * ## Why this is not just `BitmapFactory.decodeFile`
 *
 * Captures are full-resolution phone photographs — routinely 12 MP, which is
 * ~48 MB once expanded to ARGB_8888. Decoding one of those on the main thread
 * takes long enough to trip the ANR watchdog, and holding several of them (a
 * scrolling history list) exhausts the heap outright. Both problems are solved
 * here rather than at each call site:
 *
 * - **Off the main thread.** [decode] is a `suspend` function whose entire body
 *   runs under [Dispatchers.IO]. There is no non-suspending entry point, so a
 *   caller cannot accidentally do this work in composition.
 * - **Downsampled at decode time**, not after. `inSampleSize` tells the decoder
 *   to skip pixels while reading, so the full-size bitmap is never allocated at
 *   all. Decoding at full size and scaling afterwards would allocate the 48 MB
 *   first, which is the allocation we cannot afford.
 *
 * ## No image-loading library
 *
 * Coil/Glide/Picasso would each do this and more, and each is a new dependency
 * on a project that has been deliberate about every one it takes. What is needed
 * here is two `BitmapFactory` passes and an LRU map, so the platform API is used
 * directly.
 */
object EvidenceImageDecoder {

    /**
     * Reads [path] and returns a bitmap no smaller than [targetWidthPx] x
     * [targetHeightPx] but as close to it as power-of-two subsampling allows.
     *
     * @param dispatcher injectable so a test can pin the work to a deterministic
     *        thread; production callers never pass it.
     */
    suspend fun decode(
        path: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): EvidenceImage = withContext(dispatcher) { decodeBlocking(path, targetWidthPx, targetHeightPx) }

    private fun decodeBlocking(
        path: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): EvidenceImage {
        val file = File(path)
        if (!file.isFile) return EvidenceImage.Missing

        return try {
            // Pass 1: header only. `inJustDecodeBounds` reads the dimensions
            // without allocating any pixel memory, which is what makes it safe to
            // do this before knowing how big the image is.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                // Bounds decoding does not throw on a corrupt file; it reports
                // -1 dimensions. Treated as unreadable rather than passed on to
                // pass 2, which would return null and lose the distinction.
                return EvidenceImage.Unreadable(null)
            }

            // Pass 2: the real decode, subsampled.
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    sourceWidthPx = bounds.outWidth,
                    sourceHeightPx = bounds.outHeight,
                    targetWidthPx = targetWidthPx,
                    targetHeightPx = targetHeightPx,
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
                ?: return EvidenceImage.Unreadable(null)

            EvidenceImage.Loaded(applyExifOrientation(file, decoded))
        } catch (error: OutOfMemoryError) {
            // Catching an Error is normally wrong, and is right here: OOM from a
            // bitmap decode is a recoverable, *expected* outcome of reading an
            // untrusted-size file, not a sign the process is unsound. Letting it
            // propagate would kill the app over one oversized photograph.
            EvidenceImage.Unreadable(error.javaClass.simpleName)
        } catch (error: Exception) {
            EvidenceImage.Unreadable(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Applies the JPEG's own EXIF orientation tag.
     *
     * CameraX writes the capture rotation into EXIF rather than rotating pixels,
     * and `BitmapFactory` ignores that tag entirely — so without this step a
     * photograph taken in the ordinary portrait grip displays on its side. This
     * is a *display* transform only: the stored file is never touched, so the
     * media hash the proof package commits to is unaffected.
     *
     * Falls back to the un-rotated bitmap on any failure. A sideways photograph
     * is a far better outcome than no photograph.
     */
    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            file.inputStream().use(::ExifInterface)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(DEGREES_90)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(DEGREES_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(DEGREES_270)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(DEGREES_90)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(DEGREES_270)
                matrix.postScale(-1f, 1f)
            }
            // ORIENTATION_NORMAL, ORIENTATION_UNDEFINED, and anything unrecognised.
            else -> return bitmap
        }

        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { rotated ->
                    // THE one place `recycle()` is correct in this package. The
                    // source bitmap was created two lines up, has never been
                    // handed to Compose, and nothing else can hold a reference to
                    // it — so freeing it immediately halves peak memory during
                    // rotation. Contrast the cache and the viewer, which never
                    // recycle: a bitmap Compose might still be drawing throws
                    // "Canvas: trying to use a recycled bitmap" if recycled.
                    if (rotated !== bitmap) bitmap.recycle()
                }
        }.getOrDefault(bitmap)
    }

    /**
     * The power-of-two subsampling factor that gets closest to the target
     * without going under it.
     *
     * The loop stops one step before either dimension would fall below what was
     * asked for, so the result is always >= the requested size — undershooting
     * would show a visibly soft image, and the point of a downsample is to save
     * memory, not to degrade the evidence on screen. Only powers of two are
     * considered because that is all `inSampleSize` honours; the decoder rounds
     * anything else down to one.
     *
     * Internal rather than private so it can be unit-tested directly: it is pure
     * arithmetic with no Android dependency, and getting it wrong by one
     * doubling is the difference between a 1 MB and a 4 MB allocation per row.
     *
     * Returns 1 for any non-positive input rather than throwing — a corrupt
     * header must produce a bad decode, not a crash in the sizing helper.
     */
    internal fun calculateInSampleSize(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Int {
        if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return NO_SUBSAMPLING
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return NO_SUBSAMPLING
        if (sourceWidthPx <= targetWidthPx && sourceHeightPx <= targetHeightPx) return NO_SUBSAMPLING

        val halfWidth = sourceWidthPx / 2
        val halfHeight = sourceHeightPx / 2
        var sample = NO_SUBSAMPLING
        while (halfWidth / sample >= targetWidthPx && halfHeight / sample >= targetHeightPx) {
            sample *= 2
        }
        return sample
    }

    private const val NO_SUBSAMPLING = 1
    private const val DEGREES_90 = 90f
    private const val DEGREES_180 = 180f
    private const val DEGREES_270 = 270f
}
