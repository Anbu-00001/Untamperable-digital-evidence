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

## Status — Week 1 (Phase 0 Foundations + Phase 1 Design)
**Done and verified:**
- Android project scaffold: version catalog (every version centralized — nothing hardcoded), layered config (`gradle.properties` → `local.properties` → typed `BuildConfig` → `AppConfig`), centralized `CryptoConfig`/`ProofPackageConstants`, `DeviceCapabilities` probe, and a Foundation Status screen that proves the wiring.
- Backend scaffold: env-driven config, `/health`, schema-validating `/proof`, per-check `/verify` breakdown — **smoke-tested live** (valid package passes, out-of-range input is rejected with a precise error path).
- **Proof Package v1.0.0**: [JSON Schema](docs/design/proof-package.schema.json) + [spec](docs/design/PROOF_PACKAGE_SPEC.md) + example — **machine-validated** (`cd backend && npm run validate:schema`).
- Two design decisions ratified: [ADR-0001](docs/design/adr/ADR-0001-merkle-tree-leaves.md) (2-leaf now, 5-leaf designed-for) and [ADR-0002](docs/design/adr/ADR-0002-timestamping-strategy.md) (OpenTimestamps-first, RFC 3161 deferred).

**Known limitation of this pass:** the Android module was authored without a local Android SDK/Gradle, so its first compile happens in Android Studio; the version catalog is centralized so any first-sync version nudge is a one-line change (see [SETUP.md](SETUP.md) §2).

**Next:** Phase 2 — Core Capture Pipeline ([`research/09_PROJECT_PHASES.md`](research/09_PROJECT_PHASES.md)).

## Quick start
```bash
# Backend (fully runnable now)
cd backend && npm install && npm run validate:schema && npm run dev

# Android
# Open the android/ folder in Android Studio and let it sync (see SETUP.md).
```

## What this proves (and does not)
A passing proof package certifies the media+metadata bundle is **unaltered since capture and signed by a specific hardware-backed key**. It does **not** prove the depicted event was real/unstaged, and is **not** a standalone legal certificate. This honesty is by design — see [`docs/design/PROOF_PACKAGE_SPEC.md`](docs/design/PROOF_PACKAGE_SPEC.md) and `research/06_legal_standards_compliance.md` §7.
