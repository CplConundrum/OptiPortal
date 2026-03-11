package com.optiportal.metrics;

import com.optiportal.OptiPortal;

/** bStats anonymous usage metrics integration. */
public class bStatsIntegration {

    private final OptiPortal plugin;
    private final MetricsCollector metrics;

    public bStatsIntegration(OptiPortal plugin, MetricsCollector metrics) {
        this.plugin = plugin;
        this.metrics = metrics;
    }

    public void init() {
        // TODO: Initialize bStats with plugin ID from config
        // Metrics metrics = new Metrics(plugin, plugin.getPluginConfig().getBstatsPluginId());
        // metrics.addCustomChart(new SingleLineChart("preloads_fired", metrics::getPreloadsFired));
        // metrics.addCustomChart(new SingleLineChart("cache_hit_rate", () -> (int) metrics.getCacheHitRate()));
    }
}
