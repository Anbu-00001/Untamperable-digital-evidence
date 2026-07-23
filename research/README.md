# Reality Lock — Research Corpus & Handoff Index

**Project:** Reality Lock — Tamper-Evident Event Proof System for Mobile Devices
**Course:** Embedded Programming Project / Mobile Application Development, Dept. of CSE
**Team:** Rakesh S, Anbuchelvan Ganesan
**This folder produced:** 2026-07-23, as deep-research groundwork ahead of implementation.

---

## What this folder is

The team's Zeroth Review PPT (`../Embedded Programming review 0 template (2).pptx`) pitches a mobile app that captures a real-world event (photo/video + GPS + timestamp + motion sensors), cryptographically hashes and signs it, and produces a tamper-evident "proof package," with a verification module and future AI/blockchain extensions. The PPT itself is 10 slides of vision and a one-line-per-topic literature survey — enough to present, not enough to build from.

This folder is the missing layer between that pitch and actual implementation: **7 independent deep-research passes** (each run as a separate research agent, searching the live web) covering every technology and domain area the PPT touches, followed by **3 synthesis documents** that reconcile, prioritize, and sequence everything into a buildable plan. Nothing in here was guessed — every library, API, paper, and legal citation was verified to actually exist via live search on 2026-07-23; where something couldn't be found, that's stated explicitly rather than papered over.

**If you're picking this up to start building: read `08_DECISIONS_REFERENCE.md` and `09_PROJECT_PHASES.md` first.** Everything else is depth/citations to pull from as needed.

---

## Reading order

| # | File | What it is | Read this when... |
|---|---|---|---|
| 00 | `00_PPT_TRANSCRIPT.md` | Word-for-word transcript of all 10 PPT slides, plus a manual description of the Slide 9 block diagram (which is an image, not text) | You need to check what the team already committed to presenting |
| 08 | **`08_DECISIONS_REFERENCE.md`** | **Start here.** Every design question across all 7 research files, resolved into one flat table, plus the one real cross-document inconsistency (Node.js backend vs. JVM-oriented crypto examples) explicitly reconciled | You want the "what do we build with" answer without reading 2,400 lines |
| 09 | **`09_PROJECT_PHASES.md`** | **Then here.** The PPT's 4 blunt phases expanded into 8 concrete, sequenced sub-phases with exact APIs, exit criteria, and a 15-week timeline | You're ready to start assigning work / writing code |
| 01 | `01_domain_competitive_landscape.md` | C2PA, Truepic, ProofMode, Numbers Protocol, Serelay, Starling Lab, eyeWitness to Atrocities, InVID/WeVerify — full competitive landscape, comparison table, and why blockchain alone doesn't prove event authenticity | You need literature-survey-grade competitor analysis or want to know what to architecturally borrow/avoid |
| 02 | `02_cryptography_security_architecture.md` | Hashing, signing, Keystore/StrongBox, Play Integrity, RFC 3161, OpenTimestamps, blockchain anchoring, GPS spoofing, and the precise "tamper-evident vs. tamper-proof" nuance — includes working Kotlin code and a Solidity contract | You're implementing Phase 3 (crypto) or Phase 7 (blockchain stretch) |
| 03 | `03_mobile_tech_stack.md` | Android architecture, CameraX, FusedLocationProvider, sensors, backend/storage choice, offline sync, testing strategy, full Gradle dependency list | You're implementing Phase 2 (capture) or Phase 5 (backend/sync) |
| 04 | `04_ai_deepfake_media_forensics.md` | Why a heavy deepfake classifier is the wrong scope for this semester, and why ELA + EXIF checks are the right one — with the reasoning spelled out in full | You're implementing Phase 4's AI layer, or need to justify that scope choice to a reviewer |
| 05 | `05_open_source_connectors.md` | Concrete, verified-real GitHub repos/libraries by category, each with license + how Reality Lock would use it, ending in a decisive pick list | You're choosing a specific dependency and want the real repo URL and license |
| 06 | `06_legal_standards_compliance.md` | Section 65B/BSA Section 63, ISO/IEC 27037, India's DPDP Act 2023 vs. GDPR, insurance/journalism industry practice, ACPO/NIST forensics principles, e-Sakshya (India's own near-identical government app) — plus a precise, citable "what this proves / doesn't prove" statement | You're writing the ethical-considerations/limitations section, or designing consent/privacy features |
| 07 | `07_academic_literature_survey.md` | 28 real, independently-verified academic papers across 9 topic areas, each with title/authors/year/venue/summary/DOI, ending in a synthesized research-gap paragraph | You're replacing the PPT's thin 4-bullet literature slide with a real one for the next review |

---

## The single most important synthesis, in three sentences

**What Reality Lock is, precisely:** a mobile system that hashes and signs a bundle of media + GPS + timestamp + motion-sensor data at the moment of capture, using a hardware-backed key, so that any modification after that moment is cryptographically detectable. **What it demonstrably is not, and must not claim to be:** proof that the depicted real-world event was genuine/unstaged — that gap (the "trusted capture boundary" / analog-hole problem) is unsolved even by industry leaders like C2PA and Truepic, and stating this honestly on the literature survey slide is itself a mark of rigor, not a weakness to hide (`02` §7, `06` §7). **Where the actual novelty is:** not in any single cryptographic primitive (SHA-256 + ECDSA is well-trodden ground), but in *combining* hardware-backed signing + multi-sensor fusion + free timestamp anchoring + fully offline verifiability in one cohesive, open, mobile-native system — a combination no single existing open-source project currently offers end-to-end (`01` §8, `07` Synthesized Research Gap).

---

## Key facts worth remembering from this research

- **Closest open-source prior art:** [ProofMode](https://github.com/guardianproject/proofmode-android) (Guardian Project/WITNESS, GPL-3.0) — study its capture→hash→sensor-CSV→sign pipeline directly.
- **Closest real-world government precedent:** **e-Sakshya**, India's own NCRB/Home Ministry app (launched Aug 2024) — nearly identical architecture (hash-at-capture + geo/timestamp + custody log), scoped to police evidence rather than citizen capture.
- **SafetyNet is dead** (shut down 20 May 2025) — the PPT/any older tutorial referencing it must use **Play Integrity API** instead.
- **Serelay, a well-funded competitor in this exact space, dissolved in March 2025** — a real cautionary data point for why offline-verifiable proofs (not "call our server forever") is the right design philosophy.
- **A cryptographic hash+signature is not a legal certificate by itself** — Indian law (BSA 2023 Section 63) requires dual human certification (device custodian + independent expert); Reality Lock produces the technical evidence, not the legal certificate.
- **"AI: Deepfake & Authenticity Detection" (PPT Slide 8) should mean ELA + EXIF checks this semester, not a deepfake classifier** — the strong pretrained models are GPU-sized and license-gated; ELA/EXIF map better onto the project's actual threat model (splicing, false re-uploads) anyway.

---

## Provenance / methodology note

Each of files `01`–`07` was produced by an independent research agent instructed to search the live web repeatedly, cite every claim with a real, checkable URL, and state explicitly (rather than fabricate) when a search area came up thin. This `README.md`, `08_DECISIONS_REFERENCE.md`, and `09_PROJECT_PHASES.md` were then written by a synthesis pass that read all 7 files in full, cross-checked them against each other, found and resolved the one real inconsistency between them (backend-language mismatch in the blockchain/timestamping library recommendations — see `08`), and sequenced everything into the phased plan. Treat `01`–`07` as primary research material and `08`–`09` as the actionable digest.
