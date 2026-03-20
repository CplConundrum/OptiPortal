package com.optiportal.preload;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.optiportal.async.AsyncLoadBalancer;
import com.optiportal.async.AsyncMetrics;
import com.optiportal.async.WorldThreadBridge;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.model.CacheTier;
import com.optiportal.storage.StorageBackend;

/**
 * Enhanced chunk preloader with improved async handling.
 * 
 * This extends the original ChunkPreloader with better async operations,
 * load balancing, and world thread isolation to prevent blocking.
 */
public class EnhancedChunkPreloader extends ChunkPreloader {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final ScheduledExecutorService executor;
    private final WorldThreadBridge worldBridge;
    private final AsyncLoadBalancer loadBalancer;
    private final AsyncMetrics metrics;

    // Cache manager reference for ownership registration
    private final CacheManager cacheManager;

    /** Optional corridor index for path-proximity prioritization. Null = disabled. */
    private final CorridorIndex corridorIndex;
    
    /** Complexity score cache — avoids recomputing score() for already-seen chunks. */
    private final ChunkComplexityScorer.Cache complexityCache = new ChunkComplexityScorer.Cache();
    
    public EnhancedChunkPreloader(PluginConfig config,
                                  CacheManager cacheManager,
                                  WorldRegistry worldRegistry,
                                  ScheduledExecutorService executor,
                                  WorldThreadBridge worldBridge,
                                  AsyncLoadBalancer loadBalancer,
                                  AsyncMetrics metrics,
                                  StorageBackend storage,
                                  MetricsCollector metricsCollector,
                                  CorridorIndex corridorIndex) {
        super(config, cacheManager, worldRegistry, executor, storage, metricsCollector);
        this.executor = executor;
        this.worldBridge = worldBridge;
        this.loadBalancer = loadBalancer;
        this.metrics = metrics;
        this.cacheManager = cacheManager;
        this.corridorIndex = corridorIndex; // may be null if disabled
        
        // Invalidate cached scores when a world unloads
        worldRegistry.addWorldUnloadCallback(complexityCache::clearWorld);
    }
    
    /**
     * Backward-compat constructor without CorridorIndex — for callers not yet updated.
     */
    public EnhancedChunkPreloader(PluginConfig config,
                                  CacheManager cacheManager,
                                  WorldRegistry worldRegistry,
                                  ScheduledExecutorService executor,
                                  WorldThreadBridge worldBridge,
                                  AsyncLoadBalancer loadBalancer,
                                  AsyncMetrics metrics,
                                  StorageBackend storage,
                                  MetricsCollector metricsCollector) {
        this(config, cacheManager, worldRegistry, executor, worldBridge,
             loadBalancer, metrics, storage, metricsCollector, null);
    }
    
    /**
     * Enhanced predictive load with better async handling.
     * 
     * @param zoneId Zone ID for ownership tracking
     * @param worldName Hytale world name
     * @param cx Destination chunk X
     * @param cz Destination chunk Z
     * @param radius Chunk radius
     * @return CompletableFuture that completes when inner ring is ready
     */
    @Override
    public CompletableFuture<Void> predictiveLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        if (getWorldRegistry().getWorld(worldName) == null) {
            LOG.warning("[OptiPortal] predictiveLoad: world not loaded: " + worldName);
            return CompletableFuture.completedFuture(null);
        }
        
        // Use load balancer to distribute load across time
        return loadBalancer.scheduleLoad(() -> {
            return enhancedPredictiveLoad(zoneId, worldName, cx, cz, radius);
        }, AsyncMetrics.AsyncTaskPriority.HIGH);
    }
    
    /**
     * Enhanced warm load with better async handling.
     * 
     * @param zoneId Zone ID for ownership tracking
     * @param worldName Hytale world name
     * @param cx Centre chunk X
     * @param cz Centre chunk Z
     * @param radius Warm zone radius
     * @return CompletableFuture that completes when all chunks are loaded
     */
    @Override
    public CompletableFuture<Void> warmLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        if (getWorldRegistry().getWorld(worldName) == null) {
            LOG.warning("[OptiPortal] warmLoad: world not loaded: " + worldName);
            return CompletableFuture.completedFuture(null);
        }

        // Use load balancer with normal priority for warm loads
        return loadBalancer.scheduleLoad(() -> {
            return enhancedWarmLoad(zoneId, worldName, cx, cz, radius);
        }, AsyncMetrics.AsyncTaskPriority.NORMAL);
    }

    /**
     * Enhanced warm load with asymmetric X/Z radii — used by WarmZoneManager for
     * corridor and bridge zones where width and depth differ.
     */
    @Override
    public CompletableFuture<Void> warmLoad(String zoneId, String worldName, int cx, int cz, int radiusX, int radiusZ) {
        if (getWorldRegistry().getWorld(worldName) == null) {
            LOG.warning("[OptiPortal] warmLoad: world not loaded: " + worldName);
            return CompletableFuture.completedFuture(null);
        }
        return loadBalancer.scheduleLoad(() -> {
            List<int[]> chunks = buildChunkListEnhanced(cx, cz, radiusX, radiusZ);
            int batchSize = Math.min(loadBalancer.calculateOptimalBatchSize(chunks.size()) * 2, 16);
            return loadChunksAsync(worldName, chunks, true, zoneId, batchSize);
        }, AsyncMetrics.AsyncTaskPriority.NORMAL);
    }
    
    /**
     * Enhanced predictive load implementation with better batching.
     */
    private CompletableFuture<Void> enhancedPredictiveLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        List<int[]> chunks = buildChunkListEnhanced(cx, cz, radius);

        // Use adaptive batch sizing
        int batchSize = loadBalancer.calculateOptimalBatchSize(chunks.size());

        CompletableFuture<Void> load = loadChunksAsync(worldName, chunks, false, zoneId, batchSize);
        if (zoneId != null) {
            return load.thenRunAsync(() -> cacheManager.setZoneTier(zoneId, CacheTier.HOT), executor);
        }
        return load;
    }
    
    /**
     * Enhanced warm load implementation with better batching.
     */
    private CompletableFuture<Void> enhancedWarmLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        List<int[]> chunks = buildChunkListEnhanced(cx, cz, radius);
        
        // Use larger batch size for warm loads (they're less time-sensitive)
        int batchSize = Math.min(loadBalancer.calculateOptimalBatchSize(chunks.size()) * 2, 16);
        
        return loadChunksAsync(worldName, chunks, true, zoneId, batchSize);
    }
    
    /**
     * Enhanced async chunk loading with proper error handling and batching.
     * 
     * @param worldName World name
     * @param chunks List of chunk coordinates
     * @param nonTicking Whether to load as non-ticking
     * @param zoneId Zone ID for ownership tracking
     * @param batchSize Batch size for processing
     * @return CompletableFuture that completes when all chunks are loaded
     */
    private CompletableFuture<Void> loadChunksAsync(String worldName, List<int[]> chunks, 
                                                    boolean nonTicking, String zoneId, int batchSize) {
        if (chunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // Guard 1: JVM heap — stop loading if approaching the engine's desperate-eviction
        // threshold (85%). A 5% margin gives the GC time to act before the engine starts
        // evicting aggressively. Pure Java — no Hytale API required.
        double heapUsed = 1.0 - (double) Runtime.getRuntime().freeMemory()
                                / Runtime.getRuntime().maxMemory();
        if (heapUsed >= 0.80) {
            LOG.warning(() -> "[OptiPortal] loadChunksAsync: aborting — JVM heap at "
                    + String.format("%.1f", heapUsed * 100) + "% (threshold 80%)");
            return CompletableFuture.completedFuture(null);
        }

        // Guard 2: Chunk count — abort if world already exceeds the configured ceiling.
        // Skip if threshold is -1 (disabled) or if AsyncLoadBalancer already enforces it.
        int pressureThreshold = getConfig().getMaxLoadedChunksPressureThreshold();
        if (pressureThreshold > 0) {
            World world = getWorldRegistry().getWorld(worldName);
            if (world != null) {
                int liveCount = world.getChunkStore().getLoadedChunksCount();
                if (liveCount >= pressureThreshold) {
                    LOG.warning(() -> "[OptiPortal] loadChunksAsync: aborting — chunk pressure limit ("
                            + liveCount + " >= " + pressureThreshold + ")");
                    return CompletableFuture.completedFuture(null);
                }
            }
        }
        
        // Process chunks in adaptive batches
        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<int[]> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));

            // Load batch with proper async handling
            CompletableFuture<Void> batchFuture = loadChunkBatch(worldName, batch, nonTicking, zoneId);
            batchFutures.add(batchFuture);
        }
        
        // Wait for all batches — ownership is registered per-chunk in loadChunkBatch
        return CompletableFuture.allOf(batchFutures.toArray(CompletableFuture[]::new))
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        LOG.fine(() -> "[OptiPortal] Enhanced chunk load completed: " + zoneId +
                                " (" + chunks.size() + " chunks)");
                    } else {
                        LOG.warning(() -> "[OptiPortal] Enhanced chunk load failed for " + zoneId + ": " + ex.getMessage());
                    }
                });
    }
    
    /**
     * Load a batch of chunks using the world thread bridge.
     *
     * @param worldName World name
     * @param batch List of chunk coordinates
     * @param nonTicking Whether to load as non-ticking
     * @param zoneId Zone ID for complexity scoring
     * @return CompletableFuture that completes when batch is loaded
     */
    private CompletableFuture<Void> loadChunkBatch(String worldName, List<int[]> batch,
                                                   boolean nonTicking, String zoneId) {
        // Resolve world once — avoids N ConcurrentHashMap lookups and enables null guard.
        // The world could unload between scheduleLoad submission and execution.
        // WorldThreadBridge.getChunkAsync handles null world, but returning early is cleaner.
        World world = getWorldRegistry().getWorld(worldName);
        if (world == null) {
            LOG.fine(() -> "[OptiPortal] loadChunkBatch: world '" + worldName + "' no longer loaded, skipping.");
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();
        double baseKbPerChunk = getBytesPerChunk() / 1024.0;

        for (int[] coord : batch) {
            final int cx = coord[0];
            final int cz = coord[1];
            long chunkIndex = ChunkUtil.indexChunk(cx, cz);

            // Guard A: backoff — mirrors ChunkPreloader.loadChunks. Skip chunks that
            // recently failed to load to avoid hammering broken positions.
            if (world.getChunkStore().isChunkOnBackoff(chunkIndex, ChunkStore.MAX_FAILURE_BACKOFF_NANOS)) {
                LOG.fine(() -> "[OptiPortal] loadChunkBatch: skipping (" + cx + ", " + cz
                        + ") — on failure backoff");
                chunkFutures.add(CompletableFuture.completedFuture(null));
                continue;
            }

            // Store.getComponent() requires the WorldThread — use the async API for all
            // chunks; the engine returns a completed future for already-resident chunks.

            // WorldThreadBridge.getChunkAsync already records success/error metrics with real
            // duration — do NOT call metrics.record* here to avoid double-counting.
            CompletableFuture<Void> chunkFuture = worldBridge.getChunkAsync(world, cx, cz, nonTicking)
                    .thenAcceptAsync(chunk -> {
                        if (chunk != null) {
                            cacheManager.registerOwnership(zoneId, worldName, cx, cz, chunk);
                            scoreAndRecord(chunk, zoneId, baseKbPerChunk, worldName);
                        }
                    }, executor)
                    .exceptionally(ex -> {
                        LOG.warning("[OptiPortal] Chunk load failed: " + cx + "," + cz
                                + ": " + ex.getMessage());
                        return null;
                    });

            chunkFutures.add(chunkFuture);
        }

        return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Score the loaded chunk and record the complexity and RAM estimate at FINE log level.
     * Uses complexityCache to avoid recomputing score() for already-seen chunks.
     *
     * @param chunk          The loaded WorldChunk (never null here)
     * @param zoneId         Zone ID for log context (may be null)
     * @param baseKbPerChunk Base KB per chunk from config
     * @param worldName      World name for cache key
     */
    private void scoreAndRecord(com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk,
                                String zoneId, double baseKbPerChunk, String worldName) {
        try {
            float complexity = complexityCache.getOrComputeScore(worldName, chunk.getX(), chunk.getZ(), chunk);
            double measuredRamMB = complexityCache.getOrComputeRam(
                    worldName, chunk.getX(), chunk.getZ(), chunk, baseKbPerChunk);
  
            LOG.fine("[OptiPortal] Chunk scored: zone=" + zoneId
                    + " chunk=" + chunk.getX() + "," + chunk.getZ()
                    + " complexity=" + String.format("%.3f", complexity)
                    + " measuredRamMB=" + String.format("%.4f", measuredRamMB));
          
            // Update PortalEntry with measured RAM after first load
            // This replaces the pre-load estimate with actual post-load measurement
            updatePortalEntryRamEstimate(zoneId, worldName, chunk.getX(), chunk.getZ(), measuredRamMB);
        } catch (Exception e) {
            LOG.fine("[OptiPortal] scoreAndRecord error: " + e.getMessage());
        }
    }
    
    /**
     * Update the PortalEntry's ramMarginalMB with the measured post-load value.
     * This implements Phase 3 improvement: RAM estimation using post-load chunk data.
     *
     * @param zoneId Zone ID (may be null for non-zone chunk loads)
     * @param worldName World name
     * @param cx Chunk X coordinate
     * @param cz Chunk Z coordinate
     * @param measuredRamMB Measured RAM in MB from post-load analysis
     */
    private void updatePortalEntryRamEstimate(String zoneId, String worldName, int cx, int cz, double measuredRamMB) {
        if (zoneId == null) return; // No zone context, skip update
        
        // Try to find a matching PortalEntry for this zone
        getStorage().loadById(zoneId).ifPresent(entry -> {
            // Update ramMarginalMB with measured value
            // This replaces the pre-load estimate with actual post-load measurement
            double oldMarginal = entry.getRamMarginalMB();
            entry.setRamMarginalMB(measuredRamMB);
  
            LOG.fine(() -> "[OptiPortal] Updated ramMarginalMB for zone '" + zoneId
                    + "': " + String.format("%.4f", oldMarginal) + " → "
                    + String.format("%.4f", measuredRamMB) + " MB");
  
            // Persist the updated entry
            getStorage().save(entry);
        });
    }
    
    /**
     * Get current load balancer statistics.
     * 
     * @return Load balancer statistics
     */
    public AsyncLoadBalancer.LoadStats getLoadStats() {
        return loadBalancer.getLoadStats();
    }
    
    /**
     * Get current performance metrics.
     *
     * @return Performance metrics
     */
    public AsyncMetrics.PerformanceSummary getPerformanceSummary() {
        return metrics.getPerformanceSummary();
    }
    
    /**
     * Enhanced chunk list builder with better prioritization.
     *
     * @param cx Center chunk X
     * @param cz Center chunk Z
     * @param radius Radius
     * @return List of chunk coordinates
     */
    private List<int[]> buildChunkListEnhanced(int cx, int cz, int radiusX, int radiusZ) {
        List<int[]> list = new ArrayList<>((2 * radiusX + 1) * (2 * radiusZ + 1));
        for (int dx = -radiusX; dx <= radiusX; dx++) {
            for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                list.add(new int[]{cx + dx, cz + dz});
            }
        }

        final boolean corridorEnabled = corridorIndex != null
                && !corridorIndex.isEmpty()
                && getConfig().isCorridorPrioritizationEnabled();

        list.sort((c1, c2) -> {
            int dist1 = Math.abs(c1[0] - cx) + Math.abs(c1[1] - cz);
            int dist2 = Math.abs(c2[0] - cx) + Math.abs(c2[1] - cz);

            if (corridorEnabled) {
                if (corridorIndex.isCorridor(c1[0], c1[1])) dist1 = Math.max(0, dist1 - 1);
                if (corridorIndex.isCorridor(c2[0], c2[1])) dist2 = Math.max(0, dist2 - 1);
            }

            return Integer.compare(dist1, dist2);
        });

        return list;
    }

    private List<int[]> buildChunkListEnhanced(int cx, int cz, int radius) {
        return buildChunkListEnhanced(cx, cz, radius, radius);
    }
}
