package com.mapster.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.http.MediaType;

@RestController
public class OverlayController {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @CrossOrigin(origins = "*")
    @GetMapping(value = "/api/overlays", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getOverlays(
            @RequestParam("minLon") double minLon,
            @RequestParam("minLat") double minLat,
            @RequestParam("maxLon") double maxLon,
            @RequestParam("maxLat") double maxLat,
            @RequestParam(value = "band", required = false) Integer band) {
        // Map band (0-5) to name_x field (name_1, name_2, ...)
        int bandLevel = (band != null) ? band : 0;
        int nameFieldIdx = bandLevel + 1;
        String nameField = "name_" + nameFieldIdx;
        String nextNameField = "name_" + (nameFieldIdx + 1);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT jsonb_build_object(");
        sql.append(" 'type', 'FeatureCollection',");
        sql.append(" 'features', jsonb_agg(");
        sql.append("  jsonb_set(");
        sql.append("    ST_AsGeoJSON(t.*)::jsonb,");
        sql.append("    '{properties}',");
        sql.append("    (");
        sql.append("      to_jsonb(row_to_json(t)) - 'geom' - 'id' ");
        sql.append("    )::jsonb || jsonb_build_object('id', t.id, 'name_0', t.name_0, 'name_1', t.name_1, 'name_2', t.name_2, 'name_3', t.name_3, 'name_4', t.name_4, 'name_5', t.name_5)");
        sql.append("  )");
        sql.append(")");
        sql.append(") FROM (");
        sql.append(" SELECT id, name, name_0, name_1, name_2, name_3, name_4, name_5, geom FROM admin_areas WHERE ST_Intersects(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))");
        if (nameFieldIdx < 6) {
            // Bands 0-4: exact level only
            sql.append(" AND ")
               .append(nameField).append(" IS NOT NULL AND ").append(nameField).append(" != '' AND (")
               .append(nextNameField).append(" IS NULL OR ").append(nextNameField).append(" = '')");
        } else {
            // Band 5: show name_5 if present, else fallback to name_4 where name_5 is missing
            sql.append(" AND ((name_5 IS NOT NULL AND name_5 != '') OR (name_4 IS NOT NULL AND name_4 != '' AND (name_5 IS NULL OR name_5 = '')))");
        }
        sql.append(") t");
        try {
            String result = jdbcTemplate.queryForObject(sql.toString(), String.class, minLon, minLat, maxLon, maxLat);
            if (result == null) {
                return "{\"type\":\"FeatureCollection\",\"features\":[]}";
            }
            return result;
        } catch (Exception e) {
            return "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
    }
}
