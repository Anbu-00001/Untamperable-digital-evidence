'use strict';

const test = require('node:test');
const assert = require('node:assert');
const canonicalize = require('canonicalize');

const { merkleRoot2Leaf, verifyProofPackage, DECISIVE_CHECKS, PASS, FAIL, UNAVAILABLE } =
  require('../src/services/proofVerifier');
const { sha256Hex } = require('../src/services/hashService');
const { haversineMeters, impliedSpeedKmh } = require('../src/services/plausibility');

/**
 * A fixed "verification time", injected so the timestamp-plausibility tests do
 * not depend on when the suite happens to run. Chosen just after the fixture's
 * capture instant below.
 */
const NOW = 1784812400000;

/**
 * The cross-implementation test vector, asserted identically here and in
 * Android's CryptoCoreTest. Both sides check a fixed known answer rather than
 * each other, so a drift in either is caught immediately and locally instead of
 * surfacing later as an unexplained signature mismatch on a real capture.
 *
 * Source of truth: MerkleTree.CROSS_IMPL_TEST_VECTOR (android/.../crypto/MerkleTree.kt)
 */
const VECTOR = {
  mediaInput: 'reality-lock-media-test-vector',
  metadataInput: 'reality-lock-metadata-test-vector',
  mediaHash: '0c8655110a97d6ffb2f8ae15d551e4cff818b5e6e05e6260f39842426a942fea',
  metadataHash: '695cadf134d0a6cee0afd0480f31378d211c21189d38f4dcfdf3a5bdfdabb391',
  root: '63e7fd2d0841a4776b1ddba3dc9503c9a91779e862b62bdffdaf828b6c792270',
  rootWithLeavesSwapped: '89e64f139506d0893f4cbca9a77cd223937cb801977704145e1d0165e8feca39',
};

test('cross-implementation vector: leaf digests match Android', () => {
  assert.strictEqual(sha256Hex(Buffer.from(VECTOR.mediaInput, 'utf8')), VECTOR.mediaHash);
  assert.strictEqual(sha256Hex(Buffer.from(VECTOR.metadataInput, 'utf8')), VECTOR.metadataHash);
});

test('cross-implementation vector: Merkle root matches Android', () => {
  assert.strictEqual(merkleRoot2Leaf(VECTOR.mediaHash, VECTOR.metadataHash), VECTOR.root);
});

test('Merkle composition is positional, not order-independent', () => {
  assert.strictEqual(
    merkleRoot2Leaf(VECTOR.metadataHash, VECTOR.mediaHash),
    VECTOR.rootWithLeavesSwapped
  );
  assert.notStrictEqual(VECTOR.root, VECTOR.rootWithLeavesSwapped);
});

test('Merkle concatenates raw digest bytes, not hex text', () => {
  // Pins the reading of the spec's ambiguous `‖`.
  const hexTextInterpretation = sha256Hex(VECTOR.mediaHash + VECTOR.metadataHash);
  assert.notStrictEqual(hexTextInterpretation, VECTOR.root);
});

// --- end-to-end over a synthetic but genuinely signed package ---------------

// The fixture lives in helpers/ because the route suite builds the same signed
// packages; two copies would drift.
const { buildSignedPackage, locationAt, COORDS } = require('./helpers/signedPackage');

test('a genuine package passes every cryptographic check', () => {
  const { pkg, media } = buildSignedPackage();
  const { checks } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.mediaHashMatch, PASS);
  assert.strictEqual(checks.metadataHashMatch, PASS);
  assert.strictEqual(checks.merkleRootMatch, PASS);
  assert.strictEqual(checks.signatureValid, PASS);
});

test('flipping one bit of the media fails the media leaf', () => {
  const { pkg, media } = buildSignedPackage();
  const tampered = Buffer.from(media);
  tampered[0] ^= 0x01;

  const { checks, verdict } = verifyProofPackage(pkg, tampered);

  assert.strictEqual(checks.mediaHashMatch, FAIL);
  assert.strictEqual(verdict, 'failed');
});

test('changing one metadata field fails the metadata leaf', () => {
  const { pkg, media } = buildSignedPackage();
  pkg.metadata.timestamp.wallClockMillis += 1;

  const { checks, verdict } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.metadataHashMatch, FAIL);
  assert.strictEqual(verdict, 'failed');
});

test('substituting the signature fails signature verification', () => {
  const { pkg, media } = buildSignedPackage();
  const other = buildSignedPackage();
  pkg.signature.value = other.pkg.signature.value;

  const { checks, verdict } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.signatureValid, FAIL);
  assert.strictEqual(verdict, 'failed');
});

test('a rewritten root that matches its leaves still fails the signature', () => {
  // The strongest single case: an attacker edits the metadata AND recomputes
  // the leaf and root so the tree is internally consistent. Only the signature
  // catches this — which is exactly why the root is signed.
  const { pkg, media } = buildSignedPackage();
  pkg.metadata.device.model = 'NotTheRealDevice';
  pkg.merkle.leaves.metadata = sha256Hex(Buffer.from(canonicalize(pkg.metadata), 'utf8'));
  pkg.merkle.root = merkleRoot2Leaf(pkg.merkle.leaves.media, pkg.merkle.leaves.metadata);

  const { checks, verdict } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.metadataHashMatch, PASS, 'the tree is internally consistent');
  assert.strictEqual(checks.merkleRootMatch, PASS, 'the tree is internally consistent');
  assert.strictEqual(checks.signatureValid, FAIL, 'but the signature does not cover the new root');
  assert.strictEqual(verdict, 'failed');
});

test('missing media reports unavailable, never pass', () => {
  const { pkg } = buildSignedPackage();
  const { checks, verdict } = verifyProofPackage(pkg, undefined);

  assert.strictEqual(checks.mediaHashMatch, UNAVAILABLE);
  assert.notStrictEqual(verdict, 'verified');
});

test('an unattested package does not claim hardware backing', () => {
  const { pkg, media } = buildSignedPackage();
  const { checks, advisories, verdict } = verifyProofPackage(pkg, media, { nowMillis: NOW });

  // `unavailable`, not `fail`: the chain is absent, which is the absence of
  // evidence rather than evidence of a defect (ADR-0006 §5). This changed in
  // Phase 5 — it used to report FAIL, which wrongly condemned every package from
  // a device that cannot attest.
  assert.strictEqual(checks.attestationPresent, UNAVAILABLE);
  assert.strictEqual(checks.attestationKeyBinding, UNAVAILABLE);

  // The package still verifies — its integrity since capture IS proven — but the
  // missing hardware guarantee is impossible to overlook.
  assert.strictEqual(verdict, 'verified');
  assert.ok(
    advisories.some((a) => a.includes('No key attestation chain')),
    'an absent chain must raise an advisory rather than pass silently',
  );
});

/**
 * A self-signed EC certificate, subject "Not A Google Attestation Root".
 * Anyone can mint one in two openssl commands, which is exactly the point: it is
 * the cheapest possible forgery of an attestation chain.
 */
const SELF_SIGNED_CERT_B64 =
  'MIIBpTCCAUugAwIBAgIUf+yqqhLQEbUatsvKSPWzqSjJq48wCgYIKoZIzj0EAwIwKDEmMCQGA1UEAwwd' +
  'Tm90IEEgR29vZ2xlIEF0dGVzdGF0aW9uIFJvb3QwHhcNMjYwNzI3MDQ1MjE4WhcNMzYwNzI0MDQ1MjE4' +
  'WjAoMSYwJAYDVQQDDB1Ob3QgQSBHb29nbGUgQXR0ZXN0YXRpb24gUm9vdDBZMBMGByqGSM49AgEGCCqG' +
  'SM49AwEHA0IABPsGpkOewLJaNm2yM8Ko4+Iu9qGTwEJLjHinP4P8t+ATRE+3w7OJCwAN3CfprvzaUgWC' +
  'MDk+Z3cUrpRIku0iM66jUzBRMB0GA1UdDgQWBBRUyLTYzuTdJ3NTPbXIRZk1tJ+oNDAfBgNVHSMEGDAW' +
  'gBRUyLTYzuTdJ3NTPbXIRZk1tJ+oNDAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0gAMEUCIDuz' +
  'iaS38MyovRtnTTiFdPx+l7gckhvM4v6U8V3ciQa5AiEAy7E/HXGUdeFOJx5U4W+JWGSmLV2g52kY0E2x' +
  'qEt8VlA=';

test('a single self-signed certificate cannot pass as an attestation chain', () => {
  // The regression this pins: the linkage loop runs `chain.length - 1` times, so
  // a one-element chain skipped it entirely and left `linked` at its initial
  // `true` — reporting `attestationChainValid: pass` having verified nothing.
  // The schema puts no `minItems` on the chain, so this package is well-formed.
  const { pkg, media } = buildSignedPackage();
  pkg.signature.attestationCertificateChain = [SELF_SIGNED_CERT_B64];

  const { checks, notes } = verifyProofPackage(pkg, media, { nowMillis: NOW });

  assert.strictEqual(checks.attestationChainValid, FAIL);
  assert.ok(
    notes.some((n) => n.includes('single certificate')),
    'the reason must be stated, not just the verdict',
  );
});

test('a lone self-signed certificate is not treated as an anchored chain', () => {
  // A single certificate cannot chain to an issuer, so linkage fails and root
  // trust is not assessable — `unavailable`, which is a different claim from
  // "checked and did not anchor" (`fail`) and must not be collapsed into it.
  //
  // This test previously asserted that `attestationRootTrusted` could NEVER be
  // `pass`, because at the time no root set was consulted anywhere. Phase 8 pins
  // Google's published roots and checks them, so that invariant is gone; what
  // survives is the part that still matters — a chain which does not anchor must
  // say so, or a reader will take it for hardware-backed.
  const { pkg, media } = buildSignedPackage();
  pkg.signature.attestationCertificateChain = [SELF_SIGNED_CERT_B64];

  const { checks, advisories } = verifyProofPackage(pkg, media, { nowMillis: NOW });

  assert.strictEqual(checks.attestationPresent, PASS);
  assert.strictEqual(checks.attestationChainValid, FAIL);
  assert.strictEqual(checks.attestationRootTrusted, UNAVAILABLE);
  assert.ok(
    advisories.some((a) => /anchor/i.test(a) && /hardware backing/i.test(a)),
    `an unanchored chain must say so. advisories were:\n${advisories.join('\n')}`,
  );
});

// --- verdict semantics (ADR-0006 §5) ---------------------------------------

test('a fully checkable genuine package is verified', () => {
  const { pkg, media } = buildSignedPackage();
  const { checks, verdict } = verifyProofPackage(pkg, media, { nowMillis: NOW });

  for (const name of DECISIVE_CHECKS) {
    assert.strictEqual(checks[name], PASS, `${name} should pass`);
  }
  assert.strictEqual(verdict, 'verified');
});

test('missing media holds the verdict at incomplete, never verified', () => {
  const { pkg } = buildSignedPackage();
  const { checks, verdict } = verifyProofPackage(pkg, undefined, { nowMillis: NOW });

  assert.strictEqual(checks.mediaHashMatch, UNAVAILABLE);
  // mediaHashMatch is decisive, so `unavailable` blocks `verified` without
  // implying anything failed.
  assert.strictEqual(verdict, 'incomplete');
});

// --- timestamp plausibility (check 4) --------------------------------------

test('timestamp self-consistency is an exact identity, and breaking it fails', () => {
  const { pkg, media } = buildSignedPackage();
  // The producer computes wallClockMillis = floor(elapsedRealtimeNanos/1e6) +
  // wallClockOffsetMillis, so this is exact arithmetic. Move the offset alone and
  // the identity no longer holds.
  pkg.metadata.timestamp.wallClockOffsetMillis += 1000;

  // Note the metadata leaf now mismatches too — which is the point: an editor
  // cannot break the identity without also breaking the hash. This test isolates
  // the timestamp verdict.
  const { checks, verdict } = verifyProofPackage(pkg, media, { nowMillis: NOW });

  assert.strictEqual(checks.timestampPlausible, FAIL);
  assert.strictEqual(verdict, 'failed');
});

test('an iso8601 rendering that disagrees with the epoch millis fails', () => {
  const { pkg, media } = buildSignedPackage();
  pkg.metadata.timestamp.iso8601 = '2020-01-01T00:00:00.000Z';

  const { checks } = verifyProofPackage(pkg, media, { nowMillis: NOW });

  assert.strictEqual(checks.timestampPlausible, FAIL);
});

test('a capture from the future fails, but ordinary clock skew does not', () => {
  const { pkg, media } = buildSignedPackage();
  const capturedAt = pkg.metadata.timestamp.wallClockMillis;

  // Verified one second before the capture instant: within the skew allowance,
  // because an unsynchronised device clock is common and is not forgery.
  const tolerated = verifyProofPackage(pkg, media, { nowMillis: capturedAt - 1000 });
  assert.strictEqual(tolerated.checks.timestampPlausible, PASS);

  // A day before: nothing can be captured after the moment it is verified.
  const rejected = verifyProofPackage(pkg, media, { nowMillis: capturedAt - 86400000 });
  assert.strictEqual(rejected.checks.timestampPlausible, FAIL);
  assert.strictEqual(rejected.verdict, 'failed');
});

test('a package with no recorded offset is incomplete, not passed', () => {
  // wallClockOffsetMillis is nullable in the schema, so the identity check must
  // be skipped rather than crash — while the other two sub-checks still apply.
  //
  // Skipping it must NOT yield `pass`. That sub-check is the only one that
  // distinguishes an instant derived from the monotonic clock from one typed in
  // by hand, so returning `pass` without it would let an attacker defeat the
  // strongest part of a DECISIVE check by deleting an optional field. This
  // asserts the module's own stated rule: a check that cannot be performed
  // returns `unavailable`, never `pass`.
  const { pkg, media } = buildSignedPackage({ timestamp: { wallClockOffsetMillis: null } });
  const ok = verifyProofPackage(pkg, media, { nowMillis: NOW });
  assert.strictEqual(ok.checks.timestampPlausible, UNAVAILABLE);
  assert.strictEqual(ok.verdict, 'incomplete');

  const { pkg: bad, media: badMedia } = buildSignedPackage({
    timestamp: { wallClockOffsetMillis: null, iso8601: '1999-12-31T23:59:59.000Z' },
  });
  assert.strictEqual(
    verifyProofPackage(bad, badMedia, { nowMillis: NOW }).checks.timestampPlausible,
    FAIL,
  );
});

// --- location plausibility (check 5) ----------------------------------------

/**
 * The same expected great-circle distances asserted in Android's
 * LocationPlausibilityTest. Both sides check fixed known answers rather than
 * each other, so a drift in either implementation is caught locally instead of
 * surfacing later as an unexplained disagreement about the same pair of captures.
 */
const { chennai: CHENNAI, bangalore: BANGALORE, newYork: NEW_YORK } = COORDS;

test('cross-implementation vector: haversine matches Android', () => {
  assert.ok(Math.abs(haversineMeters(...CHENNAI, ...BANGALORE) - 290000) < 3000);
  assert.ok(Math.abs(haversineMeters(...CHENNAI, ...NEW_YORK) - 13473000) < 20000);
  assert.strictEqual(haversineMeters(...CHENNAI, ...CHENNAI), 0);
});

test('implied speed is distance over time', () => {
  assert.strictEqual(impliedSpeedKmh(100000, 3600000), 100);
});

/** Two signed packages an hour apart, at the given coordinates. */
function buildConsecutivePair(fromCoords, toCoords, gapMillis) {
  const previous = buildSignedPackage({
    eventId: '11111111-1111-4111-8111-111111111111',
    location: locationAt(...fromCoords),
  });
  const current = buildSignedPackage({
    eventId: '22222222-2222-4222-8222-222222222222',
    location: locationAt(...toCoords),
    timestamp: {
      wallClockMillis: previous.pkg.metadata.timestamp.wallClockMillis + gapMillis,
      // The identity must still hold, so move the monotonic clock by the same
      // amount rather than only the wall clock.
      elapsedRealtimeNanos:
        previous.pkg.metadata.timestamp.elapsedRealtimeNanos + gapMillis * 1000000,
      iso8601: new Date(previous.pkg.metadata.timestamp.wallClockMillis + gapMillis).toISOString(),
    },
  });
  return { previous, current };
}

test('no stored history reports unavailable, not a pass', () => {
  const { pkg, media } = buildSignedPackage({ location: locationAt(...CHENNAI) });
  const { checks, advisories, verdict } = verifyProofPackage(pkg, media, {
    nowMillis: NOW,
    previousPackage: null,
  });

  assert.strictEqual(checks.locationPlausible, UNAVAILABLE);
  // Non-decisive, so a first-ever capture is not punished for being first.
  assert.strictEqual(verdict, 'verified');
  assert.ok(advisories.some((a) => a.includes('could not be cross-checked')));
});

test('an event with no location cannot be cross-checked', () => {
  const { pkg, media } = buildSignedPackage();
  const { checks } = verifyProofPackage(pkg, media, { nowMillis: NOW, previousPackage: null });
  assert.strictEqual(checks.locationPlausible, UNAVAILABLE);
});

test('ordinary travel between two captures is plausible', () => {
  // Chennai to Bangalore (290 km) in 3 hours = ~97 km/h. A car.
  const { previous, current } = buildConsecutivePair(CHENNAI, BANGALORE, 3 * 3600000);
  const { checks, verdict } = verifyProofPackage(current.pkg, current.media, {
    nowMillis: current.pkg.metadata.timestamp.wallClockMillis + 1000,
    previousPackage: previous.pkg,
  });

  assert.strictEqual(checks.locationPlausible, PASS);
  assert.strictEqual(verdict, 'verified');
});

test('air travel is NOT flagged — the plan\'s 300 km/h bound would have been wrong', () => {
  // Chennai to New York (13,473 km) in 15 hours = ~898 km/h: a real long-haul
  // flight. Under the original plan's 300 km/h threshold this would have been a
  // false "teleportation" (ADR-0005 §2).
  const { previous, current } = buildConsecutivePair(CHENNAI, NEW_YORK, 15 * 3600000);
  const { checks } = verifyProofPackage(current.pkg, current.media, {
    nowMillis: current.pkg.metadata.timestamp.wallClockMillis + 1000,
    previousPackage: previous.pkg,
  });

  assert.strictEqual(checks.locationPlausible, PASS);
});

test('teleportation fails the location check and the verdict', () => {
  // Chennai to New York in one minute = ~808,000 km/h.
  const { previous, current } = buildConsecutivePair(CHENNAI, NEW_YORK, 60000);
  const { checks, notes, verdict } = verifyProofPackage(current.pkg, current.media, {
    nowMillis: current.pkg.metadata.timestamp.wallClockMillis + 1000,
    previousPackage: previous.pkg,
  });

  assert.strictEqual(checks.locationPlausible, FAIL);
  // A detected physical impossibility is a real finding, so it blocks the verdict
  // even though it is not a decisive check (ADR-0006 §5 rule 1).
  assert.strictEqual(verdict, 'failed');
  assert.ok(notes.some((n) => n.includes('exceeds the')));
});

test('captures too close in time or space are not judged', () => {
  // Half a second apart: dividing GNSS scatter by a near-zero interval would
  // manufacture a phantom huge speed.
  const tooSoon = buildConsecutivePair(CHENNAI, NEW_YORK, 500);
  assert.strictEqual(
    verifyProofPackage(tooSoon.current.pkg, tooSoon.current.media, {
      nowMillis: NOW + 10000,
      previousPackage: tooSoon.previous.pkg,
    }).checks.locationPlausible,
    UNAVAILABLE,
  );

  // Standing still: well inside the 50 m minimum-distance guard.
  const stationary = buildConsecutivePair(CHENNAI, [CHENNAI[0] + 0.0001, CHENNAI[1]], 60000);
  assert.strictEqual(
    verifyProofPackage(stationary.current.pkg, stationary.current.media, {
      nowMillis: stationary.current.pkg.metadata.timestamp.wallClockMillis + 1000,
      previousPackage: stationary.previous.pkg,
    }).checks.locationPlausible,
    PASS,
  );
});

test('a mock-provider location raises an advisory without failing the signature', () => {
  const { pkg, media } = buildSignedPackage({
    location: locationAt(...CHENNAI, { isMock: true }),
  });
  const { checks, advisories, verdict } = verifyProofPackage(pkg, media, { nowMillis: NOW });

  // The signature is genuine; spoofing happened before capture, so this is a
  // provenance finding rather than a tamper-evidence one.
  assert.strictEqual(checks.signatureValid, PASS);
  assert.strictEqual(verdict, 'verified');
  assert.ok(
    advisories.some((a) => a.includes('MOCK provider')),
    'a mock location must be impossible to miss',
  );
});

test('the verifier overrides a device that wrongly claimed plausibility', () => {
  const { previous, current } = buildConsecutivePair(CHENNAI, NEW_YORK, 60000);
  // The device-side block is unsigned and advisory, so a tamperer can set it to
  // true. It must gain them nothing.
  current.pkg.integrity = {
    location: { isMock: false, mockDetectionChecks: [], gnssChecked: false, speedPlausible: true },
  };

  const { checks, advisories, verdict } = verifyProofPackage(current.pkg, current.media, {
    nowMillis: current.pkg.metadata.timestamp.wallClockMillis + 1000,
    previousPackage: previous.pkg,
  });

  assert.strictEqual(checks.locationPlausible, FAIL);
  assert.strictEqual(verdict, 'failed');
  assert.ok(advisories.some((a) => a.includes('device claimed')));
});
