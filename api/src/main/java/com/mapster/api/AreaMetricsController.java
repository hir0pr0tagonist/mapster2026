package com.mapster.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@RestController
public class AreaMetricsController {
    private static final Logger logger = LoggerFactory.getLogger(AreaMetricsController.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @CrossOrigin(origins = "*")
    @GetMapping(value = "/area-metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getAreaMetrics(
        @RequestParam("minLon") double minLon,
        @RequestParam("minLat") double minLat,
        @RequestParam("maxLon") double maxLon,
        @RequestParam("maxLat") double maxLat,
        @RequestParam("metricId") String metricId,
        @RequestParam(value = "depth", required = false) Integer depth,
        @RequestParam(value = "zoom", required = false) Double zoom,
        @RequestParam(value = "from", required = false) LocalDate from,
        @RequestParam(value = "to", required = false) LocalDate to,
        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        Integer effectiveDepth = depth;
        if (effectiveDepth == null && zoom != null) {
            effectiveDepth = ZoomDepthMapper.depthForOverlayZoom(zoom);
        }

        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);

        String etag = String.format(
            "W/\"minLon=%.5f&minLat=%.5f&maxLon=%.5f&maxLat=%.5f&depth=%s&metric=%s&from=%s&to=%s\"",
            minLon, minLat, maxLon, maxLat,
            effectiveDepth == null ? "null" : effectiveDepth,
            metricId,
            effectiveFrom,
            effectiveTo
        );

        CacheControl cacheControl = CacheControl.maxAge(10, TimeUnit.SECONDS).cachePublic();
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .cacheControl(cacheControl)
                .build();
        }

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        sql.append("WITH env AS (SELECT ST_MakeEnvelope(?, ?, ?, ?, 4326) AS e), ");
        params.add(minLon);
        params.add(minLat);
        params.add(maxLon);
        params.add(maxLat);

        sql.append("agg AS ( ");
        sql.append("  SELECT area_key, metric_id, ");
        sql.append("    SUM(sum_value) AS sum_value, ");
        sql.append("    SUM(count_value) AS count_value, ");
        sql.append("    MIN(min_value) AS min_value, ");
        sql.append("    MAX(max_value) AS max_value ");
        sql.append("  FROM facts_agg.area_metric_daily ");
        sql.append("  WHERE metric_id = ? AND day >= ? AND day <= ? ");
        params.add(metricId);
        params.add(effectiveFrom);
        params.add(effectiveTo);
        sql.append("  GROUP BY area_key, metric_id ");
        sql.append(") ");

        sql.append("SELECT jsonb_build_object( ");
        sql.append("  'type', 'FeatureCollection', ");
        sql.append("  'features', COALESCE(jsonb_agg( ");
        sql.append("    jsonb_build_object( ");
        sql.append("      'type', 'Feature', ");
        sql.append("      'geometry', ST_AsGeoJSON(a.geom, 6)::jsonb, ");
        sql.append("      'properties', jsonb_build_object( ");
        sql.append("        'area_key', a.area_key, ");
        sql.append("        'depth', a.depth, ");
        sql.append("        'gid_0', a.gid_0, 'gid_1', a.gid_1, 'gid_2', a.gid_2, 'gid_3', a.gid_3, 'gid_4', a.gid_4, 'gid_5', a.gid_5, ");
        sql.append("        'name_0', a.country, 'name_1', a.name_1, 'name_2', a.name_2, 'name_3', a.name_3, 'name_4', a.name_4, 'name_5', a.name_5, ");
        sql.append("        'metric_id', ?, ");
        params.add(metricId);
        sql.append("        'count', COALESCE(agg.count_value, 0), ");
        sql.append("        'min', agg.min_value, ");
        sql.append("        'max', agg.max_value, ");
        sql.append("        'avg', CASE WHEN agg.count_value IS NULL OR agg.count_value = 0 THEN NULL ELSE (agg.sum_value / agg.count_value) END ");
        sql.append("      ) ");
        sql.append("    ) ");
        sql.append("  ), '[]'::jsonb) ");
        sql.append(") ");
        sql.append("FROM ( ");
        sql.append("  SELECT DISTINCT ON (a.area_key) a.* ");
        sql.append("  FROM geo.admin_areas a, env ");
        sql.append("  WHERE a.geom && env.e ");
        sql.append("    AND ST_Intersects(a.geom, env.e) ");
        if (effectiveDepth != null) {
            sql.append("    AND a.depth = ? ");
            params.add(effectiveDepth);
        }
        sql.append("  ORDER BY a.area_key, a.id ");
        sql.append(") a ");
        sql.append("LEFT JOIN agg ON agg.area_key = a.area_key ");

        try {
            logger.info("[DEBUG] area-metrics bbox=({},{})->({},{}), depth={}, metricId={}, from={}, to={}",
                minLon, minLat, maxLon, maxLat, effectiveDepth, metricId, effectiveFrom, effectiveTo);

            final String emptyFeatureCollection = "{\"type\":\"FeatureCollection\",\"features\":[]}";
            String sqlString = Objects.requireNonNull(sql.toString());
            String result = Objects.requireNonNullElse(
                jdbcTemplate.queryForObject(
                    sqlString,
                    String.class,
                    params.toArray()
                ),
                emptyFeatureCollection
            );

            return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cacheControl)
                .body(result);
        } catch (Exception e) {
            logger.error("[ERROR] Exception in area-metrics SQL: {}", e.getMessage(), e);
            return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body("{\"type\":\"FeatureCollection\",\"features\":[]}");
        }
    }
}
