# ADR-0009 — An independent time anchor (RFC 3161)

**Status:** accepted, implemented 2026-08-07
**Supersedes in part:** ADR-0002's choice of OpenTimestamps
**Related:** ADR-0006 §5 (unavailable ≠ fail), `PHASE7_STRETCH_STATUS.md`

## Context

Every other claim a proof package makes is the device's account of itself. The
signature proves a Keystore key signed the Merkle root; the attestation chain
proves that key is hardware-backed. Neither says **when**, and
`metadata.timestamp.wallClockMillis` comes from a clock the holder of the phone
can set to anything.

`PHASE7_STRETCH_STATUS.md` recorded this as the unmade claim:

> the annexure states a hash value and algorithm **from this project's own Merkle
> root**. That is not an independent time anchor. A third party still has only
> the device's word for *when* a capture happened.

ADR-0002 chose OpenTimestamps to close this. Phase 7 built it and retracted it:
`opentimestamps@0.4.9` carries 11 advisories including two critical, through
`request`, deprecated since 2020, with no patched release. Two unfixable
criticals inside the service that verifies evidence was a bad trade.

## Decision

Anchor the Merkle root with **RFC 3161** timestamp tokens, obtained by the
backend at ingest and stored beside the package.

### Why RFC 3161 rather than retrying OpenTimestamps or a chain

It is a request/response protocol over plain HTTP against public authorities, so
the whole feature is one outbound POST and a signature check. There is no
calendar to poll, no "pending vs confirmed" state to explain, and no wallet to
fund — the three things that make OpenTimestamps and Polygon Amoy expensive.

Crucially it also has **third-party tooling that already exists**. A token this
system stores verifies with a command a forensic examiner already knows:

```
openssl ts -verify -digest <merkle.root> -in token.tsr -CAfile <ca-bundle>
```

An anchor that only this project's code can check is not independent
verification; it is this project's word a second time.

### The dependency was measured before it was adopted

The lesson from OpenTimestamps was that a "first-class package" claim needs
checking. `pkijs` was installed into a scratch project and audited before any
code was written against it:

| | `opentimestamps@0.4.9` | `pkijs@3.4.0` |
|---|---|---|
| advisories | 11, incl. 2 critical | **0** |
| tree | pulls deprecated `request` | 14 packages, 3.8 MB |
| deps | transitive, unmaintained | author's own ASN.1 utils + `@noble/hashes` |

## What the anchor does and does not prove

Stated precisely, because this is the easiest overclaim in the project:

- **It proves an upper bound.** A TSA will not sign a digest it has not been
  shown, so the root existed no later than `genTime`. Backdating a capture to
  before an anchored event is not possible without the TSA's key.
- **It does not prove the capture time.** Nothing stops someone capturing today
  and requesting a token next week. The anchor bounds the root's age from one
  side only.

That asymmetry is implemented literally. `captureTimeNotAfterAnchor` fails only
when the device claims a capture time *later* than the anchor — a genuine
contradiction, since a capture cannot postdate proof that its own root already
existed. A capture claimed long *before* its anchor passes without comment,
because a phone that was offline for a month is the normal case for this system,
not a suspicious one.

This is the only check in Reality Lock where a device clock claim can be
contradicted by evidence rather than merely doubted.

## Consequences and the traps found on the way

**The imprint is the root itself, not a hash of it.** `pkijs.MessageImprint
.create()` hashes its input. Our root is already a SHA-256 digest, so the obvious
call would have timestamped `SHA-256(root)` — a value appearing nowhere in the
proof package. The token would have been cryptographically perfect and useless:
`openssl ts -verify -digest <root>` would report FAILED, and a third party would
have to be told to hash the root again first. A test asserts the raw root bytes
appear in the DER request.

**The digest algorithm is read from `SignerInfo`, never assumed.** An earlier
draft hardcoded SHA-256 and rejected valid Certum and GlobalSign tokens that
openssl accepts. Four authorities' real tokens are checked in the suite for
exactly this reason; one vendor would have hidden the disagreement.

**`pkijs.SignedData.verify()` is deliberately not used.** It special-cases
`id-ct-TSTInfo` by hashing a supplied pre-image against the message imprint,
which assumes the caller holds the original data. We timestamp a digest, so that
path always fails. The CMS checks are performed directly — and that turned out to
be necessary anyway, because pkijs does not check the **RFC 3161 §2.3
timeStamping EKU rule** at all: the TSA certificate must carry
`id-kp-timeStamping`, it must be the only key purpose, and the extension must be
critical. Without that check, a certificate issued for TLS could mint timestamps.

**Chain validity is judged at `genTime`, not at "now".** A token signed by a
certificate that has since expired was valid when issued, which is precisely the
situation old evidence will be in.

**Trust anchors are Node's bundled Mozilla set, not a pinned file** — the
opposite of the choice in ADR-0004 for the Google attestation roots, and
deliberately so. Those are a closed set of four keys that must never be silently
widened, so pinning *is* the security property. TSA certificates are ordinary
WebPKI certificates that rotate on their own schedule; DigiCert's responder
already rolled over in 2025. A pinned TSA root would go stale and fail closed on
a working token.

That choice has a real consequence worth recording: **freetsa.org is unusable
here.** It is the most widely recommended free TSA, and it does not chain to any
public root — probed 2026-08-07, "No valid certificate paths found". Using it
would mean pinning a self-signed CA. `timestamp.acs.microsoft.com` fails the same
way. Five of eight probed authorities are usable; `backend/scripts/ops/probe_tsa.js`
re-checks any candidate.

**Anchors are stored beside packages, never inside them.** A stored package is
immutable — that rule is what stops anyone with network access rewriting stored
evidence. An anchor necessarily arrives *after* the package, so merging it in
would mean breaking immutability or blocking ingest on a third-party server.
First-write-wins, because the earliest token bounds the root's age most tightly;
a later one could only weaken the record.

**The package schema is unchanged.** `anchors.openTimestamps` and `anchors.chain`
remain untouched and unpopulated. Adding an `anchors.rfc3161` slot that nothing
ever writes would misdescribe where the token actually lives.

**Failure is never a verdict.** If the TSA is unreachable the package is stored
anyway and both checks report `unavailable`. A timestamp authority being down is
not evidence about a capture (ADR-0006 §5). Anchoring is off by default in
config and on in `render.yaml`, so importing the config in a test does not
acquire a dependency on a third party being up.

**The token endpoint is public.** `GET /verify/:eventId/timestamp` returns the
raw DER, mounted under `/verify` rather than the authenticated `/proof` because
it discloses a digest and a time — both already published by
`GET /verify/:eventId` — and no coordinates. An anchor a third party cannot fetch
is not independent.

## Verification performed

- 17 unit tests, no network: four authorities' real tokens verify; a wrong
  digest, a flipped signature byte, an unparseable token, and an empty trust
  store each fail with the correct distinct reason.
- Full suite 167 tests green (was 150).
- End-to-end on a live server with anchoring enabled: `POST /proof` → 201 in
  1.06 s including the TSA round trip → `GET /verify/:eventId` reports
  `timestampAnchorValid: pass` → token fetched from
  `GET /verify/:eventId/timestamp` → **`openssl ts -verify` returns
  `Verification: OK`**.

## What this still does not give

An independent bound on *when*, not on *what* or *where*. The anchor says
nothing about whether the depicted event was real, and it is not a legal
certificate — the limitations printed with every verdict are unchanged.
