'use strict';

/**
 * Pieces shared by every store driver. Sits *below* the drivers in the
 * dependency graph (drivers require this; the factory requires the drivers), so
 * nothing here may require a driver — that would be a require cycle.
 */

/** Rejected write that would mutate immutable stored evidence. */
class ConflictError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ConflictError';
    this.code = 'conflict';
    this.status = 409;
  }
}

/** Referenced a package that was never stored. */
class NotFoundError extends Error {
  constructor(message) {
    super(message);
    this.name = 'NotFoundError';
    this.code = 'not_found';
    this.status = 404;
  }
}

/**
 * Canonical byte form used to decide whether two submissions of the same
 * `eventId` are the *same package*.
 *
 * `JSON.stringify` with sorted keys is deliberate and sufficient here: this is
 * an equality test between two documents the store itself holds, not a hash
 * that has to agree with the Android producer. The RFC 8785 canonicalization
 * that must agree cross-platform is applied to `metadata` by the verifier, and
 * lives in proofVerifier.js.
 */
function stableStringify(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  const keys = Object.keys(value).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(',')}}`;
}

/** True when two packages are the same document, key order aside. */
function samePackage(a, b) {
  return stableStringify(a) === stableStringify(b);
}

/**
 * The most recent stored package from `installId` whose capture instant is
 * strictly earlier than `beforeWallClockMillis`.
 *
 * Shared so both drivers order history identically — the location plausibility
 * check compares against "the previous event", and two drivers disagreeing on
 * which event that is would make a verification result depend on deployment
 * configuration.
 *
 * Same install only: comparing positions recorded by two different devices says
 * nothing about whether either of them teleported.
 */
function selectPreviousPackage(packages, installId, beforeWallClockMillis, predicate) {
  let best = null;
  for (const pkg of packages) {
    if (!pkg || !pkg.metadata || pkg.metadata.device.installId !== installId) continue;
    const at = pkg.metadata.timestamp.wallClockMillis;
    if (at >= beforeWallClockMillis) continue;
    if (predicate && !predicate(pkg)) continue;
    if (best === null || at > best.metadata.timestamp.wallClockMillis) best = pkg;
  }
  return best;
}

/**
 * Predicate for the location cross-check: a usable predecessor is one that
 * actually recorded a position.
 *
 * Without this, "most recent earlier event" and "most recent earlier event we can
 * compare against" were the same query, and they are not the same question. An
 * attacker could suppress the implied-speed check entirely by making one capture
 * with location switched off immediately before the spoofed one — the verifier
 * would pick that empty predecessor, report `locationPlausible: unavailable`, and
 * advise that this "does not indicate a problem", while a perfectly comparable
 * located capture sat one position further back in the history.
 *
 * Skipping to the older located event is sound: the elapsed wall-clock gap is an
 * input to the implied-speed arithmetic, so a longer interval simply permits
 * proportionally more travel.
 */
function hasLocation(pkg) {
  return Boolean(pkg && pkg.metadata && pkg.metadata.location);
}

module.exports = {
  ConflictError,
  NotFoundError,
  stableStringify,
  samePackage,
  selectPreviousPackage,
  hasLocation,
};
