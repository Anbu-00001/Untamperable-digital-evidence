package com.realitylock.app.core.config

/**
 * Where the place name shown beside a capture's coordinates comes from.
 *
 * The coordinates are the evidence; the place name is only a convenience for a
 * human reading them, looked up from a third party AFTER the fact. It is never
 * written into the proof package and never signed, and the UI says so. Every
 * value that shapes that lookup lives here rather than at the call site.
 */
object GeocodingConfig {

    /**
     * Coordinates are rounded to this many decimals BEFORE leaving the phone:
     * four places is about 11 m, plenty to name a locality, and it sends the
     * lookup service less than the full-precision fix.
     */
    const val COORDINATE_DECIMALS: Int = 4

    /** OpenStreetMap's public reverse-geocoding endpoint (used when the device has none). */
    const val NOMINATIM_REVERSE_URL: String = "https://nominatim.openstreetmap.org/reverse"

    /**
     * Nominatim's usage policy requires an identifying User-Agent and at most one
     * request per second. Results are cached on disk, so a given place is only
     * ever asked about once.
     */
    const val USER_AGENT: String =
        "RealityLock-StudentProject/0.1 (github.com/Anbu-00001/Untamperable-digital-evidence)"
    const val NOMINATIM_MIN_INTERVAL_MILLIS: Long = 1_100L

    /** Neither lookup may hold a screen's address line up for long. */
    const val LOOKUP_TIMEOUT_MILLIS: Long = 8_000L

    /** After a failed lookup (typically no network), do not retry the same place for this long. */
    const val RETRY_AFTER_FAILURE_MILLIS: Long = 30_000L

    /** Bump when the rule that builds a short place name changes; older cache entries are then ignored. */
    const val CACHE_VERSION: Int = 5

    /** Subdirectory of `filesDir` holding the lookup cache. */
    const val CACHE_SUBDIR: String = "places"
}
