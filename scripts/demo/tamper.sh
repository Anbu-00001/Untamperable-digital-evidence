#!/usr/bin/env bash
# ============================================================================
# Live tamper demo: change ONE character of a capture's signed record on the
# phone, so the next Verify shows `metadataHashMatch` failing — then put the
# original bytes back.
#
#   ./scripts/demo/tamper.sh            # tamper the newest capture
#   ./scripts/demo/tamper.sh <eventId>  # tamper a specific one
#   ./scripts/demo/tamper.sh --restore  # restore every capture this script touched
#
# What it edits: one digit inside the metadata's `wallClockMillis` (the capture
# time) — a change of a few milliseconds, invisible to a person reading the
# record, which is exactly why it makes the point. The edit is made on the RAW
# bytes; the document is never parsed and re-serialised, so the only difference
# from the original is that one character.
#
# Safety rules, because the backend store is append-only:
#   * Refuses unless the event's sync stage is COMPLETE. A tampered package that
#     had not yet uploaded would be uploaded by the next sync and stored forever.
#   * The original is copied to files/demo-tamper-backup/ first, and --restore
#     checks the SHA-256 of the restored file against the backup.
#
# Say what you did when you demo this: "I just edited the stored record by
# hand" — the point is that the change is caught, not that it was a surprise.
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=../e2e/app_constants.sh
source scripts/e2e/app_constants.sh
PKG="$(grep -oP '(?<=^val appPackage = ")[^"]+' android/app/build.gradle.kts)"
BACKUP="files/demo-tamper-backup"
as_app() { adb shell run-as "$PKG" "$@"; }
die() { echo "tamper: $*" >&2; exit 1; }

sha_on_device() { as_app sha256sum "$1" | awk '{print $1}'; }

if [ "${1:-}" = "--restore" ]; then
  files="$(as_app ls "$BACKUP" 2>/dev/null | tr -d '\r' | grep '\.json$' || true)"
  [ -n "$files" ] || { echo "nothing to restore"; exit 0; }
  for f in $files; do
    as_app cp "$BACKUP/$f" "files/$CAPTURES_SUBDIR/$f"
    if [ "$(sha_on_device "$BACKUP/$f")" = "$(sha_on_device "files/$CAPTURES_SUBDIR/$f")" ]; then
      as_app rm "$BACKUP/$f"
      echo "restored ${f%.json} — byte-identical to the original"
    else
      die "restore of $f did not verify; backup left in place at $BACKUP/$f"
    fi
  done
  exit 0
fi

ID="${1:-}"
if [ -z "$ID" ]; then
  ID="$(as_app ls -t "files/$CAPTURES_SUBDIR" | tr -d '\r' | grep '\.json$' | head -1)"
  ID="${ID%.json}"
fi
[ -n "$ID" ] || die "no captures on the device"
PKG_FILE="files/$CAPTURES_SUBDIR/$ID.json"

stage="$(as_app cat "files/$SYNC_SUBDIR/$ID.json" 2>/dev/null \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("stage",""))' 2>/dev/null || true)"
[ "$stage" = "COMPLETE" ] || die "event $ID has sync stage '${stage:-none}', not COMPLETE — refusing, a tampered copy would be uploaded and stored permanently"

as_app mkdir -p "$BACKUP"
if as_app ls "$BACKUP/$ID.json" >/dev/null 2>&1; then
  die "event $ID is already tampered (backup exists) — run --restore first"
fi
as_app cp "$PKG_FILE" "$BACKUP/$ID.json"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
adb exec-out run-as "$PKG" cat "$PKG_FILE" >"$WORK/original.json"

# One digit, on the raw bytes. The last digit of wallClockMillis is bumped by one
# (9 wraps to 0), so the value stays a well-formed number and the schema still
# passes — the failure the audience sees is the hash, not a parse error.
python3 - "$WORK/original.json" "$WORK/tampered.json" <<'PY'
import re, sys
raw = open(sys.argv[1], 'rb').read()
m = re.search(rb'"wallClockMillis"\s*:\s*(\d+)', raw)
if not m:
    sys.exit("no wallClockMillis field found; refusing to guess")
pos = m.end(1) - 1
digit = raw[pos] - ord('0')
out = raw[:pos] + bytes([ord('0') + (digit + 1) % 10]) + raw[pos + 1:]
assert len(out) == len(raw) and sum(a != b for a, b in zip(raw, out)) == 1
open(sys.argv[2], 'wb').write(out)
print(f"wallClockMillis ...{chr(raw[pos])} -> ...{chr(out[pos])} (1 byte of {len(raw)})")
PY

# Written to a temp file, confirmed, then renamed into place. `adb exec-in` can
# return before the device-side write has finished, so hashing the target right
# away raced a half-written file (seen on the CPH2591): poll the TEMP file until
# it matches, and only then swap it in with an atomic mv.
want="$(sha256sum "$WORK/tampered.json" | awk '{print $1}')"
adb exec-in run-as "$PKG" sh -c "cat > $PKG_FILE.tmp" <"$WORK/tampered.json"
for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ "$(sha_on_device "$PKG_FILE.tmp")" = "$want" ] && break
  sleep 0.3
done
[ "$(sha_on_device "$PKG_FILE.tmp")" = "$want" ] \
  || { as_app rm -f "$PKG_FILE.tmp"; die "the edit did not land as written; original untouched"; }
as_app mv "$PKG_FILE.tmp" "$PKG_FILE"

echo "tampered event $ID. In the app: History > this capture > Verify."
echo "Expect metadataHashMatch FAIL — and timestampPlausible too, because the capture"
echo "time no longer matches the clock evidence recorded beside it. Undo: $0 --restore"
