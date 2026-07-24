'use strict';

const crypto = require('crypto');
const canonicalize = require('canonicalize');
const config = require('../config');
const { sha256Hex } = require('./hashService');

/**
 * Cryptographic verification of a proof package (research/02 §8 Step 10).
 *
 * Every check is independent and reported separately: a single opaque boolean
 * would tell a reviewer nothing about *what* failed, and "this media was
 * altered" is a very different statement from "we could not reach Google's
 * revocation list". Checks that cannot be performed return `unavailable`
 * rather than `pass`, because absence of evidence must never read as evidence.
 *
 * The rules implemented here mirror the producer exactly:
 *   media leaf    = SHA-256(media bytes)
 *   metadata leaf = SHA-256(RFC8785(metadata))
 *   root          = SHA-256(rawBytes(mediaLeaf) ‖ rawBytes(metadataLeaf))
 *   signature     = ECDSA-P256 over the RAW 32 bytes of the root
 * The concatenation is of raw digests, not hex text — see MerkleTree.kt.
 */

const PASS = 'pass';
const FAIL = 'fail';
const UNAVAILABLE = 'unavailable';

/** Recomputes the 2-leaf Merkle root from two hex digests. */
function merkleRoot2Leaf(mediaHashHex, metadataHashHex) {
  return sha256Hex(
    Buffer.concat([Buffer.from(mediaHashHex, 'hex'), Buffer.from(metadataHashHex, 'hex')])
  );
}

/**
 * @param {object} pkg  a schema-valid proof package
 * @param {Buffer} [mediaBytes]  the media itself, when available
 */
function verifyProofPackage(pkg, mediaBytes) {
  const checks = {};
  const notes = [];

  // --- media leaf -----------------------------------------------------------
  if (!mediaBytes) {
    // The package alone cannot prove anything about bytes it does not contain.
    checks.mediaHashMatch = UNAVAILABLE;
    notes.push('media bytes were not supplied, so the media leaf could not be recomputed');
  } else {
    const actual = sha256Hex(mediaBytes);
    checks.mediaHashMatch =
      actual === pkg.media.sha256 && actual === pkg.merkle.leaves.media ? PASS : FAIL;
    if (checks.mediaHashMatch === FAIL) {
      notes.push(`media digest ${actual} does not match the recorded leaf`);
    }
    if (mediaBytes.length !== pkg.media.byteLength) {
      notes.push(`media is ${mediaBytes.length} bytes, package claims ${pkg.media.byteLength}`);
    }
  }

  // --- metadata leaf --------------------------------------------------------
  let metadataHash = null;
  try {
    metadataHash = sha256Hex(Buffer.from(canonicalize(pkg.metadata), 'utf8'));
    checks.metadataHashMatch = metadataHash === pkg.merkle.leaves.metadata ? PASS : FAIL;
    if (checks.metadataHashMatch === FAIL) {
      notes.push('metadata does not hash to the recorded leaf — a field was altered');
    }
  } catch (err) {
    checks.metadataHashMatch = UNAVAILABLE;
    notes.push(`metadata could not be canonicalized: ${err.message}`);
  }

  // --- Merkle root ----------------------------------------------------------
  if (metadataHash) {
    const root = merkleRoot2Leaf(pkg.merkle.leaves.media, pkg.merkle.leaves.metadata);
    checks.merkleRootMatch = root === pkg.merkle.root ? PASS : FAIL;
    if (checks.merkleRootMatch === FAIL) {
      notes.push('the recorded root is not the composition of the recorded leaves');
    }
  } else {
    checks.merkleRootMatch = UNAVAILABLE;
  }

  // --- signature ------------------------------------------------------------
  let signingKeyDer = null;
  try {
    signingKeyDer = Buffer.from(pkg.signature.publicKey.value, 'base64');
    const publicKey = crypto.createPublicKey({
      key: signingKeyDer,
      format: 'der',
      type: 'spki',
    });
    const ok = crypto
      .createVerify('SHA256')
      .update(Buffer.from(pkg.merkle.root, 'hex'))
      .verify({ key: publicKey, dsaEncoding: 'der' }, Buffer.from(pkg.signature.value, 'base64'));
    checks.signatureValid = ok ? PASS : FAIL;
    if (!ok) notes.push('signature does not verify over the recorded root');
  } catch (err) {
    checks.signatureValid = FAIL;
    notes.push(`signature could not be checked: ${err.message}`);
  }

  // --- attestation ----------------------------------------------------------
  Object.assign(checks, verifyAttestationChain(pkg, signingKeyDer, notes));

  // Phase 4/5 territory, honestly marked rather than silently passing.
  checks.timestampPlausible = config.notImplementedStatus;
  checks.locationPlausible = config.notImplementedStatus;

  return { checks, notes, verdict: verdictFor(checks) };
}

/**
 * Verifies the Key Attestation chain: that each certificate is signed by the
 * next, and — critically — that the leaf's public key is the very key that
 * signed this package. Without that last binding, any genuine chain could be
 * stapled onto a package signed by an entirely different key.
 *
 * Chaining to a Google root is checked separately by the caller, which supplies
 * the current root set; roots are fetched, never pinned, because Google rotated
 * them in 2026 and publishes more than one.
 */
function verifyAttestationChain(pkg, signingKeyDer, notes) {
  const chainBase64 = pkg.signature.attestationCertificateChain;
  if (!chainBase64 || chainBase64.length === 0) {
    notes.push('no attestation chain — the key is not proven to be hardware-backed');
    return { attestationPresent: FAIL, attestationChainValid: UNAVAILABLE, attestationKeyBinding: UNAVAILABLE };
  }

  const result = { attestationPresent: PASS };
  let chain;
  try {
    chain = chainBase64.map((b64) => new crypto.X509Certificate(Buffer.from(b64, 'base64')));
  } catch (err) {
    notes.push(`attestation chain could not be parsed: ${err.message}`);
    return { ...result, attestationChainValid: FAIL, attestationKeyBinding: UNAVAILABLE };
  }

  let linked = true;
  for (let i = 0; i < chain.length - 1; i += 1) {
    if (!chain[i].verify(chain[i + 1].publicKey)) linked = false;
  }
  result.attestationChainValid = linked ? PASS : FAIL;
  if (!linked) notes.push('attestation certificates do not form a valid signature chain');

  if (signingKeyDer) {
    const leafKeyDer = chain[0].publicKey.export({ type: 'spki', format: 'der' });
    const bound = leafKeyDer.equals(signingKeyDer);
    result.attestationKeyBinding = bound ? PASS : FAIL;
    if (!bound) {
      notes.push('the attested key is NOT the key that signed this package');
    }
  } else {
    result.attestationKeyBinding = UNAVAILABLE;
  }

  return result;
}

/**
 * Any failed check makes the package unverifiable — there is no partial credit
 * for tamper-evidence. Checks that could not be run hold the verdict at
 * `incomplete` rather than letting it read as `verified`.
 */
function verdictFor(checks) {
  const values = Object.values(checks);
  if (values.includes(FAIL)) return 'failed';
  if (values.some((v) => v === UNAVAILABLE || v === config.notImplementedStatus)) {
    return 'incomplete';
  }
  return 'verified';
}

module.exports = { verifyProofPackage, verifyAttestationChain, merkleRoot2Leaf, PASS, FAIL, UNAVAILABLE };
