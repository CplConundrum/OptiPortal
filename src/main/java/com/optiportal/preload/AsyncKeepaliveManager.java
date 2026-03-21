package com.optiportal.preload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import com.optiportal.async.AsyncLoadBalancer;
import com.optiportal.async.AsyncMetrics;
import com.optiportal.async.WorldThreadBridge;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.storage.StorageBackend;

/**
 * Enhanced keepalive manager with improved async operations.
 * 
 * This extends the original KeepaliveManager with better async handling,
 * batched operations, and reduced world thread impact.
 */
public class AsyncKeepaliveManager extends KeepaliveManager {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final WorldThreadBridge worldBridge;
    private final AsyncLoadBalancer loadBalancer;
    private final AsyncMetrics metrics;
    private final com.optiportal.metrics.MetricsCollector metricsCollector;

    /** Injected after construction to avoid circular dependency with AsyncTeleportInterceptor. */
    private volatile java.util.function.Supplier<java.util.List<com.optiportal.model.PortalEntry>>
            portalCacheSupplier;
    
    // Batch processing configuration
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_BATCH_SIZE = 100;
    
    public AsyncKeepaliveManager(PluginConfig config,
                                CacheManager cacheManager,
                                ChunkPreloader chunkPreloader,
                                StorageBackend storage,
                                ScheduledExecutorService executor,
                                WorldThreadBridge worldBridge,
                                AsyncLoadBalancer loadBalancer,
                                AsyncMetrics metrics,
                                com.optiportal.metrics.MetricsCollector metricsCollector) {
        super(config, cacheManager, chunkPreloader, storage, executor);
        
        this.worldBridge = worldBridge;
        this.loadBalancer = loadBalancer;
        this.metrics = metrics;
        this.metricsCollector = metricsCollector;
    }

    public void setPortalCacheSupplier(
            java.util.function.Supplier<java.util.List<com.optiportal.model.PortalEntry>> supplier) {
        this.portalCacheSupplier = supplier;
    }
    
    /**
     * Enhanced tier ping with batched async operations.
     *
     * @param tier Cache tier to ping
     * @param label Label for logging
     */
    @Override
    protected void pingTier(CacheTier tier, String label) {
        // Group operations by world to minimize context switching
        Map<String, List<ChunkCoordinate>> chunksByWorld = groupChunksByWorld(tier);
        
        if (chunksByWorld.isEmpty()) {
            return;
        }
        
        // Process each world's chunks in batches with load balancer
        List<CompletableFuture<Void>> worldFutures = new ArrayList<>();
        
        for (Map.Entry<String, List<ChunkCoordinate>> entry : chunksByWorld.entrySet()) {
            String worldName = entry.getKey();
            List<ChunkCoordinate> chunks = entry.getValue();
   
            CompletableFuture<Void> worldFuture = processWorldChunks(worldName, chunks, tier, label);
            worldFutures.add(worldFuture);
        }
        
        // Wait for all worlds with proper error handling
        CompletableFuture.allOf(worldFutures.toArray(new CompletableFuture[0]))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        LOG.warning("Keepalive error for tier " + tier + ": " + ex.getMessage());
                        metrics.recordKeepaliveError(tier.name());
                    } else {
                        int totalChunks = chunksByWorld.values().stream()
                                .mapToInt(List::size).sum();
                        LOG.info("Keepalive " + label + ": pinged " + totalChunks + 
                                " chunks across " + chunksByWorld.size() + " worlds");
                    }
                });
    }
    
    /**
     * Process chunks for a specific world with batched operations.
     * 
     * @param worldName World name
     * @param chunks List of chunk coordinates
     * @param tier Cache tier
     * @param label Label for logging
     * @return CompletableFuture that completes when processing is done
     */
    private CompletableFuture<Void> processWorldChunks(String worldName, List<ChunkCoordinate> chunks,
                                                      CacheTier tier, String label) {
        // Calculate optimal batch size based on current load
        int batchSize = calculateOptimalBatchSize(chunks.size());
        
        // Process chunks in batches
        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<ChunkCoordinate> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
  
            CompletableFuture<Void> batchFuture = loadBalancer.scheduleLoad(() -> {
                return pingChunkBatch(worldName, batch, tier);
            }, AsyncMetrics.AsyncTaskPriority.NORMAL);
  
            batchFutures.add(batchFuture);
        }
        
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Ping a batch of chunks for a world.
     * 
     * @param worldName World name
     * @param batch List of chunk coordinates
     * @param tier Cache tier
     * @return CompletableFuture that completes when batch is done
     */
    private CompletableFuture<Void> pingChunkBatch(String worldName, List<ChunkCoordinate> batch, CacheTier tier) {
        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();
        
        for (ChunkCoordinate coord : batch) {
            // WorldThreadBridge.getChunkAsync already records success/error metrics — do not double-count.
            CompletableFuture<Void> chunkFuture = worldBridge.getChunkAsync(
                getChunkPreloader().getWorldRegistry().getWorld(worldName),
                coord.cx, coord.cz, true) // Non-ticking for keepalive
                .thenRun(() -> {
                    // Chunk pinged — metrics recorded by WorldThreadBridge
                })
                .exceptionally(ex -> {
                    LOG.fine("Keepalive chunk ping failed: " + coord.cx + "," + coord.cz + ": " + ex.getMessage());
                    return null;
                });
  
            chunkFutures.add(chunkFuture);
        }
        
        return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Group chunks by world for batched processing.
     *
     * @param tier Cache tier
     * @return Map of world name to chunk coordinates
     */
    private Map<String, List<ChunkCoordinate>> groupChunksByWorld(CacheTier tier) {
        Map<String, List<ChunkCoordinate>> result = new HashMap<>();
        
        List<PortalEntry> entries = portalCacheSupplier != null
                ? portalCacheSupplier.get()
                : getStorage().loadAll();
        for (PortalEntry entry : entries) {
            if (entry.isInstanced()) continue;
            if (getCacheManager().getZoneTier(entry.getId()) != tier) continue;

            String worldName = entry.getWorld();
            List<ChunkCoordinate> worldChunks = result.computeIfAbsent(worldName, k -> new ArrayList<>());

            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            int radiusX = resolveRadiusX(entry);
            int radiusZ = resolveRadiusZ(entry);

            // Add all chunks in the zone
            for (int dx = -radiusX; dx <= radiusX; dx++) {
                for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                    worldChunks.add(new ChunkCoordinate(cx + dx, cz + dz));
                }
            }
        }
        
        return result;
    }
    
    /**
     * Calculate optimal batch size based on current load.
     *
     * @param totalChunks Total number of chunks to process
     * @return Optimal batch size
     */
    private int calculateOptimalBatchSize(int totalChunks) {
        AsyncLoadBalancer.LoadStats loadStats = loadBalancer.getLoadStats();
        
        // Adjust batch size based on current load
        int batchSize = DEFAULT_BATCH_SIZE;
        
        if (loadStats.activeOperations > 10) {
            // High load - reduce batch size
            batchSize = Math.max(10, DEFAULT_BATCH_SIZE / 2);
        } else if (loadStats.activeOperations < 3 && loadStats.averageExecutionTime < 50) {
            // Low load and good performance - increase batch size
            batchSize = Math.min(MAX_BATCH_SIZE, DEFAULT_BATCH_SIZE * 2);
        }
        
        // Ensure we don't exceed total chunks
        return Math.min(batchSize, totalChunks);
    }
    
    /**
     * Get current async performance statistics.
     * 
     * @return Performance statistics
     */
    public AsyncLoadBalancer.LoadStats getAsyncLoadStats() {
        return loadBalancer.getLoadStats();
    }
    
    /**
     * Get current performance metrics.
     * 
     * @return Performance metrics
     */
    public AsyncMetrics.PerformanceSummary getAsyncPerformanceSummary() {
        return metrics.getPerformanceSummary();
    }
    
    /**
     * Chunk coordinate data class.
     */
    private static class ChunkCoordinate {
        public final int cx;
        public final int cz;
        
        public ChunkCoordinate(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
        
        @Override
        public String toString() {
            return cx + "," + cz;
        }
    }
}
