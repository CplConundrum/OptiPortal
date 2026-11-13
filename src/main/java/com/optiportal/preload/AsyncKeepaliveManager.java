package com.optiportal.preload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.optiportal.async.AsyncLoadBalancer;
import com.optiportal.async.AsyncMetrics;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.PortalRadii;
import com.optiportal.storage.StorageBackend;

/**
 * Asynchronous keepalive variant of {@link KeepaliveManager}: tier pings are dispatched
 * through the {@link AsyncLoadBalancer} (critical-TPS-aware queueing) and chunk pings go
 * through the direct ChunkStore reference API (no bridge, no world-thread hop - the same
 * call the classic manager uses).
 *
 * <p>Wired by {@code OptiPortal} when {@code keepalive.mode=async} (default; the classic
 * manager remains available via {@code keepalive.mode=classic}). Mode changes are
 * restart-only.
 *
 * <p>Lifecycle (2026-11-12 wiring, goldfish F2): {@link #stop()} and {@link #reschedule()}
 * bump a lifecycle epoch; queued batch suppliers capture the epoch at queue time and no-op
 * when stale, so a stop/reload can never execute queued keepalives against a disabled
 * plugin or a deleted/decayed zone. A per-tier in-flight gate coalesces interval ticks
 * while a pass is still draining.
 *
 * <p>Retention semantics: pinging a chunk re-requests it from the engine; retention
 * itself is provided by {@link CacheManager} keep-loaded pins. A missing HOT-tier chunk
 * triggers a same-world demotion scan (all overlapping HOT zones, deduped and
 * dispatch-revalidated) and an ownership/pin-aware recovery load - an unowned chunk is
 * never resurrected by keepalive (the 3-tier model owns that decision).
 */
public class AsyncKeepaliveManager extends KeepaliveManager {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final AsyncLoadBalancer loadBalancer;
    private final AsyncMetrics metrics;

    /** Injected after construction; defaults to a fresh in-memory storage snapshot (F6). */
    private volatile Supplier<List<PortalEntry>> portalCacheSupplier;

    /** F2: stop()/reschedule() bump this epoch; queued batches no-op when stale. */
    private final AtomicLong lifecycleEpoch = new AtomicLong(1);

    /** F2: while a tier pass is in flight, subsequent ticks are coalesced (skipped). */
    private final Map<CacheTier, AtomicBoolean> tierInFlight = new ConcurrentHashMap<>();

    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_BATCH_SIZE = 100;

    public AsyncKeepaliveManager(PluginConfig config,
                                 CacheManager cacheManager,
                                 ChunkPreloader chunkPreloader,
                                 StorageBackend storage,
                                 ScheduledExecutorService executor,
                                 AsyncLoadBalancer loadBalancer,
                                 AsyncMetrics metrics) {
        super(config, cacheManager, chunkPreloader, storage, executor);
        this.loadBalancer = loadBalancer;
        this.metrics      = metrics;
    }

    /**
     * Injects the portal-cache supplier (F6: a fresh in-memory storage snapshot). Call
     * through a subclass-typed reference BEFORE assigning to a base-typed field (F1).
     */
    public void setPortalCacheSupplier(Supplier<List<PortalEntry>> supplier) {
        this.portalCacheSupplier = supplier;
    }

    /** F2: invalidate queued work before the tier timers are cancelled. */
    @Override
    public void stop() {
        lifecycleEpoch.incrementAndGet();
        super.stop();
    }

    public AsyncLoadBalancer.LoadStats getAsyncLoadStats() {
        return loadBalancer.getLoadStats();
    }

    public AsyncMetrics.PerformanceSummary getAsyncPerformanceSummary() {
        return metrics.getPerformanceSummary();
    }

    @Override
    protected void pingTier(CacheTier tier, String label) {
        AtomicBoolean inFlight = tierInFlight.computeIfAbsent(tier, t -> new AtomicBoolean(false));
        if (!inFlight.compareAndSet(false, true)) {
            LOG.fine(() -> "[OptiPortal] Keepalive " + label + ": previous pass still in flight - tick coalesced");
            return;
        }
        final long epoch = lifecycleEpoch.get();

        try {
            List<PortalEntry> entries =
                    portalCacheSupplier != null ? portalCacheSupplier.get() : getStorage().loadAllCached();

            Map<String, List<ChunkCoordinate>> chunksByWorld = new HashMap<>();
            Map<String, List<HotZoneCandidate>> hotCandidates = new HashMap<>();
            collectPassWork(tier, entries, chunksByWorld, hotCandidates);

            if (chunksByWorld.isEmpty()) {
                inFlight.set(false);
                return;
            }

            // F2/I2.2: concurrency-safe dedupe set shared across delayed batches - a zone
            // demoted by an early batch is not demoted (or logged) again by a later one.
            Set<String> demotedThisPass = ConcurrentHashMap.newKeySet();

            List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
            for (Map.Entry<String, List<ChunkCoordinate>> worldEntry : chunksByWorld.entrySet()) {
                String worldName = worldEntry.getKey();
                List<ChunkCoordinate> chunks = worldEntry.getValue();
                int batchSize = calculateOptimalBatchSize(chunks.size());
                List<HotZoneCandidate> candidates = hotCandidates.getOrDefault(worldName, List.of());
                for (int i = 0; i < chunks.size(); i += batchSize) {
                    List<ChunkCoordinate> batch =
                            List.copyOf(chunks.subList(i, Math.min(i + batchSize, chunks.size())));
                    batchFutures.add(loadBalancer.scheduleLoad(() -> {
                        // F2: stale-generation guard (stop/reschedule bumped the epoch after queueing).
                        if (epoch != lifecycleEpoch.get()) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return pingChunkBatch(worldName, batch, tier, candidates, demotedThisPass, epoch);
                    }, AsyncMetrics.AsyncTaskPriority.NORMAL));
                }
            }
            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                    .whenComplete((result, ex) -> {
                        inFlight.set(false);
                        if (epoch != lifecycleEpoch.get()) return; // stale generation: no records
                        if (ex != null) {
                            LOG.warning("[OptiPortal] Keepalive error for tier " + tier + ": " + ex.getMessage());
                            metrics.recordKeepaliveError(tier.name());
                        }
                    });
        } catch (RuntimeException ex) {
            // F2: a synchronous failure after the in-flight gate was acquired (snapshot,
            // collection, batching, or scheduling) must release the gate, or this tier
            // stays permanently coalesced (the latch is otherwise only cleared by the
            // async completion path).
            inFlight.set(false);
            metrics.recordKeepaliveError(tier.name());
            throw ex; // the inherited safePing handler logs it
        }
    }

    /**
     * Groups one tier pass's work: chunk coordinates by world (deduped) and, for the HOT
     * tier only, the per-world demotion candidates (all non-instanced HOT zones with
     * pre-resolved radii and a captured tier generation for dispatch-time revalidation).
     */
    private void collectPassWork(CacheTier tier, List<PortalEntry> entries,
                                 Map<String, List<ChunkCoordinate>> chunksByWorld,
                                 Map<String, List<HotZoneCandidate>> hotCandidates) {
        Map<String, Set<Long>> seen = new HashMap<>();
        for (PortalEntry entry : entries) {
            if (entry.isInstanced()) continue;
            if (getCacheManager().getZoneTier(entry.getId()) != tier) continue;

            String worldName = entry.getWorld();
            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            int radiusX = PortalRadii.resolveRadiusX(entry, getConfig().getDefaultWarmRadius());
            int radiusZ = PortalRadii.resolveRadiusZ(entry, getConfig().getDefaultWarmRadius());

            if (tier == CacheTier.HOT) {
                hotCandidates.computeIfAbsent(worldName, k -> new ArrayList<>())
                        .add(new HotZoneCandidate(entry, cx, cz, radiusX, radiusZ,
                                getCacheManager().getZoneGeneration(entry.getId())));
            }

            Set<Long> worldSeen = seen.computeIfAbsent(worldName, k -> new HashSet<>());
            for (int dx = -radiusX; dx <= radiusX; dx++) {
                for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                    long key = ((long) (cx + dx) << 32) | ((cz + dz) & 0xFFFFFFFFL);
                    if (worldSeen.add(key)) {
                        chunksByWorld.computeIfAbsent(worldName, k -> new ArrayList<>())
                                .add(new ChunkCoordinate(cx + dx, cz + dz));
                    }
                }
            }
        }
    }

    private CompletableFuture<Void> pingChunkBatch(String worldName, List<ChunkCoordinate> batch,
                                                   CacheTier tier, List<HotZoneCandidate> candidates,
                                                   Set<String> demotedThisPass, long epoch) {
        ChunkSurface surface = chunkSurface(worldName);
        if (surface == null) {
            return CompletableFuture.completedFuture(null); // world not loaded (gone)
        }

        BatchStats stats = new BatchStats();
        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();

        // I2.2: execution-time validity - a delayed batch must not demote a zone by
        // snapshot coordinates the warp watcher has since moved/deleted. Revalidated
        // against the CURRENT stored entries at dispatch time.
        Map<String, PortalEntry> currentEntries;
        try {
            currentEntries = new HashMap<>();
            for (PortalEntry e : getStorage().loadAllCached()) {
                if (e != null && e.getId() != null) currentEntries.put(e.getId(), e);
            }
        } catch (Exception e) {
            // storage unavailable: demotion validation degrades to the safe direction
            // (no demotion without coordinate verification).
            currentEntries = Map.of();
        }

        for (ChunkCoordinate coord : batch) {
            long index = ((long) coord.cx << 32) | (coord.cz & 0xFFFFFFFFL);
            boolean resident = surface.isResident(index);

            if (!resident) {
                if (tier == CacheTier.HOT) {
                    // I2.2: demote all same-world HOT zones covering the evicted coord
                    // (candidates are pre-grouped per world - cross-world zones are excluded).
                    demoteOverlappingHotZones(coord, candidates, demotedThisPass, worldName, currentEntries);

                    // F3: ownership/pin-aware recovery. A missing chunk owned by a zone is
                    // re-loaded and its ownership re-registered (idempotent: re-pins only if
                    // the audit removed the pin). An unowned chunk is COLD/unowned geometry -
                    // keepalive must never resurrect it (the 3-tier model owns that decision).
                    Set<String> owners = getCacheManager().getChunkOwners(worldName, coord.cx, coord.cz);
                    if (owners.isEmpty()) {
                        stats.skipped.incrementAndGet();
                        continue;
                    }
                    stats.attempted.incrementAndGet();
                    // F3 generation stamp: observing an evicted-but-owned chunk is an
                    // eviction observation. Bump the chunk's pin generation BEFORE the
                    // reload so the recovery callback can distinguish pins that lapsed
                    // with the old instance from live pins acquired by owners that
                    // register in the ping gap (those must not be re-pinned).
                    getCacheManager().noteChunkEvicted(worldName, coord.cx, coord.cz);
                    chunkFutures.add(surface.ping(index).whenComplete((r, ex) -> {
                        if (ex == null) {
                            // F2: a stop()/reschedule() in the ping gap must not let a
                            // stale-generation pass reconcile pins (it would pin a
                            // former generation's chunk).
                            if (epoch == lifecycleEpoch.get()) {
                                // r4: world-thread-serialized pin reconciliation.
                                // Re-fetches the live owner set (a retained reference can
                                // be stale if the auditor stripped the mapping in the
                                // ping gap), re-registers each owner (idempotent claim),
                                // and queues ONE world-thread task that re-pins exactly
                                // the owners whose pin lapsed with the evicted instance
                                // (missing/stale stamp). The task re-checks the live-pin
                                // stamp at execution time, so queued duplicates (a
                                // registration task and this reconciliation task racing
                                // on the same zone+chunk) apply exactly one pin. All
                                // pin mutations and stamp writes happen on the world
                                // thread, total-ordered with registration pin tasks.
                                try {
                                    getCacheManager().reconcileKeepLoadedPins(
                                            worldName, coord.cx, coord.cz);
                                } catch (Exception e) {
                                    // F7: a failed reconciliation is a keepalive error
                                    // (metrics-visible), not a log-only blip.
                                    metrics.recordKeepaliveError(tier.name());
                                    LOG.warning(() -> "[OptiPortal] Keepalive recovery "
                                            + "reconciliation failed: " + e.getMessage());
                                }
                            }
                            stats.succeeded.incrementAndGet();
                        } else {
                            stats.failed.incrementAndGet();
                            LOG.fine(() -> "[OptiPortal] Keepalive chunk ping failed: "
                                    + coord.cx + "," + coord.cz + ": " + ex.getMessage());
                        }
                    }));
                } else {
                    // WARM/COLD tier: missing chunks are left to the decay/preload path.
                    stats.skipped.incrementAndGet();
                }
            } else {
                stats.attempted.incrementAndGet();
                chunkFutures.add(surface.ping(index).whenComplete((r, ex) -> {
                    if (ex == null) {
                        stats.succeeded.incrementAndGet();
                    } else {
                        stats.failed.incrementAndGet();
                        LOG.fine(() -> "[OptiPortal] Keepalive chunk ping failed: "
                                + coord.cx + "," + coord.cz + ": " + ex.getMessage());
                    }
                }));
            }
        }

        return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) -> {
                    // F7: any chunk failure in the batch (partial or total, aborted or
                    // not) is a keepalive error - a partial failure must not be
                    // absorbed into the success path.
                    if (ex != null || stats.failed.get() > 0) {
                        metrics.recordKeepaliveError(tier.name());
                        LOG.warning("[OptiPortal] Keepalive " + tier
                                + ": " + (ex != null ? "batch aborted" : "batch partially failed")
                                + " for world '" + worldName + "'"
                                + (ex != null ? " (" + ex.getMessage() + ")" : ""));
                    }
                    if (stats.attempted.get() > 0) {
                        LOG.info("[OptiPortal] Keepalive " + tier + ": pinged "
                                + stats.succeeded.get() + "/" + stats.attempted.get()
                                + " chunks for world '" + worldName + "'"
                                + (stats.skipped.get() > 0 ? " (" + stats.skipped.get() + " unowned/absent skipped)" : "")
                                + (stats.failed.get() > 0 ? " (" + stats.failed.get() + " failed)" : ""));
                    }
                });
    }

    /**
     * I2.2: demote (HOT → WARM) every candidate zone covering the evicted coord. Deduped
     * across batches via {@code demotedThisPass} and revalidated at dispatch time: a zone
     * that decayed, was deleted, was moved, or was re-promoted between the snapshot and
     * this delayed batch is left alone (tier + generation + current stored coordinates).
     */
    private void demoteOverlappingHotZones(ChunkCoordinate coord, List<HotZoneCandidate> candidates,
                                           Set<String> demotedThisPass, String worldName,
                                           Map<String, PortalEntry> currentEntries) {
        for (HotZoneCandidate c : candidates) {
            if (Math.abs(coord.cx - c.cx) > c.radiusX || Math.abs(coord.cz - c.cz) > c.radiusZ) continue;
            if (!demotedThisPass.add(c.zoneId)) continue; // already handled by an earlier batch/coord
            // I2.2 execution-time validity: a zone moved or deleted since the snapshot
            // may still read HOT/same-generation - revalidate the coordinates against
            // the current stored entry before demoting.
            PortalEntry current = currentEntries.get(c.zoneId);
            if (current == null) continue; // zone deleted since the snapshot
            if (!current.getWorld().equals(worldName)
                    || ChunkPreloader.toChunkCoord(current.getX()) != c.cx
                    || ChunkPreloader.toChunkCoord(current.getZ()) != c.cz) continue; // zone moved since the snapshot
            if (getCacheManager().getZoneTier(c.zoneId) != CacheTier.HOT) continue;
            if (getCacheManager().getZoneGeneration(c.zoneId) != c.capturedGen) continue;
            getCacheManager().setZoneTier(c.zoneId, CacheTier.WARM);
            LOG.info("[OptiPortal] Keepalive: chunk " + coord.cx + "," + coord.cz
                    + " evicted - demoted zone '" + c.zoneId + "' HOT -> WARM");
        }
    }

    /**
     * Per-world chunk ping surface (seam: tests substitute a fake without a booted World).
     * The production implementation is the ChunkStore reference API - the same direct call
     * the classic manager uses (D2b: no bridge, no world-thread hop).
     * Returns null when the world is not loaded.
     */
    protected ChunkSurface chunkSurface(String worldName) {
        World world = getChunkPreloader().getWorldRegistry().getWorld(worldName);
        if (world == null) return null;
        ChunkStore store = world.getChunkStore();
        return new ChunkSurface() {
            @Override
            public boolean isResident(long engineIndex) {
                return store.getChunkReference(engineIndex) != null;
            }

            @Override
            public CompletableFuture<Void> ping(long engineIndex) {
                // Re-requests the chunk from the engine; reloads it if evicted and resets
                // its eviction timer. A null/invalid ref means the ping did not land.
                return store.getChunkReferenceAsync(engineIndex).thenApply(ref -> {
                    if (ref == null || !ref.isValid()) {
                        throw new CancellationException(
                                "chunk reference unavailable for " + Long.toHexString(engineIndex));
                    }
                    return null;
                });
            }
        };
    }

    /**
     * Testable seam: per-world chunk residency + ping request.
     * Engine index encoding matches {@code ChunkUtil.indexChunk} (cx &lt;&lt; 32 | cz).
     */
    public interface ChunkSurface {
        boolean isResident(long engineIndex);

        CompletableFuture<Void> ping(long engineIndex);
    }

    private int calculateOptimalBatchSize(int totalChunks) {
        // Batch size is a chunk-COUNT heuristic keyed on balancer load (NOT milliseconds):
        // high active load -> 25; low load + fast average -> 100; otherwise default 50.
        AsyncLoadBalancer.LoadStats loadStats = loadBalancer.getLoadStats();
        int batchSize;
        if (loadStats.activeOperations > 10) {
            batchSize = Math.max(10, DEFAULT_BATCH_SIZE / 2);
        } else if (loadStats.activeOperations < 3 && loadStats.averageExecutionTime < 50) {
            batchSize = Math.min(MAX_BATCH_SIZE, DEFAULT_BATCH_SIZE * 2);
        } else {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        return Math.min(batchSize, Math.max(1, totalChunks));
    }

    /** Pre-resolved demotion candidate for one HOT zone (I2.2). */
    private static final class HotZoneCandidate {
        final String zoneId;
        final int cx, cz, radiusX, radiusZ;
        final long capturedGen;

        HotZoneCandidate(PortalEntry entry, int cx, int cz, int radiusX, int radiusZ, long capturedGen) {
            this.zoneId      = entry.getId();
            this.cx         = cx;
            this.cz         = cz;
            this.radiusX    = radiusX;
            this.radiusZ    = radiusZ;
            this.capturedGen = capturedGen;
        }
    }

    private static final class BatchStats {
        final AtomicInteger attempted = new AtomicInteger();
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger skipped   = new AtomicInteger();
        final AtomicInteger failed    = new AtomicInteger();
    }

    /** Simple chunk-coordinate pair for radius enumeration (no engine dependency). */
    record ChunkCoordinate(int cx, int cz) {
    }
}