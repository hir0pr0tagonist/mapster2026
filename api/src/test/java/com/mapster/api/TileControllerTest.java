package com.mapster.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TileController.class)
class TileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsMvtWithContentTypeAndEtag() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(byte[].class), any(), any(), any()))
                .thenReturn(new byte[] { 0x1, 0x2, 0x3 });

        mockMvc.perform(get("/api/tiles/6/33/20.mvt"))
                .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", MediaType.parseMediaType("application/vnd.mapbox-vector-tile").toString()))
                .andExpect(header().exists("ETag"));

        verify(jdbcTemplate).queryForObject(anyString(), eq(byte[].class), any(), any(), any());
    }

    @Test
    void returns304WhenIfNoneMatchMatches() throws Exception {
        // z=6 => depth=0 per controller mapping
        String etag = "W/\"z=6&x=33&y=20&depth=0\"";

        mockMvc.perform(get("/api/tiles/6/33/20.mvt").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(byte[].class), any(), any(), any());
    }

        @ParameterizedTest
        @CsvSource({
            "5,0",
            "6,0",
            "7,1",
            "11,5",
            "12,5"
        })
        void etagDepthMatchesZoomMapping(int z, int expectedDepth) throws Exception {
        String etag = String.format("W/\"z=%d&x=33&y=20&depth=%d\"", z, expectedDepth);

        mockMvc.perform(get(String.format("/api/tiles/%d/33/20.mvt", z)).header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag));

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(byte[].class), any(), any(), any());
        }

        @ParameterizedTest
        @CsvSource({
            // z, mustContain, mustNotContain
            "5,AND a.name_1 IS NULL,AND a.name_1 IS NOT NULL",
            "7,AND a.name_1 IS NOT NULL,AND a.name_2 IS NOT NULL",
            "12,AND a.name_5 IS NOT NULL,AND a.name_5 IS NULL"
        })
        void tileSqlContainsCorrectDepthFilter(int z, String mustContain, String mustNotContain) throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(byte[].class), any(), any(), any()))
            .thenReturn(new byte[] { 0x1 });

        mockMvc.perform(get(String.format("/api/tiles/%d/33/20.mvt", z)))
            .andExpect(status().isOk());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).queryForObject(sqlCaptor.capture(), eq(byte[].class), any(), any(), any());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains(mustContain);
        assertThat(sql).doesNotContain(mustNotContain);
        }
}

