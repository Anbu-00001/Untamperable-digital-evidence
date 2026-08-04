'use strict';

const config = require('../config');

/**
 * Google's certificate revocation status list for hardware key attestation.
 *
 * ## Why this is a cached snapshot rather than a fetch per verification
 *
 * `verifyProofPackage` is synchronous, and making it async to await an HTTP call
 * would ripple through every route and test for no gain: the endpoint itself
 * declares `Cache-Control: public, max-age=86400`, so a per-request fetch would
 * be re-downloading the same 170 KB document. The list is refreshed in the
 * background and verification reads whatever snapshot is in memory.
 *
 * ## The rule that matters
 *
 * A snapshot that is missing, stale beyond `maxAgeMillis`, or never fetched
 * yields **`unavailable`** — never "not revoked". Revocation is the one check
 * here that fails *open*: absence of an answer looks exactly like a clean
 * answer, and reporting a compromised key as fine because a network call failed
 * would be the worst outcome this service can produce.
 *
 * ## Serial number encoding — the part that is easy to get silently wrong
 *
 * Google documents the dictionary key as "the certificate serial number in
 * lowercase hex". Node's `X509Certificate.serialNumber` is **uppercase**, so a
 * direct lookup matches nothing and every certificate reads as un-revoked.
 *
 * The published list is also not uniformly hex. Of 1732 entries observed on
 * 2026-08-03, 968 contain no `a`–`f` at all. For hex strings of that length the
 * expected count is about 0.1, so those keys are decimal renderings of the
 * serial, not hex. Probing only the documented form would therefore miss the
 * majority of the list.
 *
 * So every plausible rendering is probed — lowercase hex, with and without
 * leading zeros, plus decimal. A wrongly-matched entry would falsely accuse one
 * device and be immediately visible; a missed entry silently trusts a key that
 * Google says is compromised. Given that asymmetry, probing wide is the correct
 * bias.
 */

let snapshot = null; // { entries: Map<string, {status, reason}>, fetchedAtMillis }
let inFlight = null;

/** Every key form a serial might legitimately appear under. */
function candidateKeys(serialNumber) {
  const lower = String(serialNumber).toLowerCase();
  const keys = new Set([lower, lower.replace(/^0+/, '') || '0']);
  try {
    keys.add(BigInt(`0x${lower}`).toString(10));
  } catch {
    // A serial that is not parseable as hex simply contributes no decimal form.
  }
  return keys;
}

/** Replaces the in-memory snapshot. Also the injection point for tests. */
function setSnapshot(entries, fetchedAtMillis = Date.now()) {
  snapshot = { entries: new Map(Object.entries(entries || {})), fetchedAtMillis };
}

function clearSnapshot() {
  snapshot = null;
  inFlight = null;
}

/** True when a snapshot exists and is fresh enough to answer from. */
function isUsable(nowMillis = Date.now()) {
  if (!snapshot) return false;
  return nowMillis - snapshot.fetchedAtMillis <= config.attestation.revocation.maxAgeMillis;
}

/**
 * The revocation entry for [serialNumber], or null when the list holds none.
 *
 * Throws when no usable snapshot exists, so a caller cannot mistake "nothing
 * known" for "nothing wrong" — the distinction this whole module turns on.
 */
function lookup(serialNumber, nowMillis = Date.now()) {
  if (!isUsable(nowMillis)) {
    throw new Error('no usable revocation snapshot');
  }
  for (const key of candidateKeys(serialNumber)) {
    const entry = snapshot.entries.get(key);
    if (entry) return entry;
  }
  return null;
}

/**
 * Fetches the list and replaces the snapshot.
 *
 * Never throws: a failed refresh must leave the previous snapshot in place and
 * let the freshness rule above decide whether it is still usable. Concurrent
 * calls share one request.
 */
async function refresh({ fetchImpl = fetch, nowMillis = Date.now() } = {}) {
  if (inFlight) return inFlight;

  const { url } = config.attestation.revocation;
  inFlight = (async () => {
    try {
      const response = await fetchImpl(url);
      if (!response.ok) {
        return { ok: false, reason: `status list responded ${response.status}` };
      }
      const body = await response.json();
      if (!body || typeof body.entries !== 'object' || body.entries === null) {
        return { ok: false, reason: 'status list has no "entries" object' };
      }
      setSnapshot(body.entries, nowMillis);
      return { ok: true, count: Object.keys(body.entries).length };
    } catch (err) {
      return { ok: false, reason: err.message };
    } finally {
      inFlight = null;
    }
  })();

  return inFlight;
}

/** Kicks off a background refresh if enabled. Returns the promise for tests. */
function startBackgroundRefresh({ fetchImpl = fetch } = {}) {
  const { enabled, refreshMillis } = config.attestation.revocation;
  if (!enabled) return null;

  const tick = () => refresh({ fetchImpl });
  const timer = setInterval(tick, refreshMillis);
  // Do not hold the process open for a cache refresh.
  if (typeof timer.unref === 'function') timer.unref();
  return tick();
}

module.exports = {
  candidateKeys,
  setSnapshot,
  clearSnapshot,
  isUsable,
  lookup,
  refresh,
  startBackgroundRefresh,
};
