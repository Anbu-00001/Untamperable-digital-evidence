'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const {
  verifyTimestampToken,
  buildRequest,
  getTrustedRoots,
} = require('../src/services/timestampAnchor');
const { checkTimestampAnchor } = require('../src/services/proofVerifier');

/**
 * These fixtures are REAL timestamp tokens, captured on 2026-08-07 from four
 * independent authorities over one known digest.
 *
 * Real ones rather than synthesised: the whole value of this feature is that a
 * third party's token verifies, and a token this repository minted for itself
 * would test only that our encoder agrees with our decoder. Four authorities
 * because they disagree in ways that matter and that a single vendor would have
 * hidden — Certum and GlobalSign sign with a different digest algorithm than
 * DigiCert, which is precisely the assumption an earlier draft hardcoded and got
 * wrong, rejecting two valid tokens.
 *
 * All four were independently confirmed with stock tooling:
 *   openssl ts -verify -digest <root> -in <fixture> -CAfile /etc/ssl/certs/...
 *
 * No test here makes a network call. Requesting a token is exercised only
 * against its own encoder (`buildRequest`); the live path is covered by
 * scripts/ops/probe_tsa.js, which is run by hand precisely because it depends on
 * a third party being up.
 */
const FIXTURES = path.join(__dirname, 'fixtures', 'timestamp');
const ROOT_HEX = fs.readFileSync(path.join(FIXTURES, 'root.hex'), 'utf8').trim();
const AUTHORITIES = ['digicert', 'certum', 'globalsign', 'sslcom'];

const tokenFor = (name) => fs.readFileSync(path.join(FIXTURES, `${name}.tsr`));

for (const name of AUTHORITIES) {
  test(`verifies a real ${name} token over the expected root`, async () => {
    const result = await verifyTimestampToken(tokenFor(name), ROOT_HEX);
    assert.deepEqual(result.reasons, [], `unexpected reasons from ${name}`);
    assert.equal(result.ok, true);
    assert.equal(result.imprintHex, ROOT_HEX);
    // Chained to a public root, not merely self-consistent.
    assert.ok(result.chainLength >= 2, `${name} chain length ${result.chainLength}`);
    assert.match(result.genTime, /^2026-08-07T/);
  });
}

test('rejects a token issued over a different digest', async () => {
  const result = await verifyTimestampToken(tokenFor('digicert'), 'a'.repeat(64));
  assert.equal(result.ok, false);
  assert.ok(
    result.reasons.some((r) => r.includes('different digest')),
    `reasons were ${JSON.stringify(result.reasons)}`,
  );
});

test('rejects a token whose signature has been tampered with', async () => {
  const token = Buffer.from(tokenFor('digicert'));
  // Late in the structure: inside the signature, past the TSTInfo.
  token[token.length - 40] ^= 0xff;
  const result = await verifyTimestampToken(token, ROOT_HEX);
  assert.equal(result.ok, false);
  assert.ok(
    result.reasons.some((r) => r.includes('signature does not verify')),
    `reasons were ${JSON.stringify(result.reasons)}`,
  );
});

test('rejects a token that will not parse at all', async () => {
  const result = await verifyTimestampToken(Buffer.from('not a token'), ROOT_HEX);
  assert.equal(result.ok, false);
  assert.equal(result.genTime, null);
});

test('rejects a token that chains to nothing we trust', async () => {
  // Same real token, empty trust store. Isolates the chain check: everything
  // else about this token is valid, so only the anchoring can fail.
  const result = await verifyTimestampToken(tokenFor('digicert'), ROOT_HEX, { trustedCerts: [] });
  assert.equal(result.ok, false);
  assert.ok(
    result.reasons.some((r) => r.includes('does not chain to a trusted root')),
    `reasons were ${JSON.stringify(result.reasons)}`,
  );
});

test('the request imprint is the root itself, not a hash of it', () => {
  // The trap this pins: pkijs's MessageImprint.create() hashes what it is given,
  // which would timestamp SHA-256(root) — a value appearing nowhere in the proof
  // package, leaving the token unverifiable by `openssl ts -verify -digest`.
  const { der } = buildRequest(ROOT_HEX);
  assert.ok(
    der.includes(Buffer.from(ROOT_HEX, 'hex')),
    'the DER request does not contain the raw root bytes',
  );
});

test('refuses to build a request for anything that is not a SHA-256 digest', () => {
  for (const bad of ['', 'nothex', 'ABCD'.repeat(16), ROOT_HEX.slice(0, 63)]) {
    assert.throws(() => buildRequest(bad), /64 lowercase hex/);
  }
});

test('Node ships enough trusted roots to anchor a public TSA', () => {
  // Guards the assumption behind not pinning a TSA certificate: if a future Node
  // stopped bundling the Mozilla set, every anchor would silently go unverified.
  assert.ok(getTrustedRoots().length > 100, `only ${getTrustedRoots().length} roots parsed`);
});

// --- how a verification result becomes check outcomes ------------------------

const packageClaiming = (wallClockMillis) => ({
  metadata: { timestamp: { wallClockMillis } },
});

// 2026-08-07T13:49:44Z, the genTime of the digicert fixture.
const ANCHOR_MILLIS = Date.parse('2026-08-07T13:49:44.000Z');
const goodAnchor = { ok: true, genTimeMillis: ANCHOR_MILLIS, genTime: '2026-08-07T13:49:44.000Z' };

test('no anchor is unavailable, never a pass and never a failure', () => {
  const checks = checkTimestampAnchor(packageClaiming(ANCHOR_MILLIS), null, []);
  assert.equal(checks.timestampAnchorValid, 'unavailable');
  assert.equal(checks.captureTimeNotAfterAnchor, 'unavailable');
});

test('a capture before its anchor passes', () => {
  const notes = [];
  const checks = checkTimestampAnchor(
    packageClaiming(ANCHOR_MILLIS - 60_000),
    goodAnchor,
    notes,
  );
  assert.equal(checks.timestampAnchorValid, 'pass');
  assert.equal(checks.captureTimeNotAfterAnchor, 'pass');
  assert.deepEqual(notes, []);
});

test('a capture long before its anchor still passes — lateness is not suspicious', () => {
  // Asymmetry by design: a package can be anchored any amount of time after it
  // was captured (the phone was offline for a week), and flagging that would
  // punish exactly the offline-first use this system is built for.
  const checks = checkTimestampAnchor(
    packageClaiming(ANCHOR_MILLIS - 30 * 24 * 3600 * 1000),
    goodAnchor,
    [],
  );
  assert.equal(checks.captureTimeNotAfterAnchor, 'pass');
});

test('ordinary clock drift is tolerated rather than called impossible', () => {
  const checks = checkTimestampAnchor(packageClaiming(ANCHOR_MILLIS + 120_000), goodAnchor, []);
  assert.equal(checks.captureTimeNotAfterAnchor, 'pass');
});

test('a capture claimed well after its own anchor is a contradiction', () => {
  const notes = [];
  const checks = checkTimestampAnchor(
    packageClaiming(ANCHOR_MILLIS + 48 * 3600 * 1000),
    goodAnchor,
    notes,
  );
  assert.equal(checks.captureTimeNotAfterAnchor, 'fail');
  assert.equal(checks.timestampAnchorValid, 'pass');
  assert.ok(notes.some((n) => n.includes('cannot postdate')), notes.join(' | '));
});

test('a present-but-invalid anchor fails rather than reading as absent', () => {
  const notes = [];
  const checks = checkTimestampAnchor(
    packageClaiming(ANCHOR_MILLIS),
    { ok: false, reasons: ['the TSA signature does not verify'] },
    notes,
  );
  assert.equal(checks.timestampAnchorValid, 'fail');
  // No opinion on the time: a token that does not verify cannot bound anything.
  assert.equal(checks.captureTimeNotAfterAnchor, 'unavailable');
  assert.ok(notes.some((n) => n.includes('did not verify')), notes.join(' | '));
});
