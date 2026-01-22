package com.mapster.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MetricCatalogService {
    private final JdbcTemplate jdbcTemplate;

    public MetricCatalogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureMetricExists(String metricId, String unit, String description) {
        // Idempotent upsert.
        jdbcTemplate.update(
            """
            INSERT INTO facts.metric (metric_id, unit, description)
            VALUES (?, ?, ?)
            ON CONFLICT (metric_id) DO UPDATE
              SET unit = COALESCE(EXCLUDED.unit, facts.metric.unit),
                  description = COALESCE(EXCLUDED.description, facts.metric.description)
            """,
            metricId,
            unit,
            description
        );
    }
}
