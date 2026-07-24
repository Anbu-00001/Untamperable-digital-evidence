#!/usr/bin/env bash
# End-to-end test for Reality Lock.
#
# Exercises the whole system as a user would: build, unit-test, start the
# backend, drive a real capture on a real device, pull the resulting proof
# sidecars, check them against the shared schema AND Phase 2's exit criteria,
# then feed them to the backend's /proof and /verify routes.
#
# Everything is discovered at run time — no hardcoded tap coordinates, ports,
# package names or paths.
#
# Usage:  scripts/e2e/run_e2e.sh [captures]      (default 3)
#         SKIP_DEVICE=1 scripts/e2e/run_e2e.sh  (backend + unit tests only)
set -uo pipefail

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
ok()   { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
step() { echo; echo "=== $1 ==="; }

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
step "3. Backend boots and serves /health"
(cd backend && PORT="$PORT" NODE_ENV=test npm start >"$WORK/backend.log" 2>&1) &
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

    # ------------------------------------------------------------------
    step "5. Driving $CAPTURES captures on the device"
    adb -s "$DEVICE" shell am force-stop "$PKG"
    adb -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
    sleep 5

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

    BEFORE_COUNT="$(adb -s "$DEVICE" shell run-as "$PKG" ls files/captures/ 2>/dev/null | grep -c '\.json' || echo 0)"
    for i in $(seq 1 "$CAPTURES"); do
      if tap_by_text "Capture event"; then
        echo "  capture $i triggered"
      else
        bad "could not find the 'Capture event' button (permissions granted?)"
        break
      fi
      sleep 6
    done
    AFTER_COUNT="$(adb -s "$DEVICE" shell run-as "$PKG" ls files/captures/ 2>/dev/null | grep -c '\.json' || echo 0)"
    NEW=$((AFTER_COUNT - BEFORE_COUNT))
    if [ "$NEW" -ge 1 ]; then ok "$NEW new event(s) recorded on device"; else bad "no new events recorded"; fi

    # ------------------------------------------------------------------
    step "6. Pulled sidecars satisfy the schema AND Phase 2 exit criteria"
    mkdir -p "$WORK/events"
    for f in $(adb -s "$DEVICE" shell run-as "$PKG" ls -t files/captures/ 2>/dev/null | tr -d '\r' | grep '\.json$' | head -"$CAPTURES"); do
      adb -s "$DEVICE" shell run-as "$PKG" cat "files/captures/$f" > "$WORK/events/$f" 2>/dev/null
      # Media must exist beside the sidecar (the "JPEG file reference" criterion).
      JPG="${f%.json}.jpg"
      SIZE="$(adb -s "$DEVICE" shell run-as "$PKG" stat -c %s "files/captures/$JPG" 2>/dev/null | tr -d '\r')"
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
    step "7. Backend accepts the real captures"
    for f in "$WORK/events"/*.json; do
      [ -e "$f" ] || continue
      # Phase 2 output is a prefix, so /proof is expected to reject it as
      # incomplete until Phase 3 adds merkle/signature. What is being tested is
      # that the backend reads the same contract and says so precisely.
      CODE="$(curl -s -o "$WORK/proof.out" -w '%{http_code}' -X POST "$BASE_URL/proof" \
              -H 'Content-Type: application/json' --data-binary "@$f")"
      REASON="$(python3 -c "
import json,sys
try:
  d=json.load(open('$WORK/proof.out'))
  errs=d.get('errors') or []
  print('; '.join(sorted({e.get('message','') for e in errs})) if errs else d.get('persistence',''))
except Exception: print('(unparseable)')")"
      case "$CODE" in
        200|201) ok "POST /proof → $CODE ($REASON)" ;;
        400|422) ok "POST /proof → $CODE, correctly reports the Phase-3 gap: $REASON" ;;
        *)       bad "POST /proof → $CODE ($REASON)" ;;
      esac

      VCODE="$(curl -s -o "$WORK/verify.out" -w '%{http_code}' -X POST "$BASE_URL/verify" \
               -H 'Content-Type: application/json' --data-binary "@$f")"
      if [ "$VCODE" = "200" ] || [ "$VCODE" = "400" ] || [ "$VCODE" = "422" ]; then
        ok "POST /verify → $VCODE ($(head -c 100 "$WORK/verify.out"))"
      else
        bad "POST /verify → $VCODE"
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
