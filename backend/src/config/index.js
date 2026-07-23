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

const config = {
  env: process.env.NODE_ENV || 'development',
  port: envInt('PORT', 3000),

  // CORS allow-list. '*' is dev-only; production must set explicit origins.
  allowedOrigins: envList('ALLOWED_ORIGINS', ['*']),

  // Upper bound on an incoming proof-package JSON body (default 2 MiB).
  maxJsonBytes: envInt('MAX_JSON_BYTES', 2 * 1024 * 1024),

  // The proof-package schema is the single shared contract with the app. Read
  // from docs/ by default; overridable for tests/deployment layouts.
  proofSchemaPath:
    process.env.PROOF_SCHEMA_PATH ||
    path.join(repoRoot, 'docs', 'design', 'proof-package.schema.json'),

  // Firebase wiring lands in Phase 5; null until configured.
  firebase: {
    projectId: process.env.FIREBASE_PROJECT_ID || null,
    storageBucket: process.env.FIREBASE_STORAGE_BUCKET || null,
  },
};

module.exports = config;
