'use strict';

const express = require('express');
const config = require('../config');
const { loadValidator } = require('../services/proofSchema');

const router = express.Router();

// Compile the shared schema once at module load.
const { validate } = loadValidator();

/**
 * POST /proof
 * Accepts a proof package and validates it against the shared schema. This is
 * a genuinely useful contract check the Android app can already develop
 * against. Persistence to Firestore/Cloud Storage arrives in Phase 5.
 */
router.post('/', (req, res) => {
  const valid = validate(req.body);
  if (!valid) {
    return res.status(400).json({
      validated: false,
      errors: validate.errors,
    });
  }

  return res.status(200).json({
    validated: true,
    eventId: req.body.eventId,
    persistence: config.notImplementedStatus,
  });
});

module.exports = router;
