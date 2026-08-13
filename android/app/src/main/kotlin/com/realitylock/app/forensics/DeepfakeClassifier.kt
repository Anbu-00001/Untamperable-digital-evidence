package com.realitylock.app.forensics

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
// NOTE the split identity: the Maven coordinates are `com.google.ai.edge.litert`
// (the rename), but the Java package is still `org.tensorflow.lite`. Importing
// `com.google.ai.edge.litert.Interpreter` — the obvious guess — does not resolve,
// and the error reads as a missing dependency rather than a renamed artifact
// keeping its old namespace.
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The experimental Meso-4 deepfake classifier (ADR-0010).
 *
 * ## What it is, stated exactly
 *
 * Meso-4 (Afchar & Nozick, IEEE WIFS 2018), converted to TFLite from the authors'
 * own published `Meso4_DF.h5` Deepfake-set weights. Nothing retrained, no weights
 * invented. Architecture verified against the paper's 27,977 trainable parameters;
 * conversion fidelity `max |Keras - TFLite| = 4.17e-07`.
 *
 * **No accuracy has been measured on this project's data**, and this class must
 * never be made to imply otherwise. The published figures belong to the authors
 * and were obtained on their dataset, not on anything this app will see.
 *
 * ## The preprocessing is pinned, and that is not fussiness
 *
 * The reference scores were produced with **nearest-neighbour** resampling, which
 * is what `keras.utils.load_img(target_size=...)` uses by default. Re-scoring the
 * same four reference images through this same model file with other filters
 * moves the output by more than the int8 quantisation error the int8 build was
 * rejected over:
 *
 *     nearest    max deviation 0.0000   <- what the model was measured with
 *     bicubic                  0.0134
 *     bilinear                 0.0284
 *
 * `Bitmap.createScaledBitmap` defaults to `filter = true`, i.e. bilinear, and is
 * the obvious call to reach for. Using it would silently evaluate a different
 * image than the one the reference numbers describe, while looking correct. Hence
 * [RESAMPLING_FILTER] below is `false`, named, and asserted by a test.
 *
 * ## Never touches the proof pipeline
 *
 * Constructed with a Context and nothing else — no repository, no signer, no
 * coordinator. A verdict in this system is about whether bytes changed since
 * capture, which a classifier has no opinion on, so its output cannot reach one.
 */
class DeepfakeClassifier private constructor(
    private val interpreter: Interpreter,
) : Closeable {

    /**
     * A score, with everything a caller needs to describe it honestly.
     *
     * [realScore] runs 0..1 where **1 means REAL and 0 means DEEPFAKE** — the
     * convention comes from the authors' own training layout, where
     * `flow_from_directory` sorts `df` to class 0 and `real` to class 1. Inverting
     * it would flip every reading while still looking entirely plausible, so it is
     * asserted by a test against the authors' reference images.
     */
    data class Score(val realScore: Float, val faceConfidence: Float) {

        /**
         * A coarse band, deliberately not a verdict.
         *
         * There is no "fake"/"real" label anywhere in this class. The model has no
         * measured accuracy on this project's data, so a binary call would be an
         * assertion the evidence does not support. Bands describe where the number
         * fell; they do not decide anything.
         */
        val band: Band
            get() = when {
                realScore >= 0.8f -> Band.LEANS_UNMANIPULATED
                realScore <= 0.2f -> Band.LEANS_MANIPULATED
                else -> Band.INCONCLUSIVE
            }
    }

    enum class Band { LEANS_UNMANIPULATED, INCONCLUSIVE, LEANS_MANIPULATED }

    sealed interface Outcome {
        data class Scored(val score: Score) : Outcome

        /** No face, so the model was not run. Not a finding about the image. */
        data object NoFace : Outcome

        /** The classifier could not run. Distinct from "ran and found nothing". */
        data class Unavailable(val reason: String) : Outcome
    }

    /**
     * Scores [source] if — and only if — a face is found in it.
     *
     * Runs the gate first so the model is never fed an image it has no opinion
     * about. Returns [Outcome.NoFace] rather than a low-confidence score, because
     * a number that means nothing is worse than an admission of silence.
     */
    fun classify(source: Bitmap, gate: FaceGate = FaceGate()): Outcome {
        return when (val face = gate.detect(source)) {
            is FaceGate.Result.Unavailable -> Outcome.Unavailable(face.reason)
            FaceGate.Result.NoFace -> Outcome.NoFace
            is FaceGate.Result.Found -> {
                val input = try {
                    prepare(source)
                } catch (e: Exception) {
                    return Outcome.Unavailable(e.message ?: "the image could not be prepared")
                }
                val output = Array(1) { FloatArray(1) }
                try {
                    interpreter.run(input, output)
                } catch (e: Exception) {
                    return Outcome.Unavailable(e.message ?: "the model failed to run")
                }
                Outcome.Scored(Score(realScore = output[0][0], faceConfidence = face.confidence))
            }
        }
    }

    /**
     * Whole image -> 256x256 nearest-neighbour -> RGB floats in [0,1], NHWC.
     *
     * The whole image, not a crop around the detected face. See [FaceGate] — a
     * face-tight crop measurably destroyed the model's discrimination on the only
     * labelled images available, to the point of ranking a known fake above a
     * known real one.
     */
    private fun prepare(source: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, RESAMPLING_FILTER)

        val buffer = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNELS * Float.SIZE_BYTES)
            // TFLite reads little-endian; the JVM default is big-endian, and
            // getting this wrong produces garbage rather than an error.
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f) // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f) // G
            buffer.putFloat((pixel and 0xFF) / 255f) // B
        }
        buffer.rewind()

        if (scaled !== source) scaled.recycle()
        return buffer
    }

    override fun close() = interpreter.close()

    companion object {
        const val ASSET_NAME = "meso4_df.tflite"
        const val INPUT_SIZE = 256
        const val CHANNELS = 3

        /**
         * `false` = nearest neighbour. See the class header: this is the filter
         * the reference scores were measured with, and changing it changes the
         * answer by more than quantisation does.
         */
        const val RESAMPLING_FILTER = false

        /**
         * Opens the bundled model, or null when it cannot be loaded.
         *
         * Null rather than a throw: the classifier is an optional experimental
         * extra, and an app that refuses to start because a stretch-goal model is
         * missing would have its priorities backwards. The Analyze screen simply
         * omits the section.
         */
        fun create(context: Context): DeepfakeClassifier? = runCatching {
            DeepfakeClassifier(Interpreter(loadModel(context.assets)))
        }.getOrNull()

        /**
         * Memory-maps the model straight out of the APK.
         *
         * Requires the asset to be stored uncompressed — see the `noCompress`
         * entry in build.gradle.kts. Mapping avoids copying 116 KiB onto the heap
         * and is what LiteRT expects.
         */
        private fun loadModel(assets: AssetManager): ByteBuffer =
            assets.openFd(ASSET_NAME).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                }
            }
    }
}
