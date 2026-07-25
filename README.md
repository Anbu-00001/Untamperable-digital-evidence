# Reality Lock

**Tamper-Evident Event Proof System for Mobile Devices** — an Android app that captures a real-world event (photo/video + GPS + timestamp + motion sensors), cryptographically hashes and signs the bundle at the moment of capture with a hardware-backed key, and produces a **tamper-evident proof package** that any later modification is detectable against. A verification module recomputes and checks the package and reports an authenticity verdict.

> Course project — Mobile Application Development / Embedded Programming, Dept. of CSE.
> Team: Rakesh S, Anbuchelvan Ganesan.

## Repository layout
| Path | What it is |
|---|---|
| [`android/`](android/) | Android app (Kotlin, MVVM, Compose). Capture pipeline complete and verified on hardware; cryptographic core lands in Phase 3. |
| [`backend/`](backend/) | Node.js + Express verification/storage service. Schema-validating `/proof` and `/verify` work today; crypto verification lands in Phase 5. |
| [`docs/design/`](docs/design/) | The **Proof Package** schema + spec, example instance, and Architecture Decision Records. |
| [`docs/evidence/`](docs/evidence/) | Real proof sidecars pulled off a physical device, so the status claims below can be **checked, not trusted**. |
| [`research/`](research/) | The full research corpus (competitive landscape, crypto architecture, tech stack, legal, literature) + the phased plan. **Start with [`research/README.md`](research/README.md).** |
| [`SETUP.md`](SETUP.md) | How to build/run each part + the manual cloud-account steps. |

## Status

> "Complete" below means **the phase's own exit criteria in
> [`research/09_PROJECT_PHASES.md`](research/09_PROJECT_PHASES.md) are met** — not
> merely that code exists. Where they are not met, this says so.

### Phase 0 (Foundations) — code complete; three cloud accounts outstanding
- Done: Android project + git, dependency baseline, backend, and **deployment
  plumbing solved** — [`render.yaml`](render.yaml) Blueprint and a root
  [`Dockerfile`](Dockerfile), both verified by building the image and serving
  `/health` and `/proof` from the running container.
- **Outstanding — needs account access, not code:** pressing *deploy* to get a
  public health-check URL; creating the **Firebase** project (Firestore/Storage/
  Auth); creating the **Play Console + GCP** entry that enables Play Integrity.
  Steps are in [`SETUP.md`](SETUP.md) §1.1, §3, §4. Only the last blocks work —
  it gates the Play Integrity task in Phase 3.

### Phase 1 (Design) — complete
- Android scaffold: version catalog (every version centralized — nothing hardcoded), layered config (`gradle.properties` → `local.properties` → typed `BuildConfig` → `AppConfig`), centralized `CryptoConfig`/`ProofPackageConstants`.
- Backend: env-driven config, `/health`, schema-validating `/proof`, per-check `/verify` — **smoke-tested live**.
- **Proof Package v1.0.0**: [schema](docs/design/proof-package.schema.json) + [spec](docs/design/PROOF_PACKAGE_SPEC.md), **machine-validated** (`cd backend && npm run validate:schema`).
- ADRs: [0001](docs/design/adr/ADR-0001-merkle-tree-leaves.md) (2-leaf now, 5-leaf designed-for), [0002](docs/design/adr/ADR-0002-timestamping-strategy.md) (OpenTimestamps-first).
- Built, installed and launched on a **OnePlus CPH2591 (Android 15)**.

### Phase 2 (Core Capture Pipeline) — complete, verified end-to-end on a physical device
- **CameraX capture** (in-memory, `CAPTURE_MODE_MINIMIZE_LATENCY`) — no gallery-import path exists by design, closing the "sign a pre-tampered file" hole.
- **Clock correlation** (`ClockCorrelator`) reconciling the monotonic capture instant with wall-clock time — **verified in production output**: `elapsedRealtimeNanos/1e6 + offset` reproduced the recorded `wallClockMillis` exactly.
- **Sensor binding** that selects the motion sample nearest the shutter (shared monotonic clock base) and **rejects samples beyond a 500 ms tolerance** rather than attaching misleading data.
- **Location** via `FusedLocationProviderClient.getCurrentLocation`, bounded by a timeout; when unavailable it is recorded as absent, never guessed.
- **JSON sidecar store** ([ADR-0003](docs/design/adr/ADR-0003-local-event-store.md)) — `<eventId>.jpg` + `<eventId>.json`, mirroring ProofMode's model.
- **Itemized permission consent** (camera required / location optional, separately explained) per the DPDP obligations in `research/06`.
- **48 unit tests passing**, including schema conformance validated against [the real schema file](docs/design/proof-package.schema.json) rather than a hand-copied field list.
- Verified on a **OnePlus CPH2591 (Android 15)** with a live GNSS fix — evidence in [`docs/evidence/`](docs/evidence/).

**Two real defects, both found by running the thing, both fixed:**

1. **Camera clock base** — captures were stamped **9.66 days in the past**. `SensorEvent.timestamp` uses `CLOCK_BOOTTIME`, but this device's camera declares `SENSOR_INFO_TIMESTAMP_SOURCE = UNKNOWN` (`CLOCK_MONOTONIC`), which pauses during deep sleep. The timestamp source is now queried per camera and normalised. After the fix the recorded instant sits **0.24 s** from the shutter, and motion — which had never once populated on this device — now binds **1.49 ms** from the capture. See [`docs/evidence/`](docs/evidence/).
2. **Producer/schema divergence** — the serializer emitted three shapes the shared schema rejects (a `mediaFilePath` the schema forbids, `location: null` against a non-nullable field, `gyroscope: []` against `minItems: 3`). The test that claimed to guard this only checked key presence and never loaded the schema, so it passed throughout.

**Motion skew tolerance** (added earlier, when samples were binding 4595 ms from the shutter) is what kept defect 1 from silently producing plausible-looking motion data: it rejected the mismatched samples and recorded `null` instead.

**Not yet verified:** behaviour on a device whose camera reports `TIMESTAMP_SOURCE_REALTIME` — that branch is unit-tested but has not run on such hardware.

### Phase 3 (Cryptographic Core) — complete, verified on a physical device
- **Every capture is now a fully-formed, signed proof package**: SHA-256 media leaf (streamed) → RFC 8785 canonical metadata leaf → 2-leaf Merkle root → ECDSA P-256 signature from a key generated inside the Android Keystore.
- **Hardware key attestation, not Play Integrity** ([ADR-0004](docs/design/adr/ADR-0004-attestation-strategy.md)) — a deliberate deviation from `research/08` #16 that costs **$0** instead of $25 and certifies the claim the package actually makes. Proven on the device: `tier=TRUSTED_ENVIRONMENT`, 4-certificate chain whose root SHA-256 **exactly matches** one published at `android.googleapis.com/attestation/root`.
- **Backend `/verify` performs real cryptography**: media leaf, metadata leaf, Merkle root, ECDSA signature, attestation chain linkage, and `attestationKeyBinding` — the check that the attested key *is* the signing key, without which a genuine chain could be stapled onto someone else's package.
- **74 tests** (63 Android + 11 backend), including a **cross-implementation Merkle vector** asserted identically in Kotlin and Node so the two can never silently drift.

**Tamper detection, demonstrated end-to-end:**
```
1. GENUINE package + genuine media   → all crypto checks pass
2. ONE BIT flipped in the JPEG       → verdict failed, mediaHashMatch fail
3. Latitude altered in metadata      → verdict failed, metadataHashMatch fail
4. Media not supplied                → mediaHashMatch unavailable (never "pass")
```
A backend test also covers the subtle case: an attacker who edits metadata **and** recomputes the leaf and root so the tree is internally consistent still fails `signatureValid` — which is exactly why the root is signed.

**Honest limits:** the verdict is `incomplete`, never `verified`, while `timestampPlausible`/`locationPlausible` remain Phase 4/5 — a passing package is not allowed to overclaim. Motion binding is usually 1–4 ms from the shutter but **intermittently reaches ~270–490 ms**; the 500 ms guard keeps it truthful and the exact offset travels in the package. Key attestation also does not prove the *running app* is unmodified, unlike Play Integrity's device-integrity verdict.

### Phase 4 (Location Integrity + Explainable Authenticity Heuristic) — complete, verified on device
- **Location integrity** ([ADR-0005](docs/design/adr/ADR-0005-phase4-integrity-and-forensics-scope.md)): `isMock()` plus a speed/distance "teleportation" check (Haversine, **>1500 km/h** with jitter guards — the plan's 300 km/h would have falsely flagged ordinary air travel). Written to the proof package's advisory `integrity.location` block.
- **The mock 4-check pattern was *not* built** — research showed 3 of its 4 checks are dead code for a normal app on API 35 (the AppOps scan needs a privileged permission; the `Settings.Secure` flag has read 0 since API 23). Shipping non-functional code as a security feature would be dishonest.
- **GNSS raw** is a capability probe only (surfaced on the Device screen); C/N0-AGC spoofing analysis is honestly scoped as future work.
- **Explainable Authenticity Heuristic** — a separate **Analyze** tab: pick any candidate image → ELA heat-map + EXIF-consistency flags. It produces a *report*, never a proof package, and never signs or stores anything, so the "no gallery import into the proof flow" rule stays intact.
- **Labelled as triage, never a verdict** — the screen leads with a disclaimer and attaches no real/fake score, because ELA is well-documented as unreliable if overclaimed (Farid: it mislabels real and altered images "with the same likelihood").

**Exit criteria proven on the physical CPH2591** via 3 instrumented tests (the project's first `androidTest`), using the real Android JPEG encoder and `androidx.exifinterface`:
```
PASS  ela_highlights_the_spliced_region        (spliced seam > 1.5× background)
PASS  exif_flags_an_image_edited_in_photoshop  (EDITOR_SOFTWARE fires)
PASS  ela_analyzer_produces_a_heatmap_of_matching_size
```
Evidence — including the ELA heat-map lighting up a known splice — in [docs/evidence/phase4-forensics/](docs/evidence/phase4-forensics/). **86 unit tests + 3 instrumented.**

**Next:** Phase 5 — backend sync, storage, and the full verification module.

## Quick start
```bash
# Backend (fully runnable now)
cd backend && npm install && npm run validate:schema && npm run dev

# Android
# Open the android/ folder in Android Studio and let it sync (see SETUP.md).
```

## End-to-end test
One command drives the whole system — unit tests, schema, a live backend, real
captures on an attached phone, then the pulled sidecars back through the
backend's `/proof` and `/verify`:
```bash
./scripts/e2e/run_e2e.sh 3        # 3 captures
SKIP_DEVICE=1 ./scripts/e2e/run_e2e.sh   # no phone attached
```
It discovers the package name, a free port and the shutter button at run time —
nothing about the environment is hardcoded — and checks each capture against
both the shared schema **and** Phase 2's exit criteria (a document where every
optional field is null would still be schema-valid, so validity alone is not
enough). Last run on a OnePlus CPH2591: **11 passed, 0 failed**, motion bound
0.76 / 1.27 / 4.49 ms from the shutter.

## What this proves (and does not)
A passing proof package certifies the media+metadata bundle is **unaltered since capture and signed by a specific hardware-backed key**. It does **not** prove the depicted event was real/unstaged, and is **not** a standalone legal certificate. This honesty is by design — see [`docs/design/PROOF_PACKAGE_SPEC.md`](docs/design/PROOF_PACKAGE_SPEC.md) and `research/06_legal_standards_compliance.md` §7.
