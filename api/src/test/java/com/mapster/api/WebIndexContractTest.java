package com.mapster.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WebIndexContractTest {

    @Test
    void webIndexContainsVectorTileSourceAndBoundaryLayers() throws Exception {
        // Tests the UI "contract" without requiring a browser/WebGL:
        // - vector tiles source is present
        // - boundary layers exist
        // - layer order is fill -> highlight -> line (so highlight sits under outlines)
        Path indexHtml = Path.of("..", "web", "index.html");
        String html = Files.readString(indexHtml, StandardCharsets.UTF_8);

        assertThat(html).contains("map.addSource('admin-tiles'");
        assertThat(html).contains("/api/tiles/{z}/{x}/{y}.mvt");

        int fillIdx = html.indexOf("id: 'boundaries-fill'");
        int highlightIdx = html.indexOf("id: 'boundaries-highlight'");
        int lineIdx = html.indexOf("id: 'boundaries-line'");

        assertThat(fillIdx).isGreaterThan(0);
        assertThat(highlightIdx).isGreaterThan(0);
        assertThat(lineIdx).isGreaterThan(0);
        assertThat(fillIdx).isLessThan(highlightIdx);
        assertThat(highlightIdx).isLessThan(lineIdx);
    }
}
