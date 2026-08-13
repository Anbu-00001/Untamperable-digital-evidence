package com.realitylock.app.forensics

import android.graphics.Bitmap
import android.media.FaceDetector

/**
 * Decides whether an image contains a face the deepfake classifier may be run on.
 *
 * ## Why a gate exists at all
 *
 * Meso-4 was trained exclusively on cropped facial forgeries. Handed a photograph
 * of a street, a document or a receipt it still returns a confident-looking number
 * between 0 and 1, and that number means nothing. Printing it anyway would be the
 * worst kind of output this project can produce: an authoritative-looking figure
 * with no referent, next to cryptographic results that *are* meaningful.
 *
 * So the classifier does not run unless a face is found. "We did not analyse this"
 * is a true statement; a score on a landscape is not.
 *
 * ## Why `android.media.FaceDetector` and not ML Kit
 *
 * ML Kit is the better detector, and it was still the wrong choice here:
 *
 *  - **Unbundled** (~800 KB) downloads its model through Play Services on first
 *    use. This app is offline-first by design — the whole capture pipeline works
 *    in airplane mode — so the feature would be silently unavailable in exactly
 *    the conditions the app is built for.
 *  - **Bundled** (~6.9 MB) works offline but costs sixty times the size of the
 *    classifier it is gating, for a stretch feature marked experimental.
 *
 * `android.media.FaceDetector` ships in the platform: no dependency, no download,
 * no Play Services, works offline forever. It is old and weak, and that is
 * tolerable *because of which way it fails*. A weak detector mostly produces
 * **false negatives** — it misses faces — and a missed face means the score is
 * withheld. Withholding is this project's safe direction (ADR-0005, ADR-0006 §5).
 * A stronger detector would buy more scores, and more scores is not the goal.
 *
 * ## Why this gate does NOT crop to the face
 *
 * It did, in the first version, using a square box of 2.6x the eye separation —
 * an approximation of the FaceForensics++ crops Meso-4 was trained on. Measuring
 * it killed the idea. Scoring the authors' own reference images at progressively
 * tighter centre crops:
 *
 *     crop      df00204  df01254  real00240  real00772
 *     1.00       0.0414   0.0487     0.9897     0.9977   <- reference framing
 *     0.80       0.4239   0.5934     0.9649     0.9842
 *     0.60       0.7251   0.7260     0.7538     0.5611   <- fake now beats real
 *
 * At a 60% crop the model is **worse than a coin flip**: `df01254` (0.726) scores
 * higher than `real00772` (0.561). Framing is not a tuning detail for this model,
 * it is load-bearing, and a crop factor that cannot be validated against a
 * labelled dataset must not sit in front of it. The device test caught this — a
 * genuine reference image scored 0.178 instead of 0.990.
 *
 * So the gate answers one question, "is there a face at all", and the classifier
 * sees the whole image, exactly as the authors' own `example.py` feeds it. The
 * cost is stated rather than hidden: a photograph where the face is small in
 * frame is also outside the training distribution, and its score means little.
 * That is a limitation to disclose, not a reason to substitute a crop that is
 * measurably worse on the only labelled images available.
 */
class FaceGate(private val maxFaces: Int = MAX_FACES) {

    sealed interface Result {
        /**
         * A face was found. Deliberately carries NO crop rectangle — see the
         * class header on why this gate does not crop.
         */
        data class Found(val confidence: Float) : Result

        /** No face found — the classifier must not run. */
        data object NoFace : Result

        /** The detector could not be run at all; distinct from finding nothing. */
        data class Unavailable(val reason: String) : Result
    }

    fun detect(source: Bitmap): Result {
        // FaceDetector accepts ONLY RGB_565 and throws on anything else, and it
        // requires an EVEN width — an odd-width bitmap raises
        // IllegalArgumentException from native code. Both are undocumented enough
        // to look like a bug in our code when they fire.
        val evenWidth = source.width - (source.width % 2)
        if (evenWidth < MIN_DIMENSION || source.height < MIN_DIMENSION) {
            return Result.Unavailable("image is too small to search for a face")
        }

        val rgb565 = try {
            val cropped =
                if (evenWidth == source.width) source
                else Bitmap.createBitmap(source, 0, 0, evenWidth, source.height)
            cropped.copy(Bitmap.Config.RGB_565, false)
                ?: return Result.Unavailable("image could not be converted for face search")
        } catch (e: Exception) {
            return Result.Unavailable(e.message ?: "image could not be prepared for face search")
        }

        val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
        val found = try {
            FaceDetector(rgb565.width, rgb565.height, maxFaces).findFaces(rgb565, faces)
        } catch (e: Exception) {
            return Result.Unavailable(e.message ?: "the face detector failed")
        } finally {
            if (rgb565 !== source) rgb565.recycle()
        }

        if (found <= 0) return Result.NoFace

        // The largest face, by eye separation. With several people in frame the
        // biggest is the one the photograph is most likely about, and scoring an
        // arbitrary bystander's face would be both less useful and more invasive.
        val best = faces.filterNotNull().take(found).maxByOrNull { it.eyesDistance() }
            ?: return Result.NoFace

        return Result.Found(best.confidence())
    }

    companion object {
        const val MAX_FACES = 4

        /** FaceDetector needs a few pixels to work with; below this it is pointless. */
        const val MIN_DIMENSION = 32
    }
}
