package com.optiportal.cache;

import com.optiportal.metrics.MetricsCollector;
import com.optiportal.model.CacheTier;
import com.optiportal.preload.WorldRegistry;
import com.optiportal.support.FakePluginConfig;
import com.optiportal.support.RecordingScheduledExecutor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tier 1, improvement pass — CacheManager tier-decay correctness.
 *
 * Covers the three Stage-1 fixes:
 *  (S1) registry persistence round-trip (the wiring in OptiPortal now passes a real
 *       registry file; this test proves the CacheManager side of that contract);
 *  (S2) WARM-floor policy: mark holds the zone at WARM, unmark allows decay to COLD
 *       (the command layer now calls unmark on leaving WARM strategy);
 *  (S3) the removed decay fast path: a scan must run even when earliestDecayMs is a
 *       stale future deadline (old code early-returned and could skip zones forever);
 *  (S4) generation-safe expiry: a WARM→COLD cleanup captured an older generation is
 *       skipped when the zone was re-promoted, so ownership survives;
 *  (S5) batched + paced release: N expiring zones produce ONE paced task, not N.
 */
class CacheManagerTierTest {

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("optiporal-cm-tier").toFile();
    }

    private static CacheManager newManager(File dir, FakePluginConfig cfg,
                                           RecordingScheduledExecutor exec, File registry) {
        WalManager wal = new WalManager(cfg);
        return new CacheManager(cfg, wal, exec, new WorldRegistry(), registry, new MetricsCollector());
    }

    @Test
    void registryRoundTripPersistsTiersAcrossManagers() throws Exception {
        File dir = tempDir();
        FakePluginConfig cfg = new FakePluginConfig(dir);
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        File registry = new File(new File(dir, "preload-cache"), "cache-registry.json");
        registry.getParentFile().mkdirs();

        CacheManager cm1 = newManager(dir, cfg, exec, registry);
        cm1.setZoneTier("z-hot", CacheTier.HOT);
        cm1.saveRegistry();

        CacheManager cm2 = newManager(dir, cfg, new RecordingScheduledExecutor(), registry);
        cm2.loadRegistry();

        assertEquals(CacheTier.HOT, cm2.getZoneTier("z-hot"),
                "a second CacheManager with the same registry file must restore the persisted tier");
    }

    @Test
    void warmFloorHoldsZoneThenUnmarkAllowsDecayToCold() {
        FakePluginConfig cfg = new FakePluginConfig(tempDirSafe());
        cfg.setHotDecaySeconds(0);
        cfg.setWarmDecayMinutes(0);
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newManager(null, cfg, exec, null);

        cm.setZoneTier("z", CacheTier.WARM);
        cm.markWarmFloor("z");
        cm.decayTiersInternal();
        assertEquals(CacheTier.WARM, cm.getZoneTier("z"),
                "WARM floor must hold the zone at WARM (timer resets, no COLD)");

        cm.unmarkWarmFloor("z");
        cm.decayTiersInternal();
        assertEquals(CacheTier.COLD, cm.getZoneTier("z"),
                "after unmark (leaving WARM strategy), decay must be allowed to reach COLD");
        assertEquals(List.of("z"), cm.lastDecayedBatch);
    }

    @Test
    void decayRunsEvenWhenEarliestDeadlineIsInTheFuture() throws Exception {
        FakePluginConfig cfg = new FakePluginConfig(tempDirSafe());
        cfg.setHotDecaySeconds(0);
        cfg.setWarmDecayMinutes(0);
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newManager(null, cfg, exec, null);

        // Recreate the pre-fix failure mode: a stale/future deadline in earliestDecayMs
        // (what a concurrent setZoneTier left behind when the fast path was live).
        Field f = CacheManager.class.getDeclaredField("earliestDecayMs");
        f.setAccessible(true);
        ((AtomicLong) f.get(cm)).set(System.currentTimeMillis() + 60_000L);

        cm.setZoneTier("z", CacheTier.WARM); // due immediately (0 decay windows)
        cm.decayTiersInternal();

        assertEquals(CacheTier.COLD, cm.getZoneTier("z"),
                "decay must run even when earliestDecayMs is a stale future deadline "
                        + "(the removed fast path early-returned here and could skip zones forever)");
    }

    @Test
    void staleExpiryCleanupSkippedAfterRepromotion() {
        FakePluginConfig cfg = new FakePluginConfig(tempDirSafe());
        cfg.setHotDecaySeconds(0);
        cfg.setWarmDecayMinutes(0);
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newManager(null, cfg, exec, null);

        cm.registerZoneChunks("z", "w", Set.of(new long[]{1, 2}));
        cm.setZoneTier("z", CacheTier.WARM);
        cm.decayTiersInternal(); // WARM→COLD; captures generation, records the paced task
        assertEquals(1, exec.getExecutedRunnables().size(), "exactly one paced cleanup task");

        cm.setZoneTier("z", CacheTier.HOT); // re-promotion bumps the generation
        exec.runExecutedOnce(); // run the stale cleanup

        assertEquals(1, cm.getOwnedChunkCount("z"),
                "a re-promoted zone must keep its ownership — the stale expiry was skipped");
    }

    @Test
    void multiZoneExpiryBatchesIntoSinglePacedTask() {
        FakePluginConfig cfg = new FakePluginConfig(tempDirSafe());
        cfg.setHotDecaySeconds(0);
        cfg.setWarmDecayMinutes(0);
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newManager(null, cfg, exec, null);
        cm.decayReleasePacingMs = 0;

        cm.registerZoneChunks("z1", "w", Set.of(new long[]{1, 2}));
        cm.registerZoneChunks("z2", "w", Set.of(new long[]{3, 4}));
        cm.setZoneTier("z1", CacheTier.WARM);
        cm.setZoneTier("z2", CacheTier.WARM);
        cm.decayTiersInternal();

        assertEquals(2, cm.lastDecayedBatch.size());
        assertEquals(1, exec.getExecutedRunnables().size(),
                "one paced task for the whole batch, not one executor submission per zone");

        exec.runExecutedOnce();
        assertEquals(0, cm.getOwnedChunkCount("z1"), "z1 ownership released by the paced task");
        assertEquals(0, cm.getOwnedChunkCount("z2"), "z2 ownership released by the paced task");
    }

    /** tempDir() without throwing — for tests that don't need a data folder. */
    private static File tempDirSafe() {
        try {
            return tempDir();
        } catch (Exception e) {
            throw new IllegalStateException("cannot create temp dir", e);
        }
    }
}
