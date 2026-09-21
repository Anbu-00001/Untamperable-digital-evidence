#!/usr/bin/env bash
# ============================================================================
# Pre-demo readiness check for a live Reality Lock demo on a USB-attached phone.
#
#   ./scripts/demo/demo_prep.sh [BASE_URL] [--resync-missing]
#
# Read-only by default. It checks, in the order they bite during a live demo:
#   1. a device is attached and the app is installed;
#   2. the INSTALLED APK talks to BASE_URL — read out of its dex, not trusted
#      from the build (a plain assembleDebug silently bakes in 127.0.0.1);
#   3. the backend is awake — timing the request, so a cold start is paid here
#      rather than in front of the room;
#   4. camera + location are granted (ColorOS blocks `pm grant`, so a missing
#      grant must be fixed by hand, in Settings, before the demo);
#   5. the app is exempt from battery optimisation (ColorOS kills WorkManager);
#   6. every capture the phone thinks is synced still exists on the server.
#      The Render free tier wipes its store on each deploy, and a wiped event
#      verifies INCOMPLETE ("no media to check") — true, but not the demo.
#
# --resync-missing fixes (6) the honest way: for each event the server no longer
# holds, it moves the phone's local sync record aside, so the app's own sync
# engine treats the event as never sent and uploads the ORIGINAL signed bytes
# again. Nothing is re-signed, edited or fabricated; the server re-verifies them
# exactly as it did the first time. Backups go to files/demo-sync-backup/.
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=../e2e/app_constants.sh
source scripts/e2e/app_constants.sh

BASE_URL="https://civicmesh.onrender.com"
RESYNC=0
for arg in "$@"; do
  case "$arg" in
    --resync-missing) RESYNC=1 ;;
    http*) BASE_URL="${arg%/}" ;;
    *) echo "usage: $0 [BASE_URL] [--resync-missing]" >&2; exit 2 ;;
  esac
done

PKG="$(grep -oP '(?<=^val appPackage = ")[^"]+' android/app/build.gradle.kts)"
PASS=0; WARN=0; FAIL=0
ok()   { printf '  \033[32mOK\033[0m    %s\n' "$*"; PASS=$((PASS + 1)); }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$*"; WARN=$((WARN + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAIL=$((FAIL + 1)); }
as_app() { adb shell run-as "$PKG" "$@"; }

echo "Reality Lock demo readiness — backend $BASE_URL"

# ---- 1. device + install -----------------------------------------------------
if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
  bad "no single authorised device over adb (set ANDROID_SERIAL if several)"; exit 1
fi
ok "device: $(adb shell getprop ro.product.model | tr -d '\r')"
APK_PATH="$(adb shell pm path "$PKG" 2>/dev/null | head -1 | sed 's/^package://' | tr -d '\r')"
if [ -z "$APK_PATH" ]; then bad "$PKG is not installed"; exit 1; fi
ok "app installed ($(adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r'))"

# ---- 2. which backend the installed APK actually calls -------------------------
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
adb pull "$APK_PATH" "$WORK/app.apk" >/dev/null
HOST="${BASE_URL#*://}"
# Extracted to a file first: `unzip | grep -q` lets grep exit on the first match,
# unzip then dies of SIGPIPE, and under pipefail a found match reads as a miss.
unzip -p "$WORK/app.apk" 'classes*.dex' >"$WORK/dex"
if grep -aq "$HOST" "$WORK/dex"; then
  ok "installed APK is built for $HOST"
else
  baked="$(grep -aoE 'https?://[a-z0-9.:-]+/' "$WORK/dex" | grep -vE 'schemas|w3.org|google|android|adobe|goo.gle' | sort -u | head -3 | tr '\n' ' ' || true)"
  bad "installed APK does NOT reference $HOST (found: ${baked:-none}). Rebuild with -PREALITYLOCK_BACKEND_BASE_URL=$BASE_URL/"
fi

# ---- 3. wake the backend ----------------------------------------------------
t="$(curl -s -o "$WORK/health.json" -w '%{time_total}' -m 120 "$BASE_URL/health" || echo fail)"
if [ "$t" = "fail" ]; then
  bad "backend unreachable at $BASE_URL/health"
else
  events="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["store"]["events"])' "$WORK/health.json" 2>/dev/null || echo '?')"
  ok "backend awake (first response ${t}s; store holds $events events)"
fi

# ---- 4. permissions -----------------------------------------------------------
for perm in android.permission.CAMERA android.permission.ACCESS_FINE_LOCATION; do
  if adb shell dumpsys package "$PKG" | grep -q "$perm: granted=true"; then
    ok "${perm##*.} granted"
  else
    bad "${perm##*.} NOT granted — grant it in Settings > Apps > Reality Lock (adb cannot on ColorOS)"
  fi
done

# ---- 5. battery optimisation ------------------------------------------------
if adb shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG"; then
  ok "exempt from battery optimisation"
else
  warn "not exempt from battery optimisation — Settings > Battery > Reality Lock > No restrictions, or a background sync may be killed"
fi

# ---- 6. does the server still hold what the phone thinks it synced? -----------
missing=()
for f in $(as_app ls "files/$SYNC_SUBDIR" 2>/dev/null | tr -d '\r' | grep '\.json$' || true); do
  id="${f%.json}"
  stage="$(as_app cat "files/$SYNC_SUBDIR/$f" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("stage",""))')"
  # PENDING is already queued. Anything further along — COMPLETE, PACKAGE_STORED,
  # or FAILED with the server's own "no stored package" — is checked, because a
  # store reset strands each of them the same way.
  [ "$stage" = "PENDING" ] && continue
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 60 "$BASE_URL/verify/$id?format=json")"
  if [ "$code" = "200" ]; then ok "event ${id:0:8}… present on server"; else missing+=("$id"); fi
done
if [ "${#missing[@]}" -gt 0 ]; then
  if [ "$RESYNC" = 1 ]; then
    as_app mkdir -p files/demo-sync-backup
    for id in "${missing[@]}"; do
      as_app mv "files/$SYNC_SUBDIR/$id.json" "files/demo-sync-backup/$id.json"
    done
    ok "${#missing[@]} event(s) the server lost are queued to re-upload — open the app and tap Sync now"
  else
    warn "${#missing[@]} event(s) the phone sent are GONE from the server (store reset by a deploy); they will verify INCOMPLETE. Re-run with --resync-missing"
  fi
fi

echo
echo "Summary: $PASS ok, $WARN warnings, $FAIL failures."
echo "Remember: no git push to main before the demo (a redeploy wipes the store)."
[ "$FAIL" -eq 0 ]
