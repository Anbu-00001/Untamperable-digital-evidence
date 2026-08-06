'use strict';

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');

const { createApp } = require('../src/app');
const config = require('../src/config');
const { createStore, setSharedStore } = require('../src/store');
const {
  SCHEME,
  buildSignatureBase,
} = require('../src/services/proofOfPossession');

/**
 * The read gate, exercised over real HTTP through the app that ships.
 *
 * The unit tests next door prove the cryptography. This proves the WIRING — that
 * the middleware is actually mounted on both endpoints that disclose something,
 * that it is not mounted on the public verdict route, and that the signature the
 * server reconstructs matches the one a client would build. A scheme that is
 * correct but attached to the wrong route protects nothing.
 */

const EVENT_ID = '11111111-2222-3333-4444-555555555555';

function makeKeyPair() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
  });
  return {
    privateKey,
    publicKeySpkiBase64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
  };
}

/** The shipped example package, re-keyed to a keypair the test controls. */
function packageSignedBy(publicKeySpkiBase64) {
  const pkg = JSON.parse(fs.readFileSync(config.proofExamplePath, 'utf8'));
  pkg.eventId = EVENT_ID;
  pkg.signature.publicKey.value = publicKeySpkiBase64;
  return pkg;
}

function authHeader({ privateKey, path, method = 'GET', eventId = EVENT_ID, nonce, created }) {
  const createdAt = created ?? Math.floor(Date.now() / 1000);
  const theNonce = nonce ?? crypto.randomBytes(12).toString('base64url');
  const base = buildSignatureBase({
    method, path, eventId, created: createdAt, nonce: theNonce,
  });
  const signature = crypto
    .createSign('SHA256')
    .update(Buffer.from(base, 'utf8'))
    .sign({ key: privateKey, dsaEncoding: 'der' })
    .toString('base64');
  return `${SCHEME} eventId="${eventId}",created="${createdAt}",` +
    `nonce="${theNonce}",signature="${signature}"`;
}

async function withServer(body) {
  const store = createStore('memory');
  setSharedStore(store);
  const app = createApp();
  const server = app.listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await body(base, store);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

// --- the gate holds ----------------------------------------------------------

test('GET /proof/:eventId without any Authorization is refused', async () => {
  const { publicKeySpkiBase64 } = makeKeyPair();
  await withServer(async (base, store) => {
    store.putPackage(packageSignedBy(publicKeySpkiBase64));

    const res = await fetch(`${base}/proof/${EVENT_ID}`);
    assert.strictEqual(res.status, 401);
    // An honest client must be told how to authenticate.
    assert.match(res.headers.get('www-authenticate') || '', new RegExp(SCHEME));
    const body = await res.json();
    assert.strictEqual(body.error, 'proof_of_possession_required');
  });
});

test('GET /proof/:eventId/media without Authorization is refused', async () => {
  const { publicKeySpkiBase64 } = makeKeyPair();
  await withServer(async (base, store) => {
    const pkg = packageSignedBy(publicKeySpkiBase64);
    store.putPackage(pkg);

    // The media route returns the photograph; leaving it open while gating the
    // package route would protect the coordinates and publish the picture.
    const res = await fetch(`${base}/proof/${EVENT_ID}/media`);
    assert.strictEqual(res.status, 401);
  });
});

test('a correctly signed request is admitted and returns the package', async () => {
  const { privateKey, publicKeySpkiBase64 } = makeKeyPair();
  await withServer(async (base, store) => {
    store.putPackage(packageSignedBy(publicKeySpkiBase64));

    const res = await fetch(`${base}/proof/${EVENT_ID}`, {
      headers: { Authorization: authHeader({ privateKey, path: `/proof/${EVENT_ID}` }) },
    });
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.package.eventId, EVENT_ID);
  });
});

// --- the gate refuses --------------------------------------------------------

test('a signature from the wrong key is refused', async () => {
  const owner = makeKeyPair();
  const attacker = makeKeyPair();
  await withServer(async (base, store) => {
    store.putPackage(packageSignedBy(owner.publicKeySpkiBase64));

    const res = await fetch(`${base}/proof/${EVENT_ID}`, {
      headers: {
        Authorization: authHeader({ privateKey: attacker.privateKey, path: `/proof/${EVENT_ID}` }),
      },
    });
    assert.strictEqual(res.status, 401);
  });
});

test('a captured Authorization header cannot be replayed', async () => {
  const { privateKey, publicKeySpkiBase64 } = makeKeyPair();
  await withServer(async (base, store) => {
    store.putPackage(packageSignedBy(publicKeySpkiBase64));
    const header = authHeader({ privateKey, path: `/proof/${EVENT_ID}` });

    const first = await fetch(`${base}/proof/${EVENT_ID}`, { headers: { Authorization: header } });
    assert.strictEqual(first.status, 200);

    // Byte-identical replay — cryptographically perfect, and still refused.
    const second = await fetch(`${base}/proof/${EVENT_ID}`, { headers: { Authorization: header } });
    assert.strictEqual(second.status, 401, 'the nonce must not be accepted twice');
  });
});

test('a stale request is refused even with a valid signature', async () => {
  const { privateKey, publicKeySpkiBase64 } = makeKeyPair();
  await withServer(async (base, store) => {
    store.putPackage(packageSignedBy(publicKeySpkiBase64));

    const longAgo = Math.floor(Date.now() / 1000) - (config.proofReadAuth.maxSkewSeconds + 60);
    const res = await fetch(`${base}/proof/${EVENT_ID}`, {
      headers: {
        Authorization: authHeader({ privateKey, path: `/proof/${EVENT_ID}`, created: longAgo }),
      },
    });
    assert.strictEqual(res.status, 401);
  });
});

test('a signature for one event does not unlock another', async () => {
  const { privateKey, publicKeySpkiBase64 } = makeKeyPair();
  const otherId = '99999999-8888-7777-6666-555555555555';
  await withServer(async (base, store) => {
    store.putPackage(packageSignedBy(publicKeySpkiBase64));
    const other = packageSignedBy(publicKeySpkiBase64);
    other.eventId = otherId;
    store.putPackage(other);

    // Signed for EVENT_ID, presented at otherId.
    const res = await fetch(`${base}/proof/${otherId}`, {
      headers: { Authorization: authHeader({ privateKey, path: `/proof/${EVENT_ID}` }) },
    });
    assert.strictEqual(res.status, 401);
  });
});

test('a missing event answers 401, not 404, so the endpoint is not an existence oracle', async () => {
  await withServer(async (base) => {
    // Distinguishing "no such event" from "bad signature" would hand an attacker
    // exactly the enumeration they wanted the endpoint for.
    const res = await fetch(`${base}/proof/${EVENT_ID}`);
    assert.strictEqual(res.status, 401);
  });
});

// --- what stays public -------------------------------------------------------

test('GET /verify/:eventId stays public and discloses no coordinates', async () => {
  const { publicKeySpkiBase64 } = makeKeyPair();
  await withServer(async (base, store) => {
    store.putPackage(packageSignedBy(publicKeySpkiBase64));

    // Independent verifiability is the point of the system; gating this would
    // defeat it (ADR-0006 §7).
    const res = await fetch(`${base}/verify/${EVENT_ID}`);
    assert.strictEqual(res.status, 200);

    const text = await res.text();
    assert.doesNotMatch(text, /latitude|longitude/i, 'a public verdict must not carry coordinates');
  });
});

test('POST /proof is not gated — an unsigned submission still works', async () => {
  const { publicKeySpkiBase64 } = makeKeyPair();
  await withServer(async (base) => {
    // Writes are constrained by construction (schema + hash-matched media), and
    // gating them would break the Android sync worker, which holds a key but
    // signs packages, not requests.
    const res = await fetch(`${base}/proof`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(packageSignedBy(publicKeySpkiBase64)),
    });
    assert.ok(res.status === 201 || res.status === 200, `expected stored, got ${res.status}`);
  });
});
