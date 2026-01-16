#!/bin/bash
# Import GeoPackage into PostGIS from within the data import container

set -euo pipefail

GPKG_PATH="/data/planet.gpkg"  # Path inside the container
PGUSER="mapster"
PGPASSWORD="mapsterpass"
PGDATABASE="mapster"
PGHOST="postgis"
PGPORT="5432"

export PGPASSWORD=$PGPASSWORD

# Wait for PostGIS to be ready
until pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER"; do
  echo "Waiting for PostGIS to be ready..."
  sleep 2
done

echo "Dropping existing admin_areas table (if any) to avoid duplicate imports..."
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 \
  -c "DROP TABLE IF EXISTS admin_areas;"

# Import layers explicitly, once each.
# Importing a multi-layer GeoPackage in one ogr2ogr invocation while forcing a single output layer name
# can lead to unexpected duplicate inserts. Doing one layer at a time is deterministic and idempotent.

PG_DSN="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"

# Start from the most detailed level so the target table gets the full field set.
LAYERS=(ADM_5 ADM_4 ADM_3 ADM_2 ADM_1 ADM_0)

first=1
for layer in "${LAYERS[@]}"; do
  echo "Importing layer: $layer"
  if [[ "$first" -eq 1 ]]; then
    ogr2ogr -f "PostgreSQL" \
      "$PG_DSN" \
      "$GPKG_PATH" \
      "$layer" \
      -nln admin_areas \
      -overwrite \
      -nlt PROMOTE_TO_MULTI \
      -lco GEOMETRY_NAME=geom \
      -lco FID=id \
      -lco SPATIAL_INDEX=GIST \
      -progress
    first=0
  else
    ogr2ogr -f "PostgreSQL" \
      "$PG_DSN" \
      "$GPKG_PATH" \
      "$layer" \
      -nln admin_areas \
      -update \
      -append \
      -addfields \
      -nlt PROMOTE_TO_MULTI \
      -lco GEOMETRY_NAME=geom \
      -progress
  fi
done
