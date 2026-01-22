package com.mapster.api;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MetricsController {
    private final JdbcTemplate jdbcTemplate;

    public MetricsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record MetricDto(String metricId, String unit, String description) {}

    @CrossOrigin(origins = "*")
    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MetricDto> listMetrics() {
        return jdbcTemplate.query(
            "SELECT metric_id, unit, description FROM facts.metric ORDER BY metric_id",
            (rs, rowNum) -> new MetricDto(
                rs.getString("metric_id"),
                rs.getString("unit"),
                rs.getString("description")
            )
        );
    }
}
