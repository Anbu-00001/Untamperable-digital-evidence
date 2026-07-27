package com.realitylock.app.core.config

/**
 * Tuning for the Phase-4 location-integrity checks. Centralized so a threshold
 * is never a magic number inside logic.
 *
 * The values reflect what Phase-4 research actually established, not the first
 * draft of the plan:
 *  - The `research/09` plan proposed flagging implied speed > 300 km/h. That is
 *    too low: high-speed rail already runs ~300 km/h and jet-stream-boosted
 *    commercial flights reach ~1300 km/h *ground* speed. A 300 km/h bound would
 *    false-positive on ordinary air travel, so the threshold is 1500 km/h — well
 *    clear of legitimate travel while still catching the tens-of-thousands-of-
 *    km/h implied speed a location spoofer produces.
 */
object IntegrityConfig {

    /** Mean Earth radius for the Haversine great-circle distance (metres). */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /** Implied speed above this between consecutive events is "teleportation". */
    const val MAX_PLAUSIBLE_SPEED_KMH: Double = 1_500.0

    /**
     * Below this gap between two fixes, speed is not computed: dividing a small
     * distance by a near-zero interval turns GPS jitter into a false huge speed.
     * The result is reported as "not determinable" (null), never as implausible.
     */
    const val MIN_ELAPSED_MILLIS_FOR_SPEED: Long = 1_000L

    /**
     * Minimum separation before a speed is trusted. Two fixes closer than this
     * are within normal GNSS scatter, so their implied speed is meaningless.
     */
    const val MIN_DISTANCE_METERS_FOR_SPEED: Double = 50.0

    /**
     * Named checks that actually run, recorded in `integrity.location.mockDetectionChecks`.
     *
     * Every name declared here must be reachable from [CaptureCoordinator]. A
     * constant sitting under this comment unreferenced is a check the project
     * claims and does not perform — which is the precise failure mode ADR-0005 §1
     * exists to prevent. `CHECK_GNSS_CAPABILITY_PROBE` was removed on those
     * grounds: `GnssCapabilityProbe` reports device capability for the diagnostics
     * screen and takes no part in a capture's integrity record, which is why
     * `gnssChecked` is written as false.
     */
    const val CHECK_IS_MOCK: String = "location.isMock"
    const val CHECK_SPEED_PLAUSIBILITY: String = "speed_distance_plausibility"

    const val MILLIS_PER_HOUR: Double = 3_600_000.0
    const val METERS_PER_KM: Double = 1_000.0
}
