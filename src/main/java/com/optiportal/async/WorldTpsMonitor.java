package com.optiportal.async;

import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.universe.world.World;
import com.optiportal.config.PluginConfig;
import com.optiportal.preload.WorldRegistry;

/**
 * Observes server TPS by reading World.getBufferedTickLengthMetricSet() each
 * sample interval and converting the most recent tick duration to TPS.
 *
 * This monitor is READ-ONLY — it never controls or limits the server tick rate.
 * The server's actual TPS is determined entirely by the Hytale engine and can
 * exceed HYTALE_TARGET_TPS (e.g. during catch-up bursts). OptiPortal uses the
 * observed TPS only to throttle its own chunk-preload backpressure; it does not
 * cap, slow, or otherwise influence the engine's tick rate.
 *
 * If multiple worlds are loaded, we use the MINIMUM TPS across all ticking
 * worlds (most conservative for backpressure decisions). EMA smoothing is applied
 * to suppress single-tick spikes.
 *
 * Threading: volatile double for currentTps. All reads are safe from any thread.
 */
public class WorldTpsMonitor {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    /**
     * Hytale's standard target tick rate. Used as a reference for load-factor
     * calculations and backpressure thresholds only — OptiPortal does not
     * enforce or limit the server to this value.
     */
    public static final double HYTALE_TARGET_TPS = 20.0;

    /**
     * @deprecated Use {@link #HYTALE_TARGET_TPS}. Kept for binary compatibility.
     */
    @Deprecated
    public static final double NOMINAL_TPS = HYTALE_TARGET_TPS;

    /** Default TPS threshold below which minimum batch size is used. Configurable via config.json tpsMonitor.lowThreshold. */
    public static final double TPS_LOW_THRESHOLD = 18.0;

    /** Default TPS threshold below which queue-all mode is used. Configurable via config.json tpsMonitor.criticalThreshold. */
    public static final double TPS_CRITICAL_THRESHOLD = 12.0;

    /** Sample interval in seconds. Shorter interval + EMA smoothing gives faster response
     *  with less noise than a longer raw window. */
    private static final int SAMPLE_INTERVAL_SECONDS = 1;

    /**
     * EMA smoothing factor. Higher = reacts faster but noisier; lower = smoother but slower.
     * At alpha=0.25 with 1s samples, a sustained drop to 15 TPS registers at ~95% after
     * ~4 samples (~4 seconds) — fast enough for backpressure, slow enough to ignore blips.
     */
    private static final double EMA_ALPHA = 0.25;

    private final WorldRegistry worldRegistry;
    private final ScheduledExecutorService executor;
    private final double tpsLowThreshold;
    private final double tpsCriticalThreshold;
    private volatile double currentTps = HYTALE_TARGET_TPS;

    public WorldTpsMonitor(WorldRegistry worldRegistry, ScheduledExecutorService executor) {
        this(worldRegistry, executor, TPS_LOW_THRESHOLD, TPS_CRITICAL_THRESHOLD);
    }

    public WorldTpsMonitor(WorldRegistry worldRegistry, ScheduledExecutorService executor, PluginConfig config) {
        this(worldRegistry, executor, config.getTpsLowThreshold(), config.getTpsCriticalThreshold());
    }

    private WorldTpsMonitor(WorldRegistry worldRegistry, ScheduledExecutorService executor,
                            double tpsLowThreshold, double tpsCriticalThreshold) {
        this.worldRegistry = worldRegistry;
        this.executor = executor;
        this.tpsLowThreshold = tpsLowThreshold;
        this.tpsCriticalThreshold = tpsCriticalThreshold;
    }

    /**
     * Start TPS sampling. Call once after construction.
     */
    public void start() {
        executor.scheduleAtFixedRate(() -> {
            try {
                sampleTps();
            } catch (Exception e) {
                LOG.fine(() -> "[OptiPortal] WorldTpsMonitor: sample error: " + e.getMessage());
            }
        }, SAMPLE_INTERVAL_SECONDS, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info(() -> "[OptiPortal] WorldTpsMonitor started (sampling every "
                + SAMPLE_INTERVAL_SECONDS + "s, source: HistoricMetric)");
    }

    /**
     * Sample current TPS from all ticking worlds using the server's own
     * HistoricMetric tick-length data. Reads the most recent tick duration
     * directly — no delta calculation, no jitter guard needed.
     */
    private void sampleTps() {
        Collection<World> worlds = worldRegistry.getWorlds();
        if (worlds.isEmpty()) return;

        double minTps = Double.MAX_VALUE;
        int sampledCount = 0;

        for (World world : worlds) {
            try {
                if (!world.isTicking() || world.isPaused()) continue;
                HistoricMetric tickLen = world.getBufferedTickLengthMetricSet();
                long lastTickNanos = tickLen.getLastValue();
                if (lastTickNanos <= 0) continue;
                // Convert tick duration → TPS; clamp to [0, 1.5×NOMINAL].
                double worldTps = Math.min(1_000_000_000.0 / lastTickNanos, HYTALE_TARGET_TPS * 1.5);
                worldTps = Math.max(worldTps, 0.0);
                minTps = Math.min(minTps, worldTps);
                sampledCount++;
            } catch (Exception e) {
                LOG.fine(() -> "[OptiPortal] WorldTpsMonitor: error sampling world "
                        + world.getName() + ": " + e.getMessage());
            }
        }

        if (sampledCount > 0) {
            currentTps = EMA_ALPHA * minTps + (1.0 - EMA_ALPHA) * currentTps;
            if (minTps < tpsLowThreshold) {
                final double logTps = minTps;
                LOG.fine(() -> "[OptiPortal] WorldTpsMonitor: TPS=" + String.format("%.1f", logTps)
                        + " (below low threshold " + tpsLowThreshold + ")");
            }
        }
    }

    /**
     * Get current TPS (minimum across all sampled worlds).
     * Thread-safe volatile read.
     *
     * @return current TPS, clamped to [0.0, 30.0]
     */
    public double getCurrentTps() {
        return currentTps;
    }

    /**
     * Returns true if the server is under load (TPS below low threshold).
     * Threshold defaults to {@link #TPS_LOW_THRESHOLD} but can be overridden via config.
     */
    public boolean isServerUnderLoad() {
        return currentTps < tpsLowThreshold;
    }

    /**
     * Returns a load factor between 0.0 (at or above target) and 1.0 (completely lagged).
     * Formula: max(0, (targetTps - tps) / targetTps)
     * At target TPS (20) or above → 0.0; at 10 TPS → 0.5; at 0 TPS → 1.0.
     * Returns 0.0 for servers running above 20 TPS — OptiPortal interprets any
     * reading at or above the target as "no load", not as a speed cap.
     *
     * @return load factor in [0.0, 1.0]
     */
    public double getLoadFactor() {
        return Math.max(0.0, (HYTALE_TARGET_TPS - currentTps) / HYTALE_TARGET_TPS);
    }

    /**
     * Returns true if TPS is critically low and all new chunk load operations
     * should be queued rather than executed immediately.
     * Threshold defaults to {@link #TPS_CRITICAL_THRESHOLD} but can be overridden via config.
     */
    public boolean isCriticallyLoaded() {
        return currentTps < tpsCriticalThreshold;
    }
}