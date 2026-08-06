'use strict';

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const {
  SCHEME,
  buildSignatureBase,
  parseAuthorizationHeader,
  rawSignatureToDer,
  verifySignature,
  NonceCache,
} = require('../src/services/proofOfPossession');

/**
 * Proof of possession for reading a stored package.
 *
 * These tests are weighted towards refusals, for the same reason the attestation
 * extension tests are: this code stands between an anonymous caller and a
 * photograph plus a GPS coordinate. Letting one bad request through is far worse
 * than rejecting a good one, which merely fails visibly.
 */

/** A P-256 keypair standing in for the one an Android Keystore holds. */
function makeKeyPair() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
  });
  return {
    privateKey,
    publicKeySpkiBase64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
  };
}

function signDer(privateKey, base) {
  return crypto
    .createSign('SHA256')
    .update(Buffer.from(base, 'utf8'))
    .sign({ key: privateKey, dsaEncoding: 'der' })
    .toString('base64');
}

function signRaw(privateKey, base) {
  // What an RFC 9421-compliant client sends: fixed-width r||s, not DER.
  return crypto
    .createSign('SHA256')
    .update(Buffer.from(base, 'utf8'))
    .sign({ key: privateKey, dsaEncoding: 'ieee-p1363' })
    .toString('base64');
}

const BASE_ARGS = {
  method: 'GET',
  path: '/proof/11111111-2222-3333-4444-555555555555',
  eventId: '11111111-2222-3333-4444-555555555555',
  created: 1786000000,
  nonce: 'nonce-abc',
};

// --- the signature base ------------------------------------------------------

test('the signature base covers method and path, so a signature cannot be moved', () => {
  const base = buildSignatureBase(BASE_ARGS);
  assert.match(base, /^"@method": GET$/m);
  assert.match(base, /^"@path": \/proof\/11111111-2222-3333-4444-555555555555$/m);
  assert.match(base, /^"eventid": 11111111-2222-3333-4444-555555555555$/m);
  assert.match(base, /^"created": 1786000000$/m);
  assert.match(base, /^"nonce": nonce-abc$/m);
});

test('a base cannot be built with a component missing', () => {
  for (const missing of ['method', 'path', 'eventId', 'created', 'nonce']) {
    const args = { ...BASE_ARGS };
    delete args[missing];
    assert.throws(() => buildSignatureBase(args), /signature base needs/, `missing ${missing}`);
  }
});

test('changing the method changes the base', () => {
  assert.notStrictEqual(
    buildSignatureBase(BASE_ARGS),
    buildSignatureBase({ ...BASE_ARGS, method: 'DELETE' }),
  );
});

// --- both signature encodings ------------------------------------------------

test('a DER signature verifies — what Android and Node emit by default', () => {
  const { privateKey, publicKeySpkiBase64 } = makeKeyPair();
  const base = buildSignatureBase(BASE_ARGS);

  assert.strictEqual(
    verifySignature({
      signatureBase: base,
      signatureBase64: signDer(privateKey, base),
      publicKeySpkiBase64,
    }),
    true,
  );
});

test('a raw r||s signature verifies — what RFC 9421 §3.3.4 mandates', () => {
  // Accepting only one encoding is the classic silent interop failure here: the
  // client produces a perfectly valid signature and the server rejects every one.
  const { privateKey, publicKeySpkiBase64 } = makeKeyPair();
  const base = buildSignatureBase(BASE_ARGS);
  const raw = Buffer.from(signRaw(privateKey, base), 'base64');
  assert.strictEqual(raw.length, 64, 'P-256 r||s must be exactly 64 bytes');

  assert.strictEqual(
    verifySignature({
      signatureBase: base,
      signatureBase64: raw.toString('base64'),
      publicKeySpkiBase64,
    }),
    true,
  );
});

test('r||s to DER survives values whose high bit is set', () => {
  // The case a naive converter gets wrong: DER INTEGER is signed, so a component
  // with the top bit set needs a leading zero byte or it decodes as negative.
  const r = Buffer.alloc(32, 0xff);
  const s = Buffer.alloc(32, 0x01);
  const der = rawSignatureToDer(Buffer.concat([r, s]));

  assert.strictEqual(der[0], 0x30, 'a DER signature is a SEQUENCE');
  // r must have gained a 0x00 pad; s must not have.
  assert.strictEqual(der[2], 0x02);
  assert.strictEqual(der[3], 33, 'r padded to 33 bytes');
  assert.strictEqual(der[4], 0x00);
});

test('r||s to DER strips leading zeros rather than emitting non-minimal integers', () => {
  const r = Buffer.concat([Buffer.alloc(30, 0), Buffer.from([0x01, 0x02])]);
  const s = Buffer.alloc(32, 0x01);
  const der = rawSignatureToDer(Buffer.concat([r, s]));
  assert.strictEqual(der[3], 2, 'leading zeros dropped, r is 2 bytes');
});

test('a signature of the wrong length is refused, not padded into shape', () => {
  assert.throws(() => rawSignatureToDer(Buffer.alloc(63)), /64 bytes/);
  assert.throws(() => rawSignatureToDer(Buffer.alloc(65)), /64 bytes/);
});

// --- refusals ----------------------------------------------------------------

test('a signature from a different key is refused', () => {
  const owner = makeKeyPair();
  const attacker = makeKeyPair();
  const base = buildSignatureBase(BASE_ARGS);

  // The whole premise: holding the package (and therefore the public key) is not
  // holding the private key.
  assert.strictEqual(
    verifySignature({
      signatureBase: base,
      signatureBase64: signDer(attacker.privateKey, base),
      publicKeySpkiBase64: owner.publicKeySpkiBase64,
    }),
    false,
  );
});

test('a signature over a different base is refused', () => {
  const { privateKey, publicKeySpkiBase64 } = makeKeyPair();
  const signed = signDer(privateKey, buildSignatureBase(BASE_ARGS));

  // Lifting a signature onto another event is the attack the eventId component
  // exists to stop.
  assert.strictEqual(
    verifySignature({
      signatureBase: buildSignatureBase({ ...BASE_ARGS, eventId: 'another-event' }),
      signatureBase64: signed,
      publicKeySpkiBase64,
    }),
    false,
  );
});

test('garbage in the signature field returns false rather than throwing', () => {
  const { publicKeySpkiBase64 } = makeKeyPair();
  for (const junk of ['', 'not-base64!!', Buffer.alloc(10).toString('base64')]) {
    assert.strictEqual(
      verifySignature({
        signatureBase: buildSignatureBase(BASE_ARGS),
        signatureBase64: junk,
        publicKeySpkiBase64,
      }),
      false,
    );
  }
});

// --- header parsing ----------------------------------------------------------

test('a well-formed Authorization header parses', () => {
  const parsed = parseAuthorizationHeader(
    `${SCHEME} eventId="abc",created="1786000000",nonce="n1",signature="c2ln"`,
  );
  assert.deepStrictEqual(parsed, {
    eventId: 'abc',
    created: 1786000000,
    nonce: 'n1',
    signature: 'c2ln',
  });
});

test('headers missing any required parameter are refused', () => {
  const cases = [
    `${SCHEME} created="1",nonce="n",signature="s"`,
    `${SCHEME} eventId="a",nonce="n",signature="s"`,
    `${SCHEME} eventId="a",created="1",signature="s"`,
    `${SCHEME} eventId="a",created="1",nonce="n"`,
  ];
  for (const header of cases) {
    assert.strictEqual(parseAuthorizationHeader(header), null, header);
  }
});

test('a non-numeric created is refused rather than coerced to NaN', () => {
  // NaN would sail through a naive skew comparison, since every comparison
  // against NaN is false.
  assert.strictEqual(
    parseAuthorizationHeader(`${SCHEME} eventId="a",created="soon",nonce="n",signature="s"`),
    null,
  );
});

test('another auth scheme is not mistaken for ours', () => {
  assert.strictEqual(parseAuthorizationHeader('Bearer abcdef'), null);
  assert.strictEqual(parseAuthorizationHeader('Basic dXNlcjpwdw=='), null);
  assert.strictEqual(parseAuthorizationHeader(undefined), null);
  assert.strictEqual(parseAuthorizationHeader(''), null);
});

// --- replay ------------------------------------------------------------------

test('a nonce is accepted once and refused thereafter', () => {
  const cache = new NonceCache({ maxSkewSeconds: 300 });
  const now = 1786000000;

  assert.strictEqual(cache.check('n1', now), true);
  assert.strictEqual(cache.check('n1', now), false, 'a replayed nonce must be refused');
  assert.strictEqual(cache.check('n2', now), true);
});

test('nonces past the acceptance window are pruned rather than accumulating', () => {
  const cache = new NonceCache({ maxSkewSeconds: 300 });
  const now = 1786000000;
  cache.check('old', now);
  assert.strictEqual(cache.size, 1);

  // Beyond the window the timestamp check rejects the request anyway, so keeping
  // the nonce buys nothing and would grow the map without bound.
  cache.check('fresh', now + 1000);
  assert.strictEqual(cache.size, 1, 'the stale nonce should be gone');
});
