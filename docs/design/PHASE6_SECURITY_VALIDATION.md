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

---

## Test coverage added in Phase 6

Totals after this pass: **173 Android unit tests** and **83 backend tests**, all
passing, none skipped.

### Robolectric (newly enabled)

Robolectric was deferred through Phases 1–5 because nothing yet needed a
simulated framework. Two things did, and both were untestable without it:

- **`SensorSnapshotCollector`'s framework path.** The existing test only covered
  the static selection helpers and never constructed a collector, so
  `registerListener` → `onSensorChanged` → rolling buffer → `snapshotNearest`,
  the buffer eviction, and the accelerometer/gyroscope routing had no coverage at
  all. Eight tests now cover that path.
- **`LocationSource.isMockCompat()`'s pre-API-31 branch.** This is the finding
  worth recording. Mock-location detection reads `isMock` from API 31 and the
  deprecated `isFromMockProvider` below it, and the project's only test device is
  an API 35 phone — so **the pre-31 branch had never executed anywhere**, on a
  check whose result is sealed inside the signed Merkle root. It is now asserted
  at API 28, 30, 31 and 35, in both directions (a flagged location is reported,
  an unflagged one is not).

Robolectric runs pinned to `sdk=35`, not the project's `targetSdk=36`: SDK 36's
`android-all` requires Java 21 and this toolchain is Java 17. Raising the
toolchain to satisfy a test runtime would change the compiler used for the
shipped APK, so the test runtime was pinned instead. Recorded in
`app/src/test/resources/robolectric.properties`.

These tests substitute a plain `Application` rather than `RealityLockApplication`,
which builds the whole DI graph — including a keystore open — in `onCreate()`.

### ViewModel boundary (MockK)

`ProofsViewModelTest` covers the verification and certificate flows against
mocked collaborators. Its reason for existing is a **regression test for the
certificate-verdict defect**: the verdict label was previously resolved in the
composable and handed over already decided, so whichever report was on screen got
stamped onto whatever event the user exported.

That test was mutation-checked rather than merely observed to pass — deleting the
`takeIf { reportEventId == eventId }` guard from `ProofsViewModel` makes exactly
that one test fail, and no other. A positive control asserts the report *is* used
when it does belong to the event, so a ViewModel hardwired to "not verified"
could not satisfy the suite.

`ProofsViewModel` gained an injected `ioDispatcher` (defaulting to
`Dispatchers.IO`, so production behaviour is unchanged) because a hardcoded
dispatcher leaves tests racing a real thread pool.

---

## Gaps closed in Phase 6

Both items previously listed here as known gaps are now done.

### Backend rate limiting — **closed**

The service still has no authentication, so per-IP limiting is the only control
in front of it. `/proof` and `/verify` share one bucket; `/health` has its own,
far looser one — it is not exempt (it lists the store on every call) but
answering the platform's health checker with 429 would mark the service unhealthy
and cause the outage the hardening was meant to prevent.

The security-critical part is `TRUST_PROXY_HOPS`, and it is a **count, never
`true`**. Under Express's `trust proxy: true` the client address is taken from
the left-most `X-Forwarded-For` entry — which the caller writes — so an attacker
rotates it per request and the limiter stops meaning anything;
express-rate-limit refuses that configuration by name
(`ERR_ERL_PERMISSIVE_TRUST_PROXY`). A count makes Express read from the right,
where only a real proxy can append. The config rejects `true` and negative values
rather than coercing them, and tests assert both refusals.

**Outstanding, and it matters:** the hop count is set to `1` on the reasoning
that Render fronts the app with a single load balancer. That has not been
confirmed against the live deployment. If it is wrong, the limiter either keys on
a spoofable value (too high) or collapses every caller into one bucket (too low).
Verify before relying on it.

### R8 minification — **closed**

`isMinifyEnabled = true`. The APK drops from 29 MB to **4.4 MB**.

`proguard-rules.pro` is still empty, which is a result rather than an omission:
this app has no reflective model binding (no Gson/Moshi/Retrofit converters, no
Room, no Tink — proof packages are built field-by-field against `org.json`), and
the three classes the framework instantiates by name are kept by AGP's manifest
handling and androidx.work's consumer rules. All three were confirmed present and
unrenamed in the release mapping.

Because R8's dangerous failure mode here is not a crash but a **silently weaker
proof package**, it was verified on real hardware rather than by inspection: a
capture made by the minified build was pulled from the device and run through the
backend's own verifier.

```
VERDICT: verified
  mediaHashMatch pass · metadataHashMatch pass · merkleRootMatch pass
  signatureValid pass · attestationPresent pass · attestationChainValid pass
  attestationKeyBinding pass · timestampPlausible pass
  attestationRootTrusted unavailable · locationPlausible unavailable
```

Field-for-field identical in shape to a pre-R8 baseline captured on the same
device, same 4-certificate chain. `attestationRootTrusted: unavailable` is the
pre-existing Google-root gap, not an R8 effect.

Release stack traces are unreadable without the `mapping.txt` from the exact
build that produced the APK; it is not committed, so archive it with any APK that
is distributed.

## Known gaps, stated rather than hidden

- The backend has **no authentication**. Rate limiting (above) bounds abuse per
  IP but does not establish who is calling. Anyone who can reach the service can
  still submit or verify. Proof-of-possession auth using the per-install signing
  key is the intended answer and is not built.
- The `TRUST_PROXY_HOPS=1` assumption is unverified against the live Render
  deployment (see above).
- **Espresso/Compose UI smoke test not written.** The phase table asks for a
  small number of critical-flow UI tests (capture button → result screen); the
  instrumented suite currently covers permissions, mock location, forensics and
  certificate rendering, but not that flow. `androidx.compose.ui.test.junit4` is
  not yet on the classpath.
- **Accuracy testing still not done** — unchanged from the section above. The
  ELA/EXIF false-positive rate needs a labelled corpus, and GPS accuracy needs
  outdoor captures at known points. No rate should be quoted until then.
- Scenario 5's emulator half remains outstanding; the attempt this session failed
  on host disk space, not on anything about the build.
