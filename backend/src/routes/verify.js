'use strict';

const express = require('express');
const config = require('../config');
const { loadValidator } = require('../services/proofSchema');
const { verifyProofPackage } = require('../services/proofVerifier');
const { getSharedStore, isSafeEventId } = require('../store');
const { hasLocation } = require('../store/support');

const router = express.Router();

const { validate } = loadValidator();

/**
 * Assembles the response body shared by both verification entry points, so the
 * two cannot drift into reporting the same package differently.
 *
 * `schemaValid` is a REQUIRED argument rather than a literal. It was previously
 * hardcoded to 'pass', which was true for POST (which validates before calling)
 * but false for GET, where the package is read straight off disk and never
 * validated — so the most public endpoint asserted a check it had not run. In a
 * system whose premise is per-check honesty, "a file on disk was not edited out
 * of band" is exactly the assumption that may not be made.
 */
function verificationResponse({ checks, notes, advisories, verdict }, schemaValid) {
  if (schemaValid !== 'pass' && schemaValid !== 'fail') {
    throw new TypeError(`verificationResponse requires a real schemaValid result, got ${schemaValid}`);
  }
  return {
    verdict,
    checks: { schemaValid, ...checks },
    notes,
    // Findings a reader must see that do not, on their own, condemn the package
    // (ADR-0006 §5) — a missing attestation chain, a mock-provider location.
    advisories,
    // Stated with every verdict: a passing package proves the bundle is
    // unaltered since capture and signed by a specific hardware-backed key. It
    // does NOT prove the depicted event was real, and is not a legal
    // certificate on its own (research/06 §7, BSA 2023 §63).
    limitations: config.verdictLimitations,
  };
}

/**
 * Looks up the previous **located** capture from the same install, for the
 * location cross-check. Missing history yields `unavailable` rather than a pass
 * (ADR-0005 §2).
 *
 * Returns `{ previousPackage, historyReadFailed }` rather than a bare package,
 * because "there is no earlier capture" and "the store could not be read" are
 * different facts and previously collapsed into the same `null`. That made an
 * infrastructure failure indistinguishable from a first-ever capture, and the
 * reader was then told the absence "does not indicate a problem".
 */
function previousFor(pkg) {
  try {
    return {
      previousPackage: getSharedStore().findPreviousPackage(
        pkg.metadata.device.installId,
        pkg.metadata.timestamp.wallClockMillis,
        // Only a predecessor that recorded a position can be compared against.
        hasLocation,
      ),
      historyReadFailed: false,
    };
  } catch {
    // A store that cannot be read must degrade the location check to
    // `unavailable`, not take the whole verification down — but it must say so.
    return { previousPackage: null, historyReadFailed: true };
  }
}

/**
 * POST /verify
 * Returns a per-check breakdown mirroring the verification module design
 * (research/02 §8 Step 10) — all five checks:
 *   (1) media hash  (2) metadata hash  (3) signature + attestation chain
 *   (4) timestamp plausibility  (5) location plausibility
 *
 * The breakdown is intentionally granular rather than a single opaque boolean:
 * "the media was altered" and "we could not reach the revocation list" are very
 * different statements and must not collapse into one `false`.
 *
 * Location plausibility is recomputed against the previous stored capture from
 * the same install, so verifying a package the store has never seen yields
 * `unavailable` for that one check rather than a vacuous pass.
 */
router.post('/', (req, res) => {
  // Two accepted shapes:
  //   (a) a bare proof package, or
  //   (b) an envelope { package, mediaBase64 } when the media is supplied too.
  // The media cannot ride inside the package itself: the schema sets
  // `additionalProperties: false` at the root, so an extra key would — rightly —
  // make a genuine package fail validation.
  const isEnvelope = req.body && typeof req.body === 'object' && req.body.package !== undefined;
  const pkg = isEnvelope ? req.body.package : req.body;

  const valid = validate(pkg);
  if (!valid) {
    return res.status(400).json({
      verdict: 'invalid_format',
      errors: validate.errors,
    });
  }

  // Media may be supplied inline; failing that, fall back to the store's own
  // copy so a synced package verifies fully without the client re-uploading it.
  const mediaBase64 = isEnvelope ? req.body.mediaBase64 : undefined;
  let mediaBytes =
    typeof mediaBase64 === 'string' ? Buffer.from(mediaBase64, 'base64') : undefined;
  if (!mediaBytes) {
    mediaBytes = getSharedStore().getMedia(pkg.eventId) || undefined;
  }

  const result = verifyProofPackage(pkg, mediaBytes, previousFor(pkg));
  // Validation ran above; anything invalid already returned 400.
  return res.status(200).json(verificationResponse(result, 'pass'));
});

/**
 * GET /verify/:eventId
 * The endpoint the QR verification badge points at: verifies a package the store
 * already holds, using the store's own copy of the media.
 *
 * Returns ONLY the verdict, the per-check breakdown, the advisories and the
 * limitations — never the package. The `eventId` is an unguessable v4 UUID and
 * acts as a capability token, but the package it names contains GPS
 * coordinates, and a verification badge must not double as a location leak
 * (ADR-0006 §7). Fetching the package itself is `GET /proof/:eventId`.
 */
router.get('/:eventId', (req, res) => {
  const { eventId } = req.params;
  if (!isSafeEventId(eventId)) {
    return res.status(400).json({ error: 'invalid_event_id' });
  }

  const store = getSharedStore();
  const pkg = store.getPackage(eventId);
  if (!pkg) {
    return res.status(404).json({ error: 'not_found', eventId });
  }

  // Validated here too. The store is not a trusted input: these are plain JSON
  // files on disk, and a package that no longer matches the schema must be
  // reported as such rather than silently assumed well-formed.
  const schemaValid = validate(pkg) ? 'pass' : 'fail';

  const result = verifyProofPackage(pkg, store.getMedia(eventId) || undefined, previousFor(pkg));

  return res.status(200).json({
    eventId,
    // Safe to echo: it is a digest, and it is what a holder of the original
    // media can compare against without us disclosing anything else.
    merkleRoot: pkg.merkle.root,
    capturedAt: pkg.metadata.timestamp.iso8601,
    ...verificationResponse(result, schemaValid),
  });
});

module.exports = router;
