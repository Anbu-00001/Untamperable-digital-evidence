'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');

const config = require('../src/config');
const { verifyProofPackage } = require('../src/services/proofVerifier');

/**
 * The shipped reference package, run through the real verifier.
 *
 * ## Why this file exists
 *
 * `docs/design/examples/proof-package.example.json` is the canonical illustration
 * of the proof-package format — the document a reader consults to understand the
 * contract. Until 2026-08-07 the only thing that ever looked at it was
 * `scripts/validate-schema.js`, which checks it against the JSON Schema.
 *
 * Schema validity is a statement about SHAPE. It says every field is present and
 * well-typed; it says nothing about whether the values agree with each other. So
 * the example sat in the repository with `wallClockMillis: 1784812345678` paired
 * with `iso8601: "2026-07-23T09:12:25.678Z"` — four hours apart — and every test
 * passed, because nothing had ever recomputed it. Running it through
 * `verifyProofPackage` reported `timestampPlausible: fail`.
 *
 * A reference example that fails the contract it documents is worse than no
 * example: anyone implementing against it reproduces the defect faithfully.
 *
 * This test closes that gap permanently. It is deliberately about SEMANTIC
 * self-consistency, which is exactly what schema validation cannot see.
 */

function loadExample() {
  return JSON.parse(fs.readFileSync(config.proofExamplePath, 'utf8'));
}

/** A clock just after capture, so freshness is not what is under test here. */
function justAfterCapture(pkg) {
  return pkg.metadata.timestamp.wallClockMillis + 1000;
}

test('the shipped example is internally self-consistent about time', () => {
  const pkg = loadExample();
  const report = verifyProofPackage(pkg, null, { nowMillis: justAfterCapture(pkg) });

  // The regression this file was written for. `checkTimestampPlausible` proves
  // two identities: that elapsedRealtimeNanos/1e6 + offset equals wallClockMillis,
  // and that the human-readable iso8601 denotes that same instant. The second one
  // is the one a hand-edited example breaks.
  assert.strictEqual(
    report.checks.timestampPlausible,
    'pass',
    `the reference example must not contradict itself about when it was captured. ` +
      `Notes: ${JSON.stringify(report.notes)}`,
  );
});

test('the example iso8601 renders exactly the epoch value beside it', () => {
  // Asserted directly as well as through the verifier, so a failure points at the
  // one line to edit rather than at a check with three sub-conditions.
  const { wallClockMillis, iso8601 } = loadExample().metadata.timestamp;

  assert.strictEqual(
    Date.parse(iso8601),
    wallClockMillis,
    `iso8601 "${iso8601}" denotes ${Date.parse(iso8601)}, not ${wallClockMillis}`,
  );
});

test('the example documents what it is: structure, not a verifiable capture', () => {
  const pkg = loadExample();
  const report = verifyProofPackage(pkg, null, { nowMillis: justAfterCapture(pkg) });

  // Recorded rather than "fixed", because it is true by construction and worth
  // being explicit about: the example is HAND-WRITTEN, so its digests do not
  // correspond to any real bytes and its signature is over a root nothing
  // produced. `metadataHashMatch`, `merkleRootMatch` and `signatureValid`
  // therefore fail, and no edit to the timestamp changes that.
  //
  // This assertion exists so nobody mistakes the example for a valid package, and
  // so that if someone ever DOES regenerate it properly — real media, real
  // digests, a real signature — this test fails loudly and tells them to update
  // the claim here rather than leaving a stale comment behind.
  assert.strictEqual(report.checks.signatureValid, 'fail');
  assert.strictEqual(report.verdict, 'failed');
});
