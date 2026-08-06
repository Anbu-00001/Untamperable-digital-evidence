# ADR-0004 — Key Attestation as the primary integrity signal (Play Integrity deferred)

**Status:** Accepted · **Date:** 2026-07-25 · **Phase:** 3
**Supersedes:** the Play-Integrity-first position in `research/08_DECISIONS_REFERENCE.md` #16
**Related:** ADR-0001 (Merkle leaves), ADR-0003 (local event store)

## Context

`research/09_PROJECT_PHASES.md` lists a Play Integrity task in Phase 3: request a
verdict via `IntegrityManagerFactory.createStandard`, decode it server-side. That
plan has a hard prerequisite the research did not price: **Play Integrity requires
an app registered in Google Play Console, which requires a one-time US$25
developer registration.** There is no student waiver and no free tier that avoids
the account itself.

The question raised was whether Phase 3 can be completed without paying it.

## Decision

**Make Android Key Attestation the primary device-integrity signal.** Keep
`integrity.playIntegrityToken` in the schema as an optional, nullable field and
record Play Integrity as a Phase-7 stretch, to be implemented only if the project
is ever published to Play.

Concretely, Phase 3 requests an attestation challenge at signing-key generation
and ships the resulting certificate chain in
`signature.attestationCertificateChain`. The backend verifies that chain against
Google's published attestation roots.

> **Implementation status correction (2026-07-27) — the sentence above describes
> the decision, not the shipped code.**
>
> `backend/src/services/proofVerifier.js` verifies only that each certificate is
> signed by the next (`attestationChainValid`) and that the leaf's public key is
> the key that signed the package (`attestationKeyBinding`). **No root anchoring
> is implemented, and no root set is fetched or consulted anywhere in the
> service.** The one root match recorded in the probe below was performed by hand
> during Phase 3; it is evidence about a device, not a code path.
>
> Consequence, stated plainly: a self-issued CA and leaf over an ordinary software
> key produce a chain that links and binds correctly. Since 2026-07-27 the verifier
> reports this honestly rather than silently — `attestationRootTrusted` is a named
> check that is always `unavailable`, a one-certificate chain now `fail`s instead
> of vacuously passing, and an advisory states that hardware backing is not
> established. Closing the gap for real requires fetching Google's published roots
> and parsing the attestation extension (OID `1.3.6.1.4.1.11129.2.1.17`) for
> `securityLevel` and `verifiedBootState`. That work is not done.

### Root anchoring — implemented 2026-08-03

The correction above is now **partly superseded**: `attestationRootTrusted` is a
real check.

Google's two current roots are **pinned** in `backend/data/google-attestation-roots.pem`
(provenance in the file header; fetched from `https://android.googleapis.com/attestation/root`).
The correction said the fix "requires *fetching*" them — that was the wrong
conclusion from a correct observation. Roots do rotate and there is more than
one, but they are trust anchors: fetched at verify time they are only as
trustworthy as the fetch, and anyone able to answer for `android.googleapis.com`
could hand over their own root and make every forged chain verify. Pinning keeps
the trust decision in the diff and lets verification run offline.

The top certificate anchors either by **byte-identical DER match** to a pinned
root, or by being **directly issued by** one (chains do not always carry the
root). Matching on subject or serial was rejected: those are attacker-chosen, and
a test asserts that a self-signed certificate carrying the genuine root's exact
subject is still refused.

Evidence this works on real hardware, not just in tests: the CPH2591's
4-certificate chain ends in a certificate whose DER is byte-identical to Google's
pinned RSA-4096 root (`sha256 cedb1cb6…`), `openssl verify` walks the chain to it
successfully, and a package captured on the device now reports
`attestationRootTrusted: pass`. Unlike the Phase-3 hand check noted above, this
one is a code path with tests behind it.

### Revocation — implemented 2026-08-03

`attestationNotRevoked` checks every certificate in the chain (not just the leaf
— the list carries `CA_COMPROMISE` entries, and a compromised intermediate
invalidates everything beneath it) against
`https://android.googleapis.com/attestation/status`.

Unlike the roots this list is **fetched, not pinned**: revocation data is only
useful fresh, and a pinned snapshot would guarantee the failure it exists to
prevent. It is refreshed in the background against the endpoint's own
`Cache-Control: max-age=86400`, so `verifyProofPackage` stays synchronous instead
of awaiting a 170 KB download per request.

**It fails open by nature, and the code is built around that.** With no list, a
revoked certificate is indistinguishable from a clean one — so a missing, stale
(beyond `maxAgeMillis`), or never-fetched snapshot yields `unavailable`, never
`pass`, and raises an advisory saying the check did not run. A failed refresh
leaves the previous snapshot alone rather than discarding it.

#### The encoding trap

Google documents the key as "the certificate serial number in lowercase hex".
Node's `X509Certificate.serialNumber` is **uppercase**, so the documented lookup
matches nothing and every certificate reads as un-revoked.

The published list is also not uniformly hex. Of 1732 entries observed on
2026-08-03, **968 contain no `a`–`f` at all** — for hex strings of that length the
expected count is about 0.1, so those are decimal renderings. A spec-faithful
hex-only lookup would therefore miss **56% of the list**.

Every plausible rendering is probed (lowercase hex, with and without leading
zeros, and decimal). Verified against the live list: a hex-keyed entry
(`c35747a0…`) and a decimal-keyed entry (`6681152659205225093`, which Node would
report as `5CB838F1FE157A85`) are both found, each returning `KEY_COMPROMISE`.
The project's own device certificates appear on neither.

### The attestation extension — implemented 2026-08-03

`attestationSecurityLevel` parses the KeyDescription in the leaf certificate
(OID `1.3.6.1.4.1.11129.2.1.17`), so the device's claimed security level is read
rather than assumed. `pass` for TrustedEnvironment or StrongBox, **`fail` for
Software** — the device stating plainly that the key does not live in secure
hardware, while carrying a chain a reader will take as evidence that it does.
`unavailable` when the extension is absent or will not parse.

Verified Boot state, the bootloader lock and the attestation version are parsed
and reported in the notes but deliberately **do not gate the verdict**: they
describe the OS the device was running, not where the key lives, and a genuine
capture from an unlocked device is still a genuine capture.

#### Why the DER parser is hand-written

Node's `crypto.X509Certificate` has no accessor for a custom extension, so the
options were an ASN.1 dependency or a small parser. The dependency was rejected —
this same phase had to back out `opentimestamps` for pulling two unfixable
critical CVEs, and one fixed, well-documented structure is a poor reason to widen
the supply chain of the service that verifies evidence.

The parser is bounds-checked on every read, depth-limited, rejects indefinite
lengths (not valid DER) and unterminated high-tag-number forms, and **throws on
anything malformed** so the caller reports `unavailable`. A fabricated
"StrongBox" would be far worse than no answer. Ten tests cover this, most of them
asserting a refusal.

Two details worth recording because they are easy to get wrong:

- `rootOfTrust` is `[704] EXPLICIT`, well above the 30 that the short tag form
  holds, so the high-tag-number form is mandatory — `BF 85 40`.
- It is read **only** from `hardwareEnforced`. The same tag can appear in
  `softwareEnforced`, where it is whatever the OS asserted — exactly the claim a
  compromised OS would forge.

Verified on the real device: the parser returns `attestationVersion 300`,
`securityLevel TrustedEnvironment`, `deviceLocked true`, `verifiedBootState
Verified`, matching `openssl asn1parse` field for field.

### Attestation status, complete

All six checks now run, and a package captured on the project's device passes
every one:

```
attestationPresent pass · attestationChainValid pass · attestationKeyBinding pass
attestationRootTrusted pass · attestationNotRevoked pass · attestationSecurityLevel pass
notes: attested security level: TrustedEnvironment (attestation version 300)
       verified boot state: Verified, bootloader locked
```

The original correction at the top of this section — "no root anchoring is
implemented, and no root set is fetched or consulted anywhere" — is now fully
superseded. What remains unproven is stated in `verdictLimitations` and nowhere
contradicted: revocation reports `unavailable` rather than `pass` when the list
cannot be fetched, and boot state does not gate the verdict.

The shipped `verdictLimitations` says exactly this, so no consumer of a verdict is
told more than the above supports.

### The predicted forgery shape, observed for real — 2026-08-06

The correction at the top of this section warned, before any of the above was
built: *"a self-issued CA and leaf over an ordinary software key produce a chain
that links and binds correctly."* That was a reasoned prediction, not yet an
observation. Scenario 5 of `docs/design/PHASE6_SECURITY_VALIDATION.md` has since
produced exactly that shape from a real Android build: a `google_apis_playstore`
x86_64 emulator (android-36.1) generates a genuine 3-certificate chain rooted in
a self-signed **"Droid Unregistered Device CA / Google Test LLC"** certificate —
not a device forging Google's identity, but the AVD's own stand-in root, which
serves the same test: it is not one of the two pinned production roots. The chain
links and binds exactly as predicted (`attestationChainValid: pass`,
`attestationKeyBinding: pass`), and is still caught, independently, by
`attestationRootTrusted: fail` (DER does not match a pinned root) and by
`attestationSecurityLevel: fail` (the extension itself reports `Software`). Full
per-check output and how the capture was driven are in the PHASE6 doc.

## Rationale

### 1. It certifies the claim the proof package actually makes
The package asserts: *this media+metadata bundle was signed by a key that
provably lives in this device's secure hardware.* Key attestation certifies
exactly that — key residency, security level, and Verified Boot state. Play
Integrity certifies something different and partly irrelevant here: that the app
binary came from the Play Store.

### 2. Play Integrity would not even work for this project's build
Its verdict includes a **Play Store delivery** check. A sideloaded debug APK —
what a college demo runs — is not Play-recognised, so paying the $25 would still
not yield a clean verdict without uploading to an internal testing track and
installing from Play on every iteration. We would pay for friction and a signal
that does not serve the threat model.

### 3. Verification is offline and durable
An attestation chain is verified against Google's published roots. No per-request
call to Google, no API key, no quota. This matters more than convenience: the
project's own research (`research/01`) records **Serelay dissolving in March
2025** as a cautionary tale about server-dependent verification. A Play Integrity
token cannot be re-verified if the service is withdrawn; a certificate chain
remains verifiable against archived roots years later. For evidence, that
durability is the point.

### 4. It is the stronger primitive, per independent sources
Play Integrity is *built on* hardware attestation. GrapheneOS's compatibility
guide describes hardware attestation as "a much stronger form of attestation than
the Play Integrity API"; Schertler's comparison (JKU Linz, 2024) reaches a
similar conclusion, noting the hardware API supports third-party operating
systems "while maintaining strong security guarantees through hardware-backed key
storage."

### 5. It partially covers app integrity anyway
The attestation record embeds `attestationApplicationId` — package name plus the
APK signing-certificate digest. A repackaged app signed with a different key
produces a different value, so key-to-app binding survives without Play.

## Verified before adopting

This decision was not taken on documentation alone. On the target device
(OnePlus CPH2591, Android 15):

```
first_api_level = 33      → attestation is a CTS-mandated feature (requires >25)
verifiedbootstate = green, flash.locked = 1
probe result: tier=TRUSTED_ENVIRONMENT, attested=true, 4-certificate chain
device root SHA-256 = cedb1cb6…800dfc  → exact match against a root published at
                                          https://android.googleapis.com/attestation/root
```

## Consequences

**Positive**
- Phase 3 costs **$0**; so does the remaining roadmap (Render free tier, Firebase
  free tier, OpenTimestamps).
- Verification needs no Google account at runtime.
- Stronger, more durable evidence than a Play Integrity verdict.

**Negative / accepted risks**
- **No app-runtime-integrity signal.** Key attestation does not detect a
  compromised *running* app the way Play Integrity's device-integrity verdict
  attempts to. This must be stated in the spec's Limitations section rather than
  papered over.
- **StrongBox is unavailable on the target device**, so attestation reports
  `TrustedEnvironment`. The package records the tier actually obtained; it must
  never imply the stronger one.
- **Remote Key Provisioning can fail.** Android 13+ mandates RKP, where
  attestation certificates are provisioned over the network and the pool can be
  exhausted (`ERROR_ATTESTATION_KEYS_UNAVAILABLE` = 16). Mitigation:
  `SigningKeyManager` classifies the failure via `KeyStoreException
  .isTransientFailure()` and falls back to an **unattested** key so a capture is
  never lost — recording that attestation is absent instead of implying it.
- **Roots rotate.** A new Google root began signing on 1 Feb 2026 and RKP devices
  moved to it exclusively on 10 Apr 2026. Google currently publishes two roots.
  The verifier therefore **fetches roots dynamically and must never pin one** —
  our own device chains to the older of the two, so pinning either would have
  been wrong.

## Alternatives considered

| Option | Why not |
|---|---|
| Pay $25, use Play Integrity as planned | Costs money for a signal that a sideloaded build cannot satisfy, and adds a runtime dependency on a service that may be withdrawn |
| SafetyNet Attestation | Shut down; fully retired May 2025 |
| Firebase App Check | Its Play Integrity provider has the same Play Console prerequisite |
| Both, attestation + Play Integrity | Reasonable end state, but Play Integrity adds cost and friction for no Phase-3 benefit; the schema already keeps the field optional so it can be added without a version bump |
