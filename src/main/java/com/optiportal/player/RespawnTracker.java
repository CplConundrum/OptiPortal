package com.optiportal.player;

import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.storage.StorageBackend;

import java.util.UUID;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final PluginConfig config;
    private final StorageBackend storage;
    private final CacheManager cacheManager;
    private final ChunkPreloader preloader;
    private final java.util.concurrent.ScheduledExecutorService executor;

    public RespawnTracker(PluginConfig config, StorageBackend storage,
                          CacheManager cacheManager, ChunkPreloader preloader,
                          java.util.concurrent.ScheduledExecutorService executor) {
        this.config = config;
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.preloader = preloader;
        this.executor = executor;
    }

    /**
     * Called by TeleportInterceptor.onPlayerReady when a pending respawn capture
     * flag is set for this player. Records their spawn-in position as the new
     * respawn cache entry, overwriting any previous one.
     *
     * <p>Tier-1 (improvement pass): the whole body now runs on the plugin executor —
     * the old code performed synchronous JDBC (storage.save) and chunk release on
     * the PlayerReadyEvent thread.
     */
    public void onRespawn(UUID playerId, double x, double y, double z, String world) {
        String id = "respawn:" + playerId;
        double fx = x, fy = y, fz = z;
        final String fid = id;
        executor.execute(() -> {
            try {
                // Release old cache entry before overwriting
                cacheManager.releaseZoneChunks(fid);

                PortalEntry entry = new PortalEntry(fid, world, fx, fy, fz, 0);
                entry.setType(PortalEntry.EntryType.BED); // player-data type, excluded from zone list
                entry.setCacheTTLDays(config.getTtlBed());
                entry.setLastActive(java.time.Instant.now());
                storage.save(entry);

                LOG.info("[OptiPortal] Respawn location recorded for " + playerId
                        + " at " + world + " " + fx + "," + fy + "," + fz);
            } catch (Exception e) {
                LOG.warning("[OptiPortal] RespawnTracker onRespawn off-thread work failed for "
                        + playerId + ": " + e.getMessage());
            }
        });
    }

    /**
     * Pre-loads chunks around the player's last recorded respawn location.
     * Called on PlayerReadyEvent for players without a pending capture flag —
     * i.e. normal logins where we want to warm their known respawn point.
     *
     * <p>Tier-1 (improvement pass): the body now runs on the plugin executor —
     * the old code did synchronous JDBC (storage.loadById) on the event thread.
     */
    public void preloadRespawn(UUID playerId) {
        String id = "respawn:" + playerId;
        executor.execute(() -> {
            try {
                storage.loadById(id).ifPresent(entry -> {
                    int cx = ChunkPreloader.toChunkCoord(entry.getX());
                    int cz = ChunkPreloader.toChunkCoord(entry.getZ());
                    preloader.predictiveLoad(id, entry.getWorld(), cx, cz, config.getPredictiveRadius());
                    LOG.fine("[OptiPortal] Respawn preload: " + playerId
                            + " cx=" + cx + " cz=" + cz);
                });
            } catch (Exception e) {
                LOG.warning("[OptiPortal] RespawnTracker preloadRespawn off-thread work failed for "
                        + playerId + ": " + e.getMessage());
            }
        });
    }
}
