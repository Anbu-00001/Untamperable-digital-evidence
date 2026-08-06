# ADR-0007 — Proof of possession for reading a stored package

**Status:** Accepted · **Date:** 2026-08-06 · **Phase:** 8
**Related:** ADR-0004 (attestation), ADR-0006 §7 (verdict vs package disclosure)

## Context

Two endpoints hand back content rather than a verdict:

- `GET /proof/:eventId` — the full package, **including GPS coordinates**;
- `GET /proof/:eventId/media` — the **photograph**.

Until now the only thing in front of either was knowing a UUIDv4. The code
already acknowledged the sensitivity — `routes/proof.js` carries a comment
separating this from the public `/verify` route, citing ADR-0006 §7 — but nothing
enforced it.

An unguessable identifier is a **capability, not an authorisation**. It cannot be
revoked, it is disclosed permanently the first time it appears in a server log, a
`Referer` header, a screenshot or a QR code, and whoever holds it holds it
forever. For a system whose output is meant to be evidence, "the URL is long" is
not an access-control story.

## Decision

Gate both endpoints on **proof of possession of the key that signed the
package**.

Every package is signed by a per-install ECDSA P-256 key generated in the Android
Keystore and marked non-exportable — the device can sign with it, and nothing,
including the app itself, can read it out. So the question "are you the device
that produced this record?" is already answerable with material the system has.
The caller signs a fresh challenge; the server verifies it against the public key
already stored inside the package.

The public key travelling inside the requested package is not a circularity: the
legitimate device holds its own private key and needs nothing from the server,
while an attacker holding the package has the public key and still cannot sign.

**What stays open, deliberately:**

- `GET /verify/:eventId` — a verdict, no coordinates, no media. Anyone being able
  to check a claim independently is the entire point of the system; gating it
  would defeat the design (ADR-0006 §7). A test asserts the public verdict
  contains no coordinates.
- `POST /proof` and `POST /proof/:eventId/media` — already constrained by
  construction. A package must be schema-valid, and media is refused unless it
  hashes to the digest a signed package already commits to, so an
  unauthenticated write cannot introduce anything no signature vouches for.
  Gating writes would also break the Android sync worker, which signs *packages*,
  not *requests*.

## Relationship to RFC 9421, stated exactly

The signature base follows RFC 9421 §2.5 **in shape**: newline-joined,
lower-cased, quoted component identifiers covering `@method` and `@path` so a
signature cannot be lifted onto a different request or a different event.

It is **not a compliant RFC 9421 implementation and does not claim to be**. There
is no `Signature-Input` header, no structured-field parameter serialisation, and
no general derived-component support. Naming a subset after the RFC would be the
same category of overclaim this project refuses elsewhere.

One RFC detail *is* honoured, because getting it wrong fails silently:

> **RFC 9421 §3.3.4 requires ECDSA signatures as fixed-width `r || s`** (64 bytes
> for P-256), **not ASN.1/DER.** Android's `Signature.getInstance("SHA256withECDSA")`
> and Node's `crypto` both emit DER by default. A client following either
> naively produces a well-formed signature that a spec-compliant verifier
> rejects — and the failure looks like a bad key, not an encoding mismatch.

Rather than force one convention, the verifier **accepts both**. They encode the
same `(r, s)` pair, so accepting both costs nothing in security and removes the
entire failure class. Tests cover each encoding, plus the two ways a hand-written
`r||s`→DER converter goes wrong: a component with its high bit set needs a
leading zero or it decodes as negative, and leading zeros must be stripped or the
INTEGER is non-minimal.

## Consequences

**Positive**
- The coordinates and the photograph are no longer served to anyone who learns an
  event ID.
- No new secret, no key distribution, no accounts: the authorisation key is the
  attestation key the device already has, in hardware.
- Breaks no first-party client — verified before enabling: the Android app only
  ever POSTs to `/proof`, and both e2e scripts read verdicts from `/verify`.

**Negative / accepted**
- **The nonce cache is per-process**, exactly like the rate limiter's counters
  (and for the same reason). Consequence, bounded and stated: with N processes a
  captured `Authorization` header can be replayed at most N times inside the
  freshness window, rather than indefinitely. The **timestamp window**, not the
  nonce cache, is what bounds replay today. A shared store fixes both together.
- **No revocation.** A device that loses its key cannot disown past packages.
  Nothing in the design prevents adding that; nothing here implements it.
- **A stolen unlocked phone still authenticates**, because the Keystore key signs
  on the device's behalf. This authenticates a *device*, not a *person*.
- Every failure — bad signature, unknown event, stale timestamp — returns an
  identical 401. That is deliberate: distinguishing "no such event" would make
  the endpoint an existence oracle, which is most of what an enumerating attacker
  wanted. It does make debugging a genuine client harder, so `WWW-Authenticate`
  names the scheme and the error body spells out the required header.

## Alternatives considered

| Option | Why not |
|---|---|
| Bearer tokens / API keys | Needs issuance, storage and rotation the project has no home for, and a shared secret is weaker than a hardware-bound key it already has |
| Signed URLs with an expiry | Better than a bare UUID, but still a capability: whoever receives the link can pass it on within its lifetime |
| Nonce challenge endpoint (`GET /auth/challenge`) | Requires server-side per-challenge state before the caller is authenticated — a cheap denial-of-service surface. A client-chosen nonce plus a timestamp window gets the same replay protection without pre-auth state |
| Leave reads open, rely on the UUID | The status quo being replaced. Not revocable, leaks permanently, and the code's own comment already described the endpoint as sensitive |
