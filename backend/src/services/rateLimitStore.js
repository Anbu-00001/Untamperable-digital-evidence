'use strict';

const config = require('../config');

/**
 * Chooses where the rate limiter keeps its counters.
 *
 * The default — express-rate-limit's in-process MemoryStore — is correct for
 * exactly one process. A probe of the live deployment on 2026-08-06 found three
 * independent counters answering the same hostname, meaning the effective
 * allowance was about three times the configured one. See the note on
 * `rateLimit.redisUrl` in config, and `scripts/ops/verify_trust_proxy.sh`, which
 * reports the counter count as a side effect of its main check.
 *
 * Redis is opt-in rather than required because the project's whole roadmap is
 * costed at zero (ADR-0004 "Consequences"), and a hard Redis dependency would
 * mean the service could not start on the free tier it is deployed to. So the
 * shape here is: use a shared store when one is configured, and be LOUD about
 * running without one rather than pretending the limit is exact.
 */

/** Set once so a caller can report which store is live without guessing. */
let describedAs = 'memory (per-process)';

/** Give up reconnecting after this many tries; see the socket options below. */
const MAX_RECONNECT_ATTEMPTS = 5;

/** So the memory-store warning is emitted once per process, not per limiter. */
let warnedAboutMemoryStore = false;

function describeStore() {
  return describedAs;
}

/** One shared client for the process; each limiter still gets its own store. */
let sharedClient = null;

/**
 * Builds the store express-rate-limit should use for ONE limiter, or `undefined`
 * to let it fall back to its own MemoryStore.
 *
 * `namespace` is required and must differ per limiter. This is not decoration:
 * express-rate-limit keys a store by client address alone, so two limiters
 * sharing a store also share counters — and since /health is allowed 600 while
 * /proof is allowed 100, a shared counter would let health-check traffic consume
 * the API budget and 429 real clients. The memory path gets this isolation for
 * free (every limiter constructs its own MemoryStore); Redis does not, so the
 * namespace becomes the key prefix.
 *
 * Returns `undefined` rather than throwing when Redis is not configured: an
 * unset REDIS_URL is a deployment choice, not an error.
 */
function createRateLimitStore(namespace, { onWarning = console.warn } = {}) {
  if (!namespace || typeof namespace !== 'string') {
    throw new Error(
      'createRateLimitStore needs a namespace — two limiters sharing one Redis ' +
        'prefix would share counters, and they have different limits',
    );
  }
  const url = config.rateLimit.redisUrl;

  if (!url) {
    describedAs = 'memory (per-process)';
    // Deliberately a warning on every boot in production, not a one-line info.
    // The failure mode this guards against is invisible: nothing errors, the
    // headers look right, and the limit is quietly a multiple of what was set.
    //
    // Once per process, not once per limiter: this is called for each limiter
    // mounted, and repeating an identical warning trains readers to skip it.
    if (config.env === 'production' && !warnedAboutMemoryStore) {
      warnedAboutMemoryStore = true;
      onWarning(
        '[reality-lock] rate limiter is using per-process memory counters. ' +
          'If this deployment runs more than one process, the effective limit is ' +
          'that multiple of RATE_LIMIT_MAX. Set REDIS_URL for an exact shared limit.',
      );
    }
    return undefined;
  }

  // Required lazily so a deployment with no REDIS_URL never loads the client at
  // all — it stays an optional dependency in practice, not just in intent.
  // eslint-disable-next-line global-require
  const { createClient } = require('redis');
  // eslint-disable-next-line global-require
  const { RedisStore } = require('rate-limit-redis');

  if (!sharedClient) {
    sharedClient = createClient({
      url,
      socket: {
        // node-redis retries forever by default. For a server that is usually
        // the right call, but an unreachable Redis then keeps a timer pending
        // indefinitely, which holds the event loop open and stops the process
        // exiting — it turned a failed connection into a hung shutdown. Bounded
        // retries keep the degrade-don't-die behaviour without that.
        reconnectStrategy: (retries) =>
          (retries > MAX_RECONNECT_ATTEMPTS ? false : Math.min(retries * 200, 2000)),
      },
    });
    // The counters are not worth keeping the process alive for. If nothing else
    // has work pending, Node should be free to exit.
    if (typeof sharedClient.unref === 'function') {
      sharedClient.unref();
    }

    // node-redis emits 'error' asynchronously; with no listener an EventEmitter
    // error becomes an unhandled exception and takes the process down. A rate
    // limiter losing its backing store must degrade, never kill the service it
    // is protecting.
    sharedClient.on('error', (err) => {
      onWarning(`[reality-lock] redis rate-limit store error: ${err.message}`);
    });

    // Connect in the background. express-rate-limit calls the store per request;
    // if Redis is not up yet those calls reject, which the limiter surfaces as
    // an error rather than a silent allow — the safe direction.
    sharedClient.connect().catch((err) => {
      onWarning(`[reality-lock] could not connect to REDIS_URL: ${err.message}`);
    });
  }
  const client = sharedClient;

  describedAs = 'redis (shared)';
  return new RedisStore({
    // node-redis v4+ exposes sendCommand; rate-limit-redis calls it with an
    // argument array.
    sendCommand: (...args) => client.sendCommand(args),
    // Namespaced twice over: once for this service, so counters cannot collide
    // with anything else sharing the Redis instance, and once for this specific
    // limiter, so limiters with different allowances keep separate buckets.
    prefix: `rl:realitylock:${namespace}:`,
  });
}

/**
 * Releases the Redis connection, if one was opened.
 *
 * Called on shutdown so a terminating process closes its socket rather than
 * leaving the server to time it out. Never throws: this runs on the way out, and
 * a failure to hang up cleanly must not mask whatever prompted the shutdown.
 */
async function closeRateLimitStore() {
  const client = sharedClient;
  sharedClient = null;
  if (!client) return;
  try {
    // destroy() is immediate; quit() waits for in-flight replies we do not care
    // about at this point.
    if (typeof client.destroy === 'function') client.destroy();
    else if (typeof client.disconnect === 'function') await client.disconnect();
  } catch {
    // Already closed, or never connected. Either way there is nothing to do.
  }
}

/** Test seam: drops the memoised client so a suite can re-exercise the wiring. */
function resetForTests() {
  const client = sharedClient;
  sharedClient = null;
  describedAs = 'memory (per-process)';
  warnedAboutMemoryStore = false;
  if (client) {
    try {
      if (typeof client.destroy === 'function') client.destroy();
      else if (typeof client.disconnect === 'function') client.disconnect();
    } catch {
      /* not connected */
    }
  }
}

module.exports = {
  createRateLimitStore,
  describeStore,
  closeRateLimitStore,
  resetForTests,
};
