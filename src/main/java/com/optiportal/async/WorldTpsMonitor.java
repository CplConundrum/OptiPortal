package com.optiportal.async;

import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.hypixel.hytale.server.core.universe.world.World;
import com.optiportal.preload.WorldRegistry;

/**
 * Observes server TPS by sampling World.getTick() at a fixed interval.
 *
 * This monitor is READ-ONLY — it never controls or limits the server tick rate.
 * The server's actual TPS is determined entirely by the Hytale engine and can
 * exceed HYTALE_TARGET_TPS (e.g. during catch-up bursts). OptiPortal uses the
 * observed TPS only to throttle its own chunk-preload backpressure; it does not
 * cap, slow, or otherwise influence the engine's tick rate.
 *
 * World.getTick() returns a monotonically increasing long that increments each
 * server tick. Dividing delta-ticks / delta-seconds gives the observed TPS.
 *
 * If multiple worlds are loaded, we use the MINIMUM TPS across all ticking
 * worlds (most conservative for backpressure decisions).
 *
 * Threading: AtomicLong for tick snapshot and nanos, volatile double for
 * currentTps. All reads are safe from any thread.
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

    /** TPS below this triggers minimum batch size. */
    public static final double TPS_LOW_THRESHOLD = 18.0;

    /** TPS below this triggers queue-all mode. */
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

    /**
     * Maximum fraction by which the elapsed window may deviate from the expected interval
     * before the sample is discarded. Scheduler jitter (e.g. GC pause during the window)
     * distorts deltaTicks/elapsedSeconds — skip those windows rather than publish bad data.
     */
    private static final double MAX_JITTER_FRACTION = 0.30;

    private final WorldRegistry worldRegistry;
    private final ScheduledExecutorService executor;

    // Per-world state: we track tick counts per world name
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> lastTickCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong lastSampleNanos = new AtomicLong(0);
    private volatile double currentTps = HYTALE_TARGET_TPS;

    public WorldTpsMonitor(WorldRegistry worldRegistry, ScheduledExecutorService executor) {
        this.worldRegistry = worldRegistry;
        this.executor = executor;
    }

    /**
     * Start TPS sampling. Call once after construction.
     * Records initial tick counts so the first sample has a baseline.
     */
    public void start() {
        // Record baseline
        sampleBaseline();
        // Schedule periodic sampling
        executor.scheduleAtFixedRate(() -> {
            try {
                sampleTps();
            } catch (Exception e) {
                LOG.fine("[OptiPortal] WorldTpsMonitor: sample error: " + e.getMessage());
            }
        }, SAMPLE_INTERVAL_SECONDS, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info("[OptiPortal] WorldTpsMonitor started (sampling every "
                + SAMPLE_INTERVAL_SECONDS + "s)");
    }

    /**
     * Record baseline tick counts without computing TPS.
     * Called immediately on start() before the first scheduled sample.
     */
    private void sampleBaseline() {
        long nowNanos = System.nanoTime();
        lastSampleNanos.set(nowNanos);
        Collection<World> worlds = worldRegistry.getWorlds();
        for (World world : worlds) {
            try {
                lastTickCounts.put(world.getName(), new AtomicLong(world.getTick()));
            } catch (Exception e) {
                LOG.fine("[OptiPortal] WorldTpsMonitor: baseline error for world "
                        + world.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Sample current TPS from all ticking worlds.
     * Updates currentTps with the minimum across all sampled worlds.
     * Skipped worlds (null, paused, not ticking) are excluded.
     */
    private void sampleTps() {
        long nowNanos = System.nanoTime();
        long prevNanos = lastSampleNanos.getAndSet(nowNanos);
        double elapsedSeconds = (nowNanos - prevNanos) / 1_000_000_000.0;

        if (elapsedSeconds <= 0.0) return; // Guard against clock anomaly

        // Jitter guard: if the scheduler fired significantly late or early (e.g. GC pause
        // spanning most of the window), the deltaTicks/elapsedSeconds ratio is misleading.
        // Skip the sample and keep the last EMA value rather than publishing bad data.
        double expected = SAMPLE_INTERVAL_SECONDS;
        if (elapsedSeconds < expected * (1.0 - MAX_JITTER_FRACTION)
                || elapsedSeconds > expected * (1.0 + MAX_JITTER_FRACTION)) {
            LOG.fine("[OptiPortal] WorldTpsMonitor: skipping jittered sample (elapsed="
                    + String.format("%.3f", elapsedSeconds) + "s, expected=" + expected + "s)");
            return;
        }

        Collection<World> worlds = worldRegistry.getWorlds();
        if (worlds.isEmpty()) {
            // No worlds loaded yet — keep currentTps at nominal
            return;
        }

        double minTps = Double.MAX_VALUE;
        int sampledCount = 0;

        for (World world : worlds) {
            try {
                // Skip paused or non-ticking worlds
                if (!world.isTicking() || world.isPaused()) {
                    continue;
                }
                long currentTick = world.getTick();
                AtomicLong lastTick = lastTickCounts.computeIfAbsent(
                        world.getName(), k -> new AtomicLong(currentTick));
                long prevTick = lastTick.getAndSet(currentTick);
                long deltaTicks = currentTick - prevTick;

                if (deltaTicks < 0) {
                    // Counter wrapped or world restarted — skip this sample
                    continue;
                }

                double worldTps = deltaTicks / elapsedSeconds;
                // Clamp to [0, 1.5×NOMINAL] — cap clearly-bogus spikes (e.g. tick counter
                // reset on world load) while allowing legitimate above-20 readings.
                worldTps = Math.min(worldTps, HYTALE_TARGET_TPS * 1.5);
                worldTps = Math.max(worldTps, 0.0);

                minTps = Math.min(minTps, worldTps);
                sampledCount++;
            } catch (Exception e) {
                LOG.fine("[OptiPortal] WorldTpsMonitor: error sampling world "
                        + world.getName() + ": " + e.getMessage());
            }
        }

        if (sampledCount > 0) {
            // EMA smoothing: blend the new raw reading with the running average.
            // thenRun/thenRunAsync callbacks won't fire on failed futures so transient
            // scheduler jitter doesn't cause false backpressure decisions.
            currentTps = EMA_ALPHA * minTps + (1.0 - EMA_ALPHA) * currentTps;
            if (minTps < TPS_LOW_THRESHOLD) {
                LOG.fine("[OptiPortal] WorldTpsMonitor: TPS=" + String.format("%.1f", minTps)
                        + " (below low threshold " + TPS_LOW_THRESHOLD + ")");
            }
        }
        // If sampledCount == 0, all worlds were paused/non-ticking — keep previous value
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
     *
     * @return true if TPS < 18.0
     */
    public boolean isServerUnderLoad() {
        return currentTps < TPS_LOW_THRESHOLD;
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
     * Returns true if TPS is critically low (below 12.0) and all new chunk
     * load operations should be queued rather than executed immediately.
     *
     * @return true if TPS < 12.0
     */
    public boolean isCriticallyLoaded() {
        return currentTps < TPS_CRITICAL_THRESHOLD;
    }
}