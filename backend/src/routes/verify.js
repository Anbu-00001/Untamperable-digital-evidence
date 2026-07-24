'use strict';

const express = require('express');
const config = require('../config');
const { loadValidator } = require('../services/proofSchema');
const { verifyProofPackage } = require('../services/proofVerifier');

const router = express.Router();

const { validate } = loadValidator();

/**
 * POST /verify
 * Returns a per-check breakdown mirroring the verification module design
 * (research/02 §8 Step 10).
 *
 * The breakdown is intentionally granular rather than a single opaque boolean:
 * "the media was altered" and "we could not reach the revocation list" are very
 * different statements and must not collapse into one `false`.
 *
 * Phase 3 implements the hash, Merkle, signature and attestation checks.
 * Timestamp/location plausibility remain Phase 4/5 and report as not-implemented
 * rather than passing vacuously.
 */
router.post('/', (req, res) => {
  const valid = validate(req.body);
  if (!valid) {
    return res.status(400).json({
      verdict: 'invalid_format',
      errors: validate.errors,
    });
  }

  // Media may be supplied as base64 alongside the package so the media leaf can
  // be recomputed; without it that one check reports `unavailable`.
  let mediaBytes;
  if (typeof req.body.mediaBase64 === 'string') {
    mediaBytes = Buffer.from(req.body.mediaBase64, 'base64');
  }

  const { checks, notes, verdict } = verifyProofPackage(req.body, mediaBytes);

  return res.status(200).json({
    verdict,
    checks: { schemaValid: 'pass', ...checks },
    notes,
    // Stated with every verdict: a passing package proves the bundle is
    // unaltered since capture and signed by a specific hardware-backed key. It
    // does NOT prove the depicted event was real, and is not a legal
    // certificate on its own (research/06 §7, BSA 2023 §63).
    limitations: config.verdictLimitations,
  });
});

module.exports = router;
