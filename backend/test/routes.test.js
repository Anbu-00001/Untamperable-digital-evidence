'use strict';

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('crypto');

const { createApp } = require('../src/app');
const { createStore, setSharedStore } = require('../src/store');
const { buildSignedPackage, locationAt, COORDS } = require('./helpers/signedPackage');

/**
 * Drives the real Express app over a real socket, so the middleware chain —
 * `express.json` vs `express.raw` body parsing in particular — is exercised
 * exactly as it will be in production. Mounting handlers directly would skip the
 * part most likely to be wrong.
 *
 * Each case gets a fresh in-memory store, which enforces the same immutability
 * rules as the filesystem driver (see store.test.js).
 */
async function withServer(body) {
  setSharedStore(createStore('memory'));
  const server = createApp().listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await body(base);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

const postJson = (base, path, body) =>
  fetch(base + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

const postBytes = (base, path, bytes) =>
  fetch(base + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: bytes,
  });

// --- POST /proof -----------------------------------------------------------

test('POST /proof stores a package and reports where the media goes', async () => {
  await withServer(async (base) => {
    const { pkg } = buildSignedPackage();

    const res = await postJson(base, '/proof', pkg);
    const body = await res.json();

    assert.strictEqual(res.status, 201);
    assert.strictEqual(body.created, true);
    assert.strictEqual(body.eventId, pkg.eventId);
    assert.strictEqual(body.mediaPresent, false);
    // The upload path is told to the client so the app never hardcodes it.
    assert.strictEqual(body.mediaUploadPath, `/proof/${pkg.eventId}/media`);
  });
});

test('POST /proof rejects a package that is not schema-valid', async () => {
  await withServer(async (base) => {
    const { pkg } = buildSignedPackage();
    delete pkg.merkle;

    const res = await postJson(base, '/proof', pkg);

    assert.strictEqual(res.status, 400);
    assert.strictEqual((await res.json()).validated, false);
  });
});

test('POST /proof is idempotent for a retry, and a 409 for a rewrite', async () => {
  await withServer(async (base) => {
    const { pkg } = buildSignedPackage();
    assert.strictEqual((await postJson(base, '/proof', pkg)).status, 201);

    // The sync worker retrying after a dropped connection.
    const retry = await postJson(base, '/proof', pkg);
    assert.strictEqual(retry.status, 200);
    assert.strictEqual((await retry.json()).created, false);

    // Someone trying to rewrite stored evidence under the same event id.
    const rewritten = buildSignedPackage({ mediaText: 'different media entirely' }).pkg;
    rewritten.eventId = pkg.eventId;
    const conflict = await postJson(base, '/proof', rewritten);
    assert.strictEqual(conflict.status, 409);
    assert.strictEqual((await conflict.json()).error, 'conflict');
  });
});

// --- POST /proof/:id/media -------------------------------------------------

test('media upload is accepted only when it hashes to the signed digest', async () => {
  await withServer(async (base) => {
    const { pkg, media } = buildSignedPackage();
    await postJson(base, '/proof', pkg);

    const ok = await postBytes(base, `/proof/${pkg.eventId}/media`, media);
    const body = await ok.json();
    assert.strictEqual(ok.status, 201);
    assert.strictEqual(body.storageRef, `sha256:${pkg.media.sha256}`);
  });
});

test('media that does not match the signed digest is refused, not stored', async () => {
  await withServer(async (base) => {
    const { pkg, media } = buildSignedPackage();
    await postJson(base, '/proof', pkg);

    const tampered = Buffer.from(media);
    tampered[0] ^= 0x01;

    const res = await postBytes(base, `/proof/${pkg.eventId}/media`, tampered);
    const body = await res.json();

    assert.strictEqual(res.status, 409);
    assert.strictEqual(body.error, 'media_hash_mismatch');
    assert.strictEqual(body.expected, pkg.media.sha256);

    // The store must be empty of media — refusing means refusing.
    assert.strictEqual((await fetch(`${base}/proof/${pkg.eventId}/media`)).status, 404);
  });
});

test('media for an unknown package is a 404, and a bad id a 400', async () => {
  await withServer(async (base) => {
    const unknown = '99999999-9999-4999-8999-999999999999';
    assert.strictEqual((await postBytes(base, `/proof/${unknown}/media`, Buffer.from('x'))).status, 404);
    assert.strictEqual((await postBytes(base, '/proof/not-a-uuid/media', Buffer.from('x'))).status, 400);
  });
});

test('an empty media body is rejected', async () => {
  await withServer(async (base) => {
    const { pkg } = buildSignedPackage();
    await postJson(base, '/proof', pkg);

    const res = await postBytes(base, `/proof/${pkg.eventId}/media`, Buffer.alloc(0));
    assert.strictEqual(res.status, 400);
    assert.strictEqual((await res.json()).error, 'empty_body');
  });
});

// --- GET /proof/:id --------------------------------------------------------

test('GET /proof returns the package and the media round-trips byte-exactly', async () => {
  await withServer(async (base) => {
    const { pkg, media } = buildSignedPackage();
    await postJson(base, '/proof', pkg);
    await postBytes(base, `/proof/${pkg.eventId}/media`, media);

    const res = await fetch(`${base}/proof/${pkg.eventId}`);
    const body = await res.json();
    assert.strictEqual(res.status, 200);
    assert.deepStrictEqual(body.package, pkg);
    assert.strictEqual(body.mediaPresent, true);

    const bytes = Buffer.from(await (await fetch(`${base}/proof/${pkg.eventId}/media`)).arrayBuffer());
    // Byte-exact, or the media leaf would not recompute.
    assert.deepStrictEqual(bytes, media);
  });
});

test('a path-traversal event id cannot reach the filesystem', async () => {
  await withServer(async (base) => {
    const res = await fetch(`${base}/proof/${encodeURIComponent('../../package.json')}`);
    assert.strictEqual(res.status, 400);
    assert.strictEqual((await res.json()).error, 'invalid_event_id');
  });
});

// --- verification ----------------------------------------------------------

test('POST /verify falls back to the stored media, so a synced package verifies fully', async () => {
  await withServer(async (base) => {
    const { pkg, media } = buildSignedPackage();
    await postJson(base, '/proof', pkg);
    await postBytes(base, `/proof/${pkg.eventId}/media`, media);

    // Note: no mediaBase64 in the request. The store's own copy is used, which is
    // the whole point of syncing the bytes.
    const res = await postJson(base, '/verify', pkg);
    const body = await res.json();

    assert.strictEqual(body.checks.mediaHashMatch, 'pass');
    assert.strictEqual(body.verdict, 'verified');
  });
});

test('POST /verify without any media is incomplete, never verified', async () => {
  await withServer(async (base) => {
    const { pkg } = buildSignedPackage();
    const body = await (await postJson(base, '/verify', pkg)).json();

    assert.strictEqual(body.checks.mediaHashMatch, 'unavailable');
    assert.strictEqual(body.verdict, 'incomplete');
  });
});

test('GET /verify/:id is the QR endpoint: a verdict, and never the metadata', async () => {
  await withServer(async (base) => {
    const { pkg, media } = buildSignedPackage({ location: locationAt(...COORDS.chennai) });
    await postJson(base, '/proof', pkg);
    await postBytes(base, `/proof/${pkg.eventId}/media`, media);

    const res = await fetch(`${base}/verify/${pkg.eventId}`);
    const body = await res.json();

    assert.strictEqual(res.status, 200);
    assert.strictEqual(body.verdict, 'verified');
    assert.strictEqual(body.merkleRoot, pkg.merkle.root);
    assert.ok(Array.isArray(body.limitations) && body.limitations.length > 0);

    // The privacy property: scanning a badge must not disclose where someone was
    // (ADR-0006 §7). Assert on the serialised body so a nested leak is caught too.
    const raw = JSON.stringify(body);
    assert.ok(!('package' in body), 'the package itself must not be returned');
    assert.ok(!('metadata' in body), 'metadata must not be returned');
    assert.ok(!raw.includes('80.2707'), 'GPS longitude leaked into the public verdict');
    assert.ok(!raw.includes('13.0827'), 'GPS latitude leaked into the public verdict');
  });
});

test('GET /verify/:id for an unstored event is a 404', async () => {
  await withServer(async (base) => {
    const res = await fetch(`${base}/verify/99999999-9999-4999-8999-999999999999`);
    assert.strictEqual(res.status, 404);
  });
});

test('the location cross-check uses stored history from the same install', async () => {
  await withServer(async (base) => {
    const first = buildSignedPackage({
      eventId: '11111111-1111-4111-8111-111111111111',
      location: locationAt(...COORDS.chennai),
    });
    // One minute later, on the other side of the planet.
    const gap = 60000;
    const second = buildSignedPackage({
      eventId: '22222222-2222-4222-8222-222222222222',
      location: locationAt(...COORDS.newYork),
      timestamp: {
        wallClockMillis: first.pkg.metadata.timestamp.wallClockMillis + gap,
        elapsedRealtimeNanos: first.pkg.metadata.timestamp.elapsedRealtimeNanos + gap * 1000000,
        iso8601: new Date(first.pkg.metadata.timestamp.wallClockMillis + gap).toISOString(),
      },
    });

    await postJson(base, '/proof', first.pkg);
    await postBytes(base, `/proof/${first.pkg.eventId}/media`, first.media);
    await postJson(base, '/proof', second.pkg);
    await postBytes(base, `/proof/${second.pkg.eventId}/media`, second.media);

    // The first capture has nothing before it: unavailable, and still verified.
    const one = await (await fetch(`${base}/verify/${first.pkg.eventId}`)).json();
    assert.strictEqual(one.checks.locationPlausible, 'unavailable');
    assert.strictEqual(one.verdict, 'verified');

    // The second is impossible relative to the first, and the store is what made
    // that visible.
    const two = await (await fetch(`${base}/verify/${second.pkg.eventId}`)).json();
    assert.strictEqual(two.checks.locationPlausible, 'fail');
    assert.strictEqual(two.verdict, 'failed');
  });
});

// --- health ----------------------------------------------------------------

test('GET /health reports the live store driver and event count', async () => {
  await withServer(async (base) => {
    const { pkg } = buildSignedPackage();
    await postJson(base, '/proof', pkg);

    const body = await (await fetch(`${base}/health`)).json();
    assert.strictEqual(body.status, 'ok');
    assert.strictEqual(body.store.driver, 'memory');
    assert.strictEqual(body.store.events, 1);
  });
});

test('an oversized media upload is rejected rather than buffered', async () => {
  await withServer(async (base) => {
    const { pkg } = buildSignedPackage();
    await postJson(base, '/proof', pkg);

    // One byte over the configured ceiling.
    const tooBig = crypto.randomBytes(require('../src/config').store.maxMediaBytes + 1);
    const res = await postBytes(base, `/proof/${pkg.eventId}/media`, tooBig);

    assert.strictEqual(res.status, 413);
  });
});
