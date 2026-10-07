# Walkthrough video

`reality-lock-walkthrough.mp4` — 34 s, 1280x720, no audio.

It is a real screen recording of the app running on a OnePlus CPH2591 (Android 15),
driven by real touch events from a laptop, cut into 14 short clips:

| # | Clip | What it shows |
|---|---|---|
| 1 | Launch | splash, hardware-backed key ready |
| 2 | Live sensors | real GNSS satellites (used vs seen), accuracy, tilt; tap for a legend |
| 3 | Capture, offline | airplane mode on, the five seal stages timed for real |
| 4 | Syncs by itself | network returns, the queued upload completes with no tap (**x6 speed**) |
| 5 | History | one trust ring per capture |
| 6-7 | Server verification | VERIFIED, 15 checks in 4 groups, the proof chain |
| 8 | Verify on the phone | no network: INCOMPLETE, never VERIFIED |
| 9 | Proof explorer | flip one bit: hash, Merkle root and signature all break (**x1.6**) |
| 10-11 | Real tamper test | one digit of the stored record edited, FAILED; restored, VERIFIED |
| 12 | Forensic triage | ELA heat-map and EXIF flags (**x2.2**) |
| 13 | Device status | capabilities and the hardware key (**x2.5**) |
| 14 | Public web verifier | the page the certificate's QR opens (browser screenshot) |

Edits: trims, the labelled speed-ups above, black boxes over precise GPS coordinates, and
jump cuts over the system photo picker (it shows the owner's personal gallery). Nothing is
mocked or pre-rendered.

## Not committed
`*.mp4` is git-ignored by this repository's media policy (see `.gitignore`), so this file stays
on this machine. The stills in `docs/media/` are what the README uses.

## Rebuild it
See [`scripts/demo/walkthrough/`](../scripts/demo/walkthrough/README.md).
