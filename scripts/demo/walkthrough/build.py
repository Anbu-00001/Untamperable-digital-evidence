#!/usr/bin/env python3
"""Cut the cleaned scenes into the short walkthrough video.

    python3 build.py --raw RAW --work WORK --out projectvideo/reality-lock-walkthrough.mp4

Layout: the phone recording on the left, a phase tracker and one caption on the
right. Every clip is real footage; the only edits are trims, a speed-up on the
long waits (labelled "x6" etc. on screen) and the redaction/cuts compose.py applies.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import compose

W, H = 1280, 720
PHONE_H = 690
FPS = 30
FONT_B = "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"
FONT_R = "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"
PHASES = ["CAPTURE", "SEAL", "SYNC", "VERIFY", "TAMPER", "ANALYZE", "DEVICE"]
COLORS = {"CAPTURE": "#22d3ee", "SEAL": "#34d399", "SYNC": "#3b82f6", "VERIFY": "#34d399",
          "TAMPER": "#fb7185", "ANALYZE": "#a78bfa", "DEVICE": "#fbbf24"}


def clean_t(log: dict, scene: str, raw_t: float) -> float:
    """Raw log time -> time in the cleaned video (after trim and cuts)."""
    t0, _ = compose.window(scene, log)
    dropped = 0.0
    for c in log.get("cuts", []):
        a, b = max(c["a"] - 0.25, t0), c["b"] + 0.6
        dropped += max(0.0, min(b, raw_t) - a)
    return raw_t - t0 - dropped


def after_cut(log: dict, scene: str, i: int, pad: float = 0.0) -> float:
    return clean_t(log, scene, log["cuts"][i]["b"] + 0.6) + pad


def ev(log: dict, label: str, nth: int = 0) -> float:
    return [e["t"] for e in log["events"] if e["label"] == label][nth]


def bg_image(phase: str, title: str, sub: str, tag: str, idx: int, total: int, dst: Path) -> None:
    im = Image.new("RGB", (W, H), "#060a18")
    d = ImageDraw.Draw(im)
    for y in range(H):  # soft vertical gradient
        c = int(10 + 18 * y / H)
        d.line([(0, y), (W, y)], fill=(6, c, 24 + c))
    acc = COLORS[phase]
    fb = lambda s: ImageFont.truetype(FONT_B, s)  # noqa: E731
    fr = lambda s: ImageFont.truetype(FONT_R, s)  # noqa: E731
    x0 = 470
    d.text((x0, 34), "REALITY LOCK", font=fb(22), fill="#e2e8f0")
    d.text((x0 + 190, 38), "tamper-evident proof for phone photos", font=fr(16), fill="#7f8db3")
    # phase tracker
    x = x0
    for p in PHASES:
        on = p == phase
        w = d.textlength(p, font=fb(14)) + 28
        d.rounded_rectangle([x, 92, x + w, 126], radius=17, fill=COLORS[p] if on else "#111a36",
                            outline=COLORS[p] if on else "#26335f", width=2)
        d.text((x + 14, 100), p, font=fb(14), fill="#06101f" if on else "#7f8db3")
        x += w + 10
    d.text((x0, 250), title, font=fb(54), fill="#ffffff")
    d.rounded_rectangle([x0, 330, x0 + 90, 336], radius=3, fill=acc)
    # wrap subtitle
    words, line, y = sub.split(), "", 362
    for wd in words:
        t = (line + " " + wd).strip()
        if d.textlength(t, font=fr(28)) > W - x0 - 60:
            d.text((x0, y), line, font=fr(28), fill="#c7d2fe")
            line, y = wd, y + 42
        else:
            line = t
    d.text((x0, y), line, font=fr(28), fill="#c7d2fe")
    if tag:
        d.rounded_rectangle([x0, 600, x0 + d.textlength(tag, font=fb(18)) + 28, 636], radius=18,
                            fill="#1b2750", outline="#3b82f6", width=2)
        d.text((x0 + 14, 607), tag, font=fb(18), fill="#93c5fd")
    d.text((x0, 668), "Real screen recording of the app on a OnePlus CPH2591 - nothing mocked",
           font=fr(15), fill="#5b6a94")
    # progress
    bw = (W - 2 * 40) / total
    for i in range(total):
        d.rounded_rectangle([40 + i * bw + 2, H - 12, 40 + (i + 1) * bw - 2, H - 6], radius=3,
                            fill=acc if i <= idx else "#18224a")
    im.save(dst)


def segment(work: Path, i: int, clip: dict, total: int) -> Path:
    bg = work / f"bg_{i:02d}.png"
    bg_image(clip["phase"], clip["title"], clip["sub"], clip.get("tag", ""), i, total, bg)
    dur = clip["dur"]
    speed = clip.get("speed", 1.0)
    out = work / f"seg_{i:02d}.mp4"
    fade = f"fade=t=in:st=0:d=0.18,fade=t=out:st={dur - 0.18:.2f}:d=0.18"
    if "still" in clip:
        cmd = ["ffmpeg", "-v", "error", "-y", "-loop", "1", "-framerate", str(FPS), "-t", f"{dur}", "-i", str(bg),
               "-loop", "1", "-framerate", str(FPS), "-i", str(clip["still"]),
               "-filter_complex", f"[1:v]scale=-2:{PHONE_H}[p];[0:v][p]overlay=60:{(H - PHONE_H) // 2}:shortest=1,{fade},format=yuv420p",
               "-t", f"{dur}", "-r", str(FPS), "-c:v", "libx264", "-crf", "20", "-preset", "veryfast", str(out)]
    else:
        src = clip["src"]
        cmd = ["ffmpeg", "-v", "error", "-y", "-loop", "1", "-framerate", str(FPS), "-t", f"{dur}", "-i", str(bg),
               "-ss", f"{clip['start']:.3f}", "-t", f"{dur * speed:.3f}", "-i", str(src),
               "-filter_complex",
               f"[1:v]setpts=(PTS-STARTPTS)/{speed},fps={FPS},scale=-2:{PHONE_H}[p];"
               f"[0:v][p]overlay=60:{(H - PHONE_H) // 2}:shortest=1,{fade},format=yuv420p",
               "-t", f"{dur}", "-r", str(FPS), "-c:v", "libx264", "-crf", "20", "-preset", "veryfast", str(out)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"clip {i} failed:\n{r.stderr[-1200:]}")
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", type=Path, required=True)
    ap.add_argument("--work", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--web", type=Path, help="screenshot of the public verify page")
    a = ap.parse_args()

    scenes = sorted(p.stem for p in a.raw.glob("[0-9][0-9]_*.mp4"))
    logs = {n: json.loads((a.raw / f"{n}.json").read_text()) for n in scenes}
    clean = {}
    for n in scenes:
        clean[n] = a.work / "clean" / f"{n}.mp4"
        if not clean[n].exists():
            compose.clean_scene(a.raw, a.work, n)

    L = lambda n: logs[n]  # noqa: E731
    clips = []

    def add(scene, start, dur, phase, title, sub, speed=1.0, tag=""):
        clips.append(dict(src=clean[scene], start=max(start, 0), dur=dur, speed=speed, phase=phase,
                          title=title, sub=sub, tag=tag))

    add("01_launch", 0.0, 2.0, "CAPTURE", "Launch", "A hardware-backed signing key is ready before the first photo.")
    lv = L("02_live")
    add("02_live", clean_t(lv, "02_live", ev(lv, "legend_open")) - 0.2, 2.6, "CAPTURE", "Live sensors",
        "Real GPS satellites, accuracy and tilt. A live view only - never saved or signed.")
    co = L("03_capture_offline")
    add("03_capture_offline", clean_t(co, "03_capture_offline", ev(co, "airplane_on")) + 1.8, 4.2, "SEAL",
        "Capture, offline", "Airplane mode on. Photo + place + time are hashed and signed in about 3 seconds.")
    add("03_capture_offline", clean_t(co, "03_capture_offline", ev(co, "airplane_off")), 2.8, "SYNC",
        "Syncs by itself", "Network returns - the queued capture uploads with no tap.", speed=6.0, tag="x6 speed")
    hi = L("04_history")
    add("04_history", clean_t(hi, "04_history", ev(hi, "history")) + 1.2, 2.0, "VERIFY", "History",
        "Every capture gets a trust ring: Integrity, Signature, Attestation, Context.")
    vs = L("05_verify_server")
    add("05_verify_server", clean_t(vs, "05_verify_server", vs["cuts"][0]["a"]) - 0.9, 3.0, "VERIFY",
        "Server verification", "15 checks in 4 groups. VERIFIED means unchanged since capture.")
    add("05_verify_server", after_cut(vs, "05_verify_server", 1, 0.0), 1.6, "VERIFY", "The proof chain",
        "Photo, record, root, signature and key - each link checked.")
    vp = L("06_verify_phone")
    add("06_verify_phone", after_cut(vp, "06_verify_phone", 0, 0.0), 2.2, "VERIFY", "Verify on the phone",
        "No network: it says INCOMPLETE and never claims VERIFIED.")
    ex = L("07_explorer")
    add("07_explorer", clean_t(ex, "07_explorer", ev(ex, "flip")) - 0.6, 3.2, "TAMPER", "Flip one bit",
        "Photo hash, Merkle root and signature all break. Nothing is saved.", speed=1.6, tag="x1.6 speed")
    ta = L("09_tamper")
    add("09_tamper", after_cut(ta, "09_tamper", 0, 0.0), 2.3, "TAMPER", "Real tamper test",
        "One digit of the stored record edited on the phone - the check FAILS.")
    add("09_tamper", after_cut(ta, "09_tamper", 2, 0.0), 1.5, "TAMPER", "Restored", "Original bytes put back - VERIFIED again.")
    an = L("10_analyze")
    add("10_analyze", after_cut(an, "10_analyze", 0, 0.0), 2.6, "ANALYZE", "Forensic triage",
        "ELA heat-map and EXIF flags on any image. A report, never a verdict.", speed=2.2, tag="x2.2 speed")
    dv = L("11_device")
    add("11_device", clean_t(dv, "11_device", ev(dv, "tab")), 2.2, "DEVICE", "Device status",
        "What this phone can do, and the hardware key behind the signatures.", speed=2.5, tag="x2.5 speed")
    if a.web and a.web.exists():
        clips.append(dict(still=a.web, dur=1.8, phase="VERIFY", title="Public web verifier",
                          sub="The certificate's QR opens this page: no photo, no location."))

    a.work.mkdir(parents=True, exist_ok=True)
    segs = [segment(a.work, i, c, len(clips)) for i, c in enumerate(clips)]
    lst = a.work / "list.txt"
    lst.write_text("".join(f"file '{s}'\n" for s in segs))
    a.out.parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
                        "-c:v", "libx264", "-crf", "23", "-preset", "medium", "-movflags", "+faststart",
                        "-pix_fmt", "yuv420p", str(a.out)], capture_output=True, text=True)
    if r.returncode:
        sys.exit(r.stderr[-1500:])
    total = sum(c["dur"] for c in clips)
    print(f"{a.out}  {total:.1f}s  {a.out.stat().st_size / 1e6:.1f} MB  ({len(clips)} clips)")
    (a.work / "clips.json").write_text(json.dumps(
        [{k: str(v) if isinstance(v, Path) else v for k, v in c.items()} for c in clips], indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
