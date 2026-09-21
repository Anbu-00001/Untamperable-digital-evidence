'use strict';

const test = require('node:test');
const assert = require('node:assert');

/**
 * Security switches must parse strictly. Loads a fresh copy of the config with
 * [env] applied, restoring the process environment afterwards.
 */
function loadConfigWith(env) {
  const saved = { ...process.env };
  Object.assign(process.env, env);
  const modulePath = require.resolve('../src/config');
  delete require.cache[modulePath];
  try {
    return require('../src/config');
  } finally {
    process.env = saved;
    delete require.cache[modulePath];
  }
}

test('ATTESTATION_REVOCATION_ENABLED accepts every spelling envFlag accepts', () => {
  // Regression: this flag used to compare against the literal 'true', so `1`
  // and `yes` quietly turned revocation checking OFF.
  for (const on of ['true', 'TRUE', '1', 'yes', 'on']) {
    const config = loadConfigWith({ ATTESTATION_REVOCATION_ENABLED: on });
    assert.strictEqual(config.attestation.revocation.enabled, true, `"${on}" should enable`);
  }
  for (const off of ['false', '0', 'no', 'off']) {
    const config = loadConfigWith({ ATTESTATION_REVOCATION_ENABLED: off });
    assert.strictEqual(config.attestation.revocation.enabled, false, `"${off}" should disable`);
  }
});

test('ATTESTATION_REVOCATION_ENABLED refuses a typo instead of failing open', () => {
  assert.throws(
    () => loadConfigWith({ ATTESTATION_REVOCATION_ENABLED: 'flase' }),
    /ATTESTATION_REVOCATION_ENABLED must be one of/,
  );
});

test('revocation defaults to off under NODE_ENV=test and on elsewhere', () => {
  const inTests = loadConfigWith({ NODE_ENV: 'test', ATTESTATION_REVOCATION_ENABLED: '' });
  assert.strictEqual(inTests.attestation.revocation.enabled, false);
  const inProd = loadConfigWith({ NODE_ENV: 'production', ATTESTATION_REVOCATION_ENABLED: '' });
  assert.strictEqual(inProd.attestation.revocation.enabled, true);
});
