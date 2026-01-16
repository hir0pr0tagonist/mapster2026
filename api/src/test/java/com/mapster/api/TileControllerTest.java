package com.mapster.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
}
