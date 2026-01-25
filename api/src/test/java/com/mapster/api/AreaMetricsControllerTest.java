package com.mapster.api;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AreaMetricsController.class)
class AreaMetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void buildsSqlThatJoinsAggregatesAndUsesGeoAdminAreasView() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("{\"type\":\"FeatureCollection\",\"features\":[]}");

        mockMvc.perform(
                get("/area-metrics")
                    .param("minLon", "13.38")
                    .param("minLat", "52.51")
                    .param("maxLon", "13.40")
                    .param("maxLat", "52.52")
                    .param("metricId", "price_eur_per_m2_land")
                    .param("depth", "5")
                    .param("from", "2026-01-01")
                    .param("to", "2026-01-22")
            )
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(String.class), any(), any(), any(), any(), any(), any(), any(), any(), any());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("facts_agg.area_metric_daily");
        assertThat(sql).contains("FROM areas");
        assertThat(sql).contains("LEFT JOIN facts_agg.area_metric_daily");
        assertThat(sql).contains("FROM geo.admin_areas");
        assertThat(sql).contains("LEFT JOIN agg");
        assertThat(sql).contains("AND a.depth = ?");
    }

    @Test
    void areaMetricsValuesReturnsJsonWithoutGeometryAndUsesAreaKeyJoin() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class)))
            .thenReturn("{\"metric_id\":\"price_eur_per_m2_land\",\"from\":\"2026-01-01\",\"to\":\"2026-01-22\",\"global_avg\":null,\"items\":[]}");

        mockMvc.perform(
                get("/area-metrics-values")
                    .param("minLon", "13.38")
                    .param("minLat", "52.51")
                    .param("maxLon", "13.40")
                    .param("maxLat", "52.52")
                    .param("metricId", "price_eur_per_m2_land")
                    .param("depth", "5")
                    .param("from", "2026-01-01")
                    .param("to", "2026-01-22")
            )
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(String.class), any(Object[].class));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM geo.admin_areas");
        assertThat(sql).contains("facts_agg.area_metric_daily");
        assertThat(sql).contains("LEFT JOIN facts_agg.area_metric_daily");
        assertThat(sql).contains("d.area_key = ar.area_key");

        // Metrics-only endpoint should not serialize geometry.
        assertThat(sql).doesNotContain("ST_AsGeoJSON");
        assertThat(sql).doesNotContain("'geometry'");
    }
}
