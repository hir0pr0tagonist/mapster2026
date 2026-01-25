#!/usr/bin/env bash
set -euo pipefail

# Seeds lightweight dummy metric rollups into facts_agg.area_metric_daily.
# Fast: writes aggregates directly, no observation generation.
#
# Example:
#   ./postgis/dev/seed_dummy_metrics.sh \
#     --bbox 5.5 47.0 15.5 55.5 \
#     --depth 1 \
#     --metric price_eur_per_m2_land \
#     --days 30

usage() {
  cat <<'EOF'
Usage:
  seed_dummy_metrics.sh --bbox <minLon> <minLat> <maxLon> <maxLat> (--depth <0-5|auto> | --depths <csv|all>) --metric <metricId> [--days N] [--count N] [--base N] [--spread N]

Notes:
- depth should match the UI's zoom->depth mapping (zoom ~7 => depth 1, zoom ~9 => depth 2, etc.)
- use depth=auto to pick the deepest available depth within the bbox
- use --depths 0,1,2 (or --depths all) to seed multiple depths
- days controls how many days of daily rollups to create (default 30)
- count/base/spread control the synthetic distribution
EOF
}

minLon='' minLat='' maxLon='' maxLat=''
depth='' metricId=''
depths=''
days='30' count='30' base='220' spread='160'

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bbox)
      minLon="$2"; minLat="$3"; maxLon="$4"; maxLat="$5"; shift 5 ;;
    --depth)
      depth="$2"; shift 2 ;;
    --depths)
      depths="$2"; shift 2 ;;
    --metric)
      metricId="$2"; shift 2 ;;
    --days)
      days="$2"; shift 2 ;;
    --count)
      count="$2"; shift 2 ;;
    --base)
      base="$2"; shift 2 ;;
    --spread)
      spread="$2"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -n "$depths" && -n "$depth" ]]; then
  echo "Provide either --depth or --depths, not both." >&2
  usage
  exit 2
fi

if [[ -z "$minLon" || -z "$minLat" || -z "$maxLon" || -z "$maxLat" || ( -z "$depth" && -z "$depths" ) || -z "$metricId" ]]; then
  usage
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "$repo_root"

echo "Available depths within bbox (for reference):"
docker compose exec -T postgis psql -U mapster -d mapster -v ON_ERROR_STOP=1 \
  -c "SELECT depth, COUNT(*) AS n FROM geo.admin_areas WHERE geom && ST_MakeEnvelope($minLon,$minLat,$maxLon,$maxLat,4326) GROUP BY depth ORDER BY depth;"

resolve_depths() {
  local requested_depth="$1"
  if [[ "$requested_depth" == "auto" ]]; then
    docker compose exec -T postgis psql -U mapster -d mapster -v ON_ERROR_STOP=1 -At \
      -c "SELECT max(depth) FROM geo.admin_areas WHERE geom && ST_MakeEnvelope($minLon,$minLat,$maxLon,$maxLat,4326) AND ST_Intersects(geom, ST_MakeEnvelope($minLon,$minLat,$maxLon,$maxLat,4326));"
    return
  fi
  echo "$requested_depth"
}

if [[ -n "$depth" ]]; then
  resolved_depths="$(resolve_depths "$depth")"
  if [[ -z "$resolved_depths" ]]; then
    echo "No admin_areas intersect this bbox; nothing to seed." >&2
    exit 3
  fi
elif [[ "$depths" == "all" ]]; then
  resolved_depths="$(docker compose exec -T postgis psql -U mapster -d mapster -v ON_ERROR_STOP=1 -At \
    -c "SELECT depth FROM geo.admin_areas WHERE geom && ST_MakeEnvelope($minLon,$minLat,$maxLon,$maxLat,4326) AND ST_Intersects(geom, ST_MakeEnvelope($minLon,$minLat,$maxLon,$maxLat,4326)) GROUP BY depth ORDER BY depth;")"
  if [[ -z "$resolved_depths" ]]; then
    echo "No admin_areas intersect this bbox; nothing to seed." >&2
    exit 3
  fi
else
  resolved_depths="$(printf '%s' "$depths" | tr ',' '\n')"
fi

echo "Seeding depths: $(echo "$resolved_depths" | tr '\n' ' ' | sed 's/[[:space:]]\+$//')"

mapfile -t resolved_depth_list <<< "$resolved_depths"
for resolved_depth in "${resolved_depth_list[@]}"; do
  [[ -z "$resolved_depth" ]] && continue

  matched="$(docker compose exec -T postgis psql -U mapster -d mapster -v ON_ERROR_STOP=1 -At \
    -c "SELECT count(*) FROM geo.admin_areas WHERE geom && ST_MakeEnvelope($minLon,$minLat,$maxLon,$maxLat,4326) AND ST_Intersects(geom, ST_MakeEnvelope($minLon,$minLat,$maxLon,$maxLat,4326)) AND depth = $resolved_depth;" < /dev/null)"

  if [[ "$matched" == "0" ]]; then
    echo "Skipping depth=$resolved_depth (no intersecting areas)." >&2
    continue
  fi

  echo "Seeding depth=$resolved_depth (areas matched: $matched)"
  docker compose exec -T postgis psql -U mapster -d mapster \
    -v ON_ERROR_STOP=1 \
    -v minLon="$minLon" -v minLat="$minLat" -v maxLon="$maxLon" -v maxLat="$maxLat" \
    -v depth="$resolved_depth" -v metricId="$metricId" -v days="$days" -v count="$count" -v base="$base" -v spread="$spread" \
    -f /dev/stdin < postgis/dev/seed_dummy_metrics.sql
done

echo "Seeded dummy rollups: metric=$metricId days=$days bbox=($minLon,$minLat)->($maxLon,$maxLat)"
