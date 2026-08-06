#!/usr/bin/env bash
# Verifies, against a DEPLOYED backend, the two claims that `TRUST_PROXY_HOPS`
# exists to make true:
#
#   1. a caller cannot rotate the rate limiter's key by forging X-Forwarded-For;
#   2. the limiter therefore keys on an address a real proxy appended.
#
# Why this script exists rather than a unit test: `trust proxy` is only as
# correct as the number of proxies actually in front of the app, and that is a
# property of the deployment, not of the code. A test can prove Express reads
# from the right; only a live probe can prove the hop count matches reality.
#
# Method — no diagnostic endpoint, no code change. The draft-7 `RateLimit`
# response header already exposes the remaining budget for whatever key the
# limiter chose. So:
#
#   - send N requests, every one carrying a DIFFERENT forged X-Forwarded-For;
#   - if the key were caller-controlled, each would open a fresh bucket and
#     `remaining` would sit at its maximum every time;
#   - if the key is the real client address, the budget keeps draining despite
#     the forgeries.
#
# Reading the output: `remaining` is NOT expected to decrease monotonically. A
# deployment answering from several processes keeps a separate in-memory counter
# per process, so the observations interleave. The script separates them (each
# counter decrements by exactly 1 per request it serves) and reports how many it
# found — which is itself the diagnostic for whether a shared store is needed.
#
# Usage:  scripts/ops/verify_trust_proxy.sh [base-url] [requests]
set -uo pipefail

BASE_URL="${1:-https://civicmesh.onrender.com}"
REQUESTS="${2:-20}"
ENDPOINT="$BASE_URL/health"

command -v curl >/dev/null || { echo "FATAL: curl not found" >&2; exit 1; }
command -v python3 >/dev/null || { echo "FATAL: python3 not found" >&2; exit 1; }

echo "Probing $ENDPOINT with $REQUESTS forged X-Forwarded-For values"
echo

# A free-tier service spins down after idling; the first request pays the
# cold start. Doing it here keeps that latency out of the measured run.
echo "  waking service (cold start can take ~1 min on a free instance)..."
curl -s -o /dev/null --max-time 120 "$ENDPOINT" || {
  echo "FATAL: could not reach $ENDPOINT" >&2; exit 1; }

# The header is `RateLimit: limit=..., remaining=..., reset=...` (draft-7).
# A deployment with the limiter disabled emits nothing, which must not be
# silently read as a pass.
remaining_for() {
  curl -s -D - -o /dev/null --max-time 20 -H "X-Forwarded-For: $1" "$ENDPOINT" 2>/dev/null \
    | grep -i '^ratelimit:' \
    | sed 's/.*remaining=\([0-9]*\).*/\1/' \
    | tr -d '\r'
}

VALUES=""
for i in $(seq 1 "$REQUESTS"); do
  # Documentation-reserved ranges (RFC 5737 / RFC 1918), so a probe can never
  # name a real host.
  v="$(remaining_for "172.16.$(( (i / 250) + 1 )).$(( (i % 250) + 1 ))")"
  [ -z "$v" ] && { echo "FATAL: no RateLimit header — is the limiter deployed?" >&2; exit 1; }
  VALUES="$VALUES $v"
  printf '  %2d/%d  remaining=%s\n' "$i" "$REQUESTS" "$v"
done

echo
VALUES="$VALUES" python3 <<'PY'
import os, sys

vals = [int(x) for x in os.environ["VALUES"].split()]

# Separate the interleaved per-process counters: a counter only ever hands out
# values decreasing by exactly 1 for consecutive requests it serves.
series = []
for v in vals:
    for s in series:
        if s[-1] - 1 == v:
            s.append(v)
            break
    else:
        series.append([v])

accounted = sum(len(s) for s in series)
print(f"observations : {len(vals)}")
print(f"independent counters detected : {len(series)} "
      f"({'clean split' if accounted == len(vals) else 'PARTIAL — see below'})")
for i, s in enumerate(series, 1):
    print(f"  counter {i}: {s[0]} -> {s[-1]}  ({len(s)} requests)")

drained = max(len(s) for s in series) - 1
print()
if drained >= 2:
    print("PASS  forged X-Forwarded-For did NOT open fresh buckets: at least one")
    print(f"      counter drained across {drained + 1} requests carrying {drained + 1} different")
    print("      forged addresses. The limiter is keying on an address the caller")
    print("      cannot set, which is what TRUST_PROXY_HOPS is for.")
else:
    print("FAIL  every forged address appears to have opened its own bucket.")
    print("      TRUST_PROXY_HOPS is too high for this topology — Express is")
    print("      reading an X-Forwarded-For entry the caller controls. Lower it.")
    sys.exit(1)

if len(series) > 1:
    print()
    print(f"NOTE  {len(series)} independent counters means the deployment answers from")
    print(f"      {len(series)} processes, each with its own in-memory limiter store, so the")
    print(f"      effective allowance is about {len(series)}x the configured one. This is a")
    print("      capacity finding, not a spoofing one — the check above still passed.")
    print("      Set REDIS_URL to key the limiter on one shared store.")
PY
