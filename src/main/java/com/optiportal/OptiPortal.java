package com.optiportal;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.optiportal.async.AsyncErrorHandler;
import com.optiportal.async.AsyncLoadBalancer;
import com.optiportal.async.AsyncMetrics;
import com.optiportal.async.CircuitBreaker;
import com.optiportal.async.WorldThreadBridge;
import com.optiportal.async.WorldTpsMonitor;
import com.optiportal.cache.CacheManager;
import com.optiportal.cache.ChunkOwnershipAuditor;
import com.optiportal.cache.SnapshotScheduler;
import com.optiportal.cache.WalManager;
import com.optiportal.cache.ZoneTtlEnforcer;
import com.optiportal.commands.PreloadCommand;
import com.optiportal.config.PluginConfig;
import com.optiportal.integrations.GravestoneIntegration;
import com.optiportal.integrations.WarpFileWatcher;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.metrics.bStatsIntegration;
import com.optiportal.player.DeathLocationTracker;
import com.optiportal.player.RespawnTracker;
import com.optiportal.preload.AsyncKeepaliveManager;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.preload.CorridorIndex;
import com.optiportal.preload.EnhancedChunkPreloader;
import com.optiportal.preload.PortalChunkListener;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.preload.WorldRegistry;
import com.optiportal.storage.JsonStorageBackend;
import com.optiportal.storage.StorageBackend;
import com.optiportal.storage.StorageFactory;
import com.optiportal.systems.PlayerDeathSystem;
import com.optiportal.teleport.AsyncTeleportInterceptor;
import com.optiportal.teleport.TeleportInterceptor;
import com.optiportal.update.UpdateChecker;

/**
 * OptiPortal - Hytale server optimization plugin.
 *
 * Intelligently pre-loads warp destination chunks to eliminate MSTP spikes
 * caused by teleportation and zone transitions.
 *
 * Features:
 * - WARM zones: permanently cached chunk geometry, no entity ticking
 * - PREDICTIVE zones: pre-load on portal approach, cache after first visit
 * - Chunk registry with co-ownership deduplication
 * - Three-tier cache (HOT/WARM/COLD) backed by WAL crash protection
 * - Multi-backend storage (JSON/H2/SQLite/MySQL) with bidirectional migration
 * - Bed and death location pre-loading
 * - Gravestone integration for precise cache release
 * - RAM measurement via Java Instrumentation with terrain profiling
 * - Native UI panel and full command interface
 * - Async-optimized architecture for reduced world thread blocking
 */
public class OptiPortal extends JavaPlugin {

    private static OptiPortal instance;

    // Core services
    private PluginConfig config;
    private StorageBackend storage;
    private ScheduledExecutorService executor;

    // Async infrastructure
    private WorldThreadBridge worldBridge;
    private AsyncLoadBalancer loadBalancer;
    private AsyncMetrics metrics;
    private AsyncErrorHandler errorHandler;

    // Cache layer
    private CacheManager cacheManager;
    private WalManager walManager;
    private SnapshotScheduler snapshotScheduler;
    private ChunkOwnershipAuditor ownershipAuditor;
    private ZoneTtlEnforcer ttlEnforcer;

    // Zone management
    private WorldRegistry worldRegistry;
    private WarmZoneManager warmZoneManager;
    private ChunkPreloader chunkPreloader;
    private AsyncKeepaliveManager keepaliveManager;
    private com.optiportal.preload.PortalLinkRegistry portalLinkRegistry;
    private PortalChunkListener portalChunkListener;

    // Player tracking
    private RespawnTracker respawnTracker;
    private DeathLocationTracker deathLocationTracker;

    // Integrations
    private WarpFileWatcher warpFileWatcher;
    private GravestoneIntegration gravestoneIntegration;

    // Teleport handling
    private AsyncTeleportInterceptor teleportInterceptor;

    // Metrics
    private MetricsCollector metricsCollector;

    // TPS monitor
    private WorldTpsMonitor tpsMonitor;

    // Corridor index (Phase 4)
    private CorridorIndex corridorIndex;

    // ECS systems
    private PlayerDeathSystem playerDeathSystem;

    // --- Public API for other plugins ---

    public OptiPortal(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void start0() {
        try {
            getLogger().at(Level.INFO).log("[OptiPortal] Starting up...");

            // Increased executor pool size for better concurrency (8 threads)
            executor = Executors.newScheduledThreadPool(8);

            // Load config — getDataDirectory() returns java.nio.file.Path
            config = PluginConfig.load(getDataDirectory().toFile());

            // Initialize storage — handles meta.txt detection and auto-migration if backend changed
            storage = StorageFactory.create(config);

            // WAL manager for crash protection
            walManager = new WalManager(config);

            // World registry — populated via AddWorldEvent/RemoveWorldEvent
            worldRegistry = new WorldRegistry();
            worldRegistry.register(getEventRegistry());

            // TPS monitor — must come after worldRegistry, before loadBalancer and worldBridge
            if (config.isTpsMonitorEnabled()) {
                tpsMonitor = new WorldTpsMonitor(worldRegistry, executor);
                tpsMonitor.start();
            }

            // Metrics collector — created early so CacheManager can share the same instance
            metricsCollector = new MetricsCollector();

            // Cache manager — must be after worldRegistry for tryReleaseKeepLoaded
            // P11: Create registry file path and pass to CacheManager constructor
            File registryFile = new File(getDataDirectory().toFile(), "cache-registry.json");
            cacheManager = new CacheManager(config, walManager, executor, worldRegistry, registryFile, metricsCollector);
            // P11: Load persisted registry before staged load so warm zones are restored
            cacheManager.loadRegistry();
            // Warm up stream lambdas in getTotalSharedChunks() so the JVM defines their
            // inner classes here (off the world thread) rather than on first UI open.
            cacheManager.getTotalSharedChunks();

            // Ownership drift auditor — requires both cacheManager and worldRegistry
            ownershipAuditor = new ChunkOwnershipAuditor(cacheManager, worldRegistry, executor, config);
            ownershipAuditor.start();


            // Initialize async infrastructure — must come before EnhancedChunkPreloader
            metrics = new AsyncMetrics();
            errorHandler = new AsyncErrorHandler(metrics, new CircuitBreaker(), executor);
            worldBridge = new WorldThreadBridge(executor, errorHandler, metrics, tpsMonitor);
            loadBalancer = new AsyncLoadBalancer(executor, metrics, errorHandler, tpsMonitor, worldRegistry);

            // CorridorIndex — build after worldRegistry is available
            if (config.isCorridorPrioritizationEnabled()) {
                corridorIndex = new CorridorIndex(worldRegistry, config.getCorridorRadiusChunks());
                corridorIndex.registerEventListener(getEventRegistry());
                executor.submit(corridorIndex::buildIndex);
            } else {
                corridorIndex = null;
            }

            // Zone manager
            warmZoneManager = new WarmZoneManager(config, storage, cacheManager, executor);
            chunkPreloader = new EnhancedChunkPreloader(config, cacheManager, worldRegistry, executor,
                    worldBridge, loadBalancer, metrics, storage, metricsCollector, corridorIndex);
            warmZoneManager.setChunkPreloader(chunkPreloader); // break circular dep via setter
            // H5: inject TPS monitor for adaptive batch sizing
            if (chunkPreloader instanceof EnhancedChunkPreloader) {
                ((EnhancedChunkPreloader) chunkPreloader).setTpsMonitor(tpsMonitor);
            }

            // PortalChunkListener — auto-detects PortalDevice blocks and promotes COLD zones
            portalChunkListener = new PortalChunkListener(storage, cacheManager, warmZoneManager, config);

            // Register AllWorldsLoadedEvent listener — actual chunk loading is gated on this
            warmZoneManager.registerWorldsLoadedListener(getEventRegistry());

            // Auto-detect portal destination worlds when they register
            worldRegistry.addWorldLoadCallback(warmZoneManager::scanWorldForPortalDestination);
            // Register portal chunk listener for new worlds
            worldRegistry.addWorldLoadCallback(portalChunkListener::register);

            // H6: evict stale zone state when a world is destroyed
            worldRegistry.addWorldRemoveCallback(warmZoneManager::onWorldRemoved);
            worldRegistry.addWorldRemoveCallback(portalChunkListener::onWorldRemoved);

            // Seed from Universe to capture already-live worlds (fires callbacks)
            worldRegistry.seedFromUniverse();

            // Player trackers
            respawnTracker = new RespawnTracker(config, storage, cacheManager, chunkPreloader);
            deathLocationTracker = new DeathLocationTracker(config, storage, cacheManager, chunkPreloader);

            // Portal link registry — learns links from observed teleports
            portalLinkRegistry = new com.optiportal.preload.PortalLinkRegistry(getDataDirectory().toFile(), executor, config);

            // Gravestone integration — must be before teleportInterceptor so the reference is non-null
            gravestoneIntegration = new GravestoneIntegration(config, deathLocationTracker);
            gravestoneIntegration.init(getEventRegistry());

            // Teleport interceptor - use async version for better performance
            teleportInterceptor = new AsyncTeleportInterceptor(this, config, warmZoneManager, chunkPreloader, storage, portalLinkRegistry, respawnTracker, deathLocationTracker, gravestoneIntegration, executor, worldBridge, loadBalancer, metrics);

            // Wire cache updater to storage backend for portal cache invalidation
            if (storage instanceof JsonStorageBackend) {
                ((JsonStorageBackend) storage).setCacheUpdater(
                    entries -> teleportInterceptor.updatePortalCache(entries)
                );
            }

            // File watchers
            warpFileWatcher = new WarpFileWatcher(config, storage, warmZoneManager, executor,
                    teleportInterceptor::refreshPortalCache);
            warpFileWatcher.start();

            // Register events
            registerEvents();

            // Register commands
            getCommandRegistry().registerCommand(new PreloadCommand(this));

            // Metrics
            if (config.isMetricsEnabled()) {
                new bStatsIntegration(this, metricsCollector).init();
            }

            // Snapshot scheduler
            snapshotScheduler = new SnapshotScheduler(config, cacheManager, executor);
            snapshotScheduler.start();

            // Staged startup cache load
            warmZoneManager.startStagedLoad();

            // Keepalive heartbeat - use async version
            keepaliveManager = new AsyncKeepaliveManager(
                    config, cacheManager, chunkPreloader, storage, executor, worldBridge, loadBalancer, metrics, metricsCollector);
            keepaliveManager.start();
            keepaliveManager.setPortalCacheSupplier(teleportInterceptor::getPortalCache);

            // TTL enforcer — runs scheduled cleanup to evict expired entries
            ttlEnforcer = new ZoneTtlEnforcer(config, storage, cacheManager, executor);
            ttlEnforcer.start();

            // ECS PlayerDeathSystem — registers via EntityStore.REGISTRY to track player deaths
            try {
                playerDeathSystem = new PlayerDeathSystem(deathLocationTracker);
                EntityStore.REGISTRY.registerSystem(playerDeathSystem);
            } catch (Exception e) {
                getLogger().at(Level.WARNING).log(
                        "[OptiPortal] PlayerDeathSystem registration failed — death location preloading disabled: " + e.getMessage());
                playerDeathSystem = null;
            }

            // Update checker
            if (config.isUpdateCheckerEnabled()) {
                new UpdateChecker(this).checkAsync(executor);
            }

            // Shutdown hook for clean serialization on SIGTERM
            Runtime.getRuntime().addShutdownHook(new Thread(this::doShutdown));

            getLogger().at(Level.INFO).log("[OptiPortal] Enabled successfully.");

        } catch (Exception e) {
            getLogger().at(Level.SEVERE).log("[OptiPortal] Failed to enable: " + e.getMessage());
            e.printStackTrace();
        }
    }

    protected void shutdown0(boolean force) {
        doShutdown();
    }

    private void doShutdown() {
        getLogger().at(Level.INFO).log("[OptiPortal] Shutdown detected, serializing warm zones...");
        try {
            if (warpFileWatcher != null) warpFileWatcher.stop();
            if (snapshotScheduler != null) snapshotScheduler.stop();
            if (keepaliveManager != null) keepaliveManager.stop();
            if (ttlEnforcer != null) ttlEnforcer.stop();
            if (playerDeathSystem != null && EntityStore.REGISTRY.hasSystemClass(PlayerDeathSystem.class)) {
                EntityStore.REGISTRY.unregisterSystem(PlayerDeathSystem.class);
            }
            if (portalLinkRegistry != null) portalLinkRegistry.flush();
            if (warmZoneManager != null) warmZoneManager.serializeAll();
            if (cacheManager != null) cacheManager.saveRegistry();
            if (storage != null) storage.close();
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            getLogger().at(Level.INFO).log("[OptiPortal] Cache saved cleanly.");
        } catch (Exception e) {
            getLogger().at(Level.SEVERE).log("[OptiPortal] Error during shutdown: " + e.getMessage());
        }
    }

    private void registerEvents() {
        teleportInterceptor.registerEvents(getEventRegistry());
    }

    // --- Hot reload ---

    /**
     * Reloads config.json from disk and propagates all live-reloadable settings
     * to their respective managers without a server restart.
     *
     * <p>Settings that take effect immediately:
     * <ul>
     *   <li>decay timings (hotDecaySeconds, warmDecayMinutes, pollIntervalSeconds)
     *   <li>keepalive toggles and intervals — tasks are rescheduled
     *   <li>snapshot interval — rescheduled
     *   <li>activation thresholds, TTLs, RAM warning suppression, lowTrafficThreshold
     *   <li>warp file path / watch interval — watcher is restarted
     *   <li>gravestone watch interval
     *   <li>UI display flags
     *   <li>bStats enabled flag
     * </ul>
     *
     * <p>Settings that require a full server restart:
     * <ul>
     *   <li>backend — storage backend cannot be swapped at runtime (use /preload migrate)
     *   <li>startupLoadStrategy — only used during boot
     *   <li>rebuildFromChunksOnCorruption / scheduledRebuildIntervalHours
     *   <li>mysql credentials — connection pool is created once at startup
     *   <li>cacheDirectory — cache files are already open
     *   <li>immuneToSimulationReduction — applied to worlds at registration time
     *   <li>updateChecker.enabled — runs once at startup
     * </ul>
     *
     * @return a human-readable summary of what was reloaded
     */
    public String reloadConfig() {
        config.reload();

        // Reschedule timed subsystems with updated intervals
        snapshotScheduler.reschedule();
        keepaliveManager.reschedule();
        if (ttlEnforcer != null) ttlEnforcer.reschedule();

        // Restart warp watcher in case path or interval changed
        warpFileWatcher.stop();
        warpFileWatcher = new WarpFileWatcher(config, storage, warmZoneManager, executor,
                teleportInterceptor::refreshPortalCache);
        warpFileWatcher.start();

        getLogger().at(java.util.logging.Level.INFO).log("[OptiPortal] Config reloaded via /preload reload.");

        return "Config reloaded. Live: decay, keepalive, snapshot interval, TTL cleanup, activation, TTLs, warps, UI flags. "
             + "Restart required for: backend, storage paths, MySQL credentials, startupLoadStrategy.";
    }

    // --- Public API for other plugins ---

    /**
     * Registers a warm zone programmatically without touching config.
     * Useful for other plugins to designate high-traffic areas.
     */
    public void registerWarmZone(String id, String worldName, double x, double y, double z, int radius) {
        warmZoneManager.registerWarmZone(id, worldName, x, y, z, radius);
    }

    /**
     * Triggers an immediate predictive pre-load for the given destination.
     */
    public void triggerPredictiveLoad(String worldName, double x, double z) {
        int cx = com.optiportal.preload.ChunkPreloader.toChunkCoord(x);
        int cz = com.optiportal.preload.ChunkPreloader.toChunkCoord(z);
        String zoneId = "api:" + worldName + ":" + cx + ":" + cz;
        chunkPreloader.predictiveLoad(zoneId, worldName, cx, cz, config.getPredictiveRadius());
    }

    // --- Getter methods for other components ---

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public ChunkPreloader getChunkPreloader() {
        return chunkPreloader;
    }

    public StorageBackend getStorage() {
        return storage;
    }

    public PluginConfig getPluginConfig() {
        return config;
    }

    public TeleportInterceptor getTeleportInterceptor() {
        return teleportInterceptor;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public WarpFileWatcher getWarpFileWatcher() {
        return warpFileWatcher;
    }

    public WalManager getWalManager() {
        return walManager;
    }

    // --- Async infrastructure getters ---

    public WorldThreadBridge getWorldThreadBridge() {
        return worldBridge;
    }

    public AsyncLoadBalancer getAsyncLoadBalancer() {
        return loadBalancer;
    }

    public AsyncMetrics getAsyncMetrics() {
        return metrics;
    }

    public AsyncErrorHandler getAsyncErrorHandler() {
        return errorHandler;
    }

    public com.optiportal.async.WorldTpsMonitor getTpsMonitor() {
        return tpsMonitor;
    }

    public com.optiportal.preload.PortalLinkRegistry getPortalLinkRegistry() {
        return portalLinkRegistry;
    }

    public PortalChunkListener getPortalChunkListener() {
        return portalChunkListener;
    }
}
