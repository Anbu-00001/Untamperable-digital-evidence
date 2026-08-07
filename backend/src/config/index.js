'use strict';

const path = require('path');
require('dotenv').config();

/** Parse an integer env var with a default; throws on malformed input. */
function envInt(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = Number.parseInt(raw, 10);
  if (Number.isNaN(n)) {
    throw new Error(`Environment variable ${name} must be an integer, got "${raw}"`);
  }
  return n;
}

/**
 * Number of reverse proxies between the public internet and this process.
 *
 * Deliberately a COUNT, and never Express's `trust proxy: true`. Under `true`,
 * Express takes the LEFT-most `X-Forwarded-For` entry as the client address —
 * and that entry is written by the caller, so anyone can rotate it per request
 * and walk straight through an IP-based rate limiter. express-rate-limit refuses
 * that configuration by name (ERR_ERL_PERMISSIVE_TRUST_PROXY). A hop count makes
 * Express count in from the RIGHT instead, and only a real proxy can append
 * there.
 *
 * `envInt` already rejects the dangerous spelling: `TRUST_PROXY_HOPS=true`
 * parses to NaN and throws, rather than being coerced to Express's `true`.
 */
function envTrustProxyHops(name, fallback) {
  const hops = envInt(name, fallback);
  if (hops < 0) {
    throw new Error(`Environment variable ${name} must be zero or more, got "${hops}"`);
  }
  return hops;
}

/** Parse a comma-separated list env var with a default. */
function envList(name, fallback) {
  const raw = process.env[name];
  if (!raw) return fallback;
  return raw.split(',').map((s) => s.trim()).filter(Boolean);
}

/**
 * Reads a boolean switch.
 *
 * Only an explicit, recognised value flips the flag; anything else throws rather
 * than falling back. This guards a specific and nasty mistake: these flags gate
 * security controls, and `PROOF_READ_AUTH=flase` (or `no`, or `0 `) silently
 * read as "not the string true" would turn an access control OFF while looking
 * like it had been configured. A typo must stop the process, not open the door.
 */
function envFlag(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const normalised = String(raw).trim().toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(normalised)) return true;
  if (['0', 'false', 'no', 'off'].includes(normalised)) return false;
  throw new Error(
    `${name} must be one of true/false/1/0/yes/no/on/off, got "${raw}" — refusing to ` +
      'guess, because guessing wrong on a security switch fails open',
  );
}

// Repo root, so the backend can locate the shared schema in docs/ without a
// copy. __dirname here is <repo>/backend/src/config.
const repoRoot = path.resolve(__dirname, '..', '..', '..');

// Service identity comes from package.json — one declaration, not a literal
// repeated in the health payload.
const pkg = require('../../package.json');

// Canonical hash algorithm name, as written in the proof-package schema
// (`merkle.algorithm`) and mirrored on Android by CryptoConfig.HASH_ALGORITHM.
// Deliberately NOT env-overridable: changing it would silently invalidate every
// verification, so it is a contract constant rather than a deployment knob.
const HASH_ALGORITHM = 'SHA-256';

const config = {
  env: process.env.NODE_ENV || 'development',
  port: envInt('PORT', 3000),

  serviceName: pkg.name,

  // CORS allow-list. '*' is dev-only; production must set explicit origins.
  allowedOrigins: envList('ALLOWED_ORIGINS', ['*']),

  // Upper bound on an incoming proof-package JSON body (default 2 MiB).
  maxJsonBytes: envInt('MAX_JSON_BYTES', 2 * 1024 * 1024),

  // ------------------------------------------------------------------------
  // Abuse limits (Phase 6 hardening).
  //
  // This service has no authentication — anyone who finds the URL can submit or
  // verify — so per-IP rate limiting is the only control in front of it. That
  // makes `trustProxyHops` load-bearing: too permissive and the limiter is
  // trivially bypassed, too strict and every request looks like one client.
  //
  // Default 1: a single platform load balancer in front of the app, which is the
  // usual PaaS shape (Render included).
  //
  // VERIFIED against the live deployment on 2026-08-06, no longer assumed:
  // 20 requests to civicmesh.onrender.com each carrying a DIFFERENT forged
  // X-Forwarded-For kept draining one bucket (remaining 587 -> 573) instead of
  // opening 20 fresh ones. A caller-controlled key would have shown every
  // request at the window maximum. Re-run `scripts/ops/verify_trust_proxy.sh`
  // after any change to the hosting topology — this is a property of the
  // deployment, not of this file.
  // ------------------------------------------------------------------------
  trustProxyHops: envTrustProxyHops('TRUST_PROXY_HOPS', 1),

  rateLimit: {
    windowMs: envInt('RATE_LIMIT_WINDOW_MS', 15 * 60 * 1000),
    // Applies to /proof and /verify.
    limit: envInt('RATE_LIMIT_MAX', 100),
    // /health gets its own, far looser bucket. It is NOT exempt — it lists the
    // store on every call, so it is not free to serve — but the hosting
    // platform's own checker polls it, and answering that with 429 would mark
    // the service unhealthy and pull it out of rotation. The limit is set well
    // above any plausible health-check cadence.
    healthLimit: envInt('RATE_LIMIT_HEALTH_MAX', 600),

    // Where the limiter keeps its counters.
    //
    // Unset (the default) means express-rate-limit's in-process MemoryStore,
    // which is correct for exactly one process and wrong for any other number.
    // That is not hypothetical here: the same 2026-08-06 probe found the live
    // deployment answering from THREE independent counters (the 20 observations
    // split cleanly into three series each decrementing by exactly 1), so the
    // real allowance was roughly 3x the configured one. The spoofing check still
    // passed — this is a capacity bug, not an auth bypass — but a limit that is
    // silently triple what it says is not a limit anyone should rely on.
    //
    // Setting REDIS_URL moves the counters to one shared store and makes the
    // configured number the true number, whatever the process count.
    redisUrl: process.env.REDIS_URL || null,
  },

  // ------------------------------------------------------------------------
  // Proof-of-possession on the two endpoints that hand back a package's
  // contents: GET /proof/:eventId (carries GPS) and /proof/:eventId/media (the
  // photograph). See services/proofOfPossession.js for the scheme.
  //
  // Defaults to ON. Before this, the only control on either endpoint was
  // knowing a UUIDv4 — a capability that cannot be revoked and leaks the moment
  // it reaches a log or a screenshot. Turning it on breaks no first-party
  // client: the Android app only ever POSTs to /proof, and both e2e scripts
  // read verdicts from /verify, which stays public by design.
  // ------------------------------------------------------------------------
  proofReadAuth: {
    enabled: envFlag('PROOF_READ_AUTH', true),
    // How far a signed request's `created` may sit from the server clock. Wide
    // enough for an unsynchronised phone, narrow enough that a captured header
    // is useful only briefly — the nonce cache is per-process, so this window,
    // not the cache, is what actually bounds a replay.
    maxSkewSeconds: envInt('PROOF_READ_AUTH_SKEW_SECONDS', 300),
  },

  // ------------------------------------------------------------------------
  // RFC 3161 timestamp anchoring (Phase 7).
  //
  // Off by default, and that default is a deliberate one rather than caution:
  // enabling it makes the ingest path perform an outbound network call, and a
  // library importing this config — including every unit test — would otherwise
  // silently acquire a dependency on a third-party server being reachable.
  // `render.yaml` turns it on for the deployed service, where it belongs.
  // ------------------------------------------------------------------------
  timestampAnchor: {
    enabled: envFlag('TIMESTAMP_ANCHOR_ENABLED', false),

    // DigiCert: free, no account, no rate limit published, and the only one of
    // the five measured on 2026-08-07 that answered in under 700 ms. Its
    // responder chains to DigiCert Trusted Root G4, which is in Node's bundled
    // Mozilla set, so no certificate has to be shipped with this service.
    //
    // Overridable because a TSA is a single point of failure that this project
    // does not control, and swapping one should be an env change, not a deploy.
    tsaUrl: process.env.TIMESTAMP_ANCHOR_TSA_URL || 'http://timestamp.digicert.com',

    // Measured round-trips were 650 ms – 1.4 s. Five seconds is generous enough
    // to absorb a slow day and short enough that a hung TSA cannot hold an
    // ingest request open.
    timeoutMs: envInt('TIMESTAMP_ANCHOR_TIMEOUT_MS', 5000),

    // How far a capture's claimed wall clock may exceed the TSA's genTime before
    // it is called a contradiction rather than clock drift. A capture recorded
    // *after* an independent authority already saw its root is impossible, but
    // an unsynchronised phone can be minutes out, and calling ordinary drift
    // "impossible" would burn the check's credibility on a false positive.
    maxClockLeadSeconds: envInt('TIMESTAMP_ANCHOR_MAX_LEAD_SECONDS', 300),
  },

  // The proof-package schema is the single shared contract with the app. Read
  // from docs/ by default; overridable for tests/deployment layouts.
  proofSchemaPath:
    process.env.PROOF_SCHEMA_PATH ||
    path.join(repoRoot, 'docs', 'design', 'proof-package.schema.json'),

  // Reference package used by `npm run validate:schema`; same override story as
  // the schema path above, so neither is a literal inside a script.
  proofExamplePath:
    process.env.PROOF_EXAMPLE_PATH ||
    path.join(repoRoot, 'docs', 'design', 'examples', 'proof-package.example.json'),

  // ------------------------------------------------------------------------
  // Hardware key attestation (Phase 8).
  // ------------------------------------------------------------------------
  attestation: {
    // Pinned Google attestation roots. Deliberately a file on disk rather than a
    // fetch from https://android.googleapis.com/attestation/root at verify time:
    // these are TRUST ANCHORS, and one fetched over the network is only as
    // trustworthy as the fetch. Anyone able to answer for that host could supply
    // their own root and every forged chain would verify. See the header of the
    // file itself for provenance and how to update it.
    rootsPath:
      process.env.GOOGLE_ATTESTATION_ROOTS_PATH ||
      path.join(repoRoot, 'backend', 'data', 'google-attestation-roots.pem'),

    revocation: {
      // Google's published status list. Unlike the roots above this is NOT
      // pinned: revocation data is only useful when fresh, and a stale snapshot
      // fails open — it reports a since-revoked key as fine. Pinning would
      // guarantee that failure.
      url:
        process.env.ATTESTATION_REVOCATION_URL ||
        'https://android.googleapis.com/attestation/status',

      // Matches the endpoint's own `Cache-Control: public, max-age=86400`.
      refreshMillis: envInt('ATTESTATION_REVOCATION_REFRESH_MS', 24 * 60 * 60 * 1000),

      // How stale a snapshot may be before it stops being usable. Beyond this
      // the check reports `unavailable` rather than answering from data old
      // enough to be wrong — "we could not check" is a different claim from
      // "not revoked", and the two must never be collapsed.
      maxAgeMillis: envInt('ATTESTATION_REVOCATION_MAX_AGE_MS', 7 * 24 * 60 * 60 * 1000),

      // Off by default in tests so no suite reaches the network. Any other
      // environment that disables it gets `unavailable`, never a silent pass.
      enabled: (process.env.ATTESTATION_REVOCATION_ENABLED ??
        String(process.env.NODE_ENV !== 'test')) === 'true',
    },
  },

  // Shipped with every verdict so a consumer cannot present a `verified`
  // result as more than it is (research/02 §7, research/06 §7).
  verdictLimitations: [
    // Deliberately does NOT say "hardware-backed" here. That claim is only true
    // when the attestation checks pass, and a package from a device that could
    // not attest still verifies (ADR-0006 §5) — so hardware backing is reported
    // by `attestationPresent`/`attestationKeyBinding`, not asserted blanket.
    'Proves the media and metadata are unaltered since capture and were signed by one specific key held in the capturing device keystore.',
    // History of this line, because it has been wrong in both directions.
    //
    // It first read "hardware backing is established when the attestation checks
    // pass", which overstated things: nothing anchored the chain to Google, so a
    // self-issued chain reached the same verdict. It was then corrected to say
    // the chain "is not checked against Google's published roots" — true at the
    // time, and false since Phase 8, which pins those roots and checks them.
    //
    // This line has moved three times as the attestation checks were built out,
    // which is the point of keeping the history: each version was true when
    // written and became an overclaim or an underclaim as the code changed.
    //
    // `attestationNotRevoked` is named rather than assumed because it fails
    // OPEN: with no status list, a revoked certificate looks exactly like a
    // clean one, so its `unavailable` has to be read, not skipped.
    'Hardware backing is supported only when `attestationRootTrusted`, `attestationNotRevoked` and `attestationSecurityLevel` all pass — read them; revocation reports `unavailable` when the list could not be fetched, which is not the same as a clean result.',
    // Boot state is parsed and reported in the notes, but deliberately does not
    // gate the verdict: an unlocked bootloader describes the OS the device was
    // running, not where the key lives, and a genuine capture from such a device
    // is still a genuine capture. A reader who cares must read the note.
    'The device’s Verified Boot state and bootloader lock are reported in the notes but do NOT affect the verdict; a capture from a device with an unlocked bootloader can still verify.',
    'Does NOT prove the depicted event was real, unstaged, or correctly described.',
    'Not a standalone legal certificate; BSA 2023 s.63 requires human certification.',
  ],

  // HTTP mount points. These are the contract the Android client codes against
  // (Phase 5), so they are declared once here rather than inline in app.js.
  routes: {
    health: '/health',
    proof: '/proof',
    verify: '/verify',
  },

  crypto: {
    // Schema/Android-facing spelling.
    hashAlgorithm: HASH_ALGORITHM,
    // Node's crypto.createHash() identifier for the same algorithm, derived so
    // the two can never name different algorithms.
    nodeHashAlgorithm: HASH_ALGORITHM.toLowerCase().replace(/-/g, ''),
  },

  // ------------------------------------------------------------------------
  // Plausibility thresholds (verification checks 4 and 5, research/02 §8 Step 10).
  //
  // The location values MUST equal Android's IntegrityConfig, or the device's
  // advisory answer and the verifier's authoritative one would disagree for no
  // reason but drift. A cross-implementation test vector asserts the pair stay
  // identical, exactly as the Merkle vector does for the crypto core.
  // ------------------------------------------------------------------------
  plausibility: {
    // Mean Earth radius for the Haversine great-circle distance (metres).
    earthRadiusMeters: 6371000,
    // Implied speed above this between consecutive events is "teleportation".
    // 1500 km/h, NOT the plan's 300: high-speed rail already runs ~300 km/h and
    // jet-stream-boosted flights reach ~1300 km/h ground speed (ADR-0005).
    maxPlausibleSpeedKmh: 1500,
    // Below these gaps a speed is not computed at all: dividing GNSS scatter by
    // a near-zero interval manufactures a phantom huge speed.
    minElapsedMillisForSpeed: 1000,
    minDistanceMetersForSpeed: 50,

    // How far ahead of the verifier's own clock a capture may claim to be
    // before it is implausible. An NTP-synced device lands within seconds; this
    // allows for an unsynchronised clock without admitting a forged future
    // date. Overridable because a deployment's clock discipline is a deployment
    // property, not a contract constant.
    maxFutureSkewMillis: envInt('MAX_FUTURE_SKEW_MILLIS', 5 * 60 * 1000),

    // Tolerance for `gpsTimeMillis` vs the wall clock, after accounting for the
    // fix age. ADVISORY ONLY — see plausibility.js: a fused/network provider
    // often derives getTime() from the very system clock being checked, so
    // agreement proves little and only a gross divergence is informative.
    gpsTimeToleranceMillis: envInt('GPS_TIME_TOLERANCE_MILLIS', 5 * 60 * 1000),
  },

  // ------------------------------------------------------------------------
  // Persistence (Phase 5). Proof packages and media are stored immutably and
  // addressed by content; see ADR-0006 for why media does NOT go to Firebase
  // Cloud Storage (it requires a billing account as of 2026-02-03).
  // ------------------------------------------------------------------------
  store: {
    // 'filesystem' (default) or 'memory' (tests). A Firestore adapter is a
    // documented, config-gated option — see ADR-0006 §1 and SETUP.md.
    driver: process.env.STORE_DRIVER || 'filesystem',
    // Where the filesystem driver keeps its immutable objects. Defaults inside
    // the backend directory so a bare `npm start` works with no setup.
    dataDir: process.env.STORE_DATA_DIR || path.join(repoRoot, 'backend', '.data'),
    // Upper bound on an uploaded media object (default 32 MiB). Media arrives as
    // a raw octet-stream body rather than base64, so this is the true byte size.
    maxMediaBytes: envInt('MAX_MEDIA_BYTES', 32 * 1024 * 1024),
  },

  // Firebase: Firestore only, and only if a project is configured. Cloud Storage
  // is deliberately absent — it has required a linked billing account since
  // 2026-02-03, whereas Firestore stays free on the Spark plan. Media therefore
  // lives in the content-addressed store above. See ADR-0006 §1.
  firebase: {
    projectId: process.env.FIREBASE_PROJECT_ID || null,
  },
};

module.exports = config;
