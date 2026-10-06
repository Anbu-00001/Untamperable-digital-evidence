package com.realitylock.app.places

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.realitylock.app.core.config.GeocodingConfig
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Turns coordinates into a place name by asking a real geocoder — never by
 * guessing, and never by inventing a label.
 *
 * Order: the phone's own geocoder first (Google's, via Play services), then
 * OpenStreetMap's Nominatim. If both fail — no network, no answer — [resolve]
 * returns null and the screen shows the coordinates alone. A failure is
 * deliberately NOT cached as an answer: it only suppresses retries briefly, so a
 * place looked up offline is found as soon as the phone is back online.
 *
 * A successful answer is cached on disk, so a place is asked about once and keeps
 * showing offline afterwards. The name is a third party's statement about where
 * the coordinates fall; it is not part of the signed record and the UI says so.
 */
class AddressResolver(
    private val context: Context,
    client: OkHttpClient,
    private val cacheDir: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // A short call timeout of its own: the app-wide client waits up to 75 s for a
    // cold backend, which would leave an address line spinning far too long.
    private val http: OkHttpClient = client.newBuilder()
        .callTimeout(GeocodingConfig.LOOKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    private val memory = ConcurrentHashMap<String, PlaceName>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    // Serialises lookups, which is also what keeps Nominatim under one request/second.
    private val gate = Mutex()
    private var lastNominatimAt = 0L

    suspend fun resolve(latitude: Double, longitude: Double): PlaceName? {
        val key = PlaceNames.cacheKey(latitude, longitude)
        memory[key]?.let { return it }
        withContext(io) { readDisk(key) }?.let { memory[key] = it; return it }

        failedAt[key]?.let { at ->
            if (nowMillis() - at < GeocodingConfig.RETRY_AFTER_FAILURE_MILLIS) return null
        }

        return gate.withLock {
            memory[key]?.let { return@withLock it }
            // Rounded BEFORE leaving the phone.
            val lat = PlaceNames.rounded(latitude)
            val lon = PlaceNames.rounded(longitude)
            val found = withContext(io) { fromDevice(lat, lon) ?: fromNominatim(lat, lon) }
            if (found != null) {
                memory[key] = found
                failedAt.remove(key)
                withContext(io) { writeDisk(key, found) }
            } else {
                failedAt[key] = nowMillis()
            }
            found
        }
    }

    private suspend fun fromDevice(lat: Double, lon: Double): PlaceName? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.ENGLISH)
        val addresses: List<Address>? = withTimeoutOrNull(GeocodingConfig.LOOKUP_TIMEOUT_MILLIS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lon, 1) }.getOrNull()
            }
        }
        val address = addresses?.firstOrNull() ?: return null
        val line = address.getAddressLine(0)
        val short = PlaceNames.fromGeocoderFields(
            address.subLocality, address.locality, address.subAdminArea, address.adminArea, line,
        ) ?: return null
        return PlaceName(short, line ?: short, PlaceName.Source.DEVICE_GEOCODER)
    }

    private suspend fun fromNominatim(lat: Double, lon: Double): PlaceName? {
        val wait = GeocodingConfig.NOMINATIM_MIN_INTERVAL_MILLIS - (nowMillis() - lastNominatimAt)
        if (wait > 0) delay(wait)
        lastNominatimAt = nowMillis()
        val url = GeocodingConfig.NOMINATIM_REVERSE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lon.toString())
            .addQueryParameter("zoom", "16")
            .addQueryParameter("addressdetails", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GeocodingConfig.USER_AGENT)
            .header("Accept-Language", "en")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else PlaceNames.fromNominatim(response.body.string())
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun readDisk(key: String): PlaceName? = runCatching {
        val file = File(cacheDir, "$key.json").takeIf { it.isFile } ?: return null
        val json = JSONObject(file.readText())
        // An entry written by an older labelling rule is treated as a miss, so a
        // change to how short names are built takes effect without a manual wipe.
        if (json.optInt(KEY_VERSION, 0) != GeocodingConfig.CACHE_VERSION) return null
        PlaceName(
            short = json.getString(KEY_SHORT),
            full = json.getString(KEY_FULL),
            source = PlaceName.Source.valueOf(json.getString(KEY_SOURCE)),
        )
    }.getOrNull()

    private fun writeDisk(key: String, place: PlaceName) {
        runCatching {
            cacheDir.mkdirs()
            File(cacheDir, "$key.json").writeText(
                JSONObject()
                    .put(KEY_SHORT, place.short)
                    .put(KEY_FULL, place.full)
                    .put(KEY_SOURCE, place.source.name)
                    .put(KEY_VERSION, GeocodingConfig.CACHE_VERSION)
                    .toString(),
            )
        }
    }

    private companion object {
        const val KEY_SHORT = "short"
        const val KEY_FULL = "full"
        const val KEY_SOURCE = "source"
        const val KEY_VERSION = "v"
    }
}
