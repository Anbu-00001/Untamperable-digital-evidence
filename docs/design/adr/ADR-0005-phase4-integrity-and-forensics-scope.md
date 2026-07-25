# ADR-0005 — Phase-4 scope: honest location integrity + an explainable forensic tool

**Status:** Accepted · **Date:** 2026-07-25 · **Phase:** 4
**Refines:** `research/09_PROJECT_PHASES.md` Phase 4, `research/02` §6, `research/04`
**Related:** ADR-0004 (attestation over Play Integrity)

## Context

Phase 4 was planned as "Location/Sensor Integrity & Explainable AI Layer." Two
parts of the plan did not survive contact with the current platform and the
forensics literature, and one scoping question needed settling. This ADR records
what was actually built and why it differs from the first draft.

## Decisions

### 1. Enhanced mock-location detection: only `isMock()` is real on API 35

The plan proposed porting `auag0/MockLocationDetector`'s four checks. Research
found three of the four are dead code for a non-privileged app on Android 14/15:

- **AppOps scan** for other apps holding the mock-location op needs
  `GET_APP_OPS_STATS`, a `signature|privileged` permission an installed app can
  never hold — the call throws `SecurityException` or returns `MODE_ERRORED`.
- **`Settings.Secure.ALLOW_MOCK_LOCATION`** was deprecated in API 23 and reads
  as always-0 since; mock location became per-app.
- **Reflection/hook tamper detection** only catches naive in-process tampering
  and is defeated by the same Magisk/Xposed modules that defeat `isMock()`.

**Built:** `Location.isMock()` (API 31+) / `isFromMockProvider()` (below), which
we already had, plus the speed/distance plausibility check below. The dead checks
are deliberately **not** implemented — shipping non-functional code labelled as a
security feature would be dishonest.

### 2. Speed/distance plausibility: threshold 1500 km/h, and it is a verify-side check

The plan's ">300 km/h ⇒ teleportation" bound is too low — high-speed rail runs
~300 km/h and jet-stream-boosted flights reach ~1300 km/h *ground* speed. We use
**1500 km/h**, with minimum-time (1 s) and minimum-distance (50 m) guards so GNSS
jitter never divides into a phantom huge speed; below the guards the result is
`null` ("not determinable"), never a false "implausible".

The authoritative plausibility check belongs to the **verifier**, recomputed from
the signed metadata of consecutive events (`research/02` §8), so the on-device
`integrity.location` block is **advisory** and deliberately outside the signed
Merkle root. The one integrity signal that must be signed — `isMock` — already
lives in the signed `metadata.location`.

### 3. Raw GNSS spoofing analysis: capability probe now, signal analysis is future work

C/N0-and-AGC spoofing detection needs outdoor multi-satellite captures and the
AGC field, which is optional and frequently unpopulated on consumer phones. It
cannot be demonstrated reliably at a desk. We ship only `GnssCapabilityProbe`
(`GnssCapabilities.hasMeasurements()`), surfaced on the Device screen, and the
proof package's `gnssChecked` stays **false** — we never claim to have verified
GNSS signal integrity. (The target CPH2591 does report raw-measurement support,
so the full analysis is a credible future-work line.)

### 4. The forensic layer (ELA + EXIF) is an analysis tool, not part of the capture path

`research/04` §5 notes EXIF/ELA forensics matter for "someone importing an
old/edited photo and passing it off as a live capture." Reality Lock closes that
hole differently — Phase 2 has **no gallery import into the proof flow at all**,
and a captured image is already proven by the signature. Running ELA/EXIF on our
own single-save captures is near-vacuous (ELA needs a differential compression
history to show anything).

So the forensic layer is a **standalone "Analyze" screen**: the user picks any
candidate image and gets an ELA heat-map and EXIF-consistency flags. It produces
a heuristic *report*, never a proof package — it does not hash, sign, or persist
anything, and has no reference to the coordinator or signer. This keeps the
"no gallery import into the proof flow" rule intact while still delivering the
PPT's named "authenticity detection" block.

### 5. ELA and EXIF are labelled as triage aids, never verdicts

This is the academic-integrity crux. ELA is widely and correctly criticised —
Hany Farid: it labels altered and original images as such "with the same
likelihood"; FotoForensics' own FAQ: "it does not draw any conclusions." EXIF is
trivially editable, so presence can be faked and absence proves nothing. The UI
therefore leads with a disclaimer, uses "suggestive"/"raises a question" wording,
and attaches **no** authenticity score or real/fake label to any output. The
layer is named **"Explainable Authenticity Heuristic (ELA + EXIF)"**, never "AI
Deepfake Detection."

ELA re-saves at **q95** (FotoForensics convention). `androidx.exifinterface`
**1.4.2** is the reader.

## Verification

- 86 JVM unit tests (adds `LocationPlausibilityTest` 10, `ElaCoreTest` 6,
  `ExifRulesTest` 7 to the existing suites).
- 3 **instrumented** tests on the physical CPH2591 (`ForensicInstrumentedTest`),
  using the real Android JPEG encoder and `androidx.exifinterface`: a spliced
  test image's seam reads >1.5× hotter than its background under ELA, and a
  Photoshop-tagged image fires `EDITOR_SOFTWARE`. This is also the project's
  first `androidTest` source set.
- The Analyze screen was driven on-device end to end (ELA heat-map + EXIF flag
  rendered in-app).

## Consequences

- The location layer is smaller than the plan but every part of it actually
  works; nothing is theatre.
- The forensic layer is honestly bounded and defensible in a viva precisely
  because it does not overclaim.
- `integrity.location` being advisory (unsigned) is a stated limitation; a future
  3-leaf Merkle could bind it, tracked as future work.
- **Not built (future work):** GNSS C/N0-AGC analysis, embedded-thumbnail-vs-main
  mismatch (needs decode/compare; deferred to keep false positives down), the
  MesoNet TFLite stretch classifier.
