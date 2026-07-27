'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { createStore, ConflictError, isSafeEventId } = require('../src/store');
const { selectPreviousPackage, hasLocation } = require('../src/store/support');

/**
 * Every behavioural test runs against BOTH drivers. A memory store that were
 * more permissive than the filesystem one would let a real immutability
 * violation pass CI while the suite stayed green.
 */
function eachDriver(name, body) {
  test(`[memory] ${name}`, () => body(createStore('memory')));
  test(`[filesystem] ${name}`, () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rl-store-'));
    try {
      body(createStore('filesystem', { dataDir: dir }));
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
}

/** Minimal package shaped enough for the store (the routes validate the schema). */
function pkgFixture(eventId, { installId = 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa', wallClockMillis = 1000, sha256 = 'a'.repeat(64), location = null } = {}) {
  return {
    eventId,
    media: { mimeType: 'image/jpeg', byteLength: 3, sha256 },
    metadata: {
      location,
      timestamp: { wallClockMillis, iso8601: new Date(wallClockMillis).toISOString(), elapsedRealtimeNanos: 0 },
      motion: null,
      device: { installId, model: 'CPH2591', manufacturer: 'OnePlus', sdkInt: 35, appVersionName: '0.1.0', appVersionCode: 1 },
    },
  };
}

const ID_A = '11111111-1111-4111-8111-111111111111';
const ID_B = '22222222-2222-4222-8222-222222222222';

eachDriver('stores a package and reads it back', (store) => {
  const pkg = pkgFixture(ID_A);
  const result = store.putPackage(pkg);

  assert.strictEqual(result.created, true);
  assert.deepStrictEqual(store.getPackage(ID_A), pkg);
  assert.deepStrictEqual(store.listEventIds(), [ID_A]);
});

eachDriver('an identical resubmission is idempotent, not an error', (store) => {
  const pkg = pkgFixture(ID_A);
  assert.strictEqual(store.putPackage(pkg).created, true);

  // This is the sync worker retrying after a dropped connection: it cannot know
  // whether the first attempt landed, so a repeat must succeed.
  const again = store.putPackage(pkgFixture(ID_A));
  assert.strictEqual(again.created, false);
});

eachDriver('rewriting a stored package is refused', (store) => {
  store.putPackage(pkgFixture(ID_A, { wallClockMillis: 1000 }));

  assert.throws(
    () => store.putPackage(pkgFixture(ID_A, { wallClockMillis: 9999 })),
    ConflictError,
    'stored evidence must be immutable',
  );
  // And the original survives untouched.
  assert.strictEqual(store.getPackage(ID_A).metadata.timestamp.wallClockMillis, 1000);
});

eachDriver('key order alone does not make a package "different"', (store) => {
  const pkg = pkgFixture(ID_A);
  store.putPackage(pkg);

  // Same document, keys enumerated in another order — as a JSON round-trip
  // through a different implementation may well produce.
  const reordered = { media: pkg.media, metadata: pkg.metadata, eventId: pkg.eventId };
  assert.strictEqual(store.putPackage(reordered).created, false);
});

eachDriver('media is stored under its own digest and read back', (store) => {
  const bytes = Buffer.from('the media bytes');
  const digest = require('crypto').createHash('sha256').update(bytes).digest('hex');
  store.putPackage(pkgFixture(ID_A, { sha256: digest }));

  const { storageRef } = store.putMedia(ID_A, bytes, digest);

  assert.strictEqual(storageRef, `sha256:${digest}`);
  assert.deepStrictEqual(store.getMedia(ID_A), bytes);
});

eachDriver('media for an unknown package is refused', (store) => {
  assert.throws(() => store.putMedia(ID_A, Buffer.from('x'), 'b'.repeat(64)), /no stored package/);
});

eachDriver('a package with no media reports null rather than empty bytes', (store) => {
  store.putPackage(pkgFixture(ID_A));
  assert.strictEqual(store.getMedia(ID_A), null);
});

eachDriver('an unknown event reads as null, not an error', (store) => {
  assert.strictEqual(store.getPackage(ID_B), null);
});

// --- history selection, which the location cross-check depends on -----------

eachDriver('finds the most recent earlier capture from the same install', (store) => {
  store.putPackage(pkgFixture(ID_A, { wallClockMillis: 1000 }));
  store.putPackage(pkgFixture(ID_B, { wallClockMillis: 2000 }));

  const previous = store.findPreviousPackage('aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa', 3000);
  assert.strictEqual(previous.eventId, ID_B, 'the nearest earlier event, not the oldest');

  // Strictly earlier: an event at exactly the cut-off is not its own predecessor.
  assert.strictEqual(
    store.findPreviousPackage('aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa', 1000),
    null,
  );
});

eachDriver('history from a different install is not comparable', (store) => {
  store.putPackage(pkgFixture(ID_A, { installId: 'bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb' }));

  // Two different devices being in two different places says nothing about
  // whether either of them teleported.
  assert.strictEqual(
    store.findPreviousPackage('aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa', 5000),
    null,
  );
});

eachDriver('a predicate skips predecessors that cannot answer the question', (store) => {
  // The attack this closes: the location cross-check compares against "the most
  // recent earlier capture". If that capture has no location, the check reports
  // `unavailable` — so making one capture with location switched off, immediately
  // before a spoofed one, suppressed the implied-speed check entirely while a
  // perfectly comparable located capture sat one position further back.
  const located = { latitude: 13.0827, longitude: 80.2707, accuracyMeters: 5, isMock: false };
  store.putPackage(pkgFixture(ID_A, { wallClockMillis: 1000, location: located }));
  store.putPackage(pkgFixture(ID_B, { wallClockMillis: 2000, location: null }));

  const install = 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa';

  // Unfiltered, the nearest predecessor is the location-less one.
  assert.strictEqual(store.findPreviousPackage(install, 3000).eventId, ID_B);

  // Filtered, the scan skips past it to the most recent one that CAN be compared.
  // A longer interval is not a problem: elapsed time is an input to the
  // implied-speed arithmetic, so a bigger gap simply permits more travel.
  assert.strictEqual(store.findPreviousPackage(install, 3000, hasLocation).eventId, ID_A);
});

test('hasLocation treats malformed records as not comparable', () => {
  assert.strictEqual(hasLocation(null), false);
  assert.strictEqual(hasLocation({}), false);
  assert.strictEqual(hasLocation({ metadata: {} }), false);
  assert.strictEqual(hasLocation({ metadata: { location: null } }), false);
  assert.strictEqual(hasLocation({ metadata: { location: { latitude: 1 } } }), true);
});

test('selectPreviousPackage ignores malformed entries instead of throwing', () => {
  // One unreadable record must not take down a whole history scan.
  const good = pkgFixture(ID_A, { wallClockMillis: 500 });
  assert.strictEqual(selectPreviousPackage([null, {}, good], good.metadata.device.installId, 900).eventId, ID_A);
});

// --- path-traversal guard ---------------------------------------------------

test('only UUID-shaped event ids are accepted as filenames', () => {
  assert.ok(isSafeEventId(ID_A));
  assert.ok(!isSafeEventId('../../etc/passwd'));
  assert.ok(!isSafeEventId('not-a-uuid'));
  assert.ok(!isSafeEventId(''));
  assert.ok(!isSafeEventId(undefined));
});

test('an unknown driver name is rejected loudly', () => {
  assert.throws(() => createStore('firestore'), /Unknown STORE_DRIVER/);
});

test('filesystem media is de-duplicated by content across events', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rl-store-'));
  try {
    const store = createStore('filesystem', { dataDir: dir });
    const bytes = Buffer.from('shared bytes');
    const digest = require('crypto').createHash('sha256').update(bytes).digest('hex');

    store.putPackage(pkgFixture(ID_A, { sha256: digest }));
    store.putPackage(pkgFixture(ID_B, { sha256: digest }));
    store.putMedia(ID_A, bytes, digest);
    store.putMedia(ID_B, bytes, digest);

    // Content-addressed: one object on disk, reachable from both events.
    assert.deepStrictEqual(fs.readdirSync(path.join(dir, 'media')), [digest]);
    assert.deepStrictEqual(store.getMedia(ID_A), bytes);
    assert.deepStrictEqual(store.getMedia(ID_B), bytes);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('filesystem packages survive a new store over the same directory', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rl-store-'));
  try {
    createStore('filesystem', { dataDir: dir }).putPackage(pkgFixture(ID_A));
    // A restarted process must see what the previous one stored.
    assert.ok(createStore('filesystem', { dataDir: dir }).getPackage(ID_A));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
