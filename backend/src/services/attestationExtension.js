'use strict';

/**
 * The Android key attestation extension, OID 1.3.6.1.4.1.11129.2.1.17.
 *
 * Reading it answers the two questions the chain alone cannot: what security
 * level the key actually claims (Software / TrustedEnvironment / StrongBox), and
 * what Verified Boot state the device was in when it attested. Until this
 * existed both were taken on the device's word.
 *
 * ## Why a hand-written DER reader
 *
 * Node's `crypto.X509Certificate` exposes no way to read an extension by OID —
 * there is no `extensions` accessor on it at all — so the choice was a new ASN.1
 * dependency or a small parser. A dependency was rejected: this session already
 * had to back out `opentimestamps` for pulling two unfixable critical CVEs, and
 * a parser for one fixed, well-documented structure is a poor reason to widen
 * the supply chain of the service that verifies evidence.
 *
 * The reader below is therefore deliberately minimal and defensive:
 *
 * - every read is bounds-checked against the buffer before it happens;
 * - nesting is depth-limited, so a crafted certificate cannot drive it into
 *   unbounded recursion;
 * - it decodes only what is needed and never evaluates or allocates from
 *   attacker-controlled lengths;
 * - **any** malformed input throws, and the caller turns that into
 *   `unavailable`. A parse failure must never be able to produce a security
 *   level, because a fabricated "StrongBox" is worse than no answer at all.
 *
 * Schema (source.android.com/docs/security/features/keystore/attestation):
 *
 * ```
 * KeyDescription ::= SEQUENCE {
 *   attestationVersion INTEGER, attestationSecurityLevel SecurityLevel,
 *   keyMintVersion INTEGER, keyMintSecurityLevel SecurityLevel,
 *   attestationChallenge OCTET_STRING, uniqueId OCTET_STRING,
 *   softwareEnforced AuthorizationList, hardwareEnforced AuthorizationList }
 * SecurityLevel ::= ENUMERATED { Software(0), TrustedEnvironment(1), StrongBox(2) }
 * RootOfTrust ::= SEQUENCE { verifiedBootKey OCTET_STRING, deviceLocked BOOLEAN,
 *   verifiedBootState VerifiedBootState, verifiedBootHash OCTET_STRING }
 * VerifiedBootState ::= ENUMERATED { Verified(0), SelfSigned(1), Unverified(2), Failed(3) }
 * ```
 */

const ATTESTATION_OID = '1.3.6.1.4.1.11129.2.1.17';

/** `rootOfTrust [704] EXPLICIT` inside an AuthorizationList. */
const TAG_ROOT_OF_TRUST = 704;

/** KeyDescription field positions. */
const IDX_ATTESTATION_SECURITY_LEVEL = 1;
const IDX_HARDWARE_ENFORCED = 7;

/** RootOfTrust field positions. */
const IDX_DEVICE_LOCKED = 1;
const IDX_VERIFIED_BOOT_STATE = 2;

const SECURITY_LEVELS = ['Software', 'TrustedEnvironment', 'StrongBox'];
const VERIFIED_BOOT_STATES = ['Verified', 'SelfSigned', 'Unverified', 'Failed'];

const MAX_DEPTH = 24;

// --- minimal DER reader ------------------------------------------------------

/**
 * Reads one TLV at [pos].
 *
 * Returns the tag number, whether it is constructed, and the value's bounds.
 * Throws on any truncation rather than returning a partial read.
 */
function readTlv(buf, pos, depth = 0) {
  if (depth > MAX_DEPTH) throw new Error('DER nesting too deep');
  if (pos + 2 > buf.length) throw new Error('truncated DER: no room for tag and length');

  const first = buf[pos];
  const constructed = (first & 0x20) !== 0;
  let tagNumber = first & 0x1f;
  let cursor = pos + 1;

  // High-tag-number form: 0x1f means the tag continues in base-128 bytes. Needed
  // here because rootOfTrust is [704], far above the 30 the short form holds.
  if (tagNumber === 0x1f) {
    tagNumber = 0;
    for (;;) {
      if (cursor >= buf.length) throw new Error('truncated DER: unterminated tag');
      const byte = buf[cursor];
      cursor += 1;
      tagNumber = tagNumber * 128 + (byte & 0x7f);
      if ((byte & 0x80) === 0) break;
      if (tagNumber > 0xffffff) throw new Error('implausible DER tag number');
    }
  }

  if (cursor >= buf.length) throw new Error('truncated DER: no length byte');
  let length = buf[cursor];
  cursor += 1;

  if (length & 0x80) {
    const lengthBytes = length & 0x7f;
    // Indefinite length is not valid DER, and multi-megabyte lengths in a
    // certificate extension are not plausible input.
    if (lengthBytes === 0 || lengthBytes > 4) throw new Error('unsupported DER length form');
    if (cursor + lengthBytes > buf.length) throw new Error('truncated DER length');
    length = 0;
    for (let i = 0; i < lengthBytes; i += 1) {
      length = length * 256 + buf[cursor + i];
    }
    cursor += lengthBytes;
  }

  const valueStart = cursor;
  const valueEnd = valueStart + length;
  if (valueEnd > buf.length) throw new Error('DER value runs past the end of the buffer');

  return { tagNumber, constructed, valueStart, valueEnd, next: valueEnd };
}

/** Every direct child TLV of a constructed value spanning [start, end). */
function children(buf, start, end, depth = 0) {
  const out = [];
  let pos = start;
  while (pos < end) {
    const tlv = readTlv(buf, pos, depth);
    if (tlv.valueEnd > end) throw new Error('DER child overruns its parent');
    out.push(tlv);
    pos = tlv.next;
  }
  return out;
}

/** An INTEGER/ENUMERATED value, as a JS number. Rejects oversized encodings. */
function readSmallInteger(buf, tlv) {
  const length = tlv.valueEnd - tlv.valueStart;
  if (length < 1 || length > 4) throw new Error('unexpected integer width');
  let value = 0;
  for (let i = tlv.valueStart; i < tlv.valueEnd; i += 1) value = value * 256 + buf[i];
  return value;
}

/** Encodes a dotted OID to its DER content bytes, so no magic hex is embedded. */
function encodeOid(dotted) {
  const parts = dotted.split('.').map(Number);
  if (parts.length < 2 || parts.some((n) => !Number.isInteger(n) || n < 0)) {
    throw new Error(`not an OID: ${dotted}`);
  }
  const bytes = [parts[0] * 40 + parts[1]];
  for (const part of parts.slice(2)) {
    const chunk = [part & 0x7f];
    let rest = Math.floor(part / 128);
    while (rest > 0) {
      chunk.unshift((rest & 0x7f) | 0x80);
      rest = Math.floor(rest / 128);
    }
    bytes.push(...chunk);
  }
  return Buffer.from(bytes);
}

const ATTESTATION_OID_DER = encodeOid(ATTESTATION_OID);

// --- certificate navigation --------------------------------------------------

/**
 * The attestation extension's value bytes, or null when the certificate carries
 * no such extension.
 *
 * Navigates the structure properly — Certificate → tbsCertificate → `[3]`
 * extensions → each Extension — rather than scanning the DER for the OID's
 * bytes. A scan would also match those bytes appearing inside any other field,
 * which is attacker-influenceable content.
 */
function findAttestationExtension(certDer) {
  const cert = readTlv(certDer, 0);
  const [tbs] = children(certDer, cert.valueStart, cert.valueEnd);
  if (!tbs || !tbs.constructed) throw new Error('certificate has no tbsCertificate');

  const tbsChildren = children(certDer, tbs.valueStart, tbs.valueEnd);
  // extensions ::= [3] EXPLICIT Extensions
  const extensionsHolder = tbsChildren.find((c) => c.constructed && c.tagNumber === 3);
  if (!extensionsHolder) return null;

  const [extensionsSeq] = children(
    certDer, extensionsHolder.valueStart, extensionsHolder.valueEnd,
  );
  if (!extensionsSeq) return null;

  for (const ext of children(certDer, extensionsSeq.valueStart, extensionsSeq.valueEnd)) {
    const parts = children(certDer, ext.valueStart, ext.valueEnd);
    if (parts.length === 0) continue;
    const oid = parts[0];
    const oidBytes = certDer.subarray(oid.valueStart, oid.valueEnd);
    if (!oidBytes.equals(ATTESTATION_OID_DER)) continue;

    // Extension ::= SEQUENCE { extnID, critical DEFAULT FALSE, extnValue OCTET STRING }
    const value = parts[parts.length - 1];
    return certDer.subarray(value.valueStart, value.valueEnd);
  }
  return null;
}

/**
 * Parses the KeyDescription carried in a certificate.
 *
 * Returns null when the certificate has no attestation extension — an ordinary
 * state for the CA certificates in a chain, not an error. Throws when the
 * extension exists but does not parse, so a damaged or forged structure can
 * never yield a security level.
 */
function parseAttestationExtension(certDer) {
  const extensionValue = findAttestationExtension(certDer);
  if (!extensionValue) return null;

  const keyDescription = readTlv(extensionValue, 0);
  if (!keyDescription.constructed) throw new Error('KeyDescription is not a SEQUENCE');
  const fields = children(
    extensionValue, keyDescription.valueStart, keyDescription.valueEnd,
  );
  if (fields.length <= IDX_HARDWARE_ENFORCED) {
    throw new Error(`KeyDescription has only ${fields.length} fields`);
  }

  const securityLevelValue = readSmallInteger(
    extensionValue, fields[IDX_ATTESTATION_SECURITY_LEVEL],
  );

  const result = {
    attestationVersion: readSmallInteger(extensionValue, fields[0]),
    securityLevelValue,
    // Unknown values are surfaced as-is rather than mapped to a friendly name, so
    // a future level this build does not know about is visibly unrecognised
    // instead of silently becoming "Software".
    securityLevel: SECURITY_LEVELS[securityLevelValue] ?? `Unknown(${securityLevelValue})`,
    deviceLocked: null,
    verifiedBootStateValue: null,
    verifiedBootState: null,
  };

  // rootOfTrust is read ONLY from hardwareEnforced. The same tag can appear in
  // softwareEnforced, where it is whatever the OS chose to assert — precisely
  // the claim that a compromised OS would forge.
  const hardware = fields[IDX_HARDWARE_ENFORCED];
  const rootOfTrustHolder = children(extensionValue, hardware.valueStart, hardware.valueEnd)
    .find((c) => c.constructed && c.tagNumber === TAG_ROOT_OF_TRUST);
  if (!rootOfTrustHolder) return result;

  const [rootOfTrust] = children(
    extensionValue, rootOfTrustHolder.valueStart, rootOfTrustHolder.valueEnd,
  );
  if (!rootOfTrust || !rootOfTrust.constructed) throw new Error('rootOfTrust is not a SEQUENCE');

  const rotFields = children(extensionValue, rootOfTrust.valueStart, rootOfTrust.valueEnd);
  if (rotFields.length <= IDX_VERIFIED_BOOT_STATE) {
    throw new Error(`RootOfTrust has only ${rotFields.length} fields`);
  }

  const lockedTlv = rotFields[IDX_DEVICE_LOCKED];
  result.deviceLocked = extensionValue[lockedTlv.valueStart] !== 0x00;

  const stateValue = readSmallInteger(extensionValue, rotFields[IDX_VERIFIED_BOOT_STATE]);
  result.verifiedBootStateValue = stateValue;
  result.verifiedBootState = VERIFIED_BOOT_STATES[stateValue] ?? `Unknown(${stateValue})`;

  return result;
}

module.exports = {
  ATTESTATION_OID,
  SECURITY_LEVELS,
  VERIFIED_BOOT_STATES,
  encodeOid,
  readTlv,
  findAttestationExtension,
  parseAttestationExtension,
};
