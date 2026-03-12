package com.optiportal;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.optiportal.cache.CacheManager;
import com.optiportal.cache.SnapshotScheduler;
import com.optiportal.cache.WalManager;
import com.optiportal.commands.PreloadCommand;
import com.optiportal.config.PluginConfig;
import com.optiportal.integrations.GravestoneIntegration;
import com.optiportal.integrations.WarpFileWatcher;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.metrics.bStatsIntegration;
import com.optiportal.model.WarmStrategy;
import com.optiportal.player.RespawnTracker;
import com.optiportal.player.DeathLocationTracker;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.preload.WorldRegistry;
import com.optiportal.storage.StorageBackend;
import com.optiportal.storage.StorageFactory;
import com.optiportal.teleport.TeleportInterceptor;
import com.optiportal.update.UpdateChecker;

import javax.annotation.Nonnull;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;

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
 */
public class OptiPortal extends JavaPlugin {

    private static OptiPortal instance;

    // Core services
    private PluginConfig config;
    private StorageBackend storage;
    private ScheduledExecutorService executor;

    // Cache layer
    private CacheManager cacheManager;
    private WalManager walManager;
    private SnapshotScheduler snapshotScheduler;

    // Zone management
    private WorldRegistry worldRegistry;
    private WarmZoneManager warmZoneManager;
    private ChunkPreloader chunkPreloader;
    private com.optiportal.preload.KeepaliveManager keepaliveManager;
    private com.optiportal.preload.PortalLinkRegistry portalLinkRegistry;

    // Player tracking
    private RespawnTracker respawnTracker;
    private DeathLocationTracker deathLocationTracker;

    // Integrations
    private WarpFileWatcher warpFileWatcher;
    private GravestoneIntegration gravestoneIntegration;

    // Teleport handling
    private TeleportInterceptor teleportInterceptor;

    // Metrics
    private MetricsCollector metricsCollector;

    public OptiPortal(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void start0() {
        try {
            getLogger().at(Level.INFO).log("[OptiPortal] Starting up...");

            // Shared async executor for all plugin work
            executor = Executors.newScheduledThreadPool(4);

            // Load config — getDataDirectory() returns java.nio.file.Path
            config = PluginConfig.load(getDataDirectory().toFile());

            // Initialize storage — handles meta.txt detection and auto-migration if backend changed
            storage = StorageFactory.create(config);

            // WAL manager for crash protection
            walManager = new WalManager(config);

            // Cache manager
            cacheManager = new CacheManager(config, walManager, executor);
            cacheManager.loadRegistry();

            // World registry — populated via AddWorldEvent/RemoveWorldEvent
            worldRegistry = new WorldRegistry();
            worldRegistry.register(getEventRegistry());

            // Zone manager
            warmZoneManager = new WarmZoneManager(config, storage, cacheManager, executor);
            chunkPreloader = new ChunkPreloader(config, cacheManager, worldRegistry, executor);
            warmZoneManager.setChunkPreloader(chunkPreloader); // break circular dep via setter

            // Register AllWorldsLoadedEvent listener — actual chunk loading is gated on this
            warmZoneManager.registerWorldsLoadedListener(getEventRegistry());

            // Player trackers
            respawnTracker = new RespawnTracker(config, storage, cacheManager, chunkPreloader);
            deathLocationTracker = new DeathLocationTracker(config, storage, cacheManager, chunkPreloader);

            // Portal link registry — learns links from observed teleports
            portalLinkRegistry = new com.optiportal.preload.PortalLinkRegistry(getDataDirectory().toFile());

            // Teleport interceptor
            teleportInterceptor = new TeleportInterceptor(this, config, warmZoneManager, chunkPreloader, storage, portalLinkRegistry, respawnTracker, deathLocationTracker, gravestoneIntegration, executor);

            // File watchers
            warpFileWatcher = new WarpFileWatcher(config, storage, warmZoneManager, executor);
            warpFileWatcher.start();

            // Gravestone integration
            gravestoneIntegration = new GravestoneIntegration(config, deathLocationTracker);
            gravestoneIntegration.init(getEventRegistry());

            // Register events
            registerEvents();

            // Register commands
            getCommandRegistry().registerCommand(new PreloadCommand(this));

            // Metrics
            metricsCollector = new MetricsCollector();
            if (config.isMetricsEnabled()) {
                new bStatsIntegration(this, metricsCollector).init();
            }

            // Snapshot scheduler
            snapshotScheduler = new SnapshotScheduler(config, cacheManager, executor);
            snapshotScheduler.start();

            // Staged startup cache load
            warmZoneManager.startStagedLoad();

            // Keepalive heartbeat
            keepaliveManager = new com.optiportal.preload.KeepaliveManager(
                    config, cacheManager, chunkPreloader, storage, executor);
            keepaliveManager.start();

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

    protected void shutdown0() {
        doShutdown();
    }

    private void doShutdown() {
        getLogger().at(Level.INFO).log("[OptiPortal] Shutdown detected, serializing warm zones...");
        try {
            if (warpFileWatcher != null) warpFileWatcher.stop();
            if (snapshotScheduler != null) snapshotScheduler.stop();
            if (keepaliveManager != null) keepaliveManager.stop();
            if (warmZoneManager != null) warmZoneManager.serializeAll();
            if (cacheManager != null) cacheManager.saveRegistry();
            if (storage != null) storage.close();
            if (executor != null) executor.shutdown();
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

        // Restart warp watcher in case path or interval changed
        warpFileWatcher.stop();
        warpFileWatcher = new WarpFileWatcher(config, storage, warmZoneManager, executor);
        warpFileWatcher.start();

        getLogger().at(java.util.logging.Level.INFO).log("[OptiPortal] Config reloaded via /preload reload.");

        return "Config reloaded. Live: decay, keepalive, snapshot interval, activation, TTLs, warps, UI flags. "
             + "Restart required for: backend, storage paths, MySQL credentials, startupLoadStrategy.";
    }

    // --- Public API for other plugins ---

    /**
     * Registers a warm zone programmatically without touching config.
     * Useful for other plugins to designate high-traffic areas.
     */
    public void registerWarmZone(String id, double x, double y, double z, int radius) {
        warmZoneManager.registerWarmZone(id, x, y, z, radius);
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
    public RespawnTracker getRespawnTracker() { return respawnTracker; }
    public DeathLocationTracker getDeathLocationTracker() { return deathLocationTracker; }
    public MetricsCollector getMetricsCollector() { return metricsCollector; }
    public ScheduledExecutorService getExecutor() { return executor; }
    public com.optiportal.preload.PortalLinkRegistry getPortalLinkRegistry() { return portalLinkRegistry; }
}