#!/usr/bin/env python3
"""Drive the USB-attached phone and record its screen, one scene at a time.

Why scrcpy and not `adb shell screenrecord`: ColorOS refuses to let the shell
user's screenrecord write anywhere, but scrcpy ships its own phone-side server and
records on the laptop. Get the official static build from
https://github.com/Genymobile/scrcpy/releases (no install needed) and point
SCRCPY at the binary.

Every scene is recorded to its own file together with a JSON log of what was
tapped and when, so a scene that goes wrong is re-shot alone, and so compose.py
can hide anything that must not be published:

  * hide(rects)  — screen regions blacked out for as long as the block runs
                   (used for precise GPS coordinates);
  * cut()        — time spans dropped from the final video (used for the system
                   photo picker, which would show the owner's personal gallery).

Nothing here fakes the app: taps are real touch events, and every number on
screen comes from the app itself.
"""
from __future__ import annotations

import contextlib
import json
import os
import re
import signal
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

PKG = "com.realitylock.app"
SCRCPY = os.environ.get("SCRCPY", "scrcpy")

# Full-precision coordinates as the app prints them: "12.91275, 80.14015  ·  ±3 m".
COORD_RE = re.compile(r"^-?\d{1,3}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}")

# Bottom-bar buttons (720 x 1612 screen).
NAV = {"capture": (113, 1448), "history": (366, 1448), "analyze": (487, 1448), "device": (607, 1448)}


def adb(*args: str, timeout: int = 60) -> str:
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout).stdout


def sh(*args: str, timeout: int = 60) -> str:
    return adb("shell", *args, timeout=timeout)


def tap(x: float, y: float) -> None:
    sh("input", "tap", str(int(x)), str(int(y)))


def swipe(x1: int, y1: int, x2: int, y2: int, ms: int = 450) -> None:
    sh("input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def key(code: int) -> None:
    sh("input", "keyevent", str(code))


def open_app() -> None:
    sh("monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")


def stop_app() -> None:
    sh("am", "force-stop", PKG)


def go(tab: str) -> None:
    tap(*NAV[tab])


@dataclass
class Node:
    text: str
    desc: str
    bounds: tuple[int, int, int, int]
    clickable: bool

    @property
    def label(self) -> str:
        return f"{self.text}|{self.desc}"

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


def nodes() -> list[Node]:
    """The current screen as uiautomator sees it."""
    for _ in range(4):
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        raw = adb("exec-out", "cat", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(0.5)
            continue
        out = []
        for n in root.iter("node"):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "0,0,0,0")))
            out.append(Node(n.get("text") or "", n.get("content-desc") or "", (x1, y1, x2, y2),
                            n.get("clickable") == "true"))
        return out
    return []


def find(pattern: str, ns: list[Node] | None = None, nth: int = 0) -> Node | None:
    rx = re.compile(pattern)
    hits = [n for n in (ns if ns is not None else nodes()) if rx.search(n.label)]
    return hits[nth] if len(hits) > nth else None


def wait_for(pattern: str, timeout: float = 20, nth: int = 0) -> Node:
    end = time.time() + timeout
    while time.time() < end:
        hit = find(pattern, nth=nth)
        if hit:
            return hit
    raise TimeoutError(f"never saw /{pattern}/ on screen")


def tap_text(pattern: str, timeout: float = 20, nth: int = 0) -> Node:
    node = wait_for(pattern, timeout, nth)
    tap(*node.center)
    return node


def coordinate_rects(pad: int = 10) -> list[tuple[int, int, int, int]]:
    """Boxes (x, y, w, h) around every precise-coordinate line on screen right now."""
    rects = []
    for n in nodes():
        if COORD_RE.match(n.text):
            x1, y1, x2, y2 = n.bounds
            rects.append((max(x1 - pad, 0), max(y1 - pad, 0), x2 - x1 + 2 * pad, y2 - y1 + 2 * pad))
    return rects


@contextlib.contextmanager
def quiet_phone():
    """No heads-up notifications (they could show private messages) for the shoot."""
    keys = [("global", "heads_up_notifications_enabled"), ("global", "zen_mode"), ("system", "show_touches")]
    saved = {k: sh("settings", "get", ns, k).strip() for ns, k in keys}
    try:
        sh("settings", "put", "global", "heads_up_notifications_enabled", "0")
        sh("settings", "put", "global", "zen_mode", "2")
        yield
    finally:
        for ns, k in keys:
            if saved[k] in ("", "null"):
                sh("settings", "delete", ns, k)
            else:
                sh("settings", "put", ns, k, saved[k])
        sh("settings", "put", "system", "show_touches", "0")


def airplane(on: bool) -> None:
    sh("cmd", "connectivity", "airplane-mode", "enable" if on else "disable")


class Scene:
    """One continuous screen recording plus the log that compose.py needs."""

    def __init__(self, name: str, outdir: Path, show_touches: bool = True):
        self.name = name
        self.dir = Path(outdir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.mp4 = self.dir / f"{name}.mp4"
        self.events: list[dict] = []
        self.hides: list[dict] = []
        self.cuts: list[dict] = []
        self.show_touches = show_touches
        self._proc: subprocess.Popen | None = None
        self._t0 = 0.0

    # -- timeline -----------------------------------------------------------
    def now(self) -> float:
        return time.monotonic() - self._t0

    def mark(self, label: str, **extra) -> None:
        self.events.append({"t": round(self.now(), 3), "label": label, **extra})

    def dwell(self, seconds: float) -> None:
        time.sleep(seconds)

    @contextlib.contextmanager
    def hide(self, rects, pre: float | None = None, post: float | None = None):
        """Black out these (x, y, w, h) boxes while the block runs.

        pre/post widen the window (seconds) beyond the block: the recording lags
        the log by the adb round trip and the encoder, so the default is generous.
        Short, fast things (a swipe) pass small values so the box does not linger.
        """
        start = self.now()
        try:
            yield
        finally:
            w = {"a": round(start, 3), "b": round(self.now(), 3), "rects": [list(r) for r in rects]}
            if pre is not None:
                w["pre"] = pre
            if post is not None:
                w["post"] = post
            self.hides.append(w)

    @contextlib.contextmanager
    def cut(self, why: str = ""):
        """Drop this stretch from the published video."""
        start = self.now()
        try:
            yield
        finally:
            self.cuts.append({"a": round(start, 3), "b": round(self.now(), 3), "why": why})

    # -- recorder -----------------------------------------------------------
    def __enter__(self) -> "Scene":
        cmd = [SCRCPY, "--no-window", "--no-audio", f"--record={self.mp4}",
               "--video-bit-rate=10M", "--max-fps=30"]
        if self.show_touches:
            # Shown on the phone for the length of the recording only; scrcpy
            # restores the setting on exit (quiet_phone() also resets it).
            cmd.append("--show-touches")
        self.mp4.unlink(missing_ok=True)
        self._proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # scrcpy's own "Recording started" line sits in a pipe buffer until exit,
        # so the output file appearing is the signal that frames are being muxed.
        deadline = time.time() + 30
        while not self.mp4.exists():
            if self._proc.poll() is not None or time.time() > deadline:
                self._proc.kill()
                raise RuntimeError("scrcpy did not start recording")
            time.sleep(0.02)
        self._t0 = time.monotonic()
        time.sleep(0.6)
        self.mark("start")
        return self

    def __exit__(self, *_exc) -> None:
        self.mark("end")
        assert self._proc is not None
        self._proc.send_signal(signal.SIGINT)
        try:
            self._proc.wait(25)
        except subprocess.TimeoutExpired:
            self._proc.kill()
        self.log_path.write_text(json.dumps(
            {"name": self.name, "events": self.events, "hides": self.hides, "cuts": self.cuts}, indent=2))

    @property
    def log_path(self) -> Path:
        return self.dir / f"{self.name}.json"
