package com.mapster.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AreaAssignmentService {
    private final JdbcTemplate jdbcTemplate;

    public AreaAssignmentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Assignment(String areaKey, short depth) {}

    public Optional<Assignment> assignByPoint(double lon, double lat) {
        // Prefer the most detailed area that contains the point.
        // Note: admin_areas is recreated by the import job, so we use gid_* derived keys (geo.area_key).
        String sql = """
            SELECT
              geo.area_key(gid_0, gid_1, gid_2, gid_3, gid_4, gid_5) AS area_key,
              geo.area_depth(gid_1, gid_2, gid_3, gid_4, gid_5) AS depth
            FROM public.admin_areas
            WHERE ST_Covers(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))
            ORDER BY geo.area_depth(gid_1, gid_2, gid_3, gid_4, gid_5) DESC
            LIMIT 1
            """;

        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new Assignment(rs.getString("area_key"), rs.getShort("depth")));
        }, lon, lat);
    }
}
