#!/bin/bash
# Import GeoPackage into PostGIS from within the data import container

set -euo pipefail

# Allow overriding via environment (useful for Kubernetes).
GPKG_PATH="${GPKG_PATH:-/data/planet.gpkg}"  # Path inside the container
PGUSER="${PGUSER:-mapster}"
PGPASSWORD="${PGPASSWORD:-mapsterpass}"
PGDATABASE="${PGDATABASE:-mapster}"
PGHOST="${PGHOST:-postgis}"
PGPORT="${PGPORT:-5432}"

export PGPASSWORD

# Wait for PostGIS to be ready
until pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE"; do
  echo "Waiting for PostGIS to be ready..."
  sleep 2
done

if [[ ! -f "$GPKG_PATH" ]]; then
  echo "ERROR: GeoPackage not found at: $GPKG_PATH" >&2
  exit 1
fi

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

echo "Creating geo helper schema/view and rebuilding ancestor closure table..."

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 <<'SQL'
CREATE SCHEMA IF NOT EXISTS geo;

-- Stable key helpers (kept here so the import job can work even before the API runs migrations).
CREATE OR REPLACE FUNCTION geo.area_key(
    gid_0 text,
    gid_1 text,
    gid_2 text,
    gid_3 text,
    gid_4 text,
    gid_5 text
) RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT COALESCE(gid_0, '') || '|' ||
         COALESCE(gid_1, '') || '|' ||
         COALESCE(gid_2, '') || '|' ||
         COALESCE(gid_3, '') || '|' ||
         COALESCE(gid_4, '') || '|' ||
         COALESCE(gid_5, '');
$$;

CREATE OR REPLACE FUNCTION geo.area_depth(
    gid_1 text,
    gid_2 text,
    gid_3 text,
    gid_4 text,
    gid_5 text
) RETURNS smallint
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE
    WHEN gid_5 IS NOT NULL THEN 5
    WHEN gid_4 IS NOT NULL THEN 4
    WHEN gid_3 IS NOT NULL THEN 3
    WHEN gid_2 IS NOT NULL THEN 2
    WHEN gid_1 IS NOT NULL THEN 1
    ELSE 0
  END::smallint;
$$;

-- Convenience view: admin_areas + computed key/depth.
DROP VIEW IF EXISTS geo.admin_areas;
CREATE VIEW geo.admin_areas AS
SELECT
  a.*,
  geo.area_key(a.gid_0, a.gid_1, a.gid_2, a.gid_3, a.gid_4, a.gid_5) AS area_key,
  geo.area_depth(a.gid_1, a.gid_2, a.gid_3, a.gid_4, a.gid_5) AS depth
FROM public.admin_areas a;

-- Closure table used for "aggregate up". Rebuilt each import so it stays consistent.
CREATE TABLE IF NOT EXISTS geo.admin_area_ancestors (
  area_key text NOT NULL,
  area_depth smallint NOT NULL,
  ancestor_key text NOT NULL,
  ancestor_depth smallint NOT NULL,
  distance smallint NOT NULL,
  PRIMARY KEY (area_key, ancestor_key)
);

TRUNCATE geo.admin_area_ancestors;

WITH areas AS (
  SELECT DISTINCT
    geo.area_key(gid_0, gid_1, gid_2, gid_3, gid_4, gid_5) AS area_key,
    geo.area_depth(gid_1, gid_2, gid_3, gid_4, gid_5) AS area_depth,
    gid_0, gid_1, gid_2, gid_3, gid_4, gid_5
  FROM public.admin_areas
), anc AS (
  SELECT
    a.area_key,
    a.area_depth,
    gs AS ancestor_depth,
    geo.area_key(
      a.gid_0,
      CASE WHEN gs >= 1 THEN a.gid_1 ELSE NULL END,
      CASE WHEN gs >= 2 THEN a.gid_2 ELSE NULL END,
      CASE WHEN gs >= 3 THEN a.gid_3 ELSE NULL END,
      CASE WHEN gs >= 4 THEN a.gid_4 ELSE NULL END,
      CASE WHEN gs >= 5 THEN a.gid_5 ELSE NULL END
    ) AS ancestor_key,
    (a.area_depth - gs)::smallint AS distance
  FROM areas a
  JOIN LATERAL generate_series(0, a.area_depth) gs ON true
)
INSERT INTO geo.admin_area_ancestors (area_key, area_depth, ancestor_key, ancestor_depth, distance)
SELECT DISTINCT area_key, area_depth, ancestor_key, ancestor_depth, distance
FROM anc;

CREATE INDEX IF NOT EXISTS admin_area_ancestors_ancestor_key_idx
  ON geo.admin_area_ancestors (ancestor_key);
SQL

echo "Import complete."
