package com.optiportal.preload;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.storage.StorageBackend;

/**
 * Periodically re-pings chunks for HOT/WARM/COLD zones to prevent
 * the server's chunk GC from evicting them between player visits.
 *
 * Runs one scheduled task per tier. Each task iterates zones at that
 * tier and calls getNonTickingChunkAsync on their chunk coords.
 * Does NOT touch CacheManager tier state — pure keepalive, no side effects.
 */
public class KeepaliveManager {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final PluginConfig config;
    private final CacheManager cacheManager;
    private final ChunkPreloader chunkPreloader;
    private final StorageBackend storage;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> hotTask;
    private ScheduledFuture<?> warmTask;
    private ScheduledFuture<?> coldTask;

    public KeepaliveManager(PluginConfig config,
                            CacheManager cacheManager,
                            ChunkPreloader chunkPreloader,
                            StorageBackend storage,
                            ScheduledExecutorService executor) {
        this.config        = config;
        this.cacheManager  = cacheManager;
        this.chunkPreloader = chunkPreloader;
        this.storage       = storage;
        this.executor      = executor;
    }

    // Protected getters for subclass access
    protected PluginConfig getConfig() {
        return config;
    }

    protected CacheManager getCacheManager() {
        return cacheManager;
    }

    protected ChunkPreloader getChunkPreloader() {
        return chunkPreloader;
    }

    protected StorageBackend getStorage() {
        return storage;
    }

    protected ScheduledExecutorService getExecutor() {
        return executor;
    }

    /**
     * Cancel existing tasks and reschedule using current config values.
     * Safe to call at runtime — e.g. after /preload reload.
     */
    public void reschedule() {
        stop();
        start();
        LOG.info("[OptiPortal] Keepalive intervals rescheduled from updated config.");
    }

    /** Schedule all enabled tier tasks. Call once after startup load completes. */
    public void start() {
        if (config.isKeepaliveHot()) {
            int interval = config.getKeepaliveHotIntervalMinutes();
            hotTask = executor.scheduleWithFixedDelay(
                    () -> safeping(CacheTier.HOT, "HOT"),
                    interval, interval, TimeUnit.MINUTES);
            LOG.info(() -> String.format("[OptiPortal] Keepalive HOT scheduled every %dm", interval));
        }
        if (config.isKeepaliveWarm()) {
            int interval = config.getKeepaliveWarmIntervalMinutes();
            warmTask = executor.scheduleWithFixedDelay(
                    () -> safeping(CacheTier.WARM, "WARM"),
                    interval, interval, TimeUnit.MINUTES);
            LOG.info(() -> String.format("[OptiPortal] Keepalive WARM scheduled every %dm", interval));
        }
        if (config.isKeepaliveCold()) {
            int interval = config.getKeepaliveColdIntervalMinutes();
            coldTask = executor.scheduleWithFixedDelay(
                    () -> safeping(CacheTier.COLD, "COLD"),
                    interval, interval, TimeUnit.MINUTES);
            LOG.info(() -> String.format("[OptiPortal] Keepalive COLD scheduled every %dm", interval));
        }
    }

    // Guard wrapper used by start() so any subclass override of pingTier() is also protected.
    private void safeping(CacheTier tier, String label) {
        try {
            pingTier(tier, label);
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Keepalive " + label + ": error (scheduler preserved): " + e.getMessage());
        }
    }

    /** Cancel all scheduled tasks. Call on plugin shutdown. */
    public void stop() {
        if (hotTask  != null) hotTask.cancel(false);
        if (warmTask != null) warmTask.cancel(false);
        if (coldTask != null) coldTask.cancel(false);
    }

    /**
     * Re-ping all non-instanced zones at the given tier.
     * Uses warmLoad's underlying getNonTickingChunkAsync — chunks stay loaded
     * but don't tick, matching how they were originally loaded.
     */
    protected void pingTier(CacheTier tier, String label) {
        try {
            pingTierInternal(tier, label);
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Keepalive " + label + ": error (scheduler preserved): " + e.getMessage());
        }
    }

    private void pingTierInternal(CacheTier tier, String label) {
        List<PortalEntry> entries = storage.loadAll();
        int zoneCount = 0;
        int chunkCount = 0;

        for (PortalEntry entry : entries) {
            if (entry.isInstanced()) continue;
            if (cacheManager.getZoneTier(entry.getId()) != tier) continue;

            String worldName = entry.getWorld();
            com.hypixel.hytale.server.core.universe.world.World world =
                    chunkPreloader.getWorldRegistry().getWorld(worldName);
            if (world == null) continue;

            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            int radiusX = resolveRadiusX(entry);
            int radiusZ = resolveRadiusZ(entry);
            chunkCount += (2 * radiusX + 1) * (2 * radiusZ + 1);
            zoneCount++;

            // U2: Confirm chunk residency before pinging
            // Check if chunks are actually loaded before attempting to ping them
            boolean allChunksPresent = true;
            for (int dx = -radiusX; dx <= radiusX; dx++) {
                for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                    long chunkIndex = ChunkUtil.indexChunk(cx + dx, cz + dz);
                    // Use getNonTickingChunkAsync to check residency - if it returns null, chunk is not loaded
                    if (world.getNonTickingChunkAsync(chunkIndex) == null) {
                        allChunksPresent = false;
                        break;
                    }
                }
                if (!allChunksPresent) break;
            }

            if (allChunksPresent) {
                // Fire and forget — we only care that the chunk future is requested,
                // not when it completes. getNonTickingChunkAsync resets GC reachability.
                for (int dx = -radiusX; dx <= radiusX; dx++) {
                    for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                        world.getNonTickingChunkAsync(ChunkUtil.indexChunk(cx + dx, cz + dz));
                    }
                }
            } else {
                // Chunks not present — demote from HOT tier
                if (tier == CacheTier.HOT) {
                    cacheManager.setZoneTier(entry.getId(), CacheTier.WARM);
                    LOG.info("[OptiPortal] Keepalive HOT residency check failed for " + entry.getId()
                            + " — demoted to WARM");
                }
            }
        }

        if (zoneCount > 0) {
            LOG.info("[OptiPortal] Keepalive " + label
                    + ": pinged " + chunkCount + " chunks across " + zoneCount + " zones");
        }
    }

    /**
     * Resolve X-axis radius from entry, falling back to uniform radius then config default.
     * Protected for subclass access.
     */
    protected int resolveRadiusX(PortalEntry entry) {
        if (entry.getWarmRadiusX() != null) return entry.getWarmRadiusX();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }

    /**
     * Resolve Z-axis radius from entry, falling back to uniform radius then config default.
     * Protected for subclass access.
     */
    protected int resolveRadiusZ(PortalEntry entry) {
        if (entry.getWarmRadiusZ() != null) return entry.getWarmRadiusZ();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }

    /**
     * Resolve radius from entry, falling back to config default.
     * Deprecated — use resolveRadiusX/resolveRadiusZ for asymmetric support.
     */
    @Deprecated
    private int resolveRadius(PortalEntry entry) {
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }
}