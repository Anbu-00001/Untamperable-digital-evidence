# ADR-0001: Merkle leaf strategy for the proof package

- **Status:** Accepted (Phase 1, 2026-07-23)
- **Resolves:** `research/08_DECISIONS_REFERENCE.md` open item #5
- **Related:** `research/02_cryptography_security_architecture.md` §1; [`../proof-package.schema.json`](../proof-package.schema.json)

## Context
The signature must bind the media to its context so neither can be swapped. Two hashing structures were on the table:
- **2-leaf:** `root = SHA-256(mediaHash ‖ metadataHash)` — metadata is one canonical blob.
- **5-leaf Merkle tree:** separate leaves for `media`, `location`, `timestamp`, `motion`, `device`, enabling **selective disclosure** later (reveal that GPS was valid to a verifier without exposing raw motion/device data), via the sibling-hash inclusion proof technique.

The 5-leaf design is strictly more capable but adds tree-construction, leaf-ordering, and inclusion-proof code — none of which is needed for the core "detect any tampering" guarantee, and selective disclosure has no consumer in the Phase 1–6 scope.

## Decision
**Implement the 2-leaf root in v1. Design the schema and constants to accommodate 5-leaf, and document 5-leaf as the selective-disclosure target for future work.**

Concretely:
- `merkle.scheme` accepts `"2-leaf"` and `"5-leaf"`; the schema already validates both leaf sets.
- Android `CryptoConfig.MERKLE_LEAVES_IMPLEMENTED = 2`, `MERKLE_LEAVES_TARGET = 5`.
- The verifier keys behavior off `merkle.scheme`, so upgrading later is additive, not breaking.

## Consequences
- **Positive:** minimal, correct integrity binding shippable this semester; the "designed-for" 5-leaf story is credible in the report because the schema/verifier already tolerate it; no wasted effort on an unused disclosure feature.
- **Negative / deferred:** selective disclosure (privacy-preserving partial reveal) is not available in v1. If an evidentiary/privacy use case needs it (e.g. reveal location but not device identity), it becomes a scoped Phase 7 item — no schema break required.
- **Neutral:** both schemes sign a single 32-byte root, so the signing/verification code path is identical.

## Alternatives considered
- **5-leaf now:** rejected — build cost and bug surface (leaf ordering, odd-node duplication, inclusion proofs) outweigh benefit given no v1 consumer of selective disclosure.
- **Hash flat concatenation of all fields (no metadata sub-hash):** rejected — forces revealing every field to verify any field and muddies the media/metadata boundary.
