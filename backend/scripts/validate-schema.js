'use strict';

// Standalone verification: does the example proof package validate against the
// shared schema? Run with `npm run validate:schema`. Exits non-zero on failure
// so it can gate CI later.

const fs = require('fs');
const path = require('path');
const { loadValidator } = require('../src/services/proofSchema');

const examplePath = path.join(
  __dirname,
  '..',
  '..',
  'docs',
  'design',
  'examples',
  'proof-package.example.json',
);

const { validate } = loadValidator();
const example = JSON.parse(fs.readFileSync(examplePath, 'utf8'));

if (!validate(example)) {
  console.error('FAIL: example proof package does NOT validate against the schema:');
  console.error(JSON.stringify(validate.errors, null, 2));
  process.exit(1);
}

console.log('OK: example proof package validates against the shared schema.');
