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

## Alternatives considered
- **RFC 3161 as the primary anchor:** rejected for v1 — poor Node ecosystem fit; revisit only if the legal framing is explicitly required.
- **Polygon Amoy custom contract as the primary anchor:** deferred — great for demonstrating smart-contract skills but is net-new infra; keep as an independent Phase 7 stretch (`ethers.js`, per `research/08`), not the default.
- **No anchor ever:** rejected — the PPT commits to "Blockchain Integration (Future)"; keeping the optional schema slots preserves that roadmap credibly.
