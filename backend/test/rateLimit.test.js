'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { execFileSync } = require('node:child_process');
const path = require('node:path');

const { createApp } = require('../src/app');
const { createStore, setSharedStore } = require('../src/store');

/**
 * Per-IP rate limiting (Phase 6 hardening).
 *
 * The service has no authentication, so this is the only thing standing between
 * the deployment and anyone who finds the URL. Two properties matter beyond
 * "it returns 429 eventually":
 *
 *  - /proof and /verify must share ONE bucket. Mounting a separate limiter per
 *    route silently grants a caller the full allowance on each.
 *  - /health must NOT share that bucket. The hosting platform polls it, and
 *    answering the health checker with 429 would pull the service out of
 *    rotation — hardening that causes the outage it was meant to prevent.
 *
 * Limits are passed per-instance so a few requests reach the threshold; the
 * middleware wiring under test is the same wiring that ships.
 */
async function withServer(rateLimitOverrides, body) {
  setSharedStore(createStore('memory'));
  const app = createApp({ rateLimit: { windowMs: 60_000, ...rateLimitOverrides } });
  const server = app.listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await body(base);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

/** Status codes for n sequential GETs, so the 429 boundary is visible. */
async function statuses(base, path, n) {
  const out = [];
  for (let i = 0; i < n; i += 1) {
    out.push((await fetch(base + path)).status);
  }
  return out;
}

test('requests are allowed up to the limit and refused past it', async () => {
  await withServer({ limit: 3 }, async (base) => {
    const codes = await statuses(base, '/verify', 4);

    assert.ok(
      codes.slice(0, 3).every((c) => c !== 429),
      `the first three requests should not be limited, got ${codes}`,
    );
    assert.strictEqual(codes[3], 429, `the fourth should be limited, got ${codes}`);
  });
});

test('a refusal uses the same JSON envelope as every other error', async () => {
  await withServer({ limit: 1 }, async (base) => {
    await fetch(base + '/verify');
    const response = await fetch(base + '/verify');

    assert.strictEqual(response.status, 429);
    // A client parsing the documented { error, message } shape must not get a
    // bare string back only for this one failure mode.
    const body = await response.json();
    assert.strictEqual(body.error, 'rate_limited');
    assert.ok(typeof body.message === 'string' && body.message.length > 0);
  });
});

test('/proof and /verify draw from one shared bucket, not one each', async () => {
  await withServer({ limit: 2 }, async (base) => {
    const first = await fetch(base + '/proof');
    const second = await fetch(base + '/verify');
    assert.ok(first.status !== 429 && second.status !== 429, 'the allowance was spent early');

    // Two requests have now been made against a limit of two. If each route kept
    // its own counter this would still be allowed, and the effective limit would
    // be double what the configuration says.
    const third = await fetch(base + '/proof');
    assert.strictEqual(third.status, 429, 'the two API routes did not share a bucket');
  });
});

test('/health survives an exhausted API bucket', async () => {
  await withServer({ limit: 1, healthLimit: 50 }, async (base) => {
    // Spend the API allowance.
    await fetch(base + '/verify');
    assert.strictEqual((await fetch(base + '/verify')).status, 429);

    // The platform's health checker must still get a real answer, or the
    // service is marked unhealthy and taken down.
    const health = await fetch(base + '/health');
    assert.strictEqual(health.status, 200);
    assert.strictEqual((await health.json()).status, 'ok');
  });
});

test('/health is limited too, just far more loosely', async () => {
  // Not exempt: it lists the store on every call, so it is not free to serve.
  await withServer({ limit: 100, healthLimit: 2 }, async (base) => {
    const codes = await statuses(base, '/health', 3);

    assert.deepStrictEqual(
      codes.map((c) => c === 429),
      [false, false, true],
      `expected the third health call to be limited, got ${codes}`,
    );
  });
});

test('TRUST_PROXY_HOPS=true is refused rather than coerced', () => {
  // Express's `trust proxy: true` takes the LEFT-most X-Forwarded-For entry as
  // the client address. That entry is written by the caller, so under `true` an
  // attacker rotates it per request and the IP limiter above stops meaning
  // anything. The config must reject that spelling loudly instead of accepting
  // a truthy value.
  const loadConfig = path.join(__dirname, '..', 'src', 'config', 'index.js');

  assert.throws(
    () =>
      execFileSync(process.execPath, ['-e', `require(${JSON.stringify(loadConfig)})`], {
        env: { ...process.env, TRUST_PROXY_HOPS: 'true' },
        stdio: 'pipe',
      }),
    /must be an integer/,
    'TRUST_PROXY_HOPS=true was accepted',
  );
});

test('a negative proxy hop count is refused', () => {
  const loadConfig = path.join(__dirname, '..', 'src', 'config', 'index.js');

  assert.throws(
    () =>
      execFileSync(process.execPath, ['-e', `require(${JSON.stringify(loadConfig)})`], {
        env: { ...process.env, TRUST_PROXY_HOPS: '-1' },
        stdio: 'pipe',
      }),
    /zero or more/,
  );
});
