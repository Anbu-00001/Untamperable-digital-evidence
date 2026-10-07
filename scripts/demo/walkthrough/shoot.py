#!/usr/bin/env python3
"""Record the walkthrough scenes from the real app on the USB-attached phone.

    SCRCPY=/path/to/scrcpy python3 shoot.py [--raw DIR] scene [scene ...]
    python3 shoot.py --list

Scenes are independent: each starts from a known screen, so one that misbehaves
is re-shot on its own. Raw clips (unredacted!) go to --raw, which should NOT be
inside the repository; compose.py turns them into the published video.

Rules this script keeps, on purpose:
  * Everything on screen is the app really running — real taps, real sensors,
    real server round-trips. Nothing is mocked or pre-rendered.
  * The phone is set to "quiet" for the shoot (no heads-up notifications, which
    could show private messages) and put back afterwards.
  * Airplane mode is always switched off again, even if a scene fails.
  * Precise GPS coordinates are blacked out (Scene.hide) and the system photo
    picker is cut (Scene.cut), because it shows the owner's personal gallery.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import phone as P
from phone import Scene

RAW_DEFAULT = Path.home() / "walkthrough-raw"
SHUTTER = (360, 1163)


def state_file(raw: Path) -> Path:
    return raw / "state.json"


def load_state(raw: Path) -> dict:
    f = state_file(raw)
    return json.loads(f.read_text()) if f.exists() else {}


def save_state(raw: Path, **kv) -> None:
    st = load_state(raw)
    st.update(kv)
    state_file(raw).write_text(json.dumps(st, indent=2))


def newest_event_id() -> str:
    out = P.sh(f"run-as {P.PKG} ls -t files/captures").split()
    return next(n[:-5] for n in out if n.endswith(".json"))


def event_count() -> int:
    return len([n for n in P.sh(f"run-as {P.PKG} ls files/captures").split() if n.endswith(".json")])


def fresh_app(tab: str = "capture") -> None:
    P.stop_app()
    time.sleep(1)
    P.open_app()
    time.sleep(7.5)
    if tab != "capture":
        P.go(tab)
        time.sleep(2.5)


# ---------------------------------------------------------------- scenes ----
def launch(raw: Path) -> None:
    P.stop_app()
    time.sleep(1.5)
    with Scene("01_launch", raw) as s:
        s.dwell(1.2)
        s.mark("launch")
        P.open_app()
        s.dwell(7.5)


def live(raw: Path) -> None:
    fresh_app("capture")
    with Scene("02_live", raw) as s:
        s.dwell(4.5)
        s.mark("legend_open")
        P.tap(598, 246)  # the satellites-used / in-view chip
        s.dwell(6.5)
        s.mark("legend_close")
        P.tap(360, 800)
        s.dwell(1.5)
        s.mark("notice_open")
        P.tap_text(r"Recording photo")
        s.dwell(1.2)
        P.swipe(360, 1200, 360, 500, 500)  # the page scrolls; the note opens below the bar
        s.dwell(5.5)
        P.swipe(360, 500, 360, 1200, 500)
        s.dwell(1.0)
        s.mark("notice_close")
        P.tap_text(r"Recording photo")
        s.dwell(1.5)


def capture_offline(raw: Path) -> None:
    """Airplane mode on -> capture -> airplane mode off -> it syncs by itself."""
    fresh_app("capture")
    before = event_count()
    try:
        with Scene("03_capture_offline", raw) as s:
            s.dwell(1.5)
            s.mark("airplane_on")
            P.airplane(True)
            s.dwell(5.0)
            s.mark("shutter")
            P.tap(*SHUTTER)
            # The ring finishes when a new record exists; wait for it, not a timer.
            deadline = time.time() + 40
            while time.time() < deadline and event_count() == before:
                time.sleep(0.5)
            s.mark("recorded")
            s.dwell(4.0)
            s.mark("airplane_off")
            P.airplane(False)
            # The queued badge in the header disappears when the upload completes.
            deadline = time.time() + 75
            while time.time() < deadline:
                queued = [n for n in P.nodes() if n.bounds[1] < 150 and n.bounds[0] > 600 and n.text.isdigit()]
                if not queued:
                    break
            s.mark("synced")
            s.dwell(3.5)
    finally:
        P.airplane(False)
    save_state(raw, event_id=newest_event_id())


def _verify_button() -> P.Node:
    return P.wait_for(r"^Verify\|")


def history(raw: Path) -> None:
    fresh_app("capture")
    P.go("history")
    time.sleep(3.5)
    rest = P.coordinate_rects()
    P.go("capture")
    time.sleep(2.5)
    with Scene("04_history", raw) as s:
        s.dwell(1.8)
        with s.hide(rest, pre=-0.1):
            s.mark("history")
            P.go("history")
            s.dwell(10.0)


def _padded(rects, pad=45):
    return [(x, max(y - pad, 0), w, h + 2 * pad) for x, y, w, h in rects]


def _wait_result() -> None:
    P.wait_for(r"Authenticity result", timeout=40)
    time.sleep(2.2)  # let the list finish scrolling to the card


def _dry_run(fire, prepare=lambda: None):
    """Off camera. Returns (boxes for the History top state, boxes for the settled
    result state, how many swipes can pass before ANOTHER capture's coordinate line
    appears). Redaction only ever covers settled screens: the moments in between
    (the list scrolling to the result) are jump-cut instead of chased."""
    fresh_app("history")
    time.sleep(1.5)
    top = _padded(P.coordinate_rects())
    prepare()
    time.sleep(0.8)
    fire()
    _wait_result()
    settled = _padded(P.coordinate_rects())
    safe = 0
    for _ in range(8):
        P.swipe(360, 1250, 360, 450, 600)
        time.sleep(1.2)
        if P.coordinate_rects():
            break
        safe += 1
    return top, settled, max(safe, 1)


def _verify_on_camera(s: Scene, top, settled, prepare, fire, dwell_headline: float) -> None:
    """History top state -> (menu) -> verify -> settled result, with the scroll in
    between cut out."""
    with s.hide(top):
        s.dwell(2.0)
        s.mark("trigger")
        prepare()
        s.dwell(1.4)
        with s.cut("list scrolls to the result"):
            fire()
            _wait_result()
    with s.hide(settled):
        s.dwell(dwell_headline)


def _scroll_result(s: Scene, safe: int, swipes: int) -> None:
    # The first swipe carries the card (and its coordinate line) off the top:
    # jump-cut it so no frame shows the line mid-flight.
    s.mark("scroll_1")
    with s.cut("first swipe moves the card away"):
        P.swipe(360, 1250, 360, 450, 600)
        time.sleep(0.7)
    s.dwell(3.2)
    for i in range(min(swipes, safe + 1) - 1):
        s.mark(f"scroll_{i + 2}")
        P.swipe(360, 1250, 360, 500, 600)
        s.dwell(3.2)


def _show_result(scene_name: str, raw: Path, fire, prepare=lambda: None, dwell_headline: float = 5.5,
                 swipes: int = 4) -> None:
    top, settled, safe = _dry_run(fire, prepare)
    fresh_app("history")
    time.sleep(1.5)
    with Scene(scene_name, raw) as s:
        _verify_on_camera(s, top, settled, prepare, fire, dwell_headline)
        _scroll_result(s, safe, swipes)


def verify_server(raw: Path) -> None:
    _show_result("05_verify_server", raw, fire=lambda: P.tap(*_verify_button().center))


def verify_phone(raw: Path) -> None:
    def open_menu():
        P.tap(608, _verify_button().center[1])  # the card's menu sits level with Verify

    def fire():
        P.tap_text(r"Verify on this phone")

    _show_result("06_verify_phone", raw, fire=fire, prepare=open_menu, dwell_headline=6.5, swipes=3)


def explorer(raw: Path) -> None:
    fresh_app("history")
    time.sleep(1.5)
    rest = P.coordinate_rects()
    with Scene("07_explorer", raw) as s:
        with s.hide(rest, post=0.3):
            s.dwell(1.0)
            s.mark("open")
            P.tap_text(r"Explore the proof")
            s.dwell(0.9)
        s.dwell(4.5)  # the tree: photo + record -> Merkle root -> signature -> key
        s.mark("scroll")
        P.swipe(360, 1250, 360, 500, 500)
        s.dwell(3.0)
        s.mark("flip")
        P.tap_text(r"^Flip one bit")
        s.dwell(4.5)  # photo node turns red, hash digits change
        P.swipe(360, 1250, 360, 500, 600)
        s.dwell(4.5)  # root no longer matches / signature does not verify
        P.swipe(360, 1250, 360, 500, 600)
        s.dwell(4.0)
        s.mark("reset")
        P.tap_text(r"\|Reset", timeout=8)
        s.dwell(2.5)
        with s.hide(rest, pre=-0.1, post=0.4):
            s.mark("close")
            P.tap_text(r"\|Close")
            s.dwell(1.4)


def _scroll_to_bottom() -> None:
    for _ in range(2):  # the end of the page is the same place every run
        P.swipe(360, 1250, 360, 300, 500)
        time.sleep(0.9)


def evidence(raw: Path) -> None:
    fresh_app("history")
    time.sleep(1.5)
    rest = _padded(P.coordinate_rects())
    # Where does the viewer's own (rounded) coordinate line sit at the bottom?
    P.tap(132, 786)
    time.sleep(3.5)
    _scroll_to_bottom()
    time.sleep(1.0)
    in_viewer = _padded(P.coordinate_rects(), pad=30)
    fresh_app("history")
    time.sleep(1.5)
    with Scene("08_evidence", raw) as s:
        with s.hide(rest, pre=-0.1, post=0.3):
            s.dwell(1.0)
            s.mark("open_viewer")
            P.tap(132, 786)  # the capture's thumbnail
            s.dwell(0.6)
        s.dwell(4.5)  # the photograph exactly as stored, status still "not verified"
        s.mark("scroll")
        # The scroll would drag the coordinate line across the screen, so it is
        # jump-cut: the next frame is already at rest, with the line boxed.
        with s.cut("scroll past the coordinates"):
            _scroll_to_bottom()
        with s.hide(in_viewer, pre=0.3, post=0.3):
            s.dwell(5.5)
        with s.hide(rest, pre=-0.1, post=0.3):
            s.mark("close")
            P.tap_text(r"\|Close")
            s.dwell(1.2)


def tamper(raw: Path) -> None:
    import subprocess

    st = load_state(raw)
    eid = st["event_id"]
    repo = Path(__file__).resolve().parents[3]
    tamper_sh = str(repo / "scripts/demo/tamper.sh")
    out_a = subprocess.run([tamper_sh, eid], capture_output=True, text=True, cwd=repo)
    save_state(raw, tamper_out=out_a.stdout + out_a.stderr, tamper_rc=out_a.returncode)
    if out_a.returncode != 0:
        raise RuntimeError("tamper.sh refused:\n" + out_a.stdout + out_a.stderr)
    try:
        fire = lambda: P.tap(*_verify_button().center)  # noqa: E731
        top, settled, safe = _dry_run(fire)
        fresh_app("history")
        time.sleep(1.5)
        with Scene("09_tamper", raw) as s:
            _verify_on_camera(s, top, settled, lambda: None, fire, dwell_headline=6.0)  # FAILED headline
            _scroll_result(s, safe, swipes=2)  # proof chain: the Record link is the one that breaks
            # Put the original bytes back (off the published timeline).
            with s.cut("restore the original record"):
                out_b = subprocess.run([tamper_sh, "--restore"], capture_output=True, text=True, cwd=repo)
                save_state(raw, restore_out=out_b.stdout + out_b.stderr, restore_rc=out_b.returncode)
                fresh_app("history")
                time.sleep(1.5)
            _verify_on_camera(s, top, settled, lambda: None, fire, dwell_headline=6.0)  # VERIFIED again
    finally:
        subprocess.run([tamper_sh, "--restore"], capture_output=True, text=True, cwd=repo)


DEMO_IMAGES = ["docs/evidence/phase4-forensics/edited_photoshop.jpg", "docs/evidence/phase4-forensics/spliced_final.jpg"]


def _pick(filename: str, s: Scene) -> None:
    """Choose a file through the system picker. The picker shows the owner's
    gallery, so the whole interaction is cut from the published video."""
    stem = filename.rsplit(".", 1)[0]
    with s.cut("system photo picker"):
        P.tap_text(r"Pick an image|Pick another")
        time.sleep(2.0)
        P.tap(*P.wait_for(r"\|More").center)
        time.sleep(1.0)
        P.tap_text(r"Browse")
        time.sleep(3.0)
        P.tap_text(r"\|Search")
        time.sleep(1.0)
        P.sh("input", "text", stem)
        P.key(66)
        time.sleep(3.0)
        P.tap_text(re.escape(filename), timeout=15)
        P.wait_for(r"Triage aid|Error Level|plain English", timeout=40)
        time.sleep(1.0)


def analyze(raw: Path) -> None:
    repo = Path(__file__).resolve().parents[3]
    names = []
    for rel in DEMO_IMAGES:
        P.adb("push", str(repo / rel), f"/sdcard/Download/{Path(rel).name}")
        names.append(Path(rel).name)
    try:
        fresh_app("capture")
        with Scene("10_analyze", raw) as s:
            s.dwell(1.0)
            s.mark("tab")
            P.go("analyze")
            s.dwell(4.5)  # empty state: pick -> inspect -> read
            s.mark("pick_1")
            _pick(names[0], s)
            s.mark("result_1")
            s.dwell(4.0)
            for i in range(4):
                P.swipe(360, 1250, 360, 500, 600)
                s.dwell(3.5)
            for _ in range(5):
                P.swipe(360, 500, 360, 1300, 400)
            s.dwell(0.8)
            s.mark("pick_2")
            _pick(names[1], s)
            s.mark("result_2")
            s.dwell(4.0)
            for i in range(3):
                P.swipe(360, 1250, 360, 500, 600)
                s.dwell(3.5)
    finally:
        for n in names:
            P.sh("rm", "-f", f"/sdcard/Download/{n}")


def device(raw: Path) -> None:
    fresh_app("capture")
    with Scene("11_device", raw) as s:
        s.dwell(1.0)
        s.mark("tab")
        P.go("device")
        s.dwell(5.5)  # the capability grid
        s.mark("scroll")
        P.swipe(360, 1250, 360, 600, 600)
        s.dwell(3.5)  # durable backup card
        s.mark("details")
        P.tap_text(r"Technical details")
        s.dwell(1.2)
        for _ in range(3):
            P.swipe(360, 1250, 360, 450, 600)
            s.dwell(3.5)


SCENES = {
    "launch": launch,
    "live": live,
    "capture_offline": capture_offline,
    "history": history,
    "verify_server": verify_server,
    "verify_phone": verify_phone,
    "explorer": explorer,
    "evidence": evidence,
    "tamper": tamper,
    "analyze": analyze,
    "device": device,
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("scenes", nargs="*")
    ap.add_argument("--raw", type=Path, default=RAW_DEFAULT)
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.list or not a.scenes:
        print("\n".join(SCENES))
        return 0
    with P.quiet_phone():
        for name in a.scenes:
            print(f"== {name}", flush=True)
            SCENES[name](a.raw)
    return 0


if __name__ == "__main__":
    sys.exit(main())
