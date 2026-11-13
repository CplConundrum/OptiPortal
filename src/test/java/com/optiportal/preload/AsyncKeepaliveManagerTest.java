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
import java.util.ArrayList;
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

        // Latch mode: ping() returns a future held until releaseLatch() - lets a test
        // interleave work (a late registration, an audit strip) in the gap between
        // "chunk observed missing" and "reload landed".
        volatile CompletableFuture<Void> latch;

        void holdNextPing() {
            latch = new CompletableFuture<>();
        }

        void releaseLatch() {
            CompletableFuture<Void> l = latch;
            latch = null;
            if (l != null) l.complete(null);
        }

        @Override public CompletableFuture<Void> ping(long engineIndex) {
            pinged.add(engineIndex);
            CompletableFuture<Void> l = latch;
            if (l != null) return l;
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

    /**
     * Test seam: records every keep-loaded pin re-acquisition. The engine pin
     * path itself is a no-op without a booted World, so pin balance (F3
     * recovery) is only observable through this recording of
     * {@link #reacquireKeepLoadedPin} calls.
     */
    static final class RecordingCacheManager extends CacheManager {
        final List<String> pinCalls = new ArrayList<>();
        final List<String> regCalls = new ArrayList<>();
        /** Test seam for {@link #isChunkStorePresent}: a null set = production
         *  behavior (a fake WorldRegistry has no worlds, so every check is false);
         *  a set = explicit control of which chunks the store reports present. */
        volatile Set<Long> presentChunks;
        /** r4 test seam: the fake chunk instance's keep-loaded counters, keyed by
         *  packed chunk index. A removed entry = a re-published (new) instance. */
        final Map<Long, java.util.concurrent.atomic.AtomicInteger> pinCounters =
                new java.util.concurrent.ConcurrentHashMap<>();
        /** r4 test seam: records execution-time stamp writes (zone|world:cx,cz). */
        final List<String> stampCalls = new ArrayList<>();
        /** r4 test seam: world tasks held for explicit interleaving (holdWorldTasks). */
        final List<Runnable> queuedWorldTasks = new ArrayList<>();
        /** r4 test seam: hold world tasks until runQueuedWorldTasks() (queued interleavings). */
        volatile boolean holdWorldTasks;

        RecordingCacheManager(FakePluginConfig cfg, ScheduledExecutorService exec) throws Exception {
            super(cfg, new WalManager(cfg), exec, new WorldRegistry(), null, new MetricsCollector());
        }

        @Override
        public int registerOwnershipBatch(String zoneId, String world, List<int[]> chunkCoords) {
            int r = super.registerOwnershipBatch(zoneId, world, chunkCoords);
            for (int[] c : chunkCoords) {
                regCalls.add(zoneId + "|" + world + ":" + c[0] + "," + c[1] + "|" + r);
            }
            return r;
        }

        @Override
        protected boolean isChunkStorePresent(String worldName, long engineIndex) {
            Set<Long> p = presentChunks;
            return p != null && p.contains(engineIndex);
        }

        @Override
        public void reacquireKeepLoadedPin(String worldName, int cx, int cz, String zoneId) {
            pinCalls.add(worldName + ":" + cx + ":" + cz + ":" + zoneId);
            super.reacquireKeepLoadedPin(worldName, cx, cz, zoneId);
        }

        @Override
        protected void submitWorldTask(String worldName, Runnable task) {
            if (holdWorldTasks) {
                queuedWorldTasks.add(task);
                return;
            }
            task.run();
        }

        @Override
        protected boolean applyKeepLoadedPin(String worldName, long engineIndex) {
            Set<Long> p = presentChunks;
            if (p == null || !p.contains(engineIndex)) return false;
            pinCounters.computeIfAbsent(engineIndex,
                            k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            return true;
        }

        @Override
        protected boolean applyKeepLoadedRelease(String worldName, long engineIndex) {
            java.util.concurrent.atomic.AtomicInteger c = pinCounters.get(engineIndex);
            if (c == null) return false;
            c.decrementAndGet();
            return true;
        }

        @Override
        public void recordPinAcquisition(String zoneId, String worldName, int cx, int cz) {
            stampCalls.add(zoneId + "|" + worldName + ":" + cx + "," + cz);
            super.recordPinAcquisition(zoneId, worldName, cx, cz);
        }

        /** Runs (and clears) the world tasks held while holdWorldTasks was on. */
        void runQueuedWorldTasks() {
            holdWorldTasks = false;
            for (Runnable t : queuedWorldTasks) t.run();
            queuedWorldTasks.clear();
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
            cm = new RecordingCacheManager(cfg, exec);
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
        // Chunk (0,0) is resident and (1,0) is evicted, and NO zone owns any chunk, so
        // the SINGLE evicted coord must still demote BOTH zones (I2.2: all-overlap
        // demotion, deduped) - a first-match-only implementation that demotes only the
        // first covering candidate would leave the other zone HOT.
        Fixture fx = new Fixture(Set.of(0L)); // chunk (0,0) resident; (1,0) evicted
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.storage.save(zone("z2", 16, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.setZoneTier("z2", CacheTier.HOT);

        fx.ak.pingTier(CacheTier.HOT, "test");

        assertEquals(CacheTier.WARM, fx.cm.getZoneTier("z1"), "overlapping HOT zone z1 demoted");
        assertEquals(CacheTier.WARM, fx.cm.getZoneTier("z2"), "overlapping HOT zone z2 demoted too (all-overlap)");
        assertEquals(Set.of(0L), fx.surface.pinged, "only the resident chunk is pinged; the unowned evicted chunk is never resurrected");
    }

    @Test
    void ownedEvictedChunkIsRecoveredAndReRegistered() throws Exception {
        Fixture fx = new Fixture(Set.of()); // chunk (0,0) evicted
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0}));
        // The engine evicted (0,0) while the ownership map stayed intact (the Fixture's
        // resident set is empty - no audit pass ran yet). F3 must re-request the chunk
        // and idempotently re-register its ownership. The successful ping also triggers
        // the keep-loaded pin re-acquisition (reacquireKeepLoadedPin) once the chunk is
        // back in the store - pin state is only observable on the world thread, so this
        // test asserts the ownership side and the pin ordering is verified by code
        // review. (An auditor-stripped chunk is indistinguishable from unowned geometry
        // by design - keepalive never resurrects unowned chunks; the 3-tier model owns
        // that decision.)
        assertEquals(Set.of("z1"), fx.cm.getChunkOwners("w1", 0, 0), "precondition: ownership intact");

        fx.ak.pingTier(CacheTier.HOT, "test");

        assertTrue(fx.surface.pinged.contains(idx(0, 0)), "the owned evicted chunk is re-pinged (F3 recovery)");
        assertEquals(Set.of("z1"), fx.cm.getChunkOwners("w1", 0, 0), "F3: ownership is re-registered after the ping lands");
    }

    /**
     * F3 pin-balance regression (QC 618daa2): a chunk owned by TWO zones that the
     * engine evicted must come back with exactly ONE keep-loaded pin per owner -
     * the per-zone release path (deregisterAllChunks) later decrements once per
     * owner, so a single shared pin would leave the chunk unpinned after the
     * first owner's release and underflow the counter on the last.
     */
    @Test
    void sharedChunkRecoveryRestoresOnePinPerOwner() throws Exception {
        Fixture fx = new Fixture(Set.of()); // nothing resident: chunk (0,0) evicted
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.storage.save(zone("z2", 0, 0, 1)); // second owner of the SAME chunk (world coords 0,0 -> chunk (0,0))
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.setZoneTier("z2", CacheTier.HOT);
        fx.cm.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0}));
        fx.cm.registerOwnershipBatch("z2", "w1", List.of(new int[]{0, 0}));
        assertEquals(Set.of("z1", "z2"), fx.cm.getChunkOwners("w1", 0, 0), "precondition: two owners on one chunk");

        // The ping's whenComplete callback runs synchronously here: reconciliation
        // re-registers both owners (idempotent claims) and queues one world-thread
        // task that re-pins the owners whose pin lapsed with the evicted instance
        // (both - z1/z2 registered while the chunk was absent, so their pre-ping
        // claims lapsed with the old instance).
        ((RecordingCacheManager) fx.cm).presentChunks = java.util.Set.of(idx(0, 0));
        fx.ak.pingTier(CacheTier.HOT, "test");
        RecordingCacheManager rec = (RecordingCacheManager) fx.cm;
        assertEquals(2, pins(rec, idx(0, 0)),
                "one re-acquired pin per current owner of the recovered chunk (balanced with the per-zone release path)");
        assertTrue(rec.hasLivePin("z1", "w1", 0, 0) && rec.hasLivePin("z2", "w1", 0, 0),
                "both owners' re-pins are stamped live on the reloaded instance");
        assertEquals(Set.of("z1", "z2"), fx.cm.getChunkOwners("w1", 0, 0), "F3: both owners' ownership is re-registered");
    }
    /**
     * F3 interleaving regression (QC r3 P1): an owner that registers on the reloaded
     * chunk DURING the ping gap already holds its own live pin (applied to the new
     * instance, stamped at registration). The recovery callback must re-pin ONLY the
     * owners whose pins lapsed with the evicted instance - re-pinning the late owner
     * would leave one extra keep-loaded count with no owner after all releases
     * (permanent residency leak). Pre-registered owners z1/z2 (no stamp on the fake
     * store) have lapsed pins and are re-pinned exactly once each.
     */
    @Test
    void lateOwnerRegisteringInPingGapIsNotDoublePinned() throws Exception {
        Set<Long> resident = new java.util.LinkedHashSet<>();
        Fixture fx = new Fixture(resident); // chunk (0,0) not resident: evicted
        ((RecordingCacheManager) fx.cm).presentChunks = resident;
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.storage.save(zone("z2", 0, 0, 1));
        fx.storage.save(zone("z3", 0, 0, 1)); // late owner: registers in the ping gap
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.setZoneTier("z2", CacheTier.HOT);
        fx.cm.setZoneTier("z3", CacheTier.HOT);
        fx.cm.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0}));
        fx.cm.registerOwnershipBatch("z2", "w1", List.of(new int[]{0, 0}));

        fx.surface.holdNextPing();
        fx.ak.pingTier(CacheTier.HOT, "test"); // the reload is in flight (latch held)
        // The gap: the reload lands (chunk now present in the store) and the late owner
        // registers on it - its registration issues and applies its own pin.
        resident.add(idx(0, 0));
        fx.cm.registerOwnershipBatch("z3", "w1", List.of(new int[]{0, 0}));
        fx.surface.releaseLatch(); // the recovery callback runs now

        RecordingCacheManager rec = (RecordingCacheManager) fx.cm;
        assertTrue(rec.regCalls.stream().anyMatch(c -> c.equals("z3|w1:0,0|1")),
                "the late owner's own registration acquired its pin (pinned == 1)");
        assertEquals(3, pins(rec, idx(0, 0)),
                "3 owners hold exactly 3 pins on the live instance: z1+z2 re-acquires + z3's own registration (z1/z2's pre-ping claims belonged to the evicted instance and lapsed with it)");
        assertTrue(rec.hasLivePin("z1", "w1", 0, 0)
                        && rec.hasLivePin("z2", "w1", 0, 0)
                        && rec.hasLivePin("z3", "w1", 0, 0),
                "every owner holds exactly one live-stamped pin on the new instance");
        // Release every owner: each release balances exactly one acquisition, so no
        // keep-loaded count survives with zero owners (the reviewer's residue probe).
        fx.cm.releaseZoneChunks("z1");
        fx.cm.releaseZoneChunks("z2");
        fx.cm.releaseZoneChunks("z3");
        assertEquals(Set.of(), fx.cm.getChunkOwners("w1", 0, 0),
                "all owners released - no ownership (and therefore no orphaned pin) remains");
        assertEquals(0, pins(rec, idx(0, 0)),
                "no keep-loaded count survives after all owners release");
    }

    /**
     * F3 interleaving regression (goldfish major): the auditor strips the chunk's
     * ownership mapping during the ping gap. The recovery callback must operate on
     * the LIVE owner set (empty after a strip) and must not re-register the retained
     * set reference - that would resurrect the stripped ownership with pins and
     * re-trigger the audit loop.
     */
    @Test
    void stripDuringPingGapDoesNotResurrectOwnership() throws Exception {
        Fixture fx = new Fixture(Set.of()); // chunk (0,0) evicted
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.storage.save(zone("z2", 0, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.setZoneTier("z2", CacheTier.HOT);
        fx.cm.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0}));
        fx.cm.registerOwnershipBatch("z2", "w1", List.of(new int[]{0, 0}));

        fx.surface.holdNextPing();
        fx.ak.pingTier(CacheTier.HOT, "test"); // the reload is in flight (latch held)
        // The gap: the auditor strips the chunk (e.g. a demotion sweep ran concurrently).
        fx.cm.onChunksEvicted("w1", List.of(new int[]{0, 0}));
        fx.surface.releaseLatch(); // the recovery callback runs now

        assertEquals(0, pins((RecordingCacheManager) fx.cm, idx(0, 0)),
                "no re-pins for a stripped chunk (the live owner set is empty)");
        assertTrue(((RecordingCacheManager) fx.cm).stampCalls.isEmpty(),
                "no stamps were written for the stripped chunk");
        assertEquals(Set.of(), fx.cm.getChunkOwners("w1", 0, 0),
                "stripped ownership is not resurrected by the recovery callback");
    }

    /** The fake chunk instance's keep-loaded counter for a packed chunk index. */
    private static int pins(RecordingCacheManager cm, long idx) {
        java.util.concurrent.atomic.AtomicInteger c = cm.pinCounters.get(idx);
        return c == null ? 0 : c.get();
    }

    @Test
    void registrationWhileAbsentThenRepublishPinsExactlyOncePerOwner() throws Exception {
        Set<Long> resident = new java.util.LinkedHashSet<>();
        Fixture fx = new Fixture(resident); // chunk (0,0) not resident: evicted
        ((RecordingCacheManager) fx.cm).presentChunks = resident;
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.storage.save(zone("z2", 0, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        fx.cm.setZoneTier("z2", CacheTier.HOT);
        fx.cm.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0}));
        fx.cm.registerOwnershipBatch("z2", "w1", List.of(new int[]{0, 0}));
        // Absent at registration: nothing to apply, no live stamps (their claims
        // lapsed with the evicted instance).
        resident.add(idx(0, 0)); // the republished (new) instance is now in the store
        RecordingCacheManager rec = (RecordingCacheManager) fx.cm;
        assertEquals(0, pins(rec, idx(0, 0)), "precondition: the absent chunk held no pins");
        rec.reconcileKeepLoadedPins("w1", 0, 0);
        assertEquals(2, pins(rec, idx(0, 0)),
                "each current owner holds exactly one pin on the reloaded instance (the r4 P1 window: register while absent, republish, reconcile)");
        assertTrue(rec.hasLivePin("z1", "w1", 0, 0) && rec.hasLivePin("z2", "w1", 0, 0),
                "both pins are stamped live on the new instance (reconciliation is idempotent per zone+chunk)");
        fx.cm.releaseZoneChunks("z1");
        fx.cm.releaseZoneChunks("z2");
        assertEquals(0, pins(rec, idx(0, 0)),
                "releases balance exactly the re-pins - zero residue with zero owners");
    }

    @Test
    void queuedDuplicatePinTasksApplyExactlyOnePin() throws Exception {
        Set<Long> resident = new java.util.LinkedHashSet<>(java.util.Set.of(idx(0, 0)));
        Fixture fx = new Fixture(resident);
        ((RecordingCacheManager) fx.cm).presentChunks = resident;
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        RecordingCacheManager rec = (RecordingCacheManager) fx.cm;
        rec.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0}));
        rec.holdWorldTasks = true;
        // Two tasks race for the same zone+chunk: the recovery re-pin (queued by the
        // reconciliation) and a duplicate zone-aware re-pin (a registration task
        // queued in the same window). The instance is evicted between the
        // registration and task execution; the republished instance must receive
        // exactly ONE pin from the two queued tasks.
        rec.reacquireKeepLoadedPin("w1", 0, 0, "z1");
        rec.noteChunkEvicted("w1", 0, 0); // the instance's pins are gone (gen bump)
        rec.pinCounters.remove(idx(0, 0)); // the republished (new) instance
        rec.reconcileKeepLoadedPins("w1", 0, 0);
        assertEquals(2, rec.queuedWorldTasks.size(),
                "both tasks are queued before either executes");
        rec.runQueuedWorldTasks();
        assertEquals(1, pins(rec, idx(0, 0)),
                "queued duplicate pin tasks for the same zone+chunk apply exactly one pin (the execution-time live-pin guard makes tasks idempotent)");
        assertTrue(rec.hasLivePin("z1", "w1", 0, 0),
                "the surviving pin is stamped live on the new instance");
        rec.holdWorldTasks = false;
        fx.cm.releaseZoneChunks("z1");
        assertEquals(0, pins(rec, idx(0, 0)), "the release balances the single pin");
    }

    @Test
    void auditorEvictionInvalidatesStaleStamps() throws Exception {
        Set<Long> resident = new java.util.LinkedHashSet<>();
        Fixture fx = new Fixture(resident); // chunk (0,0) not resident
        ((RecordingCacheManager) fx.cm).presentChunks = resident;
        fx.storage.save(zone("z1", 0, 0, 1));
        fx.cm.setZoneTier("z1", CacheTier.HOT);
        RecordingCacheManager rec = (RecordingCacheManager) fx.cm;
        rec.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0})); // absent: nothing applied
        // Auditor-confirmed engine eviction (a different observation path than the
        // keepalive ping): it must invalidate every stale zone stamp, so a later
        // re-registration on the reloaded chunk re-pins from scratch.
        rec.onChunkEvicted("w1", 0, 0);
        assertEquals(Set.of(), rec.getChunkOwners("w1", 0, 0), "the eviction stripped the ownership mapping");
        resident.add(idx(0, 0)); // the reloaded (new) instance
        rec.registerOwnership("z1", "w1", 0, 0);
        assertEquals(1, pins(rec, idx(0, 0)),
                "the re-registration re-pinned the reloaded instance exactly once (no stale stamp from the evicted instance fed the live-pin guard)");
        fx.cm.releaseZoneChunks("z1");
        assertEquals(0, pins(rec, idx(0, 0)), "the release balances the re-registration");
    }

    @Test
    void releaseClearsPinStampSoReclaimIsNotSkipped() throws Exception {
        Set<Long> resident = new java.util.LinkedHashSet<>(java.util.Set.of(idx(0, 0)));
        Fixture fx = new Fixture(resident);
        ((RecordingCacheManager) fx.cm).presentChunks = resident;
        fx.storage.save(zone("z1", 0, 0, 1)); // COLD tier (default)
        RecordingCacheManager rec = (RecordingCacheManager) fx.cm;
        rec.registerOwnershipBatch("z1", "w1", List.of(new int[]{0, 0}));
        assertEquals(1, pins(rec, idx(0, 0)), "precondition: the pin applied and stamped");
        // Engine eviction of a COLD-owned chunk runs deregisterAllChunks (the full
        // deregistration path): it must clear the zone's stamp, so a re-registered
        // zone is not skipped by the live-pin guard.
        rec.onChunksEvicted("w1", List.of(new int[]{0, 0}));
        rec.pinCounters.remove(idx(0, 0)); // the republished (new) instance
        rec.registerOwnership("z1", "w1", 0, 0);
        assertEquals(1, pins(rec, idx(0, 0)),
                "re-registration after a full deregistration re-pins the new instance exactly once (the deregistration cleared the stale stamp)");
        fx.cm.releaseZoneChunks("z1");
        assertEquals(0, pins(rec, idx(0, 0)), "the release balances the re-registration");
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
