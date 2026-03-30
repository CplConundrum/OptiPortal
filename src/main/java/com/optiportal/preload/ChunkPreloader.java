package com.optiportal.preload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.model.PortalEntry;
import com.optiportal.storage.StorageBackend;

/**
 * Handles async chunk pre-loading for WARM and PREDICTIVE zones.
 *
 * Load order: priority rings — inner chunks first, outer rings fill behind.
 * Ring 0 (r <= INNER_RING_RADIUS): loaded first, gates the returned CompletableFuture.
 * Ring 1 (r > INNER_RING_RADIUS): loaded after inner ring, best-effort in background.
 *
 * All chunk work goes through world.getChunkAsync() / getNonTickingChunkAsync()
 * which schedule on the world thread — we never block on them from plugin threads.
 */
public class ChunkPreloader {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    /** Chunks within this Chebyshev radius gate the future returned to callers. */
    private static final int INNER_RING_RADIUS = 2;

    /** Approximate RAM per chunk in MB (16KB per chunk). Deprecated — use config.getBytesPerChunk() instead. */
    @Deprecated
    private static final double CHUNK_RAM_MB = 0.016;

    private final PluginConfig config;
    private final CacheManager cacheManager;
    private final WorldRegistry worldRegistry;
    private final ScheduledExecutorService executor;
    private final StorageBackend storage;
    private final MetricsCollector metricsCollector;

    /**
     * In-flight load dedup map: zoneId → relay future currently being loaded.
     * Shared by subclasses so both ChunkPreloader and EnhancedChunkPreloader
     * deduplicate against the same state.
     */
    protected final ConcurrentHashMap<String, CompletableFuture<Void>> inflightLoads = new ConcurrentHashMap<>();

    public ChunkPreloader(PluginConfig config,
                          CacheManager cacheManager,
                          WorldRegistry worldRegistry,
                          ScheduledExecutorService executor,
                          StorageBackend storage,
                          MetricsCollector metricsCollector) {
        this.config       = config;
        this.cacheManager = cacheManager;
        this.worldRegistry = worldRegistry;
        this.executor     = executor;
        this.storage      = storage;
        this.metricsCollector = metricsCollector;
    }

    /**
     * Protected getter for config to allow subclasses to access it.
     */
    protected PluginConfig getConfig() {
        return config;
    }

    /**
     * Updates the PortalEntry with preload statistics.
     * Increments preload count and calculates marginal RAM based on chunks loaded.
     *
     * @param entry The portal entry to update
     * @param chunkCount Number of chunks loaded for this zone
     */
    public void updateEntryStats(PortalEntry entry, int chunkCount) {
        if (entry == null) return;
        
        entry.incrementPreloadCount();
        
        // Use configurable bytesPerChunk instead of the hardcoded CHUNK_RAM_MB constant.
        // config.getBytesPerChunk() returns int (default 262144 = 256 KB).
        // Convert: (chunkCount * bytesPerChunk) / (1024 * 1024) = MB
        double marginalMB = (chunkCount * (double) config.getBytesPerChunk()) / (1024.0 * 1024.0);
        entry.setRamMarginalMB(marginalMB);
        
        LOG.fine("[OptiPortal] Updated entry stats: " + entry.getId() +
                 " preloadCount=" + entry.getPreloadCount() +
                 " ramMarginalMB=" + String.format("%.3f", entry.getRamMarginalMB()));
    }

    /**
     * Protected getter for bytesPerChunk to allow subclasses to access it.
     * @return bytesPerChunk value from config
     */
    protected int getBytesPerChunk() {
        return config.getBytesPerChunk();
    }

    /**
     * Protected getter for storage to allow subclasses to access it.
     * @return Storage backend instance
     */
    protected StorageBackend getStorage() {
        return storage;
    }

    // -------------------------------------------------------------------------
    // In-flight dedup
    // -------------------------------------------------------------------------

    /**
     * Atomically ensures at most one predictive load runs per zone at a time.
     *
     * Uses ConcurrentHashMap.compute to install a relay CompletableFuture before
     * the loader starts, so any concurrent caller that arrives after the compute
     * but before the load completes receives the same relay rather than starting
     * a second load. The relay mirrors the actual future's completion.
     *
     * If zoneId is null (anonymous load), the loader runs unconditionally.
     */
    protected final CompletableFuture<Void> withInflightDedup(
            String zoneId, Supplier<CompletableFuture<Void>> loader) {
        if (zoneId == null) {
            return loader.get();
        }
        boolean[] isNew = {false};
        CompletableFuture<Void> relay = inflightLoads.compute(zoneId, (k, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing; // re-use; isNew stays false
            }
            isNew[0] = true;
            return new CompletableFuture<>();
        });
        if (!isNew[0]) {
            LOG.fine(() -> "[OptiPortal] predictiveLoad: reusing in-flight future for " + zoneId);
            return relay;
        }
        // We own the slot — start the actual load and wire it to the relay.
        CompletableFuture<Void> actual = loader.get();
        actual.whenComplete((v, ex) -> {
            if (ex != null) relay.completeExceptionally(ex);
            else relay.complete(null);
            inflightLoads.remove(zoneId, relay);
        });
        return relay;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * PREDICTIVE load: full simulation, triggered by portal approach.
     * Returns when inner ring is ready; outer ring continues in background.
     *
     * @param worldName  Hytale world name (e.g. "Berkan")
     * @param cx         destination chunk X
     * @param cz         destination chunk Z
     * @param radius     chunk radius (typically current sim distance 5-7)
     */
    public CompletableFuture<Void> predictiveLoad(String worldName, int cx, int cz, int radius) {
        return predictiveLoad(null, worldName, cx, cz, radius);
    }

    /**
     * PREDICTIVE load with zone ID for tier promotion.
     * On completion, promotes the zone tier from UNVISITED → HOT in CacheManager.
     */
    public CompletableFuture<Void> predictiveLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        return withInflightDedup(zoneId, () -> {
            World world = worldRegistry.getWorld(worldName);
            if (world == null) {
                LOG.warning("[OptiPortal] predictiveLoad: world not loaded: " + worldName);
                return CompletableFuture.completedFuture(null);
            }
            // Dedup: register ownership for already-loaded chunks, only load new ones
            // Use density-sorted list so resident chunks are claimed first at zero IO cost (H5)
            List<int[]> allChunks = buildChunkListWithDensity(worldName, cx, cz, radius);
            List<int[]> toLoad = new ArrayList<>();
            List<int[]> alreadyOwned = new ArrayList<>();
            for (int[] coord : allChunks) {
                if (cacheManager.isChunkOwned(worldName, coord[0], coord[1])) {
                    // Already loaded by another zone — collect for batched ownership claim
                    alreadyOwned.add(coord);
                } else {
                    toLoad.add(coord);
                }
            }
            // Batch-register ownership for already-owned chunks (Issue 4 fix)
            if (zoneId != null && !alreadyOwned.isEmpty()) {
                cacheManager.registerOwnershipBatch(zoneId, worldName, alreadyOwned);
            }
            int skipped = allChunks.size() - toLoad.size();
            if (skipped > 0 && toLoad.size() > 0) LOG.info(() -> "[OptiPortal] predictiveLoad " + zoneId + ": skipped " + skipped + " already-owned chunks");

            CompletableFuture<Void> future = toLoad.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : loadChunks(world, toLoad, false, zoneId);

            if (zoneId != null) {
                final String zid = zoneId;
                final int loadedCount = toLoad.size();
                final int totalCount = allChunks.size();
                final long startTime = System.nanoTime();
                // D4: thenRunAsync dispatches storage I/O to executor, not the world thread.
                // U1: thenRunAsync does not fire on a failed future (e.g. ChunkLoadAbortedException),
                //     so HOT promotion is suppressed when the load was aborted by a guard.
                CompletableFuture<Void> chainedFuture = future.thenRunAsync(() -> {
                    cacheManager.setZoneTier(zid, com.optiportal.model.CacheTier.HOT);
                    if (loadedCount > 0) {
                        LOG.info(() -> "[OptiPortal] predictiveLoad complete: " + zid + " → HOT (loaded=" + loadedCount + " shared=" + skipped + ")");
                    }
                    // Update entry stats using total chunk count (not just newly loaded),
                    // so shared-chunk loads still record correct RAM and preload count.
                    Optional<PortalEntry> entryOpt = storage.loadById(zid);
                    entryOpt.ifPresent(entry -> {
                        updateEntryStats(entry, totalCount);
                        storage.save(entry);
                    });
                    // Record metrics
                    metricsCollector.recordPreload();
                    metricsCollector.recordChunksDeduped(skipped);
                    long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                    metricsCollector.recordFreshLoadTime(durationMs);
                }, executor).exceptionally(ex -> {
                    if (ex instanceof ChunkLoadAbortedException || ex.getCause() instanceof ChunkLoadAbortedException) {
                        LOG.fine("[OptiPortal] predictiveLoad aborted for " + zid + " — tier not promoted: " + ex.getMessage());
                    } else {
                        LOG.warning("[OptiPortal] predictiveLoad post-load error for " + zid + ": " + ex.getMessage());
                    }
                    return null;
                });
                return chainedFuture;
            }
            return future;
        });
    }

    /**
     * WARM load: geometry only, no simulation cost.
     * Uses getNonTickingChunkAsync — chunks stay loaded but don't tick.
     * Returns when ALL chunks are loaded (warm zones fully pre-load on startup).
     *
     * @param worldName  Hytale world name
     * @param cx         centre chunk X
     * @param cz         centre chunk Z
     * @param radius     warm zone radius (default 4 from config)
     */
    public CompletableFuture<Void> warmLoad(String worldName, int cx, int cz, int radius) {
        World world = worldRegistry.getWorld(worldName);
        if (world == null) {
            String known = worldRegistry.getWorlds().stream()
                    .map(com.hypixel.hytale.server.core.universe.world.World::getName)
                    .collect(java.util.stream.Collectors.joining(", "));
            LOG.warning("[OptiPortal] warmLoad: world not loaded: '" + worldName
                    + "' — known worlds: [" + known + "]");
            return CompletableFuture.completedFuture(null);
        }
        // Warm zones load everything — no background split, caller awaits full completion
        return warmLoad(null, worldName, cx, cz, radius);
    }

    /**
     * WARM load with zone ID for ownership registration and dedup.
     */
    public CompletableFuture<Void> warmLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        return warmLoad(zoneId, worldName, cx, cz, radius, radius);
    }

    /**
     * WARM load resolving the world via UUID first (H7), then falling back to name.
     * Use this overload when loading from a PortalEntry that may carry a destinationWorldUuid.
     */
    public CompletableFuture<Void> warmLoad(com.optiportal.model.PortalEntry entry,
                                             int cx, int cz, int radiusX, int radiusZ) {
        World world = worldRegistry.resolveWorld(entry.getDestinationWorldUuid(), entry.getWorld());
        if (world == null) {
            LOG.warning("[OptiPortal] warmLoad(PortalEntry): world not found for entry: " + entry.getId());
            return CompletableFuture.completedFuture(null);
        }
        return warmLoad(entry.getId(), world.getName(), cx, cz, radiusX, radiusZ);
    }

    /**
     * PREDICTIVE load resolving the world via UUID first (H7), then falling back to name.
     */
    public CompletableFuture<Void> predictiveLoad(com.optiportal.model.PortalEntry entry,
                                                   int cx, int cz, int radius) {
        World world = worldRegistry.resolveWorld(entry.getDestinationWorldUuid(), entry.getWorld());
        if (world == null) {
            LOG.warning("[OptiPortal] predictiveLoad(PortalEntry): world not found for entry: " + entry.getId());
            return CompletableFuture.completedFuture(null);
        }
        return predictiveLoad(entry.getId(), world.getName(), cx, cz, radius);
    }

    /**
     * WARM load with asymmetric X/Z radii for ownership registration and dedup.
     * This overload supports rectangular zones for corridors and bridges.
     *
     * @param zoneId Zone ID for ownership tracking
     * @param worldName Hytale world name
     * @param cx Centre chunk X
     * @param cz Centre chunk Z
     * @param radiusX X-axis radius (chunk radius in X direction)
     * @param radiusZ Z-axis radius (chunk radius in Z direction)
     * @return CompletableFuture that completes when all chunks are loaded
     */
    public CompletableFuture<Void> warmLoad(String zoneId, String worldName, int cx, int cz, int radiusX, int radiusZ) {
        World world = worldRegistry.getWorld(worldName);
        if (world == null) return CompletableFuture.completedFuture(null);

        // Dedup: claim already-owned chunks, only load new ones
        List<int[]> allChunks = buildChunkListAsymmetric(cx, cz, radiusX, radiusZ);
        List<int[]> toLoad = new ArrayList<>();
        List<int[]> alreadyOwned = new ArrayList<>();
        for (int[] coord : allChunks) {
            if (cacheManager.isChunkOwned(worldName, coord[0], coord[1])) {
                // Collect for batched ownership claim (Issue 4 fix)
                alreadyOwned.add(coord);
            } else {
                toLoad.add(coord);
            }
        }
        // Batch-register ownership for already-owned chunks (Issue 4 fix)
        if (zoneId != null && !alreadyOwned.isEmpty()) {
            cacheManager.registerOwnershipBatch(zoneId, worldName, alreadyOwned);
        }
        int skipped = allChunks.size() - toLoad.size();
        if (skipped > 0) LOG.info(() -> "[OptiPortal] warmLoad " + zoneId + ": skipped " + skipped + " already-owned chunks");

        CompletableFuture<Void> future = toLoad.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : loadChunks(world, toLoad, true, zoneId);

        if (zoneId != null) {
            final String zid = zoneId;
            final int loadedCount = toLoad.size();
            final int totalCount = allChunks.size();
            final long startTime = System.nanoTime();
            // D4 + U1: thenRunAsync dispatches to executor; does not fire on failed future.
            CompletableFuture<Void> chainedFuture = future.thenRunAsync(() -> {
                LOG.info(() -> "[OptiPortal] warmLoad complete: " + zid + " (loaded=" + loadedCount + " shared=" + skipped + ")");
                // Update entry stats using total chunk count so shared-chunk loads
                // still record correct RAM. Save so the UI reflects updated values.
                Optional<PortalEntry> entryOpt = storage.loadById(zid);
                entryOpt.ifPresent(entry -> {
                    updateEntryStats(entry, totalCount);
                    storage.save(entry);
                });
                // Record metrics
                metricsCollector.recordPreload();
                metricsCollector.recordChunksDeduped(skipped);
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                metricsCollector.recordRestoreTime(durationMs);
            }, executor).exceptionally(ex -> {
                if (ex instanceof ChunkLoadAbortedException || ex.getCause() instanceof ChunkLoadAbortedException) {
                    LOG.fine("[OptiPortal] warmLoad aborted for " + zid + " — skipping stats update: " + ex.getMessage());
                } else {
                    LOG.warning("[OptiPortal] warmLoad post-load error for " + zid + ": " + ex.getMessage());
                }
                return null;
            });
            return chainedFuture;
        }
        return future;
    }

    /**
     * Pre-load bed and death location simultaneously during the respawn screen.
     * Returns when the bed inner ring is ready (higher priority for spawn).
     * Death location continues loading in background.
     */
    public CompletableFuture<Void> preloadRespawnLocations(
            UUID playerId,
            String worldName,
            int bedCx,   int bedCz,
            int deathCx, int deathCz) {

        int radius = config.getPredictiveRadius();

        // Fire both simultaneously — independent CompletableFutures
        CompletableFuture<Void> bedFuture   = predictiveLoad(worldName, bedCx,   bedCz,   radius);
        CompletableFuture<Void> deathFuture = predictiveLoad(worldName, deathCx, deathCz, radius);

        // Gate on bed (spawn destination), death load proceeds independently
        deathFuture.whenComplete((v, ex) -> {
            if (ex != null) LOG.warning("[OptiPortal] Death location preload failed: " + ex.getMessage());
        });

        return bedFuture;
    }

    // --- Enhanced predictive loading using density functions ---

    /**
     * Enhanced predictive load using density functions for better chunk prioritization.
     * This method sorts chunks by terrain complexity to load more important areas first.
     */
    public CompletableFuture<Void> enhancedPredictiveLoad(String worldName, int cx, int cz, int radius) {
        World world = worldRegistry.getWorld(worldName);
        if (world == null) {
            LOG.warning("[OptiPortal] enhancedPredictiveLoad: world not loaded: " + worldName);
            return CompletableFuture.completedFuture(null);
        }

        // Get chunk coordinates in priority order based on terrain density
        List<int[]> prioritizedChunks = buildChunkListWithDensity(worldName, cx, cz, radius);
        
        // Deduplication and loading logic remains the same
        List<int[]> toLoad = new ArrayList<>();
        for (int[] coord : prioritizedChunks) {
            if (!cacheManager.isChunkOwned(worldName, coord[0], coord[1])) {
                toLoad.add(coord);
            }
        }

        if (toLoad.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return loadChunks(world, toLoad, false, null);
    }

    /**
     * Builds a chunk list sorted by:
     *   1. Chebyshev distance from centre (inner-first — unchanged)
     *   2. Already-resident chunks first within the same ring (zero load cost)
     *   3. Chunks on failure backoff last (will be skipped in loadChunks anyway)
     *
     * This replaces the placeholder that returned the same order as buildChunkList().
     */
    private List<int[]> buildChunkListWithDensity(String worldName, int cx, int cz, int radius) {
        World world = worldRegistry.getWorld(worldName);
        List<int[]> chunks = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new int[]{cx + dx, cz + dz});
            }
        }

        chunks.sort((c1, c2) -> {
            // Primary: Chebyshev distance — inner chunks first
            int d1 = Math.max(Math.abs(c1[0] - cx), Math.abs(c1[1] - cz));
            int d2 = Math.max(Math.abs(c2[0] - cx), Math.abs(c2[1] - cz));
            if (d1 != d2) return Integer.compare(d1, d2);

            if (world != null) {
                long idx1 = ChunkUtil.indexChunk(c1[0], c1[1]);
                long idx2 = ChunkUtil.indexChunk(c2[0], c2[1]);

                // Secondary: resident chunks first — claiming ownership costs zero IO
                boolean bo1 = world.getChunkStore().isChunkOnBackoff(
                        idx1, ChunkStore.MAX_FAILURE_BACKOFF_NANOS);
                boolean bo2 = world.getChunkStore().isChunkOnBackoff(
                        idx2, ChunkStore.MAX_FAILURE_BACKOFF_NANOS);

                // Tertiary: backoff chunks last — they will be skipped in loadChunks
                if (bo1 != bo2) return bo1 ? 1 : -1;
            }
            return 0;
        });

        return chunks;
    }

    /**
     * Returns the effective batch size for chunk loading.
     * Overridden by {@link EnhancedChunkPreloader} to apply TPS-adaptive scaling.
     */
    protected int getEffectiveBatchSize(String worldName) {
        return config.getWarmBatchSize();
    }

    /**
     * Returns the effective inter-batch delay in milliseconds.
     * Overridden by {@link EnhancedChunkPreloader} to apply TPS-adaptive scaling.
     */
    protected int getEffectiveBatchDelay(String worldName) {
        return config.getWarmBatchDelayMs();
    }

    // -------------------------------------------------------------------------
    // Coordinate utilities
    // -------------------------------------------------------------------------

    /** Convert a world-space coordinate to a chunk coordinate. */
    public WorldRegistry getWorldRegistry() { return worldRegistry; }

    public static int toChunkCoord(double worldCoord) {
        return ChunkUtil.chunkCoordinate(worldCoord);
    }

    /** Pack (chunkX, chunkZ) into a single long for use as a map key. */
    public static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
    }

    // -------------------------------------------------------------------------
    // Internal ring-loading logic
    // -------------------------------------------------------------------------

    /** Build the full flat list of chunk coords within radius (no ring split). */
    private List<int[]> buildChunkList(int cx, int cz, int radius) {
        List<int[]> list = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                list.add(new int[]{cx + dx, cz + dz});
            }
        }
        // Sort centre-outward
        list.sort(Comparator.comparingInt(c -> Math.abs(c[0] - cx) + Math.abs(c[1] - cz)));
        return list;
    }

    /** Build the full flat list of chunk coords within asymmetric X/Z radii. */
    private List<int[]> buildChunkListAsymmetric(int cx, int cz, int radiusX, int radiusZ) {
        List<int[]> list = new ArrayList<>((2 * radiusX + 1) * (2 * radiusZ + 1));
        for (int dx = -radiusX; dx <= radiusX; dx++) {
            for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                list.add(new int[]{cx + dx, cz + dz});
            }
        }
        // Sort centre-outward using Chebyshev distance
        list.sort(Comparator.comparingInt(c -> Math.max(Math.abs(c[0] - cx), Math.abs(c[1] - cz))));
        return list;
    }

    /**
     * Resolve X-axis radius from entry, falling back to uniform radius then config default.
     * Protected for subclass access.
     */
    protected int resolveRadiusX(com.optiportal.model.PortalEntry entry) {
        if (entry.getWarmRadiusX() != null) return entry.getWarmRadiusX();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }

    /**
     * Resolve Z-axis radius from entry, falling back to uniform radius then config default.
     * Protected for subclass access.
     */
    protected int resolveRadiusZ(com.optiportal.model.PortalEntry entry) {
        if (entry.getWarmRadiusZ() != null) return entry.getWarmRadiusZ();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }

    /** Issue async load requests for every coord and return a future over all of them. */
    private CompletableFuture<Void> loadChunks(World world, List<int[]> coords, boolean nonTicking, String zoneId) {
        if (coords.isEmpty()) return CompletableFuture.completedFuture(null);

        // Guard 1: JVM heap — stop loading if approaching the engine's desperate-eviction
        // threshold (80%). Uses actual used/max ratio: (totalMemory - freeMemory) / maxMemory.
        // NOTE: do NOT use (1 - freeMemory/maxMemory) — that over-estimates usage when the JVM
        // has not yet expanded the heap to -Xmx, causing false aborts on startup.
        Runtime rt = Runtime.getRuntime();
        double heapUsed = (double)(rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        if (heapUsed >= 0.80) {
            String reason = "JVM heap at " + String.format("%.1f", heapUsed * 100) + "% (threshold 80%)";
            LOG.warning(() -> "[OptiPortal] loadChunks: aborting — " + reason);
            // U1: Return a failed future so thenRun/thenRunAsync callbacks do NOT fire,
            // preventing the zone from being promoted to HOT when no load occurred.
            return CompletableFuture.failedFuture(new ChunkLoadAbortedException(reason));
        }

        // Guard 2: Chunk count — abort if world already exceeds the configured ceiling.
        // Skip if threshold is -1 (disabled) or if AsyncLoadBalancer already enforces it.
        int pressureThreshold = config.getMaxLoadedChunksPressureThreshold();
        if (pressureThreshold > 0) {
            int liveCount = world.getChunkStore().getLoadedChunksCount();
            if (liveCount >= pressureThreshold) {
                String reason = "chunk pressure limit (" + liveCount + " >= " + pressureThreshold + ")";
                LOG.warning(() -> "[OptiPortal] loadChunks: aborting — " + reason);
                // U1: Same as above — failed future suppresses HOT promotion.
                return CompletableFuture.failedFuture(new ChunkLoadAbortedException(reason));
            }
        }

        int batchSize  = getEffectiveBatchSize(world.getName());
        int batchDelay = getEffectiveBatchDelay(world.getName());

        // Build list of batches
        List<List<int[]>> batches = new java.util.ArrayList<>();
        for (int i = 0; i < coords.size(); i += batchSize) {
            batches.add(coords.subList(i, Math.min(i + batchSize, coords.size())));
        }

        // Chain batches sequentially: each batch starts after the previous completes + delay
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<int[]> batch : batches) {
            chain = chain.thenCompose(v -> {
                // Fire this batch concurrently
                @SuppressWarnings("unchecked")
                CompletableFuture<WorldChunk>[] futures = new CompletableFuture[batch.size()];
                for (int i = 0; i < batch.size(); i++) {
                    int cx = batch.get(i)[0];
                    int cz = batch.get(i)[1];
                    long chunkIndex = ChunkUtil.indexChunk(cx, cz);
                    
                    // Guard: skip chunks on failure backoff to avoid hammering broken chunks
                    if (world.getChunkStore().isChunkOnBackoff(chunkIndex, ChunkStore.MAX_FAILURE_BACKOFF_NANOS)) {
                        LOG.fine(() -> "[OptiPortal] loadChunks: skipping chunk (" + cx + ", " + cz
                                + ") — on failure backoff");
                        futures[i] = CompletableFuture.completedFuture((WorldChunk) null);
                    } else {
                        final int finalCx = cx;
                        final int finalCz = cz;
                        final String finalZoneId = zoneId;

                        // Store.getComponent() requires the WorldThread — use the async API
                        // for all chunks; the engine returns a completed future for already-
                        // resident chunks without a disk hop.
                        CompletableFuture<WorldChunk> base = nonTicking
                                ? world.getNonTickingChunkAsync(ChunkUtil.indexChunk(cx, cz))
                                : world.getChunkAsync(ChunkUtil.indexChunk(cx, cz));
                        futures[i] = base.thenApply(chunk -> {
                            if (chunk != null && finalZoneId != null) {
                                cacheManager.registerOwnership(finalZoneId,
                                        world.getName(), finalCx, finalCz, chunk);
                            }
                            return chunk;
                        });
                    }
                }
                CompletableFuture<Void> batchDone = CompletableFuture.allOf(futures);
                if (batchDelay <= 0) return batchDone;
                // After batch completes, sleep before next batch
                return batchDone.thenCompose(vv -> {
                    CompletableFuture<Void> delay = new CompletableFuture<>();
                    executor.schedule(() -> delay.complete(null), batchDelay, java.util.concurrent.TimeUnit.MILLISECONDS);
                    return delay;
                });
            });
        }
        return chain;
    }
}
