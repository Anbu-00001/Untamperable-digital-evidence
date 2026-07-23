'use strict';

const fs = require('fs');
const config = require('../config');

// Robust CJS/ESM interop for Ajv's draft-2020 build and ajv-formats.
const AjvModule = require('ajv/dist/2020');
const Ajv2020 = AjvModule.default || AjvModule;
const addFormatsModule = require('ajv-formats');
const addFormats = addFormatsModule.default || addFormatsModule;

/**
 * Loads the shared proof-package schema from disk and compiles a validator.
 * Throws if the schema file is missing or does not compile — callers use that
 * to fail fast at boot rather than on the first request.
 *
 * @returns {{ schema: object, validate: import('ajv').ValidateFunction }}
 */
function loadValidator() {
  const raw = fs.readFileSync(config.proofSchemaPath, 'utf8');
  const schema = JSON.parse(raw);
  // strict:false tolerates annotation keywords (contentEncoding, examples).
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  return { schema, validate };
}

module.exports = { loadValidator };
