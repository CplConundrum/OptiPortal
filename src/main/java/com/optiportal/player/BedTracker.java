package com.optiportal.player;

import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.storage.StorageBackend;

import java.util.UUID;

/**
 * Tracks player bed locations and manages bed cache entries.
 * Updates on bed set, removes on bed destruction.
 * Always PREDICTIVE strategy, 3 day TTL.
 *
 * TODO: Hook into Hytale bed set / bed destroy events.
 */
public class BedTracker {

    private final PluginConfig config;
    private final StorageBackend storage;
    private final CacheManager cacheManager;
    private final ChunkPreloader preloader;

    public BedTracker(PluginConfig config, StorageBackend storage,
                      CacheManager cacheManager, ChunkPreloader preloader) {
        this.config = config;
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.preloader = preloader;
    }

    /** Called when a player sets their spawn at a bed. */
    public void onBedSet(UUID playerId, double x, double y, double z, String world) {
        String id = "bed:" + playerId;

        // Remove old cache entry
        storage.delete(id);
        cacheManager.releaseZoneChunks(id);

        // Register new bed location
        PortalEntry entry = new PortalEntry(id, world, x, y, z, 0);
        entry.setType(PortalEntry.EntryType.BED);
        entry.setCacheTTLDays(config.getTtlBed());
        storage.save(entry);
    }

    /** Called when a player's bed is destroyed. */
    public void onBedDestroyed(UUID playerId) {
        String id = "bed:" + playerId;
        storage.delete(id);
        cacheManager.releaseZoneChunks(id);
    }

    /** Pre-load bed location during respawn screen. */
    public void preloadBed(UUID playerId) {
        String id = "bed:" + playerId;
        storage.loadById(id).ifPresent(entry -> {
            int cx = com.optiportal.preload.ChunkPreloader.toChunkCoord(entry.getX());
            int cz = com.optiportal.preload.ChunkPreloader.toChunkCoord(entry.getZ());
            preloader.predictiveLoad(entry.getWorld(), cx, cz, 7);
        });
    }
}
