'use strict';

const express = require('express');
const config = require('../config');
const { loadValidator } = require('../services/proofSchema');
const { sha256Hex } = require('../services/hashService');
const { getSharedStore, isSafeEventId } = require('../store');
const { requireProofOwnership } = require('../middleware/requireProofOwnership');

const router = express.Router();

// Compile the shared schema once at module load.
const { validate } = loadValidator();

/**
 * POST /proof
 * Stores a proof package immutably (research/02 §8 Step 8).
 *
 * The package must be schema-valid first: this store holds evidence, and a
 * document that does not satisfy the shared contract is not evidence.
 *
 * Resubmitting byte-identical content succeeds with `created: false`. That
 * matters — the Android sync worker retries with exponential backoff and cannot
 * always know whether an earlier attempt landed before the connection dropped,
 * so a retry must be idempotent rather than an error. Submitting *different*
 * content under an existing eventId is a 409: stored packages are immutable.
 */
router.post('/', (req, res, next) => {
  const valid = validate(req.body);
  if (!valid) {
    return res.status(400).json({
      validated: false,
      errors: validate.errors,
    });
  }

  const pkg = req.body;
  try {
    const store = getSharedStore();
    const { created } = store.putPackage(pkg);
    return res.status(created ? 201 : 200).json({
      validated: true,
      stored: true,
      created,
      eventId: pkg.eventId,
      // Where to send the bytes. Told to the client rather than assumed by it,
      // so the media path is not duplicated as a literal inside the app.
      mediaUploadPath: `${config.routes.proof}/${pkg.eventId}/media`,
      // Whether the store already holds the media, so a client that uploaded it
      // once does not send it again.
      mediaPresent: store.getMedia(pkg.eventId) !== null,
    });
  } catch (err) {
    return next(err);
  }
});

/**
 * POST /proof/:eventId/media
 * Accepts the raw media bytes for an already-stored package.
 *
 * Raw `application/octet-stream`, not multipart and not base64: base64 would
 * inflate every upload by a third over a mobile connection, and multipart would
 * add a parser dependency to move one unnamed blob.
 *
 * The bytes are accepted only if they hash to the digest the *signed* package
 * already commits to. That gives the endpoint a property worth having: the store
 * cannot be made to hold media that no validly-signed package vouches for, so
 * this is not an open file drop.
 */
router.post(
  '/:eventId/media',
  express.raw({ type: '*/*', limit: config.store.maxMediaBytes }),
  (req, res, next) => {
    const { eventId } = req.params;
    if (!isSafeEventId(eventId)) {
      return res.status(400).json({ error: 'invalid_event_id' });
    }

    const store = getSharedStore();
    const pkg = store.getPackage(eventId);
    if (!pkg) {
      return res
        .status(404)
        .json({ error: 'not_found', message: `no stored package for ${eventId}` });
    }

    if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
      return res.status(400).json({ error: 'empty_body', message: 'expected raw media bytes' });
    }

    const digest = sha256Hex(req.body);
    if (digest !== pkg.media.sha256) {
      // Refused rather than stored-and-flagged: these bytes are not the media
      // this package describes, so keeping them would only create confusion.
      return res.status(409).json({
        error: 'media_hash_mismatch',
        message:
          'the uploaded bytes do not hash to the digest recorded in the signed package; ' +
          'refusing to store them',
        expected: pkg.media.sha256,
        actual: digest,
      });
    }

    if (req.body.length !== pkg.media.byteLength) {
      return res.status(409).json({
        error: 'media_length_mismatch',
        message: `package declares ${pkg.media.byteLength} bytes, received ${req.body.length}`,
      });
    }

    try {
      const { storageRef } = store.putMedia(eventId, req.body, digest);
      return res
        .status(201)
        .json({ stored: true, eventId, storageRef, byteLength: req.body.length });
    } catch (err) {
      return next(err);
    }
  },
);

/**
 * GET /proof/:eventId
 * Returns the stored package.
 *
 * Deliberately distinct from the public `GET /verify/:eventId`, which returns
 * only a verdict: the package carries GPS coordinates, so handing it out is a
 * different act from confirming that it verifies (ADR-0006 §7).
 *
 * Gated on proof of possession of the signing key. Knowing the event ID is no
 * longer sufficient — see middleware/requireProofOwnership.js.
 */
router.get('/:eventId', requireProofOwnership, (req, res) => {
  const { eventId } = req.params;
  if (!isSafeEventId(eventId)) {
    return res.status(400).json({ error: 'invalid_event_id' });
  }
  const store = getSharedStore();
  const pkg = store.getPackage(eventId);
  if (!pkg) {
    return res.status(404).json({ error: 'not_found' });
  }
  return res.status(200).json({
    package: pkg,
    mediaPresent: store.getMedia(eventId) !== null,
  });
});

/**
 * GET /proof/:eventId/media
 * Returns the stored media bytes, so an independent verifier can recompute the
 * media leaf itself rather than trusting our recomputation of it.
 *
 * Gated identically to the package route above: this returns the photograph.
 */
router.get('/:eventId/media', requireProofOwnership, (req, res) => {
  const { eventId } = req.params;
  if (!isSafeEventId(eventId)) {
    return res.status(400).json({ error: 'invalid_event_id' });
  }
  const store = getSharedStore();
  const pkg = store.getPackage(eventId);
  const bytes = store.getMedia(eventId);
  if (!pkg || !bytes) {
    return res.status(404).json({ error: 'not_found' });
  }
  res.setHeader('Content-Type', pkg.media.mimeType);
  return res.status(200).send(bytes);
});

module.exports = router;
