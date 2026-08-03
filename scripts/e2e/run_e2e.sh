#!/usr/bin/env bash
# End-to-end test for Reality Lock.
#
# Exercises the whole system as a user would: build, unit-test, start the
# backend, drive a real capture on a real device, pull the resulting proof
# sidecars, check them against the shared schema AND Phase 2's exit criteria,
# then feed them to the backend's /proof and /verify routes.
#
# Discovered at run time: the device, the application id, a free port, and the
# shutter button's screen coordinates (never tapped by fixed x/y).
# Read from the app's own source via app_constants.sh: the shutter button's
# label and the on-disk `captures` directory name.
# Still mirrored by hand: the debug APK's output path and the launcher activity's
# class name.
#
# Usage:  scripts/e2e/run_e2e.sh [captures]      (default 3)
#         SKIP_DEVICE=1 scripts/e2e/run_e2e.sh  (backend + unit tests only)
set -uo pipefail

# Values shared with the app (shutter label, on-disk subdirectory names),
# read from its own source so this script and the app cannot drift apart.
. "$(dirname "${BASH_SOURCE[0]}")/app_constants.sh"

CAPTURES="${1:-3}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# A port nobody else is on, so a dev server already running cannot mask a failure.
PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("",0));print(s.getsockname()[1]);s.close()')"
BASE_URL="http://127.0.0.1:${PORT}"
PKG="$(sed -n 's/^ *val appPackage = "\(.*\)"/\1/p' android/app/build.gradle.kts)"
WORK="$(mktemp -d)"
BACKEND_PID=""

PASS=0; FAIL=0
# Set when a precondition makes driving the UI impossible (see step 4). Declared
# here because the script runs under `set -u`, where a conditional assignment
# deeper in the flow would leave it unbound on the happy path.
SKIP_CAPTURES=0
ok()   { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
step() { echo; echo "=== $1 ==="; }

# How many event sidecars the app currently holds.
#
# `grep -c` already prints a count for an empty stream, and it *also* exits 1 when
# that count is zero. The earlier `... | grep -c '\.json' || echo 0` therefore
# emitted BOTH the grep's "0" and the fallback "0", so the caller received the two-
# line string "0\n0" and `$((AFTER - BEFORE))` died with
# `syntax error in expression (error token is "0")`. It broke in exactly the case
# it was added to survive: no captures on the device.
#
# `tail -1` keeps the last line whatever happens, and the `:-0` covers adb failing
# outright and printing nothing at all.
count_event_sidecars() {
  local n
  n="$(adb -s "$DEVICE" shell run-as "$PKG" ls "files/$CAPTURES_SUBDIR/" 2>/dev/null \
    | tr -d '\r' | grep -c '\.json$' | tail -1)"
  echo "${n:-0}"
}

cleanup() {
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "Reality Lock — end-to-end test"
echo "repo=$REPO_ROOT  package=$PKG  backend_port=$PORT  captures=$CAPTURES"

# --------------------------------------------------------------------------
step "1. Android unit tests"
if (cd android && ./gradlew --quiet :app:testDebugUnitTest >"$WORK/unit.log" 2>&1); then
  TOTAL=$(python3 - <<'PY'
import glob,xml.etree.ElementTree as ET
t=f=e=0
for p in glob.glob('android/app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(p).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(f"{t} {f} {e}")
PY
)
  read -r T F E <<<"$TOTAL"
  if [ "$F" = "0" ] && [ "$E" = "0" ]; then ok "$T unit tests, 0 failures"; else bad "$F failures, $E errors"; fi
else
  bad "unit tests did not run (see $WORK/unit.log)"; tail -20 "$WORK/unit.log"
fi

# --------------------------------------------------------------------------
step "2. Shared schema compiles"
if (cd backend && npm run --silent validate:schema >/dev/null 2>&1); then
  ok "proof-package.schema.json compiles and the example validates"
else
  bad "schema/example validation failed"
fi

# --------------------------------------------------------------------------
step "2b. Backend verifier unit tests"
if (cd backend && npm test >"$WORK/backend-test.log" 2>&1); then
  BT="$(grep -c '^ok ' "$WORK/backend-test.log" 2>/dev/null || echo '?')"
  ok "$BT backend crypto tests pass (incl. the cross-implementation Merkle vector)"
else
  bad "backend verifier tests failed"
  grep -E '^not ok|AssertionError|expected' "$WORK/backend-test.log" | head -10
fi

# --------------------------------------------------------------------------
step "3. Backend boots and serves /health"
# The media travels inline as base64 for /verify, so raise the body cap.
(cd backend && PORT="$PORT" NODE_ENV=test MAX_JSON_BYTES=16777216 npm start >"$WORK/backend.log" 2>&1) &
BACKEND_PID=$!
for _ in $(seq 1 40); do
  curl -sf "$BASE_URL/health" >/dev/null 2>&1 && break
  sleep 0.25
done
HEALTH="$(curl -sf "$BASE_URL/health" 2>/dev/null)"
if [ -n "$HEALTH" ]; then
  ok "GET /health → $(echo "$HEALTH" | head -c 120)"
else
  bad "backend did not answer on $BASE_URL/health"; tail -20 "$WORK/backend.log"
fi

# --------------------------------------------------------------------------
if [ "${SKIP_DEVICE:-0}" = "1" ]; then
  step "4-7. Device phase skipped (SKIP_DEVICE=1)"
else
  step "4. Device present and app installed"
  DEVICE="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
  if [ -z "$DEVICE" ]; then
    bad "no adb device attached — run with SKIP_DEVICE=1 to test the rest"
  else
    MODEL="$(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')"
    ok "device $DEVICE ($MODEL)"

    APK=android/app/build/outputs/apk/debug/app-debug.apk
    (cd android && ./gradlew --quiet :app:assembleDebug >>"$WORK/unit.log" 2>&1)
    if adb -s "$DEVICE" install -r "$APK" >/dev/null 2>&1; then ok "APK installed"; else bad "APK install failed"; fi

    # Fail fast, and say what to do about it.
    #
    # Without the camera permission the app opens on its rationale screen, the
    # shutter never renders, and the run dies several steps later as "could not
    # find the 'Capture event' button" — which reads like a UI regression. It is
    # not: on this project's ColorOS target BOTH grant routes are refused
    # (`adb shell pm grant` → no GRANT_RUNTIME_PERMISSIONS;
    # `UiAutomation.grantRuntimePermission` → SecurityException, see
    # docs/design/PHASE6_SECURITY_VALIDATION.md), and a reinstall clears the
    # grant — which the install above has very likely just done.
    if ! adb -s "$DEVICE" shell dumpsys package "$PKG" | grep -q "android.permission.CAMERA: granted=true"; then
      bad "CAMERA is not granted to $PKG — no capture can be driven.
        This device refuses to grant it from adb or from instrumentation, so it has
        to be done by hand: open Reality Lock, allow camera (and location), re-run.
        Note that reinstalling the APK clears the grant again."
      SKIP_CAPTURES=1
    fi

    # ------------------------------------------------------------------
    step "5. Driving $CAPTURES captures on the device"
    adb -s "$DEVICE" shell am force-stop "$PKG"
    adb -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
    # Let the camera bind and the sensor stream reach steady state. A shutter
    # fired immediately after launch can precede the first sensor delivery, in
    # which case the skew guard correctly records motion as absent.
    sleep 10

    # Find the shutter by its label rather than by fixed coordinates, so this
    # keeps working if the layout changes.
    tap_by_text() {
      local want="$1" xml
      adb -s "$DEVICE" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
      xml="$(adb -s "$DEVICE" shell cat /sdcard/ui.xml 2>/dev/null)"
      local coords
      coords="$(printf '%s' "$xml" | python3 -c "
import sys,re
x=sys.stdin.read(); want=sys.argv[1]
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x):
    if m.group(1).strip()==want:
        x1,y1,x2,y2=map(int,m.groups()[1:]); print((x1+x2)//2,(y1+y2)//2); break
" "$want")"
      [ -z "$coords" ] && return 1
      adb -s "$DEVICE" shell input tap $coords
    }

    BEFORE_COUNT="$(count_event_sidecars)"
    for i in $(seq 1 "$CAPTURES"); do
      [ "$SKIP_CAPTURES" -eq 1 ] && break
      if tap_by_text "$SHUTTER_LABEL"; then
        echo "  capture $i triggered"
      else
        bad "could not find the '$SHUTTER_LABEL' button on screen"
        break
      fi
      sleep 6
    done
    AFTER_COUNT="$(count_event_sidecars)"
    NEW=$((AFTER_COUNT - BEFORE_COUNT))
    if [ "$NEW" -ge 1 ]; then ok "$NEW new event(s) recorded on device"; else bad "no new events recorded"; fi

    # ------------------------------------------------------------------
    step "6. Pulled sidecars satisfy the schema AND Phase 2 exit criteria"
    mkdir -p "$WORK/events"
    for f in $(adb -s "$DEVICE" shell run-as "$PKG" ls -t files/$CAPTURES_SUBDIR/ 2>/dev/null | tr -d '\r' | grep '\.json$' | head -"$CAPTURES"); do
      adb -s "$DEVICE" shell run-as "$PKG" cat "files/$CAPTURES_SUBDIR/$f" > "$WORK/events/$f" 2>/dev/null
      # Media must exist beside the sidecar (the "JPEG file reference" criterion).
      JPG="${f%.json}.jpg"
      SIZE="$(adb -s "$DEVICE" shell run-as "$PKG" stat -c %s "files/$CAPTURES_SUBDIR/$JPG" 2>/dev/null | tr -d '\r')"
      if [ -n "$SIZE" ] && [ "$SIZE" -gt 0 ] 2>/dev/null; then
        echo "  media  $JPG (${SIZE} bytes)"
      else
        bad "media file missing for $f"
      fi
      if node scripts/e2e/check_event.js "$WORK/events/$f" --expect-location --expect-motion; then
        PASS=$((PASS+1))
      else
        FAIL=$((FAIL+1))
      fi
    done

    # ------------------------------------------------------------------
    step "7. Backend verifies the real captures cryptographically"
    for f in "$WORK/events"/*.json; do
      [ -e "$f" ] || continue
      EVENT_ID="$(basename "$f" .json)"
      adb -s "$DEVICE" exec-out run-as "$PKG" cat "files/$CAPTURES_SUBDIR/$EVENT_ID.jpg" > "$WORK/media.jpg" 2>/dev/null

      # Envelope form: the media travels beside the package, never inside it —
      # the schema forbids extra root properties, and rightly so.
      python3 - "$f" "$WORK/media.jpg" "$WORK/envelope.json" <<'PY'
import base64, json, sys
pkg = json.load(open(sys.argv[1]))
media = open(sys.argv[2], 'rb').read()
json.dump({"package": pkg, "mediaBase64": base64.b64encode(media).decode()}, open(sys.argv[3], 'w'))
PY
      RESP="$(curl -s -X POST "$BASE_URL/verify" -H 'Content-Type: application/json' \
              --data-binary "@$WORK/envelope.json")"
      SUMMARY="$(printf '%s' "$RESP" | python3 -c "
import json,sys
d=json.load(sys.stdin); c=d.get('checks',{})
crypto=['mediaHashMatch','metadataHashMatch','merkleRootMatch','signatureValid',
        'attestationPresent','attestationChainValid','attestationKeyBinding']
bad=[k for k in crypto if c.get(k)!='pass']
print(('OK ' if not bad else 'BAD ')+d.get('verdict','?')+' '+(','.join(bad) if bad else 'all crypto checks pass'))
")"
      case "$SUMMARY" in
        OK*) ok "POST /verify → ${SUMMARY#OK }" ;;
        *)   bad "POST /verify → ${SUMMARY#BAD }" ;;
      esac

      # Tamper: one flipped bit must be detected.
      python3 - "$f" "$WORK/media.jpg" "$WORK/tampered.json" <<'PY'
import base64, json, sys
pkg = json.load(open(sys.argv[1]))
media = bytearray(open(sys.argv[2], 'rb').read())
media[len(media)//2] ^= 0x01
json.dump({"package": pkg, "mediaBase64": base64.b64encode(bytes(media)).decode()}, open(sys.argv[3], 'w'))
PY
      TVERDICT="$(curl -s -X POST "$BASE_URL/verify" -H 'Content-Type: application/json' \
                  --data-binary "@$WORK/tampered.json" \
                  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('verdict'),d.get('checks',{}).get('mediaHashMatch'))")"
      if [ "$TVERDICT" = "failed fail" ]; then
        ok "one flipped bit in the JPEG is detected (verdict=failed, mediaHashMatch=fail)"
      else
        bad "tampered media was NOT detected (got: $TVERDICT)"
      fi
      break   # one representative round-trip is enough
    done
  fi
fi

# --------------------------------------------------------------------------
echo
echo "=================================================="
echo "  passed: $PASS    failed: $FAIL"
echo "=================================================="
[ "$FAIL" -eq 0 ] || exit 1
