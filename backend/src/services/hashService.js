'use strict';

const crypto = require('crypto');
const config = require('../config');

/**
 * Hash of a Buffer/string, lowercase hex. The algorithm is taken from
 * config.crypto rather than written inline, because it must equal the schema's
 * `merkle.algorithm` const and Android's CryptoConfig.HASH_ALGORITHM for the
 * verifier to recompute and compare (research/02 §1, §8). Used by the Phase-5
 * verification implementation.
 *
 * @param {Buffer|string} data
 * @returns {string} lowercase hex digest
 */
function sha256Hex(data) {
  return crypto.createHash(config.crypto.nodeHashAlgorithm).update(data).digest('hex');
}

module.exports = { sha256Hex };
