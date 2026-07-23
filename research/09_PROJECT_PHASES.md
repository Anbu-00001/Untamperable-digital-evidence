# Reality Lock — Detailed Phased Project Plan

**Expands** the PPT's Slide 10 ("Project Planning": Phase 1 Research & Design, Phase 2 Development, Phase 3 Security Implementation, Phase 4 Testing & Deployment) into 8 concrete, buildable sub-phases, sized for a **2-person team over one semester** (assume ~14–16 teaching weeks). Every task below names the exact API/library to use — see `08_DECISIONS_REFERENCE.md` for the one-line "why" behind each choice and the source research file for full depth.

The PPT's original 4 phases still map cleanly onto this plan:
- PPT Phase 1 (Research & Design) → **Phase 0 + Phase 1** below (mostly already done by this research pass)
- PPT Phase 2 (Development) → **Phase 2** below
- PPT Phase 3 (Security Implementation) → **Phase 3 + Phase 4** below
- PPT Phase 4 (Testing & Deployment) → **Phase 6** below

Phases 5 and 7 are new, made explicit because the research surfaced real work items (backend/storage, blockchain "Future" line) the original 4-phase slide compressed into a single bullet each.

---

## Phase 0 — Foundations & Setup
**Duration:** ~1 week · **Maps to:** PPT Phase 1 (tooling half)

**Goal:** every account, repo, and empty-but-building project skeleton exists before any feature code is written.

| Task | Concrete detail |
|---|---|
| Android Studio project | Kotlin, min SDK 28 (API 28/Android 9 — required floor for StrongBox support per `02` §2), package `com.realitylock.app` (or final name) |
| Version control | Git repo, `.gitignore` for `local.properties`/`google-services.json`/keystore files |
| Firebase project | Create project in Firebase console; enable Firestore, Cloud Storage, Authentication; download `google-services.json` |
| Google Play Console entry | Internal testing track only (no public release needed) — required for Play Integrity API setup (`02` §3) |
| Google Cloud project | Link to Play Console app; enable Play Integrity API |
| Backend skeleton | Node.js + Express (per `03` §6 / `08` decision #15); deploy a "hello world" route to Render or Railway free tier early, so deployment plumbing is solved before it's a deadline crunch |
| Gradle dependency baseline | Add the full dependency list from `03_mobile_tech_stack.md` §10 up front (CameraX, play-services-location, Room, WorkManager, Retrofit/OkHttp, Firebase BoM, test libs) |

**Exit criteria:** empty Android app builds and runs on a device/emulator; empty Node backend responds to a health-check route from a deployed URL; Firebase console shows the project with Firestore/Storage/Auth enabled.

---

## Phase 1 — Design Finalization
**Duration:** ~1 week (runs partly in parallel with Phase 0) · **Maps to:** PPT Phase 1 (design half)

**Goal:** lock the architecture decisions on paper before writing feature code — this phase is largely **already complete**, produced by this research pass (`00`–`08` in this folder). The team's job in Phase 1 is to review and ratify it, not re-derive it.

| Task | Reference |
|---|---|
| Review and ratify the master decision table | `08_DECISIONS_REFERENCE.md` |
| Finalize the Proof Package schema (fields, JSON shape, which are 2-leaf vs 5-leaf Merkle) | `02_cryptography_security_architecture.md` §1, §8 |
| Draw the final architecture diagram (adapt the mermaid diagram already provided) | `03_mobile_tech_stack.md` — Recommended Stack diagram |
| Write/finalize the Literature Survey slide content for the next review | `01_domain_competitive_landscape.md`, `07_academic_literature_survey.md` |
| Decide 2-leaf vs 5-leaf Merkle tree scope for this semester (per `08` open item #5) | Team judgment call — recommend starting 2-leaf, documenting 5-leaf as designed-for |
| Decide RFC 3161 in-scope-or-not (per `08` open item #1) | Team judgment call — recommend deferring, OpenTimestamps only for now |

**Exit criteria:** a one-page internal design doc (can literally be `08_DECISIONS_REFERENCE.md` plus the two judgment calls above resolved) that both students have read and agree to build against.

---

## Phase 2 — Core Capture Pipeline
**Duration:** ~3 weeks · **Maps to:** PPT Phase 2 ("Android app development, Sensor & camera integration")

**Goal:** a working capture screen that produces a bundled, timestamped, geo-tagged, sensor-tagged event record — no crypto yet, just correct, correlated data capture.

| Task | Exact API/decision |
|---|---|
| Capture screen UI (MVVM) | `ViewModel` + `StateFlow`, per `03` §1 |
| Camera capture | CameraX `ImageCapture`, `CAPTURE_MODE_MINIMIZE_LATENCY`, `OnImageCapturedCallback` → `ImageProxy.imageInfo.timestamp`, per `03` §2 |
| **Block gallery import** — in-app capture only | Design decision from `01` §9 rec. 9 / `08` #27 — no "pick from gallery" path into the proof flow, ever |
| Location | `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY)`, kept warm before shutter press, per `03` §3 |
| Motion sensors | `SensorManager`, `TYPE_ACCELEROMETER` + `TYPE_GYROSCOPE`, `SENSOR_DELAY_GAME`, rolling buffer, per `03` §4 |
| Timestamp correlation | Implement the `elapsedRealtimeNanos()` ↔ `currentTimeMillis()` offset function from `03` §4 — this is the single trickiest correctness detail in this phase, budget real time for it |
| Device identity | Generate + persist install UUID at first launch (`03` §5) |
| Local queue | Room `ProofPackageEntity` (unsigned at this stage — signing comes in Phase 3) |
| History/queue screen | Observe the Room table as `Flow<List<ProofPackageEntity>>` |

**Exit criteria:** pressing capture produces one Room row containing: JPEG file reference, GPS lat/lon/accuracy + fix age, accelerometer/gyroscope snapshot, both raw (`elapsedRealtimeNanos`) and derived wall-clock timestamps, install UUID, `Build.MODEL`. Demoable: show the queue screen listing several captured events with all fields populated and sane.

---

## Phase 3 — Cryptographic Core
**Duration:** ~2–3 weeks · **Maps to:** PPT Phase 3 ("Encryption, Digital signature")

**Goal:** every captured event is hashed, canonicalized, and signed with a hardware-backed key — implements Slide 9's "Security Engine" and "Proof Package Creation" stages.

| Task | Exact API/decision |
|---|---|
| Media hashing | `MessageDigest.getInstance("SHA-256")`, streamed via `DigestInputStream` (don't load whole video into RAM), per `02` §1, §8 Step 2 |
| Metadata canonicalization | `io.github.erdtman:java-json-canonicalization` (RFC 8785 JCS), per `02` §1, §8 Step 3 |
| Merkle root composition | `rootHash = SHA-256(mediaHash + metadataHash)` (2-leaf minimum per Phase 1 decision), per `02` §1, §8 Step 4 |
| Keystore key generation | `KeyGenParameterSpec` + `KeyProperties.KEY_ALGORITHM_EC`, `setIsStrongBoxBacked(true)` with `StrongBoxUnavailableException` fallback, per `02` §2 — **use the exact Kotlin code already written in `02` §2** |
| Signing | Google Tink `PublicKeySign` (preferred, per `05` §3 / `08` #19) or raw `Signature.getInstance("SHA256withECDSA")` — pick one and be consistent |
| Assemble Proof Package | Match the exact field list in `02` §8 Step 7 |
| Play Integrity (Standard flow) | `IntegrityManagerFactory.createStandard`, request hash = `SHA-256(rootHash + eventId)`, decode **server-side only** in the Node backend via `googleapis` npm package, per `02` §3 / `08` reconciliation table |

**Exit criteria:** a captured event produces a fully-formed, signed Proof Package; a unit test (Robolectric) proves that modifying any single byte of the media or any metadata field changes the root hash and invalidates the signature check. This is the single most demo-able "wow" moment for a review — have it ready to show live: tamper with a test file, show detection.

---

## Phase 4 — Location/Sensor Integrity & Explainable AI Layer
**Duration:** ~2 weeks · **Maps to:** PPT Phase 3 ("Tamper detection") + Component slide's "AI: Deepfake & Authenticity Detection"

**Goal:** the layers that catch spoofed inputs *before* they get cryptographically sealed, and the project's honestly-scoped "AI" contribution.

| Task | Exact API/decision |
|---|---|
| Mock-location detection | `Location.isMock()`/`isFromMockProvider()`, per `02` §6 |
| Extended GPS integrity checks | Port `auag0/MockLocationDetector`'s 4-check pattern (AppOpsManager scan, `Settings.Secure` flag, `Location` mock-flag cross-check, basic hook detection), per `05` §5 |
| GNSS raw signal check (stretch) | `GnssMeasurement` C/N0-AGC analysis, per `02` §6 — mark as stretch if time-constrained |
| Speed/distance plausibility | Haversine distance between consecutive events ÷ elapsed time, flag implausible "teleportation," per `02` §6 |
| **ELA (Error Level Analysis)** | Native Kotlin implementation using `android.graphics.Bitmap` (`Bitmap.compress(JPEG, quality~90-95)` → diff → amplify), per `04` §3, §6 — **no external dependency needed** |
| **EXIF consistency checks** | `androidx.exifinterface` — check `Software` tag, MakerNote presence, thumbnail/main-image mismatch, GPS/timezone consistency, `DateTimeOriginal` vs `ModifyDate`, per `04` §5, §6 |
| Label this layer honestly | Call it **"Explainable Authenticity Heuristic (ELA + EXIF)"** in all UI/report text — never "AI Deepfake Detection" — per `04` §6 / `08` #20 |

**Exit criteria:** a deliberately-spliced test image produces a visible ELA heat-map highlighting the spliced region, and at least one EXIF-based flag fires on a test image edited in an external tool. Both results surface in the app UI, not just logs.

---

## Phase 5 — Backend, Storage & Verification Module
**Duration:** ~2–3 weeks · **Maps to:** Component slide's "Backend"/"Storage" rows + Slide 9's "Secure Storage" and "Verification Module" stages

**Goal:** proof packages sync reliably to the cloud, and an independent verification flow can check any package's authenticity — offline-first, per `01` §9 rec. 3 / `08` #25.

| Task | Exact API/decision |
|---|---|
| Offline sync | Room queue + WorkManager (`NetworkType.CONNECTED` constraint, exponential backoff), per `03` §8 |
| Backend API | Node.js/Express, `/proof` (POST, store package) and `/verify` (POST, recompute hash + verify signature), per `03` §6 |
| Cloud storage | Firebase Firestore (metadata) + Cloud Storage (media), immutable per-event objects — never overwrite in place, per `02` §8 Step 8 |
| Verification module logic | Implement all 5 checks from `02` §8 Step 10 in order: (1) recompute media hash, (2) recompute metadata hash, (3) verify signature + attestation cert chain, (4) timestamp plausibility, (5) location plausibility — emit **per-check breakdown**, not one opaque boolean |
| Authenticity Result UI | "Valid / Tampered" with the per-check breakdown visible — matches Slide 9 exactly |
| QR verification badge | `zxing-android-embedded` — encode a verification URL/short-hash, per `05` §6 / `08` #29 |
| Certificate export | Android's built-in `PdfDocument` API, rendering proof metadata + embedded QR, per `05` §7 / `08` #30 |
| Certificate content framing | Explicitly label what it proves/doesn't prove (per `06` §7's precise citable statement) — **do not claim legal admissibility on its own** |

**Exit criteria:** a proof package captured on-device syncs to Firebase automatically when connectivity returns (test in airplane mode); the verification module, given a package pulled fresh from Firebase, reproduces "Valid"; given a package with a manually-edited field, reproduces "Tampered" with the correct specific check flagged; a PDF certificate with an embedded QR code renders correctly.

---

## Phase 6 — Testing, Security Validation & Deployment
**Duration:** ~2 weeks · **Maps to:** PPT Phase 4 ("Accuracy testing, Security validation, Final prototype")

**Goal:** confidence the system behaves correctly under both normal and adversarial conditions, and a polished, demoable build.

| Task | Exact API/decision |
|---|---|
| Unit tests | JUnit — hash computation, signature round-trip, wall-clock/elapsedRealtime conversion math, per `03` §9 |
| Sensor/location/crypto tests | Robolectric with `ShadowLocationManager`/`ShadowSensorManager`, per `03` §9 |
| ViewModel mocking | MockK for Repository/ViewModel boundary tests, per `03` §9 |
| UI smoke tests | Espresso — small number, critical flows only (capture button → result screen), per `03` §9 |
| Security validation scenarios (run and document each explicitly) | (1) tamper media after capture → detected; (2) tamper one metadata field → detected; (3) mock-location app active → flagged; (4) gallery-import attempt → blocked at the UI layer; (5) rooted-emulator capture → Play Integrity verdict reflects it |
| Accuracy testing | ELA/EXIF false-positive rate on a small set of known-untouched vs. known-edited test images; GPS fix accuracy vs. reported `Location.getAccuracy()` |
| Final prototype polish | End-to-end demo script: capture → view queue → sync → verify → view Authenticity Result → export certificate |

**Exit criteria:** a runnable APK plus a written test report covering the 5 security-validation scenarios above with pass/fail and screenshots — this **is** the "Final prototype" line item on the PPT's own Phase 4.

---

## Phase 7 — Stretch Goals / "Future" Scope
**Duration:** time-permitting, after Phase 6 is solid · **Maps to:** Component slide's "⛓ Future: Blockchain Integration" + AI stretch layer

Do not start these until Phases 0–6 are demonstrably working — they are explicitly lower priority.

| Task | Exact API/decision |
|---|---|
| OpenTimestamps hash anchoring | `opentimestamps` npm package (Node backend), stamp `rootHash` to Bitcoin via free calendar servers, per `08` reconciliation table |
| Polygon Amoy smart-contract anchoring | Deploy `RealityLockAnchor.sol` (full source in `02` §5) via Remix + MetaMask; call `anchorHash()`/`verifyHash()` from the Node backend via **ethers.js**, per `08` reconciliation table |
| TFLite MesoNet classifier | Convert `DariusAf/MesoNet` (Meso-4, 27,977 params) to quantized TFLite, run as an optional, clearly-labeled-experimental secondary "AI confidence score" — never replacing the ELA/EXIF layer, per `04` §2, §6 |
| PRNU offline demo | `polimi-ispl/prnu-python` in a Jupyter notebook, presented as future work, not integrated into the app, per `04` §4 |
| C2PA manifest export | Integrate `contentauth/c2pa-android` to emit a real, third-party-verifiable C2PA Content Credential alongside the native proof package, per `01` §1, §9 rec. 1–2 |
| WiFi/cell-tower location cross-check | Extend Phase 4's location integrity checks with nearby WiFi SSID/cell-tower ID logging, per `01` §9 rec. 5 |
| Bystander privacy features | Post-capture face-blur/redaction option, visible on-screen "recording" indicator, per `06` §8 items 5, 8 |
| Section 63(BSA)-style certificate template | Auto-populate hash value + algorithm + device particulars into a template explicitly labeled "draft — requires human/expert countersignature," per `06` §1.3, §8 item 3 |

**Exit criteria for the report, if none of Phase 7 is built:** these remain honestly documented as designed-but-not-implemented future work, consistent with how the PPT itself already frames "Blockchain Integration" as Future — this is not a gap to hide, it's exactly the scope the source material anticipated.

---

## Summary Timeline (indicative, ~15-week semester)

| Weeks | Phase |
|---|---|
| 1 | Phase 0 — Foundations & Setup |
| 1–2 (overlap) | Phase 1 — Design Finalization |
| 2–5 | Phase 2 — Core Capture Pipeline |
| 5–8 | Phase 3 — Cryptographic Core |
| 8–10 | Phase 4 — Location/Sensor Integrity & Explainable AI Layer |
| 10–13 | Phase 5 — Backend, Storage & Verification Module |
| 13–15 | Phase 6 — Testing, Security Validation & Deployment |
| Beyond / if ahead of schedule | Phase 7 — Stretch Goals |

This ordering deliberately front-loads Phase 2 (capture) and Phase 3 (crypto) — the two phases with the most existing reusable reference material (CameraX docs, the exact Keystore code already written in `02` §2) — and defers the phases with the thinnest existing tooling (GPS integrity, Phase 7's blockchain/AI stretch work) to where the team will have the most implementation experience and the least schedule risk if they slip.
