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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestionController.class)
class IngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private MetricCatalogService metricCatalogService;

    @MockBean
    private AreaAssignmentService areaAssignmentService;

    @MockBean
    private AggregationService aggregationService;

    @Test
    void ingestRawInsertsIntoFactsRaw() throws Exception {
        when(jdbcTemplate.queryForObject(contains("INSERT INTO facts_raw.raw_record"), eq(Long.class), any(), any(), any()))
            .thenReturn(123L);

        mockMvc.perform(
                post("/ingest/raw")
                    .param("sourceSystem", "test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"hello\":\"world\"}")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value(123))
            .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void ingestObservationInsertsAndTriggersAggregationWhenAssignedAreaPresent() throws Exception {
        when(jdbcTemplate.queryForObject(
            contains("INSERT INTO facts.observation"),
            eq(Long.class),
            any(), any(), any(), any(), any(),
            any(), any(), any(), any(),
            any(), any(),
            any(), any()
        )).thenReturn(7L);

        String body = """
            {
              \"metricId\": \"price_eur_per_m2_land\",
              \"value\": 230.5,
              \"unit\": \"EUR/m2\",
              \"currency\": \"EUR\",
              \"assignedAreaKey\": \"DEU|||||\",
              \"assignedDepth\": 0
            }
            """;

        mockMvc.perform(
                post("/ingest/observation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(jsonPath("$.assignedAreaKey").value("DEU|||||"))
            .andExpect(jsonPath("$.assignedDepth").value(0));

        verify(metricCatalogService).ensureMetricExists(eq("price_eur_per_m2_land"), eq("EUR/m2"), isNull());
        verify(aggregationService).addObservationToDailyRollups(eq("DEU|||||"), eq("price_eur_per_m2_land"), any(), eq(new java.math.BigDecimal("230.5")));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            eq(Long.class),
            any(), any(), any(), any(), any(),
            any(), any(), any(), any(),
            any(), any(),
            any(), any()
        );
        assertThat(sqlCaptor.getValue()).contains("INSERT INTO facts.observation");
    }
}
