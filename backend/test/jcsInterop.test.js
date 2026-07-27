'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const canonicalize = require('canonicalize');

/**
 * The Node half of the RFC 8785 cross-implementation regression test.
 *
 * The Android app canonicalizes metadata with
 * `io.github.erdtman:java-json-canonicalization` and signs the result; this
 * verifier re-canonicalizes with the `canonicalize` npm package and compares
 * hashes. Those are two unrelated codebases, and nothing forces them to agree.
 * If they ever diverge by one byte, `metadataHashMatch` fails for every package
 * ever signed — an unrecoverable break, since the signing key is non-exportable
 * and the captured moment cannot be re-signed.
 *
 * Both this file and
 * `android/app/src/test/kotlin/com/realitylock/app/crypto/JcsInteropVectorTest.kt`
 * assert against the SAME vector file, so a change on either side turns a test
 * red instead of silently invalidating evidence. Two suites with their own copies
 * of the expected values would not do that — the copy next to the failing test
 * would just get edited.
 */

const VECTOR_PATH = path.join(
  __dirname,
  '..',
  '..',
  'docs',
  'design',
  'examples',
  'jcs-interop-vector.json',
);

function loadVectors() {
  return JSON.parse(fs.readFileSync(VECTOR_PATH, 'utf8'));
}

function sha256Hex(text) {
  return crypto.createHash('sha256').update(Buffer.from(text, 'utf8')).digest('hex');
}

test('every shared vector canonicalizes and hashes identically to the reference', () => {
  const { vectors } = loadVectors();
  assert.ok(Array.isArray(vectors) && vectors.length > 0, 'vector file contains no vectors');

  for (const vector of vectors) {
    const canonical = canonicalize(JSON.parse(vector.inputJson));
    assert.strictEqual(
      canonical,
      vector.canonical,
      `canonical form drifted for vector '${vector.name}' — the Node and Java JCS ` +
        'implementations no longer agree, which invalidates every signature',
    );
    assert.strictEqual(
      sha256Hex(canonical),
      vector.sha256,
      `canonical hash drifted for vector '${vector.name}'`,
    );
  }
});

test('the vector file covers the number forms that actually break implementations', () => {
  // RFC 8785 §3.2.2.3 mandates ES6 number serialization, which is where real
  // implementations diverge: -0 collapsing to 0, the 1e+21 exponent threshold,
  // 1e-7 vs 0.000001, subnormals, and the 2^53 integer boundary. A vector file
  // that quietly lost these would keep passing while protecting nothing.
  const names = loadVectors().vectors.map((v) => v.name);
  for (const required of ['es6-number-edge-cases', 'float-widened-to-double']) {
    assert.ok(names.includes(required), `the '${required}' vector is missing from ${VECTOR_PATH}`);
  }
});

test('the vectors are reproduced from their inputs, not merely copied', () => {
  // Guards the file itself: if `canonical` were ever hand-edited to match a
  // broken implementation, the stored hash would no longer be the hash of the
  // stored canonical form. This catches a doctored vector file.
  for (const vector of loadVectors().vectors) {
    assert.strictEqual(
      sha256Hex(vector.canonical),
      vector.sha256,
      `vector '${vector.name}' is internally inconsistent: sha256 is not the hash of canonical`,
    );
  }
});
