
package com.mapster.api;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
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
    public ResponseEntity<String> getOverlays(
            @RequestParam("minLon") double minLon,
            @RequestParam("minLat") double minLat,
            @RequestParam("maxLon") double maxLon,
            @RequestParam("maxLat") double maxLat,
            @RequestParam(value = "band", required = false) Integer band,
            @RequestParam(value = "zoom", required = false) Double zoom,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
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
        // IMPORTANT: simplify in meters (Web Mercator) rather than degrees to avoid scale distortions,
        // and keep tolerances small at high zoom so adjacent polygons don't drift apart visually.
        Integer simplifyToleranceMeters = null;
        Integer snapGridMeters = null;
        if (depth != null) {
            switch (depth) {
                case 0 -> {
                    simplifyToleranceMeters = 5000;
                    snapGridMeters = 250;
                }
                case 1 -> {
                    simplifyToleranceMeters = 2000;
                    snapGridMeters = 100;
                }
                case 2 -> {
                    simplifyToleranceMeters = 800;
                    snapGridMeters = 50;
                }
                case 3 -> {
                    simplifyToleranceMeters = 250;
                    snapGridMeters = 20;
                }
                case 4 -> {
                    simplifyToleranceMeters = 80;
                    snapGridMeters = 5;
                }
                case 5 -> {
                    simplifyToleranceMeters = 20;
                    snapGridMeters = 2;
                }
                default -> {
                    simplifyToleranceMeters = null;
                    snapGridMeters = null;
                }
            }
        }

        String geomExpr;
        if (simplifyToleranceMeters != null && snapGridMeters != null) {
            geomExpr = "ST_Transform(" +
                    "ST_SnapToGrid(" +
                    "ST_SimplifyPreserveTopology(ST_Transform(t.geom, 3857), " + simplifyToleranceMeters + ")" +
                    ", " + snapGridMeters + ")" +
                    ", 4326)";
        } else {
            geomExpr = "t.geom";
        }

        // Weak ETag keyed by request shape + rendering parameters.
        // This enables cheap 304 responses and works well with short max-age caching.
        // (We don't include any DB revision here; this assumes admin boundaries are mostly static.)
        String etag = String.format(
            "W/\"minLon=%.5f&minLat=%.5f&maxLon=%.5f&maxLat=%.5f&depth=%s&s=%s&g=%s\"",
            minLon, minLat, maxLon, maxLat,
            depth == null ? "null" : depth.toString(),
            simplifyToleranceMeters == null ? "null" : simplifyToleranceMeters.toString(),
            snapGridMeters == null ? "null" : snapGridMeters.toString()
        );

        CacheControl cacheControl = CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic();
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .cacheControl(cacheControl)
                .build();
        }
        StringBuilder sql = new StringBuilder();
        // Compute the viewport envelope once and re-use it in both the bbox operator and ST_Intersects.
        sql.append("WITH env AS (SELECT ST_MakeEnvelope(?, ?, ?, ?, 4326) AS e) ");
        sql.append("SELECT jsonb_build_object(");
        sql.append(" 'type', 'FeatureCollection',");
        sql.append(" 'features', COALESCE(jsonb_agg(");
        sql.append("  jsonb_build_object(");
        sql.append("    'type', 'Feature',");
        sql.append("    'geometry', ST_AsGeoJSON(").append(geomExpr).append(", 6)::jsonb,");
        // Build a small properties payload (avoid row_to_json(t) which is larger and slower).
        sql.append("    'properties', jsonb_build_object(");
        sql.append("      'id', t.id,");
        sql.append("      'gid_0', t.gid_0,");
        sql.append("      'gid_1', t.gid_1,");
        sql.append("      'gid_2', t.gid_2,");
        sql.append("      'gid_3', t.gid_3,");
        sql.append("      'gid_4', t.gid_4,");
        sql.append("      'gid_5', t.gid_5,");
        sql.append("      'name_0', t.country,");
        sql.append("      'name_1', t.name_1,");
        sql.append("      'name_2', t.name_2,");
        sql.append("      'name_3', t.name_3,");
        sql.append("      'name_4', t.name_4,");
        sql.append("      'name_5', t.name_5");
        sql.append("    )");
        sql.append("  )");
        sql.append("), '[]'::jsonb)");
        sql.append(") FROM (");
        // NOTE: Some datasets can contain duplicate rows for the same admin unit (same gid_* path) that differ only by
        // surrogate id. Use DISTINCT ON to ensure we emit one Feature per admin unit.
        sql.append(" SELECT DISTINCT ON (gid_0, gid_1, gid_2, gid_3, gid_4, gid_5) id, gid_0, gid_1, gid_2, gid_3, gid_4, gid_5, country, name_1, name_2, name_3, name_4, name_5, geom");
        sql.append(" FROM admin_areas, env");
        // Use the bbox operator first to maximize GiST index usage; then exact intersects.
        sql.append(" WHERE geom && env.e");
        sql.append(" AND ST_Intersects(geom, env.e)");
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
                    jdbcTemplate.queryForObject(sql.toString(), String.class,
                            minLon, minLat, maxLon, maxLat),
                    emptyFeatureCollection
                );
            int prefixLen = Math.min(500, result.length());
            logger.info("[DEBUG] Raw SQL result length: {}, prefix: {}", result.length(), result.substring(0, prefixLen));
            return ResponseEntity.ok()
                    .eTag(etag)
                    .cacheControl(cacheControl)
                    .body(result);
        } catch (Exception e) {
            logger.error("[ERROR] Exception in overlays SQL: {}", e.getMessage(), e);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body("{\"type\":\"FeatureCollection\",\"features\":[]}");
        }
    }
}
