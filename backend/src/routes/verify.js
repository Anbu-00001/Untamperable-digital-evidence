'use strict';

const express = require('express');
const config = require('../config');
const { loadValidator } = require('../services/proofSchema');

const router = express.Router();

const { validate } = loadValidator();

/**
 * POST /verify
 * Returns a per-check breakdown mirroring the verification module design
 * (research/02 §8 Step 10). At skeleton stage only schema validation is
 * implemented; the cryptographic checks (hash recompute, signature, timestamp
 * and location plausibility, Play Integrity decode) are implemented in Phase 5.
 * The breakdown is intentionally granular rather than a single opaque boolean.
 */
router.post('/', (req, res) => {
  const valid = validate(req.body);
  if (!valid) {
    return res.status(400).json({
      verdict: 'invalid_format',
      errors: validate.errors,
    });
  }

  const pending = config.notImplementedStatus;
  return res.status(200).json({
    verdict: 'incomplete',
    checks: {
      schemaValid: 'pass',
      mediaHashMatch: pending,
      metadataHashMatch: pending,
      signatureValid: pending,
      timestampPlausible: pending,
      locationPlausible: pending,
      playIntegrity: pending,
    },
  });
});

module.exports = router;
