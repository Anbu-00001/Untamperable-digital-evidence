# ADR-0010 — An experimental deepfake classifier, and the fences around it

**Status:** accepted, implemented 2026-08-13
**Related:** ADR-0005 (Phase 4 forensics scope), ADR-0006 §5 (unavailable ≠ fail),
`research/04` §2/§6, `PHASE7_STRETCH_STATUS.md`

## Context

Phase 7 lists a TFLite MesoNet classifier as an optional "AI confidence score".
`PHASE7_STRETCH_STATUS.md` recorded it as blocked and flagged the risk in the
same sentence:

> a classifier shipped with an unvalidated model would carry the highest overclaim
> risk in the whole list.

The model is now available: `research/mesonet_to_tflite.ipynb` converts Meso-4
from the authors' own published `Meso4_DF.h5` weights. So the question stopped
being "can we build it" and became "can we show it to a user without lying".

That is the hard part, and it is what most of this ADR is about. A number between
0 and 1 rendered next to "these bytes are unchanged since capture, signed by
hardware-backed key X" inherits that sentence's authority whether it deserves it
or not.

## Decision

Ship it, on the Analyze screen only, behind four fences: a **face gate**, a
**pinned preprocessing path**, **no verdict language**, and **no accuracy claim**.

### 1. The model may not run on non-faces

Meso-4 was trained exclusively on cropped facial forgeries. Handed a street, a
document or a receipt it still returns a confident-looking number that means
nothing. The classifier therefore does not run at all unless a face is detected;
the screen says so plainly, and that is a true statement where a score would not
be.

**`android.media.FaceDetector`, not ML Kit.** ML Kit is the better detector and
still the wrong choice: unbundled (~800 KB) it downloads its model through Play
Services on first use, which fails in airplane mode — precisely the condition
this app is designed for — and bundled (~6.9 MB) it costs sixty times the size of
the classifier it gates. The platform detector needs no dependency, no download
and no Play Services. It is weak, and that is tolerable *because of the direction
it fails in*: a weak detector produces false negatives, a missed face withholds
the score, and withholding is this project's safe direction.

### 2. Preprocessing is pinned, and the reason is measured

The reference scores were produced with **nearest-neighbour** resampling, matching
Keras's `load_img` default. Re-scoring the same images through the same model with
other filters:

| resampling | max deviation |
|---|---|
| **nearest** | **0.0000** |
| bicubic | 0.0134 |
| bilinear | 0.0284 |

`Bitmap.createScaledBitmap` defaults to bilinear and is the obvious call to reach
for. It moves the output by more than the int8 quantisation error that the int8
build was rejected over. `RESAMPLING_FILTER = false` is named, commented and
asserted by a test.

The image is also decoded at full size and resized exactly once. Every additional
resampling step is another perturbation of a model this sensitive.

### 3. The gate does NOT crop — the measurement that changed the design

The first implementation cropped a square of 2.6 × eye separation around the
detected face, approximating the FaceForensics++ crops Meso-4 was trained on. It
was a reasonable-sounding heuristic and it was wrong.

The instrumented test caught it: `real00240.jpg`, one of the authors' own genuine
images, scored **0.178** on device against a reference of **0.990** — it had
crossed from "unmanipulated" to "manipulated". Measuring the model against
progressively tighter centre crops explained why:

| crop | df00204 | df01254 | real00240 | real00772 |
|---|---|---|---|---|
| **1.00 (whole image)** | 0.0414 | 0.0487 | 0.9897 | 0.9977 |
| 0.90 | 0.1950 | 0.2751 | 0.9821 | 0.9896 |
| 0.80 | 0.4239 | 0.5934 | 0.9649 | 0.9842 |
| 0.70 | 0.6510 | 0.7015 | 0.9308 | 0.8967 |
| 0.60 | 0.7251 | **0.7260** | 0.7538 | **0.5611** |

At a 60% crop the model is **worse than a coin flip**: a known fake (0.726) scores
above a known real image (0.561). Framing is not a tuning parameter for Meso-4, it
is load-bearing — and a crop factor that cannot be validated against a labelled
dataset must not sit in front of it.

So the gate answers exactly one question, "is there a face at all", and the
classifier sees the whole image, the way the authors' own `example.py` feeds it.

**The cost is disclosed rather than hidden**: a photograph where the face is small
in frame is also outside the training distribution and its score means little.
That is a limitation to state, not a reason to substitute a transformation that is
measurably worse on the only labelled images available.

This is also the strongest argument that no accuracy figure may be quoted. A model
whose output swings from 0.05 to 0.73 on the *same image* under a framing change
is not something to attach a percentage to without a dataset.

### 4. No verdict, and no borrowed authority

- The words "fake" and "real" appear nowhere in the UI. Bands describe where the
  number fell — *"shows patterns this model associates with face manipulation"* —
  they do not decide anything.
- The score is shown as a raw model output with its scale spelled out, never as a
  probability of manipulation.
- **"Its accuracy has NOT been measured on this project's data"** is given the
  same visual weight as the score, not fine print.
- **"It does not affect any proof, certificate or verification result"** is stated
  explicitly, because proximity on a screen implies relationship and there is
  none.

## Consequences

**Structurally cannot reach a verdict.** `DeepfakeClassifier` is constructed with
a `Context` and nothing else — no repository, no signer, no coordinator — mirroring
the isolation `ForensicAnalyzer` already has. A proof verdict answers "did these
bytes change since capture", which a classifier has no opinion on.

**Optional at runtime.** `create()` returns null if the asset will not open, and
the Analyze screen omits the section. An app that refused to start because a
stretch-goal model was unreadable would have its priorities inverted.

**Two packaging traps, both non-obvious:**

- The `.tflite` must be stored **uncompressed** (`noCompress += "tflite"`), or
  LiteRT cannot memory-map it and fails with a misleading "could not open model" —
  a build-packaging problem that reads as a corrupt file.
- LiteRT's Maven coordinates are `com.google.ai.edge.litert` but its **Java package
  is still `org.tensorflow.lite`**. Importing the obvious
  `com.google.ai.edge.litert.Interpreter` does not resolve, and the error reads as
  a missing dependency rather than a renamed artifact keeping its old namespace.

**APK cost:** 116 KiB model plus the LiteRT runtime. The int8 build (35.8 KiB) is
deliberately not shipped — it deviates by 0.0225, enough to move a borderline
reading, for a saving that does not matter.

## Verification

Seven instrumented tests on the CPH2591, against the authors' four reference
images. The one that matters most asserts the **convention is not inverted** —
reversing it would flip every reading in the app while every individual score
still looked entirely reasonable, and no other assertion would notice. It also
requires a **wide** separation, because a narrow one means the model loaded but
the preprocessing is wrong.

Also asserted: faceless images are not scored, unusably small images report
unavailable rather than a number, and `RESAMPLING_FILTER` is still `false`.

## What this does not do

It does not detect deepfakes reliably, and this project has no basis to say how
reliably it detects them at all. It is a triage aid on one screen, and the
system's actual claim — that a proof package's bytes are unaltered since capture
and signed by a specific hardware-backed key — neither depends on it nor is
weakened by it being wrong.
