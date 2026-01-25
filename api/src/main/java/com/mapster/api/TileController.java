package com.mapster.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class TileController {
    private static final Logger logger = LoggerFactory.getLogger(TileController.class);

    // Vector tiles are best-effort cacheable because boundaries are mostly static.
    private static final CacheControl TILE_CACHE = CacheControl.maxAge(Duration.ofHours(6)).cachePublic();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @CrossOrigin(origins = "*")
    @GetMapping(value = "/tiles/{z}/{x}/{y}.mvt")
    public ResponseEntity<byte[]> getTile(
            @PathVariable("z") int z,
            @PathVariable("x") int x,
            @PathVariable("y") int y,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        // Map zoom 6..11 => depth 0..5 (same as GeoJSON endpoint)
        int depth = ZoomDepthMapper.depthForTileZoom(z);

        String etag = String.format("W/\"z=%d&x=%d&y=%d&depth=%d\"", z, x, y, depth);
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(TILE_CACHE)
                    .build();
        }

        // Build MVT using PostGIS; filter using bbox in 4326 for index usage, then transform for MVT geometry.
        // - ST_TileEnvelope returns WebMercator bounds (3857).
        // - We transform that to 4326 for fast GiST index filtering.
        // - ST_AsMVTGeom clips to tile bounds.
        StringBuilder sql = new StringBuilder();
        sql.append("WITH bounds AS (");
        // IMPORTANT: use the 3-arg overload; passing NULL bounds explicitly can yield NULL.
        sql.append("  SELECT ST_TileEnvelope(?, ?, ?) AS b3857");
        sql.append("), env AS (");
        sql.append("  SELECT ST_Transform(bounds.b3857, 4326) AS b4326 FROM bounds");
        sql.append("), candidates AS (");
        sql.append("  SELECT ");
        sql.append("    a.id,");
        sql.append("    a.gid_0, a.gid_1, a.gid_2, a.gid_3, a.gid_4, a.gid_5,");
        sql.append("    geo.area_key(a.gid_0, a.gid_1, a.gid_2, a.gid_3, a.gid_4, a.gid_5) AS area_key,");
        sql.append("    a.country AS name_0,");
        sql.append("    a.name_1, a.name_2, a.name_3, a.name_4, a.name_5,");
        sql.append("    CASE ");
        sql.append("      WHEN a.name_5 IS NOT NULL THEN 5");
        sql.append("      WHEN a.name_4 IS NOT NULL THEN 4");
        sql.append("      WHEN a.name_3 IS NOT NULL THEN 3");
        sql.append("      WHEN a.name_2 IS NOT NULL THEN 2");
        sql.append("      WHEN a.name_1 IS NOT NULL THEN 1");
        sql.append("      ELSE 0");
        sql.append("    END AS a_depth,");
        sql.append("    ST_AsMVTGeom(");
        sql.append("      ST_Transform(a.geom, 3857),");
        sql.append("      bounds.b3857,");
        sql.append("      4096,");
        sql.append("      64,");
        sql.append("      true");
        sql.append("    ) AS geom ");
        sql.append("  FROM admin_areas a, bounds, env ");
        sql.append("  WHERE a.geom && env.b4326 ");
        sql.append("    AND ST_Intersects(a.geom, env.b4326) ");
        sql.append("    AND a.country IS NOT NULL ");
        sql.append("), target AS (");
        // Clamp requested depth to what is actually available within this tile.
        // This prevents empty tiles (no borders) in countries that lack deeper admin levels.
        sql.append("  SELECT LEAST(").append(depth).append(", COALESCE(MAX(a_depth), 0)) AS depth FROM candidates");
        sql.append("), mvt AS (");
        sql.append("  SELECT DISTINCT ON (c.gid_0, c.gid_1, c.gid_2, c.gid_3, c.gid_4, c.gid_5) ");
        sql.append("    c.id,");
        sql.append("    c.gid_0, c.gid_1, c.gid_2, c.gid_3, c.gid_4, c.gid_5,");
        sql.append("    c.area_key,");
        sql.append("    c.name_0, c.name_1, c.name_2, c.name_3, c.name_4, c.name_5,");
        sql.append("    c.geom ");
        sql.append("  FROM candidates c, target ");
        sql.append("  WHERE c.a_depth = target.depth ");
        sql.append("  ORDER BY c.gid_0, c.gid_1, c.gid_2, c.gid_3, c.gid_4, c.gid_5, c.id");
        sql.append(") ");
        // Ensure vector-tile features have a stable feature id so MapLibre feature-state works.
        // (We keep the 'id' property too, but this sets the MVT feature id explicitly.)
        sql.append("SELECT COALESCE(ST_AsMVT(mvt, 'admin', 4096, 'geom', 'id'), ''::bytea) ");
        sql.append("FROM mvt ");
        sql.append("WHERE mvt.geom IS NOT NULL;");

        try {
            logger.info("[DEBUG] getTile z={}, x={}, y={}, depth={}", z, x, y, depth);
            byte[] tile = jdbcTemplate.queryForObject(sql.toString(), byte[].class, z, x, y);
            if (tile == null) tile = new byte[0];

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.mapbox-vector-tile"));
            headers.set("Content-Disposition", "inline");

            return ResponseEntity.ok()
                    .headers(headers)
                    .eTag(etag)
                    .cacheControl(TILE_CACHE)
                    .body(tile);
        } catch (Exception e) {
            logger.error("[ERROR] Exception in tile SQL: {}", e.getMessage(), e);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.mapbox-vector-tile"))
                    .cacheControl(CacheControl.noStore())
                    .body(new byte[0]);
        }
    }
}
