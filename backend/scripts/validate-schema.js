'use strict';

// Standalone verification: does the example proof package validate against the
// shared schema? Run with `npm run validate:schema`. Exits non-zero on failure
// so it can gate CI later.

const fs = require('fs');
const config = require('../src/config');
const { loadValidator } = require('../src/services/proofSchema');

const { validate } = loadValidator();
const example = JSON.parse(fs.readFileSync(config.proofExamplePath, 'utf8'));

if (!validate(example)) {
  console.error('FAIL: example proof package does NOT validate against the schema:');
  console.error(JSON.stringify(validate.errors, null, 2));
  process.exit(1);
}

console.log('OK: example proof package validates against the shared schema.');
