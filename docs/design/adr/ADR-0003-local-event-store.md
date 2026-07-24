# ADR-0003: Local event store — JSON sidecar files now, Room deferred to Phase 5

- **Status:** Accepted (Phase 2, 2026-07-23)
- **Supersedes (in part):** the "Local queue: Room `ProofPackageEntity`" line under Phase 2 in `research/09_PROJECT_PHASES.md`
- **Related:** `research/03_mobile_tech_stack.md` §8 (offline-first sync); `research/01_domain_competitive_landscape.md` §3 (ProofMode's sidecar model)

## Context
Phase 2 needs somewhere to persist a captured event (media path + metadata) before Phase 5 adds upload/sync. The plan called for Room + WorkManager, following the offline-first recommendation.

Two things emerged when Room was actually wired in:

1. **Build performance is pathological.** With the KSP plugin applied and Room on the classpath, `:app:kspDebugKotlin` ran for **12+ minutes at sustained CPU** on a project that contains **zero Room entities** — there was literally nothing to process. A multi-minute penalty on every build is a severe tax on a two-student team's iteration loop.
2. **Room is not what Phase 2 actually needs.** Room's value is *queryable state* — "which packages are PENDING, which FAILED, retry them". That need appears in **Phase 5** (sync), not Phase 2 (capture). In Phase 2 the store is append-and-list.

Notably, the dependency-compatibility question was answered favourably: CameraX 1.5.0, play-services-location 21.3.0 and Room 2.7.0 all **pass** `checkAarMetadata` on AGP 8.13.0. This decision is about build cost and fit, not compatibility.

## Decision
**Persist each captured event as a JSON sidecar file alongside its media, behind an `EventRepository` interface. Defer Room (and the KSP plugin) to Phase 5, where sync-status queries make it worthwhile.**

- `EventRepository` — the interface capture code depends on (`save`, `list`, `findById`, `delete`).
- `FileEventRepository` — the Phase 2 implementation: media at `<app-files>/captures/<eventId>.jpg`, metadata at `<eventId>.json`.
- Phase 5 may add `RoomEventRepository` implementing the same interface; **no capture-side code changes**.

## Consequences
- **Positive:** zero added dependencies and no annotation processor, so builds stay fast; the sidecar layout mirrors the **ProofMode** model (original media untouched, proof data in adjacent files), which is the closest open-source prior art; the JSON on disk is the *same shape* as the proof package the backend already validates, so it is inspectable with `adb pull` + any JSON tool during development; the store is trivially unit-testable with a temp directory and no framework.
- **Negative / deferred:** no indexed queries or transactions. Listing is a directory scan, which is fine at the scale of one user's captures but would not suit thousands of records — exactly the point at which Phase 5 should introduce Room behind the existing interface.
- **Neutral:** the interface boundary means this is reversible at low cost; it is a sequencing decision, not a permanent architectural commitment.

## Alternatives considered
- **Room now, accept the build cost:** rejected — minutes-per-build for a feature whose benefit only materialises in Phase 5.
- **Room now, investigate the KSP slowness first:** rejected for this phase — the investigation is unbounded and blocks the actual Phase 2 deliverable (the capture pipeline). Worth revisiting in Phase 5, by which time the project may also have moved to the AGP 9 / Kotlin 2.4 line where the toolchain combination differs.
- **SQLite via `SQLiteOpenHelper` (no KSP):** rejected — reintroduces hand-written SQL and migration handling for no benefit over files at this scale.
