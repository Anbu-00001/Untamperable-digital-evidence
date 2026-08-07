'use strict';

const tls = require('node:tls');
const nodeCrypto = require('node:crypto');
const pkijs = require('pkijs');
const asn1js = require('asn1js');

const config = require('../config');

/**
 * RFC 3161 time-stamping: an *independent* upper bound on when a capture happened.
 *
 * ## The gap this closes
 *
 * Everything else in a proof package is the device's own account of itself. The
 * signature proves the root was signed by a key in that device's Keystore; the
 * attestation chain proves the key is hardware-backed. Neither says *when*, and
 * `metadata.timestamp.wallClockMillis` is read from a clock the holder of the
 * phone can set to any value they like. `PHASE7_STRETCH_STATUS.md` states this
 * plainly: "a third party still has only the device's word for *when* a capture
 * happened."
 *
 * A TSA token closes exactly half of that gap, and it is important to be precise
 * about which half. The token proves the Merkle root **existed no later than**
 * the TSA's `genTime`, because a TSA will not sign a digest it has not been
 * shown. It does NOT prove the capture happened *at* the claimed time — nothing
 * stops someone capturing today and requesting a token next week.
 *
 * So the anchor supports one honest claim and one honest contradiction:
 *
 *  - **upper bound**: the root is no younger than `genTime`. Backdating a
 *    capture to before an anchored event is not possible without the TSA's key.
 *  - **contradiction**: if the device claims a capture time *after* the moment an
 *    independent authority already saw the root, one of the two is wrong. That is
 *    a real finding, and it is the only place in this system where a device clock
 *    claim can be contradicted by evidence rather than merely doubted.
 *
 * ## Why the imprint is the root itself, not a hash of it
 *
 * `pkijs.MessageImprint.create()` *hashes* the input it is given. Our Merkle root
 * is already a SHA-256 digest, so passing it there would timestamp SHA-256(root)
 * — a value that appears nowhere in the proof package. The token would still be
 * cryptographically sound and completely useless to a third party, who would have
 * to know to hash the root again before checking anything.
 *
 * The imprint is therefore constructed directly, so that the token is verifiable
 * with the command a forensic examiner would actually reach for:
 *
 *     openssl ts -verify -digest <merkle.root> -in token.tsr -CAfile <ca-bundle>
 *
 * That interoperability is the whole point of choosing a standard anchor. This
 * was verified against DigiCert, Certum, GlobalSign, SSL.com and rfc3161.ai.moda
 * on 2026-08-07: all five returned `Verification: OK`, and openssl correctly
 * reported FAILED for a wrong digest and for a flipped byte.
 *
 * ## Why not OpenTimestamps
 *
 * ADR-0002 chose OpenTimestamps and Phase 7 retracted it: `opentimestamps@0.4.9`
 * carries 11 advisories including two critical, via `request`, deprecated since
 * 2020, with no patched release. `pkijs` was measured before being adopted here —
 * 14 packages, 3.8 MB, **0 vulnerabilities** — and its dependencies are the
 * author's own ASN.1 utilities plus `@noble/hashes`.
 */

const OID = {
  TSTINFO: '1.2.840.113549.1.9.16.1.4',
  CONTENT_TYPE: '1.2.840.113549.1.9.3',
  MESSAGE_DIGEST: '1.2.840.113549.1.9.4',
  EXT_KEY_USAGE: '2.5.29.37',
  KP_TIMESTAMPING: '1.3.6.1.5.5.7.3.8',
  SHA256: '2.16.840.1.101.3.4.2.1',
};

const HEX64 = /^[0-9a-f]{64}$/;

/** pkijs stores every parsed value as a hex view; this is the ubiquitous unwrap. */
const bytesOf = (v) => Buffer.from(v.valueBlock.valueHexView);

/**
 * Node's bundled Mozilla CA set, parsed once.
 *
 * Deliberately NOT a pinned file, which is the opposite of the choice made for
 * the Google attestation roots — and the difference is real. Those are a closed
 * set of four keys that Google publishes and that must never be silently widened,
 * so pinning them is the security property. TSA certificates are ordinary WebPKI
 * certificates that rotate on their own schedule; DigiCert's timestamp responder
 * already rolled over in 2025. Pinning those would mean shipping a file that goes
 * stale and fails closed on a working token.
 */
let trustedRoots = null;
function getTrustedRoots() {
  if (trustedRoots !== null) return trustedRoots;
  trustedRoots = [];
  for (const pem of tls.rootCertificates) {
    try {
      const der = Buffer.from(
        pem.replace(/-----(BEGIN|END) CERTIFICATE-----/g, '').replace(/\s+/g, ''),
        'base64',
      );
      trustedRoots.push(pkijs.Certificate.fromBER(der));
    } catch {
      // A root this pkijs build cannot parse is skipped rather than fatal: the
      // set is large, supplied by Node, and one unparseable entry must not
      // disable timestamp verification entirely.
    }
  }
  return trustedRoots;
}

/**
 * DER INTEGER is *signed*. Eight random bytes have a 50% chance of a set high
 * bit, which encodes as a negative integer — legal per RFC 3161, but it makes
 * the nonce echo comparison sign-dependent and some responders unhappy. A
 * leading zero byte keeps it unambiguously positive.
 */
function positiveNonce(byteLength = 8) {
  const b = nodeCrypto.randomBytes(byteLength);
  return b[0] & 0x80 ? Buffer.concat([Buffer.from([0x00]), b]) : b;
}

/** Builds the DER TimeStampReq for a Merkle root. */
function buildRequest(rootHex) {
  if (!HEX64.test(rootHex)) {
    throw new Error(`timestamp anchor: root must be 64 lowercase hex chars, got "${rootHex}"`);
  }
  const nonce = positiveNonce();
  const request = new pkijs.TimeStampReq({
    version: 1,
    messageImprint: new pkijs.MessageImprint({
      hashAlgorithm: new pkijs.AlgorithmIdentifier({
        algorithmId: OID.SHA256,
        algorithmParams: new asn1js.Null(),
      }),
      // The root itself. See the header note on why this is not `create()`.
      hashedMessage: new asn1js.OctetString({ valueHex: Buffer.from(rootHex, 'hex') }),
    }),
    // Ask the TSA to embed its certificate chain. Without it the token cannot be
    // verified by anyone who does not already hold that TSA's certificates.
    certReq: true,
    nonce: new asn1js.Integer({ valueHex: nonce }),
  });
  return { der: Buffer.from(request.toSchema().toBER(false)), nonce };
}

/**
 * Requests a timestamp token over `rootHex` from a TSA.
 *
 * Throws on any failure. Callers on the ingest path treat that as "no anchor",
 * never as "the package is bad" — a TSA outage is not evidence about a capture.
 */
async function requestTimestamp(rootHex, options = {}) {
  const tsaUrl = options.tsaUrl || config.timestampAnchor.tsaUrl;
  const timeoutMs = options.timeoutMs || config.timestampAnchor.timeoutMs;
  const { der, nonce } = buildRequest(rootHex);

  const response = await fetch(tsaUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/timestamp-query',
      Accept: 'application/timestamp-reply',
    },
    body: der,
    signal: AbortSignal.timeout(timeoutMs),
  });

  if (!response.ok) {
    throw new Error(`TSA ${tsaUrl} returned HTTP ${response.status}`);
  }
  const body = Buffer.from(await response.arrayBuffer());

  // Verify what we just received before storing it. A token that does not check
  // out is worse than no token: it would sit in the record looking like evidence.
  const verdict = await verifyTimestampToken(body, rootHex);
  if (!verdict.ok) {
    throw new Error(`TSA ${tsaUrl} returned an unverifiable token: ${verdict.reasons.join('; ')}`);
  }
  if (verdict.nonce === null || !Buffer.from(verdict.nonce, 'hex').equals(nonce)) {
    // Only checkable at request time, and only meaningful here: it proves this
    // token is a fresh answer to *this* request rather than a replayed one.
    throw new Error(`TSA ${tsaUrl} did not echo the request nonce`);
  }

  return {
    tokenBase64: body.toString('base64'),
    tsaUrl,
    genTime: verdict.genTime,
    serialNumber: verdict.serialNumber,
    policy: verdict.policy,
    accuracySeconds: verdict.accuracySeconds,
    requestedAtMillis: Date.now(),
  };
}

/**
 * Full RFC 3161 / RFC 5652 verification of a timestamp token against a root.
 *
 * Never throws for a bad token — a malformed or forged token is a *result*, not
 * an exception, and the caller needs the reasons to report them.
 *
 * `pkijs.SignedData.verify()` is deliberately not used. It special-cases
 * `id-ct-TSTInfo` content by hashing a supplied pre-image and comparing it to
 * the message imprint, which assumes the caller holds the original data. We
 * timestamp a digest, so there is no pre-image to supply and that path always
 * fails. The CMS checks below are the same ones it performs, plus the
 * timeStamping EKU rule, which it does not check at all.
 */
async function verifyTimestampToken(tokenDer, expectedRootHex, options = {}) {
  const reasons = [];
  const fail = (why) => ({ ok: false, reasons: [why], genTime: null });

  let response;
  try {
    response = pkijs.TimeStampResp.fromBER(tokenDer);
  } catch (err) {
    return fail(`token is not a TimeStampResp: ${err.message}`);
  }

  // granted(0) and grantedWithMods(1) both carry a token; everything else is a
  // refusal with no token to inspect.
  const status = response.status.status;
  if (status !== 0 && status !== 1) return fail(`TSA refused the request (PKIStatus ${status})`);
  if (!response.timeStampToken) return fail('response carries no timeStampToken');

  let signed;
  let tstInfo;
  let eContent;
  try {
    signed = new pkijs.SignedData({ schema: response.timeStampToken.content });
    if (signed.encapContentInfo.eContentType !== OID.TSTINFO) {
      return fail(`encapsulated content is ${signed.encapContentInfo.eContentType}, not TSTInfo`);
    }
    eContent = signed.encapContentInfo.eContent.valueBlock.valueHexView;
    tstInfo = pkijs.TSTInfo.fromBER(eContent);
  } catch (err) {
    return fail(`token could not be parsed: ${err.message}`);
  }

  // --- the imprint must be the root we are asking about ----------------------
  if (tstInfo.messageImprint.hashAlgorithm.algorithmId !== OID.SHA256) {
    reasons.push(`imprint uses ${tstInfo.messageImprint.hashAlgorithm.algorithmId}, not SHA-256`);
  }
  const imprint = bytesOf(tstInfo.messageImprint.hashedMessage).toString('hex');
  if (expectedRootHex && imprint !== expectedRootHex) {
    reasons.push('token was issued over a different digest than this package\'s Merkle root');
  }

  const signerInfo = signed.signerInfos && signed.signerInfos[0];
  if (!signerInfo) return fail('token has no SignerInfo');

  // --- locate the signing certificate inside the token -----------------------
  const certificates = (signed.certificates || []).filter((c) => c instanceof pkijs.Certificate);
  const signerCert = certificates.find(
    (c) =>
      c.issuer.isEqual(signerInfo.sid.issuer) &&
      bytesOf(c.serialNumber).equals(bytesOf(signerInfo.sid.serialNumber)),
  );
  if (!signerCert) {
    return fail('the signing certificate is not embedded in the token (was certReq set?)');
  }

  const crypto = pkijs.getCrypto(true);

  // The digest algorithm is whatever SignerInfo declares. Hardcoding SHA-256
  // here rejected valid Certum and GlobalSign tokens that openssl accepts.
  let digestName;
  try {
    digestName = crypto.getAlgorithmByOID(signerInfo.digestAlgorithm.algorithmId, true).name;
  } catch (err) {
    return fail(`unsupported digest algorithm: ${err.message}`);
  }
  const nodeDigest = digestName.replace('-', '').toLowerCase();

  // --- signed attributes are mandatory for non-id-data content (RFC 5652 §5.3)
  if (!signerInfo.signedAttrs) {
    return fail('token has no signed attributes');
  }
  const attributeOf = (oid) => signerInfo.signedAttrs.attributes.find((a) => a.type === oid);

  const contentTypeAttr = attributeOf(OID.CONTENT_TYPE);
  if (!contentTypeAttr || contentTypeAttr.values[0].valueBlock.toString() !== OID.TSTINFO) {
    reasons.push('contentType attribute does not declare TSTInfo');
  }

  const messageDigestAttr = attributeOf(OID.MESSAGE_DIGEST);
  if (!messageDigestAttr) {
    reasons.push('no messageDigest attribute');
  } else {
    const declared = bytesOf(messageDigestAttr.values[0]).toString('hex');
    const actual = nodeCrypto.createHash(nodeDigest).update(Buffer.from(eContent)).digest('hex');
    if (declared !== actual) {
      reasons.push('messageDigest attribute does not match the TSTInfo it claims to cover');
    }
  }

  // --- the signature itself --------------------------------------------------
  // Covers the DER re-encoding of signedAttrs with the SET OF tag; pkijs keeps
  // that encoding on the parsed object.
  try {
    const signatureValid = await crypto.verifyWithPublicKey(
      signerInfo.signedAttrs.encodedValue,
      signerInfo.signature,
      signerCert.subjectPublicKeyInfo,
      signerInfo.signatureAlgorithm,
      digestName,
    );
    if (!signatureValid) reasons.push('the TSA signature does not verify');
  } catch (err) {
    reasons.push(`the TSA signature could not be checked: ${err.message}`);
  }

  // --- RFC 3161 §2.3: the timeStamping EKU rule ------------------------------
  // "The TSA MUST sign each time-stamp message with a key reserved specifically
  //  for that purpose", expressed as an extendedKeyUsage that contains ONLY
  //  id-kp-timeStamping and is marked critical. This is what stops a certificate
  //  issued for TLS or code signing from being repurposed to mint timestamps,
  //  and pkijs does not check it.
  const ekuExtension = (signerCert.extensions || []).find((e) => e.extnID === OID.EXT_KEY_USAGE);
  if (!ekuExtension) {
    reasons.push('the signing certificate has no extendedKeyUsage extension');
  } else {
    const purposes = (ekuExtension.parsedValue && ekuExtension.parsedValue.keyPurposes) || [];
    if (!purposes.includes(OID.KP_TIMESTAMPING)) {
      reasons.push('the signing certificate is not authorised for timeStamping');
    } else if (purposes.length !== 1) {
      reasons.push(`the signing certificate has additional key purposes: ${purposes.join(', ')}`);
    }
    if (!ekuExtension.critical) {
      reasons.push('the extendedKeyUsage extension is not marked critical');
    }
  }

  // --- chain to a public root, judged at the token's own genTime --------------
  // Not at "now": a token signed by a certificate that has since expired was
  // still valid when it was issued, and that is precisely the situation an old
  // piece of evidence will be in.
  let chainLength = null;
  try {
    const engine = new pkijs.CertificateChainValidationEngine({
      certs: certificates,
      trustedCerts: options.trustedCerts || getTrustedRoots(),
      checkDate: tstInfo.genTime,
    });
    const chain = await engine.verify();
    if (!chain.result) {
      reasons.push(`the TSA certificate does not chain to a trusted root: ${chain.resultMessage}`);
    } else {
      chainLength = chain.certificatePath.length;
    }
  } catch (err) {
    reasons.push(`chain validation failed: ${err.message}`);
  }

  return {
    ok: reasons.length === 0,
    reasons,
    genTime: tstInfo.genTime.toISOString(),
    genTimeMillis: tstInfo.genTime.getTime(),
    imprintHex: imprint,
    serialNumber: bytesOf(tstInfo.serialNumber).toString('hex'),
    policy: tstInfo.policy || null,
    accuracySeconds: (tstInfo.accuracy && tstInfo.accuracy.seconds) || null,
    nonce: tstInfo.nonce ? bytesOf(tstInfo.nonce).toString('hex') : null,
    chainLength,
    signerSubject: signerCert.subject.typesAndValues
      .map((t) => t.value.valueBlock.value)
      .join(', '),
  };
}

module.exports = {
  requestTimestamp,
  verifyTimestampToken,
  buildRequest,
  getTrustedRoots,
  OID,
};
