package com.optiportal.preload;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import com.optiportal.async.AsyncLoadBalancer;
import com.optiportal.async.AsyncMetrics;
import com.optiportal.async.WorldThreadBridge;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.storage.StorageBackend;

/**
 * Enhanced chunk preloader with improved async handling.
 * 
 * This extends the original ChunkPreloader with better async operations,
 * load balancing, and world thread isolation to prevent blocking.
 */
public class EnhancedChunkPreloader extends ChunkPreloader {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final WorldThreadBridge worldBridge;
    private final AsyncLoadBalancer loadBalancer;
    private final AsyncMetrics metrics;
    
    // Cache manager reference for ownership registration
    private final CacheManager cacheManager;
    
    public EnhancedChunkPreloader(PluginConfig config,
                                 CacheManager cacheManager,
                                 WorldRegistry worldRegistry,
                                 ScheduledExecutorService executor,
                                 WorldThreadBridge worldBridge,
                                 AsyncLoadBalancer loadBalancer,
                                 AsyncMetrics metrics,
                                 StorageBackend storage) {
        super(config, cacheManager, worldRegistry, executor, storage, null);
        this.worldBridge = worldBridge;
        this.loadBalancer = loadBalancer;
        this.metrics = metrics;
        this.cacheManager = cacheManager;
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
     * Enhanced predictive load implementation with better batching.
     */
    private CompletableFuture<Void> enhancedPredictiveLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        List<int[]> chunks = buildChunkListEnhanced(cx, cz, radius);
        
        // Use adaptive batch sizing
        int batchSize = loadBalancer.calculateOptimalBatchSize(chunks.size());
        
        return loadChunksAsync(worldName, chunks, false, zoneId, batchSize);
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
        
        // Process chunks in adaptive batches
        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<int[]> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            
            // Load batch with proper async handling
            CompletableFuture<Void> batchFuture = loadChunkBatch(worldName, batch, nonTicking);
            batchFutures.add(batchFuture);
        }
        
        // Wait for all batches with proper error handling
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        // Register ownership after successful load
                        registerChunkOwnership(zoneId, worldName, chunks);
                        LOG.info("[OptiPortal] Enhanced chunk load completed: " + zoneId + 
                                " (" + chunks.size() + " chunks)");
                    } else {
                        LOG.warning("[OptiPortal] Enhanced chunk load failed for " + zoneId + ": " + ex.getMessage());
                    }
                });
    }
    
    /**
     * Load a batch of chunks using the world thread bridge.
     * 
     * @param worldName World name
     * @param batch List of chunk coordinates
     * @param nonTicking Whether to load as non-ticking
     * @return CompletableFuture that completes when batch is loaded
     */
    private CompletableFuture<Void> loadChunkBatch(String worldName, List<int[]> batch, boolean nonTicking) {
        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();
        
        for (int[] coord : batch) {
            CompletableFuture<Void> chunkFuture = worldBridge.getChunkAsync(
                getWorldRegistry().getWorld(worldName), coord[0], coord[1], nonTicking)
                    .thenRun(() -> {
                        // Chunk loaded successfully
                        metrics.recordChunkLoadSuccess(coord[0], coord[1], 0);
                    })
                    .exceptionally(ex -> {
                        // Handle chunk load error
                        metrics.recordChunkLoadError(coord[0], coord[1], 0);
                        LOG.warning("[OptiPortal] Chunk load failed: " + coord[0] + "," + coord[1] + ": " + ex.getMessage());
                        return null;
                    });
            
            chunkFutures.add(chunkFuture);
        }
        
        return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Register chunk ownership after successful load.
     * 
     * @param zoneId Zone ID
     * @param worldName World name
     * @param chunks List of chunk coordinates
     */
    private void registerChunkOwnership(String zoneId, String worldName, List<int[]> chunks) {
        for (int[] coord : chunks) {
            cacheManager.registerOwnership(zoneId, worldName, coord[0], coord[1]);
        }
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
    private List<int[]> buildChunkListEnhanced(int cx, int cz, int radius) {
        List<int[]> list = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                list.add(new int[]{cx + dx, cz + dz});
            }
        }
        // Sort centre-outward for better loading priority
        list.sort((c1, c2) -> Integer.compare(
            Math.abs(c1[0] - cx) + Math.abs(c1[1] - cz),
            Math.abs(c2[0] - cx) + Math.abs(c2[1] - cz)
        ));
        return list;
    }
}