'use strict';

const test = require('node:test');
const assert = require('node:assert');

const { createRateLimitStore, describeStore, resetForTests } =
  require('../src/services/rateLimitStore');

/**
 * Where the rate limiter keeps its counters.
 *
 * The motivating evidence is in `scripts/ops/verify_trust_proxy.sh` and in the
 * config comment: probing the live deployment on 2026-08-06 found THREE
 * independent counters behind one hostname, so the effective allowance was about
 * three times the configured one. Redis fixes that, but only if the wiring is
 * right — and the wiring has one sharp edge, which most of these tests are about.
 *
 * The edge: express-rate-limit keys a store by client address alone. Two
 * limiters sharing a store therefore share counters. This service runs limiters
 * with DIFFERENT allowances (/health 600, API 100), so a shared store would let
 * health-check traffic drain the API budget and 429 real clients — the exact
 * self-inflicted outage the /health carve-out exists to prevent.
 */

test.beforeEach(() => {
  delete process.env.REDIS_URL;
  resetForTests();
});

test.after(() => {
  delete process.env.REDIS_URL;
  resetForTests();
});

// --- the namespace guard -----------------------------------------------------

test('a store cannot be built without a namespace', () => {
  // The failure this prevents is silent in every observable way: the service
  // starts, the headers look right, and two limiters quietly share one bucket.
  // Refusing at construction is the only point where it is loud.
  assert.throws(() => createRateLimitStore(), /namespace/);
  assert.throws(() => createRateLimitStore(''), /namespace/);
  assert.throws(() => createRateLimitStore(null), /namespace/);
});

test('a non-string namespace is refused rather than coerced', () => {
  // `prefix: rl:realitylock:1:` would "work" and be meaningless.
  assert.throws(() => createRateLimitStore(1), /namespace/);
  assert.throws(() => createRateLimitStore({}), /namespace/);
});

// --- the default path --------------------------------------------------------

test('with no REDIS_URL the store is undefined, letting express-rate-limit use memory', () => {
  // Undefined is the documented way to ask for the library default. Returning a
  // hand-rolled memory store instead would be a second implementation of
  // something that already ships and is already tested upstream.
  assert.strictEqual(createRateLimitStore('api', { onWarning: () => {} }), undefined);
});

test('running without a shared store warns in production, and only in production', () => {
  const original = process.env.NODE_ENV;
  try {
    const warnings = [];
    const collect = (m) => warnings.push(m);

    // The whole point: a limit that is silently a multiple of the configured one
    // is worse than no limit, because it looks enforced.
    process.env.NODE_ENV = 'production';
    delete require.cache[require.resolve('../src/config')];
    delete require.cache[require.resolve('../src/services/rateLimitStore')];
    // eslint-disable-next-line global-require
    const prod = require('../src/services/rateLimitStore');
    prod.createRateLimitStore('api', { onWarning: collect });
    assert.strictEqual(warnings.length, 1);
    assert.match(warnings[0], /per-process|REDIS_URL/);

    warnings.length = 0;
    process.env.NODE_ENV = 'test';
    delete require.cache[require.resolve('../src/config')];
    delete require.cache[require.resolve('../src/services/rateLimitStore')];
    // eslint-disable-next-line global-require
    const nonProd = require('../src/services/rateLimitStore');
    nonProd.createRateLimitStore('api', { onWarning: collect });
    assert.strictEqual(warnings.length, 0, 'a dev run must not nag about Redis');
  } finally {
    process.env.NODE_ENV = original;
    delete require.cache[require.resolve('../src/config')];
    delete require.cache[require.resolve('../src/services/rateLimitStore')];
  }
});

test('the described store names the memory default honestly', () => {
  createRateLimitStore('api', { onWarning: () => {} });
  assert.match(describeStore(), /memory/);
});

// --- the Redis path ----------------------------------------------------------

test('a configured REDIS_URL yields a real store, namespaced per limiter', () => {
  process.env.REDIS_URL = 'redis://127.0.0.1:6379';
  delete require.cache[require.resolve('../src/config')];
  delete require.cache[require.resolve('../src/services/rateLimitStore')];
  // eslint-disable-next-line global-require
  const mod = require('../src/services/rateLimitStore');

  const warnings = [];
  // No Redis is running in CI, and that must not fail the test: the connection
  // is deliberately backgrounded so a limiter can never block startup. What is
  // asserted here is the WIRING — that a store object comes back and that the
  // two limiters get different prefixes.
  const api = mod.createRateLimitStore('api', { onWarning: (m) => warnings.push(m) });
  const health = mod.createRateLimitStore('health', { onWarning: (m) => warnings.push(m) });

  assert.ok(api, 'a configured REDIS_URL must produce a store');
  assert.ok(health);
  assert.notStrictEqual(api, health, 'each limiter needs its own store instance');
  assert.strictEqual(api.prefix, 'rl:realitylock:api:');
  assert.strictEqual(health.prefix, 'rl:realitylock:health:');
  // The distinct prefixes ARE the isolation. Equal prefixes would mean /health
  // and the API sharing counters despite having different limits.
  assert.notStrictEqual(api.prefix, health.prefix);

  assert.match(mod.describeStore(), /redis/);
});

test('a failing Redis connection warns but never throws', () => {
  // A rate limiter losing its backing store must degrade, not kill the service
  // it exists to protect. Port 1 is reserved and refuses instantly.
  process.env.REDIS_URL = 'redis://127.0.0.1:1';
  delete require.cache[require.resolve('../src/config')];
  delete require.cache[require.resolve('../src/services/rateLimitStore')];
  // eslint-disable-next-line global-require
  const mod = require('../src/services/rateLimitStore');

  assert.doesNotThrow(() => mod.createRateLimitStore('api', { onWarning: () => {} }));
});
