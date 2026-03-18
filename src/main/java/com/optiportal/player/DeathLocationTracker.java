package com.optiportal.player;

import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.storage.StorageBackend;

import java.time.Instant;
import java.util.UUID;

/**
 * Records player death locations and manages death cache entries.
 * Single death location per player - new death overwrites previous.
 * Released when gravestone is broken (if integration enabled) or TTL expires.
 * Always PREDICTIVE strategy, 1 day TTL.
 *
 * TODO: Hook into Hytale player death event.
 */
public class DeathLocationTracker {

    private final PluginConfig config;
    private final StorageBackend storage;
    private final CacheManager cacheManager;
    private final ChunkPreloader preloader;

    public DeathLocationTracker(PluginConfig config, StorageBackend storage,
                                 CacheManager cacheManager, ChunkPreloader preloader) {
        this.config = config;
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.preloader = preloader;
    }

    /** Called on player death. Overwrites any existing death location. */
    public void onPlayerDeath(UUID playerId, double x, double y, double z, String world) {
        String id = "death:" + playerId;

        // Overwrite old entry
        cacheManager.releaseZoneChunks(id);

        PortalEntry entry = new PortalEntry(id, world, x, y, z, 0);
        entry.setType(PortalEntry.EntryType.DEATH);
        entry.setCacheTTLDays(config.getTtlDeathLocation());
        entry.setLastActive(Instant.now());
        storage.save(entry);
    }

    /** Called when gravestone is broken or emptied - releases cache ownership. */
    public void onGravestoneReleased(UUID playerId) {
        String id = "death:" + playerId;
        storage.delete(id);
        cacheManager.releaseZoneChunks(id);
    }

    /** Pre-load death location during respawn screen. */
    public void preloadDeathLocation(UUID playerId) {
        String id = "death:" + playerId;
        storage.loadById(id).ifPresent(entry -> {
            int cx = com.optiportal.preload.ChunkPreloader.toChunkCoord(entry.getX());
            int cz = com.optiportal.preload.ChunkPreloader.toChunkCoord(entry.getZ());
            preloader.predictiveLoad(id, entry.getWorld(), cx, cz, 7);
        });
    }
}
