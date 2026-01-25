-- Seed lightweight dummy metric rollups into facts_agg.area_metric_daily.
--
-- Goal: enable UI testing (shading/bands) without heavy observation generation.
-- This inserts/updates rollups for areas intersecting a bbox at a given depth.
--
-- Usage (example):
--   docker compose exec -T postgis psql -U mapster -d mapster \
--     -v minLon=5.5 -v minLat=47.0 -v maxLon=15.5 -v maxLat=55.5 \
--     -v depth=1 -v metricId=price_eur_per_m2_land -v days=30 \
--     -f /dev/stdin < postgis/dev/seed_dummy_metrics.sql
--
-- Required psql vars:
--   :minLon :minLat :maxLon :maxLat :depth :metricId
-- Optional (caller should provide defaults):
--   :days
--   :count
--   :base
--   :spread

BEGIN;

-- Ensure metric exists.
INSERT INTO facts.metric(metric_id, unit, description)
VALUES (:'metricId', 'unit', 'Dummy metric seeded for UI testing')
ON CONFLICT (metric_id) DO NOTHING;

WITH env AS (
  SELECT ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326) AS e
),
areas AS (
  SELECT DISTINCT ON (a.area_key)
    a.area_key,
    a.depth::smallint AS area_depth
  FROM geo.admin_areas a, env
  WHERE a.geom && env.e
    AND ST_Intersects(a.geom, env.e)
    AND a.depth = :depth
  ORDER BY a.area_key
),
days AS (
  SELECT d::date AS day
  FROM generate_series(
    (CURRENT_DATE - (greatest(:days::int - 1, 0) || ' days')::interval)::date,
    CURRENT_DATE::date,
    INTERVAL '1 day'
  ) d
),
seeded AS (
  SELECT
    a.area_key,
    a.area_depth,
    dy.day,
    (:count)::bigint AS count_value,
    (
      (:base)::numeric
      + ((('x' || substr(md5(a.area_key || '|' || dy.day::text || '|' || :'metricId'), 1, 8))::bit(32)::int % (:spread)::int) - ((:spread)::int / 2))
    )::numeric AS avg_value
  FROM areas a
  CROSS JOIN days dy
)
INSERT INTO facts_agg.area_metric_daily(
  area_key,
  area_depth,
  metric_id,
  day,
  count_value,
  sum_value,
  min_value,
  max_value
)
SELECT
  s.area_key,
  s.area_depth,
  :'metricId' AS metric_id,
  s.day,
  s.count_value,
  (s.avg_value * s.count_value)::numeric AS sum_value,
  (s.avg_value * 0.85)::numeric AS min_value,
  (s.avg_value * 1.15)::numeric AS max_value
FROM seeded s
ON CONFLICT (area_key, metric_id, day)
DO UPDATE SET
  area_depth = EXCLUDED.area_depth,
  count_value = EXCLUDED.count_value,
  sum_value = EXCLUDED.sum_value,
  min_value = EXCLUDED.min_value,
  max_value = EXCLUDED.max_value,
  updated_at = now();

COMMIT;
