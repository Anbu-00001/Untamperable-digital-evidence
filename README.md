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

### Phase 0 (Foundations) — partially complete
- Done: Android project + git, full dependency baseline, local backend.
- **Not done:** the backend is **localhost-only** (no deployed health-check URL),
  and no **Firebase** project or **Play Console / GCP + Play Integrity** entry
  exists yet. Phase 0's exit criteria name all three. These are manual cloud
  steps ([`SETUP.md`](SETUP.md) §3–4); Play Integrity blocks part of Phase 3.

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

**Next:** Phase 3 — Cryptographic Core (SHA-256 → canonical metadata hash → Merkle root → hardware-backed ECDSA signature).

## Quick start
```bash
# Backend (fully runnable now)
cd backend && npm install && npm run validate:schema && npm run dev

# Android
# Open the android/ folder in Android Studio and let it sync (see SETUP.md).
```

## What this proves (and does not)
A passing proof package certifies the media+metadata bundle is **unaltered since capture and signed by a specific hardware-backed key**. It does **not** prove the depicted event was real/unstaged, and is **not** a standalone legal certificate. This honesty is by design — see [`docs/design/PROOF_PACKAGE_SPEC.md`](docs/design/PROOF_PACKAGE_SPEC.md) and `research/06_legal_standards_compliance.md` §7.
