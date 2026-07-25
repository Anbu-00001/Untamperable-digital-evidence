# Phase 5 — sync, storage and verification evidence

How the Phase-5 exit criteria were checked, and what the check actually proves.
Reproduce with:

```bash
./scripts/e2e/run_sync_e2e.sh
```

It needs a USB-connected device with camera + location already granted, and it
leaves the phone exactly as it found it.

## What the script does that a unit test cannot

- Puts the **real device into airplane mode** (`cmd connectivity airplane-mode
  enable`) and confirms `Active default network: none` before capturing, so the
  offline case is genuinely offline rather than simulated.
- Routes the phone to a backend on the laptop over **`adb reverse`**. A USB
  reverse tunnel is unaffected by airplane mode, while the *system* network state
  — which is what WorkManager's `CONNECTED` constraint reads — really does go
  down. That separation is what makes the test honest: the app cannot reach the
  server while offline, and needs no LAN or internet when it can.
- Restores connectivity and then **taps nothing**. The queued upload has to fire
  by itself, which is the whole claim.

## Exit criteria, and how each is evidenced

| Criterion | How it is shown |
|---|---|
| Captures survive having no connectivity | Sidecar written on device while `Active default network: none`; backend still holds 0 packages |
| Syncs automatically when connectivity returns | Airplane mode off, no user action, backend package count goes to 1 |
| Backend stores the package | `packages/<eventId>.json` present, and byte-compared against the device's own copy |
| Media is stored intact | Object named `media/<sha256>`; the file is re-hashed and must equal its own name |
| Verification reproduces "Valid" | `POST /verify` → `verdict: verified`, all five checks pass |
| A manually-edited field reproduces "Tampered" | Latitude altered → `verdict: failed`, `metadataHashMatch: fail` |

## Beyond the required criteria

- **Immutability.** Re-POSTing the identical package returns `200` (the sync
  worker retries and cannot know whether an earlier attempt landed, so a retry
  must not be an error), while a package with an altered field under the same
  `eventId` is refused with `409`.
- **Hash-enforced media.** Uploading bytes that do not hash to the digest in the
  signed package is refused `409` and **not stored** — so the endpoint is not an
  open file drop.
- **Byte-exactness.** The stored package on the server is compared against the
  device's copy. Those bytes are what the signature covers, so a re-encode
  anywhere in the sync path would show up here.
- **Privacy of the QR endpoint.** `GET /verify/<eventId>` is asserted to contain
  neither the package nor the recorded coordinates: a scannable badge must not
  double as a location leak (ADR-0006 §7).
- **The cross-event location check.** Three captures are taken, not one. The
  check compares against the most recent earlier event *that has a location*, and
  the airplane-mode capture necessarily has none — there is no GNSS in airplane
  mode. So the first online capture still reports `unavailable`, correctly, and
  only the capture after *that* has a located predecessor to be measured against.
  A test that stopped at two would never exercise the half of the check that
  needed the store, and this is exactly what the first run of this script
  revealed.

  Where no GNSS fix is available at all (indoors), the script accepts
  `unavailable` and says so rather than forcing a pass. The teleportation and
  legitimate-air-travel cases are covered against real signed packages in
  `backend/test/proofVerifier.test.js`.

## Two things this does NOT establish

1. **The backend is unauthenticated.** Anyone who can reach it can submit or
   verify. That is acceptable for a coursework deployment and is Phase-6
   hardening work, but it is not a property this evidence claims.
2. **Filesystem persistence is not durable on free hosting.** Render's free tier
   has an ephemeral filesystem and cannot attach a persistent disk, so a redeploy
   discards the store. The test runs against a local backend where the disk is
   real. Firestore is the free, card-free durable option for packages
   (ADR-0006 §1).

## Note on `adb shell pm grant`

This device (ColorOS/OxygenOS) refuses shell-granted runtime permissions with
`SecurityException: Neither user 2000 nor current process has
GRANT_RUNTIME_PERMISSIONS`. The script therefore **verifies** the granted state
via `dumpsys` and stops with an instruction rather than assuming a grant
succeeded, and it resets only `files/captures` and `files/sync` via `run-as`
instead of `pm clear` — `pm clear` would revoke permissions that cannot then be
restored from the shell.
