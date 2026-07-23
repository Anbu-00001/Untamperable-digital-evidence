# Source PPT — Full Transcript

Source file: `Embedded Programming review 0 template (2).pptx` (Zeroth Review, Embedded Programming Project)
Department of Computer Science and Engineering — Mobile Application Development
Date on slides: 27-06-2026
Authors: **Rakesh S**, **Anbuchelvan Ganesan**

Transcribed word-for-word from all 10 slides (text extracted from XML + block diagram image described manually). Nothing paraphrased or omitted.

---

## Slide 1 — Title
Department of Computer Science and Engineering
MOBILE APPLICATION DEVELOPMENT
**Reality Lock: Tamper-Evident Event Proof System for Mobile Devices**
Supervisor
Presentation by
RAKESH S
ANBUCHELVAN GANESAN
EMBEDDED PROGRAMMING PROJECT — ZEROTH REVIEW

## Slide 2 — Contents
- Introduction
- Problem Statement
- Project Description
- Aim and Scope of the Proposed work
- Literature Survey
- Hardware Component
- Block Diagram Design
- Project Planning

(27-06-2026, page 2)

## Slide 3 — Introduction
In today's digital world, mobile devices capture millions of real-world events through photos, videos, sensor data, and communication records. However, current digital evidence can be easily manipulated using AI editing tools, metadata modification, deepfakes, and unauthorized alterations.

Examples:
- Fake accident videos
- Modified screenshots
- Edited surveillance footage
- False location claims
- Manipulated digital documents

There is a growing need for a system that can prove:
> "This event actually happened, at this exact time and location, and the evidence has not been modified."

(27-06-2026, page 3)

## Slide 4 — Problem Statement
**Existing Problem**
Current mobile systems store data but do not guarantee authenticity. A photo or video can be:
- Edited after capture
- Timestamp changed
- Location manipulated
- AI-generated
- Re-uploaded with false context

Existing solutions verify files, but they cannot verify the complete event context.

**Problem Definition**
> "Develop a mobile-based system that generates a tamper-evident digital certificate for real-world events by securely capturing, validating, and locking event information at the moment of occurrence."

**Real-World Impact Areas**
- Legal evidence verification
- Insurance claim validation
- Emergency response
- Journalism authenticity
- Industrial safety monitoring
- Digital identity protection

(27-06-2026, page 4)

## Slide 5 — Project Description
Reality Lock is a mobile security system that creates a tamper-proof digital proof certificate for real-world events.

**Captures:** 📷 Media, 📍 Location, ⏱ Timestamp, 📱 Sensor Data

**Process:** Event Data → Hash Generation → Digital Signature → Secure Storage

**Key Feature:** Any modification changes the hash: Original Hash ≠ New Hash → Tampering Detected

**Output:** Verified event proof with authenticity, time, and location validation.

(27-06-2026, page 5)

## Slide 6 — Aim and Scope of the Proposed Work
**Aim:** Develop a digital trust system that creates secure and verifiable proof of real-world events using mobile devices.

**Objectives:**
- ✓ Prevent evidence manipulation
- ✓ Verify event authenticity
- ✓ Reduce digital fraud
- ✓ Build trust in mobile data

**Scope**
- Short-Term: Mobile application, Secure media capture, Hash & timestamp verification, Location authentication
- Long-Term: Integration with Smart Cities | Law Enforcement | Insurance | Healthcare | IoT

**Vision:** "Proof of Reality in the Digital Age"

(27-06-2026, page 6)

## Slide 7 — Literature Survey
**Existing Technologies**
- Digital Watermarking → Protects ownership but can be removed or modified.
- Blockchain Evidence Storage → Provides data immutability but does not verify real-world event authenticity.
- Digital Signatures → Verify sender identity but not event conditions.
- AI Deepfake Detection → Detects manipulation but cannot prove original capture time.

**Research Gap:** Existing methods verify data, but not the complete reality of an event.

**Proposed Improvement:** Reality Lock combines Cryptography + AI + Sensor Fusion + Secure Logging to create trusted event proof.

(27-06-2026, page 7)

## Slide 8 — (Hardware/Software) Component
- 📱 Frontend: Android (Kotlin)
- ⚙️ Backend: Node.js / Python
- 🔐 Security: SHA-256 Hashing + Encryption
- ☁️ Storage: Firebase / Cloud Database
- 🤖 AI: Deepfake & Authenticity Detection
- 📍 Sensors: GPS + Camera + Motion Sensors
- ⛓ Future: Blockchain Integration

(27-06-2026, page 8)

## Slide 9 — Block Diagram Design
Title: **"REALITY LOCK – SYSTEM BLOCK DIAGRAM"**

Six-stage pipeline (left to right, described exactly as drawn):

1. **Event Capture (Mobile Device)** — Image/Video, GPS Location, Timestamp, Motion Sensors (Accelerometer, Gyroscope), Device Identity & Info, Environmental Conditions
2. **Data Processing** — Data Aggregation & Validation
3. **Security Engine** — Cryptographic Hash Generation (SHA-256) → Digital Signature Generation
4. **Proof Package Creation** — checklist: Original Media File, Cryptographic Hash, Secure Timestamp, GPS Location Proof, Sensor Data, Environmental Data, Digital Signature
5. **Secure Storage** — Cloud Storage / Blockchain Ledger (Immutable & Tamper-Evident)
6. **Verification Module** (below, fed from Secure Storage AND from a parallel "Tamper Detection" box) — checklist: Hash Match, Signature Verify, Time Validate, Location Verify, Integrity Check → **Authenticity Result (Valid / Tampered)**

Parallel branch under stage 3→4: **Tamper Detection** — "If any data is modified, Old Hash ≠ New Hash → Tampering Detected" — feeds into Verification Module.

**Flow Summary strip (bottom left):** Capture → Process → Secure → Create Proof Package → Store Securely → Verify Authenticity

(27-06-2026, page 9)

## Slide 10 — Project Planning
- **Phase 1: Research & Design** — Problem analysis, System architecture design
- **Phase 2: Development** — Android app development, Sensor & camera integration, Hash generation module
- **Phase 3: Security Implementation** — Encryption, Digital signature, Tamper detection
- **Phase 4: Testing & Deployment** — Accuracy testing, Security validation, Final prototype

(27-06-2026, page 10)

---

## Images in the deck
- `image2.png` — used on slide 1 (title slide decoration/institution logo, not content-bearing).
- `image3.png` — the Slide 9 block diagram, described in full above.
- `image1.png` — present in `ppt/media/` but not referenced by any slide relationship (likely an unused master/layout background asset).

## Notes on completeness
- There are no speaker notes (`ppt/notesSlides/`) in the file.
- All 10 slides, every bullet, every icon-label, and the one embedded diagram have been transcribed above with nothing skipped.
