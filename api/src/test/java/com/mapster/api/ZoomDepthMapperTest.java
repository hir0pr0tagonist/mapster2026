package com.mapster.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ZoomDepthMapperTest {

    @ParameterizedTest
    @CsvSource({
            "0,0",
            "5,0",
            "6,0",
            "7,1",
            "10,4",
            "11,5",
            "12,5",
            "20,5"
    })
    void tileZoomMapsToExpectedDepth(int z, int expectedDepth) {
        assertThat(ZoomDepthMapper.depthForTileZoom(z)).isEqualTo(expectedDepth);
    }

    @ParameterizedTest
    @CsvSource({
            "5.9,0",
            "6.0,0",
            "6.99,0",
            "7.0,1",
            "10.1,4",
            "11.0,5",
            "11.9,5",
            "12.0,5"
    })
    void overlayZoomMapsToExpectedDepth(double zoom, int expectedDepth) {
        assertThat(ZoomDepthMapper.depthForOverlayZoom(zoom)).isEqualTo(expectedDepth);
    }

    @Test
    void overlayNullZoomReturnsNullDepth() {
        assertThat(ZoomDepthMapper.depthForOverlayZoom(null)).isNull();
    }
}
