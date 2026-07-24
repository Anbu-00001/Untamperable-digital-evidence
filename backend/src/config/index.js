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

  // Placeholder status returned by checks whose implementation lands in Phase 5.
  // Repeated across the /proof and /verify payloads, so it is declared once.
  notImplementedStatus: 'not_implemented_phase_5',

  // Firebase wiring lands in Phase 5; null until configured.
  firebase: {
    projectId: process.env.FIREBASE_PROJECT_ID || null,
    storageBucket: process.env.FIREBASE_STORAGE_BUCKET || null,
  },
};

module.exports = config;
