'use strict';

const test = require('node:test');
const assert = require('node:assert');

const {
  candidateKeys, setSnapshot, clearSnapshot, isUsable, lookup, refresh,
} = require('../src/services/attestationRevocation');
const config = require('../src/config');

/**
 * Google's attestation revocation status list.
 *
 * This is the one check in the verifier that fails **open**: with no list, a
 * revoked certificate is indistinguishable from a clean one. So most of what is
 * asserted here is that "no answer" never becomes "not revoked".
 *
 * No test reaches the network — the snapshot is injected, and the one refresh
 * test supplies its own fetch.
 */

test.beforeEach(() => clearSnapshot());
test.after(() => clearSnapshot());

// --- serial encoding, where a silent miss would hide every revocation --------

test('a serial is probed as lowercase hex, since Node reports it uppercase', () => {
  // X509Certificate.serialNumber is uppercase; Google documents the key as
  // lowercase hex. Probing only what Node hands over matches nothing at all, and
  // every certificate then reads as un-revoked.
  const keys = candidateKeys('F1C172A699EAF51D');

  assert.ok(keys.has('f1c172a699eaf51d'), `lowercase hex missing from ${[...keys]}`);
});

test('a serial is also probed in decimal, because much of the list is decimal', () => {
  // Of 1732 published entries on 2026-08-03, 968 contained no a-f whatsoever.
  // For hex strings of that length the expected count is ~0.1, so those keys are
  // decimal renderings. Probing only the documented hex form would miss them.
  const keys = candidateKeys('F1C172A699EAF51D');

  assert.ok(
    keys.has('17420330893518239005'),
    `decimal rendering missing from ${[...keys]}`,
  );
});

test('leading zeros do not hide an entry', () => {
  const keys = candidateKeys('01');

  assert.ok(keys.has('01'), 'padded form missing');
  assert.ok(keys.has('1'), 'stripped form missing');
});

// --- the fail-open rule ------------------------------------------------------

test('with no snapshot, a lookup throws rather than answering "not revoked"', () => {
  assert.strictEqual(isUsable(), false);
  assert.throws(() => lookup('F1C172A699EAF51D'), /no usable revocation snapshot/);
});

test('a snapshot older than the freshness limit stops being usable', () => {
  const now = Date.now();
  setSnapshot({}, now - config.attestation.revocation.maxAgeMillis - 1);

  assert.strictEqual(isUsable(now), false, 'a stale list was treated as authoritative');
  assert.throws(() => lookup('ABCD', now), /no usable revocation snapshot/);
});

test('a fresh snapshot is usable', () => {
  const now = Date.now();
  setSnapshot({}, now);

  assert.strictEqual(isUsable(now), true);
  assert.strictEqual(lookup('ABCD', now), null, 'an absent serial should report null');
});

// --- matching -----------------------------------------------------------------

test('a revoked serial is found through the hex key', () => {
  setSnapshot({ f1c172a699eaf51d: { status: 'REVOKED', reason: 'KEY_COMPROMISE' } });

  const entry = lookup('F1C172A699EAF51D');

  assert.ok(entry, 'a revoked certificate was reported as clean');
  assert.strictEqual(entry.status, 'REVOKED');
  assert.strictEqual(entry.reason, 'KEY_COMPROMISE');
});

test('a revoked serial is found through the decimal key', () => {
  // The half of the list that would be missed by a hex-only lookup.
  //
  // The key is QUOTED deliberately. Written bare, `{ 17420330893518239005: … }`
  // is a numeric literal: JavaScript coerces it through Number first, and the
  // value exceeds Number.MAX_SAFE_INTEGER, so the property silently becomes
  // "17420330893518240000" and the lookup misses. The production path is safe
  // because JSON.parse keeps keys as strings — which the next test pins down.
  setSnapshot({ '17420330893518239005': { status: 'REVOKED', reason: 'CA_COMPROMISE' } });

  const entry = lookup('F1C172A699EAF51D');

  assert.ok(entry, 'a decimal-keyed revocation was missed');
  assert.strictEqual(entry.reason, 'CA_COMPROMISE');
});

test('JSON.parse preserves long decimal keys exactly, so no serial is lost', () => {
  // The real list arrives as JSON. Every decimal key in it is longer than
  // Number.MAX_SAFE_INTEGER can represent, so if parsing rounded them the
  // majority of the list would quietly stop matching.
  const parsed = JSON.parse('{"entries":{"17420330893518239005":{"status":"REVOKED"}}}');

  assert.deepStrictEqual(
    Object.keys(parsed.entries),
    ['17420330893518239005'],
    'JSON parsing altered a serial key',
  );

  setSnapshot(parsed.entries);
  assert.ok(lookup('F1C172A699EAF51D'), 'a serial was lost between JSON and lookup');
});

test('SUSPENDED is surfaced as an entry, not ignored for not being REVOKED', () => {
  setSnapshot({ abcd: { status: 'SUSPENDED', reason: 'SOFTWARE_FLAW' } });

  const entry = lookup('ABCD');

  assert.ok(entry, 'a suspended certificate was treated as clean');
  assert.strictEqual(entry.status, 'SUSPENDED');
});

// --- refresh ------------------------------------------------------------------

test('a failed refresh leaves the previous snapshot alone and does not throw', async () => {
  const now = Date.now();
  setSnapshot({ abcd: { status: 'REVOKED' } }, now);

  const result = await refresh({ fetchImpl: async () => { throw new Error('network down'); } });

  assert.strictEqual(result.ok, false);
  assert.match(result.reason, /network down/);
  assert.ok(lookup('ABCD', now), 'a failed refresh discarded a usable snapshot');
});

test('a non-200 response is refused rather than parsed', async () => {
  const result = await refresh({
    fetchImpl: async () => ({ ok: false, status: 503, json: async () => ({}) }),
  });

  assert.strictEqual(result.ok, false);
  assert.match(result.reason, /503/);
  assert.strictEqual(isUsable(), false, 'a 503 body became the trusted snapshot');
});

test('a body without an entries object is refused', async () => {
  // Guards against a proxy or captive portal returning valid JSON that is not
  // the status list — which would otherwise install an empty, "nothing is
  // revoked" snapshot.
  const result = await refresh({
    fetchImpl: async () => ({ ok: true, status: 200, json: async () => ({ unexpected: true }) }),
  });

  assert.strictEqual(result.ok, false);
  assert.match(result.reason, /entries/);
  assert.strictEqual(isUsable(), false);
});

test('a good response installs a usable snapshot', async () => {
  const result = await refresh({
    fetchImpl: async () => ({
      ok: true,
      status: 200,
      json: async () => ({ entries: { f1c172a699eaf51d: { status: 'REVOKED' } } }),
    }),
  });

  assert.strictEqual(result.ok, true);
  assert.strictEqual(result.count, 1);
  assert.ok(lookup('F1C172A699EAF51D'));
});
