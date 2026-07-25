# Proof Package Specification — v1.0.0

**Status:** ratified for Phase 1 · **Schema:** [`proof-package.schema.json`](./proof-package.schema.json) · **Example:** [`examples/proof-package.example.json`](./examples/proof-package.example.json)

The Proof Package is the single artifact Reality Lock produces per captured event and the single contract shared between the **Android app (producer)** and the **backend verifier (consumer)**. The JSON Schema is authoritative and machine-checked (`cd backend && npm run validate:schema`); this document explains the *why* behind each field. It implements Slide 9's "Proof Package Creation" stage and the pipeline in `research/02_cryptography_security_architecture.md` §8.

## Design principles
1. **Offline-verifiable.** Everything needed to verify integrity/authenticity (hashes, signature, public key, optional attestation chain) travels inside the package — no server round-trip required (`research/01` §9 rec. 3, `08` decision #25).
2. **Bind media + context together.** The signature covers a Merkle root over *both* the media hash and the metadata hash, so swapping either is detected (`research/02` §1; closes ProofMode's documented gap).
3. **Canonicalize before hashing.** Metadata is serialized with RFC 8785 (JCS) so logically-identical objects always hash identically (`research/02` §1).
4. **State what it proves, precisely.** The package certifies *chain-of-custody integrity from capture*, not that the depicted event is real — see the Limitations section.

## Field reference

| Field | Required | Meaning / source |
|---|---|---|
| `schemaUrn` / `schemaVersion` | ✓ | Format identity; mirror of `ProofPackageConstants` on Android. |
| `eventId` | ✓ | Client UUID for the capture event. |
| `media.mimeType/byteLength/sha256` | ✓ | SHA-256 over the raw media bytes (`research/02` §8 Step 2). |
| `media.storageRef` | — | Cloud object reference; `null` on-device until Phase 5 sync. |
| `metadata.location` | ✓ | GPS lat/lon/accuracy, provider, `fixAgeMillis`, and `isMock` (`research/02` §6, `03` §3). |
| `metadata.timestamp` | ✓ | Both `wallClockMillis`/`iso8601` (human/legal) **and** monotonic `elapsedRealtimeNanos` + `wallClockOffsetMillis`, plus optional `gpsTimeMillis` cross-check (`research/03` §4). |
| `metadata.motion` | — | Accelerometer/gyroscope vec3 snapshots (`research/03` §4). Nullable (some devices lack a gyroscope). |
| `metadata.device` | ✓ | Locally-generated `installId` UUID (never IMEI/ANDROID_ID), model, manufacturer, SDK, app version (`research/03` §5). |
| `canonicalization` | ✓ | Always `"RFC8785"`. |
| `merkle.algorithm/scheme/leaves/root` | ✓ | `scheme` is `2-leaf` (v1) or `5-leaf` (target) — see ADR-0001. `root` is what gets signed. |
| `signature.algorithm/value/publicKey` | ✓ | ECDSA P-256 (`SHA256withECDSA`) over the root; public key in X.509, curve `secp256r1`. |
| `signature.attestationCertificateChain` | — | Android Key Attestation chain proving hardware-backing (`research/02` §2). |
| `integrity.playIntegrityToken` | — | Encrypted Play Integrity token, decoded server-side only (`research/02` §3). |
| `integrity.location` | — | Mock-detection results, GNSS-checked flag, speed plausibility (`research/02` §6). |
| `anchors.openTimestamps` / `anchors.chain` | — | Optional external anchors — Phase 7 (`research/02` §4–5). |

## Construction order (producer, Android)
`research/02` §8, Steps 2–7:
1. `media.sha256 = SHA-256(mediaBytes)` (streamed), lowercase hex.
2. Build metadata object → `JCS` canonicalize → `metadataHash = SHA-256(canonical)`.
3. `merkle.root = SHA-256(rawBytes(mediaHash) ‖ rawBytes(metadataHash))` (2-leaf).
4. `signature.value = ECDSA_sign(AndroidKeystoreKey, rawBytes(merkle.root))`.
5. Attach `publicKey` (+ attestation chain), `integrity`, then assemble the JSON.

### The concatenation and signing inputs are byte-exact
Steps 3–4 are the only places where producer and verifier can silently disagree,
so they are stated without ambiguity:

- **`‖` joins the two raw 32-byte digests, not their hex renderings**, in the
  fixed order `[media, metadata]`. Never sorted, never length-prefixed. Hashing
  the concatenated *hex text* yields a different root and is wrong.
- **The signature covers the raw 32 bytes of `merkle.root`**, which
  `SHA256withECDSA` then hashes internally. Signing the hex string is wrong.

A verifier fed the wrong interpretation computes a well-formed but incorrect
value and reports a failure indistinguishable from tampering. To make drift
impossible rather than merely discouraged, both implementations assert one fixed
vector — `MerkleTree.CROSS_IMPL_TEST_VECTOR` in Kotlin and the same constants in
`backend/test/proofVerifier.test.js`:

```
media    = SHA-256("reality-lock-media-test-vector")    = 0c865511…6a942fea
metadata = SHA-256("reality-lock-metadata-test-vector") = 695cadf1…fdabb391
root                                                     = 63e7fd2d…6c792270
```

## Verification order (consumer, backend)
`research/02` §8, Step 10, exposed as the `/verify` per-check breakdown. **All
five checks are implemented** as of Phase 5:

`schemaValid → mediaHashMatch → metadataHashMatch → merkleRootMatch →
signatureValid → attestationPresent → attestationChainValid →
attestationKeyBinding → timestampPlausible → locationPlausible`

Four properties of the breakdown are deliberate:

- **`unavailable` is not `pass`.** A check that could not be run reports
  `unavailable`. Absence of evidence must never read as evidence — in either
  direction, which is why a *missing* attestation chain is `unavailable` with an
  advisory rather than `fail`.
- **`attestationKeyBinding` is load-bearing.** It asserts that the public key in
  `signature.publicKey` is the same key the attestation chain attests. Without
  it, a genuine chain could be stapled onto a package signed by a different key.
- **A failure names what failed.** "The media was altered" and "we could not
  reach the revocation list" are different statements and never collapse into
  one boolean.
- **Non-blocking findings are `advisories`.** A mock-provider location, or a
  location that could not be cross-checked, is reported without condemning the
  package. See the verdict rules below.

### The two plausibility checks (Phase 5)
- **`timestampPlausible`** is decisive, and only fails on the genuinely
  impossible. Three sub-checks: the producer's own derivation identity
  `wallClockMillis == floor(elapsedRealtimeNanos / 1e6) + wallClockOffsetMillis`
  (an **exact** integer identity, not a tolerance); `iso8601` denoting the same
  instant as `wallClockMillis`; and the capture not claiming to be in the
  verifier's future beyond a configured skew. There is deliberately **no**
  "captures cannot predate date X" rule — that would be an arbitrary literal
  posing as a security property.
- **`locationPlausible`** recomputes the Haversine implied speed between this
  event and the previous stored event **from the same `installId`**, against
  `1500 km/h` with 1 s / 50 m jitter guards (ADR-0005 §2). It needs stored
  history, so a first-ever capture is `unavailable`, never a pass. This is the
  authoritative answer; the device's own unsigned `integrity.location.speedPlausible`
  is advisory, and a disagreement is surfaced rather than reconciled away.
- `gpsTimeMillis` vs the wall clock is **advisory only**: a fused/network provider
  often derives `getTime()` from the very system clock under examination, so
  agreement proves little and only a gross divergence is informative.

### Verdict rules (ADR-0006 §5)
Two rules, in order:
1. **`failed`** — *any* check returned `fail`. This deliberately reaches beyond
   the cryptographic checks: an attestation chain that is present but does not
   bind to the signing key, or a physically impossible implied speed, are real
   findings.
2. Otherwise every **decisive** check must have passed — `mediaHashMatch`,
   `metadataHashMatch`, `merkleRootMatch`, `signatureValid`,
   `timestampPlausible`. One that could not be run gives **`incomplete`** (most
   often `mediaHashMatch`, when no media was available). All passing gives
   **`verified`**.

### Request shapes
`POST /verify` accepts either a bare proof package, or an envelope
`{ "package": {...}, "mediaBase64": "..." }` when the media is available for the
media-leaf check. The media cannot travel *inside* the package: the schema sets
`additionalProperties: false` at the root, so an extra key would correctly make a
genuine package fail validation. **If no media is supplied, the verifier falls
back to its own stored copy**, so a synced package verifies fully without the
client re-uploading bytes the server already has.

`GET /verify/<eventId>` verifies a stored package and returns **only** the
verdict, checks, advisories and limitations — never the package. The `eventId` is
an unguessable v4 UUID acting as a capability token, but the package it names
contains GPS coordinates, and a scannable verification badge must not double as a
location leak (ADR-0006 §7).

## Storage (Phase 5)
`POST /proof` stores a package **immutably**: a byte-identical resubmission
succeeds idempotently (the sync worker retries and cannot always know whether an
earlier attempt landed), while a submission that would *change* a stored package
is a `409`. `POST /proof/<eventId>/media` takes raw bytes and accepts them **only
if they hash to the digest the signed package already commits to**, so the store
cannot be made to hold media that no validly-signed package vouches for. Media is
addressed by content, which also de-duplicates it.

## Limitations (must ship with every verifier UI)
Per `research/02` §7 and `research/06` §7: a passing package proves the bundle of media+metadata is **unaltered since capture and signed by a specific (hardware-backed) key** — it does **not** prove the depicted event was real/unstaged, and it is **not** a standalone legal certificate (India's BSA 2023 §63 requires human dual-certification). The verdict wording must never overclaim.

## Change control
Any change to field semantics bumps `schemaVersion` (and `SCHEMA_URN`), updates this doc + the schema + the Android `ProofPackageConstants` together, and keeps `backend/npm run validate:schema` green.
