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
`research/02` §8, Step 10, exposed as the `/verify` per-check breakdown.
Implemented in Phase 3: `schemaValid → mediaHashMatch → metadataHashMatch →
merkleRootMatch → signatureValid → attestationPresent → attestationChainValid →
attestationKeyBinding`. Deferred to Phase 4/5: `timestampPlausible`,
`locationPlausible`.

Three properties of the breakdown are deliberate:

- **`unavailable` is not `pass`.** A check that could not be run (e.g. the media
  bytes were not supplied) reports `unavailable`, and the overall verdict stays
  `incomplete`. Absence of evidence must never read as evidence.
- **`attestationKeyBinding` is load-bearing.** It asserts that the public key in
  `signature.publicKey` is the same key the attestation chain attests. Without
  it, a genuine chain could be stapled onto a package signed by a different key.
- **A failure names what failed.** "The media was altered" and "we could not
  reach the revocation list" are different statements and never collapse into
  one boolean.

### Request shapes
`POST /verify` accepts either a bare proof package, or an envelope
`{ "package": {...}, "mediaBase64": "..." }` when the media is available for the
media-leaf check. The media cannot travel *inside* the package: the schema sets
`additionalProperties: false` at the root, so an extra key would correctly make a
genuine package fail validation.

## Limitations (must ship with every verifier UI)
Per `research/02` §7 and `research/06` §7: a passing package proves the bundle of media+metadata is **unaltered since capture and signed by a specific (hardware-backed) key** — it does **not** prove the depicted event was real/unstaged, and it is **not** a standalone legal certificate (India's BSA 2023 §63 requires human dual-certification). The verdict wording must never overclaim.

## Change control
Any change to field semantics bumps `schemaVersion` (and `SCHEMA_URN`), updates this doc + the schema + the Android `ProofPackageConstants` together, and keeps `backend/npm run validate:schema` green.
