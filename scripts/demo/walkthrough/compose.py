#!/usr/bin/env python3
"""Turn the raw per-scene recordings into the published walkthrough.

    python3 compose.py clean  --raw RAW --out WORK     # redact + cut + trim each scene
    python3 compose.py frame  --raw RAW --scene NAME --t SECONDS --out FILE   # redacted still
    python3 compose.py build  --raw RAW --out WORK --video FINAL.mp4 --media DIR

`clean` is the only step that touches the phone footage, and it only does three
things: black out the boxes recorded by Scene.hide (precise GPS coordinates), drop
the spans recorded by Scene.cut (the system photo picker, which shows a personal
gallery), and trim the lead-in/lead-out. Every other pixel is the phone's own.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

FPS = 30
BOX = "0x0a1020"       # fill for redaction boxes (app background navy)
EDGE = "0x2a3c78"      # thin outline, so a box reads as deliberate, not as a glitch
PRE, POST = 0.30, 1.00  # recording lags the log (adb latency, encode): widen every window

# Per-scene trim, as (first mark to start from, seconds after it, seconds to drop at the end).
TRIM = {
    "01_launch": ("launch", 0.15, 1.0),
}


def run(cmd: list[str]) -> None:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("ffmpeg failed:\n" + " ".join(cmd) + "\n" + r.stderr[-1500:])


def load_log(raw: Path, scene: str) -> dict:
    return json.loads((raw / f"{scene}.json").read_text())


def mark_time(log: dict, label: str) -> float:
    return next(e["t"] for e in log["events"] if e["label"] == label)


def window(scene: str, log: dict) -> tuple[float, float]:
    end = mark_time(log, "end")
    if scene in TRIM:
        label, after, drop = TRIM[scene]
        return mark_time(log, label) + after, end - drop
    return 0.9, end - 0.2


def redaction_filters(log: dict, until: float | None = None) -> list[str]:
    out = []
    for h in log.get("hides", []):
        a, b = max(h["a"] - h.get("pre", PRE), 0), h["b"] + h.get("post", POST)
        if until is not None and a > until:
            continue
        for x, y, w, hh in h["rects"]:
            en = f"enable='between(t,{a:.3f},{b:.3f})'"
            out.append(f"drawbox=x={x}:y={y}:w={w}:h={hh}:color={BOX}@1:t=fill:{en}")
            out.append(f"drawbox=x={x}:y={y}:w={w}:h={hh}:color={EDGE}@1:t=2:{en}")
    return out


def cut_expr(log: dict) -> str:
    return "".join(f"*not(between(t,{c['a'] - 0.25:.3f},{c['b'] + 0.6:.3f}))" for c in log.get("cuts", []))


def clean_scene(raw: Path, out: Path, scene: str) -> Path:
    log = load_log(raw, scene)
    t0, t1 = window(scene, log)
    sel = f"gte(t,{t0:.3f})*lte(t,{t1:.3f}){cut_expr(log)}"
    chain = [f"fps={FPS}", "tpad=stop_mode=clone:stop_duration=4"]
    chain += redaction_filters(log)
    chain += [f"select='{sel}'", "setpts=N/FRAME_RATE/TB", "format=yuv420p"]
    dst = out / "clean" / f"{scene}.mp4"
    dst.parent.mkdir(parents=True, exist_ok=True)
    run(["ffmpeg", "-v", "error", "-y", "-i", str(raw / f"{scene}.mp4"), "-vf", ",".join(chain),
         "-r", str(FPS), "-c:v", "libx264", "-crf", "17", "-preset", "veryfast", "-an", str(dst)])
    return dst


def still(raw: Path, scene: str, t: float, dst: Path) -> None:
    """One frame at video time t with the same redaction as the video."""
    log = load_log(raw, scene)
    chain = [f"fps={FPS}"] + redaction_filters(log)
    run(["ffmpeg", "-v", "error", "-y", "-ss", "0", "-i", str(raw / f"{scene}.mp4"), "-vf",
         ",".join(chain) + f",select='gte(t,{t:.3f})'", "-frames:v", "1", str(dst)])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["clean", "frame"])
    ap.add_argument("--raw", type=Path, required=True)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--scene")
    ap.add_argument("--t", type=float, default=0.0)
    ap.add_argument("--scenes", nargs="*")
    a = ap.parse_args()
    if a.cmd == "clean":
        names = a.scenes or sorted(p.stem for p in a.raw.glob("[0-9][0-9]_*.mp4"))
        for n in names:
            print("clean", n, flush=True)
            clean_scene(a.raw, a.out, n)
    elif a.cmd == "frame":
        still(a.raw, a.scene, a.t, a.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
