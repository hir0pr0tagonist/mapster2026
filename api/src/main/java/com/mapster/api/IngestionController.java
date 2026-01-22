package com.mapster.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

@RestController
public class IngestionController {
    private static final Logger logger = LoggerFactory.getLogger(IngestionController.class);

    private final JdbcTemplate jdbcTemplate;
    private final MetricCatalogService metricCatalogService;
    private final AreaAssignmentService areaAssignmentService;
    private final AggregationService aggregationService;

    public IngestionController(
        JdbcTemplate jdbcTemplate,
        MetricCatalogService metricCatalogService,
        AreaAssignmentService areaAssignmentService,
        AggregationService aggregationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.metricCatalogService = metricCatalogService;
        this.areaAssignmentService = areaAssignmentService;
        this.aggregationService = aggregationService;
    }

    @PostMapping(value = "/ingest/raw", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ingestRaw(
        @RequestParam(value = "sourceSystem", defaultValue = "manual") String sourceSystem,
        @RequestParam(value = "sourceRecordId", required = false) String sourceRecordId,
        @RequestBody JsonNode payload
    ) {
        Long id = jdbcTemplate.queryForObject(
            """
            INSERT INTO facts_raw.raw_record (source_system, source_record_id, payload)
            VALUES (?, ?, ?::jsonb)
            RETURNING id
            """,
            Long.class,
            sourceSystem,
            sourceRecordId,
            payload.toString()
        );

        logger.info("[INFO] ingest/raw sourceSystem={}, sourceRecordId={}, id={}", sourceSystem, sourceRecordId, id);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
            "id", id,
            "status", "accepted"
        ));
    }

    public record IngestObservationRequest(
        String metricId,
        BigDecimal value,
        String unit,
        String currency,
        Instant observedAt,
        Double lon,
        Double lat,
        String assignedAreaKey,
        Short assignedDepth,
        Float sourceConfidence,
        String sourceUrl
    ) {}

    @PostMapping(value = "/ingest/observation", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ingestObservation(@RequestBody IngestObservationRequest req) {
        if (req.metricId() == null || req.metricId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "metricId is required"));
        }
        if (req.value() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "value is required"));
        }

        Instant observedAt = req.observedAt() != null ? req.observedAt() : Instant.now();
        LocalDate day = observedAt.atZone(ZoneOffset.UTC).toLocalDate();

        String areaKey = req.assignedAreaKey();
        Short depth = req.assignedDepth();

        if (areaKey == null && req.lon() != null && req.lat() != null) {
            var assignmentOpt = areaAssignmentService.assignByPoint(req.lon(), req.lat());
            if (assignmentOpt.isPresent()) {
                areaKey = assignmentOpt.get().areaKey();
                depth = assignmentOpt.get().depth();
            }
        }

        // Ensure the metric exists so inserts/rollups don't fail.
        metricCatalogService.ensureMetricExists(req.metricId(), req.unit(), null);

        Long obsId = jdbcTemplate.queryForObject(
            """
            INSERT INTO facts.observation (
              metric_id, value, unit, currency, observed_at,
              point_geom,
              assigned_area_key, assigned_depth,
              source_confidence, source_url
            ) VALUES (
              ?, ?, ?, ?, ?,
              CASE WHEN ? IS NULL OR ? IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint(?, ?), 4326) END,
              ?, ?,
              ?, ?
            )
            RETURNING id
            """,
            Long.class,
            req.metricId(),
            req.value(),
            req.unit(),
            req.currency(),
            observedAt,
            req.lon(), req.lat(), req.lon(), req.lat(),
            areaKey,
            depth,
            req.sourceConfidence(),
            req.sourceUrl()
        );

        if (areaKey != null) {
            aggregationService.addObservationToDailyRollups(areaKey, req.metricId(), day, req.value());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", obsId,
            "assignedAreaKey", areaKey,
            "assignedDepth", depth
        ));
    }
}
