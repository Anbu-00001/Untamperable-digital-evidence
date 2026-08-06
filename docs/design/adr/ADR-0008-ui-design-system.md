# ADR-0008 — A status palette of four, and no bundled font

**Status:** Accepted · **Date:** 2026-08-07 · **Phase:** 8
**Related:** ADR-0006 §5 (absence of evidence), ADR-0007 (read authorisation)
**Design source:** Claude Design project `3b64ac38-6a6d-4946-9d57-2868463e458a`

## Context

The app shipped with unstyled Material3 defaults: a bare title, a tab row, and
stacked cards with no visual hierarchy. Two problems were structural rather than
cosmetic.

**A verdict has four states, and Material has slots for about two.** A
verification report carries 13 named checks, each `pass`, `fail`, `unavailable`
or `unknown`. `ColorScheme` offers `error`, `primary`, `surface` and friends —
useful for a form, insufficient for evidence. Mapping four states onto that
vocabulary means one of them gets rendered as another, and the pair that must
never merge is exactly the pair most likely to: **`unavailable` ("we could not
run this check") against `fail` ("this check proved a problem")**. ADR-0006 §5
exists because absence of evidence is not evidence of a defect, and a palette
that cannot express the difference undoes that at the last step.

**Thirteen checks do not fit on a phone card.** Showing them flat produced a wall
of chips; showing a summary risked the single-boolean collapse the whole system
is built to avoid.

## Decision

### 1. A separate `RealityLockColors` palette, alongside MaterialTheme

Not instead of it — Material3 still supplies typography, shape and the standard
scheme. The additional palette carries the vocabulary Material has no slot for:
`pass`, `fail`, `unavailable`, `unknown`, `warn`, `neutral`, `info`, each with a
`*Soft` companion for chip backgrounds, in light and dark.

`unknown` is first-class on purpose. A newer backend can report a check this app
version does not recognise, and the honest rendering is a distinct colour plus
text saying so — never a silent fold into pass or fail. `VerificationReport
.Outcome.parse` already refuses to guess; the palette now lets the UI keep that
promise instead of quietly discarding it.

There is **no default palette**. Reading the tokens outside `RealityLockTheme`
throws, because a silent fallback would hide the mistake until it reached a
screenshot.

### 2. Colours converted, not transcribed

The design defines every colour in **oklch**, which CSS understands and Compose
does not. Each was converted through OKLab → linear sRGB → gamma encoding by
script, and **the original oklch triple is kept in a trailing comment on every
line** so any value can be checked against the design without guesswork.

There are 46 of them. A hand-typed hex table is a transcription bug waiting to
happen, and the one that slips through is invisible — a slightly wrong red still
looks like a red.

### 3. Groups take the worst state inside them

The six `attestation*` checks collapse into one **Attestation** group, because
that is how they read to a person. The rule that makes grouping safe:

> **A group's state is the worst state inside it**, ranked
> `fail > unknown > unavailable > pass`.

One failing attestation check turns the whole group red, never green. A group
that read green while containing a failure would be a lie about evidence, which
is a worse outcome than the crowded card the grouping was meant to fix.

Every group chip additionally carries a **literal fraction** (`5/6`), so a group
is never a single word: the colour says *worst thing in here*, the fraction says
*how much of it passed*, and the group expands to the individual checks.

### 4. Verdicts that are not pass or fail must look like neither

`INCOMPLETE` is amber with the glyph `◐` and a sentence stating outright that it
is not a pass. `INVALID_FORMAT` is red but uses `▲` and says the contents were
never checked — different from `FAILED`, which means a check proved alteration.
Borrowing green or red for either would collapse a distinction the backend went
to some trouble to report.

### 5. Status is never colour alone

Every status is **icon + colour + text label**. Colour-blind users are the
obvious reason; the better one is that this output may be printed, photocopied or
screenshotted in greyscale, which is precisely what happens to evidence.

### 6. Platform monospace, no bundled font

The design specifies JetBrains Mono for hashes, ids, timestamps and device
values. It is **not** bundled, and will not be. Per the design's own note, the
monospace here is doing **structural** work — letting a reader compare hash
digits without slipping — not brand work, and `FontFamily.Monospace` does that
job. Bundling costs roughly 200 KB per weight in an APK, for a difference no
reader of a SHA-256 will benefit from.

## Consequences

**Positive**
- The four outcome states survive all the way to the pixels.
- Grouping makes 13 checks readable without collapsing them to a boolean.
- No new dependencies and no font assets; APK size unchanged.
- Colour values are auditable against the design, line by line.

**Negative / accepted**
- A second palette alongside `ColorScheme` is a thing contributors must learn.
  Mitigated by the tokens throwing when read outside the theme.
- The tokens are a *translation* of the design, so the two can drift. The oklch
  comments make a re-check mechanical, but nothing enforces it automatically.
- Group membership is a judgement call. It is defined in one place so it can be
  argued with, rather than spread across the UI.
