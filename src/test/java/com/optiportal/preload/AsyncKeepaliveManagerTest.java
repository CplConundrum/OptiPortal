package com.optiportal.preload;

import com.optiportal.async.AsyncErrorHandler;
import com.optiportal.async.AsyncLoadBalancer;
import com.optiportal.async.AsyncMetrics;
import com.optiportal.async.CircuitBreaker;
import com.optiportal.async.WorldTpsMonitor;
import com.optiportal.cache.CacheManager;
import com.optiportal.cache.WalManager;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.support.FakePluginConfig;
import com.optiportal.support.FakeStorageBackend;
import com.optiportal.support.RecordingScheduledExecutor;
import com.optiportal.storage.StorageBackend;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-12 wiring - AsyncKeepaliveManager (item 2, default keepalive.mode=async).
 *
 * The engine seam (per-world ChunkStore pings) is the protected {@code chunkSurface}
 * override: a fake surface stands in for the booted-World path, so the tests run in a
 * plain JVM. Tier passes execute synchronously through the load balancer's
 * immediate-execution path; the F2 stale-generation test uses the balancer's
 * critical-TPS queueing path (a critical WorldTpsMonitor) so a batch is genuinely
 * queued - and provably no-ops after stop() bumps the lifecycle epoch.
 */
class AsyncKeepaliveManagerTest {

    private static long idx(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL); // ChunkUtil.indexChunk encoding
    }

    private static PortalEntry zone(String id, int blockX, int blockZ, int radius) {
        PortalEntry e = new PortalEntry();
        e.setId(id);
        e.setWorld("w1");
        e.setX(blockX);
        e.setZ(blockZ);
        e.setWarmRadiusX(radius);
        e.setWarmRadiusZ(radius);
        return e;
    }

    /** Per-world chunk residency + ping seam (no booted World needed). */
    static final class FakeSurface implements AsyncKeepaliveManager.ChunkSurface {
        final Set<Long> resident;
        final Set<Long> pinged = ConcurrentHashMap.newKeySet();
        volatile boolean failPings;

        FakeSurface(Set<Long> resident) {
            this.resident = resident;
        }

        @Override public boolean isResident(long engineIndex) {
            return resident.contains(engineIndex);
        }

        @Override public CompletableFuture<Void> ping(long engineIndex) {
            pinged.add(engineIndex);
            return failPings
                    ? CompletableFuture.failedFuture(new IllegalStateException("simulated chunk failure"))
                    : CompletableFuture.completedFuture(null);
        }
    }

    static final class TestKeepalive extends AsyncKeepaliveManager {
        final FakeSurface surface;

        TestKeepalive(com.optiportal.config.PluginConfig config, CacheManager cacheManager,
                      ChunkPreloader chunkPreloader, StorageBackend storage,
                      ScheduledExecutorService executor, AsyncLoadBalancer loadBalancer,
                      AsyncMetrics metrics, FakeSurface surface) {
            super(config, cacheManager, chunkPreloader, storage, executor, loadBalancer, metrics);
            this.surface = surface;
        }

        @Override protected ChunkSurface chunkSurface(String worldName) {
            return surface;
        }
    }

    /** Shared fixture: everything the manager needs, minus a booted World. */
    static final class Fixture {
        final FakePluginConfig cfg;
        final RecordingScheduledExecutor exec;
        final CacheManager cm;
        final FakeStorageBackend storage;
        final ChunkPreloader preloader;
        final AsyncMetrics metrics;
        final FakeSurface surface;
        final TestKeepalive ak;

        Fixture(Set<Long> resident) throws Exception {
            var dir = Files.createTempDirectory("optiporal-ak").toFile();
            cfg = new FakePluginConfig(dir);
            exec = new RecordingScheduledExecutor();
            cm = new CacheManager(cfg, new WalManager(cfg), exec, new WorldRegistry(), null, new MetricsCollector());
            storage = new FakeStorageBackend();
            preloader = new ChunkPreloader(cfg, cm, new WorldRegistry(), exec, storage, new MetricsCollector());
            metrics = new AsyncMetrics();
            surface = new FakeSurface(resident);
            ak = new TestKeepalive(cfg, cm, preloader, storage, exec,
                    new AsyncLoadBalancer(exec, metrics, new AsyncErrorHandler(metrics, new CircuitBreaker(), exec)),
                    metrics, surface);
        }
    }

    @Test
    void hotPassDemotesAllOverlappingZones() throws Exception {
        // z1 at chunk (0,0), z2 at chunk (1,0): radii of 1 overlap on the shared chunks.
        // Every chunk is evicted (empty resident set) and NO zone owns any chunk, so the
        // pass must still demote BOTH zones (I2.2: all-overlap demotion, deduped).
        Fixture fx = new Fixture(Set.of());
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.storage.save(zone("z2", 16, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.setZoneTier("z2", CacheTier.HOT);

        fx.ak.pingTier(CacheTier.HOT, "test");

        assertEquals(CacheTier.WARM, fx.cm.getZoneTier("z1"), "overlapping HOT zone z1 demoted");
        assertEquals(CacheTier.WARM, fx.cm.getZoneTier("z2"), "overlapping HOT zone z2 demoted too (all-overlap)");
        assertTrue(fx.surface.pinged.isEmpty(), "unowned chunks are never pinged (3-tier model owns resurrection)");
    }

    @Test
    void ownedEvictedChunkIsRecoveredAndReRegistered() throws Exception {
        Fixture fx = new Fixture(Set.of()); // chunk (0,0) evicted
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0}));
        // The engine evicted (0,0) while the ownership map stayed intact (the Fixture's
        // resident set is empty - no audit pass ran yet). F3 must re-request the chunk
        // and idempotently re-register its ownership. (An auditor-stripped chunk is
        // indistinguishable from unowned geometry by design - keepalive never
        // resurrects unowned chunks; the 3-tier model owns that decision.)
        assertEquals(Set.of("z1"), fx.cm.getChunkOwners("w1", 0, 0), "precondition: ownership intact");

        fx.ak.pingTier(CacheTier.HOT, "test");

        assertTrue(fx.surface.pinged.contains(idx(0, 0)), "the owned evicted chunk is re-pinged (F3 recovery)");
        assertEquals(Set.of("z1"), fx.cm.getChunkOwners("w1", 0, 0), "F3: ownership is re-registered after the ping lands");
    }

    @Test
    void unownedEvictedChunkIsNeverResurrected() throws Exception {
        Fixture fx = new Fixture(Set.of());
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.storage.save(zone("z2", 160, 160, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.setZoneTier("z2", CacheTier.HOT);
        // No registerOwnershipBatch call: nothing owns anything.

        fx.ak.pingTier(CacheTier.HOT, "test");

        assertTrue(fx.surface.pinged.isEmpty(),
                "an unowned chunk is COLD/unowned geometry - keepalive must never resurrect it");
    }

    @Test
    void fullyFailedTierRecordsKeepaliveError() throws Exception {
        Fixture fx = new Fixture(Set.of(idx(0, 0))); // the zone's chunk is resident
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.WARM);
        fx.surface.failPings = true;

        fx.ak.pingTier(CacheTier.WARM, "test");

        assertTrue(fx.metrics.getKeepaliveErrorCount() >= 1,
                "F7: a fully-failed tier ping is a keepalive error, not a silent success");
    }

    @Test
    void defaultSupplierSeesLateSavedZones() throws Exception {
        // F6: no setPortalCacheSupplier call - the manager must read a FRESH storage
        // snapshot per pass (a zone saved after manager construction is still seen).
        Fixture fx = new Fixture(Set.of(idx(0, 0)));
        fx.storage.save(zone("z1", 0, 0, 1)); // saved AFTER the manager was constructed
        fx.cm.setZoneTier("z1", CacheTier.HOT);

        fx.ak.pingTier(CacheTier.HOT, "test");

        assertTrue(fx.surface.pinged.contains(idx(0, 0)),
                "the default supplier is storage::loadAllCached - a live snapshot, not a construction-time one");
    }

    @Test
    void stopInvalidatesQueuedBatchesFromStaleGeneration() throws Exception {
        // F2: under critical TPS the balancer QUEUES the tier batch instead of running it
        // synchronously. stop() bumps the lifecycle epoch; a batch captured before the
        // stop must no-op when it finally executes, while a fresh-generation batch runs.
        var dir = Files.createTempDirectory("optiporal-ak").toFile();
        FakePluginConfig cfg = new FakePluginConfig(dir);
        RecordingScheduledExecutor exec = new RecordingScheduledExecutor();
        CacheManager cm = new CacheManager(cfg, new WalManager(cfg), exec, new WorldRegistry(), null, new MetricsCollector());
        FakeStorageBackend storage = new FakeStorageBackend();
        ChunkPreloader preloader = new ChunkPreloader(cfg, cm, new WorldRegistry(), exec, storage, new MetricsCollector());
        AsyncMetrics metrics = new AsyncMetrics();
        FakeSurface surface = new FakeSurface(Set.of(idx(0, 0)));
        WorldTpsMonitor monitor = new WorldTpsMonitor(new WorldRegistry(), exec);
        setTps(monitor, 1.0); // critically loaded (target is ~20 TPS)
        AsyncLoadBalancer lb = new AsyncLoadBalancer(exec, metrics,
                new AsyncErrorHandler(metrics, new CircuitBreaker(), exec), monitor);
        TestKeepalive ak = new TestKeepalive(cfg, cm, preloader, storage, exec, lb, metrics, surface);

        storage.save(zone("z1", 0, 0, 1));
        cm.setZoneTier("z1", CacheTier.WARM);

        ak.pingTier(CacheTier.WARM, "stale"); // critical TPS -> the batch is QUEUED, not executed
        assertTrue(surface.pinged.isEmpty(), "precondition: the batch stayed queued under critical TPS");

        ak.stop(); // lifecycle epoch 1 -> 2

        @SuppressWarnings("unchecked")
        DequeSupplier deque = peekQueuedNormalBatches(lb);
        assertTrue(!deque.isEmpty(), "the queued batch is visible in the balancer's pending queue");
        CompletableFuture<Void> staleResult = deque.pollFirst().get();
        staleResult.join();
        assertTrue(surface.pinged.isEmpty(),
                "F2: the queued batch captured under the previous generation is a no-op after stop()");

        ak.pingTier(CacheTier.WARM, "fresh"); // epoch 2 - captured fresh
        setTps(monitor, 20.0); // load clears (the queued batch can now execute)
        deque = peekQueuedNormalBatches(lb);
        assertTrue(!deque.isEmpty(), "the fresh-generation batch is queued");
        CompletableFuture<Void> freshResult = deque.pollFirst().get();
        freshResult.join();
        assertTrue(surface.pinged.contains(idx(0, 0)),
                "a fresh-generation batch still executes after the stop/restart cycle");
    }

    private static DequeSupplier peekQueuedNormalBatches(AsyncLoadBalancer lb) throws Exception {
        Field f = AsyncLoadBalancer.class.getDeclaredField("pendingTasks");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<AsyncMetrics.AsyncTaskPriority, java.util.Deque<Supplier<CompletableFuture<Void>>>> pending =
                (Map<AsyncMetrics.AsyncTaskPriority, java.util.Deque<Supplier<CompletableFuture<Void>>>>) f.get(lb);
        java.util.Deque<Supplier<CompletableFuture<Void>>> deque = pending.get(AsyncMetrics.AsyncTaskPriority.NORMAL);
        assertTrue(deque != null, "the NORMAL-priority queue exists");
        return new DequeSupplier(deque);
    }

    private record DequeSupplier(java.util.Deque<Supplier<CompletableFuture<Void>>> deque) {
        boolean isEmpty() { return deque.isEmpty(); }
        Supplier<CompletableFuture<Void>> pollFirst() { return deque.pollFirst(); }
    }

    private static void setTps(WorldTpsMonitor monitor, double tps) throws Exception {
        Field f = WorldTpsMonitor.class.getDeclaredField("currentTps");
        f.setAccessible(true);
        f.setDouble(monitor, tps);
    }

    @Test
    void startSchedulesTierTicksAndStopCancelsThem() throws Exception {
        Fixture fx = new Fixture(Set.of());
        int before = fx.exec.scheduledRecords.size(); // the cache manager's decay timer is a separate fixed-RATE record
        fx.ak.start();
        List<RecordingScheduledExecutor.ScheduledRecord> akRecords =
                new java.util.ArrayList<>(fx.exec.scheduledRecords.subList(before, fx.exec.scheduledRecords.size()));
        assertFalse(akRecords.isEmpty(), "start() schedules the tier ticks");
        assertTrue(akRecords.stream().anyMatch(r -> r.period > 0),
                "start() schedules at least one tier tick with a positive period");

        fx.ak.stop();
        assertTrue(akRecords.stream().allMatch(RecordingScheduledExecutor.ScheduledRecord::isCancelled),
                "stop() cancels every keepalive-scheduled tick (the cache manager's decay timer belongs to the cache manager)");
    }
}
