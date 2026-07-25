'use strict';

const config = require('../config');

/**
 * Verification checks 4 and 5 — timestamp and location plausibility
 * (research/02 §8 Step 10). These are the two that were reporting
 * `not_implemented` until Phase 5; see ADR-0006 §5 and §6.
 *
 * Design rule throughout: a check that CANNOT be performed returns
 * `unavailable`, never `pass`. "This is the first capture, so there is nothing
 * to compare against" and "this device teleported 4000 km in a minute" are
 * completely different statements and must not collapse into one boolean.
 *
 * Pure functions taking explicit inputs (including "now"), so every branch is
 * unit-testable without a clock or a database.
 */

const PASS = 'pass';
const FAIL = 'fail';
const UNAVAILABLE = 'unavailable';

const MILLIS_PER_NANO_DIVISOR = 1000000; // ns -> ms, matching ClockCorrelator.NANOS_PER_MILLI
const MILLIS_PER_HOUR = 3600000;
const METERS_PER_KM = 1000;

/**
 * Great-circle distance between two WGS-84 points, in metres (Haversine).
 *
 * Deliberately the same formula and the same Earth radius as Android's
 * `LocationPlausibility.haversineMeters`, so the device's advisory answer and
 * this authoritative one cannot differ by implementation. ~0.3–0.5% worst-case
 * error against the ellipsoid, negligible at a 1500 km/h decision boundary.
 */
function haversineMeters(lat1, lon1, lat2, lon2) {
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  // asin form; the min() guards a rounding overshoot above 1.0.
  const c = 2 * Math.asin(Math.min(1, Math.sqrt(a)));
  return config.plausibility.earthRadiusMeters * c;
}

/** Implied average speed in km/h to cover `distanceMeters` in `elapsedMillis`. */
function impliedSpeedKmh(distanceMeters, elapsedMillis) {
  return distanceMeters / METERS_PER_KM / (elapsedMillis / MILLIS_PER_HOUR);
}

/**
 * Timestamp plausibility — DECISIVE, so it only fails on things that are
 * genuinely impossible rather than merely odd.
 *
 * Three sub-checks, all derived from the package itself or the verifier's own
 * clock. There is deliberately no "captures cannot predate <some date>" rule:
 * that would be an arbitrary literal masquerading as a security property.
 *
 *  1. Self-consistency. The producer computes
 *         wallClockMillis = floor(elapsedRealtimeNanos / 1e6) + wallClockOffsetMillis
 *     (CaptureCoordinator step 2), so this is an EXACT integer identity, not an
 *     approximation. Checking it exactly is what makes it worth checking: it
 *     independently confirms the recorded instant was derived from the monotonic
 *     clock rather than written in by hand.
 *  2. The ISO-8601 rendering must denote the same instant as wallClockMillis.
 *  3. A capture cannot come from the future — beyond a configured skew, since an
 *     unsynchronised device clock is common and is not evidence of forgery.
 *
 * `gpsTimeMillis` is intentionally NOT decisive here; see gpsTimeAdvisory().
 *
 * @param {object} metadata      the package's signed metadata block
 * @param {number} nowMillis     the verifier's wall clock
 * @param {string[]} notes       appended to, explaining any failure
 * @returns {string} PASS | FAIL
 */
function checkTimestampPlausible(metadata, nowMillis, notes) {
  const ts = metadata.timestamp;

  // 1. The exact derivation identity, when the offset was recorded.
  if (ts.wallClockOffsetMillis !== null && ts.wallClockOffsetMillis !== undefined) {
    const derived =
      Math.floor(ts.elapsedRealtimeNanos / MILLIS_PER_NANO_DIVISOR) + ts.wallClockOffsetMillis;
    if (derived !== ts.wallClockMillis) {
      notes.push(
        `timestamp is not self-consistent: elapsedRealtimeNanos/1e6 + offset = ${derived}, ` +
          `but wallClockMillis is ${ts.wallClockMillis}`,
      );
      return FAIL;
    }
  }

  // 2. The human-readable rendering must not disagree with the machine value.
  const isoMillis = Date.parse(ts.iso8601);
  if (Number.isNaN(isoMillis)) {
    notes.push(`iso8601 "${ts.iso8601}" is not a parseable instant`);
    return FAIL;
  }
  if (isoMillis !== ts.wallClockMillis) {
    notes.push(
      `iso8601 ${ts.iso8601} denotes ${isoMillis}, which is not wallClockMillis ${ts.wallClockMillis}`,
    );
    return FAIL;
  }

  // 3. Nothing can be captured after the moment it is verified.
  const futureBy = ts.wallClockMillis - nowMillis;
  if (futureBy > config.plausibility.maxFutureSkewMillis) {
    notes.push(
      `capture claims to be ${futureBy} ms in the future, beyond the ` +
        `${config.plausibility.maxFutureSkewMillis} ms skew allowance`,
    );
    return FAIL;
  }

  return PASS;
}

/**
 * Compares satellite/provider-supplied time against the device wall clock.
 *
 * ADVISORY ONLY, and the asymmetry is the whole point: `Location.getTime()` from
 * a fused or network provider is frequently derived from the very system clock
 * under examination, so agreement proves close to nothing. A gross divergence,
 * on the other hand, is real evidence that the wall clock was wrong or moved.
 * Reporting this as a decisive check would give a noisy signal the power to
 * condemn a genuine package.
 *
 * @returns {string|null} an advisory message, or null when nothing to say
 */
function gpsTimeAdvisory(metadata) {
  const ts = metadata.timestamp;
  if (ts.gpsTimeMillis === null || ts.gpsTimeMillis === undefined) return null;

  // The fix was taken fixAgeMillis before the shutter, so that is the instant
  // gpsTimeMillis should correspond to — not the capture instant itself.
  const fixAge = metadata.location && metadata.location.fixAgeMillis != null
    ? metadata.location.fixAgeMillis
    : 0;
  const expected = ts.wallClockMillis - fixAge;
  const divergence = Math.abs(ts.gpsTimeMillis - expected);
  if (divergence <= config.plausibility.gpsTimeToleranceMillis) return null;

  return (
    `provider-supplied time differs from the device wall clock by ${divergence} ms ` +
    `(allowance ${config.plausibility.gpsTimeToleranceMillis} ms), which suggests the ` +
    'device clock was inaccurate or adjusted; treat the recorded instant with caution'
  );
}

/**
 * Location plausibility — recomputed from the signed metadata of two consecutive
 * events, which is why it needs stored history (ADR-0005 §2, ADR-0006 §6).
 *
 * This is the AUTHORITATIVE answer. The device's own
 * `integrity.location.speedPlausible` is advisory and unsigned; a tamperer can
 * set it to true and gain nothing, because this recomputation is what the
 * verdict listens to.
 *
 * Non-blocking by design (see verdict semantics in ADR-0006 §5): a first-ever
 * capture legitimately has nothing to compare against and must not be punished
 * for it.
 *
 * @param {object} metadata            this event's signed metadata
 * @param {object|null} previousMetadata  the signed metadata of the most recent
 *        earlier event from the SAME installId, or null if there is none
 * @param {string[]} notes
 * @returns {string} PASS | FAIL | UNAVAILABLE
 */
function checkLocationPlausible(metadata, previousMetadata, notes) {
  const here = metadata.location;
  if (!here) {
    notes.push('this event recorded no location, so there is nothing to cross-check');
    return UNAVAILABLE;
  }
  if (!previousMetadata || !previousMetadata.location) {
    notes.push(
      'no earlier located event from this install is stored, so the implied-speed ' +
        'cross-check could not be run',
    );
    return UNAVAILABLE;
  }

  const there = previousMetadata.location;
  const elapsed = metadata.timestamp.wallClockMillis - previousMetadata.timestamp.wallClockMillis;

  // Wall clock, not the monotonic clock: consecutive captures can straddle a
  // reboot, which resets elapsedRealtimeNanos and would make the interval
  // meaningless.
  if (elapsed < config.plausibility.minElapsedMillisForSpeed) {
    notes.push(
      `only ${elapsed} ms separates these captures — too close in time for an implied ` +
        'speed to mean anything',
    );
    return UNAVAILABLE;
  }

  const distance = haversineMeters(there.latitude, there.longitude, here.latitude, here.longitude);
  if (distance < config.plausibility.minDistanceMetersForSpeed) {
    // Within normal GNSS scatter — the device did not meaningfully move.
    return PASS;
  }

  const speed = impliedSpeedKmh(distance, elapsed);
  if (speed > config.plausibility.maxPlausibleSpeedKmh) {
    notes.push(
      `implied speed ${Math.round(speed)} km/h over ${Math.round(distance)} m in ${elapsed} ms ` +
        `exceeds the ${config.plausibility.maxPlausibleSpeedKmh} km/h plausibility bound — ` +
        'the location may be spoofed',
    );
    return FAIL;
  }

  return PASS;
}

module.exports = {
  haversineMeters,
  impliedSpeedKmh,
  checkTimestampPlausible,
  checkLocationPlausible,
  gpsTimeAdvisory,
  PASS,
  FAIL,
  UNAVAILABLE,
};
