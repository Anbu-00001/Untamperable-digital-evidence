# Phase 7 — stretch scope: what was built, what was refused, what remains

`research/09_PROJECT_PHASES.md` Phase 7 lists eight stretch items and sets an
unusually honest exit criterion:

> **Exit criteria for the report, if none of Phase 7 is built:** these remain
> honestly documented as designed-but-not-implemented future work […] this is not
> a gap to hide, it's exactly the scope the source material anticipated.

This file is that record. One item was built, one was investigated and
deliberately rejected on evidence, and six were not attempted — each with the
reason stated.

## Status at a glance

| Item | Status | Why |
|---|---|---|
| Section 63 (BSA) certificate template | **Built** (content layer) | Pure Kotlin, no new dependencies, JVM-testable |
| OpenTimestamps anchoring | **Rejected on evidence** | Package carries 2 unfixable critical CVEs — see ADR-0002 |
| Polygon Amoy contract | Not attempted | Needs a wallet, testnet funds and a Remix deploy — external setup, not code |
| TFLite MesoNet classifier | Not attempted | Needs a device and model conversion; also the highest overclaim risk in the list |
| PRNU offline demo | Not attempted | Explicitly "not integrated into the app" in the plan; a notebook, not product work |
| C2PA manifest export | Not attempted | `c2pa-android` needs device builds; host disk is full |
| WiFi/cell-tower cross-check | Not attempted | Cannot be developed or tested without a device |
| Bystander privacy (face blur, indicator) | Not attempted | Same |

The six "not attempted" items were blocked by two environmental constraints, not
by design problems: **no test device available**, and the **host disk at 100%**
(≈1.6 GB free), which also prevents the emulator from starting. Neither is a
statement about their feasibility.

---

## Built: BSA 2023 s.63 draft annexure

`StatutoryAnnexureContent` — `research/06` §8 item 3.

A document shaped like a s.63(4)/Schedule certificate: hash value, algorithm,
device particulars and production method, for a human certifier to complete and
sign. `research/06` §1.3 is the whole design brief, and it is mostly a list of
things this document must *not* claim:

- a s.63(4) certificate must be signed by a **natural person** in a responsible
  position, and the BSA additionally requires an **independent expert** — dual
  certification;
- it must originate from whoever **controls the device**, not an app vendor;
- it must speak to matters this system cannot know: that the device "was
  operating properly throughout", that information was fed in the ordinary course
  of activity, and chain-of-custody facts after the file leaves the phone.

So the feature's substance is its refusals, and they are enforced structurally
rather than left to whoever assembles the content:

- **`SignatoryBlock` has no name, signature, date or credential field.** Not
  "must be left blank" — the fields do not exist, so the app cannot emit a
  document that looks executed, and a later refactor cannot auto-fill what is not
  there.
- **An unsigned event is refused outright.** The Schedule's hash value is the one
  legally load-bearing field; a statutory-looking form with a blank where the
  hash belongs is worse than no form. This mirrors `ForensicAnalyzer` refusing an
  unreadable image rather than synthesising a finding.
- **Fewer than two signatory blocks is refused**, because printing one would
  quietly understate the dual-certification requirement to the person signing it.
- **The draft notice and the "matters requiring human attestation" list cannot be
  empty** — an annexure listing no such matters implies the technical output
  satisfies the whole statutory test.
- Device particulars carry the **install UUID, never IMEI/ANDROID\_ID**
  (`research/03` §5), asserted by test — a "particulars of the device" section
  being exactly where a hardware identifier would be tempting.

Seven unit tests, all passing; five of them assert a refusal. The guards are
constructor `require`/`error` calls exercised through `assertThrows`, so removing
a guard fails its test directly.

### Not done for this item

**Rendering and UI wiring.** `CertificateRenderer` draws through Android's
`PdfDocument`/`Canvas`, which has no JVM stand-in — it is instrumented-only, and
no device was available. The content layer is where "the evidentiary and legal
consequences" live (the existing `CertificateContent` says exactly this), and it
is complete and tested; drawing it to a page and exposing an export button is
not, and should not be counted as done.

---

## Rejected on evidence: OpenTimestamps

Full record in **ADR-0002's implementation-status correction**. Summary:

ADR-0002 chose OpenTimestamps as "the lowest-friction, backend-native, zero-cost
choice", resting on its first-class Node package. Building it in Phase 7 tested
that premise and it failed. `opentimestamps@0.4.9` installs and works on Node 22,
but its tree carries **11 advisories including two critical**, via
[`request`, deprecated since 2020](https://github.com/request/request/issues/3142).
`npm audit` gives the fix as `opentimestamps@0.0.0` — npm's way of saying
"remove it". There is no patched release to wait for.

Two unfixable criticals inside the service that *verifies evidence*, for a
feature the PPT lists as "Future" and which the core proof does not depend on, is
a bad trade. The package was uninstalled; the backend audit is **clean, 0
vulnerabilities**.

This does not overturn the strategy. The `anchors.openTimestamps` schema slot is
untouched, and the paths forward (implement the calendar protocol directly,
vendor and patch, or wait) are recorded in the ADR.

### The claim this leaves unmade

Worth stating plainly, because the s.63 annexure could otherwise be read as
covering it: the annexure states a hash value and algorithm **from this project's
own Merkle root**. That is not an independent time anchor. A third party still
has only the device's word for *when* a capture happened. RFC 3161 and
OpenTimestamps are both unimplemented, and nothing built in Phase 7 substitutes
for them.

Had OpenTimestamps been wired up, the same care would have been needed at the
other end: a freshly created stamp is a **pending calendar attestation, not a
Bitcoin confirmation** — confirmation takes roughly 1–2 hours and requires
`upgrade()`. Reporting a fresh stamp as "anchored to Bitcoin" would have been the
same class of overclaim as `attestationRootTrusted` reporting `pass` on an
unanchored chain (ADR-0006 §5).
