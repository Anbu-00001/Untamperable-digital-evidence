'use strict';

const express = require('express');
const config = require('../config');
const { loadValidator } = require('../services/proofSchema');
const { verifyProofPackage } = require('../services/proofVerifier');
const { getSharedStore, isSafeEventId } = require('../store');

const router = express.Router();

const { validate } = loadValidator();

/**
 * Assembles the response body shared by both verification entry points, so the
 * two cannot drift into reporting the same package differently.
 */
function verificationResponse({ checks, notes, advisories, verdict }) {
  return {
    verdict,
    checks: { schemaValid: 'pass', ...checks },
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
 * Looks up the previous capture from the same install, for the location
 * cross-check. Returns null when nothing is stored, which the verifier reports
 * as `unavailable` rather than as a pass (ADR-0005 §2).
 */
function previousFor(pkg) {
  try {
    return getSharedStore().findPreviousPackage(
      pkg.metadata.device.installId,
      pkg.metadata.timestamp.wallClockMillis,
    );
  } catch {
    // A store that cannot be read must degrade the location check to
    // `unavailable`, not take the whole verification down.
    return null;
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

  const result = verifyProofPackage(pkg, mediaBytes, { previousPackage: previousFor(pkg) });
  return res.status(200).json(verificationResponse(result));
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

  const result = verifyProofPackage(pkg, store.getMedia(eventId) || undefined, {
    previousPackage: previousFor(pkg),
  });

  return res.status(200).json({
    eventId,
    // Safe to echo: it is a digest, and it is what a holder of the original
    // media can compare against without us disclosing anything else.
    merkleRoot: pkg.merkle.root,
    capturedAt: pkg.metadata.timestamp.iso8601,
    ...verificationResponse(result),
  });
});

module.exports = router;
