# Reality Lock — Developer Setup

Everything here is the **manual, account/tooling setup** that cannot be scripted into the repo. Code scaffolding (Android app, backend) is already in place; this gets a fresh machine building it. Follows Phase 0 of [`research/09_PROJECT_PHASES.md`](research/09_PROJECT_PHASES.md).

---

## 1. Backend (works fully offline, right now)
No accounts needed for the Phase 1 skeleton.
```bash
cd backend
npm install
npm run validate:schema      # verifies the proof schema + example
npm run dev                  # http://localhost:3000  (GET /health)
```
Config is env-driven — copy `backend/.env.example` to `backend/.env` only if you need to change a default.

### 1.1 Deploying it (Phase 0 exit criterion)
Phase 0 requires the health check to answer **from a deployed URL**. Two ready paths, both tested locally:

**Render (recommended — free tier, no card).** [`render.yaml`](render.yaml) at the repo root is a Blueprint:
1. Push this repo to GitHub.
2. Render dashboard → **New → Blueprint** → pick the repo. Everything (root dir, build/start commands, health-check path) is declared already.
3. Note the assigned URL, then point the app at it in `android/local.properties`:
   `REALITYLOCK_BACKEND_BASE_URL=https://<your-service>.onrender.com/`

**Docker (Railway / Fly.io / Cloud Run).** Build **from the repository root**, not `backend/`:
```bash
docker build -t reality-lock-backend .
docker run --rm -p 8080:3000 -e PORT=3000 reality-lock-backend
curl http://127.0.0.1:8080/health
```

> **Why the build context is the repo root:** the proof schema lives in
> `docs/design/`, *outside* `backend/`, because it is the single contract shared
> with the Android app and is deliberately never duplicated. A deploy that ships
> only `backend/` will fail fast at boot with a message saying exactly this.
> Override with `PROOF_SCHEMA_PATH` if your platform needs a different layout.

## 2. Android app

### 2.1 Prerequisites
- **Android Studio** (2026.x / "Narwhal"-era or newer) — bundles a compatible JDK 17+ (this repo targets JDK 17, AGP 8.13.0, Gradle 8.14, Kotlin 2.3.0).
- Android SDK Platform **API 36** and a device/emulator on **API 28+** (StrongBox-capable emulator image recommended to exercise hardware-backed keys later).

### 2.2 Gradle wrapper
The wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.jar`) **is committed** — just use it:
```bash
cd android
./gradlew :app:assembleDebug
```

### 2.3 Open & build
1. Open the **`android/`** folder in Android Studio (not the repo root).
2. Let Gradle sync. **If sync proposes a version adjustment** (AGP/Kotlin/KSP/CameraX), make the change in **`android/gradle/libs.versions.toml`** only — never in a module build file. Keep `kotlin`, `ksp`, and `composeCompiler` versions identical to each other, and check each library's *minimum AGP* before bumping (the catalog header explains the AGP-8.13 ceiling).
3. Run the `app` configuration. You should see the **Capture** screen with four tabs — *Capture*, *History*, *Analyze*, and *Device*. **History** is where Phase 5 lives: per-event sync state, **Verify**, and **Certificate**. The Device tab carries the foundation-status readouts (app version, backend URL, proof schema, device capabilities, attestation tier, GNSS capability).

Useful checks:
```bash
./gradlew :app:testDebugUnitTest         # 141 JVM unit tests, incl. real schema validation
./gradlew :app:connectedDebugAndroidTest # 6 instrumented tests (needs a device)
cd ../backend && npm run validate:schema && npm test
```

### 2.4 Per-machine config overrides (optional)
Defaults live in `android/gradle.properties`. To override for your machine, copy `android/local.properties.example` → `android/local.properties` (gitignored) and set:
- `REALITYLOCK_BACKEND_BASE_URL` — see §2.5 for the physical-device route (emulator uses `http://10.0.2.2:3000/` by default).
- `REALITYLOCK_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER` — from §4 below.

### 2.5 Pointing a USB-connected phone at a backend on your laptop
Use `adb reverse` rather than hunting for a LAN IP: it makes the laptop's port
reachable at `127.0.0.1` **on the phone**, works on any network (or none), and
needs no firewall changes.
```bash
adb reverse tcp:3000 tcp:3000
# then build/install with the phone-side URL:
cd android && ./gradlew :app:installDebug -PREALITYLOCK_BACKEND_BASE_URL=http://127.0.0.1:3000/
```
Re-run `adb reverse` after the phone reconnects or the adb daemon restarts.

Cleartext HTTP is permitted **only in debug builds**, via
[`app/src/debug/AndroidManifest.xml`](android/app/src/debug/AndroidManifest.xml).
Release builds keep Android's default block, because a proof package contains GPS
coordinates and shipping those unencrypted is not acceptable.

---

## 3. Storage: what Phase 5 actually uses, and why it is not Firebase Storage

**Firebase Cloud Storage is no longer free.** Since **2026-02-03** Google requires a
linked billing account (the Blaze plan) to create or keep a bucket at all,
regardless of volume; a project left on Spark gets HTTP 402/403 on every bucket
call. **Firestore is unaffected** — it stays free on Spark with a 1 GiB /
20 000-writes-a-day quota and **no card**.

So Phase 5 splits the two (see [ADR-0006](docs/design/adr/ADR-0006-phase5-sync-storage-and-verification.md) §1):

| What | Where | Cost |
|---|---|---|
| Proof packages (small JSON) | backend store; Firestore is the config-gated durable option | **$0, no card** |
| Media (large bytes) | backend's **content-addressed** store, never Firebase | **$0, no card** |

This costs nothing evidentiary, because **the proof package binds the media by
hash, not by location** — where the bytes live is a durability question, not an
authenticity one. The authoritative copy stays on the device.

**Nothing needs configuring to run it.** The default `filesystem` driver writes to
`backend/.data/` (gitignored) and works out of the box:
```
backend/.data/packages/<eventId>.json     written once, never rewritten
backend/.data/media/<sha256>              named by its own digest
```
Optional env overrides: `STORE_DRIVER` (`filesystem` | `memory`),
`STORE_DATA_DIR`, `MAX_MEDIA_BYTES`.

> **Free-tier hosting caveat:** Render's free tier has an **ephemeral filesystem
> and cannot attach a persistent disk**, so a redeploy discards `.data/`. Fine for
> a demo; if you need packages to survive a redeploy, that is what the Firestore
> adapter is for.

### 3.1 If you *do* want Firestore (optional, still free)
1. Create a project at [console.firebase.google.com](https://console.firebase.google.com/) — **stay on Spark; do not upgrade to Blaze.**
2. Enable **Firestore** only. Do **not** enable Cloud Storage (that is the part that wants a card).
3. Set `FIREBASE_PROJECT_ID` in `backend/.env`.

There is no `google-services.json` and no Firebase Android SDK: the app talks only
to our own backend, so nothing Firebase-shaped is on the device at all.

## 4. Play Integrity + Google Cloud (Phase 3 — not required until then)
1. Create/reuse a **Google Play Console** app entry (internal testing track is enough — no public release needed).
2. Link it to a **Google Cloud project**; note the **project NUMBER**.
3. Enable the **Play Integrity API** in that Cloud project.
4. Put the project number in `android/local.properties` as `REALITYLOCK_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER`.
5. Token **decoding happens server-side only** — the backend holds the service-account credentials (Phase 3/5). Never decode on-device.

---

## 5. Troubleshooting

**`NoSuchMethodError: No direct method <init>(...)` at app start.**
Seen after changing a constructor that has default arguments (Kotlin generates a
synthetic constructor whose signature encodes the parameter list, so adding a
parameter changes it). Gradle's **build cache** can restore a stale caller
compiled against the old signature, and `clean` alone does not always evict it.
Fix:
```bash
cd android
rm -rf app/build .gradle
./gradlew --no-build-cache :app:assembleDebug
adb uninstall com.realitylock.app && adb install app/build/outputs/apk/debug/app-debug.apk
```

**Resource compilation fails with `values-*/values-*.xml (No such file or
directory)`.** Stale incremental merge state. Same remedy as above:
`rm -rf app/build .gradle` then rebuild.

**Captures appear to do nothing on an emulator.** Two causes seen in practice:
a system ANR dialog (e.g. "Pixel Launcher isn't responding") silently swallowing
taps — dismiss it and retry; and each capture taking ~10 s because the location
request runs its full timeout when the emulator never supplies a GPS fix
(`adb emu geo fix` reports `OK` but frequently does not register on a headless
emulator).

**Timestamps look wrong by days, and `motion` is always `null`.** The camera's
clock base. Check it:
```bash
adb shell dumpsys media.camera | grep -A1 timestampSource
```
`[UNKNOWN ]` means the camera stamps frames on `CLOCK_MONOTONIC`, which pauses
during deep sleep, so a long-idle phone reports a capture instant far in the
past. The app detects and corrects this (`ClockCorrelator.toElapsedRealtimeNanos`);
the note matters because **an emulator or freshly rebooted phone has ~0 deep
sleep and therefore cannot reproduce the bug**. See [`docs/evidence/`](docs/evidence/).

**`adb shell pm grant` fails with `SecurityException`.** ColorOS/OxygenOS block
shell-granted runtime permissions. Grant them through the in-app permission
panel on the device instead.

**Reading captures off a device** (debug builds only):
```bash
adb shell run-as com.realitylock.app ls -t files/captures/
adb shell run-as com.realitylock.app cat files/captures/<eventId>.json
```

## 6. What each part proves (keep honest)
The cryptographic pipeline delivers **tamper-evidence** (integrity + authenticity of the captured bundle), **not** proof the depicted event is real, and **not** a standalone legal certificate. See [`docs/design/PROOF_PACKAGE_SPEC.md`](docs/design/PROOF_PACKAGE_SPEC.md) → Limitations, and `research/06_legal_standards_compliance.md` §7.
