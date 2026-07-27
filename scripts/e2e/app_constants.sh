#!/usr/bin/env bash
# =============================================================================
# Values the e2e scripts share with the app, read from the app's own source.
#
# Why this file exists: both runners used to carry their own copies of the
# shutter button's label and the on-disk directory names. Those copies were
# invisible duplicates of `strings.xml` and the `core/config` objects, and the
# failure they produced was actively misleading — rename the button and the run
# reports "could not find the 'Capture event' button", which reads as a broken
# capture rather than a stale script.
#
# Lookups fail loudly. A constant that has been renamed must stop the run with a
# clear message, never fall back to a stale literal that quietly tests nothing.
#
# Sourced, not executed:  . "$(dirname "$0")/app_constants.sh"
# =============================================================================

_ac_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Reads `const val NAME: Type = "value"` out of a Kotlin config object.
_ac_kotlin_const() {
  local file="$_ac_repo_root/$1" name="$2" value
  value="$(sed -n "s/.*const val ${name}[[:space:]]*:[^=]*=[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$file" | head -1)"
  if [ -z "$value" ]; then
    echo "FATAL: const val $name not found in $1 — was it renamed?" >&2
    exit 1
  fi
  printf '%s' "$value"
}

# Reads a <string name="..."> out of the app's string resources.
_ac_string_resource() {
  local name="$1" file="$_ac_repo_root/android/app/src/main/res/values/strings.xml" value
  value="$(sed -n "s/.*<string name=\"${name}\">\(.*\)<\/string>.*/\1/p" "$file" | head -1)"
  if [ -z "$value" ]; then
    echo "FATAL: string resource '$name' not found in strings.xml — was it renamed?" >&2
    exit 1
  fi
  printf '%s' "$value"
}

_AC_CAPTURE_CONFIG="android/app/src/main/kotlin/com/realitylock/app/core/config/CaptureConfig.kt"
_AC_SYNC_CONFIG="android/app/src/main/kotlin/com/realitylock/app/core/config/SyncConfig.kt"

# The label the runner taps. Mirrors R.string.capture_action.
SHUTTER_LABEL="$(_ac_string_resource capture_action)"
# app-private subdirectories, mirroring CaptureConfig / SyncConfig.
CAPTURES_SUBDIR="$(_ac_kotlin_const "$_AC_CAPTURE_CONFIG" MEDIA_SUBDIR)"
SYNC_SUBDIR="$(_ac_kotlin_const "$_AC_SYNC_CONFIG" SYNC_STATE_SUBDIR)"

export SHUTTER_LABEL CAPTURES_SUBDIR SYNC_SUBDIR
