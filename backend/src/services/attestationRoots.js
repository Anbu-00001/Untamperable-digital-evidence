'use strict';

const fs = require('fs');
const crypto = require('crypto');

const config = require('../config');

const PEM_BLOCK = /-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g;

let cached = null;

/**
 * The pinned Google hardware-attestation root certificates.
 *
 * Loaded once and held, because they are static data and re-reading them per
 * verification would put a disk read on the hot path for no benefit.
 *
 * Each root is sanity-checked on load: it must be **self-signed**, which every
 * genuine root is. That is cheap insurance against the file being truncated,
 * partially edited, or accidentally populated with an intermediate — failure
 * modes that would otherwise surface as "no device attests correctly any more"
 * long after the change that caused them.
 *
 * A missing or empty file throws rather than yielding an empty trust set. An
 * empty set would silently make `attestationRootTrusted` fail for every package,
 * which reads as "every device is suspect" instead of "the server is
 * misconfigured".
 */
function loadRoots() {
  if (cached) return cached;

  const { rootsPath } = config.attestation;
  let pem;
  try {
    pem = fs.readFileSync(rootsPath, 'utf8');
  } catch (err) {
    throw new Error(
      `could not read the pinned Google attestation roots at ${rootsPath}: ${err.message}`,
    );
  }

  const blocks = pem.match(PEM_BLOCK) || [];
  if (blocks.length === 0) {
    throw new Error(`no certificates found in the pinned attestation roots at ${rootsPath}`);
  }

  const roots = blocks.map((block, i) => {
    let cert;
    try {
      cert = new crypto.X509Certificate(block);
    } catch (err) {
      throw new Error(`pinned attestation root #${i} could not be parsed: ${err.message}`);
    }
    if (!cert.verify(cert.publicKey)) {
      throw new Error(
        `pinned attestation root #${i} (${cert.subject.replace(/\n/g, ' ')}) is not self-signed, ` +
          'so it is not a root — check data/google-attestation-roots.pem',
      );
    }
    return cert;
  });

  cached = roots;
  return roots;
}

/**
 * Is [cert] one of the pinned roots, or directly issued by one?
 *
 * Two ways to anchor, because chains differ in whether they carry the root:
 *
 * 1. **Identity** — the chain includes the root itself, and its DER matches a
 *    pinned one byte for byte. This is the case on the project's own test
 *    device, whose 4-certificate chain ends in Google's RSA-4096 root.
 * 2. **Issued by** — the chain stops at an intermediate, and a pinned root's key
 *    verifies that intermediate's signature. Accepting only case 1 would reject
 *    a legitimate device purely for omitting a certificate the verifier already
 *    holds.
 *
 * Comparing raw DER rather than subject/serial is deliberate: subject and serial
 * are attacker-chosen fields, so matching on them would accept a forged
 * certificate that merely *claims* Google's identity.
 */
function isAnchoredToPinnedRoot(cert, roots = loadRoots()) {
  if (roots.some((root) => root.raw.equals(cert.raw))) {
    return { anchored: true, how: 'identity' };
  }
  if (roots.some((root) => cert.verify(root.publicKey))) {
    return { anchored: true, how: 'issued-by' };
  }
  return { anchored: false, how: null };
}

/** Test seam: forces the next [loadRoots] to re-read from disk. */
function resetCache() {
  cached = null;
}

module.exports = { loadRoots, isAnchoredToPinnedRoot, resetCache };
