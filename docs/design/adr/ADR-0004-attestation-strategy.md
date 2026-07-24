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
