# Phase 6 — security validation plan

The five adversarial scenarios from `research/09_PROJECT_PHASES.md` Phase 6, with
what each one is actually testable against in *this* build, and where it runs.

Two of the five needed rewriting, for reasons recorded below. Rewriting a test
plan to match what was built is only legitimate when the reason is stated, so each
change carries its cause.

## Where each scenario runs, and why

The project's target device — a OnePlus CPH2591 on ColorOS — **refuses the entire
privileged-shell family**:

```
$ adb shell pm grant   … SecurityException: … GRANT_RUNTIME_PERMISSIONS
$ adb shell pm revoke  … SecurityException: … REVOKE_RUNTIME_PERMISSIONS
$ adb shell appops set … SecurityException: uid 2000 does not have MANAGE_APP_OPS_MODES
```

Instrumentation cannot revoke there either (`UiAutomation.revokeRuntimePermission`
is a no-op on that device — verified by
`PermissionRevokeGrantInstrumentedTest`, which skips itself when it detects this).

That is not a project blocker; it is a routing constraint. Anything needing a
*privileged state change* runs on an emulator. Anything needing *real hardware
truth* — a real TEE, a real GNSS fix, a real camera clock — runs on the phone.

| Scenario | Runs on | Status | Why there |
|---|---|---|---|
| 1. Tamper media | Backend + e2e | **pass** | Pure computation |
| 2. Tamper metadata | Backend + e2e | **pass** | Pure computation |
| 3. Mock location | Phone | **pass** | Tests our mapping; needs no OEM cooperation |
| 4. Gallery import | Structural assertion | **pass** | There is no import path to block |
| 5. Weak/absent attestation | Phone (proven) + emulator (outstanding) | **half** | Needs a device with no hardware attestation |

Instrumented suite on the CPH2591: **18 tests — 11 asserted and passed, 7 skipped
for documented environment limits, 0 failed.** The skips are all cases where the
device refuses a privileged operation, and each names the reason; none is a
silently-disabled assertion.

---

## 1. Tamper the media after capture → detected

**Status: passing.** `proofVerifier.test.js` flips one bit of the media and asserts
`mediaHashMatch: fail`, `verdict: failed`. `run_sync_e2e.sh` additionally proves
the server *refuses to store* media that does not hash to the signed digest
(HTTP 409), so tampered bytes never enter the store at all.

## 2. Tamper one metadata field → detected

**Status: passing.** Two levels. The obvious one: edit a field, `metadataHashMatch`
fails. The subtle one, which is the case that matters: an attacker who edits the
metadata **and** recomputes the leaf and the root so the tree is internally
consistent still fails `signatureValid` — which is exactly why the root is signed.
Both are asserted, and the e2e run does it against a package captured on the phone.

## 3. Mock-location app active → flagged

**Status: passing.** `MockLocationPlatformInstrumentedTest`
(`a_mock_flagged_location_reaches_the_signed_metadata`, verified as a real pass —
instrumentation status code 0, not an assumption skip).

It asserts both directions of the contract:

- a mock-flagged `Location` produces `metadata.location.isMock = true`, and that
  field sits **inside the signed Merkle root**, so it cannot be cleared afterwards
  without invalidating the signature;
- an unflagged `Location` is **not** reported as mock — the false-positive
  direction, without which the signal would be worthless.

### What this scenario turned out to actually be about

The first attempt drove `FusedLocationProviderClient.setMockMode()` /
`setMockLocation()`, on the reasoning that a fabricated `Location` would answer an
easier question than the real provider. That reasoning was wrong in an instructive
way, and the device said so:

> `setMockMode(true)` and `setMockLocation(...)` **both complete without
> throwing**, and `lastLocation` still returns `null`.

The platform `LocationManager.addTestProvider` route behaves the same way:
`addTestProvider` and `setTestProviderLocation` succeed, and
`getLastKnownLocation` returns null. **ColorOS accepts mock injection and ignores
it.** The lesson worth keeping: *"the API did not throw" is not "the API worked"* —
only asserting the observable effect caught this.

That reframes the scenario correctly. Our contract is *"if the platform flags a
location as mock, we record that in the signed metadata."* Setting the flag is the
platform's job. Whether a given OEM honours third-party mock injection is that
OEM's behaviour, and making our test depend on it produced a flaky test rather
than a strict one. `Location.setMock(boolean)` has been public API since API 31,
so the flagged location is built directly — deterministic, no Developer-options
setup, runs on any device.

Both injection routes are retained as tests that **skip** with an explanatory
message, so the OEM finding stays visible and re-checks itself on other hardware.

**Incidental security note:** on this device a third-party fake-GPS app going
through Play services appears unable to inject a position into our capture path at
all. That is not a claim the project should lean on — it is one device, one
ColorOS build — but it is worth recording.

## 4. Gallery-import attempt → blocked at the UI layer

**Status: passing, structurally.** There is no gallery-import path into the proof
flow to block — Phase 2 built capture as in-app only, and Phase 4 kept the
forensic "Analyze" screen strictly separate: it receives a `Context` and nothing
else, holds no reference to the coordinator or the signer, and produces a report
rather than a proof package.

The test for this is therefore an architectural assertion rather than a UI one:
`ForensicAnalyzer` has no route to `CaptureCoordinator` or `EventSigner`. A UI test
that clicked around looking for an import button would be weaker evidence, since
absence of a button is not absence of a path.

## 5. ~~Rooted-emulator capture → Play Integrity verdict reflects it~~ → **weak or absent attestation is reported as such**

**Rewritten. Status: half proven; emulator half outstanding.**

**Why the original cannot be run:** it names Play Integrity, which this project
deliberately does not implement. ADR-0004 chose Android Key Attestation instead —
Play Integrity needs a $25 Play Console account, and its Play-delivery check can
*never* pass for a sideloaded debug APK, so paying would buy friction rather than
signal. Play Integrity is recorded there as a Phase-7 stretch.

**The equivalent claim, in terms this build can support:** the system must never
report hardware backing it did not obtain.

| Environment | Expected | Status |
|---|---|---|
| OnePlus CPH2591 (real TEE) | `tier=TRUSTED_ENVIRONMENT`, 4-cert chain, root matches a published Google root | **Proven** (Phase 3) |
| Emulator / no attestation keys | `attestationPresent: unavailable` + advisory; verdict may still be `verified` | Outstanding |

The second row is the adversarial half, and the verdict rules already encode the
honest answer: a missing chain is `unavailable` **plus an advisory**, not `fail`
(ADR-0006 §5) — absence of evidence is not evidence of a defect — while a chain
that is *present but does not bind to the signing key* does fail, via
`attestationKeyBinding`. That last check is the one that stops a genuine chain
being stapled onto someone else's package, and it is unit-tested.

Note the emulator image here is `google_apis_playstore` **x86_64**, which has no
hardware-backed keymaster, so it exercises exactly the "cannot attest" path.

---

## Accuracy testing (also Phase 6)

- **ELA/EXIF false-positive rate** — needs a small labelled corpus of known-untouched
  vs known-edited images. The Phase-4 fixtures are a starting point but are too few
  to quote a rate from; saying "3 images, no false positives" would be a misleading
  statistic and should not be written.
- **GPS accuracy vs `Location.getAccuracy()`** — needs outdoor captures at known
  points. Not yet done.

## Known gaps, stated rather than hidden

- The backend has **no authentication or rate limiting**. Anyone who can reach it
  can submit or verify. Acceptable for a coursework deployment; it is Phase-6
  hardening work and is not claimed to be solved.
- `isMinifyEnabled = false` for release. Turning R8 on is Phase-6 work, and a
  release build also blocks cleartext HTTP, so it requires the HTTPS deployment.
