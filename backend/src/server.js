'use strict';

const { createApp } = require('./app');
const config = require('./config');
const { loadValidator } = require('./services/proofSchema');
const { loadRoots } = require('./services/attestationRoots');
const revocation = require('./services/attestationRevocation');

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

// Fail fast on the pinned attestation roots too. A server that answers requests
// while unable to read its own trust anchors would report every device's chain as
// unanchored — which reads as "these devices are suspect" rather than "this
// deployment is broken".
try {
  const roots = loadRoots();
  console.log(`[reality-lock] ${roots.length} pinned Google attestation root(s) loaded`);
} catch (err) {
  console.error('[reality-lock] Failed to load attestation roots:', err.message);
  console.error(
    `[reality-lock] Looked in: ${config.attestation.rootsPath}\n` +
      '  These are pinned trust anchors, committed under backend/data/. Set\n' +
      '  GOOGLE_ATTESTATION_ROOTS_PATH if this deployment keeps them elsewhere.'
  );
  process.exit(1);
}

// Revocation is fetched, not pinned, and deliberately does NOT fail fast: the
// list is only useful fresh, and refusing to boot without it would take the whole
// service down for a transient network fault. Verification reports
// `attestationNotRevoked: unavailable` until a snapshot arrives, which is honest
// rather than silently reassuring.
const revocationStarted = revocation.startBackgroundRefresh();
if (revocationStarted) {
  revocationStarted.then((result) => {
    console.log(
      result.ok
        ? `[reality-lock] revocation list loaded (${result.count} entries)`
        : `[reality-lock] revocation list unavailable (${result.reason}); ` +
          'reports will say the check did not run'
    );
  });
} else {
  console.log('[reality-lock] revocation checking is disabled for this environment');
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
