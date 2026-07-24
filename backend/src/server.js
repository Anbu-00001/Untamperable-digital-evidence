'use strict';

const { createApp } = require('./app');
const config = require('./config');
const { loadValidator } = require('./services/proofSchema');

// Fail fast if the shared schema can't be loaded/compiled at boot.
try {
  loadValidator();
} catch (err) {
  console.error('[reality-lock] Failed to load proof-package schema:', err.message);
  console.error(
    `[reality-lock] Looked in: ${config.proofSchemaPath}\n` +
      '  The schema lives in docs/design/, OUTSIDE backend/, because it is the\n' +
      '  contract shared with the Android app and is never duplicated. If this is\n' +
      '  a deployment, make sure docs/design/ ships too (see Dockerfile /\n' +
      '  render.yaml at the repo root), or set PROOF_SCHEMA_PATH to its location.'
  );
  process.exit(1);
}

const app = createApp();
const server = app.listen(config.port, () => {
  console.log(`[reality-lock] backend listening on :${config.port} (env=${config.env})`);
});

function shutdown(signal) {
  console.log(`[reality-lock] ${signal} received, shutting down`);
  server.close(() => process.exit(0));
}

['SIGINT', 'SIGTERM'].forEach((sig) => process.on(sig, () => shutdown(sig)));

module.exports = server;
