package com.optiportal.preload;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.optiportal.OptiPortal;
import com.optiportal.async.AsyncLoadBalancer;
import com.optiportal.async.AsyncMetrics;
import com.optiportal.async.WorldThreadBridge;
import com.optiportal.async.WorldTpsMonitor;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.model.CacheTier;
import com.optiportal.storage.StorageBackend;

/**
 * Enhanced chunk preloader with improved async handling.
 *
 * <p><b>DORMANT: This class is intentionally not wired into startup.</b>
 * It may be activated in a future pass if the original ChunkPreloader proves insufficient.
 *
 * <p>This extends the original ChunkPreloader with better async operations, load balancing,
 * and world thread isolation to prevent blocking.
 */
public class EnhancedChunkPreloader extends ChunkPreloader {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private static boolean pluginShuttingDownOrFalse() {
        OptiPortal plugin = OptiPortal.getInstance();
        return plugin != null && plugin.isShuttingDown();
    }
    
    private final ScheduledExecutorService executor;
    private final WorldThreadBridge worldBridge;
    private final AsyncLoadBalancer loadBalancer;
    private final AsyncMetrics metrics;

    // Cache manager reference for ownership registration
    private final CacheManager cacheManager;

    /** Optional corridor index for path-proximity prioritization. Null = disabled. */
    private final CorridorIndex corridorIndex;

    /** Optional TPS monitor for adaptive batch sizing (H5). Null = disabled. */
    private volatile WorldTpsMonitor tpsMonitor;
    
    /** Complexity score cache — avoids recomputing score() for already-seen chunks. */
    private final ChunkComplexityScorer.Cache complexityCache = new ChunkComplexityScorer.Cache();

    /**
     * Per-world, per-chunk retry tracker: world name → chunk index → nanos of last failure.
     * Guards against the server's quadratic backoff (as of 2026.03.26-89796e57b) which allows
     * retries after just 1ms on a first failure. Keyed by world name so entries are cleanly
     * evicted on world unload.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>> chunkFailureTimestamps =
            new ConcurrentHashMap<>();
    private static final long RETRY_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(10);

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
        this(config, cacheManager, worldRegistry, executor, worldBridge, loadBalancer, metrics,
             storage, metricsCollector, corridorIndex, EnhancedChunkPreloader::pluginShuttingDownOrFalse);
    }

    public EnhancedChunkPreloader(PluginConfig config,
                                  CacheManager cacheManager,
                                  WorldRegistry worldRegistry,
                                  ScheduledExecutorService executor,
                                  WorldThreadBridge worldBridge,
                                  AsyncLoadBalancer loadBalancer,
                                  AsyncMetrics metrics,
                                  StorageBackend storage,
                                  MetricsCollector metricsCollector,
                                  CorridorIndex corridorIndex,
                                  BooleanSupplier shuttingDown) {
        super(config, cacheManager, worldRegistry, executor, storage, metricsCollector, shuttingDown);
        this.executor = executor;
        this.worldBridge = worldBridge;
        this.loadBalancer = loadBalancer;
        this.metrics = metrics;
        this.cacheManager = cacheManager;
        this.corridorIndex = corridorIndex; // may be null if disabled
        
        // Invalidate cached scores when a world unloads
        worldRegistry.addWorldUnloadCallback(complexityCache::clearWorld);
        // Evict failure timestamps for the unloaded world to prevent unbounded growth
        worldRegistry.addWorldUnloadCallback(chunkFailureTimestamps::remove);
    }
    
    /** Inject TPS monitor after construction to break circular dependency. */
    public void setTpsMonitor(WorldTpsMonitor tpsMonitor) {
        this.tpsMonitor = tpsMonitor;
    }

    /**
     * TPS-adaptive batch size with three tiers:
     *   critical  (TPS < criticalThreshold): batch = 1  — server is struggling, go minimal
     *   low       (TPS < lowThreshold):      batch / 2  — server under load, ease off
     *   normal    (TPS >= lowThreshold):     full batch
     */
    @Override
    protected int getEffectiveBatchSize(String worldName) {
        WorldTpsMonitor mon = this.tpsMonitor;
        if (mon == null) return getConfig().getWarmBatchSize();
        if (mon.isCriticallyLoaded()) return 1;
        if (mon.isServerUnderLoad()) return Math.max(1, getConfig().getWarmBatchSize() / 2);
        return getConfig().getWarmBatchSize();
    }

    /**
     * TPS-adaptive inter-batch delay with three tiers:
     *   critical  (TPS < criticalThreshold): delay × 4  — server is struggling, slow way down
     *   low       (TPS < lowThreshold):      delay × 2  — server under load, give breathing room
     *   normal    (TPS >= lowThreshold):     base delay
     */
    @Override
    protected int getEffectiveBatchDelay(String worldName) {
        WorldTpsMonitor mon = this.tpsMonitor;
        if (mon == null) return getConfig().getWarmBatchDelayMs();
        if (mon.isCriticallyLoaded()) return getConfig().getWarmBatchDelayMs() * 4;
        if (mon.isServerUnderLoad()) return getConfig().getWarmBatchDelayMs() * 2;
        return getConfig().getWarmBatchDelayMs();
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
             loadBalancer, metrics, storage, metricsCollector, (CorridorIndex) null);
    }

    public EnhancedChunkPreloader(PluginConfig config,
                                  CacheManager cacheManager,
                                  WorldRegistry worldRegistry,
                                  ScheduledExecutorService executor,
                                  WorldThreadBridge worldBridge,
                                  AsyncLoadBalancer loadBalancer,
                                  AsyncMetrics metrics,
                                  StorageBackend storage,
                                  MetricsCollector metricsCollector,
                                  BooleanSupplier shuttingDown) {
        this(config, cacheManager, worldRegistry, executor, worldBridge,
             loadBalancer, metrics, storage, metricsCollector, null, shuttingDown);
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
        // In-flight dedup: if a load is already running for this zone, return its relay future.
        // Prevents N concurrent portal approaches from queuing N identical scheduleLoad tasks.
        return withInflightDedup(zoneId, () ->
            loadBalancer.scheduleLoad(
                () -> enhancedPredictiveLoad(zoneId, worldName, cx, cz, radius),
                AsyncMetrics.AsyncTaskPriority.HIGH)
        );
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
            
            // Dedup: claim already-owned chunks, only load new ones
            List<int[]> toLoad = new ArrayList<>();
            int skipped = 0;
            for (int[] coord : chunks) {
                if (cacheManager.isChunkOwned(worldName, coord[0], coord[1])) {
                    if (zoneId != null) cacheManager.registerOwnership(zoneId, worldName, coord[0], coord[1]);
                    skipped++;
                } else {
                    toLoad.add(coord);
                }
            }
            final int skippedCount = skipped;
            if (skippedCount > 0) LOG.fine(() -> "[OptiPortal] warmLoad " + zoneId + ": skipped " + skippedCount + " already-owned chunks");
            
            int batchSize = Math.min(loadBalancer.calculateOptimalBatchSize(toLoad.size()) * 2, 16);
            return toLoad.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : loadChunksAsync(worldName, toLoad, true, zoneId, batchSize);
        }, AsyncMetrics.AsyncTaskPriority.NORMAL);
    }
    
    /**
     * Enhanced predictive load implementation with better batching.
     */
    private CompletableFuture<Void> enhancedPredictiveLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        List<int[]> chunks = buildChunkListEnhanced(cx, cz, radius);

        // Dedup: claim already-owned chunks, only load new ones
        List<int[]> toLoad = new ArrayList<>();
        int skipped = 0;
        for (int[] coord : chunks) {
            if (cacheManager.isChunkOwned(worldName, coord[0], coord[1])) {
                if (zoneId != null) cacheManager.registerOwnership(zoneId, worldName, coord[0], coord[1]);
                skipped++;
            } else {
                toLoad.add(coord);
            }
        }
        final int skippedCount = skipped;
        if (skippedCount > 0) LOG.fine(() -> "[OptiPortal] enhancedPredictiveLoad " + zoneId + ": skipped " + skippedCount + " already-owned chunks");

        // Use adaptive batch sizing
        int batchSize = loadBalancer.calculateOptimalBatchSize(toLoad.size());

        CompletableFuture<Void> load = toLoad.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : loadChunksAsync(worldName, toLoad, false, zoneId, batchSize);
        if (zoneId != null) {
            final String zid = zoneId;
            // U1: thenRunAsync does not fire on a failed future (ChunkLoadAbortedException),
            //     so HOT promotion is suppressed when the load was aborted by a guard.
            return load.thenRunAsync(() -> {
                if (isShuttingDown()) return;
                cacheManager.setZoneTier(zid, CacheTier.HOT);
            }, executor)
                    .exceptionally(ex -> {
                        if (ex instanceof ChunkLoadAbortedException || ex.getCause() instanceof ChunkLoadAbortedException) {
                            LOG.fine("[OptiPortal] enhancedPredictiveLoad aborted for " + zid + " — tier not promoted: " + ex.getMessage());
                        } else {
                            LOG.warning("[OptiPortal] enhancedPredictiveLoad error for " + zid + ": " + ex.getMessage());
                        }
                        return null;
                    });
        }
        return load;
    }
    
    /**
     * Enhanced warm load implementation with better batching.
     */
    private CompletableFuture<Void> enhancedWarmLoad(String zoneId, String worldName, int cx, int cz, int radius) {
        List<int[]> chunks = buildChunkListEnhanced(cx, cz, radius);
        
        // Dedup: claim already-owned chunks, only load new ones
        List<int[]> toLoad = new ArrayList<>();
        int skipped = 0;
        for (int[] coord : chunks) {
            if (cacheManager.isChunkOwned(worldName, coord[0], coord[1])) {
                if (zoneId != null) cacheManager.registerOwnership(zoneId, worldName, coord[0], coord[1]);
                skipped++;
            } else {
                toLoad.add(coord);
            }
        }
        final int skippedCount = skipped;
        if (skippedCount > 0) LOG.fine(() -> "[OptiPortal] enhancedWarmLoad " + zoneId + ": skipped " + skippedCount + " already-owned chunks");
        
        // Use larger batch size for warm loads (they're less time-sensitive)
        int batchSize = Math.min(loadBalancer.calculateOptimalBatchSize(toLoad.size()) * 2, 16);
        
        return toLoad.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : loadChunksAsync(worldName, toLoad, true, zoneId, batchSize);
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

        // H5: apply TPS-adaptive batch size cap — getEffectiveBatchSize() halves under low TPS.
        // Takes the smaller of the load-balancer's suggestion and the TPS-adaptive limit so
        // both mechanisms contribute without fighting each other.
        batchSize = Math.min(batchSize, getEffectiveBatchSize(worldName));

        // Guard 1: JVM heap — stop loading if approaching the engine's desperate-eviction
        // threshold (80%). Uses actual used/max ratio: (totalMemory - freeMemory) / maxMemory.
        // NOTE: do NOT use (1 - freeMemory/maxMemory) — that over-estimates usage when the JVM
        // has not yet expanded the heap to -Xmx, causing false aborts on startup.
        Runtime rt = Runtime.getRuntime();
        double heapUsed = (double)(rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        if (heapUsed >= 0.80) {
            String reason = "JVM heap at " + String.format("%.1f", heapUsed * 100) + "% (threshold 80%)";
            LOG.warning(() -> "[OptiPortal] loadChunksAsync: aborting — " + reason);
            // U1: Return a failed future so thenRunAsync does NOT fire and the zone
            // is not promoted to HOT when no chunks were actually loaded.
            return CompletableFuture.failedFuture(new ChunkLoadAbortedException(reason));
        }

        // Guard 2: Chunk count — abort if world already exceeds the configured ceiling.
        // Skip if threshold is -1 (disabled) or if AsyncLoadBalancer already enforces it.
        int pressureThreshold = getConfig().getMaxLoadedChunksPressureThreshold();
        if (pressureThreshold > 0) {
            World world = getWorldRegistry().getWorld(worldName);
            if (world != null) {
                int liveCount = world.getChunkStore().getLoadedChunksCount();
                if (liveCount >= pressureThreshold) {
                    String reason = "chunk pressure limit (" + liveCount + " >= " + pressureThreshold + ")";
                    LOG.warning(() -> "[OptiPortal] loadChunksAsync: aborting — " + reason);
                    // U1: Same — failed future suppresses HOT promotion.
                    return CompletableFuture.failedFuture(new ChunkLoadAbortedException(reason));
                }
            }
        }
        
        // Guard 3: GC backoff — if a GC ran on the world thread since the last tick,
        // skip this load to avoid adding allocation pressure during recovery.
        // consumeGCHasRun() is a one-shot flag that must be read on the world thread.
        World gcWorld = getWorldRegistry().getWorld(worldName);
        if (gcWorld != null && gcWorld.isTicking() && !gcWorld.isPaused()) {
            final int finalBatchSize = batchSize;
            return CompletableFuture.supplyAsync(gcWorld::consumeGCHasRun, gcWorld)
                    .thenComposeAsync(gcRan -> {
                        if (gcRan) {
                            LOG.fine(() -> "[OptiPortal] loadChunksAsync: GC detected, deferring preload for " + worldName);
                            return CompletableFuture.completedFuture(null);
                        }
                        return buildLoadChain(worldName, chunks, nonTicking, zoneId, finalBatchSize);
                    }, executor);
        }

        return buildLoadChain(worldName, chunks, nonTicking, zoneId, batchSize);
    }

    private CompletableFuture<Void> buildLoadChain(String worldName, List<int[]> chunks,
                                                   boolean nonTicking, String zoneId, int batchSize) {
        // Process chunks in sequential batches so each batch starts only after the previous
        // completes — prevents all batches hammering the world thread simultaneously.
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (int i = 0; i < chunks.size(); i += batchSize) {
            final List<int[]> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            chain = chain.thenCompose(ignored -> loadChunkBatch(worldName, batch, nonTicking, zoneId));
        }

        // Ownership is registered per-chunk in loadChunkBatch
        return chain.whenComplete((result, ex) -> {
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

            // Guard B: OptiPortal retry cooldown — the server's isChunkOnBackoff now uses a
            // quadratic ramp (count²×1ms, capped at 10s) since 2026.03.26-89796e57b, so a
            // first failure only suppresses retries for ~1ms. Enforce our own minimum cooldown
            // to avoid hammering transiently-failing chunks on frequent preload triggers.
            ConcurrentHashMap<Long, Long> worldFailures = chunkFailureTimestamps.get(worldName);
            if (worldFailures != null) {
                Long lastFailed = worldFailures.get(chunkIndex);
                if (lastFailed != null && System.nanoTime() - lastFailed < RETRY_COOLDOWN_NANOS) {
                    LOG.fine(() -> "[OptiPortal] loadChunkBatch: skipping (" + cx + ", " + cz
                            + ") — OptiPortal retry cooldown active");
                    chunkFutures.add(CompletableFuture.completedFuture(null));
                    continue;
                }
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
                            // Clear failure record now that this chunk loaded successfully.
                            ConcurrentHashMap<Long, Long> wf = chunkFailureTimestamps.get(worldName);
                            if (wf != null) {
                                wf.remove(chunkIndex);
                                if (wf.isEmpty()) chunkFailureTimestamps.remove(worldName, wf);
                            }
                        }
                    }, executor)
                    .exceptionally(ex -> {
                        // Record failure timestamp so Guard B suppresses rapid retries.
                        chunkFailureTimestamps.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                                .put(chunkIndex, System.nanoTime());
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
            LOG.fine("[OptiPortal] Chunk scored: zone=" + zoneId
                    + " chunk=" + chunk.getX() + "," + chunk.getZ()
                    + " complexity=" + String.format("%.3f", complexity));
        } catch (Exception e) {
            LOG.fine("[OptiPortal] scoreAndRecord error: " + e.getMessage());
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

        // A3: Use Chebyshev distance instead of Manhattan for better chunk loading prioritization
        list.sort((c1, c2) -> {
            int dist1 = Math.max(Math.abs(c1[0] - cx), Math.abs(c1[1] - cz));
            int dist2 = Math.max(Math.abs(c2[0] - cx), Math.abs(c2[1] - cz));

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
