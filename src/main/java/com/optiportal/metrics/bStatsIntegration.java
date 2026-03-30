package com.optiportal.metrics;

import com.optiportal.OptiPortal;
import java.util.logging.Logger;

/** bStats anonymous usage metrics integration. */
public class bStatsIntegration {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final OptiPortal plugin;
    private final MetricsCollector metrics;

    public bStatsIntegration(OptiPortal plugin, MetricsCollector metrics) {
        this.plugin = plugin;
        this.metrics = metrics;
    }

    public void init() {
        // bStats is not yet wired up — add the bStats library dependency and uncomment
        // the lines below, supplying the plugin ID from config:
        //
        // Metrics bStats = new Metrics(plugin, plugin.getPluginConfig().getBstatsPluginId());
        // bStats.addCustomChart(new SingleLineChart("preloads_fired", metrics::getPreloadsFired));
        // bStats.addCustomChart(new SingleLineChart("cache_hit_rate", () -> (int) metrics.getCacheHitRate()));
        LOG.fine("[OptiPortal] bStats integration is not configured — anonymous usage metrics disabled.");
    }
}
