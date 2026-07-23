# AI-Based Media Authenticity / Deepfake Detection & Image Forensics — Research for Reality Lock

**Context:** Reality Lock's Literature Survey slide lists *"AI Deepfake Detection → Detects manipulation but cannot prove original capture time"* as an existing-technology limitation the project complements with cryptographic proof. The Component slide lists *"AI: Deepfake & Authenticity Detection"* as a system block. This document researches what is actually available, open-source, and realistic for two students to integrate in one semester — it does **not** recommend training anything from scratch.

---

## 1. Standard benchmark datasets and strongest open-source pretrained detectors

### Benchmark datasets

| Dataset | Scale | Content | Access / License |
|---|---|---|---|
| **[FaceForensics++](https://github.com/ondyari/FaceForensics)** | 1,000 real YouTube videos + 4,000 fakes across 4 manipulation methods (Deepfakes, Face2Face, FaceSwap, NeuralTextures), 3 compression levels (RAW/HQ/LQ) | Face-swap & face-reenactment video forgery | Data released under the **[FaceForensics Terms of Use](http://kaldir.vc.in.tum.de/faceforensics_tos.pdf)** (research-only, must fill a Google form and get approved by the authors before a download script is emailed); code on GitHub is **MIT-licensed**. |
| **[Celeb-DF](https://github.com/yuezunli/celeb-deepfakeforensics)** | 590 real + 5,639 high-quality DeepFake videos of 59 celebrities (Celeb-DF-v2) | Higher visual quality face-swaps than FF++, designed to be harder | Request-only: fill the form linked in the repo, approval emailed by `deepfakeforensics@gmail.com`. Research-use terms, not a permissive open license. |
| **[DFDC (Deepfake Detection Challenge)](https://ai.meta.com/datasets/dfdc/)** | 128,154 clips, 960 subjects / 100k+ total clips, Meta/Facebook-produced with paid consenting actors | Largest public face-swap dataset, several generation methods (GAN + non-learned) | Released under a **non-commercial research license** (ethical-use restrictions); also distributed via the **[Kaggle competition](https://www.kaggle.com/competitions/deepfake-detection-challenge)**, gated behind accepting competition rules. |

**Practical note for this project:** none of these three datasets are needed directly — you will not be training a detector. They matter only because every pretrained model below was trained/validated on them, and their access-gating is exactly why "download and fine-tune" is not a realistic semester task.

### Strongest open-source pretrained detectors / repos

- **[selimsef/dfdc_deepfake_challenge](https://github.com/selimsef/dfdc_deepfake_challenge)** — **1st-place** DFDC Kaggle solution by Selim Seferbekov. Architecture: MTCNN face detector + **EfficientNet-B7** encoder (ImageNet + Noisy-Student pretraining), 380×380 frame-level classification, ensembled across 7 seeds. **License: MIT.** Ships a `download_weights.sh` script for pretrained weights. This is the single most credible "reference" open detector to cite, but it is GPU-trained/GPU-sized (multiple B7 ensembles) and was built for a Kaggle leaderboard, not a phone.
- **[cuihaoleo/kaggle-dfdc](https://github.com/cuihaoleo/kaggle-dfdc)** — 2nd-place DFDC solution (Xception + WS-DAN, and an EfficientNet-B3 variant), same caveats.
- **[Hook35/deepfake-scanner](https://github.com/Hook35/deepfake-scanner)** (a.k.a. the open-sourced **Deepware** scanner) — CLI tool based on **EfficientNet-B7**, trained primarily on the DFDC dataset, ships a pretrained model download. Requires an **NVIDIA GPU with CUDA** — explicitly not CPU/mobile-friendly. A LICENSE file exists in the repo but its exact terms weren't legible via automated fetch; verify manually before reuse.
- **[DariusAf/MesoNet](https://github.com/DariusAf/MesoNet)** — the original authors' implementation of **MesoNet** (Afchar, Nozick, Yamagishi, Echizen, *"MesoNet: a Compact Facial Video Forgery Detection Network"*, IEEE WIFS 2018, [arXiv:1809.00888](https://arxiv.org/abs/1809.00888)). Two tiny CNNs — **Meso-4** (only **27,977 trainable parameters**) and **MesoInception-4** — reporting >98% on Deepfakes and >95% on Face2Face on the original test data. This is the one architecture on this whole list that is genuinely small enough to be interesting for mobile (see Q2).
- **Hugging Face ONNX models** — [`prithivMLmods/Deep-Fake-Detector-v2-Model-ONNX`](https://huggingface.co/prithivMLmods/Deep-Fake-Detector-v2-Model-ONNX) / [`onnx-community/Deep-Fake-Detector-v2-Model-ONNX`](https://huggingface.co/onnx-community/Deep-Fake-Detector-v2-Model-ONNX) and [`prithivMLmods/Deep-Fake-Detector-Model-ONNX`](https://huggingface.co/prithivMLmods/Deep-Fake-Detector-Model-ONNX) — a **Vision Transformer** (fine-tuned `google/vit-base-patch16-224-in21k`), binary Real/Deepfake classifier, exported to ONNX. **License: Apache 2.0.** uint8-quantized ONNX file is **≈87.3 MB** — small enough to ship in an APK, though a ViT-base forward pass is still noticeably heavier per-inference than a MobileNet-class CNN on a mid-range phone.
- **Microsoft Video Authenticator** — trained on FaceForensics++, gives a per-frame manipulation confidence score. **Not open-source and not publicly downloadable** — Microsoft deliberately withheld public release to limit misuse. Useful only as a citation, not as integrable code. ([WeLiveSecurity](https://www.welivesecurity.com/2020/09/03/microsoft-debuts-deepfake-detection-tool/), [PopSci](https://www.popsci.com/story/technology/microsof-video-authenticator-deepfakes/))
- **Intel FakeCatcher** — real-time detector claiming 96% accuracy, but it works on a fundamentally different signal (photoplethysmography / subtle facial "blood-flow" color shifts) rather than pixel artifacts. **Not open-source**, positioned as an Intel Responsible-AI research demo. ([Intel Newsroom](https://newsroom.intel.com/artificial-intelligence/intel-introduces-real-time-deepfake-detector), [VentureBeat](https://venturebeat.com/ai/intel-unveils-real-time-deepfake-detector-claims-96-accuracy-rate))
- **Deepware** — see Hook35/deepfake-scanner above; deepware.ai also runs a hosted scanner UI (not something you'd embed, but usable to sanity-check test images by hand).

**Bottom line for Q1:** the strongest genuinely reusable open code is `selimsef/dfdc_deepfake_challenge` (MIT, best accuracy, heaviest) and `DariusAf/MesoNet` (tiny, weaker but tractable). Everything Microsoft/Intel-branded is closed. Datasets are all gated behind research-use request forms, not casually downloadable — another reason not to plan around training/fine-tuning this semester.

---

## 2. Lightweight, mobile-deployable (TFLite/ONNX/ML Kit) on-device deepfake detector

**Direct answer: there is no widely-adopted, battle-tested, "official" TFLite deepfake-detection model equivalent to the object-detection/pose-estimation models Google ships.** This is a real gap — deepfake detectors are almost entirely published as PyTorch/Keras research code sized for a GPU. That said, three realistic paths exist:

1. **Convert MesoNet to TFLite yourself.** Meso-4/MesoInception-4 ([DariusAf/MesoNet](https://github.com/DariusAf/MesoNet), Keras/TensorFlow) is small (27,977 params for Meso-4) and shallow enough that a standard `tf.lite.TFLiteConverter` pass (optionally with post-training int8 quantization) should produce a model in the tens-to-low-hundreds of KB — comfortably runnable on a mid-range Android phone with `tflite-support` / LiteRT. This is by far the most realistic "on-device deepfake classifier" path for a two-student semester project: no dataset licensing needed (use the pretrained weights), small conversion effort, small enough to bundle in the APK.
2. **Use the Hugging Face ONNX ViT models directly via ONNX Runtime Mobile / `onnxruntime-android`**, e.g. [`prithivMLmods/Deep-Fake-Detector-v2-Model-ONNX`](https://huggingface.co/prithivMLmods/Deep-Fake-Detector-v2-Model-ONNX) (Apache 2.0, ~87 MB quantized). Feasible (ONNX Runtime has an official Android AAR), but a ViT-base is noticeably slower per-frame on CPU than a MobileNet-class network, and 87 MB is a meaningfully large asset for a student-app APK. Treat as "possible, but heavier than ideal."
3. **Lightweight adjacent building blocks that *do* have small pretrained mobile models:** [`facenox/face-antispoof-onnx`](https://github.com/facenox/face-antispoof-onnx) — a **600 KB** MiniFASNetV2-SE face anti-spoofing (liveness) classifier, ~98% accuracy on its benchmark. This detects *presentation attacks* (photo-of-a-photo, screen replay) rather than GAN deepfakes, but it is the kind of edge-sized model that fits the "runs happily on a mid-range Android phone" bar the other options don't quite meet — worth knowing about even if it's solving an adjacent problem.
4. **Google ML Kit** ([Face Detection](https://developers.google.com/ml-kit/vision/face-detection/android), [Face Mesh](https://developers.google.com/ml-kit/vision/face-mesh-detection/android)) is fully on-device and bundled/unbundled for Android, but it only does face **localization/landmarks**, not manipulation classification — useful as a face-crop preprocessing step feeding into whichever classifier you pick above, not a deepfake detector itself.

**Practical recommendation:** if the team wants any on-device AI classifier at all, convert MesoNet to a quantized TFLite model — it is the only option on this list combining (a) genuinely small footprint, (b) a real, citable published architecture, and (c) freely available pretrained weights.

---

## 3. Error Level Analysis (ELA)

### How it works, concretely

JPEG is a **lossy** format: each save re-quantizes 8×8 DCT blocks at a chosen quality level, and each re-save of an *already-JPEG* region introduces a small, predictable additional error relative to its own compression history. ELA exploits this:

1. Take the (possibly tampered) JPEG image.
2. **Re-save it at a known, fixed quality level** (commonly ~90–95%) to a temporary file.
3. Compute the **per-pixel absolute difference** between the original and the resave (e.g. `ImageChops.difference` in PIL).
4. **Amplify/scale** the difference (since raw differences are usually tiny, near-invisible) so it can be visualized as a heat-map.
5. Interpret: a *whole authentic* JPEG that has only ever been saved once has a **uniform error level** across the frame, because every region shares the same original compression history. A **spliced-in region** (pasted from a different source, or a region re-edited and re-saved locally in an editor) was compressed a *different number of times / at a different quality*, so it "settles" to a different error level than its surroundings under the fixed-quality resave — showing up as a visibly brighter or darker patch in the diff image. Untouched high-contrast edges also naturally show some ELA response, which is the main source of false positives, so ELA output should be read as *suspicious-region highlighting*, not a definitive fake/real verdict.

Originated by Neal Krawetz, *"A Picture's Worth… Digital Image Analysis and Forensics"* (Black Hat, 2007), and it's the basis of the public tool FotoForensics.

**Known limitations (be upfront about these in the report):** ELA works on JPEGs specifically (weak/meaningless on PNG or other lossless formats unless they were *ever* JPEG-compressed in their history); repeated re-compression by messaging apps / social media (which the paper's own threat-model of "re-uploaded with false context" explicitly worries about) can wash out the very artifact ELA looks for; and heavy image noise can itself masquerade as a manipulated region. It is best presented as an *explainable, cheap first-pass triage signal*, not a standalone verdict.

### Open-source implementations

- **Python (most reusable for a quick prototype or a Python-based backend microservice):**
  - [`theforces/Error-level-analysis`](https://github.com/theforces/Error-level-analysis/) — Python + OpenCV, Jupyter notebook.
  - [`prsntmaurya/Error-Level-Analysis`](https://github.com/prsntmaurya/Error-Level-Analysis)
  - [`samotarnik/ela`](https://github.com/samotarnik/ela)
  - Minimal reference gists: [ewencp/3356622](https://gist.github.com/ewencp/3356622), [cirocosta/33c758ad77e6e6531392](https://gist.github.com/cirocosta/33c758ad77e6e6531392) — both are ~20–30 line PIL-only implementations (`Image.save(quality=90)` → `ImageChops.difference` → `ImageEnhance.Brightness`), useful as the literal algorithm reference regardless of language.
- **Java (portable-ish to Android):** [`rstreet85/ELA`](https://github.com/rstreet85/ELA) — a pure-Java ELA implementation (re-save at 95% quality, diff, scale). It uses `javax.imageio`, which is **not available on Android** (Android has no AWT/Swing/ImageIO), so it cannot be dropped in as-is — but the *algorithm* ports trivially to Android's own `android.graphics.Bitmap` / `Bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)` + `BitmapFactory.decodeStream` + manual per-pixel `getPixel`/`getPixels` diff. This is realistically a **1–2 day implementation task** for a student, with zero third-party dependencies, entirely on-device, and fully explainable (you can literally show the heat-map bitmap on screen as your "AI-lite" evidence).
- ImageJ plugin [`SebMilardo/ErrorLevelAnalysis`](https://github.com/SebMilardo/ErrorLevelAnalysis) exists but is a desktop/scientific-imaging tool, not Android-relevant.

**Recommendation:** implement ELA natively in Kotlin using Android's own Bitmap/JPEG APIs (no external library needed) rather than trying to port `rstreet85/ELA` or a Python lib. It is small, explainable, fast, and dependency-free — an excellent fit for a "quick check" layer.

---

## 4. PRNU (Photo Response Non-Uniformity) sensor-noise fingerprinting

### How it binds a photo to a specific sensor

Every camera sensor has microscopic manufacturing imperfections — tiny variations in each pixel's light sensitivity caused by silicon wafer inhomogeneities during fabrication. This produces a **multiplicative noise pattern** ("Sensor Pattern Noise") that is:
- **Unique per physical sensor instance** (not just per camera *model* — two identical phone models off the same production line have different PRNU patterns), and
- **Stable over the sensor's lifetime** (doesn't change with wear, unlike some other artifacts), and
- **Scene-independent** (a function of the sensor, not of what's being photographed).

To use it forensically: extract a **reference fingerprint** for a given camera by denoising many images taken by that camera (e.g. flat-field/sky shots) and averaging the residual noise. Then, for a *questioned* photo, extract its own noise residual (typically via a denoising filter, e.g. wavelet-based, following the classic Lukas–Fridrich–Goljan method) and compute a **correlation** between the residual and the candidate camera's reference fingerprint. High correlation ⇒ strong evidence the photo was captured by *that exact physical sensor*; low/no correlation ⇒ evidence it wasn't (or was heavily re-processed/re-compressed/screenshotted).

### Open-source libraries

- **[polimi-ispl/prnu-python](https://github.com/polimi-ispl/prnu-python)** — Python port of the Binghamton University reference MATLAB PRNU toolbox, from the Politecnico di Milano ISPL forensics group (a well-cited academic lab in this exact field). **MIT license.** Requires Python ≥3.4, ships `example.py` and unit tests — directly usable for a demo: build a fingerprint from ~20–50 sample photos from one phone, then correlate a held-out photo from the same phone vs. a photo from a different phone/device.
- **[sim-pez/prnu](https://github.com/sim-pez/prnu)** — extracts fingerprints using multiple modern denoiser backends (VDNet, VDID, etc.) with a CLI comparison mode; a good "if the basic one underperforms" fallback.
- **[E0HYL/CameraFingerprint_pytorch](https://github.com/E0HYL/CameraFingerprint_pytorch)** / **[ocrim1996/prnu-python](https://github.com/ocrim1996/prnu-python)** — DnCNN/DRUNet-based (deep-learning denoiser) variants of the same idea, heavier but sometimes more robust to compression.
- **[ernestbies/Camera-Fingerprint-PRNU](https://github.com/ernestbies/Camera-Fingerprint-PRNU)** — packaged as an Autopsy (digital forensics platform) module rather than a standalone library — useful for understanding the workflow, less directly embeddable in an Android app.

### Feasibility caveat for this project

PRNU is scientifically the closest thing to "binding a photo to a physical camera," which sounds like a natural fit for Reality Lock's promise — but be realistic about semester-project feasibility: (1) it needs a **fingerprint enrollment step** (dozens of reference photos per device, ideally flat/low-detail scenes) before it can verify anything, which is an awkward UX for an app whose whole pitch is "capture once, prove later"; (2) it's computationally heavier than ELA (denoising filters over full-resolution images); (3) modern phone camera pipelines apply **HDR stacking, multi-frame noise reduction, and heavy JPEG compression**, all of which measurably degrade the PRNU signal, and this is an active research problem, not a solved one (see [*"A Stress Test for Robustness of PRNU Identification on Smartphones"*, PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC10098672/)). **Recommendation: cite PRNU in the literature survey as the academically "correct" sensor-binding technique and demo `polimi-ispl/prnu-python` offline/in a Python notebook as a proof-of-concept if time allows — but do not put it on the critical path of the Android app itself this semester.** It's a strong "future work" line.

---

## 5. EXIF / metadata forensics

### ExifTool

[**ExifTool**](https://exiftool.org/) (Phil Harvey, Perl, CLI) is the de-facto standard tool for reading/writing/validating image metadata — used across digital forensics and OSINT workflows (it's a core tool in [Bellingcat's Online Investigation Toolkit](https://bellingcat.gitbook.io/toolkit/more/all-tools/exiftool)). Key point for the project's threat model: **ExifTool can read metadata authoritatively but cannot itself prove metadata is truthful** — anyone (including ExifTool itself) can rewrite any tag, so the tool's forensic value is entirely in spotting *inconsistencies*, not in "verifying" a single field in isolation.

### androidx.exifinterface (on-device, for the Android app itself)

The Jetpack [**`androidx.exifinterface`**](https://developer.android.com/jetpack/androidx/releases/exifinterface) library (`implementation 'androidx.exifinterface:exifinterface:<version>'`) reads/writes ~140 EXIF attributes directly on-device from JPEG, PNG, WebP, HEIF, and several RAW formats (DNG, CR2, NEF, ARW, ORF, etc.). Relevant API surface: construct `ExifInterface` from an `InputStream` (required for `content://` URIs on Android 10+), then `getAttribute(TAG_MAKE)`, `TAG_DATETIME`/`TAG_DATETIME_ORIGINAL`, `getLatLong()` for GPS, `TAG_SOFTWARE`, `TAG_ORIENTATION`, etc. — every read should defensively check for `null` since tags are frequently absent.

### Common metadata-based tamper indicators

- **`Software` tag** naming an editor (`Adobe Photoshop`, `GIMP`, `Lightroom`, `Snapseed`, `Affinity Photo`) on an image supposedly captured live and never edited.
- **Missing or corrupted MakerNote** — the proprietary manufacturer-specific block; generic photo editors frequently strip or mangle it, so its total absence on an image claiming to be a direct camera JPEG is a red flag.
- **`DateTimeOriginal` vs `ModifyDate`/file-system modify time mismatches** — a large gap between "when the shutter fired" and "when the file was last written" suggests post-processing.
- **Embedded thumbnail disagreeing with the main image** — a classic Photoshop-tampering tell: the small EXIF-embedded thumbnail is sometimes not regenerated after editing the full image, so it visibly differs from the final picture.
- **GPS inconsistency** — GPS tags absent despite location services being on at capture time; GPS coordinates that don't match a claimed location; or timezone offsets that don't line up with the GPS position.
- **Resolution / color-profile inconsistencies** and multiple JPEG quantization tables embedded in one file (this dovetails with ELA — a doubly-compressed file is exactly what ELA is designed to expose).

### How this maps onto Reality Lock specifically

For media that *never left the app* (the core "capture → hash → sign" pipeline), EXIF/metadata checks are secondary — the cryptographic signature is the authoritative proof, and metadata is really just one more field bound into the signed bundle. Where EXIF/metadata forensics becomes genuinely useful is the **adversarial case the app must guard against**: someone importing an old/edited/downloaded photo and trying to pass it through the app's capture flow as a fresh live capture. Checking the `Software` tag, MakerNote presence, and thumbnail/GPS consistency of whatever the OS camera pipeline handed the app *before* it gets hashed is a legitimate, cheap sanity check worth doing at ingest time.

---

## 6. Most realistic AI component to build in one semester

**Direct recommendation: (b) — implement ELA + EXIF-consistency checks as an explainable, "AI-lite" secondary signal, with (a) — a pretrained TFLite classifier (MesoNet, converted) — as an optional stretch layer bolted on afterward if time remains. Do not do (c) as a blanket skip, and do not make a heavy pretrained deepfake classifier the primary or load-bearing AI component.**

**Reasoning:**

1. **The project's own literature slide already tells you what the novel contribution is.** "AI Deepfake Detection → detects manipulation but cannot prove original capture time" is framed as an *existing, external* technology the project **complements**, not a technology Reality Lock itself needs to reinvent. The cryptographic hash+sign+lock pipeline is the actual thesis. Sinking scarce semester time into training or heavily tuning a deepfake classifier would dilute effort away from the one component that is genuinely novel and gradeable as "your work."
2. **The licensing and scale of the strong pretrained models argue against them as a primary dependency.** FF++/Celeb-DF/DFDC are all gated behind manual approval-request forms (not instant downloads), and the strongest open detectors (`selimsef/dfdc_deepfake_challenge`, EfficientNet-B7 ensembles) are GPU-sized research artifacts, not something you casually embed and demo confidently on a mid-range Android phone in a live review. Even the friendliest ONNX option is ~87 MB and a ViT-base forward pass — workable, but a meaningful integration and testing cost for marginal narrative benefit.
3. **Threat-model mismatch.** Nearly every mainstream deepfake detector (FF++/Celeb-DF/DFDC-trained) is built to catch **face-swap/reenactment video forgery**. Reality Lock's own stated threats are broader and mostly *not* GAN-face-swaps: "fake accident videos," "modified screenshots," "edited surveillance footage," "false location claims," "manipulated digital documents." Splicing/editing detection (ELA) and metadata consistency (EXIF) map far more directly onto that actual threat list than a face-swap classifier does.
4. **Explainability and defensibility in a viva/review.** ELA produces a **visual heat-map** a professor can look at and understand instantly ("this patch re-compressed differently, therefore possibly spliced"); EXIF checks produce a **plain list of flags** ("Software tag = Photoshop", "GPS missing", "thumbnail mismatch"). Both are honestly describable as a *lightweight, rule-based/explainable AI-adjacent layer* without overclaiming "we built a deepfake detector," which two students genuinely can defend being asked hard questions about, unlike a black-box EfficientNet-B7 ensemble they didn't train and can't fully explain.
5. **Engineering cost fits a semester.** ELA in Kotlin using only `android.graphics.Bitmap` APIs is realistically a few days of work with zero external dependencies and no dataset licensing. EXIF checks via `androidx.exifinterface` are even simpler (a library already provided by Google). Both integrate cleanly into "Data Processing" / "Proof Package Creation" stages already on the block diagram. A pretrained TFLite classifier is a reasonable *additional* stretch layer once the crypto pipeline and ELA/EXIF checks are solid, but should not be the first or only AI work attempted.
6. **Why not just skip AI entirely (option c)?** Because the PPT has already publicly committed, on two slides, to "AI: Deepfake & Authenticity Detection" as a named system component. Dropping it silently creates a mismatch between the pitched architecture and the delivered system that a reviewer is likely to notice and question directly. The fix is not to build heavy AI, but to **right-size** what "AI" means in this system: an explainable heuristic layer (ELA + EXIF) *is* a legitimate, honestly-labeled answer to "what does your AI component do," and it is far more defensible than either overclaiming a borrowed black-box classifier or quietly dropping the block entirely.

**Labeling advice:** in the report/PPT going forward, describe this layer precisely — e.g. *"Explainable secondary authenticity signal (ELA + EXIF consistency checks)"* — rather than continuing to call it "AI Deepfake Detection," to avoid overclaiming relative to what's actually implemented.

---

## 7. Recent research combining cryptographic provenance with AI detection as complementary layers (2021–2026)

1. **Alexander Vilesov, Yuan Tian, Nader Sehatbakhsh, Achuta Kadambi — [*"Solutions to Deepfakes: Can Camera Hardware, Cryptography, and Deep Learning Verify Real Images?"*](https://arxiv.org/abs/2407.04169), arXiv:2407.04169 (2024).**
   Directly surveys and weighs the three verification strategies most relevant to Reality Lock's own design — camera-hardware-level attestation, cryptographic signing, and deep-learning-based detection — as the candidate tools for distinguishing camera-captured "real" images from synthetic/AI-generated ones. The single most on-topic paper found for this literature survey.

2. **John C. Simmons, Joseph M. Winograd (Verance Corporation) — [*"Interoperable Provenance Authentication of Broadcast Media using Open Standards-based Metadata, Watermarking and Cryptography"*](https://arxiv.org/abs/2405.12336), arXiv:2405.12336 (2024).**
   Analyzes how cryptographically-signed metadata (C2PA) and audio/video watermarking (ATSC) interact when validating the provenance of broadcast content re-shared on social platforms, and argues these standards are well-suited to real-world provenance verification when combined correctly. Good source for how an industry standard (C2PA) operationalizes "cryptographic proof of origin," directly analogous to Reality Lock's own hash+sign+lock approach.

3. **Alex Shamis, Bryan Parno, et al. (Microsoft Research) — [*"AMP: Authentication of Media via Provenance"*](https://arxiv.org/abs/2001.07886), ACM Multimedia Systems Conference (MMSys) 2021.**
   Proposes a system where publishers create signed "manifests" binding cryptographic hashes of media to publisher-asserted metadata (including back-pointers recording derivation from source media). Explicitly frames its motivation the same way Reality Lock's literature slide does: *"detection may help in the short term, but is destined to fail as fake-media generation quality improves — pipelines for assuring source and integrity of media will be required and increasingly relied upon."* Strong citation for justifying "why cryptographic provenance, not just detection" as the core design decision.

4. **Alexander Nemecek, Hengzhi He, Guang Cheng, Erman Ayday — [*"Authenticated Contradictions from Desynchronized Provenance and Watermarking"*](https://arxiv.org/abs/2603.02378), IEEE/CVF CVPR Workshops 2026.**
   Shows that C2PA cryptographic manifests and invisible AI-generation watermarks are *independent* verification layers that can validly disagree on the same file (a file can carry a valid "human-authored" C2PA manifest while its pixels simultaneously carry an "AI-generated" watermark, both checks passing in isolation) — the "Integrity Clash." Useful cautionary citation: even sophisticated, deployed provenance+AI systems have not solved the "which layer do you trust" problem, which is exactly the tension Reality Lock's own literature slide is gesturing at.

5. **Florinel-Alin Croitoru, Andrei-Iulian Hiji, Vlad Hondru, et al. — [*"Deepfake Media Generation and Detection in the Generative AI Era: A Survey and Outlook"*](https://arxiv.org/abs/2411.19537), arXiv:2411.19537 (2024).**
   Broad, current survey of deepfake generation (GANs, diffusion models, NeRFs) and detection methods across image/video/audio/multimodal content; explicitly documents that state-of-the-art detectors **fail to generalize** to unseen generation methods. This is the key citation for justifying *why* pure AI detection is an insufficient foundation on its own and *why* a complementary cryptographic-proof approach (Reality Lock's actual thesis) has independent merit.

*(Supplementary, non-academic but useful framing source: [Pebblous — "Deepfakes vs. Provenance: Why C2PA Beats Detection"](https://blog.pebblous.ai/blog/deepfake-detection-vs-provenance/en/), an industry blog making the same complementary-not-competing argument in plainer language — good for the presentation narrative, not to be cited as a peer-reviewed source.)*

---

## Recommended AI Scope for This Project

**Build this semester:** a two-part **explainable secondary-signal layer**, not a deepfake classifier:
1. **Error Level Analysis (ELA)** implemented natively in Kotlin (Android `Bitmap`/JPEG APIs, no external dependency) — re-save at fixed quality (~90–95%), diff, amplify, render as a heat-map, flag high-variance regions as "inconsistent compression history."
2. **EXIF/metadata consistency checks** via `androidx.exifinterface` — flag editing-software tags, missing MakerNote, thumbnail/main-image mismatch, GPS/timezone inconsistency, and `DateTimeOriginal`/`ModifyDate` gaps — run at ingest, before the media is hashed and locked into the proof package.

Label this layer honestly in all documentation as an **"Explainable Authenticity Heuristic"** or **"Secondary Signal (ELA + EXIF)"**, not "AI Deepfake Detection" — it is cheap, dependency-free, on-device, fully explainable in a viva, and directly matches the project's actual threat model (splicing, re-editing, false re-uploads) far better than a face-swap classifier would.

**Stretch goal / future work (do only if the core crypto pipeline + ELA/EXIF layer are solid with time remaining):** convert **MesoNet** ([DariusAf/MesoNet](https://github.com/DariusAf/MesoNet), 27,977-parameter Meso-4) to a quantized **TFLite** model and run it on-device as an optional, clearly-labeled-experimental "AI confidence score" alongside — never instead of — the cryptographic and heuristic checks. If TFLite conversion proves troublesome under time pressure, the fallback-fallback is to cite it, plus PRNU sensor-fingerprinting (demoed only as an offline Python notebook via [`polimi-ispl/prnu-python`](https://github.com/polimi-ispl/prnu-python), MIT-licensed) and full pretrained-classifier integration (e.g. the Hugging Face ONNX ViT model), as explicit **future work** in the final report — consistent with how the Literature Survey slide already frames AI detection as complementary infrastructure the project builds toward, not something it must fully solve in one semester.
