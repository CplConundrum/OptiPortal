package com.optiportal.teleport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.optiportal.OptiPortal;
import com.optiportal.async.AsyncLoadBalancer;
import com.optiportal.async.AsyncMetrics;
import com.optiportal.async.WorldThreadBridge;
import com.optiportal.config.PluginConfig;
import com.optiportal.integrations.GravestoneIntegration;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.player.DeathLocationTracker;
import com.optiportal.player.RespawnTracker;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.storage.StorageBackend;

/**
 * Enhanced teleport interceptor with improved async handling.
 * 
 * This extends the original TeleportInterceptor with better async operations,
 * event-driven updates, and reduced world thread impact.
 */
public class AsyncTeleportInterceptor extends TeleportInterceptor {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final WorldThreadBridge worldBridge;
    private final AsyncLoadBalancer loadBalancer;
    private final AsyncMetrics metrics;
    
    // Player position cache for staggered updates
    private final ConcurrentHashMap<UUID, PlayerPositionCache> positionCaches;
    
    // In-memory portal cache - populated at startup, invalidated on mutations
    private volatile List<PortalEntry> portalCache = Collections.emptyList();
    
    // Staggered position update configuration
    private static final int POSITION_UPDATE_BATCH_SIZE = 10;
    private static final int POSITION_UPDATE_INTERVAL_MS = 200;
    
    public AsyncTeleportInterceptor(OptiPortal plugin, PluginConfig config,
                                   WarmZoneManager warmZoneManager,
                                   ChunkPreloader chunkPreloader, StorageBackend storage,
                                   com.optiportal.preload.PortalLinkRegistry portalLinkRegistry,
                                   RespawnTracker respawnTracker, DeathLocationTracker deathLocationTracker,
                                   GravestoneIntegration gravestoneIntegration,
                                   ScheduledExecutorService executor,
                                   WorldThreadBridge worldBridge,
                                   AsyncLoadBalancer loadBalancer,
                                   AsyncMetrics metrics) {
        super(plugin, config, warmZoneManager, chunkPreloader, storage, portalLinkRegistry,
              respawnTracker, deathLocationTracker, gravestoneIntegration, executor);
        
        this.worldBridge = worldBridge;
        this.loadBalancer = loadBalancer;
        this.metrics = metrics;
        this.positionCaches = new ConcurrentHashMap<>();
        
        // Initialize portal cache from storage
        updatePortalCache(storage.loadAll());
        
        // Start staggered position updates instead of polling all players
        startStaggeredPositionUpdates();
    }
    
    // Accessor methods for parent class private fields
    protected ScheduledExecutorService getExecutor() {
        return super.getExecutor();
    }
    
    protected ConcurrentHashMap<UUID, PlayerRef> getPlayerRefs() {
        return super.getPlayerRefs();
    }
    
    protected StorageBackend getStorage() {
        return super.getStorage();
    }
    
    protected ChunkPreloader getChunkPreloader() {
        return super.getChunkPreloader();
    }
    
    protected ConcurrentHashMap<UUID, Long> getLastSeenTeleportNanos() {
        return super.getLastSeenTeleportNanos();
    }
    
    protected PluginConfig getPluginConfig() {
        return super.getConfig();
    }
    
    protected ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> getCooldowns() {
        return super.getCooldowns();
    }
    
    protected boolean isOnCooldown(UUID playerId, String zoneId) {
        Long last = getCooldowns()
                .computeIfAbsent(playerId != null ? playerId : new UUID(0, 0), k -> new ConcurrentHashMap<>())
                .get(zoneId);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < (getConfig().getActivationCooldownSeconds() * 1000L);
    }
    
    protected void recordCooldown(UUID playerId, String zoneId) {
        getCooldowns()
                .computeIfAbsent(playerId != null ? playerId : new UUID(0, 0), k -> new ConcurrentHashMap<>())
                .put(zoneId, System.currentTimeMillis());
    }
    
    protected int resolveRadius(PortalEntry entry) {
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return getConfig().getPredictiveRadius();
    }
    
    /**
     * Update the portal cache with a new list of entries.
     * Thread-safe via volatile write.
     * Public for access from OptiPortal.
     */
    public void updatePortalCache(List<PortalEntry> newCache) {
        this.portalCache = Collections.unmodifiableList(new ArrayList<>(newCache));
    }

    /**
     * Re-fetch all entries from storage and replace the cache.
     * Call this after any external mutation (WarpFileWatcher sync, command edits, etc.).
     */
    public void refreshPortalCache() {
        updatePortalCache(getStorage().loadAll());
    }

    /**
     * Returns the in-memory portal cache instead of hitting storage on every check.
     */
    @Override
    protected List<PortalEntry> getAllPortalEntries() {
        return portalCache;
    }

    /**
     * Get the current portal cache (thread-safe read).
     */
    private List<PortalEntry> getPortalCache() {
        return portalCache;
    }
    
    /**
     * Start staggered position updates to reduce world thread impact.
     */
    private void startStaggeredPositionUpdates() {
        getExecutor().scheduleAtFixedRate(() -> {
            try {
                updatePlayerPositionsBatch();
            } catch (Exception e) {
                LOG.warning("Error in staggered position update: " + e.getMessage());
            }
        }, 0, POSITION_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Update player positions in batches to reduce world thread impact.
     */
    private void updatePlayerPositionsBatch() {
        if (getPlayerRefs().isEmpty()) {
            return;
        }
        List<UUID> playerBatch = getNextPlayerBatch();
        
        if (playerBatch.isEmpty()) {
            return;
        }
        
        // Process batch with load balancer
        loadBalancer.scheduleLoad(() -> {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
          
            for (UUID playerId : playerBatch) {
                CompletableFuture<Void> future = updatePlayerPositionAsync(playerId);
                futures.add(future);
            }
          
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        }, AsyncMetrics.AsyncTaskPriority.NORMAL);
    }
    
    /**
     * Get the next batch of players for position updates.
     * 
     * @return List of player IDs to update
     */
    private List<UUID> getNextPlayerBatch() {
        List<UUID> batch = new ArrayList<>();
        int processed = 0;
        
        for (UUID playerId : getPlayerRefs().keySet()) {
            if (processed >= POSITION_UPDATE_BATCH_SIZE) {
                break;
            }
  
            PlayerPositionCache cache = positionCaches.get(playerId);
            if (cache == null || cache.shouldUpdate()) {
                batch.add(playerId);
                processed++;
            }
        }
        
        return batch;
    }
    
    /**
     * Update player position asynchronously.
     *
     * @param playerId Player ID
     * @return CompletableFuture that completes when update is done
     */
    private CompletableFuture<Void> updatePlayerPositionAsync(UUID playerId) {
        PlayerRef playerRef = getPlayerRefs().get(playerId);
        if (playerRef == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Get world for this player
        World world = getChunkPreloader().getWorldRegistry().getWorldForPlayer(playerRef);
        if (world == null) {
            // Fallback: world lookup failed — clean up any lingering ref
            removePlayerRef(playerId);
            return CompletableFuture.completedFuture(null);
        }
        
        return worldBridge.getPlayerPositionAsync(world, playerRef)
                .thenAcceptAsync(position -> {
                    if (position != null) {
                        updatePositionCache(playerId, position);
                        checkProximityAndPreloadAsync(playerId, world.getName(), position.x, position.y, position.z);
                    }
                }, getExecutor())
                .exceptionally(ex -> {
                    LOG.warning(() -> "Position update error for player " + playerId + ": " + ex.getMessage());
                    return null;
                });
    }
    
    /**
     * Update position cache for a player.
     * 
     * @param playerId Player ID
     * @param position New position
     */
    private void updatePositionCache(UUID playerId, WorldThreadBridge.PlayerPosition position) {
        PlayerPositionCache cache = positionCaches.computeIfAbsent(playerId, 
            k -> new PlayerPositionCache());
        cache.updatePosition(position);
    }
    
    /**
     * Check proximity and trigger preload asynchronously.
     *
     * @param playerId  Player ID
     * @param worldName World name (matches PortalEntry.getWorld())
     * @param x         Player X position
     * @param z         Player Z position
     */
    @Override
    public void removePlayerRef(UUID uuid) {
        super.removePlayerRef(uuid);
        positionCaches.remove(uuid);
    }

    private void checkProximityAndPreloadAsync(UUID playerId, String worldName, double x, double y, double z) {
        List<PortalEntry> nearbyPortals = getNearbyPortals(worldName, x, y, z);
        for (PortalEntry portal : nearbyPortals) {
            CacheTier tier = getPlugin().getCacheManager().getZoneTier(portal.getId());
            if (tier == CacheTier.HOT || tier == CacheTier.WARM) {
                // Chunks are present — just ensure tier is HOT, no reload needed
                if (tier != CacheTier.HOT) {
                    getPlugin().getCacheManager().setZoneTier(portal.getId(), CacheTier.HOT);
                }
                continue;
            }
            // COLD or UNVISITED — chunks absent, trigger load
            if (!isOnCooldown(playerId, portal.getId())) {
                triggerAsyncPreload(portal, playerId);
            }
        }
    }
    
    /**
     * Get nearby portals using cached data.
     * 
     * @param worldName World name
     * @param x Player X position
     * @param z Player Z position
     * @return List of nearby portals
     */
    private List<PortalEntry> getNearbyPortals(String worldName, double x, double y, double z) {
        List<PortalEntry> nearby = new ArrayList<>();
        double rH = getPluginConfig().getActivationDistance();
        double rV = getPluginConfig().getActivationDistanceVertical();
        String globalShape = getPluginConfig().getActivationShape();
        List<PortalEntry> cache = getPortalCache();

        for (PortalEntry entry : cache) {
            if (entry.isInstanced()) continue;
            if (!entry.getWorld().equals(worldName)) continue;
            if (entry.getId().contains(":")) continue;

            double dx = entry.getX() - x;
            double dy = entry.getY() - y;
            double dz = entry.getZ() - z;

            String shape = entry.getActivationShape() != null ? entry.getActivationShape() : globalShape;

            boolean inside = switch (shape.toUpperCase()) {
                case "ELLIPSOID" -> (dx * dx) / (rH * rH) + (dy * dy) / (rV * rV) + (dz * dz) / (rH * rH) <= 1.0;
                case "BOX"       -> Math.abs(dx) <= rH && Math.abs(dy) <= rV && Math.abs(dz) <= rH;
                default          -> Math.abs(dy) <= rV && dx * dx + dz * dz <= rH * rH; // CYLINDER
            };

            if (inside) nearby.add(entry);
        }

        return nearby;
    }
    
    /**
     * Trigger async preload for a portal.
     * 
     * @param portal Portal to preload
     * @param playerId Player ID
     */
    private void triggerAsyncPreload(PortalEntry portal, UUID playerId) {
        // Record cooldown
        recordCooldown(playerId, portal.getId());
        
        // Schedule preload with proper priority and error handling
        loadBalancer.scheduleLoad(() -> {
            return getChunkPreloader().predictiveLoad(
                portal.getId(), portal.getWorld(), 
                ChunkPreloader.toChunkCoord(portal.getX()),
                ChunkPreloader.toChunkCoord(portal.getZ()),
                resolveRadius(portal));
        }, AsyncMetrics.AsyncTaskPriority.HIGH)
        .whenComplete((result, ex) -> {
            if (ex != null) {
                LOG.warning("Async preload failed for " + portal.getId() + ": " + ex.getMessage());
            } else {
                LOG.info(() -> "Async preload completed for " + portal.getId());
            }
        });
    }
    
    /**
     * Enhanced teleport record polling with better async handling.
     * Batches all player checks into a single load-balancer task to reduce
     * task queue overhead and lock contention.
     */
    protected void pollTeleportRecords() {
        List<java.util.Map.Entry<UUID, PlayerRef>> entries =
                new ArrayList<>(getPlayerRefs().entrySet());
        if (entries.isEmpty()) return;

        loadBalancer.scheduleLoad(() -> {
            List<CompletableFuture<Void>> futures = new ArrayList<>(entries.size());
            for (java.util.Map.Entry<UUID, PlayerRef> e : entries) {
                futures.add(checkTeleportRecordAsync(e.getKey(), e.getValue()));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        }, AsyncMetrics.AsyncTaskPriority.HIGH);
    }
    
    /**
     * Check teleport record asynchronously.
     *
     * @param playerId  Player ID
     * @param playerRef Player reference
     * @return CompletableFuture that completes when check is done
     */
    private CompletableFuture<Void> checkTeleportRecordAsync(UUID playerId, PlayerRef playerRef) {
        com.hypixel.hytale.server.core.universe.world.World world =
                getChunkPreloader().getWorldRegistry().getWorldForPlayer(playerRef);
        if (world == null) {
            getPlayerRefs().remove(playerId);
            positionCaches.remove(playerId);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return worldBridge.getTeleportRecordAsync(world, playerRef)
                .thenAccept(record -> {
                    if (record != null) {
                        processTeleportRecord(playerId, record);
                    }
                })
                .exceptionally(ex -> {
                    LOG.warning("Teleport record check failed for " + playerId + ": " + ex.getMessage());
                    return null;
                });
    }
    
    /**
     * Process a teleport record.
     * 
     * @param playerId Player ID
     * @param record Teleport record entry
     */
    private void processTeleportRecord(UUID playerId, com.hypixel.hytale.server.core.modules.entity.teleport.TeleportRecord.Entry record) {
        // Check if this is a new teleport
        Long prev = getLastSeenTeleportNanos().get(playerId);
        long ts = record.timestampNanos();
        
        if (prev == null || prev != ts) {
            getLastSeenTeleportNanos().put(playerId, ts);
  
            // Check age
            long ageNanos = System.nanoTime() - ts;
            if (ageNanos <= 3_000_000_000L) { // 3 seconds
                com.hypixel.hytale.math.vector.Location dest = record.destination();
                if (dest != null) {
                    String destWorld = dest.getWorld();
                    com.hypixel.hytale.math.vector.Vector3d pos = dest.getPosition();
                    if (pos != null && destWorld != null) {
                        // Process teleport asynchronously
                        processTeleportAsync(playerId, destWorld, pos.x, pos.y, pos.z);
                    }
                }
            }
        }
    }
    
    /**
     * Process teleport asynchronously.
     * 
     * @param playerId Player ID
     * @param worldName Destination world
     * @param x Destination X
     * @param y Destination Y
     * @param z Destination Z
     */
    private void processTeleportAsync(UUID playerId, String worldName, double x, double y, double z) {
        loadBalancer.scheduleLoad(() -> {
            checkProximityAndPreloadAsync(playerId, worldName, x, y, z);
            return CompletableFuture.completedFuture(null);
        }, AsyncMetrics.AsyncTaskPriority.CRITICAL);
    }
    
    /**
     * Player position cache for staggered updates.
     */
    private static class PlayerPositionCache {
        private WorldThreadBridge.PlayerPosition position;
        private long lastUpdate;
        private static final long UPDATE_INTERVAL_MS = 1000; // Update every second
        
        public boolean shouldUpdate() {
            return System.currentTimeMillis() - lastUpdate >= UPDATE_INTERVAL_MS;
        }
        
        public void updatePosition(WorldThreadBridge.PlayerPosition position) {
            this.position = position;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        public WorldThreadBridge.PlayerPosition getPosition() {
            return position;
        }
    }
    
    /**
     * Trigger a predictive load with velocity-aware radius adjustment.
     *
     * Fires the base-radius load immediately, then asynchronously reads the player's
     * speed. If speed exceeds the configured threshold and baseRadius < maxRadius,
     * fires a second load at baseRadius+1 to cover the extra ring a fast-moving
     * player will reach before preloading completes.
     */
    private void predictiveLoadWithVelocity(String zoneId, World world, String worldName,
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef,
            int cx, int cz, int baseRadius) {

        // Fire base radius load immediately — do not wait for velocity read
        predictiveLoadWithRam(zoneId, worldName, cx, cz, baseRadius);

        // Only attempt velocity boost if feature is enabled
        if (!getConfig().isVelocityAwareActivation()) {
            return;
        }

        double speedThreshold = getConfig().getVelocityRadiusBoostThreshold();
        int maxRadius = getConfig().getPredictiveRadius();

        // Async velocity check — runs on world thread, result delivered on executor thread
        worldBridge.getPlayerSpeedAsync(world, playerRef)
                .thenAccept(speed -> {
                    if (speed == null) return;
                    if (speed > speedThreshold && baseRadius < maxRadius) {
                        int boostedRadius = baseRadius + 1; // cap at +1
                        LOG.fine("[OptiPortal] Velocity boost: speed=" + String.format("%.2f", speed)
                                + " threshold=" + speedThreshold
                                + " radius=" + baseRadius + " → " + boostedRadius
                                + " zone=" + zoneId);
                        predictiveLoadWithRam(zoneId, worldName, cx, cz, boostedRadius);
                    }
                })
                .exceptionally(ex -> {
                    LOG.fine("[OptiPortal] predictiveLoadWithVelocity: speed check failed: "
                            + ex.getMessage());
                    return null;
                });
    }

    @Override
    protected void triggerPredictiveLoad(String zoneId, String worldName,
            int cx, int cz, int radius,
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef) {
        if (playerRef == null) {
            super.triggerPredictiveLoad(zoneId, worldName, cx, cz, radius, null);
            return;
        }

        World world = getChunkPreloader().getWorldRegistry().getWorldForPlayer(playerRef);
        if (world == null) {
            super.triggerPredictiveLoad(zoneId, worldName, cx, cz, radius, playerRef);
            return;
        }

        predictiveLoadWithVelocity(zoneId, world, worldName, playerRef, cx, cz, radius);
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
}
