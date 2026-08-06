'use strict';

const crypto = require('node:crypto');

/**
 * Proof of possession for reading a stored proof package.
 *
 * The problem this solves, stated plainly: `GET /proof/:eventId` returns the
 * full package — which carries GPS coordinates — and `GET /proof/:eventId/media`
 * returns the photograph. Before this, the only thing standing in front of both
 * was knowing a UUIDv4. An unguessable identifier is a capability, not an
 * authorisation: it cannot be revoked, it leaks permanently the moment it
 * appears in a log, a referrer header or a screenshot, and it gives the holder
 * everything forever.
 *
 * The fix uses a key the system already has. Every package is signed by a
 * per-install ECDSA P-256 key that lives in the Android Keystore and is
 * **non-exportable** — the device can sign with it but nothing, including the
 * app, can read it out. So "prove you are the device that made this record" is
 * answerable: sign a fresh challenge and let the server check it against the
 * public key already inside the stored package.
 *
 * That the public key travels inside the very package being requested is not a
 * circularity. The legitimate device holds its own private key and needs nothing
 * from the server to sign; an attacker who obtained the package by other means
 * has the public key and still cannot produce a signature.
 *
 * ---------------------------------------------------------------------------
 * Relationship to RFC 9421 (HTTP Message Signatures), stated exactly
 * ---------------------------------------------------------------------------
 * The signature base below follows RFC 9421 §2.5 in shape: a newline-joined list
 * of lower-cased, quoted component identifiers, each with its value, covering
 * the method and path so a signature cannot be lifted onto a different request.
 *
 * It is deliberately NOT a compliant RFC 9421 implementation, and this file does
 * not claim to be one. A compliant implementation carries `Signature-Input`,
 * structured-field parameter serialisation, and the full derived-component set;
 * none of that is here. Calling a subset by the RFC's name would be the kind of
 * overclaim this project refuses elsewhere.
 *
 * One RFC detail IS honoured, because getting it wrong is a silent interop
 * failure: RFC 9421 §3.3.4 mandates that ECDSA signatures travel as fixed-width
 * `r || s` (64 bytes for P-256), NOT as ASN.1/DER. Android's
 * `Signature.getInstance("SHA256withECDSA")` and Node's `crypto` both emit DER
 * by default, so a client following either naively produces a well-formed
 * signature that a spec-compliant verifier rejects. Rather than pick a side and
 * make every client match, [verifySignature] accepts BOTH encodings — they carry
 * the same (r, s) pair, so accepting both costs nothing in security and removes
 * an entire class of "signature looks right but fails" bugs.
 */

/** Seconds a signed request stays acceptable either side of the server clock. */
const DEFAULT_MAX_SKEW_SECONDS = 300;

/** P-256: r and s are 32 bytes each. */
const P256_COORDINATE_BYTES = 32;

const SCHEME = 'RealityLock-PoP';

/**
 * Builds the string a client signs and the server re-derives.
 *
 * Covers the method and path so a signature captured from one request cannot be
 * replayed against another — without `@method`, a signature authorising a read
 * would also authorise anything else at that path.
 */
function buildSignatureBase({ method, path, eventId, created, nonce }) {
  if (!method || !path || !eventId || created === undefined || !nonce) {
    throw new Error('signature base needs method, path, eventId, created and nonce');
  }
  return [
    `"@method": ${String(method).toUpperCase()}`,
    `"@path": ${path}`,
    `"eventid": ${eventId}`,
    `"created": ${created}`,
    `"nonce": ${nonce}`,
  ].join('\n');
}

/**
 * Parses an `Authorization: RealityLock-PoP k="v",...` header.
 *
 * Returns null for anything unparseable rather than throwing: a malformed header
 * is an authentication failure, not a server error, and the caller turns it into
 * a 401 with the same wording as every other rejection so the response cannot be
 * used to distinguish "bad syntax" from "bad signature".
 */
function parseAuthorizationHeader(header) {
  if (typeof header !== 'string') return null;
  const trimmed = header.trim();
  if (!trimmed.toLowerCase().startsWith(`${SCHEME.toLowerCase()} `)) return null;

  const params = {};
  const body = trimmed.slice(SCHEME.length + 1);
  // Deliberately strict: quoted values only. Accepting bare tokens too would
  // mean two spellings of the same header, and the signature base is built from
  // these values — two spellings would eventually mean two different bases.
  const re = /([a-zA-Z]+)="([^"]*)"/g;
  let m = re.exec(body);
  while (m !== null) {
    params[m[1].toLowerCase()] = m[2];
    m = re.exec(body);
  }

  const { eventid, created, nonce, signature } = params;
  if (!eventid || !created || !nonce || !signature) return null;
  if (!/^\d+$/.test(created)) return null;

  return {
    eventId: eventid,
    created: Number(created),
    nonce,
    signature,
  };
}

/** Converts a fixed-width r||s signature to the DER form Node verifies. */
function rawSignatureToDer(raw) {
  if (raw.length !== P256_COORDINATE_BYTES * 2) {
    throw new Error(`expected ${P256_COORDINATE_BYTES * 2} bytes of r||s`);
  }
  const toInteger = (bytes) => {
    // DER INTEGER is signed and minimally encoded: strip leading zeros, then put
    // one back if the high bit is set, or the value reads as negative.
    let start = 0;
    while (start < bytes.length - 1 && bytes[start] === 0) start += 1;
    let body = bytes.subarray(start);
    if (body[0] & 0x80) body = Buffer.concat([Buffer.from([0]), body]);
    return Buffer.concat([Buffer.from([0x02, body.length]), body]);
  };
  const r = toInteger(raw.subarray(0, P256_COORDINATE_BYTES));
  const s = toInteger(raw.subarray(P256_COORDINATE_BYTES));
  const body = Buffer.concat([r, s]);
  if (body.length > 127) throw new Error('unexpected signature length');
  return Buffer.concat([Buffer.from([0x30, body.length]), body]);
}

/**
 * Verifies `signatureBase` against `publicKeySpkiBase64`.
 *
 * Accepts DER or raw r||s, for the reason given in the file header. Returns a
 * boolean and never throws for a bad signature — only a malformed KEY is
 * exceptional, since that means the stored package itself is broken.
 */
function verifySignature({ signatureBase, signatureBase64, publicKeySpkiBase64 }) {
  const publicKey = crypto.createPublicKey({
    key: Buffer.from(publicKeySpkiBase64, 'base64'),
    format: 'der',
    type: 'spki',
  });

  const signature = Buffer.from(signatureBase64, 'base64');
  const attempt = (sig) => {
    try {
      return crypto
        .createVerify('SHA256')
        .update(Buffer.from(signatureBase, 'utf8'))
        .verify({ key: publicKey, dsaEncoding: 'der' }, sig);
    } catch {
      return false;
    }
  };

  // Raw r||s first when the length says so, since a 64-byte DER signature is not
  // a thing — no ambiguity between the two forms at this size.
  if (signature.length === P256_COORDINATE_BYTES * 2) {
    try {
      if (attempt(rawSignatureToDer(signature))) return true;
    } catch {
      /* fall through to the DER attempt */
    }
  }
  return attempt(signature);
}

/**
 * Remembers recently-seen nonces so a captured Authorization header cannot be
 * replayed inside its freshness window.
 *
 * In-memory, and therefore per-process — the same limitation the rate limiter
 * has, and for the same reason (see services/rateLimitStore.js). The security
 * consequence is bounded and worth stating: with N processes, a captured header
 * can be replayed at most N times within `maxSkewSeconds`, rather than
 * indefinitely. The timestamp window, not the nonce cache, is what bounds it.
 */
class NonceCache {
  constructor({ maxSkewSeconds = DEFAULT_MAX_SKEW_SECONDS } = {}) {
    this.maxSkewSeconds = maxSkewSeconds;
    this.seen = new Map();
  }

  /** True if unseen (and records it); false if this nonce was already used. */
  check(nonce, nowSeconds) {
    this.prune(nowSeconds);
    // Scoped per nonce value only: nonces are client-generated randoms, and
    // scoping by event would let the same nonce be reused across events.
    if (this.seen.has(nonce)) return false;
    this.seen.set(nonce, nowSeconds);
    return true;
  }

  prune(nowSeconds) {
    // Entries older than the acceptance window can never be replayed anyway —
    // the timestamp check rejects them first — so holding them would grow the
    // map without adding protection.
    const cutoff = nowSeconds - this.maxSkewSeconds;
    for (const [nonce, at] of this.seen) {
      if (at < cutoff) this.seen.delete(nonce);
    }
  }

  get size() {
    return this.seen.size;
  }
}

module.exports = {
  SCHEME,
  DEFAULT_MAX_SKEW_SECONDS,
  buildSignatureBase,
  parseAuthorizationHeader,
  rawSignatureToDer,
  verifySignature,
  NonceCache,
};
