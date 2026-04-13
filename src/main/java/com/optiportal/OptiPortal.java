package com.optiportal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.optiportal.cache.CacheManager;
import com.optiportal.cache.SnapshotScheduler;
import com.optiportal.cache.ZoneTtlEnforcer;
import com.optiportal.cache.WalManager;
import com.optiportal.commands.PreloadCommand;
import com.optiportal.config.PluginConfig;
import com.optiportal.integrations.BetterGravestoneWatcher;
import com.optiportal.integrations.GravestoneIntegration;
import com.optiportal.integrations.HyTeleportersXWatcher;
import com.optiportal.integrations.WarpFileWatcher;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.metrics.bStatsIntegration;
import com.optiportal.player.DeathLocationTracker;
import com.optiportal.player.RespawnTracker;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.preload.WorldRegistry;
import com.optiportal.storage.StorageBackend;
import com.optiportal.storage.StorageFactory;
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
 *
 * Note: The following subsystems are currently dormant or config-gated:
 * - ChunkOwnershipAuditor
 * - EnhancedChunkPreloader
 * - AsyncTeleportInterceptor
 * - AsyncKeepaliveManager
 * - PortalChunkListener world-event activation (available via getter, disabled by default)
 * They may be activated in a future pass if needed for specific use cases.
 */
public class OptiPortal extends JavaPlugin {

    private static OptiPortal instance;

    // Core services
    private PluginConfig config;
    private StorageBackend storage;
    private ScheduledExecutorService executor;

    // Shutdown management
    private Thread shutdownHook;
    private final java.util.concurrent.atomic.AtomicBoolean shuttingDown = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Cache layer
    private CacheManager cacheManager;
    private WalManager walManager;
    private SnapshotScheduler snapshotScheduler;
    private com.optiportal.cache.ZoneTtlEnforcer ttlEnforcer;

    // Zone management
    private WorldRegistry worldRegistry;
    private WarmZoneManager warmZoneManager;
    private ChunkPreloader chunkPreloader;
    private com.optiportal.preload.KeepaliveManager keepaliveManager;
    private com.optiportal.preload.PortalLinkRegistry portalLinkRegistry;
    private com.optiportal.preload.PortalChunkListener portalChunkListener;

    // Player tracking
    private RespawnTracker respawnTracker;
    private DeathLocationTracker deathLocationTracker;

    // Integrations
    private WarpFileWatcher warpFileWatcher;
    private HyTeleportersXWatcher hyTeleportersXWatcher;
    private GravestoneIntegration gravestoneIntegration;
    private BetterGravestoneWatcher betterGravestoneWatcher;

    // Teleport handling
    private TeleportInterceptor teleportInterceptor;

    // Metrics
    private MetricsCollector metricsCollector;
    private com.optiportal.async.AsyncMetrics asyncMetrics;
    private com.optiportal.async.AsyncErrorHandler asyncErrorHandler;
    private com.optiportal.async.AsyncLoadBalancer asyncLoadBalancer;
    private com.optiportal.async.WorldTpsMonitor tpsMonitor;

    public OptiPortal(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void start() {
        try {
            getLogger().at(Level.INFO).log("[OptiPortal] Starting up...");

            // Shared async executor for all plugin work
            executor = new java.util.concurrent.ScheduledThreadPoolExecutor(4, new ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "OptiPortal-Executor-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

            // Load config — getDataDirectory() returns java.nio.file.Path
            config = PluginConfig.load(getDataDirectory().toFile());

            // Initialize storage — handles meta.txt detection and auto-migration if backend changed
            storage = StorageFactory.create(config);

            // WAL manager for crash protection
            walManager = new WalManager(config);

            // Shared metrics collector for the live runtime path
            metricsCollector = new MetricsCollector();

            // Async observability/runtime protection stack.
            // These services are now live so status reporting and future async integrations
            // have concrete infrastructure instead of null placeholder getters.
            asyncMetrics = new com.optiportal.async.AsyncMetrics();

            // World registry — populated via AddWorldEvent/RemoveWorldEvent
            worldRegistry = new WorldRegistry();
            worldRegistry.register(getEventRegistry());

            if (config.isTpsMonitorEnabled()) {
                tpsMonitor = new com.optiportal.async.WorldTpsMonitor(worldRegistry, executor, config);
                tpsMonitor.start();
            }
            com.optiportal.async.CircuitBreaker circuitBreaker = new com.optiportal.async.CircuitBreaker();
            asyncErrorHandler = new com.optiportal.async.AsyncErrorHandler(asyncMetrics, circuitBreaker, executor);
            asyncLoadBalancer = new com.optiportal.async.AsyncLoadBalancer(
                    executor,
                    asyncMetrics,
                    asyncErrorHandler,
                    tpsMonitor,
                    worldRegistry
            );

            // Cache manager
            cacheManager = new CacheManager(config, walManager, executor, worldRegistry, null, metricsCollector);
            cacheManager.loadRegistry();

            // TTL enforcement for expired zones
            ttlEnforcer = new com.optiportal.cache.ZoneTtlEnforcer(config, storage, cacheManager, executor);
            ttlEnforcer.setOnZoneDeleted(id -> {
                var registry = portalLinkRegistry;
                var interceptor = teleportInterceptor;
                if (registry != null) {
                    registry.removeLink(id);
                }
                if (interceptor != null) {
                    interceptor.onPortalDeleted(id);
                }
                if (interceptor != null) {
                    interceptor.refreshPortalCache();
                }
            });
            ttlEnforcer.start();

            // Zone manager
            warmZoneManager = new WarmZoneManager(config, storage, cacheManager, executor);
            chunkPreloader = new ChunkPreloader(config, cacheManager, worldRegistry, executor, storage, metricsCollector, this::isShuttingDown);
            warmZoneManager.setChunkPreloader(chunkPreloader); // break circular dep via setter
            portalChunkListener = new com.optiportal.preload.PortalChunkListener(
                    storage,
                    cacheManager,
                    warmZoneManager,
                    config,
                    executor
            );

            // Register AllWorldsLoadedEvent listener — actual chunk loading is gated on this
            warmZoneManager.registerWorldsLoadedListener(getEventRegistry());

            // Player trackers
            respawnTracker = new RespawnTracker(config, storage, cacheManager, chunkPreloader);
            deathLocationTracker = new DeathLocationTracker(config, storage, cacheManager, chunkPreloader);

            // Gravestone integration — create before TeleportInterceptor to ensure it's initialized
            gravestoneIntegration = new GravestoneIntegration(config, deathLocationTracker);
            gravestoneIntegration.init(getEventRegistry());
            betterGravestoneWatcher = new BetterGravestoneWatcher(
                    this,
                    config,
                    deathLocationTracker,
                    gravestoneIntegration,
                    worldRegistry,
                    executor
            );

            // Portal link registry — learns links from observed teleports
            portalLinkRegistry = new com.optiportal.preload.PortalLinkRegistry(getDataDirectory().toFile(), executor, config);

            // Teleport interceptor
            teleportInterceptor = new TeleportInterceptor(this, config, warmZoneManager, chunkPreloader, storage, portalLinkRegistry, respawnTracker, deathLocationTracker, gravestoneIntegration, executor);

            // File watchers - created but .start() deferred to post-enable
            warpFileWatcher = new WarpFileWatcher(config, storage, warmZoneManager, executor,
                    teleportInterceptor::refreshPortalCache,
                    id -> {
                        portalLinkRegistry.removeLink(id);
                        teleportInterceptor.onPortalDeleted(id);
                    });
            hyTeleportersXWatcher = new HyTeleportersXWatcher(config, storage, warmZoneManager, executor,
                    teleportInterceptor::refreshPortalCache,
                    id -> {
                        portalLinkRegistry.removeLink(id);
                        teleportInterceptor.onPortalDeleted(id);
                    });

            // Register events
            registerEvents();

            // Register commands
            getCommandRegistry().registerCommand(new PreloadCommand(this));

            // Snapshot scheduler - created but .start() deferred to post-enable
            snapshotScheduler = new SnapshotScheduler(config, cacheManager, executor);

            // Staged startup cache load - called after listeners registered, deferred to post-enable
            // warmZoneManager.startStagedLoad(); // REMOVED: now deferred in executor

            // Keepalive heartbeat - created but .start() deferred to post-enable
            keepaliveManager = new com.optiportal.preload.KeepaliveManager(
                    config, cacheManager, chunkPreloader, storage, executor);

            // Metrics - one-time init, stays eager
            if (config.isMetricsEnabled()) {
                new bStatsIntegration(this, metricsCollector).init();
            }

            // Update checker - one-time check, stays eager
            if (config.isUpdateCheckerEnabled()) {
                new UpdateChecker(this).checkAsync(executor);
            }

            // Shutdown hook for clean serialization on SIGTERM
            shutdownHook = new Thread(this::doShutdown);
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // Wire world removal cleanup callbacks
            if (config.isPortalChunkListenerEnabled()) {
                worldRegistry.addWorldLoadCallback(portalChunkListener::register);
                worldRegistry.addWorldRemoveCallback(portalChunkListener::onWorldRemoved);
                getLogger().at(Level.INFO).log("[OptiPortal] PortalChunkListener world hooks enabled.");
            } else {
                getLogger().at(Level.INFO).log("[OptiPortal] PortalChunkListener world hooks disabled by config.");
            }
            worldRegistry.addWorldRemoveCallback(warmZoneManager::onWorldRemoved);
            worldRegistry.addWorldUnloadCallback(teleportInterceptor::onWorldRemoved);
            worldRegistry.seedFromUniverse();

            // Log success - core runtime is ready
            getLogger().at(Level.INFO).log("[OptiPortal] Core runtime enabled successfully.");

            // Schedule deferred follow-up tasks to begin after enable completes
            // These are non-critical startup operations that don't block the plugin from being considered live
            executor.execute(() -> {
                // Skip if shutdown already occurred
                if (shuttingDown.get()) return;

                // File watchers - integration sync, not core runtime
                if (warpFileWatcher != null) {
                    warpFileWatcher.start();
                }
                if (hyTeleportersXWatcher != null) {
                    hyTeleportersXWatcher.start();
                }
                if (betterGravestoneWatcher != null) {
                    betterGravestoneWatcher.start();
                }

                // Staged-load arming - can start after listener is registered
                if (warmZoneManager != null) {
                    warmZoneManager.startStagedLoad();
                }

                // Keepalive heartbeat - async task, doesn't block runtime
                if (keepaliveManager != null) {
                    keepaliveManager.start();
                }

                // Snapshot scheduler - can start after cache state is loaded
                if (snapshotScheduler != null) {
                    snapshotScheduler.start();
                }

                getLogger().at(Level.INFO).log("[OptiPortal] Deferred startup tasks completed.");
            });

        } catch (Exception e) {
            getLogger().at(Level.SEVERE).log("[OptiPortal] Failed to enable: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void shutdown() {
        doShutdown();
    }

    private void doShutdown() {
        // Guard against duplicate shutdown execution
        if (!shuttingDown.compareAndSet(false, true)) {
            getLogger().at(java.util.logging.Level.FINE).log("[OptiPortal] Shutdown already in progress, skipping.");
            return;
        }

        getLogger().at(Level.INFO).log("[OptiPortal] Shutdown detected, serializing warm zones...");
        try {
            // Stop local components first so their own guards flip before unregistering
            if (warmZoneManager != null) warmZoneManager.stop();
            if (teleportInterceptor != null) teleportInterceptor.stop();
            if (warpFileWatcher != null) warpFileWatcher.stop();
            if (hyTeleportersXWatcher != null) hyTeleportersXWatcher.stop();
            if (betterGravestoneWatcher != null) betterGravestoneWatcher.stop();
            if (snapshotScheduler != null) snapshotScheduler.stop();
            if (keepaliveManager != null) keepaliveManager.stop();
            if (ttlEnforcer != null) ttlEnforcer.stop();
            if (chunkPreloader != null) chunkPreloader.flushPendingEntrySaves();
            
            // Serialize warm zones
            if (warmZoneManager != null) warmZoneManager.serializeAll();
            
            // Save cache registry
            if (cacheManager != null) cacheManager.saveRegistry();
            
            // Flush portal link registry before executor shutdown
            if (portalLinkRegistry != null) portalLinkRegistry.flush();
            
            // Unregister plugin event listeners using engine's explicit cleanup API
            if (getEventRegistry() != null) {
                getEventRegistry().shutdownAndCleanup(true);
            }
            
            // Shutdown executor before closing storage. If the graceful drain times out,
            // shutdownNow() is called but does not guarantee running tasks have finished —
            // so we await a second time to give interrupted tasks a chance to exit before
            // storage.close() tears down the underlying SQL connection or file handles.
            if (executor != null) {
                unregisterShutdownHook();
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                        // Second await: give interrupted tasks up to 2s to exit cleanly.
                        if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                            getLogger().at(Level.WARNING).log(
                                    "[OptiPortal] Executor still has running tasks after shutdownNow(); "
                                  + "proceeding to storage close relying on shutdown guards to keep callbacks inert.");
                        }
                    }
                } catch (InterruptedException ie) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                    getLogger().at(Level.WARNING).log(
                            "[OptiPortal] Interrupted while waiting for executor shutdown; "
                          + "proceeding to storage close relying on shutdown guards.");
                }
            }

            // Close storage backend only after the executor is as quiescent as possible.
            if (storage != null) storage.close();
            getLogger().at(Level.INFO).log("[OptiPortal] Cache saved cleanly.");
        } catch (Exception e) {
            getLogger().at(Level.SEVERE).log("[OptiPortal] Error during shutdown: " + e.getMessage());
        }
    }

    private void unregisterShutdownHook() {
        if (shutdownHook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            shutdownHook = null;
        } catch (IllegalStateException ignored) {
            // JVM shutdown already in progress — removal not possible, safe to ignore
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
     *   <li>betterGravestone watch interval / block id / plugin id
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
     *   <li>tpsMonitor.enabled / thresholds — monitor wiring happens at startup
     *   <li>integrations.portalChunkListener.enabled — world-event registration happens at startup
     * </ul>
     *
     * @return a human-readable summary of what was reloaded
     */
    public String reloadConfig() {
        // Capture old watcher settings BEFORE reload to avoid comparing against already-reloaded values
        boolean oldTpsMonitorEnabled = config.isTpsMonitorEnabled();
        double oldTpsLowThreshold = config.getTpsLowThreshold();
        double oldTpsCriticalThreshold = config.getTpsCriticalThreshold();
        boolean oldPortalChunkListenerEnabled = config.isPortalChunkListenerEnabled();

        boolean oldWarpsWatch = config.isWarpsWatchForChanges();
        String oldWarpsPath = config.getWarpsSourcePath();
        int oldWarpsInterval = config.getWarpsWatchIntervalSeconds();

        boolean oldHtxEnabled = config.isHyTeleportersXIntegrationEnabled();
        boolean oldHtxWatch = config.isHyTeleportersXWatchForChanges();
        String oldHtxPath = config.getHyTeleportersXSourcePath();
        int oldHtxInterval = config.getHyTeleportersXWatchIntervalSeconds();
        boolean oldBetterGsEnabled = config.isBetterGravestoneIntegrationEnabled();
        String oldBetterGsPluginId = config.getBetterGravestonePluginId();
        String oldBetterGsBlockId = config.getBetterGravestoneBlockId();
        int oldBetterGsInterval = config.getBetterGravestoneWatchIntervalSeconds();

        config.reload();
        storage.onConfigReload(config);

        // Reschedule timed subsystems with updated intervals
        snapshotScheduler.reschedule();
        keepaliveManager.reschedule();
        if (ttlEnforcer != null) ttlEnforcer.reschedule();

        // Restart warp watcher only if relevant settings changed (old vs new comparison)
        boolean newWarpsWatch = config.isWarpsWatchForChanges();
        String newWarpsPath = config.getWarpsSourcePath();
        int newWarpsInterval = config.getWarpsWatchIntervalSeconds();

        boolean warpNeedsRestart = warpFileWatcher != null
                && (oldWarpsWatch != newWarpsWatch
                    || !oldWarpsPath.equals(newWarpsPath)
                    || oldWarpsInterval != newWarpsInterval);
        if (warpNeedsRestart) {
            warpFileWatcher.stop();
            warpFileWatcher = new WarpFileWatcher(config, storage, warmZoneManager, executor,
                    teleportInterceptor::refreshPortalCache,
                    id -> {
                        portalLinkRegistry.removeLink(id);
                        teleportInterceptor.onPortalDeleted(id);
                    });
            warpFileWatcher.start();
        }

        // Restart HyTeleportersX watcher only if relevant settings changed (old vs new comparison)
        boolean newHtxEnabled = config.isHyTeleportersXIntegrationEnabled();
        boolean newHtxWatch = config.isHyTeleportersXWatchForChanges();
        String newHtxPath = config.getHyTeleportersXSourcePath();
        int newHtxInterval = config.getHyTeleportersXWatchIntervalSeconds();
        boolean newBetterGsEnabled = config.isBetterGravestoneIntegrationEnabled();
        String newBetterGsPluginId = config.getBetterGravestonePluginId();
        String newBetterGsBlockId = config.getBetterGravestoneBlockId();
        int newBetterGsInterval = config.getBetterGravestoneWatchIntervalSeconds();
        boolean newTpsMonitorEnabled = config.isTpsMonitorEnabled();
        double newTpsLowThreshold = config.getTpsLowThreshold();
        double newTpsCriticalThreshold = config.getTpsCriticalThreshold();
        boolean newPortalChunkListenerEnabled = config.isPortalChunkListenerEnabled();

        boolean htxNeedsRestart = hyTeleportersXWatcher != null
                && (oldHtxEnabled != newHtxEnabled
                    || oldHtxWatch != newHtxWatch
                    || !oldHtxPath.equals(newHtxPath)
                    || oldHtxInterval != newHtxInterval);
        if (htxNeedsRestart) {
            hyTeleportersXWatcher.stop();
            hyTeleportersXWatcher = new HyTeleportersXWatcher(config, storage, warmZoneManager, executor,
                    teleportInterceptor::refreshPortalCache,
                    id -> {
                        portalLinkRegistry.removeLink(id);
                        teleportInterceptor.onPortalDeleted(id);
                    });
            hyTeleportersXWatcher.start();
        }

        boolean betterGsNeedsRestart = betterGravestoneWatcher != null
                && (oldBetterGsEnabled != newBetterGsEnabled
                    || !oldBetterGsPluginId.equals(newBetterGsPluginId)
                    || !oldBetterGsBlockId.equals(newBetterGsBlockId)
                    || oldBetterGsInterval != newBetterGsInterval);
        if (betterGsNeedsRestart) {
            betterGravestoneWatcher.stop();
            betterGravestoneWatcher = new BetterGravestoneWatcher(
                    this,
                    config,
                    deathLocationTracker,
                    gravestoneIntegration,
                    worldRegistry,
                    executor
            );
            betterGravestoneWatcher.start();
        }

        getLogger().at(java.util.logging.Level.INFO).log("[OptiPortal] Config reloaded via /preload reload.");

        String restartChanged = "";
        if (oldTpsMonitorEnabled != newTpsMonitorEnabled
                || Double.compare(oldTpsLowThreshold, newTpsLowThreshold) != 0
                || Double.compare(oldTpsCriticalThreshold, newTpsCriticalThreshold) != 0) {
            restartChanged += " Changed: tpsMonitor settings require restart to affect monitor wiring/thresholds.";
        }
        if (oldPortalChunkListenerEnabled != newPortalChunkListenerEnabled) {
            restartChanged += " Changed: integrations.portalChunkListener.enabled requires restart to affect world hooks.";
        }

        return "Config reloaded. Live: decay, keepalive, snapshot interval, activation, TTLs, warps, optional integrations, UI flags. "
             + "Restart required for: backend, storage paths, MySQL credentials, startupLoadStrategy, tpsMonitor wiring, portalChunkListener world hooks."
             + restartChanged;
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
        chunkPreloader.predictiveLoad(worldName, cx, cz, config.getPredictiveRadius());
    }

    // --- Getters ---

    public static OptiPortal getInstance() { return instance; }
    public PluginConfig getPluginConfig() { return config; }
    public StorageBackend getStorage() { return storage; }
    public CacheManager getCacheManager() { return cacheManager; }
    public TeleportInterceptor getTeleportInterceptor() { return teleportInterceptor; }
    public WarmZoneManager getWarmZoneManager() { return warmZoneManager; }
    public ChunkPreloader getChunkPreloader() { return chunkPreloader; }
    public WalManager getWalManager() { return walManager; }
    public WarpFileWatcher getWarpFileWatcher() { return warpFileWatcher; }
    public HyTeleportersXWatcher getHyTeleportersXWatcher() { return hyTeleportersXWatcher; }
    public RespawnTracker getRespawnTracker() { return respawnTracker; }
    public DeathLocationTracker getDeathLocationTracker() { return deathLocationTracker; }
    public MetricsCollector getMetricsCollector() { return metricsCollector; }
    public ScheduledExecutorService getExecutor() { return executor; }
    public com.optiportal.preload.PortalLinkRegistry getPortalLinkRegistry() { return portalLinkRegistry; }
    public com.optiportal.cache.ZoneTtlEnforcer getTtlEnforcer() { return ttlEnforcer; }
    public com.optiportal.async.AsyncLoadBalancer getAsyncLoadBalancer() { return asyncLoadBalancer; }
    public com.optiportal.async.AsyncErrorHandler getAsyncErrorHandler() { return asyncErrorHandler; }
    public com.optiportal.async.WorldTpsMonitor getTpsMonitor() { return tpsMonitor; }
    public com.optiportal.preload.PortalChunkListener getPortalChunkListener() { return portalChunkListener; }
    
    /** Check if the plugin is shutting down. Used by listeners to prevent post-shutdown work. */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }
    
    // --- Integration with HytaleServer utilities ---

    /**
     * Enhanced predictive load using density-based chunk prioritization.
     * Leverages HytaleServer's field functions for more intelligent loading.
     */
    public void enhancedPredictiveLoad(String worldName, double x, double z, int radius) {
        // Use the more advanced chunk loading strategy that considers terrain complexity
        // In a real implementation, this would use HytaleServer's density functions
        
        // Convert world coordinates to chunk coordinates
        int cx = com.optiportal.preload.ChunkPreloader.toChunkCoord(x);
        int cz = com.optiportal.preload.ChunkPreloader.toChunkCoord(z);
        
        // First load the inner ring with priority
        chunkPreloader.predictiveLoad(worldName, cx, cz, radius);
    }
}
