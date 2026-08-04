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
  // usual PaaS shape (Render included). VERIFY IT against the actual deployment
  // rather than trusting this default — if the hosting provider adds a hop, the
  // limiter starts keying on the proxy instead of the caller.
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
    // What remains true is narrower than "hardware-backed", and is what this now
    // says: anchoring is real but partial. Certificate revocation is not
    // consulted (Google publishes a status list keyed by serial), and the
    // attestation extension itself is not parsed, so the security level the
    // device claims — TrustedEnvironment vs StrongBox — is never verified.
    'Hardware backing is supported only when `attestationRootTrusted` passes, and even then it is partial: certificate revocation is not checked, and the attestation extension’s declared security level is not parsed.',
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
