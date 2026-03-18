package com.optiportal.player;

import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.storage.StorageBackend;

import java.util.UUID;

/**
 * Records player respawn locations after death and pre-loads those chunks.
 *
 * Flow:
 *   1. GravestoneIntegration marks the player as pending capture on GravestoneCreatedEvent.
 *   2. TeleportInterceptor.onPlayerReady detects the pending flag and calls onRespawn()
 *      with the player's actual spawn position.
 *   3. On next login/load after a death, preloadRespawn() fires early so chunks are
 *      ready before the player arrives.
 *
 * Entry key: "respawn:<uuid>" — one record per player, overwritten on each death cycle.
 * TTL: configured via ttl.bed in config.json (reusing the bed TTL setting).
 */
public class RespawnTracker {

    private final PluginConfig config;
    private final StorageBackend storage;
    private final CacheManager cacheManager;
    private final ChunkPreloader preloader;

    public RespawnTracker(PluginConfig config, StorageBackend storage,
                          CacheManager cacheManager, ChunkPreloader preloader) {
        this.config = config;
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.preloader = preloader;
    }

    /**
     * Called by TeleportInterceptor.onPlayerReady when a pending respawn capture
     * flag is set for this player. Records their spawn-in position as the new
     * respawn cache entry, overwriting any previous one.
     */
    public void onRespawn(UUID playerId, double x, double y, double z, String world) {
        String id = "respawn:" + playerId;

        // Release old cache entry before overwriting
        cacheManager.releaseZoneChunks(id);

        PortalEntry entry = new PortalEntry(id, world, x, y, z, 0);
        entry.setType(PortalEntry.EntryType.BED); // player-data type, excluded from zone list
        entry.setCacheTTLDays(config.getTtlBed());
        storage.save(entry);

        System.out.println("[OptiPortal] Respawn location recorded for " + playerId
                + " at " + world + " " + x + "," + y + "," + z);
    }

    /**
     * Pre-loads chunks around the player's last recorded respawn location.
     * Called on PlayerReadyEvent for players without a pending capture flag —
     * i.e. normal logins where we want to warm their known respawn point.
     */
    public void preloadRespawn(UUID playerId) {
        String id = "respawn:" + playerId;
        storage.loadById(id).ifPresent(entry -> {
            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            preloader.predictiveLoad(id, entry.getWorld(), cx, cz, 7);
            System.out.println("[OptiPortal] Respawn preload: " + playerId
                    + " cx=" + cx + " cz=" + cz);
        });
    }
}
