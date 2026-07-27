'use strict';

const crypto = require('crypto');
const canonicalize = require('canonicalize');
const config = require('../config');
const { sha256Hex } = require('./hashService');
const plausibility = require('./plausibility');

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

/**
 * The checks that must all `pass` before a package may be called `verified`
 * (ADR-0006 §5). Everything outside this set is still reported, and can still
 * *fail* the package, but its being `unavailable` does not hold the verdict back.
 *
 * The line is drawn at "does this check answer the question the verdict claims
 * to answer" — namely, is this bundle unaltered since capture and signed by the
 * key it names. An absent attestation chain leaves that question answered; a
 * missing media file does not.
 */
const DECISIVE_CHECKS = [
  'mediaHashMatch',
  'metadataHashMatch',
  'merkleRootMatch',
  'signatureValid',
  'timestampPlausible',
];

/** Recomputes the 2-leaf Merkle root from two hex digests. */
function merkleRoot2Leaf(mediaHashHex, metadataHashHex) {
  return sha256Hex(
    Buffer.concat([Buffer.from(mediaHashHex, 'hex'), Buffer.from(metadataHashHex, 'hex')])
  );
}

/**
 * @param {object} pkg  a schema-valid proof package
 * @param {Buffer} [mediaBytes]  the media itself, when available
 * @param {object} [options]
 * @param {number} [options.nowMillis]  the verifier's clock; injected so the
 *        timestamp check is testable without waiting for real time to pass.
 * @param {object|null} [options.previousPackage]  the most recent earlier
 *        LOCATED package from the same install, for the location cross-check.
 *        Absent means "no history available", which yields `unavailable`, not a
 *        pass.
 * @param {boolean} [options.historyReadFailed]  true when the store could not be
 *        read at all. Kept distinct from "no history": one is a normal state, the
 *        other is an infrastructure failure, and reporting them identically told
 *        the reader a broken store "does not indicate a problem".
 */
function verifyProofPackage(pkg, mediaBytes, options = {}) {
  const nowMillis = options.nowMillis !== undefined ? options.nowMillis : Date.now();
  const previousPackage = options.previousPackage || null;
  const historyReadFailed = options.historyReadFailed === true;
  const checks = {};
  const notes = [];
  const advisories = [];

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

  // --- timestamp plausibility (check 4) -------------------------------------
  checks.timestampPlausible = plausibility.checkTimestampPlausible(pkg.metadata, nowMillis, notes);

  // --- location plausibility (check 5) --------------------------------------
  // Recomputed from the signed metadata of two consecutive events. This is the
  // authoritative answer; the device's own `integrity.location.speedPlausible`
  // is advisory and unsigned (ADR-0005 §2).
  checks.locationPlausible = plausibility.checkLocationPlausible(
    pkg.metadata,
    previousPackage ? previousPackage.metadata : null,
    notes,
  );

  collectAdvisories(pkg, checks, advisories, historyReadFailed);

  return { checks, notes, advisories, verdict: verdictFor(checks) };
}

/**
 * Findings that a reader must see but that must not, on their own, condemn a
 * package. Kept separate from `notes` (which explain check outcomes) because
 * these are standing caveats about what the package does and does not establish.
 */
function collectAdvisories(pkg, checks, advisories, historyReadFailed = false) {
  if (checks.attestationPresent === UNAVAILABLE) {
    advisories.push(
      'No key attestation chain: the signature proves the bundle is unaltered since ' +
        'capture, but not that the signing key lives in secure hardware.',
    );
  } else if (checks.attestationRootTrusted !== PASS) {
    // A chain that links and binds still proves nothing about its origin until
    // its top certificate is anchored to a published Google root. Saying so is
    // the difference between "attested" and "carries something shaped like an
    // attestation" — and an attacker can mint the latter freely.
    advisories.push(
      'The attestation chain is internally consistent and covers the signing key, but it ' +
        'was NOT checked against Google’s published attestation roots. Hardware backing is ' +
        'therefore not established: a self-issued chain would reach this same result.',
    );
  }

  if (pkg.metadata.location && pkg.metadata.location.isMock === true) {
    // Signed by the device, so this is the device itself reporting that the
    // position came from a mock provider. Not a tampering finding — a
    // provenance one, and a serious one.
    advisories.push(
      'The device recorded this location as coming from a MOCK provider. The signature ' +
        'is genuine; the position it covers is not trustworthy.',
    );
  }

  if (checks.locationPlausible === UNAVAILABLE) {
    advisories.push(
      historyReadFailed
        ? 'Location could not be cross-checked because the stored capture history could ' +
          'not be read. This is a failure of the verification service, NOT a statement ' +
          'about this package — the cross-check was not attempted.'
        : 'Location could not be cross-checked against an earlier capture — this does not ' +
          'indicate a problem, only that no comparison was possible.',
    );
  }

  const gps = plausibility.gpsTimeAdvisory(pkg.metadata);
  if (gps) advisories.push(gps);

  // A disagreement between what the device claimed and what the verifier
  // recomputed is worth surfacing rather than quietly preferring one.
  const claimed = pkg.integrity && pkg.integrity.location
    ? pkg.integrity.location.speedPlausible
    : undefined;
  if (claimed === true && checks.locationPlausible === FAIL) {
    advisories.push(
      'The device claimed this location was physically plausible, but the verifier ' +
        "recomputed it as implausible. The verifier's result stands — the device-side " +
        'value is unsigned and advisory.',
    );
  }
}

/**
 * Verifies the Key Attestation chain: that each certificate is signed by the
 * next, and — critically — that the leaf's public key is the very key that
 * signed this package. Without that last binding, any genuine chain could be
 * stapled onto a package signed by an entirely different key.
 *
 * ## What this does NOT establish
 *
 * Internal linkage plus key binding proves the chain is self-consistent and
 * covers the signing key. It does NOT prove the chain came from Google, because
 * nothing here anchors the top certificate to a published Google attestation
 * root. An attacker can mint their own CA, issue a leaf over their own software
 * key, and produce a chain that links perfectly and binds correctly.
 *
 * That gap is reported explicitly as `attestationRootTrusted: unavailable`
 * rather than left implicit, so a reader is never told hardware backing was
 * established when only self-consistency was. Implementing it means fetching
 * Google's published roots (they rotated in 2026 and there is more than one, so
 * they must be fetched rather than pinned) and verifying the top certificate
 * against that set — plus parsing the attestation extension (OID
 * 1.3.6.1.4.1.11129.2.1.17) for `securityLevel` and `verifiedBootState`.
 */
function verifyAttestationChain(pkg, signingKeyDer, notes) {
  const chainBase64 = pkg.signature.attestationCertificateChain;
  if (!chainBase64 || chainBase64.length === 0) {
    notes.push('no attestation chain — the key is not proven to be hardware-backed');
    // `unavailable`, not `fail`: the chain is absent, which is the absence of
    // evidence, not evidence of a defect. A package from a device that could not
    // attest is still a package whose integrity-since-capture is provable, so
    // this raises an advisory instead of condemning it (ADR-0006 §5). A chain
    // that IS present but invalid or unbound is a different matter entirely, and
    // fails below.
    return {
      attestationPresent: UNAVAILABLE,
      attestationChainValid: UNAVAILABLE,
      attestationKeyBinding: UNAVAILABLE,
      attestationRootTrusted: UNAVAILABLE,
    };
  }

  // Never `pass`: no root set is consulted anywhere in this service. Declared as
  // a real check so the limit is visible in every report instead of living in a
  // comment (ADR-0005 §1 — a named check must be a check that ran).
  const result = { attestationPresent: PASS, attestationRootTrusted: UNAVAILABLE };
  let chain;
  try {
    chain = chainBase64.map((b64) => new crypto.X509Certificate(Buffer.from(b64, 'base64')));
  } catch (err) {
    notes.push(`attestation chain could not be parsed: ${err.message}`);
    return { ...result, attestationChainValid: FAIL, attestationKeyBinding: UNAVAILABLE };
  }

  // A lone certificate is not a chain. Guarding this explicitly matters: the
  // linkage loop below runs `chain.length - 1` times, so a single self-signed
  // certificate would skip it entirely and leave `linked` at its initial `true`
  // — reporting `pass` having verified precisely nothing. The schema puts no
  // `minItems` on the chain, so such a package is otherwise well-formed.
  if (chain.length < 2) {
    notes.push(
      `attestation chain has only ${chain.length} certificate: a single certificate cannot ` +
        'chain to an issuer, so it establishes no hardware backing',
    );
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
 * Two rules, in order (ADR-0006 §5):
 *
 *  1. **Any** check that returned `fail` makes the verdict `failed`. There is no
 *     partial credit for tamper-evidence, and this deliberately includes checks
 *     outside the decisive set: a stapled-on attestation chain that does not bind
 *     to the signing key, or a physically impossible implied speed, are real
 *     findings even though neither is "the media was altered".
 *
 *  2. Otherwise every DECISIVE check must have passed. One that could not be run
 *     — most often `mediaHashMatch`, when the media was not supplied — holds the
 *     verdict at `incomplete` rather than letting absence of evidence read as
 *     evidence. Non-decisive checks being `unavailable` raise an advisory and
 *     leave the verdict alone.
 */
function verdictFor(checks) {
  if (Object.values(checks).includes(FAIL)) return 'failed';
  const decisiveAllPassed = DECISIVE_CHECKS.every((name) => checks[name] === PASS);
  return decisiveAllPassed ? 'verified' : 'incomplete';
}

module.exports = {
  verifyProofPackage,
  verifyAttestationChain,
  merkleRoot2Leaf,
  verdictFor,
  DECISIVE_CHECKS,
  PASS,
  FAIL,
  UNAVAILABLE,
};
