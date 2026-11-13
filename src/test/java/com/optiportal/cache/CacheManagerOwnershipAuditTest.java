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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-12 wiring - CacheManager accessors and the F5 release path that the
 * chunk-ownership auditor and the async keepalive manager build on:
 *
 *  (a) getOwnedChunkKeys / getChunkOwners / getZoneGeneration (auditor I2.1 +
 *      keepalive F3 revalidation);
 *  (b) the F5 goldfish fix: an audit-driven WARM -> COLD transition submits a
 *      generation-safe cleanup task, and the task skips its release when the zone
 *      was re-promoted in the gap (the stale release must not strip current
 *      ownership).
 */
class CacheManagerOwnershipAuditTest {

    private static CacheManager newManager(File dir, FakePluginConfig cfg, RecordingScheduledExecutor exec) {
        WalManager wal = new WalManager(cfg);
        return new CacheManager(cfg, wal, exec, new WorldRegistry(), null, new MetricsCollector());
    }

    private static FakePluginConfig newConfig() throws Exception {
        return new FakePluginConfig(Files.createTempDirectory("optiporal-cm-audit").toFile());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Set<Long>>> zoneToChunks(CacheManager cm) throws Exception {
        Field f = CacheManager.class.getDeclaredField("zoneToChunks");
        f.setAccessible(true);
        return (Map<String, Map<String, Set<Long>>>) f.get(cm);
    }

    @Test
    void ownershipAccessorsReflectRegistration() throws Exception {
        FakePluginConfig cfg = newConfig();
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newManager(null, cfg, exec);

        assertTrue(cm.getOwnedChunkKeys("w").isEmpty(), "no world bucket yet");
        assertTrue(cm.getChunkOwners("w", 3, -7).isEmpty(), "no owner for an unowned chunk");

        cm.registerOwnershipBatch("z1", "w", List.of(new int[]{3, -7}, new int[]{3, -6}));

        assertEquals(2, cm.getOwnedChunkKeys("w").size(), "both registered chunks owned");
        assertEquals(Set.of("z1"), cm.getChunkOwners("w", 3, -7), "live owner view for a chunk");
        assertEquals(Set.of("z1"), cm.getChunkOwners("w", 3, -6), "second chunk owner view");
        assertTrue(cm.getChunkOwners("w", 9, 9).isEmpty(), "unowned chunk has no owners");
    }

    @Test
    void zoneGenerationBumpsOnPromotion() throws Exception {
        FakePluginConfig cfg = newConfig();
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newManager(null, cfg, exec);

        assertEquals(0L, cm.getZoneGeneration("z"), "unknown zone reports generation 0");
        cm.setZoneTier("z", CacheTier.WARM);
        long afterFirstPromotion = cm.getZoneGeneration("z");
        assertEquals(1L, afterFirstPromotion, "a WARM promotion bumps the generation once");

        cm.setZoneTier("z", CacheTier.COLD);
        assertEquals(afterFirstPromotion, cm.getZoneGeneration("z"), "a COLD write does not bump");
        cm.setZoneTier("z", CacheTier.WARM);
        assertTrue(cm.getZoneGeneration("z") > afterFirstPromotion,
                "re-promotion bumps the generation (the generation-safe fence anchor)");
    }

    @Test
    void staleCleanupSkipsRePromotedZoneButReleasesUnpromotedOne() throws Exception {
        FakePluginConfig cfg = newConfig();
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newManager(null, cfg, exec);

        long key11 = 1L | (1L << 32); // packChunkIndex(1, 1)

        // z-keep: one chunk evicted, one kept. WARM -> COLD + ONE F5 task.
        cm.setZoneTier("z-keep", CacheTier.WARM);
        cm.registerOwnershipBatch("z-keep", "w", List.of(new int[]{0, 0}, new int[]{1, 1}));
        int before = exec.getExecutedRunnables().size();
        cm.onChunksEvicted("w", List.of(new int[]{0, 0}));
        assertEquals(CacheTier.COLD, cm.getZoneTier("z-keep"), "eviction downgrades WARM -> COLD (no warm floor)");
        assertEquals(before + 1, exec.getExecutedRunnables().size(), "exactly one generation-safe cleanup task submitted");

        // Re-promote in the gap: the stale cleanup must skip, keeping the surviving ownership.
        cm.setZoneTier("z-keep", CacheTier.WARM);
        for (int i = before; i < exec.getExecutedRunnables().size(); i++) {
            exec.getExecutedRunnables().get(i).run();
        }
        Map<String, Map<String, Set<Long>>> ztc = zoneToChunks(cm);
        Set<Long> kept = ztc.get("z-keep") == null ? null : ztc.get("z-keep").get("w");
        assertEquals(Set.of(key11), kept,
                "re-promoted zone survives the stale cleanup - its remaining ownership is intact");

        // z-clean: same shape, but NOT re-promoted - the cleanup runs and wipes the reverse index.
        cm.setZoneTier("z-clean", CacheTier.WARM);
        cm.registerOwnershipBatch("z-clean", "w", List.of(new int[]{2, 2}, new int[]{3, 3}));
        cm.onChunksEvicted("w", List.of(new int[]{2, 2}, new int[]{3, 3}));
        for (Runnable task : exec.getExecutedRunnables().subList(before + 1, exec.getExecutedRunnables().size())) {
            task.run();
        }
        ztc = zoneToChunks(cm);
        assertTrue(ztc.get("z-clean") == null,
                "un-promoted zone: deregisterAllChunks wiped the reverse index (no dangling ownership/pins)");
        assertEquals(CacheTier.COLD, cm.getZoneTier("z-clean"), "the cleanup task never re-promotes the zone");
    }

    @Test
    void unpromotedZoneGetsItsReverseIndexWipedByTheCleanupTask() throws Exception {
        FakePluginConfig cfg = newConfig();
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newManager(null, cfg, exec);

        cm.setZoneTier("z-clean", CacheTier.WARM);
        cm.registerOwnershipBatch("z-clean", "w", List.of(new int[]{0, 0}, new int[]{1, 1}));
        cm.onChunksEvicted("w", List.of(new int[]{0, 0}, new int[]{1, 1}));
        assertEquals(CacheTier.COLD, cm.getZoneTier("z-clean"));

        // No re-promotion: the cleanup task runs its release.
        for (Runnable task : exec.getExecutedRunnables()) {
            task.run();
        }
        Map<String, Map<String, Set<Long>>> ztc = zoneToChunks(cm);
        assertTrue(ztc.get("z-clean") == null,
                "deregisterAllChunks wiped the zone's reverse index (no dangling ownership/pins)");
        assertEquals(CacheTier.COLD, cm.getZoneTier("z-clean"), "the task never re-promotes the zone");
    }
}
