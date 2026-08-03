# ADR-0002: External timestamping / anchoring strategy

- **Status:** Accepted (Phase 1, 2026-07-23)
- **Resolves:** `research/08_DECISIONS_REFERENCE.md` open item #1 (RFC 3161 in scope?)
- **Related:** `research/02_cryptography_security_architecture.md` §4–5; `research/08` backend-reconciliation table

## Context
An independent time anchor lets a verifier confirm the proof root existed at/before a point in time without trusting the device clock or Reality Lock's servers. Three mechanisms were researched:
- **OpenTimestamps (Bitcoin-anchored):** free, no accounts/keys/gas, Merkle-aggregated calendar servers; first-class Node package (`opentimestamps` on npm). Downside: minutes–hours confirmation latency.
- **RFC 3161 TSA (e.g. FreeTSA):** produces a TSA-signed token some legal contexts expect (aligns with BSA §63 "hash + algorithm" framing). Downside: **no mature single Node library**; requires ASN.1 handling or shelling out to `openssl ts` (`research/08` reconciliation table).
- **Custom blockchain (Polygon Amoy + Solidity):** fast, demoable, shows smart-contract literacy, but is net-new infrastructure.

The backend is **Node.js** (`research/03` §6), which makes RFC 3161 the awkward option and OpenTimestamps the natural one.

## Decision
**For the semester core (Phases 2–6), ship no external anchor as a hard dependency. Adopt OpenTimestamps (via the Node `opentimestamps` package) as the single anchoring mechanism, implemented as a Phase 7 stretch item. Defer RFC 3161 unless the legal-framing requirement is explicitly prioritized; defer the custom Polygon contract to a separate Phase 7 stretch.**

Rationale: anchoring is a "Future" line on the PPT itself, not a core guarantee. The core integrity/authenticity proof (hash + signature + hardware-backed key) stands without any anchor. When an anchor is added, OpenTimestamps is the lowest-friction, backend-native, zero-cost choice.

## Consequences
- **Positive:** no anchoring work blocks the Phase 2–6 critical path; the schema already carries optional `anchors.openTimestamps` and `anchors.chain`, so adding either later is additive; avoids sinking time into ASN.1/`openssl` glue for RFC 3161.
- **Negative / deferred:** no legally-recognized TSA token in v1. If the BSA §63 "stated hash + algorithm certificate" framing (`research/06` §1.3) is prioritized, RFC 3161 via a FreeTSA HTTPS POST (or `openssl ts` child process) becomes a scoped add-on — the schema needs a new optional `anchors.rfc3161` object at that point (a minor, non-breaking bump).
- **Neutral:** live-demo latency (OpenTimestamps confirmation lag) is a demo-scripting concern, not a design flaw — pre-stamp assets before a viva, or show the pending→confirmed flow across two runs.

## Implementation-status correction (Phase 7, 2026-08-03)

The decision above was made on the premise that OpenTimestamps is "the
lowest-friction, backend-native, zero-cost choice", resting on its **first-class
Node package**. That premise was tested when Phase 7 came to build it, and it no
longer holds. Recording this because the decision text otherwise reads as though
the path is still clear.

`npm install opentimestamps` (v0.4.9, the current release) was performed against
this backend and then reverted. It resolves clean on Node 22 — the API surface is
present and usable (`stamp`, `verify`, `upgrade`, `info`,
`DetachedTimestampFile.fromHash`, `Ops.OpSHA256`) — but its dependency tree
brings **11 advisories, two of them critical**:

| Package | Severity | Reached via |
|---|---|---|
| `request` | **critical** | direct dependency of `opentimestamps` |
| `form-data` | **critical** | via `request` |
| `bitcore-lib`, `bn.js`, `qs`, `request-promise` | moderate | via `opentimestamps` |
| `elliptic` | low | via `bitcore-lib` |

**There is no upgrade path.** `npm audit` reports the fix for both criticals as
`opentimestamps@0.0.0` — a non-existent version, npm's way of saying "remove the
package". The root cause is [`request`, deprecated since
2020](https://github.com/request/request/issues/3142) and unmaintained, so no
patched release is coming.

**Decision for Phase 7: not adopted.** Accepting two permanent, unfixable
critical advisories inside the service that *verifies evidence* is a poor trade
for a feature the PPT itself lists as "Future", and which the core proof (hash +
signature + hardware-backed key) does not depend on. The backend's audit is clean
and stays clean.

This does **not** overturn the strategic choice — OpenTimestamps remains the right
mechanism if it is wanted. What changed is its cost, and the options now are:
implement the calendar protocol directly over HTTPS without the package (the
protocol is simple; the `.ots` serialisation format is the real work, and
hand-rolling a proof format is exactly the kind of thing that should not be done
casually), vendor and patch the dependency, or wait for an updated release.

The schema's optional `anchors.openTimestamps` slot is untouched, so any of those
remains additive.

### What Phase 7 built instead

The **BSA 2023 s.63 draft annexure** (`research/06` §8 item 3) —
`StatutoryAnnexureContent`. It addresses the "stated hash + algorithm" framing
noted under Consequences above, but note precisely what it does and does not do:
it states the hash value and algorithm **from this project's own Merkle root**,
which is not an independent time anchor. A third party still has only the
device's word for *when* the capture happened. RFC 3161 and OpenTimestamps both
remain unimplemented, and the annexure does not substitute for either.

## Alternatives considered
- **RFC 3161 as the primary anchor:** rejected for v1 — poor Node ecosystem fit; revisit only if the legal framing is explicitly required.
- **Polygon Amoy custom contract as the primary anchor:** deferred — great for demonstrating smart-contract skills but is net-new infra; keep as an independent Phase 7 stretch (`ethers.js`, per `research/08`), not the default.
- **No anchor ever:** rejected — the PPT commits to "Blockchain Integration (Future)"; keeping the optional schema slots preserves that roadmap credibly.
