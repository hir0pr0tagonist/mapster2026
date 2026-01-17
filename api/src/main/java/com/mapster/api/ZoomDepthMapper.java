package com.mapster.api;

/**
 * Maps OSM-style zoom levels to an administrative boundary depth.
 *
 * Depth is clamped such that zoom <= 6 maps to depth 0 and zoom >= 11 maps to depth 5.
 */
public final class ZoomDepthMapper {
    private ZoomDepthMapper() {}

    public static final int MIN_ZOOM = 6;
    public static final int MAX_ZOOM = 11;
    public static final int MIN_DEPTH = 0;
    public static final int MAX_DEPTH = 5;

    public static int depthForTileZoom(int z) {
        if (z <= MIN_ZOOM) return MIN_DEPTH;
        if (z >= MAX_ZOOM) return MAX_DEPTH;
        return z - MIN_ZOOM;
    }

    public static Integer depthForOverlayZoom(Double zoom) {
        if (zoom == null) return null;
        int z = (int) Math.floor(zoom);
        return depthForTileZoom(z);
    }
}
