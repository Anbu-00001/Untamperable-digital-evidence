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
| 5. Weak/absent attestation | Phone + emulator | **pass** | Needs a device with no hardware attestation |

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

**Rewritten. Status: proven on both environments (2026-08-06).**

**Why the original cannot be run:** it names Play Integrity, which this project
deliberately does not implement. ADR-0004 chose Android Key Attestation instead —
Play Integrity needs a $25 Play Console account, and its Play-delivery check can
*never* pass for a sideloaded debug APK, so paying would buy friction rather than
signal. Play Integrity is recorded there as a Phase-7 stretch.

**The equivalent claim, in terms this build can support:** the system must never
report hardware backing it did not obtain.

| Environment | Expected (as written) | Actual, verified | Status |
|---|---|---|---|
| OnePlus CPH2591 (real TEE) | `tier=TRUSTED_ENVIRONMENT`, 4-cert chain, root matches a published Google root | matches | **Proven** (Phase 3) |
| Emulator / no hardware attestation | `attestationPresent: unavailable` + advisory; verdict may still be `verified` | chain **present**, `attestationRootTrusted: fail`, `attestationSecurityLevel: fail` (`Software`); **`verdict: failed`** | **Proven, prediction corrected by evidence** |

**The prediction in the row above was wrong, and the real result is the more
interesting one.** A `google_apis_playstore` x86_64 AVD (android-36.1) does not
skip attestation — `KeyGenParameterSpec.setAttestationChallenge` still returns a
3-certificate chain. What the emulator cannot do is back it with hardware:
decoding the pulled chain shows

```
leaf   CN=Android Keystore Key            issuer: O=TEE, CN=2c9f81f3...
TEE    O=TEE, CN=2c9f81f3...              issuer: CN=Droid Unregistered Device CA, O=Google Test LLC
root   CN=Droid Unregistered Device CA, O=Google Test LLC   (self-signed)
```

— a self-signed **"Droid Unregistered Device CA / Google Test LLC"** root, which
is not one of the two production roots pinned in
`backend/data/google-attestation-roots.pem`. This is exactly the case ADR-0004's
original correction paragraph names: *"a self-issued CA and leaf over an ordinary
software key produce a chain that links and binds correctly."* It links
(`attestationChainValid: pass`) and binds (`attestationKeyBinding: pass`), but two
independent checks catch it anyway:

- `attestationRootTrusted: fail` — the root's raw DER matches neither pinned
  Google root.
- `attestationSecurityLevel: fail` — the extension's own `securityLevel` field
  reads `Software (attestation version 400)`, the device honestly stating the key
  is not in secure hardware.

Because `attestationSecurityLevel` is a decisive check (`fail` for `Software`, per
ADR-0004), the overall verdict is **`failed`**, not "verified with an advisory" as
originally guessed. The full response, from `POST /verify` against the pulled
capture:

```
schemaValid pass · mediaHashMatch pass · metadataHashMatch pass · merkleRootMatch pass
signatureValid pass · attestationPresent pass · attestationChainValid pass
attestationKeyBinding pass · attestationRootTrusted fail · attestationNotRevoked pass
attestationSecurityLevel fail · timestampPlausible pass · locationPlausible unavailable
verdict: failed
```

(`locationPlausible: unavailable` is unrelated to attestation — the backend had no
earlier located event for this fresh install to cross-check against.)

The verdict rules still encode the honest answer for the case the original row
described (no chain at all, e.g. `attestationCertificateChain: null`): that
reads `attestationPresent: unavailable` **plus an advisory**, not `fail`
(ADR-0006 §5) — absence of evidence is not evidence of a defect. This build's
emulator produces the *stronger* adversarial case — a chain that is present,
internally consistent, and still correctly rejected — which subsumes the weaker
one rather than leaving it untested.

### How this was run

`RealityLock_NoAttest` AVD (`system-images;android-36.1;google_apis_playstore;x86_64`,
Pixel 6 profile), booted headless. Unlike the CPH2591 (ColorOS refuses both
`adb shell pm grant` and `UiAutomation.grantRuntimePermission` — see above), the
stock AVD grants CAMERA and location via plain `adb shell pm grant`, so the
capture was driven the same way `run_e2e.sh` drives the phone: launch
`MainActivity`, locate the "Capture event" button by its on-screen text via
`uiautomator dump`, tap it, pull the resulting sidecar and JPEG with
`run-as`. The pulled package (schema-valid, media/metadata hashes present) was
posted to a locally running backend's `/verify` exactly as `run_e2e.sh` step 7
does, producing the response quoted above. Not run through `run_e2e.sh` itself,
since that script auto-selects the first `adb devices` entry and does not
distinguish which of two attached devices to drive — a real device and this AVD
were connected simultaneously.

---

## Accuracy testing (also Phase 6)

- **ELA/EXIF false-positive rate** — needs a small labelled corpus of known-untouched
  vs known-edited images. The Phase-4 fixtures are a starting point but are too few
  to quote a rate from; saying "3 images, no false positives" would be a misleading
  statistic and should not be written.
- **GPS accuracy vs `Location.getAccuracy()`** — needs outdoor captures at known
  points. Not yet done.

---

## Instrumented suite, re-run on hardware (2026-08-03)

**22 tests on the CPH2591 — 14 passed, 8 skipped for documented OEM limits, 0
failed.** (Previously 18: 11 passed, 7 skipped. The four new ones are the Compose
UI smoke test.)

### The Compose UI smoke test now genuinely runs

`CaptureFlowInstrumentedTest` — all four cases executed and passed on the device,
with real execution times and no skip markers. It asserts the four tabs render
and navigate, and — the case with actual weight — that the Analyze surface still
displays its **"triage aid — not a verdict"** banner, which research/04 §6
requires and whose loss would leave ELA/EXIF output reading as a determination of
authenticity. It deliberately does not re-drive capture; `scripts/e2e/run_e2e.sh`
already does that end-to-end.

### Integration runs, same session

Both end-to-end scripts were run against the device after the Phase-6 changes
(Robolectric, R8, rate limiting) and the Phase-7 addition, to confirm none of them
disturbed the working system.

| Script | Result | Notable |
|---|---|---|
| `run_e2e.sh 2` | **11 passed, 0 failed** | 2 real captures driven through the UI; motion bound 2.63 ms and 216.89 ms from the shutter; **one flipped bit in the JPEG detected** (`verdict=failed`, `mediaHashMatch=fail`) |
| `run_sync_e2e.sh` | **33 passed, 0 failed** | full sync → verify; rewriting a stored package refused (409); media not matching the signed digest refused (409); **`locationPlausible: pass`** — the cross-event check ran against real stored history; edited metadata detected; the public QR verdict discloses no coordinates |

This makes scenarios 1 and 2 (tamper media / tamper metadata) proven against
genuine device captures rather than fixtures, and `locationPlausible` exercised
with real history rather than reported `unavailable`.

#### A latent bug in `run_e2e.sh`, found and fixed by running it

The first run died with
`line 155: 0\n0: syntax error in expression (error token is "0")`.

Cause: `... | grep -c '\.json' || echo 0`. `grep -c` prints `0` for an empty
match **and** exits non-zero, so the `|| echo 0` fired as well and the command
substitution returned the two-line string `"0\n0"`, which `$((AFTER - BEFORE))`
cannot parse. It failed in precisely the situation it was written to survive — a
device holding no captures.

Replaced with a `count_event_sidecars` helper (`| tail -1`, plus `:-0` for adb
failing outright). A pre-flight check was also added to step 4: if the camera
permission is absent the run now stops there with instructions, instead of
surfacing several steps later as "could not find the Capture event button", which
reads like a UI regression rather than a device-policy problem.

### A question this file left open is now answered, and the answer is "no"

Section "Where each scenario runs" said `GrantPermissionRule` should work because
it goes through `UiAutomation.grantRuntimePermission()` rather than the `pm`
shell command, and noted honestly that `PermissionGrantInstrumentedTest` passing
on a device where the permissions were **already granted** proved only that the
rule did not interfere — not that it could grant from scratch. It proposed the
emulator as the way to settle that.

Uninstalling the app and running fresh settled it instead, and it settles it
against us:

```
SecurityException: Error granting runtime permission
  at android.app.UiAutomation.grantRuntimePermissionAsUser(UiAutomation.java:1574)
```

All four UI tests failed this way on a clean install. The `pm` route was
re-confirmed blocked in the same session:

```
$ adb shell pm grant com.realitylock.app android.permission.CAMERA
SecurityException: grantRuntimePermission: Neither user 2000 nor current
process has android.permission.GRANT_RUNTIME_PERMISSIONS
```

So ColorOS refuses **both** routes, and the earlier hope that instrumentation was
privileged where the shell is not does not hold on this device. The only way to
hold these permissions here is a human tapping Allow.

`CaptureFlowInstrumentedTest` was therefore changed to **skip with an explanatory
message** when the camera permission is absent, rather than fail — the convention
this file already applies to OEM limits, since a red failure would report a
device policy as an application defect. On a prepared device (or any emulator)
the tests run normally, which is how the passing run above was obtained.

The practical consequence, worth stating for anyone reproducing this: **the
instrumented suite cannot be run unattended from a clean install on this
hardware.** Grant camera and location once by hand first.

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

### Backend rate limiting — **implemented, with a stated production limitation**

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

#### What the live deployment actually showed

The limiter was probed against the deployed service rather than assumed correct,
and the result is worth recording because it is **not** a clean pass.

Six requests carrying the *same* spoofed `X-Forwarded-For` returned
`remaining = 597, 596, 595, 597, 596, 595` — the same triplet twice. A single
coherent counter would have descended 597→592. The state is therefore not shared
across requests.

The cause is in this repo, not in Render: the limiter is constructed with no
`store`, so it uses express-rate-limit's default **MemoryStore, which is
per-process**. Consequences, stated plainly:

- The effective allowance is roughly *configured limit × number of serving
  processes*, not the configured limit.
- On Render's free tier the service spins down when idle, and the counter is lost
  entirely on every cold start.

So rate limiting is **implemented, unit-tested and active** — the seven tests in
`backend/test/rateLimit.test.js` are deterministic because they run one process —
but it should be described as *raising the cost of abuse*, not as a dependable
per-IP quota in production. Making it dependable needs a shared store (the
library's Redis/Memcached stores exist for exactly this) or a guaranteed
single-instance deployment.

**Still unverified:** whether `TRUST_PROXY_HOPS=1` matches Render's real
topology. The counter noise above masks the signal the spoofing test depends on —
a fresh bucket per forged IP is indistinguishable from a different process
answering — so the experiment could not settle it either way. Re-run it once the
limiter has a shared store; until then treat the hop count as an assumption.

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
- The rate limiter's per-process MemoryStore, and the unverified
  `TRUST_PROXY_HOPS=1` assumption (both detailed above).
*(The Compose UI smoke test has since been executed — see "Instrumented suite,
re-run on hardware" below.)*

- **Accuracy testing still not done**, and one part of it needs restating.
  GPS accuracy still needs outdoor captures at known points.

  For ELA/EXIF, a *"false-positive rate"* is the wrong measure to chase as
  written. `ExifRules` documents every flag as suggestive — "the metadata is
  internally inconsistent or names an editor", explicitly **not** "the image was
  manipulated" — so scoring it against untouched/edited labels would presuppose a
  claim the design refuses to make, and publishing that number would overstate
  the feature in exactly the way research/04 §6 forbids. A synthetic corpus makes
  it worse, not better: generated images are not camera photographs, and a rate
  measured on them would be precise and meaningless.

  What would be legitimate is a *characterisation* on real photographs — how
  often each rule fires on untouched camera output versus editor-processed
  output, reported as rule behaviour rather than detector accuracy. That needs a
  real labelled corpus, which the project does not have and must not fabricate.
- ~~Scenario 5's emulator half remains outstanding~~ — resolved 2026-08-06 once
  host disk space freed up; see the Scenario 5 section above for the result (a
  software-backed chain, correctly failed by two independent checks).
