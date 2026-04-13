package com.optiportal.preload;

import com.optiportal.metrics.MetricsCollector;
import com.optiportal.support.FakePluginConfig;
import com.optiportal.support.FakeStorageBackend;
import com.optiportal.support.RecordingScheduledExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for ChunkPreloader adaptive rebudgeting.
 *
 * All tests bypass the pressure-check path (maxLoadedChunksPressureThreshold=0)
 * so that no real World or ChunkStore is needed.  Only the latency-driven
 * budgeting path is exercised here.
 *
 * Thresholds (from ChunkPreloader):
 *   HIGH_BATCH_LATENCY_MS     = 100 ms → multiplier 0.5  (batch), 2.0  (delay)
 *   CRITICAL_BATCH_LATENCY_MS = 250 ms → multiplier 0.25 (batch), 4.0  (delay)
 */
class ChunkPreloaderAdaptiveBudgetingTest {

    private static final double HIGH_LATENCY_MS     = 100.0;
    private static final double CRITICAL_LATENCY_MS = 250.0;

    @TempDir File tempDir;

    private FakePluginConfig config;
    private ChunkPreloader preloader;

    @BeforeEach
    void setUp() throws Exception {
        config = new FakePluginConfig(tempDir);
        // pressureThreshold = 0 → pressure checks are skipped, no World needed
        // warmBatchSize defaults from parent PluginConfig (10) — adequate for testing
        preloader = new ChunkPreloader(
                config,
                null,                       // CacheManager — not used by budgeting path
                new WorldRegistry(),        // empty registry → getWorld() returns null
                new RecordingScheduledExecutor(),
                new FakeStorageBackend(),
                new MetricsCollector()
        );
    }

    // -------------------------------------------------------------------------
    // effectiveBatchSettings are world-scoped
    // -------------------------------------------------------------------------

    /**
     * Degraded latency for one world must not throttle batch settings for another world.
     * This protects the earlier bug where latency tracking was global.
     */
    @Test
    void effectiveBatchSettings_areWorldScoped() {
        preloader.injectBatchLatencyForTest("overloaded-world", CRITICAL_LATENCY_MS);
        // "healthy-world" has no latency entry → default 0 → full batch size

        int overloadedBatch = preloader.getEffectiveBatchSize("overloaded-world");
        int healthyBatch    = preloader.getEffectiveBatchSize("healthy-world");

        assertTrue(overloadedBatch < healthyBatch,
                "Critical latency in one world must not degrade settings for another world");

        // Verify delay also diverges
        int overloadedDelay = preloader.getEffectiveBatchDelay("overloaded-world");
        int healthyDelay    = preloader.getEffectiveBatchDelay("healthy-world");

        assertTrue(overloadedDelay > healthyDelay,
                "Critical latency must increase the batch delay for that world only");
    }

    // -------------------------------------------------------------------------
    // batchSettings recompute between batches
    // -------------------------------------------------------------------------

    /**
     * Injecting a worse latency between batches must produce tighter settings on
     * the next effective-batch calculation — the budgeting must re-evaluate each call.
     */
    @Test
    void batchSettings_recomputeBetweenBatches() {
        String world = "dynamic-world";

        // Healthy conditions
        preloader.injectBatchLatencyForTest(world, 10.0);
        int batchSizeHealthy = preloader.getEffectiveBatchSize(world);
        int batchDelayHealthy = preloader.getEffectiveBatchDelay(world);

        // Degrade to HIGH latency
        preloader.injectBatchLatencyForTest(world, HIGH_LATENCY_MS);
        int batchSizeHigh = preloader.getEffectiveBatchSize(world);
        int batchDelayHigh = preloader.getEffectiveBatchDelay(world);

        // Degrade to CRITICAL latency
        preloader.injectBatchLatencyForTest(world, CRITICAL_LATENCY_MS);
        int batchSizeCritical  = preloader.getEffectiveBatchSize(world);
        int batchDelayCritical = preloader.getEffectiveBatchDelay(world);

        // Batch size must shrink as latency worsens
        assertTrue(batchSizeHigh <= batchSizeHealthy,
                "HIGH latency must reduce batch size vs healthy");
        assertTrue(batchSizeCritical <= batchSizeHigh,
                "CRITICAL latency must further reduce batch size vs HIGH");

        // Delay must grow as latency worsens
        assertTrue(batchDelayHigh >= batchDelayHealthy,
                "HIGH latency must increase batch delay vs healthy");
        assertTrue(batchDelayCritical >= batchDelayHigh,
                "CRITICAL latency must further increase batch delay vs HIGH");
    }

    // -------------------------------------------------------------------------
    // adaptiveBudgeting never exceeds configured bounds
    // -------------------------------------------------------------------------

    /**
     * Even under extreme latency / pressure values, batch size must stay within
     * [1, configuredBatchSize] and delay must be non-negative.
     */
    @Test
    void adaptiveBudgeting_neverExceedsConfiguredBounds() {
        String world = "extreme-world";
        int configuredBatchSize = config.getWarmBatchSize();

        // Feed extreme latency (well above CRITICAL threshold)
        preloader.injectBatchLatencyForTest(world, 9999.0);

        int effectiveBatch = preloader.getEffectiveBatchSize(world);
        int effectiveDelay = preloader.getEffectiveBatchDelay(world);

        assertTrue(effectiveBatch >= 1,
                "Effective batch size must never drop below 1");
        assertTrue(effectiveBatch <= configuredBatchSize,
                "Effective batch size must never exceed the configured warmBatchSize");
        assertTrue(effectiveDelay >= 0,
                "Effective batch delay must never be negative");
    }

    /**
     * With healthy latency (below HIGH threshold), the batch size must equal the
     * configured base — no throttling should be applied.
     */
    @Test
    void adaptiveBudgeting_healthyLatency_usesFullBatchSize() {
        String world = "healthy-world";
        int configuredBatchSize = config.getWarmBatchSize();

        preloader.injectBatchLatencyForTest(world, 10.0); // well below HIGH_LATENCY_MS=100

        int effectiveBatch = preloader.getEffectiveBatchSize(world);

        assertEquals(configuredBatchSize, effectiveBatch,
                "Under healthy latency conditions, full configured batch size must be used");
    }
}
