package com.optiportal.model;

/**
 * Pure radius-resolution helpers shared by the preloader, keepalive, warm-zone
 * and teleport layers.
 *
 * <p>Neutral home for the per-axis resolve logic that previously existed as
 * duplicated per-class methods (ChunkPreloader / KeepaliveManager /
 * WarmZoneManager carried identical copies; both teleport interceptors carried
 * a uniform-radius copy with a different fallback family).
 *
 * <p>The fallback value is a parameter because the call-site families
 * intentionally differ: the preload layer falls back to the default warm
 * radius, the teleport layer to the predictive radius.
 *
 * <p>The strategy-aware variants in PreloadCommand / OptiPortalUIPage are a
 * separate family (WARM -&gt; default warm radius, otherwise predictive radius)
 * and are deliberately not consolidated here.
 */
public final class PortalRadii {

    private PortalRadii() {
    }

    /** X-axis radius: entry explicit axis radius &gt; uniform warm radius &gt; caller default. */
    public static int resolveRadiusX(PortalEntry entry, int defaultRadius) {
        if (entry.getWarmRadiusX() != null) return entry.getWarmRadiusX();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return defaultRadius;
    }

    /** Z-axis radius: entry explicit axis radius &gt; uniform warm radius &gt; caller default. */
    public static int resolveRadiusZ(PortalEntry entry, int defaultRadius) {
        if (entry.getWarmRadiusZ() != null) return entry.getWarmRadiusZ();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return defaultRadius;
    }

    /**
     * Uniform-radius resolution (predecessor of the per-axis pair):
     * entry uniform radius &gt; caller fallback. The caller passes its own
     * fallback (default warm radius in the preload layer, predictive radius in
     * the teleport layer) -- the two families intentionally differ.
     */
    public static int resolveRadius(PortalEntry entry, int fallbackRadius) {
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return fallbackRadius;
    }
}
