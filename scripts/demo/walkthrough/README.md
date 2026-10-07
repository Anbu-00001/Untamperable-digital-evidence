# Walkthrough recorder

Records the real app on a USB-attached phone, scene by scene, and cuts a short video and the
README stills from the footage.

| File | Role |
|---|---|
| `phone.py` | adb driver (taps, UI dump) and a per-scene scrcpy recorder that logs what must be hidden or cut |
| `shoot.py` | the scenes: launch, live, capture_offline, history, verify_server, verify_phone, explorer, evidence, tamper, analyze, device |
| `compose.py` | redaction boxes, cuts and trims (the only edits made to phone footage) |
| `build.py` | the 34-second montage with phase captions |

```bash
# scrcpy: official static build, no install: https://github.com/Genymobile/scrcpy/releases
export SCRCPY=/path/to/scrcpy
python3 shoot.py --raw ~/walkthrough-raw launch live capture_offline history verify_server \
    verify_phone explorer evidence tamper analyze device
python3 build.py --raw ~/walkthrough-raw --work ~/walkthrough-work \
    --out ../../../projectvideo/reality-lock-walkthrough.mp4
```

Needs: Python 3 + Pillow, ffmpeg, adb, a phone with the app installed (built with
`-PREALITYLOCK_BACKEND_BASE_URL=https://civicmesh.onrender.com/`) and the backend awake.

Why scrcpy: ColorOS blocks `adb shell screenrecord` from writing anywhere.

## Rules the scripts keep
- Nothing is faked: real touches, real sensors, real server round-trips.
- The phone goes quiet for the shoot (no heads-up notifications) and is put back afterwards;
  airplane mode is always switched off again, even if a scene fails.
- Precise coordinates are boxed (only on settled screens; moving transitions are jump-cut),
  and the system photo picker is cut.
- `tamper` edits a capture's stored record via `scripts/demo/tamper.sh` and **always restores it**.
- Raw clips are unredacted: keep `--raw` outside the repository.
