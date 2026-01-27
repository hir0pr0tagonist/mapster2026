#!/usr/bin/env bash
set -euo pipefail

# Simple performance smoke test for Mapster endpoints.
#
# Defaults are tuned for local dev containers; override via env vars.
#
# Usage:
#   BASE_URL=http://localhost:8080 RUNS=5 ./scripts/perf_smoke.sh
#
# Thresholds are in seconds.

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUNS="${RUNS:-3}"

MAX_TTFB_TILE_Z6="${MAX_TTFB_TILE_Z6:-1.5}"
MAX_TTFB_TILE_Z7="${MAX_TTFB_TILE_Z7:-1.0}"
MAX_TTFB_METRICS="${MAX_TTFB_METRICS:-0.2}"

TILE_6_URL="${TILE_6_URL:-$BASE_URL/api/tiles/6/33/20.mvt}"
TILE_7_URL="${TILE_7_URL:-$BASE_URL/api/tiles/7/67/40.mvt}"
METRICS_URL="${METRICS_URL:-$BASE_URL/api/area-metrics-values?minLon=13.38&minLat=52.51&maxLon=13.4&maxLat=52.52&depth=5&metricId=price_eur_per_m2_land&from=2026-01-01&to=2026-01-22}"

median() {
  # Prints median (p50) of numeric args.
  # For even counts, returns the lower-middle value (good enough for smoke tests).
  local sorted
  sorted=$(printf '%s\n' "$@" | sort -n)
  local count
  count=$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')
  local idx=$(( (count + 1) / 2 ))
  printf '%s\n' "$sorted" | sed -n "${idx}p"
}

is_gt() {
  # Returns success if $1 > $2.
  awk -v a="$1" -v b="$2" 'BEGIN { exit !(a > b) }'
}

measure_ttfb_p50() {
  local label="$1"
  local url="$2"
  local runs="$3"

  local -a ttfbs=()

  echo "-- $label"
  for i in $(seq 1 "$runs"); do
    # shellcheck disable=SC2086
    local out
    out=$(curl -sS -o /dev/null -w '%{http_code} %{size_download} %{time_starttransfer} %{time_total}' "$url")
    local code bytes ttfb total
    read -r code bytes ttfb total <<<"$out"

    echo "   run#$i code=$code bytes=$bytes ttfb=${ttfb}s total=${total}s"
    if [[ "$code" != "200" && "$code" != "304" ]]; then
      echo "   FAIL: unexpected http_code=$code for $label ($url)" >&2
      return 2
    fi

    ttfbs+=("$ttfb")
  done

  local p50
  p50=$(median "${ttfbs[@]}")
  echo "   p50_ttfb=${p50}s"
  printf '%s\n' "$p50"
}

echo "Perf smoke @ $(date -Is)"
echo "BASE_URL=$BASE_URL"
echo "RUNS=$RUNS"

failures=0

z6_p50=$(measure_ttfb_p50 "tile z=6" "$TILE_6_URL" "$RUNS") || failures=$((failures + 1))
if is_gt "$z6_p50" "$MAX_TTFB_TILE_Z6"; then
  echo "FAIL: tile z=6 p50_ttfb=$z6_p50 exceeds MAX_TTFB_TILE_Z6=$MAX_TTFB_TILE_Z6" >&2
  failures=$((failures + 1))
fi

z7_p50=$(measure_ttfb_p50 "tile z=7" "$TILE_7_URL" "$RUNS") || failures=$((failures + 1))
if is_gt "$z7_p50" "$MAX_TTFB_TILE_Z7"; then
  echo "FAIL: tile z=7 p50_ttfb=$z7_p50 exceeds MAX_TTFB_TILE_Z7=$MAX_TTFB_TILE_Z7" >&2
  failures=$((failures + 1))
fi

metrics_p50=$(measure_ttfb_p50 "metrics" "$METRICS_URL" "$RUNS") || failures=$((failures + 1))
if is_gt "$metrics_p50" "$MAX_TTFB_METRICS"; then
  echo "FAIL: metrics p50_ttfb=$metrics_p50 exceeds MAX_TTFB_METRICS=$MAX_TTFB_METRICS" >&2
  failures=$((failures + 1))
fi

if [[ "$failures" -ne 0 ]]; then
  echo "Perf smoke: FAIL (failures=$failures)" >&2
  exit 1
fi

echo "Perf smoke: OK"
