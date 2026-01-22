package com.mapster.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
public class AggregationService {
    private final JdbcTemplate jdbcTemplate;

    public AggregationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void addObservationToDailyRollups(String assignedAreaKey, String metricId, LocalDate day, BigDecimal value) {
        // Option 1 policy is encoded by assignment: we only roll up to ancestors (never distribute down).
        // This upserts daily rollups for (self + ancestors) via geo.admin_area_ancestors.
        jdbcTemplate.update(
            """
            WITH anc AS (
              SELECT ancestor_key, ancestor_depth
              FROM geo.admin_area_ancestors
              WHERE area_key = ?
            )
            INSERT INTO facts_agg.area_metric_daily (
              area_key, area_depth, metric_id, day,
              count_value, sum_value, min_value, max_value
            )
            SELECT
              anc.ancestor_key,
              anc.ancestor_depth,
              ?,
              ?,
              1,
              ?,
              ?,
              ?
            FROM anc
            ON CONFLICT (area_key, metric_id, day) DO UPDATE
              SET count_value = facts_agg.area_metric_daily.count_value + 1,
                  sum_value = facts_agg.area_metric_daily.sum_value + EXCLUDED.sum_value,
                  min_value = LEAST(facts_agg.area_metric_daily.min_value, EXCLUDED.min_value),
                  max_value = GREATEST(facts_agg.area_metric_daily.max_value, EXCLUDED.max_value),
                  updated_at = now()
            """,
            assignedAreaKey,
            metricId,
            day,
            value,
            value,
            value
        );
    }
}
