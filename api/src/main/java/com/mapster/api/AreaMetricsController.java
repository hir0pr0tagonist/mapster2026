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

        sql.append("areas AS ( ");
        sql.append("  SELECT DISTINCT ON (a.area_key) a.* ");
        sql.append("  FROM geo.admin_areas a, env ");
        sql.append("  WHERE a.geom && env.e ");
        sql.append("    AND ST_Intersects(a.geom, env.e) ");
        if (effectiveDepth != null) {
            sql.append("    AND a.depth = ? ");
            params.add(effectiveDepth);
        }
        sql.append("  ORDER BY a.area_key, a.id ");
        sql.append(") , ");

        sql.append("agg AS ( ");
        sql.append("  SELECT ar.area_key, d.metric_id, ");
        sql.append("    SUM(d.sum_value) AS sum_value, ");
        sql.append("    SUM(d.count_value) AS count_value, ");
        sql.append("    MIN(d.min_value) AS min_value, ");
        sql.append("    MAX(d.max_value) AS max_value ");
        sql.append("  FROM areas ar ");
        sql.append("  LEFT JOIN facts_agg.area_metric_daily d ");
        sql.append("    ON d.area_key = ar.area_key ");
        sql.append("   AND d.metric_id = ? ");
        sql.append("   AND d.day >= ? AND d.day <= ? ");
        params.add(metricId);
        params.add(effectiveFrom);
        params.add(effectiveTo);
        sql.append("  GROUP BY ar.area_key, d.metric_id ");
        sql.append(") , ");

        sql.append("enriched AS ( ");
        sql.append("  SELECT ");
        sql.append("    a.*, ");
        sql.append("    COALESCE(agg.count_value, 0) AS count_value, ");
        sql.append("    agg.sum_value AS sum_value, ");
        sql.append("    agg.min_value AS min_value, ");
        sql.append("    agg.max_value AS max_value, ");
        sql.append("    CASE ");
        sql.append("      WHEN agg.count_value IS NULL OR agg.count_value = 0 THEN NULL ");
        sql.append("      ELSE (agg.sum_value / agg.count_value) ");
        sql.append("    END AS avg_value ");
        sql.append("  FROM areas a ");
        sql.append("  LEFT JOIN agg ON agg.area_key = a.area_key ");
        sql.append(") , ");

        sql.append("global AS ( ");
        sql.append("  SELECT ");
        sql.append("    CASE ");
        sql.append("      WHEN SUM(count_value) = 0 THEN NULL ");
        sql.append("      ELSE (SUM(COALESCE(sum_value, 0)) / SUM(count_value)) ");
        sql.append("    END AS global_avg ");
        sql.append("  FROM enriched ");
        sql.append(") ");

        sql.append("SELECT jsonb_build_object( ");
        sql.append("  'type', 'FeatureCollection', ");
        sql.append("  'features', COALESCE(jsonb_agg( ");
        sql.append("    jsonb_build_object( ");
        sql.append("      'type', 'Feature', ");
        sql.append("      'geometry', ST_AsGeoJSON(e.geom, 6)::jsonb, ");
        sql.append("      'properties', jsonb_build_object( ");
        sql.append("        'area_key', e.area_key, ");
        sql.append("        'depth', e.depth, ");
        sql.append("        'gid_0', e.gid_0, 'gid_1', e.gid_1, 'gid_2', e.gid_2, 'gid_3', e.gid_3, 'gid_4', e.gid_4, 'gid_5', e.gid_5, ");
        sql.append("        'name_0', e.country, 'name_1', e.name_1, 'name_2', e.name_2, 'name_3', e.name_3, 'name_4', e.name_4, 'name_5', e.name_5, ");
        sql.append("        'metric_id', ?, ");
        params.add(metricId);
        sql.append("        'count', e.count_value, ");
        sql.append("        'min', e.min_value, ");
        sql.append("        'max', e.max_value, ");
        sql.append("        'avg', e.avg_value, ");
        sql.append("        'global_avg', global.global_avg, ");
        sql.append("        'ratio_to_avg', CASE WHEN e.avg_value IS NULL OR global.global_avg IS NULL OR global.global_avg = 0 THEN NULL ELSE (e.avg_value / global.global_avg) END, ");
        sql.append("        'band', CASE ");
        sql.append("          WHEN e.avg_value IS NULL OR global.global_avg IS NULL OR global.global_avg = 0 THEN NULL ");
        sql.append("          WHEN e.avg_value <= global.global_avg THEN CASE ");
        sql.append("            WHEN (e.avg_value / global.global_avg) <= 0.50 THEN 1 ");
        sql.append("            WHEN (e.avg_value / global.global_avg) <= 0.75 THEN 2 ");
        sql.append("            WHEN (e.avg_value / global.global_avg) <= 0.90 THEN 3 ");
        sql.append("            WHEN (e.avg_value / global.global_avg) <= 0.97 THEN 4 ");
        sql.append("            ELSE 5 ");
        sql.append("          END ");
        sql.append("          ELSE CASE ");
        sql.append("            WHEN (e.avg_value / global.global_avg) >= 2.00 THEN 10 ");
        sql.append("            WHEN (e.avg_value / global.global_avg) >= 1.50 THEN 9 ");
        sql.append("            WHEN (e.avg_value / global.global_avg) >= 1.25 THEN 8 ");
        sql.append("            WHEN (e.avg_value / global.global_avg) >= 1.10 THEN 7 ");
        sql.append("            ELSE 6 ");
        sql.append("          END ");
        sql.append("        END ");
        sql.append("      ) ");
        sql.append("    ) ");
        sql.append("  ), '[]'::jsonb) ");
        sql.append(") ");
        sql.append("FROM enriched e ");
        sql.append("CROSS JOIN global ");

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
