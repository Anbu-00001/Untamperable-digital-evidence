'use strict';

const crypto = require('crypto');
const canonicalize = require('canonicalize');
const config = require('../config');
const { sha256Hex } = require('./hashService');
const plausibility = require('./plausibility');
const { isAnchoredToPinnedRoot } = require('./attestationRoots');
const revocation = require('./attestationRevocation');
const { parseAttestationExtension } = require('./attestationExtension');

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
  } else if (checks.attestationRootTrusted === FAIL) {
    // The chain links and binds, but its top certificate is not one of Google's
    // published roots and was not issued by one. That is the shape a self-minted
    // CA produces, so it is stated far more firmly than a missing chain.
    advisories.push(
      'The attestation chain is internally consistent, but it does NOT anchor to any ' +
        'published Google attestation root. A chain minted by anyone with their own CA ' +
        'looks exactly like this, so hardware backing is not established.',
    );
  } else if (checks.attestationRootTrusted === UNAVAILABLE) {
    // Root trust could not be assessed at all — a broken chain above it, or this
    // service failing to read its own pinned roots. Distinguished from FAIL
    // because "we could not check" and "we checked and it did not anchor" are
    // different claims and must not be collapsed.
    advisories.push(
      'The attestation chain could not be anchored to a published Google root — the ' +
        'anchoring check did not complete. Hardware backing is therefore not established ' +
        'by this report.',
    );
  }

  if (checks.attestationNotRevoked === UNAVAILABLE && checks.attestationPresent === PASS) {
    // Said out loud because this check fails OPEN: with no list, a revoked
    // certificate looks exactly like a clean one. A reader who is not told the
    // check did not run will reasonably assume it passed.
    advisories.push(
      'Certificate revocation was NOT checked against Google’s published status list, so a ' +
        'key revoked for compromise would not be detected in this report.',
    );
  }

  if (checks.attestationSecurityLevel === FAIL) {
    advisories.push(
      'The device reports this key as Software-protected — by its own attestation the key ' +
        'does NOT live in secure hardware, whatever the presence of a chain suggests.',
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
 * ## What Phase 8 added
 *
 * `attestationRootTrusted` is now a real check: the top of the chain is anchored
 * against Google's **pinned** published roots (`./attestationRoots`). That closes
 * the forgery route described above — a self-minted CA no longer reaches the same
 * verdict as a genuine Google-rooted chain.
 *
 * An earlier revision of this comment said the roots "must be fetched rather than
 * pinned" because they rotate and there is more than one. Both halves of that are
 * true — there are two current roots and a new ECDSA one began signing on
 * 2026-02-01 — but the conclusion was wrong. A trust anchor fetched at verify
 * time is only as trustworthy as the fetch, so anyone able to answer for
 * `android.googleapis.com` could supply their own root and every forged chain
 * would verify. The roots are therefore pinned in `data/`, with their provenance
 * and update procedure in the file header.
 *
 * ## What is STILL not established
 *
 * - **Revocation.** Google publishes a status list at
 *   `https://android.googleapis.com/attestation/status`, keyed by certificate
 *   serial. It is not consulted, so a key revoked for compromise still verifies
 *   here. This is the next gap, and it is not claimed to be solved.
 * - **The attestation extension itself** (OID 1.3.6.1.4.1.11129.2.1.17) is not
 *   parsed, so `securityLevel` (TrustedEnvironment vs StrongBox) and
 *   `verifiedBootState` are not checked against expectations.
 */
/**
 * Is any certificate in [chain] on Google's revocation list?
 *
 * Returns `unavailable` — never `pass` — when no usable snapshot exists. This is
 * the one check that fails open by nature: with no list, a revoked certificate
 * is indistinguishable from a clean one, so answering `pass` would report a
 * key Google says is compromised as fine because a network fetch had not
 * happened yet.
 */
function evaluateRevocation(chain, notes) {
  const revoked = [];
  for (const cert of chain) {
    let entry;
    try {
      entry = revocation.lookup(cert.serialNumber);
    } catch (err) {
      notes.push(
        `revocation not checked (${err.message}) — a revoked certificate would not be ` +
          'detected in this report',
      );
      return UNAVAILABLE;
    }
    if (entry) {
      revoked.push(
        `${cert.subject.replace(/\n/g, ' ')} [${entry.status}${entry.reason ? `: ${entry.reason}` : ''}]`,
      );
    }
  }

  if (revoked.length > 0) {
    notes.push(`certificate revoked by Google: ${revoked.join('; ')}`);
    return FAIL;
  }
  return PASS;
}

/**
 * What the leaf's attestation extension says about the key's security level, and
 * the device's Verified Boot state.
 *
 * `fail` when the extension reports **Software**: the device is stating plainly
 * that this key does not live in secure hardware, while the package carries an
 * attestation chain that a reader will take as evidence it does. That
 * contradiction is loud enough to belong in the verdict rather than in an
 * advisory someone may not read — and because every report carries its per-check
 * breakdown, a `failed` here is never opaque about which claim broke.
 *
 * `unavailable` when the extension is absent or will not parse. A leaf with no
 * extension is unusual but not evidence of wrongdoing, and a parse failure must
 * never be able to invent a security level.
 *
 * Verified Boot state and the bootloader lock are reported as notes rather than
 * folded into this outcome: they describe the OS the device was running, not
 * where the key lives, and a genuine capture from an unlocked device is still a
 * genuine capture.
 */
function evaluateSecurityLevel(leaf, notes) {
  let description;
  try {
    description = parseAttestationExtension(leaf.raw);
  } catch (err) {
    notes.push(`attestation extension could not be parsed: ${err.message}`);
    return UNAVAILABLE;
  }

  if (!description) {
    notes.push('the leaf certificate carries no key attestation extension');
    return UNAVAILABLE;
  }

  notes.push(
    `attested security level: ${description.securityLevel} ` +
      `(attestation version ${description.attestationVersion})`,
  );

  if (description.verifiedBootState !== null) {
    notes.push(
      `verified boot state: ${description.verifiedBootState}, ` +
        `bootloader ${description.deviceLocked ? 'locked' : 'UNLOCKED'}`,
    );
  } else {
    notes.push('the attestation extension carries no rootOfTrust, so boot state is unknown');
  }

  if (description.securityLevelValue === 0) {
    notes.push(
      'the device reports this key as Software-protected: it does NOT live in secure hardware',
    );
    return FAIL;
  }
  // Anything the build does not recognise is not assumed good.
  return description.securityLevelValue === 1 || description.securityLevelValue === 2
    ? PASS
    : UNAVAILABLE;
}

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
      attestationNotRevoked: UNAVAILABLE,
      attestationSecurityLevel: UNAVAILABLE,
    };
  }

  const result = { attestationPresent: PASS, attestationRootTrusted: UNAVAILABLE };
  let chain;
  try {
    chain = chainBase64.map((b64) => new crypto.X509Certificate(Buffer.from(b64, 'base64')));
  } catch (err) {
    notes.push(`attestation chain could not be parsed: ${err.message}`);
    return {
      ...result,
      attestationChainValid: FAIL,
      attestationKeyBinding: UNAVAILABLE,
      attestationNotRevoked: UNAVAILABLE,
      attestationSecurityLevel: UNAVAILABLE,
    };
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
    return {
      ...result,
      attestationChainValid: FAIL,
      attestationKeyBinding: UNAVAILABLE,
      attestationNotRevoked: UNAVAILABLE,
      attestationSecurityLevel: UNAVAILABLE,
    };
  }

  let linked = true;
  for (let i = 0; i < chain.length - 1; i += 1) {
    if (!chain[i].verify(chain[i + 1].publicKey)) linked = false;
  }
  result.attestationChainValid = linked ? PASS : FAIL;
  if (!linked) notes.push('attestation certificates do not form a valid signature chain');

  // Anchor the top of the chain to a pinned Google root.
  //
  // Only meaningful when the chain links: a Google root sitting on top of
  // certificates that do not actually sign one another says nothing about the
  // leaf, so reporting `pass` there would be the exact overclaim this check was
  // added to remove. The broken linkage is already reported by the check above.
  if (!linked) {
    result.attestationRootTrusted = UNAVAILABLE;
    notes.push(
      'the chain does not link, so it cannot be anchored to a root — root trust was not assessed',
    );
  } else {
    const top = chain[chain.length - 1];
    let anchor;
    try {
      anchor = isAnchoredToPinnedRoot(top);
    } catch (err) {
      // A misconfigured or unreadable root file is a SERVER fault, not evidence
      // against the package. Saying `fail` here would accuse every device of
      // forgery because of our own deployment error.
      notes.push(`attestation roots unavailable, root trust not assessed: ${err.message}`);
      return result;
    }
    result.attestationRootTrusted = anchor.anchored ? PASS : FAIL;
    if (!anchor.anchored) {
      notes.push(
        'the attestation chain does not anchor to any pinned Google root: either it was not ' +
          'issued by Google, or this device uses a root absent from ' +
          'backend/data/google-attestation-roots.pem',
      );
    }
  }

  // Revocation. Checked across the WHOLE chain, not just the leaf: Google's list
  // carries CA_COMPROMISE entries, and a compromised intermediate invalidates
  // everything beneath it however clean the leaf looks.
  result.attestationNotRevoked = evaluateRevocation(chain, notes);

  // The extension itself — what the key actually claims about where it lives.
  result.attestationSecurityLevel = evaluateSecurityLevel(chain[0], notes);

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
