package com.realitylock.app.places

import com.realitylock.app.core.config.GeocodingConfig
import org.json.JSONObject
import java.util.Locale

/**
 * A human-readable place for a pair of coordinates.
 *
 * [short] is what fits on a card — "Kannadapalayam, Tambaram". [full] is the
 * service's own address line. [source] says who answered, because a place name is
 * a third party's claim, not a measurement this app made.
 */
data class PlaceName(val short: String, val full: String, val source: Source) {
    enum class Source { DEVICE_GEOCODER, OPENSTREETMAP }
}

/**
 * The pure parts of the lookup — turning a service's answer into a [PlaceName] —
 * kept apart from any network or platform code so they can be unit-tested with
 * real response bodies.
 */
object PlaceNames {

    /** Cache and request key: coordinates rounded to [GeocodingConfig.COORDINATE_DECIMALS]. */
    fun cacheKey(latitude: Double, longitude: Double): String =
        "%.${GeocodingConfig.COORDINATE_DECIMALS}f_%.${GeocodingConfig.COORDINATE_DECIMALS}f"
            .format(Locale.ROOT, latitude, longitude)

    fun rounded(value: Double): Double =
        "%.${GeocodingConfig.COORDINATE_DECIMALS}f".format(Locale.ROOT, value).toDouble()

    /**
     * Joins up to two distinct, non-blank place levels, most local first.
     *
     * Callers pass every level the service gave them, finest to coarsest
     * (neighbourhood, locality, sub-district, district, state). Two levels are
     * enough to read as a place — "Kundrathur, Tambaram" — and a service that
     * names only one still yields one; one that names nothing yields null, and the
     * caller then shows coordinates alone rather than inventing a label.
     */
    fun shorten(vararg levels: String?): String? =
        levels.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(2)
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }

    /**
     * The sub-locality, when the geocoder's structured fields omit it but its own
     * address line carries it: the segment immediately BEFORE the locality, e.g.
     * "W47R+32Q, East Tambaram, Tambaram, Tamil Nadu 600073, India" with locality
     * "Tambaram" gives "East Tambaram".
     *
     * Only a segment that reads like an area name is accepted — no digits, no
     * street words, not a plus code — so "12, Main Rd, Chennai" yields nothing
     * rather than a door number. Everything returned is text the service itself
     * produced; nothing is invented.
     */
    fun neighbourhoodFromAddressLine(addressLine: String?, locality: String?): String? {
        if (addressLine.isNullOrBlank() || locality.isNullOrBlank()) return null
        val parts = addressLine.split(",").map { it.trim() }
        val at = parts.indexOfFirst { it.equals(locality.trim(), ignoreCase = true) }
        if (at <= 0) return null
        val candidate = parts[at - 1]
        val looksLikeArea = candidate.isNotEmpty() &&
            candidate.none(Char::isDigit) &&
            !STREET_WORDS.containsMatchIn(candidate)
        return candidate.takeIf { looksLikeArea }
    }

    /**
     * Combines a platform geocoder's fields into a short name.
     *
     * Some geocoders fill only one of `subLocality` / `locality`, or put the same
     * name in both, keeping the finer area only inside the address line. When the
     * fields give a single level, that name anchors a search for a finer area in
     * the line; when they give two different levels, they are used as they are.
     */
    fun fromGeocoderFields(
        subLocality: String?,
        locality: String?,
        subAdminArea: String?,
        adminArea: String?,
        addressLine: String?,
    ): String? {
        val sub = subLocality?.trim()?.takeIf(String::isNotEmpty)
        val loc = locality?.trim()?.takeIf(String::isNotEmpty)
        // One level only: either field is missing, or the service put the same name
        // in both (this phone's geocoder does that for Tambaram). The finer area, if
        // any, then lives only in the address line.
        return if (sub == null || loc == null || sub.equals(loc, ignoreCase = true)) {
            val anchor = loc ?: sub
            shorten(neighbourhoodFromAddressLine(addressLine, anchor), anchor, subAdminArea, adminArea)
        } else {
            shorten(sub, loc, subAdminArea, adminArea)
        }
    }

    private val STREET_WORDS = Regex(
        """\b(rd|road|st|street|ave|avenue|lane|ln|main|cross|highway|hwy|nh|flyover|bridge)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses a Nominatim `jsonv2` reverse-geocoding body, or null if it names no
     * place (an error object, an ocean, an unparseable body).
     */
    fun fromNominatim(body: String): PlaceName? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.has("error")) return null
        val address = json.optJSONObject("address") ?: return null

        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key -> address.optString(key, "").takeIf { it.isNotBlank() } }

        val short = shorten(
            first("suburb", "neighbourhood", "quarter", "village", "hamlet"),
            first("city", "town", "municipality", "city_district"),
            first("county", "state_district"),
            first("state"),
        ) ?: return null
        val full = json.optString("display_name", "").ifBlank { short }
        return PlaceName(short, full, PlaceName.Source.OPENSTREETMAP)
    }
}
