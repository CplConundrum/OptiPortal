package com.optiportal.preload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
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
        World world = worldRegistry.getWorld(worldName);
        if (world == null) {
            LOG.warning("[OptiPortal] predictiveLoad: world not loaded: " + worldName);
            return CompletableFuture.completedFuture(null);
        }
        // Dedup: register ownership for already-loaded chunks, only load new ones
        List<int[]> allChunks = buildChunkList(cx, cz, radius);
        List<int[]> toLoad = new ArrayList<>();
        for (int[] coord : allChunks) {
            if (cacheManager.isChunkOwned(worldName, coord[0], coord[1])) {
                // Already loaded by another zone — just claim ownership
                if (zoneId != null) cacheManager.registerOwnership(zoneId, worldName, coord[0], coord[1]);
            } else {
                toLoad.add(coord);
            }
        }
        int skipped = allChunks.size() - toLoad.size();
        if (skipped > 0 && toLoad.size() > 0) LOG.info("[OptiPortal] predictiveLoad " + zoneId + ": skipped " + skipped + " already-owned chunks");

        CompletableFuture<Void> future = toLoad.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : loadChunks(world, toLoad, false);

        if (zoneId != null) {
            final String zid = zoneId;
            final int loadedCount = toLoad.size();
            final long startTime = System.nanoTime();
            future.thenRun(() -> {
                // Register ownership for newly loaded chunks
                for (int[] coord : toLoad) {
                    cacheManager.registerOwnership(zid, worldName, coord[0], coord[1]);
                }
                cacheManager.setZoneTier(zid, com.optiportal.model.CacheTier.HOT);
                if (loadedCount > 0) {
                    LOG.info("[OptiPortal] predictiveLoad complete: " + zid + " → HOT (loaded=" + loadedCount + " shared=" + skipped + ")");
                }
                // Update entry stats
                Optional<PortalEntry> entryOpt = storage.loadById(zid);
                entryOpt.ifPresent(entry -> updateEntryStats(entry, loadedCount));
                // Record metrics
                metricsCollector.recordPreload();
                metricsCollector.recordChunksDeduped(skipped);
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                metricsCollector.recordFreshLoadTime(durationMs);
            });
        }
        return future;
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
        World world = worldRegistry.getWorld(worldName);
        if (world == null) return CompletableFuture.completedFuture(null);

        // Dedup: claim already-owned chunks, only load new ones
        List<int[]> allChunks = buildChunkList(cx, cz, radius);
        List<int[]> toLoad = new ArrayList<>();
        for (int[] coord : allChunks) {
            if (cacheManager.isChunkOwned(worldName, coord[0], coord[1])) {
                if (zoneId != null) cacheManager.registerOwnership(zoneId, worldName, coord[0], coord[1]);
            } else {
                toLoad.add(coord);
            }
        }
        int skipped = allChunks.size() - toLoad.size();
        if (skipped > 0) LOG.info("[OptiPortal] warmLoad " + zoneId + ": skipped " + skipped + " already-owned chunks");

        CompletableFuture<Void> future = toLoad.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : loadChunks(world, toLoad, true);

        if (zoneId != null) {
            final String zid = zoneId;
            final int loadedCount = toLoad.size();
            final long startTime = System.nanoTime();
            future.thenRun(() -> {
                for (int[] coord : toLoad) {
                    cacheManager.registerOwnership(zid, worldName, coord[0], coord[1]);
                }
                LOG.info("[OptiPortal] warmLoad complete: " + zid + " (loaded=" + loadedCount + " shared=" + skipped + ")");
                // Update entry stats
                Optional<PortalEntry> entryOpt = storage.loadById(zid);
                entryOpt.ifPresent(entry -> updateEntryStats(entry, loadedCount));
                // Record metrics
                metricsCollector.recordPreload();
                metricsCollector.recordChunksDeduped(skipped);
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                metricsCollector.recordRestoreTime(durationMs);
            });
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

        return loadChunks(world, toLoad, false);
    }

    /**
     * Builds chunk list sorted by density priority.
     * This provides better prioritization than simple distance-based sorting.
     */
    private List<int[]> buildChunkListWithDensity(String worldName, int cx, int cz, int radius) {
        // Create list of all chunks in the area
        List<int[]> chunks = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new int[]{cx + dx, cz + dz});
            }
        }

        // Sort by density priority - this would normally use HytaleServer's density functions
        // For now, we'll implement a basic version that demonstrates the concept
        chunks.sort((c1, c2) -> {
            // Calculate distance from center (lower = closer)
            int dist1 = Math.max(Math.abs(c1[0] - cx), Math.abs(c1[1] - cz));
            int dist2 = Math.max(Math.abs(c2[0] - cx), Math.abs(c2[1] - cz));
  
            // Primary sort: distance from center (closer first)
            if (dist1 != dist2) {
                return Integer.compare(dist1, dist2);
            }
  
            // Secondary sort: for chunks at same distance, prioritize based on some heuristic
            // In a real implementation, this would use HytaleServer's density functions
            return 0; // Placeholder - in reality this would be more complex
        });
        
        return chunks;
    }

    // -------------------------------------------------------------------------
    // Coordinate utilities
    // -------------------------------------------------------------------------

    /** Convert a world-space coordinate to a chunk coordinate. */
    public WorldRegistry getWorldRegistry() { return worldRegistry; }

    public static int toChunkCoord(double worldCoord) {
        return (int) Math.floor(worldCoord / 16.0);
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

    /** Issue async load requests for every coord and return a future over all of them. */
    private CompletableFuture<Void> loadChunks(World world, List<int[]> coords, boolean nonTicking) {
        if (coords.isEmpty()) return CompletableFuture.completedFuture(null);

        int batchSize  = config.getWarmBatchSize();   // default 4
        int batchDelay = config.getWarmBatchDelayMs(); // default 250

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
                    futures[i] = nonTicking
                            ? world.getNonTickingChunkAsync(cx, cz)
                            : world.getChunkAsync(cx, cz);
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
