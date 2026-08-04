'use strict';

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const config = require('../src/config');
const { loadRoots, isAnchoredToPinnedRoot, resetCache } = require('../src/services/attestationRoots');

/**
 * Anchoring an attestation chain to Google's published roots (Phase 8).
 *
 * Until this existed, `attestationRootTrusted` was hardcoded `unavailable` and
 * the reports said so: a chain that linked and bound correctly proved only that
 * it was self-consistent, and **an attacker could mint their own CA and reach the
 * identical verdict**. Closing that is the point, so the test that matters most
 * here is the impersonation one below.
 *
 * The certificates used are generated per-run rather than committed. A real
 * device chain would be a better positive fixture, but its intermediates carry
 * per-device TEE serial numbers, and this repository has no business holding a
 * hardware identifier for the sake of a test.
 */

/** A throwaway self-signed certificate with an attacker-chosen subject. */
function selfSigned(subject) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rl-attest-'));
  const keyPath = path.join(dir, 'key.pem');
  const certPath = path.join(dir, 'cert.pem');
  execFileSync(
    'openssl',
    ['req', '-x509', '-newkey', 'rsa:2048', '-nodes',
      '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', subject],
    { stdio: 'pipe' },
  );
  const cert = new crypto.X509Certificate(fs.readFileSync(certPath));
  fs.rmSync(dir, { recursive: true, force: true });
  return cert;
}

test('the pinned roots load, and every one of them is self-signed', () => {
  const roots = loadRoots();

  assert.ok(roots.length >= 2, `expected at least the two current roots, got ${roots.length}`);
  for (const root of roots) {
    assert.ok(
      root.verify(root.publicKey),
      `pinned root ${root.subject} is not self-signed, so it is not a root`,
    );
  }
});

test('the pinned RSA root is byte-for-byte the certificate Google publishes', () => {
  // Digest of the DER fetched from https://android.googleapis.com/attestation/root
  // on 2026-08-03, and independently confirmed to be the root that the project's
  // own test device chains up to. Pinned here so that editing the roots file —
  // the one place where trusting the wrong key silently makes every forgery
  // verify — cannot pass unnoticed.
  const expected = 'cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0fbe34a9a1248800dfc';

  const digests = loadRoots().map((r) =>
    crypto.createHash('sha256').update(r.raw).digest('hex'));

  assert.ok(
    digests.includes(expected),
    `the pinned Google RSA root is missing or altered.\n  expected: ${expected}\n  found:    ${digests.join('\n            ')}`,
  );
});

test('a pinned root anchors to itself, by identity', () => {
  const root = loadRoots()[0];

  const result = isAnchoredToPinnedRoot(root);

  assert.strictEqual(result.anchored, true);
  assert.strictEqual(result.how, 'identity');
});

test('an unrelated self-signed CA does not anchor', () => {
  // The plain forgery: mint a CA, sign your own software key with it, present the
  // chain. Before Phase 8 this reached the same verdict as a genuine chain.
  const rogue = selfSigned('/CN=Definitely Not Google/O=Rogue CA');

  assert.strictEqual(isAnchoredToPinnedRoot(rogue).anchored, false);
});

test('a certificate impersonating Google’s root subject does not anchor', () => {
  // The sharper forgery, and the reason anchoring compares raw DER rather than
  // subject or serial: those fields are chosen by whoever makes the certificate.
  // This cert carries the real root's subject and a key the attacker controls.
  const impostor = selfSigned('/serialNumber=f92009e853b6b045');
  const realRoot = loadRoots().find((r) => r.subject.includes('f92009e853b6b045'));

  assert.ok(realRoot, 'the genuine root this test impersonates is not in the pinned set');
  assert.strictEqual(
    impostor.subject.trim(),
    realRoot.subject.trim(),
    'the impostor should carry an identical subject, or this test proves nothing',
  );

  assert.strictEqual(
    isAnchoredToPinnedRoot(impostor).anchored,
    false,
    'a forged certificate was trusted because it merely CLAIMED Google’s identity',
  );
});

test('a missing roots file throws instead of silently trusting nothing', () => {
  // An empty trust set would make every package fail root trust, which reads as
  // "every device is suspect" rather than "this server is misconfigured".
  const original = config.attestation.rootsPath;
  config.attestation.rootsPath = path.join(os.tmpdir(), 'no-such-roots-file.pem');
  resetCache();

  try {
    assert.throws(() => loadRoots(), /could not read the pinned Google attestation roots/);
  } finally {
    config.attestation.rootsPath = original;
    resetCache();
  }
});
