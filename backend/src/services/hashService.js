'use strict';

const crypto = require('crypto');

/**
 * SHA-256 of a Buffer/string, lowercase hex. Matches the Android side's
 * MessageDigest("SHA-256") output so the verifier can recompute and compare
 * (research/02 §1, §8). Used by the Phase-5 verification implementation.
 *
 * @param {Buffer|string} data
 * @returns {string} lowercase hex digest
 */
function sha256Hex(data) {
  return crypto.createHash('sha256').update(data).digest('hex');
}

module.exports = { sha256Hex };
