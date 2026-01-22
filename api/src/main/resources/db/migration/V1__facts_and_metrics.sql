-- Facts + metrics storage for Mapster
-- Raw → Canonical → Aggregates

-- Keep geodata import separate (import job builds public.admin_areas).
-- These schemas/tables are safe to apply to an existing database.

CREATE SCHEMA IF NOT EXISTS geo;
CREATE SCHEMA IF NOT EXISTS facts_raw;
CREATE SCHEMA IF NOT EXISTS facts;
CREATE SCHEMA IF NOT EXISTS facts_agg;

-- Stable key for admin areas based on the gid path (survives table reloads where surrogate ids change).
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

-- Closure table: for each area_key, store all ancestors (including self) with a hop distance.
-- Populated by the import job after loading admin_areas.
CREATE TABLE IF NOT EXISTS geo.admin_area_ancestors (
    area_key text NOT NULL,
    area_depth smallint NOT NULL,
    ancestor_key text NOT NULL,
    ancestor_depth smallint NOT NULL,
    distance smallint NOT NULL,
    PRIMARY KEY (area_key, ancestor_key)
);

CREATE INDEX IF NOT EXISTS admin_area_ancestors_ancestor_key_idx
    ON geo.admin_area_ancestors (ancestor_key);

-- Metric definitions (so we can add new "one-dimensional" metrics without schema refactors).
CREATE TABLE IF NOT EXISTS facts.metric (
    metric_id text PRIMARY KEY,
    unit text NULL,
    description text NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Raw ingest landing zone (append-only).
CREATE TABLE IF NOT EXISTS facts_raw.raw_record (
    id bigserial PRIMARY KEY,
    source_system text NOT NULL,
    source_record_id text NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    payload jsonb NOT NULL,
    payload_schema_version int NOT NULL DEFAULT 1,
    status text NOT NULL DEFAULT 'new',
    error text NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS raw_record_source_dedupe_idx
    ON facts_raw.raw_record (source_system, source_record_id)
    WHERE source_record_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS raw_record_ingested_at_idx
    ON facts_raw.raw_record (ingested_at);

-- Canonical observations (append-only).
-- Note: we intentionally do NOT FK to public.admin_areas.id because the import job recreates that table.
CREATE TABLE IF NOT EXISTS facts.observation (
    id bigserial PRIMARY KEY,
    raw_record_id bigint NULL REFERENCES facts_raw.raw_record(id),

    metric_id text NOT NULL REFERENCES facts.metric(metric_id),
    value numeric NOT NULL,
    unit text NULL,
    currency text NULL,

    observed_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),

    -- best known location (may be null if the source is area-scoped)
    point_geom geometry(Point, 4326) NULL,
    geocode_accuracy text NULL,
    uncertainty_radius_m integer NULL,

    -- assignment (Option 1: only assign to the known depth; never distribute down)
    assigned_area_key text NULL,
    assigned_depth smallint NULL,

    source_confidence real NULL,
    source_url text NULL,
    extra jsonb NULL
);

CREATE INDEX IF NOT EXISTS observation_metric_time_idx
    ON facts.observation (metric_id, observed_at);

CREATE INDEX IF NOT EXISTS observation_area_metric_time_idx
    ON facts.observation (assigned_area_key, metric_id, observed_at)
    WHERE assigned_area_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS observation_point_geom_gist
    ON facts.observation USING gist (point_geom)
    WHERE point_geom IS NOT NULL;

-- Pre-aggregated daily rollups per area.
CREATE TABLE IF NOT EXISTS facts_agg.area_metric_daily (
    area_key text NOT NULL,
    area_depth smallint NOT NULL,
    metric_id text NOT NULL REFERENCES facts.metric(metric_id),
    day date NOT NULL,

    count_value bigint NOT NULL,
    sum_value numeric NOT NULL,
    min_value numeric NOT NULL,
    max_value numeric NOT NULL,

    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (area_key, metric_id, day)
);

CREATE INDEX IF NOT EXISTS area_metric_daily_metric_day_idx
    ON facts_agg.area_metric_daily (metric_id, day);

CREATE INDEX IF NOT EXISTS area_metric_daily_area_metric_idx
    ON facts_agg.area_metric_daily (area_key, metric_id);
