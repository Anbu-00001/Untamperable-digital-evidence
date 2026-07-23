'use strict';

const express = require('express');
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

  return res.status(200).json({
    verdict: 'incomplete',
    checks: {
      schemaValid: 'pass',
      mediaHashMatch: 'not_implemented_phase_5',
      metadataHashMatch: 'not_implemented_phase_5',
      signatureValid: 'not_implemented_phase_5',
      timestampPlausible: 'not_implemented_phase_5',
      locationPlausible: 'not_implemented_phase_5',
      playIntegrity: 'not_implemented_phase_5',
    },
  });
});

module.exports = router;
