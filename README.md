# Reality Lock

**Tamper-Evident Event Proof System for Mobile Devices** — an Android app that captures a real-world event (photo/video + GPS + timestamp + motion sensors), cryptographically hashes and signs the bundle at the moment of capture with a hardware-backed key, and produces a **tamper-evident proof package** that any later modification is detectable against. A verification module recomputes and checks the package and reports an authenticity verdict.

> Course project — Mobile Application Development / Embedded Programming, Dept. of CSE.
> Team: Rakesh S, Anbuchelvan Ganesan.

## Repository layout
| Path | What it is |
|---|---|
| [`android/`](android/) | Android app (Kotlin, MVVM, Compose). Phase-1 foundation skeleton — capture pipeline lands in Phase 2. |
| [`backend/`](backend/) | Node.js + Express verification/storage service. Schema-validating `/proof` and `/verify` work today; crypto verification lands in Phase 5. |
| [`docs/design/`](docs/design/) | The **Proof Package** schema + spec, example instance, and Architecture Decision Records. |
| [`research/`](research/) | The full research corpus (competitive landscape, crypto architecture, tech stack, legal, literature) + the phased plan. **Start with [`research/README.md`](research/README.md).** |
| [`SETUP.md`](SETUP.md) | How to build/run each part + the manual cloud-account steps. |

## Status

### Phase 0–1 (Foundations + Design) — complete, verified on a physical device
- Android scaffold: version catalog (every version centralized — nothing hardcoded), layered config (`gradle.properties` → `local.properties` → typed `BuildConfig` → `AppConfig`), centralized `CryptoConfig`/`ProofPackageConstants`.
- Backend: env-driven config, `/health`, schema-validating `/proof`, per-check `/verify` — **smoke-tested live**.
- **Proof Package v1.0.0**: [schema](docs/design/proof-package.schema.json) + [spec](docs/design/PROOF_PACKAGE_SPEC.md), **machine-validated** (`cd backend && npm run validate:schema`).
- ADRs: [0001](docs/design/adr/ADR-0001-merkle-tree-leaves.md) (2-leaf now, 5-leaf designed-for), [0002](docs/design/adr/ADR-0002-timestamping-strategy.md) (OpenTimestamps-first).
- Built, installed and launched on a **OnePlus CPH2591 (Android 15)**.

### Phase 2 (Core Capture Pipeline) — complete, verified end-to-end on an emulator
- **CameraX capture** (in-memory, `CAPTURE_MODE_MINIMIZE_LATENCY`) — no gallery-import path exists by design, closing the "sign a pre-tampered file" hole.
- **Clock correlation** (`ClockCorrelator`) reconciling the monotonic capture instant with wall-clock time — **verified in production output**: `elapsedRealtimeNanos/1e6 + offset` reproduced the recorded `wallClockMillis` exactly.
- **Sensor binding** that selects the motion sample nearest the shutter (shared monotonic clock base) and **rejects samples beyond a 500 ms tolerance** rather than attaching misleading data.
- **Location** via `FusedLocationProviderClient.getCurrentLocation`, bounded by a timeout; when unavailable it is recorded as absent, never guessed.
- **JSON sidecar store** ([ADR-0003](docs/design/adr/ADR-0003-local-event-store.md)) — `<eventId>.jpg` + `<eventId>.json`, mirroring ProofMode's model.
- **Itemized permission consent** (camera required / location optional, separately explained) per the DPDP obligations in `research/06`.
- **24 unit tests passing**; a real capture produced a 60 KB JPEG plus a correct proof sidecar.

**Defect found and fixed by testing:** motion samples were being bound at **4595 ms** and **715 ms** from the shutter — a reading that far from the capture instant misrepresents it. Now rejected beyond tolerance and recorded as absent.

**Not yet verified:** the location-populated path (the headless emulator never supplies a GPS fix, so only the no-location branch was exercised). Worth confirming on a physical device.

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
