# Phase 7 — stretch scope: what was built, what was refused, what remains

`research/09_PROJECT_PHASES.md` Phase 7 lists eight stretch items and sets an
unusually honest exit criterion:

> **Exit criteria for the report, if none of Phase 7 is built:** these remain
> honestly documented as designed-but-not-implemented future work […] this is not
> a gap to hide, it's exactly the scope the source material anticipated.

This file is that record, and it has been revised twice as the environment
changed. As of 2026-08-07: **three items are built** (the s.63 annexure, now
including its PDF renderer and export action; and the bystander capture
indicator), **one was investigated and rejected on evidence**, **two were
declined with reasons**, **two are deferred**, and **two are genuinely blocked on
external accounts or assets**. Each carries its reason below.

## Status at a glance

| Item | Status | Why |
|---|---|---|
| Section 63 (BSA) certificate template | **Built** — content **and** PDF, exported from the UI | Pure Kotlin, no new dependencies; renderer verified on hardware |
| OpenTimestamps anchoring | **Rejected on evidence** | Package carries 2 unfixable critical CVEs — see ADR-0002 |
| Bystander privacy — capture indicator | **Built** | On-screen notice under the live preview, exactly as research/06 §3.2 asks |
| Bystander privacy — face blur | **Declined, with reasons** | Conflicts with the proof model; see below |
| WiFi/cell-tower cross-check | **Declined, with reasons** | Buys little, costs invasive permissions; see below |
| PRNU offline demo | Not built | Research-grade; needs a camera-specific corpus the project must not fabricate |
| C2PA manifest export | Not built | Real value, large surface; the honest next candidate |
| Polygon Amoy contract | **Blocked** | Needs a funded testnet wallet — an account, not code |
| TFLite MesoNet classifier | **Blocked** | Needs a trained model file that cannot be fabricated |

### The 2026-08-06 re-assessment

The earlier version of this table blamed all six unbuilt items on two
environmental constraints — **no test device** and **host disk at 100%**.

**Both of those are now false**, and repeating them would have been the
convenient answer rather than the true one. The disk has ~295 GB free, an
emulator runs (it settled Scenario 5, see `PHASE6_SECURITY_VALIDATION.md`), and
the CPH2591 is attached. So each remaining item was re-judged on its merits, and
the outcomes above are **decisions**, not blockers — except the last two, which
are genuinely blocked on something no amount of work here supplies.

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

### ~~Not done for this item~~ — completed 2026-08-07

This section previously recorded that rendering and UI wiring were outstanding,
because `PdfDocument`/`Canvas` has no JVM stand-in and no device was available.
**Both are now done** — see "Built: the s.63 annexure as an exportable PDF"
below, including the five instrumented tests that back it.

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

---

## Built: the s.63 annexure as an exportable PDF

The content layer landed earlier; the renderer and the export action landed
2026-08-07. `StatutoryAnnexureRenderer` draws the annexure with Android's own
`PdfDocument`, and a second button on each event card saves it through the same
system "save as" dialog the certificate uses.

**It is a separate renderer and a separate button, deliberately.** The two
documents make opposite claims: the certificate reports what this system
computed and is complete when generated; the annexure is a draft form whose
load-bearing content is what a person adds. One class or one button emitting both
would invite the exact merge that must not happen — a single PDF that reads as
though this system certified something under BSA 2023 s.63, which it cannot do
(research/06 §1.3). The filenames differ for the same reason: the two must not be
confused in a case file.

**No verdict is printed on it.** `buildStatutoryAnnexure` takes no report, no
verdict and no check outcomes, unlike `buildCertificate`. A statutory annexure
records particulars — hash value, algorithm, device, method of production — and a
verification verdict is not one of them. Printing "VERIFIED" onto a page someone
is about to sign would invite them to adopt this system's conclusion as their own
certification.

### Pagination, and a prediction that was wrong

`CertificateRenderer` draws one page and never checks whether it overran; that is
safe there because its content is bounded. The annexure's is not, so every write
reserves its space first and breaks *before* overflowing. The failure this
prevents is the worst one available on a statutory form: content drawn past the
bottom edge is invisible in the PDF while appearing to have been included.

The instrumented test asserting this was written expecting **two** pages. On
hardware the real document needs **one**. The renderer was right and the
expectation was wrong, so the test now asserts `assertEquals(1, …)` — pinned as
an equality so that adding prose trips it and prompts someone to look at the
printed page. Pagination is proven separately: ten signature blocks demonstrably
produce more pages than two.

Five instrumented tests, all passing on the CPH2591. One of them asserts by
reflection that `SignatoryBlock` carries only `role` and `basis` — no `name`,
`signature` or `date` field — so a future refactor that adds somewhere to
auto-fill a signature has to delete a test that explains why it must not exist.

---

## Built: the bystander capture indicator

research/06 §3.2 asks for "a visible on-screen indicator during capture" aimed at
people captured incidentally. Under the DPDP Act 2023 a bystander whose face and
location are recorded is plausibly a Data Principal in their own right, and is
the one party to a capture who never saw a consent screen.

Shown beneath the live preview, always, with no dismiss control — it is addressed
to someone other than the person holding the phone. Placed under the preview
rather than over it because an overlay would sit on the framing the user is
composing, and a notice that gets in the way is one people learn to switch off.

---

## Declined: post-capture face blur

research/06 §3.2 pairs the indicator with "a post-capture blur/redaction option
for faces not relevant to the proven event". The indicator is built; the blur is
not, and the reason is a genuine conflict rather than effort.

**Blurring after capture produces an image that does not verify.** The proof
package commits to the SHA-256 of the exact bytes captured. Redact a face
afterwards and the media hash no longer matches, the Merkle root no longer
matches, and the signature is over a root that describes a different image. The
redacted copy would be, by this system's own definition, a tampered one.

That leaves two coherent designs, and neither is a small change:

1. **Blur before hashing.** The proof then covers the redacted image, and it
   verifies. But the unredacted original must be destroyed immediately or the
   redaction is theatre, and destroying it means the evidence can never be
   un-redacted if a court later needs the face. That is a policy decision about
   evidence, not a UI toggle.
2. **Emit a redacted derivative alongside the original**, clearly labelled as not
   verifying, with its own hash. Honest, but it means shipping a second artifact
   whose whole description is "this one is not the evidence" — and the failure
   mode is somebody filing it as though it were.

Both need a design decision recorded as an ADR before any code. Shipping a blur
button that quietly breaks verification would be worse than not shipping one:
users would learn the feature and only discover at verification time that they
had destroyed their own proof. ML Kit face detection runs on-device and offline
and is not the hard part here.

---

## Declined: WiFi / cell-tower cross-check

The idea is to corroborate GPS against visible WiFi BSSIDs and cell identifiers.
Declined on cost/benefit, and the cost is mostly privacy:

- Android 10+ throttles WiFi scanning to roughly 4 scans per 2 minutes and gates
  it behind location permission; cell identity needs `READ_PHONE_STATE`.
- Both collect identifiers of **third-party infrastructure and other people's
  networks**, which cuts directly against the project's existing stance —
  research/03 §5 is why the package carries a rotatable install UUID rather than
  IMEI or `ANDROID_ID`.
- What it buys is a weak corroboration of a signal the package already carries,
  with a mock-provider check already in front of it.

Adding an invasive permission to slightly strengthen an existing check is the
wrong trade for this project. Recorded as a decision so it is not re-proposed as
an oversight.

---

## Not built: PRNU and C2PA

Neither is blocked; both are simply larger than the value they add right now.

**PRNU** (sensor pattern noise) is research-grade. The extraction and correlation
maths is implementable, but a fingerprint needs an enrollment corpus of many
images from the same camera, and any accuracy figure without a labelled corpus
would be exactly the fabricated statistic this project has refused elsewhere.

**C2PA** is the honest next candidate and the one with real external value —
interoperability with Adobe and industry tooling. It is deferred rather than
declined because the surface is large: `@contentauth/c2pa-node` needs Node ≥22
and native bindings, there is no mature Kotlin signer, and a C2PA manifest is
embedded **into the JPEG**, which changes the bytes and therefore collides with
the hash chain in the same way face blur does. It would need the same design
decision recorded first.

---

## Blocked: Polygon Amoy and TFLite MesoNet

These two are not decisions. **Polygon Amoy** anchoring needs a funded testnet
wallet — an account and a faucet, not code. **TFLite MesoNet** needs a trained
model file; the plumbing is straightforward and the model cannot be invented,
and a classifier shipped with an unvalidated model would carry the highest
overclaim risk in the whole list.
