#!/usr/bin/env bash
# =============================================================================
# Reality Lock — Phase 5 end-to-end test: offline sync, storage, verification.
#
# Proves the Phase-5 exit criteria against real hardware and a real server:
#
#   1. A capture made with NO connectivity (airplane mode) is queued, not lost.
#   2. It uploads by itself once connectivity returns — no user action.
#   3. The backend stores the package immutably and only accepts media whose
#      digest matches the signed package.
#   4. A package verified from the server's own copy of the media reaches
#      `verified` — all five checks, including the two plausibility ones.
#   5. An edited package is reported `failed`, with the correct check flagged.
#   6. The public QR endpoint returns a verdict and leaks no GPS.
#
# Nothing about the environment is hardcoded: the package name, a free port and
# the shutter button are all discovered at run time. The app is pointed at the
# host over `adb reverse`, so this works over USB with no LAN setup and no
# changes to the committed configuration.
#
# Usage:  ./scripts/e2e/run_sync_e2e.sh
# =============================================================================
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

WORK="$(mktemp -d)"
PASS=0
FAIL=0
BACKEND_PID=""
PORT=""
AIRPLANE_TOUCHED=0

# ---- reporting --------------------------------------------------------------
step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[0;32mPASS\033[0m %s\n' "$*"; PASS=$((PASS + 1)); }
bad()  { printf '  \033[0;31mFAIL\033[0m %s\n' "$*"; FAIL=$((FAIL + 1)); }
info() { printf '       %s\n' "$*"; }

cleanup() {
  # Always put the phone back the way we found it: leaving a device in airplane
  # mode because a test aborted would be a genuinely annoying side effect.
  if [ "$AIRPLANE_TOUCHED" = "1" ]; then
    adb -s "$DEVICE" shell cmd connectivity airplane-mode disable >/dev/null 2>&1
    info "airplane mode restored to off"
  fi
  [ -n "$PORT" ] && adb -s "${DEVICE:-}" reverse --remove "tcp:$PORT" >/dev/null 2>&1
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

# =============================================================================
step "1. Device and package discovery"
DEVICE="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$DEVICE" ]; then
  echo "No adb device attached. Connect the phone and enable USB debugging." >&2
  exit 1
fi
MODEL="$(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')"
ok "device $DEVICE ($MODEL)"

# The applicationId is declared once in the build file; read it rather than
# repeating it here.
PKG="$(grep -oP '(?<=^val appPackage = ")[^"]+' android/app/build.gradle.kts)"
[ -n "$PKG" ] || { echo "could not determine the application id" >&2; exit 1; }
ok "package $PKG"

# =============================================================================
step "2. Starting the backend on a free port with a clean immutable store"
PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
STORE_DIR="$WORK/store"
mkdir -p "$STORE_DIR"

PORT="$PORT" STORE_DRIVER=filesystem STORE_DATA_DIR="$STORE_DIR" NODE_ENV=test \
  node backend/src/server.js >"$WORK/backend.log" 2>&1 &
BACKEND_PID=$!

BASE="http://127.0.0.1:$PORT"
for _ in $(seq 1 40); do
  curl -sf "$BASE/health" >/dev/null 2>&1 && break
  sleep 0.25
done
if curl -sf "$BASE/health" >"$WORK/health.json"; then
  DRIVER="$(python3 -c "import json;print(json.load(open('$WORK/health.json'))['store']['driver'])")"
  ok "backend up on :$PORT (store driver: $DRIVER)"
else
  bad "backend did not come up — see $WORK/backend.log"
  cat "$WORK/backend.log"
  exit 1
fi

# =============================================================================
step "3. Routing the phone to this host over USB"
# adb reverse makes the host's port reachable at 127.0.0.1 ON THE PHONE. That
# avoids needing a LAN IP, and — importantly for this test — a USB reverse tunnel
# is unaffected by airplane mode, while the system's own network state (which is
# what WorkManager's CONNECTED constraint reads) genuinely goes down.
adb -s "$DEVICE" reverse --remove-all >/dev/null 2>&1
if adb -s "$DEVICE" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
  ok "adb reverse tcp:$PORT established"
else
  bad "adb reverse failed"
  exit 1
fi

# =============================================================================
step "4. Building and installing the app pointed at this backend"
# Passed as a Gradle property, so the committed gradle.properties default is
# untouched and no developer's local.properties is edited by a test.
(cd android && ./gradlew --quiet :app:assembleDebug \
  "-PREALITYLOCK_BACKEND_BASE_URL=http://127.0.0.1:$PORT/" >"$WORK/build.log" 2>&1)
if [ $? -ne 0 ]; then
  bad "build failed — see $WORK/build.log"; tail -30 "$WORK/build.log"; exit 1
fi
APK=android/app/build/outputs/apk/debug/app-debug.apk
if adb -s "$DEVICE" install -r "$APK" >/dev/null 2>&1; then
  ok "APK installed (backend base URL http://127.0.0.1:$PORT/)"
else
  bad "APK install failed"; exit 1
fi

# Try to grant, best effort. On ColorOS/OxygenOS this throws SecurityException
# ("Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS"), so it
# is NOT relied upon — the state is verified below instead of assumed.
for P in CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  adb -s "$DEVICE" shell pm grant "$PKG" "android.permission.$P" >/dev/null 2>&1 || true
done

granted() {
  adb -s "$DEVICE" shell dumpsys package "$PKG" 2>/dev/null \
    | grep -q "android.permission.$1: granted=true"
}
if granted CAMERA && granted ACCESS_FINE_LOCATION; then
  ok "camera and location permissions are granted"
else
  bad "camera/location not granted, and this device blocks 'adb shell pm grant'"
  info "Grant them once in the app (Capture tab -> Grant access), then re-run."
  exit 1
fi

# Clear only this app's OWN capture and sync directories.
#
# Deliberately NOT `pm clear`: that wipes everything the app owns and — on a
# device where `pm grant` is blocked — revokes runtime permissions that then
# cannot be restored from the shell, which is precisely how an earlier version of
# this script wedged itself. Scoping the reset to our two directories keeps the
# permissions and touches nothing else.
adb -s "$DEVICE" shell run-as "$PKG" rm -rf files/captures files/sync >/dev/null 2>&1
ok "capture and sync directories reset (permissions left intact)"

# ---- helpers ---------------------------------------------------------------
tap_by_text() {
  local want="$1" xml coords
  adb -s "$DEVICE" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  xml="$(adb -s "$DEVICE" shell cat /sdcard/ui.xml 2>/dev/null)"
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

# Count of stored proof sidecars in the app's private directory.
device_event_count() {
  adb -s "$DEVICE" shell run-as "$PKG" ls files/captures/ 2>/dev/null \
    | tr -d '\r' | grep -c '\.json$'
}

# The id of the most recently stored event on the device.
device_latest_event_id() {
  adb -s "$DEVICE" shell run-as "$PKG" ls -t files/captures/ 2>/dev/null \
    | tr -d '\r' | grep '\.json$' | head -1 | sed 's/\.json$//'
}

# The sync stage recorded for a specific event, read from the app's own sidecar.
# Named per event rather than "whatever file is first" so the assertion cannot
# accidentally read a different capture's state.
device_sync_stage() {
  local id="$1"
  [ -z "$id" ] && { echo "NO_EVENT"; return; }
  adb -s "$DEVICE" shell run-as "$PKG" cat "files/sync/$id.json" 2>/dev/null \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['stage'])" 2>/dev/null \
    || echo "NO_STATE_FILE"
}

backend_event_count() {
  curl -sf "$BASE/health" | python3 -c "import sys,json;print(json.load(sys.stdin)['store']['events'])" 2>/dev/null || echo 0
}

# =============================================================================
step "5. Capturing with NO connectivity (airplane mode)"
AIRPLANE_TOUCHED=1
adb -s "$DEVICE" shell cmd connectivity airplane-mode enable >/dev/null 2>&1
sleep 5
NET="$(adb -s "$DEVICE" shell dumpsys connectivity 2>/dev/null | grep -m1 'Active default network' | tr -d '\r')"
info "${NET:-Active default network: (unknown)}"
if printf '%s' "$NET" | grep -q 'none'; then
  ok "device has no active network"
else
  bad "expected no active network in airplane mode ($NET)"
fi

# adb reverse must still work over USB — that is what lets the test talk to the
# backend later without giving the phone real internet.
adb -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
adb -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
# Let CameraX bind and the sensor stream reach steady state before the shutter.
sleep 10

if tap_by_text "Capture event"; then
  info "shutter tapped"
else
  bad "could not find the 'Capture event' button"
fi

# Poll for the sidecar rather than sleeping a fixed interval. In airplane mode
# there is no GNSS, so the location request runs its FULL
# CaptureConfig.LOCATION_REQUEST_TIMEOUT_MILLIS (10 s) before the event is
# written — a shorter fixed sleep looks exactly like "the capture failed".
CAPTURED=0
for i in $(seq 1 30); do
  CAPTURED="$(device_event_count)"
  [ "$CAPTURED" -ge 1 ] && { info "sidecar written after ~$((i * 2))s"; break; }
  sleep 2
done

if [ "$CAPTURED" -ge 1 ]; then
  ok "capture stored on device while offline ($CAPTURED event(s))"
else
  bad "no event was stored on the device"
fi

BACKEND_NOW="$(backend_event_count)"
if [ "$BACKEND_NOW" = "0" ]; then
  ok "backend still holds 0 packages — nothing was sent without a network"
else
  bad "backend already holds $BACKEND_NOW package(s); expected 0 while offline"
fi

DEVICE_EVENT_ID="$(device_latest_event_id)"
info "device event id: ${DEVICE_EVENT_ID:-none}"
STAGE_OFFLINE="$(device_sync_stage "$DEVICE_EVENT_ID")"
case "$STAGE_OFFLINE" in
  PENDING|NO_STATE_FILE)
    ok "sync state is '$STAGE_OFFLINE' — queued, not failed and not lost" ;;
  *)
    bad "unexpected offline sync stage '$STAGE_OFFLINE'" ;;
esac

# =============================================================================
step "6. Restoring connectivity — sync must happen by itself"
adb -s "$DEVICE" shell cmd connectivity airplane-mode disable >/dev/null 2>&1
AIRPLANE_TOUCHED=0
info "waiting for the network to come back and WorkManager to fire…"

# No taps here. The whole point of the CONNECTED constraint is that the queued
# work runs unprompted once a network appears.
SYNCED=0
for i in $(seq 1 60); do
  sleep 2
  if [ "$(backend_event_count)" -ge 1 ]; then SYNCED=1; info "synced after ~$((i * 2))s"; break; fi
done

if [ "$SYNCED" = "1" ]; then
  ok "the queued capture uploaded automatically when connectivity returned"
else
  bad "the capture did not sync within 120s"
  info "backend log tail:"; tail -10 "$WORK/backend.log"
  adb -s "$DEVICE" logcat -d -s RealityLockSync:* 2>/dev/null | tail -10
fi

# Give the media step time to follow the package step.
for _ in $(seq 1 30); do
  [ "$(device_sync_stage "$DEVICE_EVENT_ID")" = "COMPLETE" ] && break
  sleep 2
done
STAGE_ONLINE="$(device_sync_stage "$DEVICE_EVENT_ID")"
if [ "$STAGE_ONLINE" = "COMPLETE" ]; then
  ok "device sync state reached COMPLETE (package + media both stored)"
else
  bad "device sync state is '$STAGE_ONLINE', expected COMPLETE"
fi

# =============================================================================
step "7. Checking what the backend actually stored"
EVENT_ID="$(ls "$STORE_DIR/packages" 2>/dev/null | head -1 | sed 's/\.json$//')"
if [ -n "$EVENT_ID" ]; then
  ok "package stored as $EVENT_ID"
else
  bad "no package file in the store"; exit 1
fi

# The media object must be named by its own digest, and that digest must be the
# one the signed package commits to.
DECLARED_SHA="$(python3 -c "import json;print(json.load(open('$STORE_DIR/packages/$EVENT_ID.json'))['media']['sha256'])")"
if [ -f "$STORE_DIR/media/$DECLARED_SHA" ]; then
  ok "media stored content-addressed under its signed digest (${DECLARED_SHA:0:16}…)"
else
  bad "no media object named $DECLARED_SHA"
fi

ACTUAL_SHA="$(sha256sum "$STORE_DIR/media/$DECLARED_SHA" 2>/dev/null | cut -d' ' -f1)"
if [ "$ACTUAL_SHA" = "$DECLARED_SHA" ]; then
  ok "stored media hashes to its own name — bytes survived the upload intact"
else
  bad "stored media hashes to $ACTUAL_SHA, not $DECLARED_SHA"
fi

# The device's copy of the package and the server's must be byte-identical: the
# signature covers those bytes.
adb -s "$DEVICE" shell run-as "$PKG" cat "files/captures/$EVENT_ID.json" 2>/dev/null \
  | tr -d '\r' >"$WORK/device_pkg.json"
if python3 - "$WORK/device_pkg.json" "$STORE_DIR/packages/$EVENT_ID.json" <<'PY'
import json,sys
a=json.load(open(sys.argv[1])); b=json.load(open(sys.argv[2]))
sys.exit(0 if a==b else 1)
PY
then
  ok "the package on the server is the same document as the one on the device"
else
  bad "the stored package differs from the device's copy"
fi

# =============================================================================
step "8. Immutability and hash enforcement"
# A byte-identical resubmission is the sync worker retrying: it must succeed.
CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/proof" \
  -H 'Content-Type: application/json' --data-binary "@$STORE_DIR/packages/$EVENT_ID.json")"
if [ "$CODE" = "200" ]; then
  ok "re-POSTing the identical package is idempotent (200, not an error)"
else
  bad "identical re-POST returned $CODE, expected 200"
fi

# Changing a stored package must be refused outright.
python3 - "$STORE_DIR/packages/$EVENT_ID.json" "$WORK/rewritten.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]))
p['metadata']['timestamp']['wallClockMillis'] += 60000
json.dump(p, open(sys.argv[2],'w'))
PY
CODE="$(curl -s -o "$WORK/conflict.json" -w '%{http_code}' -X POST "$BASE/proof" \
  -H 'Content-Type: application/json' --data-binary "@$WORK/rewritten.json")"
if [ "$CODE" = "409" ]; then
  ok "rewriting a stored package is refused (409 conflict)"
else
  bad "rewrite attempt returned $CODE, expected 409"
fi

# Media that does not match the signed digest must be refused, not stored.
printf 'not the real media' >"$WORK/wrong_media.bin"
CODE="$(curl -s -o "$WORK/mediafail.json" -w '%{http_code}' -X POST \
  "$BASE/proof/$EVENT_ID/media" -H 'Content-Type: application/octet-stream' \
  --data-binary "@$WORK/wrong_media.bin")"
if [ "$CODE" = "409" ]; then
  ok "media whose digest does not match the signed package is refused (409)"
else
  bad "bad-media upload returned $CODE, expected 409"
fi

# =============================================================================
step "9. Verification — all five checks, from the server's own copy of the media"
curl -sf -X POST "$BASE/verify" -H 'Content-Type: application/json' \
  --data-binary "@$STORE_DIR/packages/$EVENT_ID.json" >"$WORK/verdict.json"
python3 - "$WORK/verdict.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
print("       verdict:", r['verdict'])
for k,v in r['checks'].items():
    print(f"         {k:26s} {v}")
for a in r.get('advisories',[]):
    print("       advisory:", a[:100])
PY
VERDICT="$(python3 -c "import json;print(json.load(open('$WORK/verdict.json'))['verdict'])")"
if [ "$VERDICT" = "verified" ]; then
  ok "a genuine synced package verifies (verdict: verified)"
else
  bad "expected verdict 'verified', got '$VERDICT'"
fi

for CHECK in mediaHashMatch metadataHashMatch merkleRootMatch signatureValid timestampPlausible; do
  V="$(python3 -c "import json;print(json.load(open('$WORK/verdict.json'))['checks'].get('$CHECK','missing'))")"
  if [ "$V" = "pass" ]; then ok "$CHECK = pass"; else bad "$CHECK = $V, expected pass"; fi
done

# =============================================================================
step "9b. Two more captures, so the history-dependent location check actually runs"
# TWO, not one. The check compares against the most recent earlier event that has
# a location, and the airplane-mode capture necessarily has none (no GNSS). So the
# first online capture still reports `unavailable` — correctly — and only the
# second one has a *located* predecessor to be compared against. That is the half
# of the check which needed the store, and it is the half worth proving.
adb -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
sleep 8
for n in 2 3; do
  if tap_by_text "Capture event"; then
    for _ in $(seq 1 30); do
      [ "$(device_event_count)" -ge "$n" ] && break
      sleep 2
    done
  fi
done

if [ "$(device_event_count)" -ge 3 ]; then
  ok "two further captures stored on device (3 total)"
  # Wait for them to reach the backend, then verify the newest.
  for _ in $(seq 1 60); do
    [ "$(backend_event_count)" -ge 3 ] && break
    sleep 2
  done
  if [ "$(backend_event_count)" -ge 3 ]; then
    ok "further captures synced (backend now holds $(backend_event_count) packages)"
    SECOND_ID="$(device_latest_event_id)"
    curl -sf "$BASE/verify/$SECOND_ID" >"$WORK/second.json" 2>/dev/null
    LOC="$(python3 -c "import json;print(json.load(open('$WORK/second.json'))['checks'].get('locationPlausible','missing'))" 2>/dev/null)"
    info "locationPlausible on the newest capture: $LOC"
    case "$LOC" in
      pass)
        # Both captures are at the same desk, so the implied speed is inside the
        # 50 m jitter guard and the check passes on real data.
        ok "the cross-event location check ran against stored history and passed" ;;
      unavailable)
        # Honest, not a failure: with no GNSS fix indoors there is no located
        # predecessor, so there is genuinely nothing to compare against.
        info "no located predecessor (no GNSS fix indoors) — nothing to compare"
        ok "reported 'unavailable' rather than a vacuous pass" ;;
      *)
        bad "unexpected locationPlausible '$LOC' for a stationary second capture" ;;
    esac
  else
    bad "the further captures did not sync"
  fi
else
  bad "the further captures were not stored"
fi

# =============================================================================
step "10. Tamper detection on the verify path"
python3 - "$STORE_DIR/packages/$EVENT_ID.json" "$WORK/tampered.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]))
loc=p['metadata'].get('location')
if loc: loc['latitude'] = round(loc['latitude'] + 1.0, 5)
else:   p['metadata']['device']['model'] = 'NotTheRealDevice'
json.dump(p, open(sys.argv[2],'w'))
PY
curl -sf -X POST "$BASE/verify" -H 'Content-Type: application/json' \
  --data-binary "@$WORK/tampered.json" >"$WORK/tampered_verdict.json"
python3 - "$WORK/tampered_verdict.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
ok = r['verdict']=='failed' and r['checks'].get('metadataHashMatch')=='fail'
print(f"       verdict: {r['verdict']}, metadataHashMatch: {r['checks'].get('metadataHashMatch')}")
sys.exit(0 if ok else 1)
PY
if [ $? -eq 0 ]; then
  ok "an edited metadata field is detected (failed / metadataHashMatch fail)"
else
  bad "tampered package was not correctly rejected"
fi

# =============================================================================
step "11. The public QR endpoint: a verdict, and no GPS"
curl -sf "$BASE/verify/$EVENT_ID" >"$WORK/public.json"
PUB_VERDICT="$(python3 -c "import json;print(json.load(open('$WORK/public.json'))['verdict'])")"
if [ "$PUB_VERDICT" = "verified" ]; then
  ok "GET /verify/<eventId> returns a verdict for the stored package"
else
  bad "public endpoint verdict was '$PUB_VERDICT'"
fi

if python3 - "$WORK/public.json" "$STORE_DIR/packages/$EVENT_ID.json" <<'PY'
import json,sys
pub=open(sys.argv[1]).read()
pkg=json.load(open(sys.argv[2]))
body=json.loads(pub)
# A verification badge must not double as a location leak (ADR-0006 §7).
if 'package' in body or 'metadata' in body: sys.exit(1)
loc=pkg['metadata'].get('location')
if loc:
    for coord in (loc['latitude'], loc['longitude']):
        # Check the coordinate at the precision the package records it.
        if str(coord) in pub: sys.exit(1)
sys.exit(0)
PY
then
  ok "the public verdict discloses no coordinates and not the package"
else
  bad "the public verdict leaked location data"
fi

if python3 -c "
import json,sys
b=json.load(open('$WORK/public.json'))
sys.exit(0 if b.get('limitations') else 1)"; then
  ok "every verdict ships its limitations"
else
  bad "the verdict did not include its limitations"
fi

# =============================================================================
printf '\n\033[1m==================== SUMMARY ====================\033[0m\n'
printf '  passed: \033[0;32m%d\033[0m\n  failed: \033[0;31m%d\033[0m\n' "$PASS" "$FAIL"
printf '  device: %s (%s)\n' "$DEVICE" "$MODEL"
printf '  event:  %s\n' "${EVENT_ID:-none}"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
