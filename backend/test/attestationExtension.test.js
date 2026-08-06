'use strict';

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const {
  ATTESTATION_OID, encodeOid, readTlv, parseAttestationExtension,
} = require('../src/services/attestationExtension');

/**
 * The key attestation extension parser (OID 1.3.6.1.4.1.11129.2.1.17).
 *
 * This is hand-written DER parsing over attacker-reachable bytes, so the tests
 * are weighted towards what it must REFUSE. The governing rule: a malformed or
 * hostile extension must never yield a security level. Inventing
 * "TrustedEnvironment" from rubbish is worse than admitting the extension could
 * not be read.
 */

// A KeyDescription captured from a real device, kept as hex rather than as a
// certificate file so no per-device TEE identifier is committed. This is the
// structure `openssl asn1parse` reports as: attestationVersion 300,
// attestationSecurityLevel 1, rootOfTrust deviceLocked TRUE, verifiedBootState 0.
const REAL_KEY_DESCRIPTION_HEX =
  '3082012F0202012C0A01010202012C0A01010420AA9EA9ECE94EDB6146675DB7510DF8E8E744588053' +
  '7069A8EE391D25ACDE657A04003054BF853D080206019F9A533787BF85454404423040311A30180413' +
  '636F6D2E7265616C6974796C6F636B2E617070020101312204207D6DBC59794BA3CD0FCE05A302A88B' +
  '911AAF28405093B286A0DD27926B5FFE523081A4A1083106020103020102A203020103A30402020100' +
  'A5053103020104AA03020101BF8377020500BF853E03020100BF85404C304A0420552D218C71839B0C' +
  'B250EBD2F0179A575DB7766C73C75BA41D49839D6E4588750101FF0A01000420C589A1E55D5DC6669D' +
  '9503A1E549D2794EE9FD789BB7C2221B41BCEF6D5D17CCBF85410502030249F0BF854205020303176B' +
  'BF854E060204013525CDBF854F060204013525CD';

/** Wraps a KeyDescription in a certificate carrying it under the attestation OID. */
function certWithExtension(keyDescriptionDer) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rl-ext-'));
  const keyPath = path.join(dir, 'k.pem');
  const cnfPath = path.join(dir, 'openssl.cnf');
  const certPath = path.join(dir, 'c.pem');

  fs.writeFileSync(
    cnfPath,
    `[req]\ndistinguished_name=dn\nx509_extensions=v3\n[dn]\n[v3]\n` +
      `${ATTESTATION_OID}=DER:${keyDescriptionDer.toString('hex')}\n`,
  );
  execFileSync(
    'openssl',
    ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath,
      '-out', certPath, '-days', '1', '-subj', '/CN=test', '-config', cnfPath],
    { stdio: 'pipe' },
  );
  const cert = new crypto.X509Certificate(fs.readFileSync(certPath));
  fs.rmSync(dir, { recursive: true, force: true });
  return cert;
}

// --- OID encoding ------------------------------------------------------------

test('the attestation OID encodes to the bytes the certificate actually carries', () => {
  // Derived from the dotted form rather than pasted as a hex literal, so the
  // constant cannot drift from the OID it claims to be.
  assert.strictEqual(encodeOid(ATTESTATION_OID).toString('hex'), '2b06010401d679020111');
});

// --- the real structure ------------------------------------------------------

test('a real device KeyDescription parses to the values openssl reports', () => {
  const cert = certWithExtension(Buffer.from(REAL_KEY_DESCRIPTION_HEX, 'hex'));

  const parsed = parseAttestationExtension(cert.raw);

  assert.strictEqual(parsed.attestationVersion, 300);
  assert.strictEqual(parsed.securityLevelValue, 1);
  assert.strictEqual(parsed.securityLevel, 'TrustedEnvironment');
  assert.strictEqual(parsed.deviceLocked, true);
  assert.strictEqual(parsed.verifiedBootStateValue, 0);
  assert.strictEqual(parsed.verifiedBootState, 'Verified');
});

test('a certificate without the extension returns null, which is not an error', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rl-plain-'));
  const certPath = path.join(dir, 'c.pem');
  execFileSync(
    'openssl',
    ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', path.join(dir, 'k.pem'),
      '-out', certPath, '-days', '1', '-subj', '/CN=plain'],
    { stdio: 'pipe' },
  );
  const cert = new crypto.X509Certificate(fs.readFileSync(certPath));
  fs.rmSync(dir, { recursive: true, force: true });

  // CA certificates in a genuine chain have no attestation extension. That is an
  // ordinary state and must be distinguishable from a broken one.
  assert.strictEqual(parseAttestationExtension(cert.raw), null);
});

// --- refusals ----------------------------------------------------------------

test('a truncated KeyDescription throws instead of reporting a security level', () => {
  const full = Buffer.from(REAL_KEY_DESCRIPTION_HEX, 'hex');
  const cert = certWithExtension(full.subarray(0, 40));

  assert.throws(() => parseAttestationExtension(cert.raw), /DER|KeyDescription/);
});

test('a length header longer than the buffer is refused, not read past', () => {
  // SEQUENCE claiming 0x7FFF bytes of content while carrying two.
  const lying = Buffer.from('30827fff0101', 'hex');

  assert.throws(() => readTlv(lying, 0), /runs past the end/);
});

test('an unterminated high-tag-number form is refused', () => {
  // 0x1f opens the multi-byte tag form; every byte here sets the continuation
  // bit, so the tag never ends.
  const runaway = Buffer.from('bf'.concat('ff'.repeat(8)), 'hex');

  assert.throws(() => readTlv(runaway, 0), /unterminated tag|implausible/);
});

test('an indefinite length is refused, since it is not valid DER', () => {
  assert.throws(() => readTlv(Buffer.from('3080', 'hex'), 0), /unsupported DER length/);
});

test('a KeyDescription with too few fields is refused', () => {
  // A SEQUENCE holding only attestationVersion. Reading field 7 from it would be
  // an out-of-bounds access; reporting a level from it would be fiction.
  const short = Buffer.from('30050202012c', 'hex');
  const cert = certWithExtension(short);

  assert.throws(() => parseAttestationExtension(cert.raw), /fields|DER/);
});

// --- security level semantics -------------------------------------------------

test('an unrecognised security level is surfaced, never defaulted to a known one', () => {
  // Value 7 is not in the published enum. Mapping it silently onto "Software" or
  // "TrustedEnvironment" would invent a claim the device never made.
  const hex = REAL_KEY_DESCRIPTION_HEX.replace('0202012C0A0101', '0202012C0A0107');
  const cert = certWithExtension(Buffer.from(hex, 'hex'));

  const parsed = parseAttestationExtension(cert.raw);

  assert.strictEqual(parsed.securityLevelValue, 7);
  assert.strictEqual(parsed.securityLevel, 'Unknown(7)');
});

test('a Software security level is read as Software', () => {
  // The case that must be visible: the device stating the key is not in secure
  // hardware. The verifier turns this into a `fail`.
  const hex = REAL_KEY_DESCRIPTION_HEX.replace('0202012C0A0101', '0202012C0A0100');
  const cert = certWithExtension(Buffer.from(hex, 'hex'));

  const parsed = parseAttestationExtension(cert.raw);

  assert.strictEqual(parsed.securityLevelValue, 0);
  assert.strictEqual(parsed.securityLevel, 'Software');
});
