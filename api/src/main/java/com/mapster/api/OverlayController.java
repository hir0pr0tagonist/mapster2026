
package com.mapster.api;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.http.MediaType;

import java.util.Objects;

@RestController
public class OverlayController {
    private static final Logger logger = LoggerFactory.getLogger(OverlayController.class);
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/api/test")
    public String testEndpoint() {
        logger.info("[DEBUG] /api/test endpoint called");
        return "test-ok";
    }

    @CrossOrigin(origins = "*")
    @GetMapping(value = "/api/overlays", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getOverlays(
            @RequestParam("minLon") double minLon,
            @RequestParam("minLat") double minLat,
            @RequestParam("maxLon") double maxLon,
            @RequestParam("maxLat") double maxLat,
            @RequestParam(value = "band", required = false) Integer band,
            @RequestParam(value = "zoom", required = false) Double zoom) {
        logger.info("[DEBUG] getOverlays called: minLon={}, minLat={}, maxLon={}, maxLat={}, band={}, zoom={}", minLon, minLat, maxLon, maxLat, band, zoom);

        // Back-compat: if caller still sends `band`, treat it as an integer zoom level.
        // Preferred is `zoom` (may be floating-point, e.g. 6.14).
        Double effectiveZoom = zoom != null ? zoom : (band != null ? band.doubleValue() : null);

        // We map OSM zoom levels 6..11 onto admin-area depth 0..5.
        // depth 0: country only (name_1..name_5 are null)
        // depth 1: country + name_1 (name_2..name_5 are null)
        // ...
        // depth 5: country + name_1..name_5
        Integer depth = null;
        if (effectiveZoom != null) {
            int z = (int) Math.floor(effectiveZoom);
            if (z < 6) {
                depth = 0;
            } else if (z > 11) {
                depth = 5;
            } else {
                depth = z - 6;
            }
        }

        // Reduce payload size for large polygons at low zoom.
        // This is in degrees (SRID 4326). Values are conservative and derived solely from depth.
        Double simplifyToleranceDeg = null;
        if (depth != null) {
            switch (depth) {
                case 0 -> simplifyToleranceDeg = 0.05;
                case 1 -> simplifyToleranceDeg = 0.02;
                case 2 -> simplifyToleranceDeg = 0.01;
                case 3 -> simplifyToleranceDeg = 0.005;
                case 4 -> simplifyToleranceDeg = 0.002;
                case 5 -> simplifyToleranceDeg = 0.001;
                default -> simplifyToleranceDeg = null;
            }
        }

        String geomExpr = simplifyToleranceDeg != null
                ? "ST_SimplifyPreserveTopology(t.geom, " + simplifyToleranceDeg + ")"
                : "t.geom";
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT jsonb_build_object(");
        sql.append(" 'type', 'FeatureCollection',");
        sql.append(" 'features', COALESCE(jsonb_agg(");
        sql.append("  jsonb_build_object(");
        sql.append("    'type', 'Feature',");
        sql.append("    'geometry', ST_AsGeoJSON(").append(geomExpr).append(", 6)::jsonb,");
        sql.append("    'properties', (");
        sql.append("      to_jsonb(row_to_json(t)) - 'geom' - 'id'");
        sql.append("    )::jsonb || jsonb_build_object('id', t.id, 'name_0', t.country, 'name_1', t.name_1, 'name_2', t.name_2, 'name_3', t.name_3, 'name_4', t.name_4, 'name_5', t.name_5)");
        sql.append("  )");
        sql.append("), '[]'::jsonb)");
        sql.append(") FROM (");
        // NOTE: Some datasets can contain duplicate rows for the same admin unit (same gid_* path) that differ only by
        // surrogate id. Use DISTINCT ON to ensure we emit one Feature per admin unit.
        sql.append(" SELECT DISTINCT ON (gid_0, gid_1, gid_2, gid_3, gid_4, gid_5) id, country, name_1, name_2, name_3, name_4, name_5, geom");
        sql.append(" FROM admin_areas");
        sql.append(" WHERE ST_Intersects(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))");
        if (depth != null) {
            // enforce the hierarchy depth via NULL/NOT NULL checks on name_* columns
            sql.append(" AND country IS NOT NULL");
            if (depth >= 1) sql.append(" AND name_1 IS NOT NULL");
            if (depth >= 2) sql.append(" AND name_2 IS NOT NULL");
            if (depth >= 3) sql.append(" AND name_3 IS NOT NULL");
            if (depth >= 4) sql.append(" AND name_4 IS NOT NULL");
            if (depth >= 5) sql.append(" AND name_5 IS NOT NULL");

            if (depth < 1) sql.append(" AND name_1 IS NULL");
            if (depth < 2) sql.append(" AND name_2 IS NULL");
            if (depth < 3) sql.append(" AND name_3 IS NULL");
            if (depth < 4) sql.append(" AND name_4 IS NULL");
            if (depth < 5) sql.append(" AND name_5 IS NULL");
        }
        sql.append(" ORDER BY gid_0, gid_1, gid_2, gid_3, gid_4, gid_5, id");
        sql.append(") t");
        try {
            logger.info("[DEBUG] Entering overlays SQL try block");
            logger.info("[DEBUG] SQL: {}", sql.toString());
            logger.info("[DEBUG] SQL params: minLon={}, minLat={}, maxLon={}, maxLat={}, effectiveZoom={}, depth={}", minLon, minLat, maxLon, maxLat, effectiveZoom, depth);

                final String emptyFeatureCollection = "{\"type\":\"FeatureCollection\",\"features\":[]}";

                @SuppressWarnings("null")
                String result = Objects.requireNonNullElse(
                    jdbcTemplate.queryForObject(sql.toString(), String.class, minLon, minLat, maxLon, maxLat),
                    emptyFeatureCollection
                );
            int prefixLen = Math.min(500, result.length());
            logger.info("[DEBUG] Raw SQL result length: {}, prefix: {}", result.length(), result.substring(0, prefixLen));
            return result;
        } catch (Exception e) {
            logger.error("[ERROR] Exception in overlays SQL: {}", e.getMessage(), e);
            return "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
    }
}
