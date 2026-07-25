# Phase 4 — forensic layer evidence

Controlled, **synthetic** test images for the "Explainable Authenticity
Heuristic" (ELA + EXIF), plus the ELA map the tool produces. These are generated
fixtures, not anyone's real photos — they exist so the Phase-4 exit criteria can
be reproduced and checked.

## Files

| File | What it is |
|---|---|
| `authentic.jpg` | A real capture, re-saved once at q92 — a uniform-compression baseline |
| `spliced_final.jpg` | The same base at q65 with a q98 patch composited at (380,280) and re-saved — a known compression-history seam |
| `spliced_ela_heatmap.png` | The ELA map of `spliced_final.jpg`: the spliced rectangle reads bright against a dark background |
| `edited_photoshop.jpg` | `authentic.jpg` with its EXIF `Software` tag set to "Adobe Photoshop 25.0" |

The last two are the inputs the two exit criteria require: a spliced image that
lights up under ELA, and an externally-edited image that trips an EXIF flag.

## How the test images were generated (reproducible)

```bash
# ELA-positive splice: different compression histories in one frame
convert base.jpg   -resize 1024x1024 -quality 65 base65.jpg
convert base.jpg   -resize 1024x1024 -quality 92 authentic.jpg
convert authentic.jpg -crop 320x240+380+280 +repage -quality 98 patchHi.jpg
convert base65.jpg patchHi.jpg -geometry +380+280 -composite -quality 92 spliced_final.jpg

# EXIF-positive: set an editor Software tag (PIL used here; ExifTool works too)
python3 -c "from PIL import Image; im=Image.open('authentic.jpg').convert('RGB'); \
  e=im.getexif(); e[0x0131]='Adobe Photoshop 25.0 (Windows)'; \
  im.save('edited_photoshop.jpg','JPEG',quality=92,exif=e)"
```

A reference ELA (resave q95, per-pixel diff, amplify) confirms the splice region
reads **~3.3× hotter** than the untouched background.

## Exit criteria — verified on the physical device

Both criteria are proven by `ForensicInstrumentedTest` running on a **OnePlus
CPH2591 (Android 15)** against these exact images bundled as test assets, using
the real Android JPEG encoder and `androidx.exifinterface` — not a JVM stand-in:

```
$ ./gradlew :app:connectedDebugAndroidTest
Starting 3 tests on CPH2591 - 15
  PASS  ela_highlights_the_spliced_region          (spliced seam > 1.5× background under ELA)
  PASS  exif_flags_an_image_edited_in_photoshop     (EDITOR_SOFTWARE fires on the Photoshop tag)
  PASS  ela_analyzer_produces_a_heatmap_of_matching_size
tests=3 failures=0 errors=0
```

The Analyze screen was also driven end-to-end on the device: picking a candidate
image renders the ELA heat-map and EXIF flags in-app, beneath a "triage aid — not
a verdict" disclaimer. No real personal photo is committed here — the on-screen
demonstration used the user's own device and its results were not retained.

## What these prove — and do not

ELA highlights where JPEG re-compression error differs; a bright region merely
warrants a closer look. It cannot label an image real or fake, high-contrast
edges are naturally bright, and a single re-save erases the signal. EXIF flags
are suggestive only — metadata can be edited, stripped, or fabricated. This is a
triage aid layered on top of the cryptographic proof, never a substitute for it.
