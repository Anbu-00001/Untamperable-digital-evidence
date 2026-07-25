# ADR-0006 — Phase-5 scope: sync, storage, and the verification module

**Status:** Accepted · **Date:** 2026-07-25 · **Phase:** 5
**Refines:** `research/09_PROJECT_PHASES.md` Phase 5, `research/02` §8 Steps 8/10, `research/03` §6/§8
**Related:** ADR-0003 (local event store), ADR-0004 (attestation over Play Integrity), ADR-0005 (Phase-4 scope)

## Context

Phase 5 was planned as "Backend, Storage & Verification Module": Room + WorkManager
offline sync, Retrofit networking, Firebase Firestore + Cloud Storage persistence,
the five verification checks, an Authenticity Result UI, a QR badge, and a PDF
certificate.

Five parts of that plan did not survive contact with current pricing, current
library reality, or this project's own threat model. This ADR records what was
built and why it differs.

## Decisions

### 1. Media does not go to Firebase Cloud Storage — it is not free any more

**Cloud Storage for Firebase now requires a linked billing account.** Google
aligned it with standard Google Cloud Storage rules on **2026-02-03**: creating or
maintaining a bucket requires the Blaze plan regardless of volume, and a project
left on Spark gets HTTP 402/403 on every bucket call. Firestore is unaffected — it
remains free on Spark with a 1 GiB / 20 000-writes-per-day quota and **no card**.

The same $0-and-no-card constraint that produced ADR-0004 (key attestation instead
of a $25 Play Console account) applies here. So:

- **Proof packages** (small JSON) → the backend's own store, with **Firestore as a
  config-gated adapter** the team can switch on for free by creating a Spark
  project.
- **Media** (large bytes) → a **content-addressed, immutable** object store on the
  backend. Never Firebase.

This costs the project nothing in evidentiary terms, because **the proof package
binds the media by hash, not by location**. Where the bytes live is a durability
question, not an authenticity one. The authoritative copy stays on the device;
the server copy exists so a verifier can recompute the media leaf.

Render's free tier has an **ephemeral filesystem and cannot attach a persistent
disk**, so the filesystem store is honestly documented as durable only for local
and self-hosted deployments; Firestore is the durable option for the packages.

### 2. No Retrofit and no Gson — never parse-and-re-serialize a signed document

The proof package is a document whose bytes were hashed and signed. Routing it
through a Gson object model and re-serializing it on the way out introduces a
class of bug this project cannot tolerate: a change in number formatting, integer
width, or escaping would silently break `metadataHashMatch`, and the failure would
look exactly like tampering.

The app therefore **transmits the exact bytes it stored**, with plain OkHttp and a
`String` body. The rule is general and worth stating: *evidence is forwarded, never
re-encoded.* This also drops two dependencies and sidesteps the Retrofit-3-targets-
OkHttp-4.12 version skew entirely.

OkHttp is bumped to **5.4.0** (current release per Maven metadata; the catalog's
5.1.0 was stale).

### 3. Sync state lives beside the signed package, not inside it — and no Room

ADR-0003 chose JSON sidecars over Room and removed KSP from the build. Phase 5 was
the point at which Room was to return "when sync-status queries make that
worthwhile." They do not:

- The only queries needed are *list all*, *get by id*, and *update one status* —
  no joins, no indices, no relational power.
- Room would mean re-adding the KSP plugin ADR-0003 removed for build time.
- More important: decomposing an immutable signed document into mutable columns
  and re-assembling it on read re-introduces exactly the re-encoding hazard that
  decision 2 rejects.

Sync state **is** mutable, so it is stored in a **separate** sidecar,
`<eventId>.sync.json`, leaving `<eventId>.json` — the signed package — **write-once
and never rewritten after capture**. This is a stronger integrity property than
the original plan had, not a weaker one.

WorkManager is bumped to **2.11.2** (minSdk 23, below our 28; compileSdk 33+,
below our 36). A plain `CONNECTED`-constrained deferrable worker is unaffected by
the Android 15/16 background-work changes, which tightened foreground-service and
`JobScheduler` quotas — WorkManager manages the job lifecycle on the app's behalf
and is explicitly called out as unimpacted.

### 4. QR generation uses `com.google.zxing:core` alone

`zxing-android-embedded` is a **scanner**: a capture Activity, a camera preview,
and the camera permission that comes with them. Phase 5 needs QR *generation*
only — a verification badge and an image embedded in the PDF certificate. That is
`QRCodeWriter` → `BitMatrix` → `Bitmap`, and it needs nothing but zxing core
(**3.5.4**). Adding a camera-permission-bearing scanner dependency for a feature
we do not build would be unjustifiable. A scan flow can add the wrapper later.

### 5. Verdict semantics are pinned, and non-blocking concerns become `advisories`

Before Phase 5 the verdict could never be `verified`, because `timestampPlausible`
and `locationPlausible` reported `not_implemented` and any non-`pass` held the
verdict at `incomplete`. Both are now implemented, so the semantics must be stated
rather than emergent:

Two rules, applied in order:

1. **`failed`** — **any** check returned `fail`. There is no partial credit for
   tamper-evidence, and this deliberately reaches beyond the cryptographic checks:
   an attestation chain that is present but does not bind to the signing key, or a
   physically impossible implied speed, are real findings even though neither one
   is "the media was altered".
2. Otherwise every **decisive** check must have passed — `mediaHashMatch`,
   `metadataHashMatch`, `merkleRootMatch`, `signatureValid`,
   `timestampPlausible`. One that could not be run yields **`incomplete`** (most
   often `mediaHashMatch`, when the media was not supplied). All passing yields
   **`verified`**.

The decisive line is drawn at "does this check answer the question the verdict
claims to answer" — is this bundle unaltered since capture and signed by the key
it names. So a check outside the set going `unavailable` raises an **advisory**
and leaves the verdict alone: a missing attestation chain, or `locationPlausible`
being unavailable because this is the first capture from that install.

This is why a missing attestation chain now reports `unavailable` rather than the
`fail` it used to. Absence of a chain is the absence of evidence, not evidence of
a defect, and the module's own rule is that the two must never be conflated. A
chain that is present but invalid still fails, as it should.

`isMock` being true is likewise an advisory rather than a failure, and the wording
matters: the signature is genuine, the position it covers is not trustworthy.
Spoofing happens *before* capture, so it is not a tamper-evidence finding — but it
must be impossible to miss.

`verified` continues to ship with the `limitations` list, so it can never be
presented as proof the depicted event was real.

### 6. Location plausibility is verify-side and history-dependent, per ADR-0005

ADR-0005 established that the authoritative speed/distance check belongs to the
verifier, recomputed from the signed metadata of consecutive events. That requires
history, which is why it lands with the store in this phase: the verifier looks up
the previous stored event **for the same `installId`** and recomputes the
Haversine implied speed against `IntegrityConfig`'s bound. With no prior event the
result is `unavailable` — never a pass, never a fail.

The device-side `integrity.location.speedPlausible` remains advisory and unsigned;
the verifier's recomputation is what counts, and the two are reported separately
so a disagreement is visible rather than reconciled away.

### 7. The public verification endpoint returns a verdict, not the metadata

The QR badge encodes a URL to `GET /verify/:eventId`. The `eventId` is an
unguessable v4 UUID and acts as a capability token, but the package it names
contains **GPS coordinates**. Returning the full package to anyone who scans the
code would turn a verification badge into a location leak, which the DPDP
obligations in `research/06` do not permit.

So the public endpoint returns **only** the per-check breakdown, the verdict, the
advisories, and the limitations. Retrieving the package itself is a separate
route. Authentication and rate-limiting are out of scope for this phase and
recorded below as a known gap.

## Verification

- Backend unit tests covering the plausibility checks, the immutable store's
  refusal to overwrite, hash-enforced media upload, and the verdict/advisory rules.
- Android unit tests for the pure sync-state and QR/certificate logic.
- Offline sync exercised on the physical CPH2591 in **airplane mode**: captures
  queue, and drain automatically when connectivity returns.
- End-to-end: a stored package verifies from the server's own copy of the media
  (so `mediaHashMatch` can reach `pass` without the client re-uploading), and an
  edited field flips the correct specific check.

## Consequences

- The project reaches Phase 5's exit criteria at **$0 with no payment card**, and
  the one criterion that literally named Firebase is met for packages via a free
  Spark project while the media path is documented as deliberately non-Firebase.
- The signed package file is now provably write-once on device.
- Content-addressing plus hash enforcement gives the media endpoint a useful
  property: the server will only accept bytes that a validly-signed package
  already commits to.
- **Known gaps (not built):** no authentication or rate limiting on the backend —
  anyone who can reach it can submit or verify, which is acceptable for a coursework
  deployment and is Phase-6 hardening work. Filesystem persistence does not survive
  a redeploy on a free-tier host. Firestore adapter durability is only as verified
  as the credentials available at the time of writing.
