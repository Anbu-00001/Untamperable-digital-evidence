# `meso4_df.tflite` — provenance and verified facts

Produced by `research/mesonet_to_tflite.ipynb` on Google Colab, 2026-08-13.

**The shipped copy lives at `android/app/src/main/assets/meso4_df.tflite`** and is
the one under version control; this folder holds the original download. The
integration is ADR-0010.

Note that `.gitignore` excludes `*.tflite` wholesale, so the shipped asset needed
an explicit negation — the same trap `*.pem` sprang on the Google attestation
roots, and a quieter one here: without it `create()` returns null and the Analyze
screen simply omits the section, with no error anywhere.

## What this file is

Meso-4 (Afchar & Nozick, *MesoNet: a Compact Facial Video Forgery Detection
Network*, IEEE WIFS 2018), converted to TFLite from the authors' own published
`Meso4_DF.h5` weights — the Deepfake-set variant. Nothing was retrained and no
weights were invented.

| | |
|---|---|
| SHA-256 | `c87c108ed446e05ff480d2b006993f11ff7ecf55abaf65d2285d9795b93b7d91` |
| Size | 118,640 bytes (115.86 KiB) |
| Input | `[1, 256, 256, 3]` float32, scaled to `[0, 1]` |
| Output | `[1, 1]` float32, sigmoid |
| **Convention** | **→1 = REAL, →0 = DEEPFAKE** |
| Produced by | TensorFlow 2.20.0 |
| `min_runtime_version` | 1.14.0 |

## Verified independently, not just reported

The Colab session modified the notebook's environment-setup cell, so its printed
output was a claim about code that was not reviewed. The model was therefore
re-run locally, off Colab, against the authors' four sample images:

- **Parameters reconcile exactly.** Keras reports 28,073 total; the paper says
  27,977. The 96 difference is precisely the BatchNorm `moving_mean`/`moving_var`
  pairs (2×8 + 2×8 + 2×16 + 2×16), which are state, not learned weights. The
  paper quotes the trainable count. The architecture transcription is exact.
- **Conversion is faithful.** `max |keras − tflite_float32| = 4.172e-07`.
- **Scores reproduce to 0.0000** on all four images, using nearest-neighbour
  resampling (see below).
- **Convention holds with wide margin**: fakes 0.0353–0.0509, reals 0.9897–0.9985.

## The trap: resampling changes the answer

`load_img(..., target_size=(256, 256))` resamples with **nearest** by default,
and that is not a cosmetic detail. Re-scoring the same four images through this
same `.tflite` with other filters:

| resampling | max deviation from the reference scores |
|---|---|
| **nearest** | **0.0000** |
| bicubic | 0.0134 |
| bilinear | 0.0284 |

Bilinear is the default in most Android image pipelines. It shifts scores by
**more than the int8 quantisation error (0.0225)** that the int8 build was
rejected over — so an integration that resizes "the obvious way" would silently
evaluate a different image than the one measured here, while looking correct.

One of the sample images (`real00240.jpg`) is already 256×256, so it scores
identically under every filter. It was the file that matched first and made the
cause obvious; a test set of only already-square images would have hidden this
entirely.

**Whatever loads this model must pin its resampling as deliberately as it pins
the model file.**

## No accuracy figure

None has been measured on this project's data, and none may be quoted. The
MesoNet repository ships four test images; a coin flip scores 4/4 about 6% of the
time. Section 6 of the notebook measures accuracy only against a labelled dataset
the user supplies, and prints an explicit refusal otherwise.

What is defensible today:

> Meso-4 (Afchar et al., IEEE WIFS 2018) converted to TFLite from the authors'
> published `Meso4_DF.h5` weights. Architecture verified against the paper's
> 27,977 trainable parameters; conversion fidelity max |Keras − TFLite| =
> 4.17e-07. **No accuracy has been measured on this project's data; the published
> figures are the authors', on their own dataset.**

## Before integrating

1. **An ADR first.** `research/04` §6 and ADR-0005 require this to be a
   secondary, clearly-experimental signal that never replaces the ELA/EXIF layer
   and never contributes to a proof package's verdict. A verdict is about whether
   bytes changed since capture — a classifier has no opinion on that. Placing a
   confidence score beside a cryptographic result invites exactly the conflation
   the whole project is built to avoid.
2. **Pin the preprocessing** to nearest-neighbour, with a test that fails if it
   changes.
3. **It only makes sense on faces.** Meso-4 was trained on cropped facial
   forgeries. Scoring a photograph of a street or a document produces a number
   with no meaning, and that number will look exactly as authoritative as a real
   one.
4. Do not ship the int8 build. It is 35.8 KiB against 115.86 KiB, and the
   float32 model is small enough that the saving buys nothing.
