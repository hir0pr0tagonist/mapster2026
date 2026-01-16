#!/bin/bash
# Import GeoPackage into PostGIS from within the data import container

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

ogr2ogr -f "PostgreSQL" \
  PG:"host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD" \
  "$GPKG_PATH" \
  -nln admin_areas \
  -progress
