#!/bin/bash
# Test connection to PostGIS and verify test data

set -e

PGUSER=mapster
PGPASSWORD=mapsterpass
PGDATABASE=mapster
PGHOST=localhost
PGPORT=5432

# Wait for PostGIS to be ready
until pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER"; do
  echo "Waiting for PostGIS to be ready..."
  sleep 2
done

echo "PostGIS is ready. Running test query..."

# Run a test query to check for the Berlin polygon
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -c "SELECT id, name, ST_AsText(geom) FROM admin_areas;"
