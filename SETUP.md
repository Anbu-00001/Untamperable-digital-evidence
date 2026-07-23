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

## 2. Android app

### 2.1 Prerequisites
- **Android Studio** (2026.x / "Narwhal"-era or newer) — bundles a compatible JDK 17+ (this repo targets JDK 17, AGP 8.13.0, Gradle 8.14, Kotlin 2.3.0).
- Android SDK Platform **API 36** and a device/emulator on **API 28+** (StrongBox-capable emulator image recommended to exercise hardware-backed keys later).

### 2.2 Generate the Gradle wrapper (one-time)
The wrapper JAR and `gradlew` scripts are **not committed** (they are per-machine binaries). Generate them once:
```bash
cd android
# If you have a system Gradle:
gradle wrapper --gradle-version 8.14
# Otherwise, just open the `android/` folder in Android Studio — it generates
# the wrapper and syncs automatically.
```

### 2.3 Open & build
1. Open the **`android/`** folder in Android Studio (not the repo root).
2. Let Gradle sync. **If sync proposes a version adjustment** (AGP/Kotlin/KSP/CameraX), accept it and make the change in **`android/gradle/libs.versions.toml`** only — never in a module build file. Keep `kotlin`, `ksp`, and `composeCompiler` versions identical to each other.
3. Run the `app` configuration. You should see the **Foundation Status** screen (app version, backend URL, proof schema, and device-capability readouts) — this confirms the config plumbing is wired end to end.

> **Note:** the Android project was authored without a local Android SDK/Gradle in the authoring environment, so the first sync in Android Studio is the first real compile. The version catalog is centralized precisely so any needed nudge is a one-line change. (Tracked expectation: a first-sync version tweak is plausible.)

### 2.4 Per-machine config overrides (optional)
Defaults live in `android/gradle.properties`. To override for your machine, copy `android/local.properties.example` → `android/local.properties` (gitignored) and set:
- `REALITYLOCK_BACKEND_BASE_URL` — use your LAN IP for a physical device (emulator uses `http://10.0.2.2:3000/` by default).
- `REALITYLOCK_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER` — from §4 below.

---

## 3. Firebase (Phase 5 — not required until then)
1. Create a project at [console.firebase.google.com](https://console.firebase.google.com/).
2. Add an **Android app** with package name `com.realitylock.app`.
3. Download **`google-services.json`** into `android/app/` (it is gitignored).
4. Enable **Firestore**, **Cloud Storage**, and **Authentication**.
5. Backend: set `FIREBASE_PROJECT_ID` / `FIREBASE_STORAGE_BUCKET` in `backend/.env`.
6. Wiring (Firebase Gradle plugin + SDKs) is uncommented in `android/app/build.gradle.kts` during Phase 5 per the phase plan.

## 4. Play Integrity + Google Cloud (Phase 3 — not required until then)
1. Create/reuse a **Google Play Console** app entry (internal testing track is enough — no public release needed).
2. Link it to a **Google Cloud project**; note the **project NUMBER**.
3. Enable the **Play Integrity API** in that Cloud project.
4. Put the project number in `android/local.properties` as `REALITYLOCK_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER`.
5. Token **decoding happens server-side only** — the backend holds the service-account credentials (Phase 3/5). Never decode on-device.

---

## 5. What each part proves (keep honest)
The cryptographic pipeline delivers **tamper-evidence** (integrity + authenticity of the captured bundle), **not** proof the depicted event is real, and **not** a standalone legal certificate. See [`docs/design/PROOF_PACKAGE_SPEC.md`](docs/design/PROOF_PACKAGE_SPEC.md) → Limitations, and `research/06_legal_standards_compliance.md` §7.
