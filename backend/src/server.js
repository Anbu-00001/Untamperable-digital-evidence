'use strict';

const { createApp } = require('./app');
const config = require('./config');
const { loadValidator } = require('./services/proofSchema');

// Fail fast if the shared schema can't be loaded/compiled at boot.
try {
  loadValidator();
} catch (err) {
  console.error('[reality-lock] Failed to load proof-package schema:', err.message);
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
