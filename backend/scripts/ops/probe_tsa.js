#!/usr/bin/env node
'use strict';

/**
 * Probes public RFC 3161 timestamp authorities and reports which are usable.
 *
 * Run by hand, never in CI. Every assertion it makes depends on a third party
 * being up, so as a test it would fail for reasons that say nothing about this
 * repository. The unit suite covers the same verification logic against stored
 * real tokens (test/fixtures/timestamp/); this script is what you run when
 * choosing or replacing `TIMESTAMP_ANCHOR_TSA_URL`.
 *
 *   node backend/scripts/ops/probe_tsa.js
 *   node backend/scripts/ops/probe_tsa.js http://timestamp.example.com
 *
 * A TSA is only usable here if it (a) answers, (b) returns a token over the
 * exact digest requested, (c) embeds its certificate chain, and (d) chains to a
 * root Node already trusts. Any of those failing makes it unsuitable regardless
 * of how well known the brand is.
 */

const nodeCrypto = require('node:crypto');
const { requestTimestamp } = require('../../src/services/timestampAnchor');

// Measured working on 2026-08-07. Kept as a list rather than one URL because the
// point of the exercise is comparison.
const DEFAULT_CANDIDATES = [
  'http://timestamp.digicert.com',
  'http://time.certum.pl',
  'http://timestamp.globalsign.com/tsa/r6advanced1',
  'http://ts.ssl.com',
  'https://rfc3161.ai.moda',
  'https://freetsa.org/tsr',
  'http://timestamp.sectigo.com',
  'http://timestamp.acs.microsoft.com',
];

async function main() {
  const candidates = process.argv.slice(2);
  const urls = candidates.length > 0 ? candidates : DEFAULT_CANDIDATES;

  // A digest of something, so the probe is indistinguishable from real traffic
  // and cannot accidentally reuse a stored root.
  const root = nodeCrypto.createHash('sha256').update(nodeCrypto.randomBytes(32)).digest('hex');
  console.log(`probing ${urls.length} authorities with root ${root}\n`);

  const rows = [];
  for (const tsaUrl of urls) {
    const started = Date.now();
    try {
      const anchor = await requestTimestamp(root, { tsaUrl, timeoutMs: 15000 });
      rows.push({
        tsaUrl,
        ok: true,
        ms: Date.now() - started,
        genTime: anchor.genTime,
        policy: anchor.policy,
        accuracySeconds: anchor.accuracySeconds,
      });
    } catch (err) {
      rows.push({ tsaUrl, ok: false, ms: Date.now() - started, why: err.message });
    }
  }

  for (const r of rows) {
    const status = r.ok ? 'OK  ' : 'FAIL';
    const detail = r.ok ? `${r.genTime}  policy=${r.policy}` : r.why;
    console.log(`${status} ${String(r.ms).padStart(6)}ms  ${r.tsaUrl.padEnd(48)} ${detail}`);
  }

  const usable = rows.filter((r) => r.ok);
  console.log(`\n${usable.length}/${rows.length} usable.`);
  if (usable.length === 0) {
    console.error('No authority answered. Check outbound network before changing config.');
    process.exitCode = 1;
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
