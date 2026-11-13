package com.optiportal.cache;

import com.optiportal.metrics.MetricsCollector;
import com.optiportal.model.CacheTier;
import com.optiportal.preload.WorldRegistry;
import com.optiportal.support.FakePluginConfig;
import com.optiportal.support.RecordingScheduledExecutor;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-12 wiring - ChunkOwnershipAuditor (item 1, goldfish D1 ruling: ON by default).
 *
 * The auditor's production snapshot source (WorldRegistry -> live ChunkStore views) is
 * replaced by a fake source so the tests run without a booted World. The observation-
 * to-apply gap (F4) is made deterministic with latched re-reads: the auditor re-reads
 * the loaded-index view at apply time, and the latch lets a concurrent registration or
 * reload land exactly in that gap.
 */
class ChunkOwnershipAuditorTest {

    private static long idx(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL); // ChunkUtil.indexChunk encoding
    }

    /** Plain snapshot source: a fixed loaded-index set. */
    static final class StaticSnapshots implements ChunkOwnershipAuditor.WorldSnapshotSource {
        final LongSet loaded = new LongOpenHashSet();

        @Override public List<String> worldNames() {
            return List.of("w");
        }

        @Override public LongSet loadedIndexes(String worldName) {
            return loaded;
        }
    }

    /**
     * Latched snapshot source: the observation read returns immediately; the apply-time
     * re-read blocks on {@code readGate} until the test releases it - a deterministic
     * observation-to-apply window.
     */
    static final class LatchSnapshots implements ChunkOwnershipAuditor.WorldSnapshotSource {
        final LongSet loaded = new LongOpenHashSet();
        final CountDownLatch readGate = new CountDownLatch(1);
        final CountDownLatch applyReadStarted = new CountDownLatch(1);
        private boolean firstCall = true;

        @Override public List<String> worldNames() {
            return List.of("w");
        }

        @Override public LongSet loadedIndexes(String worldName) {
            if (firstCall) {
                firstCall = false;
                return loaded; // observation read
            }
            applyReadStarted.countDown(); // apply-time re-read - open the gap window
            try {
                readGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return loaded;
        }
    }

    private static CacheManager newCacheManager(FakePluginConfig cfg, RecordingScheduledExecutor exec) {
        WalManager wal = new WalManager(cfg);
        return new CacheManager(cfg, wal, exec, new WorldRegistry(), null, new MetricsCollector());
    }

    private static ChunkOwnershipAuditor newAuditor(CacheManager cm, FakePluginConfig cfg,
                                                    RecordingScheduledExecutor exec,
                                                    ChunkOwnershipAuditor.WorldSnapshotSource snapshots) {
        return new ChunkOwnershipAuditor(cm, exec, cfg, snapshots);
    }

    @Test
    void cleanAuditChangesNothing() throws Exception {
        FakePluginConfig cfg = new FakePluginConfig(Files.createTempDirectory("optiporal-auditor").toFile());
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newCacheManager(cfg, exec);
        StaticSnapshots snap = new StaticSnapshots();
        cm.setZoneTier("z1", CacheTier.WARM);
        cm.registerOwnershipBatch("z1", "w", List.of(new int[]{1, 1}, new int[]{2, 2}));
        snap.loaded.add(idx(1, 1));
        snap.loaded.add(idx(2, 2));

        // The apply phase checks the active fence - a pass that never ran start()
        // would be a no-op, so exercise the fence (the 3 gap tests above do the same).
        ChunkOwnershipAuditor auditor = newAuditor(cm, cfg, exec, snap);
        auditor.start();
        auditor.runAudit();

        assertEquals(java.util.Set.of("z1"), cm.getChunkOwners("w", 1, 1), "owned + loaded chunk untouched");
        assertEquals(java.util.Set.of("z1"), cm.getChunkOwners("w", 2, 2), "owned + loaded chunk untouched");
        assertEquals(CacheTier.WARM, cm.getZoneTier("z1"), "a clean audit never touches the tier");
    }

    @Test
    void evictedChunkOwnershipIsRepaired() throws Exception {
        FakePluginConfig cfg = new FakePluginConfig(Files.createTempDirectory("optiporal-auditor").toFile());
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newCacheManager(cfg, exec);
        StaticSnapshots snap = new StaticSnapshots();
        cm.setZoneTier("z1", CacheTier.WARM);
        cm.registerOwnershipBatch("z1", "w", List.of(new int[]{1, 1}, new int[]{2, 2}));
        snap.loaded.add(idx(1, 1)); // (2,2) missing = engine evicted it without telling OptiPortal

        ChunkOwnershipAuditor auditor = newAuditor(cm, cfg, exec, snap);
        auditor.start(); // sets the active fence the apply phase checks
        auditor.runAudit();

        assertTrue(cm.getChunkOwners("w", 2, 2).isEmpty(),
                "the evicted chunk's ownership is repaired (removed) by the audit");
        assertEquals(java.util.Set.of("z1"), cm.getChunkOwners("w", 1, 1), "the loaded chunk keeps its owner");
        assertEquals(CacheTier.COLD, cm.getZoneTier("z1"),
                "the eviction downgrade (WARM -> COLD, no warm floor) is part of the repair");
    }

    @Test
    void chunkReloadedInTheGapIsNotReEvicted() throws Exception {
        FakePluginConfig cfg = new FakePluginConfig(Files.createTempDirectory("optiporal-auditor").toFile());
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newCacheManager(cfg, exec);
        LatchSnapshots snap = new LatchSnapshots();
        cm.setZoneTier("z1", CacheTier.WARM);
        cm.registerOwnershipBatch("z1", "w", List.of(new int[]{1, 1}, new int[]{2, 2}));
        snap.loaded.add(idx(1, 1));

        ChunkOwnershipAuditor auditor = newAuditor(cm, cfg, exec, snap);
        auditor.start(); // active fence for the apply phase
        Thread audit = new Thread(auditor::runAudit);
        audit.start();
        assertTrue(snap.applyReadStarted.await(5, TimeUnit.SECONDS), "audit reached the apply-time re-read");
        snap.loaded.add(idx(2, 2)); // (2,2) reloaded in the gap
        snap.readGate.countDown();
        audit.join(5000);

        assertEquals(java.util.Set.of("z1"), cm.getChunkOwners("w", 2, 2),
                "F4(a): a chunk reloaded in the gap is NOT re-evicted by the stale observation");
    }

    @Test
    void ownershipMovedInTheGapIsNotErased() throws Exception {
        FakePluginConfig cfg = new FakePluginConfig(Files.createTempDirectory("optiporal-auditor").toFile());
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newCacheManager(cfg, exec);
        LatchSnapshots snap = new LatchSnapshots();
        cm.setZoneTier("z1", CacheTier.WARM);
        cm.registerOwnershipBatch("z1", "w", List.of(new int[]{1, 1}, new int[]{2, 2}));
        snap.loaded.add(idx(1, 1)); // (2,2) missing at observation time

        ChunkOwnershipAuditor auditor = newAuditor(cm, cfg, exec, snap);
        auditor.start(); // active fence for the apply phase
        Thread audit = new Thread(auditor::runAudit);
        audit.start();
        assertTrue(snap.applyReadStarted.await(5, TimeUnit.SECONDS), "audit reached the apply-time re-read");
        cm.registerOwnershipBatch("z2", "w", List.of(new int[]{2, 2})); // ownership moved in the gap
        snap.readGate.countDown();
        audit.join(5000);

        assertEquals(java.util.Set.of("z1", "z2"), cm.getChunkOwners("w", 2, 2),
                "F4(b): a coord whose ownership changed in the gap is left alone (both owners survive)");
    }

    @Test
    void allEvictedLooksLikeAWorldUnloadAndIsSkipped() throws Exception {
        FakePluginConfig cfg = new FakePluginConfig(Files.createTempDirectory("optiporal-auditor").toFile());
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newCacheManager(cfg, exec);
        StaticSnapshots snap = new StaticSnapshots(); // nothing loaded
        cm.setZoneTier("z1", CacheTier.WARM);
        cm.registerOwnershipBatch("z1", "w", List.of(new int[]{1, 1}, new int[]{2, 2}));

        // The apply phase checks the active fence - a pass that never ran start()
        // would be a no-op, so exercise the fence (the 3 gap tests above do the same).
        ChunkOwnershipAuditor auditor = newAuditor(cm, cfg, exec, snap);
        auditor.start();
        auditor.runAudit();

        assertEquals(java.util.Set.of("z1"), cm.getChunkOwners("w", 1, 1),
                "100% missing owned chunks read as a world unload - nothing is applied");
        assertEquals(java.util.Set.of("z1"), cm.getChunkOwners("w", 2, 2));
        assertEquals(CacheTier.WARM, cm.getZoneTier("z1"), "an unload-looking audit never downgrades tiers");
    }

    @Test
    void stopFencesAnInFlightPass() throws Exception {
        FakePluginConfig cfg = new FakePluginConfig(Files.createTempDirectory("optiporal-auditor").toFile());
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newCacheManager(cfg, exec);
        LatchSnapshots snap = new LatchSnapshots();
        cm.setZoneTier("z1", CacheTier.WARM);
        cm.registerOwnershipBatch("z1", "w", List.of(new int[]{1, 1}, new int[]{2, 2}));
        snap.loaded.add(idx(1, 1));

        ChunkOwnershipAuditor auditor = newAuditor(cm, cfg, exec, snap);
        auditor.start(); // active fence for the apply phase (stop() below fences it)
        Thread audit = new Thread(auditor::runAudit);
        audit.start();
        assertTrue(snap.applyReadStarted.await(5, TimeUnit.SECONDS), "audit reached the apply-time re-read");
        auditor.stop(); // I1.1: fence the in-flight pass before it applies anything
        snap.readGate.countDown();
        audit.join(5000);

        assertEquals(java.util.Set.of("z1"), cm.getChunkOwners("w", 2, 2),
                "a stopped auditor applies no mutations (the pass is fenced at the apply phase)");
    }

    @Test
    void schedulingRecordsFixedDelayAndCancelsOnStop() throws Exception {
        FakePluginConfig cfg = new FakePluginConfig(Files.createTempDirectory("optiporal-auditor").toFile());
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = newCacheManager(cfg, exec);
        StaticSnapshots snap = new StaticSnapshots();
        ChunkOwnershipAuditor auditor = newAuditor(cm, cfg, exec, snap);

        int before = exec.scheduledRecords.size(); // the cache manager's decay timer is a separate fixed-RATE record
        auditor.start();
        assertEquals(before + 1, exec.scheduledRecords.size(), "start() schedules exactly one fixed-delay task");
        RecordingScheduledExecutor.ScheduledRecord record = exec.scheduledRecords.get(exec.scheduledRecords.size() - 1);
        assertEquals("fixedDelay", record.kind, "fixed delay: a slow audit can never overlap the next one");
        assertEquals(1, record.initialDelay, "one-minute initial delay");
        assertEquals(cfg.getOwnershipAuditIntervalMinutes(), record.period, "period = the config interval");

        auditor.start(); // double start is a no-op (armed guard)
        assertEquals(before + 1, exec.scheduledRecords.size(), "a second start() does not schedule a second task");

        auditor.stop();
        assertTrue(record.isCancelled(), "stop() cancels the scheduled task");
    }
}
