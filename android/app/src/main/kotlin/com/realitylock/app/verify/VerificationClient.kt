package com.realitylock.app.verify

import com.realitylock.app.core.config.SyncConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Asks the backend to verify a proof package and returns the per-check breakdown.
 *
 * Verification runs on the **server**, not in the app, and that is the point: a
 * verdict produced by the same application that produced the package would prove
 * nothing to anyone. The app is one client of an independent verifier.
 *
 * The package is sent as the exact stored bytes, for the same reason the sync
 * uploader does (ADR-0006 §2) — re-encoding a signed document is how you break
 * its hash.
 *
 * The media is deliberately **not** uploaded here. If the event has been synced
 * the server already holds the bytes and uses its own copy; if it has not, the
 * media check honestly reports `unavailable` and the verdict is `incomplete`.
 * Base64-ing a multi-megabyte JPEG into a verification request to avoid that
 * would cost a third more bandwidth to tell us something the sync already does.
 */
class VerificationClient(
    private val client: OkHttpClient,
    baseUrl: String,
) {

    private val base: HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) {
        "REALITYLOCK_BACKEND_BASE_URL is not a valid URL: \"$baseUrl\""
    }

    sealed interface Result {
        data class Ok(val report: VerificationReport) : Result

        /** The verifier could not be reached, or answered unintelligibly. */
        data class Unreachable(val reason: String) : Result
    }

    /** POSTs the stored package bytes to `/verify`. Blocking; call off the main thread. */
    fun verify(packageBytes: ByteArray): Result {
        val url = base.newBuilder().addPathSegments(SyncConfig.VERIFY_PATH).build()
        val request = Request.Builder()
            .url(url)
            .post(packageBytes.toRequestBody(SyncConfig.CONTENT_TYPE_JSON.toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                // Non-null in OkHttp 5 (it was nullable in 4.x).
                val body = response.body.string()
                if (body.isBlank()) {
                    return Result.Unreachable("verifier returned an empty body (${response.code})")
                }
                // A 400 still carries a usable answer — `invalid_format` with the
                // schema errors — so it is parsed rather than treated as a failure
                // to reach the verifier.
                runCatching { Result.Ok(parseReport(JSONObject(body))) }
                    .getOrElse { Result.Unreachable("could not parse the verifier response: ${it.message}") }
            }
        } catch (e: IOException) {
            Result.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The verification URL a QR badge encodes, for `GET /verify/<eventId>`. */
    fun verificationUrl(eventId: String): String =
        base.newBuilder()
            .addPathSegments(SyncConfig.VERIFY_PATH)
            .addPathSegment(eventId)
            .build()
            .toString()

    private companion object {
        const val KEY_VERDICT = "verdict"
        const val KEY_CHECKS = "checks"
        const val KEY_NOTES = "notes"
        const val KEY_ADVISORIES = "advisories"
        const val KEY_LIMITATIONS = "limitations"
        const val KEY_MERKLE_ROOT = "merkleRoot"
    }

    /**
     * Maps the JSON response onto [VerificationReport].
     *
     * Checks are sorted into [VerificationReport.DISPLAY_ORDER] rather than left in
     * the order `JSONObject.keys()` yields them: that iteration order is arbitrary
     * (HashMap-backed), so relying on it produced a different sequence on every
     * call. Unknown check names are kept and appended.
     */
    internal fun parseReport(json: JSONObject): VerificationReport {
        val checksJson = json.optJSONObject(KEY_CHECKS)
        val checks = buildList {
            checksJson?.keys()?.forEach { name ->
                add(
                    VerificationReport.Check(
                        name = name,
                        outcome = VerificationReport.Outcome.parse(checksJson.optString(name)),
                    ),
                )
            }
        }

        return VerificationReport(
            verdict = VerificationReport.Verdict.parse(json.optString(KEY_VERDICT)),
            checks = VerificationReport.sortForDisplay(checks),
            notes = json.stringList(KEY_NOTES),
            advisories = json.stringList(KEY_ADVISORIES),
            limitations = json.stringList(KEY_LIMITATIONS),
            merkleRoot = json.optString(KEY_MERKLE_ROOT).takeIf { it.isNotEmpty() },
        )
    }
}

/** Reads a JSON array of strings, tolerating an absent key. */
private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
}
