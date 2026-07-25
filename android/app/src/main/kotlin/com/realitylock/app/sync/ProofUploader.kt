package com.realitylock.app.sync

import com.realitylock.app.core.config.SyncConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Sends proof packages and their media to the backend.
 *
 * **The package is uploaded as the exact bytes stored on disk.** It is never
 * parsed into objects and re-serialized, because those bytes are what the
 * metadata hash and the signature cover: a re-encode that changed number
 * formatting, integer width, or escaping would break `metadataHashMatch` on the
 * server, and the failure would be indistinguishable from tampering. Forwarding
 * evidence verbatim removes that entire class of bug, and is the reason this
 * project has no Retrofit/Gson layer (ADR-0006 §2).
 *
 * Blocking OkHttp calls; callers run it off the main thread (the worker does).
 */
class ProofUploader(
    private val client: OkHttpClient,
    baseUrl: String,
) {

    /**
     * Resolved once so a malformed configured URL surfaces here rather than as a
     * confusing failure on every upload attempt.
     */
    private val base: HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) {
        "REALITYLOCK_BACKEND_BASE_URL is not a valid URL: \"$baseUrl\""
    }

    /** Outcome of one upload step, classified by what the caller should do next. */
    sealed interface Result {
        /** Stored (or already stored — the endpoint is idempotent). */
        data class Success(val alreadyPresent: Boolean, val storageRef: String? = null) : Result

        /**
         * Worth retrying: a network error, a timeout, or a 5xx. The condition is
         * outside our control and may simply pass.
         */
        data class Transient(val reason: String) : Result

        /**
          * Not worth retrying: the server rejected the content itself (4xx). No
          * number of retries will make a schema-invalid package valid, and hammering
          * the endpoint would only waste battery.
          */
        data class Permanent(val reason: String) : Result
    }

    /**
     * POSTs the proof package.
     *
     * @param packageBytes the sidecar file's contents, unmodified.
     */
    fun uploadPackage(packageBytes: ByteArray): Result {
        val url = base.newBuilder().addPathSegments(SyncConfig.PROOF_PATH).build()
        val request = Request.Builder()
            .url(url)
            .post(packageBytes.toRequestBody(SyncConfig.CONTENT_TYPE_JSON.toMediaType()))
            .build()

        return execute(request) { code, body ->
            val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            // 201 = newly created, 200 = already stored with identical content.
            // Both mean "the server holds this package", which is all we need.
            Result.Success(
                alreadyPresent = code == HTTP_OK,
                storageRef = json?.optString(KEY_STORAGE_REF)?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * POSTs the raw media bytes for an already-stored package.
     *
     * Streamed from the file rather than read into a ByteArray: a video could be
     * tens of megabytes, and holding it in RAM to upload it would risk an OOM for
     * no benefit.
     */
    fun uploadMedia(eventId: String, mediaFile: File): Result {
        if (!mediaFile.exists()) {
            // The media is gone from the device. Retrying cannot bring it back.
            return Result.Permanent("media file is missing: ${mediaFile.name}")
        }
        val url = base.newBuilder()
            .addPathSegments(SyncConfig.PROOF_PATH)
            .addPathSegment(eventId)
            .addPathSegment(SyncConfig.MEDIA_PATH_SEGMENT)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(mediaFile.asRequestBody(SyncConfig.CONTENT_TYPE_OCTET_STREAM.toMediaType()))
            .build()

        return execute(request) { _, body ->
            val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            Result.Success(
                alreadyPresent = false,
                storageRef = json?.optString(KEY_STORAGE_REF)?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * Runs the request and maps the outcome onto [Result].
     *
     * The 4xx/5xx split is the whole point: it decides whether WorkManager should
     * back off and try again or stop. Treating everything as retryable would spin
     * forever on a rejected package; treating everything as fatal would abandon
     * captures over a passing loss of signal.
     */
    private fun execute(request: Request, onSuccess: (Int, String?) -> Result): Result =
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                when {
                    response.isSuccessful -> onSuccess(response.code, body)
                    response.code >= HTTP_SERVER_ERROR ->
                        Result.Transient("server error ${response.code}: ${summarize(body)}")
                    else -> Result.Permanent("rejected ${response.code}: ${summarize(body)}")
                }
            }
        } catch (e: IOException) {
            // No connectivity, DNS failure, timeout, connection reset — all of
            // which the CONNECTED constraint and backoff exist to handle.
            Result.Transient(e.message ?: e.javaClass.simpleName)
        }

    /** Keeps a server error message short enough to store in the sync sidecar. */
    private fun summarize(body: String?): String {
        if (body.isNullOrBlank()) return "(no body)"
        val message = runCatching {
            val json = JSONObject(body)
            json.optString(KEY_MESSAGE).ifEmpty { json.optString(KEY_ERROR) }
        }.getOrNull()
        val text = message?.takeIf { it.isNotEmpty() } ?: body
        return text.take(MAX_ERROR_CHARS)
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_SERVER_ERROR = 500
        const val MAX_ERROR_CHARS = 200
        const val KEY_STORAGE_REF = "storageRef"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR = "error"
    }
}
