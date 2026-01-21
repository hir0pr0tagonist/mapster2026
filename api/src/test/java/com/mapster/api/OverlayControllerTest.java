package com.mapster.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OverlayController.class)
class OverlayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsEmptyFeatureCollectionWhenJdbcReturnsNull() throws Exception {
                when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any(), any(), any()))
                .thenReturn(null);

        String body = mockMvc.perform(
                        get("/overlays")
                                .param("minLon", "13.38")
                                .param("minLat", "52.51")
                                .param("maxLon", "13.40")
                                .param("maxLat", "52.52")
                                .param("zoom", "10")
                )
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("type").asText()).isEqualTo("FeatureCollection");
        assertThat(json.path("features").isArray()).isTrue();
        assertThat(json.path("features").size()).isEqualTo(0);
    }

    @Test
    void buildsSqlThatEmitsGeoJsonFeaturesAndDepthFilter() throws Exception {
                when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any(), any(), any()))
                .thenReturn("{\"type\":\"FeatureCollection\",\"features\":[]}");

        mockMvc.perform(
                        get("/overlays")
                                .param("minLon", "13.38")
                                .param("minLat", "52.51")
                                .param("maxLon", "13.40")
                                .param("maxLat", "52.52")
                                .param("zoom", "10")
                )
                .andExpect(status().isOk());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(String.class), any(), any(), any(), any());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("DISTINCT ON (gid_0, gid_1, gid_2, gid_3, gid_4, gid_5)");
        assertThat(sql).contains("'type', 'Feature'");
        assertThat(sql).contains("'geometry'");
        assertThat(sql).contains("ST_AsGeoJSON(");

        // Ensure bbox operator is used for index-friendly filtering.
        assertThat(sql).contains("WITH env AS");
        assertThat(sql).contains("geom && env.e");

        // With zoom=10 => depth 4, enforce exact depth (name_4 present, name_5 absent)
        assertThat(sql).contains("AND name_4 IS NOT NULL");
        assertThat(sql).contains("AND name_5 IS NULL");

        // When zoom/depth is present we simplify geometry (payload control)
        assertThat(sql).contains("ST_SimplifyPreserveTopology");
    }

        @ParameterizedTest
        @CsvSource({
                        // zoom, mustContain, mustNotContain
                        // depth 0
                        "5.9,AND name_1 IS NULL,AND name_1 IS NOT NULL",
                        "6.0,AND name_1 IS NULL,AND name_1 IS NOT NULL",
                        // depth 1
                        "7.0,AND name_1 IS NOT NULL,AND name_2 IS NOT NULL",
                        // depth 5
                        "11.0,AND name_5 IS NOT NULL,AND name_5 IS NULL",
                        "12.0,AND name_5 IS NOT NULL,AND name_5 IS NULL"
        })
        void zoomBoundariesSwitchDepthFilters(double zoom, String mustContain, String mustNotContain) throws Exception {
                when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any(), any(), any()))
                                .thenReturn("{\"type\":\"FeatureCollection\",\"features\":[]}");

                mockMvc.perform(
                                                get("/overlays")
                                                                .param("minLon", "13.38")
                                                                .param("minLat", "52.51")
                                                                .param("maxLon", "13.40")
                                                                .param("maxLat", "52.52")
                                                                .param("zoom", Double.toString(zoom))
                                )
                                .andExpect(status().isOk());

                ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
                verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(String.class), any(), any(), any(), any());

                String sql = sqlCaptor.getValue();
                assertThat(sql).contains(mustContain);
                assertThat(sql).doesNotContain(mustNotContain);
        }
}

