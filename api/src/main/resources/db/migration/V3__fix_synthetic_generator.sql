-- Fix synthetic generator: avoid PL/pgSQL variable ambiguity in ON CONFLICT target
--
-- The original V2 function used `ON CONFLICT (metric_id)` inside a PL/pgSQL function
-- that also had a parameter named `metric_id`, which causes an ambiguity error.
--
-- This migration replaces the function definition and uses:
--   ON CONFLICT ON CONSTRAINT metric_pkey

CREATE OR REPLACE FUNCTION facts.generate_synthetic_clustered_observations(
    metric_id text,
    n integer,
    min_lon double precision,
    min_lat double precision,
    max_lon double precision,
    max_lat double precision,
    clusters integer DEFAULT 6,
    start_day date DEFAULT (current_date - 30),
    end_day date DEFAULT current_date,
    base_value numeric DEFAULT 230,
    cluster_value_sigma double precision DEFAULT 0.35,
    noise_sigma numeric DEFAULT 25,
    cluster_spread double precision DEFAULT 0.08,
    seed double precision DEFAULT NULL,
    unit text DEFAULT NULL,
    currency text DEFAULT NULL
) RETURNS TABLE(inserted_observations bigint, inserted_rollup_rows bigint)
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
  days int;
BEGIN
  IF n IS NULL OR n <= 0 THEN
    RETURN QUERY SELECT 0::bigint, 0::bigint;
    RETURN;
  END IF;

  IF clusters IS NULL OR clusters <= 0 THEN
    clusters := 1;
  END IF;

  IF start_day IS NULL OR end_day IS NULL OR start_day > end_day THEN
    RAISE EXCEPTION 'invalid date range: start_day=% end_day=%', start_day, end_day;
  END IF;

  days := (end_day - start_day) + 1;

  IF seed IS NOT NULL THEN
    PERFORM setseed(seed);
  END IF;

  -- Ensure metric exists.
  INSERT INTO facts.metric (metric_id, unit, description)
  VALUES (metric_id, unit, 'synthetic metric')
  ON CONFLICT ON CONSTRAINT metric_pkey DO NOTHING;

  RETURN QUERY
  WITH
  cfg AS (
    SELECT
      min_lon::double precision AS min_lon,
      min_lat::double precision AS min_lat,
      max_lon::double precision AS max_lon,
      max_lat::double precision AS max_lat,
      greatest(max_lon - min_lon, 1e-9) AS span_lon,
      greatest(max_lat - min_lat, 1e-9) AS span_lat,
      clusters::int AS clusters,
      start_day::date AS start_day,
      days::int AS days,
      base_value::numeric AS base_value,
      cluster_value_sigma::double precision AS cluster_value_sigma,
      noise_sigma::numeric AS noise_sigma,
      cluster_spread::double precision AS cluster_spread
  ),
  centers AS (
    -- Each cluster has a center point and a lognormal-ish value.
    SELECT
      c AS cluster_id,
      (cfg.min_lon + random() * cfg.span_lon) AS center_lon,
      (cfg.min_lat + random() * cfg.span_lat) AS center_lat,
      (cfg.base_value * exp(facts.randn() * cfg.cluster_value_sigma))::numeric AS center_value
    FROM cfg, generate_series(1, cfg.clusters) c
  ),
  synth AS (
    -- For each observation: pick a cluster, jitter location, set value = center_value + noise.
    SELECT
      i AS obs_idx,
      (1 + floor(random() * cfg.clusters))::int AS cluster_id,
      cfg.min_lon, cfg.min_lat, cfg.max_lon, cfg.max_lat,
      cfg.span_lon, cfg.span_lat,
      cfg.start_day,
      cfg.days,
      cfg.cluster_spread,
      cfg.noise_sigma
    FROM cfg, generate_series(1, n) i
  ),
  points AS (
    SELECT
      s.obs_idx,
      s.cluster_id,
      -- Gaussian jitter around the chosen center.
      least(greatest(c.center_lon + facts.randn() * s.span_lon * s.cluster_spread, s.min_lon), s.max_lon) AS lon,
      least(greatest(c.center_lat + facts.randn() * s.span_lat * s.cluster_spread, s.min_lat), s.max_lat) AS lat,
      -- Random day in range.
      (s.start_day + floor(random() * s.days)::int) AS day,
      greatest((c.center_value + (facts.randn() * s.noise_sigma)::numeric), 0.01::numeric) AS value
    FROM synth s
    JOIN centers c ON c.cluster_id = s.cluster_id
  ),
  assigned AS (
    SELECT
      p.obs_idx,
      p.day,
      p.value,
      ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326) AS geom,
      aa.area_key,
      aa.depth
    FROM points p
    LEFT JOIN LATERAL (
      SELECT
        geo.area_key(a.gid_0, a.gid_1, a.gid_2, a.gid_3, a.gid_4, a.gid_5) AS area_key,
        geo.area_depth(a.gid_1, a.gid_2, a.gid_3, a.gid_4, a.gid_5) AS depth
      FROM public.admin_areas a
      WHERE a.geom && ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326)
        AND ST_Covers(a.geom, ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326))
      ORDER BY geo.area_depth(a.gid_1, a.gid_2, a.gid_3, a.gid_4, a.gid_5) DESC
      LIMIT 1
    ) aa ON true
  ),
  new_obs AS (
    INSERT INTO facts.observation (
      metric_id, value, unit, currency, observed_at,
      point_geom, geocode_accuracy,
      assigned_area_key, assigned_depth,
      source_confidence, source_url,
      extra
    )
    SELECT
      metric_id,
      a.value,
      unit,
      currency,
      (a.day::timestamptz + make_interval(secs => floor(random() * 86400)::int)),
      a.geom,
      'synthetic',
      a.area_key,
      a.depth,
      0.5,
      NULL,
      jsonb_build_object('synthetic', true, 'clustered', true)
    FROM assigned a
    WHERE a.area_key IS NOT NULL
    RETURNING assigned_area_key, metric_id, (observed_at AT TIME ZONE 'UTC')::date AS day, value
  ),
  rollup AS (
    INSERT INTO facts_agg.area_metric_daily (
      area_key, area_depth, metric_id, day,
      count_value, sum_value, min_value, max_value
    )
    SELECT
      anc.ancestor_key,
      anc.ancestor_depth,
      o.metric_id,
      o.day,
      count(*)::bigint,
      sum(o.value)::numeric,
      min(o.value)::numeric,
      max(o.value)::numeric
    FROM new_obs o
    JOIN geo.admin_area_ancestors anc
      ON anc.area_key = o.assigned_area_key
    GROUP BY anc.ancestor_key, anc.ancestor_depth, o.metric_id, o.day
    ON CONFLICT (area_key, metric_id, day) DO UPDATE
      SET count_value = facts_agg.area_metric_daily.count_value + EXCLUDED.count_value,
          sum_value = facts_agg.area_metric_daily.sum_value + EXCLUDED.sum_value,
          min_value = LEAST(facts_agg.area_metric_daily.min_value, EXCLUDED.min_value),
          max_value = GREATEST(facts_agg.area_metric_daily.max_value, EXCLUDED.max_value),
          updated_at = now()
    RETURNING 1
  )
  SELECT
    (SELECT count(*) FROM new_obs) AS inserted_observations,
    (SELECT count(*) FROM rollup) AS inserted_rollup_rows;
END;
$$;
