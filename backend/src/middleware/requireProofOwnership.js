'use strict';

const config = require('../config');
const { getSharedStore, isSafeEventId } = require('../store');
const {
  SCHEME,
  buildSignatureBase,
  parseAuthorizationHeader,
  verifySignature,
  NonceCache,
} = require('../services/proofOfPossession');

/**
 * Gates the endpoints that hand back a package's contents on proof that the
 * caller holds the key which signed it.
 *
 * Applied to `GET /proof/:eventId` (full package, including GPS) and
 * `GET /proof/:eventId/media` (the photograph). NOT applied to
 * `GET /verify/:eventId`, which stays public on purpose: a verdict discloses no
 * coordinates and no media, and the ability for anyone to check a claim
 * independently is the point of the whole system (ADR-0006 §7).
 *
 * Nor is it applied to the POST routes. Those are already constrained by
 * construction — a package must be schema-valid and internally consistent, and
 * media is refused unless it hashes to the digest a signed package already
 * commits to — so an unauthenticated POST cannot put anything in the store that
 * a signature does not already vouch for.
 */

const nonceCache = new NonceCache({ maxSkewSeconds: config.proofReadAuth.maxSkewSeconds });

/**
 * One reply for every failure mode.
 *
 * Deliberately uniform: distinguishing "no such event" from "bad signature"
 * would turn this endpoint into an oracle for which event IDs exist, which is
 * most of what an attacker wanted from it in the first place. The `WWW-
 * Authenticate` header still tells an honest client exactly how to authenticate.
 */
function refuse(res) {
  res.setHeader('WWW-Authenticate', SCHEME);
  return res.status(401).json({
    error: 'proof_of_possession_required',
    message:
      'this endpoint returns the stored package (including location) or its media, ' +
      'so it requires proof that you hold the key that signed the package. Send ' +
      `Authorization: ${SCHEME} eventId="…",created="…",nonce="…",signature="…" ` +
      'where the signature is over the documented signature base.',
  });
}

function requireProofOwnership(req, res, next) {
  if (!config.proofReadAuth.enabled) return next();

  const { eventId } = req.params;
  if (!isSafeEventId(eventId)) {
    return res.status(400).json({ error: 'invalid_event_id' });
  }

  const parsed = parseAuthorizationHeader(req.get('authorization'));
  if (!parsed) return refuse(res);

  // The header names the event it authorises. Without this a signature obtained
  // for one event would be replayable against any other, since the rest of the
  // base would be identical.
  if (parsed.eventId !== eventId) return refuse(res);

  const nowSeconds = Math.floor(Date.now() / 1000);
  const skew = Math.abs(nowSeconds - parsed.created);
  if (!Number.isFinite(skew) || skew > config.proofReadAuth.maxSkewSeconds) return refuse(res);

  const pkg = getSharedStore().getPackage(eventId);
  // Same 401 as a bad signature — see refuse() on why this is not a 404.
  if (!pkg) return refuse(res);

  const publicKeySpkiBase64 = pkg?.signature?.publicKey?.value;
  if (!publicKeySpkiBase64) return refuse(res);

  // Checked BEFORE the signature is verified, so a replayed header is rejected
  // even if it is cryptographically perfect — which is exactly what a replay is.
  if (!nonceCache.check(parsed.nonce, nowSeconds)) return refuse(res);

  let ok = false;
  try {
    ok = verifySignature({
      signatureBase: buildSignatureBase({
        method: req.method,
        // req.originalUrl carries the query string; the mounted path is what the
        // client signs, so derive it from the same pieces on both sides.
        path: `${config.routes.proof}/${eventId}${req.path.endsWith('/media') ? '/media' : ''}`,
        eventId,
        created: parsed.created,
        nonce: parsed.nonce,
      }),
      signatureBase64: parsed.signature,
      publicKeySpkiBase64,
    });
  } catch {
    // A key that will not load means the stored package is malformed. Refusing
    // is the only safe answer; it must never fall through to "allowed".
    return refuse(res);
  }

  if (!ok) return refuse(res);
  return next();
}

module.exports = { requireProofOwnership, nonceCache };
