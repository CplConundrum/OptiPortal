package com.optiportal.teleport;

import java.util.ArrayList;
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
        String key = (playerId != null ? playerId.toString() : "global") + ":" + zoneId;
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
            return CompletableFuture.completedFuture(null);
        }
        
        return worldBridge.getPlayerPositionAsync(world, playerRef)
                .thenAccept(position -> {
                    if (position != null) {
                        updatePositionCache(playerId, position);
                        checkProximityAndPreloadAsync(playerId, position);
                    }
                })
                .exceptionally(ex -> {
                    LOG.warning("Position update error for player " + playerId + ": " + ex.getMessage());
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
     * @param playerId Player ID
     * @param position Player position
     */
    private void checkProximityAndPreloadAsync(UUID playerId, WorldThreadBridge.PlayerPosition position) {
        // Use cached portal data to avoid storage access on every check
        List<PortalEntry> nearbyPortals = getNearbyPortals(
            position.worldUuid.toString(), position.x, position.z);
        
        for (PortalEntry portal : nearbyPortals) {
            if (shouldPreloadPortal(playerId, portal, position)) {
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
    private List<PortalEntry> getNearbyPortals(String worldName, double x, double z) {
        List<PortalEntry> nearby = new ArrayList<>();
        double maxDist = getPluginConfig().getActivationDistance();
        
        for (PortalEntry entry : getStorage().loadAll()) {
            if (entry.isInstanced()) continue;
            if (!entry.getWorld().equals(worldName)) continue;
            if (entry.getId().contains(":")) continue;
            
            double dist = Math.sqrt(Math.pow(entry.getX() - x, 2) + Math.pow(entry.getZ() - z, 2));
            if (dist <= maxDist) {
                nearby.add(entry);
            }
        }
        
        return nearby;
    }
    
    /**
     * Check if a portal should be preloaded for a player.
     * 
     * @param playerId Player ID
     * @param portal Portal entry
     * @param position Player position
     * @return True if portal should be preloaded
     */
    private boolean shouldPreloadPortal(UUID playerId, PortalEntry portal, 
                                       WorldThreadBridge.PlayerPosition position) {
        // Check cooldown
        if (isOnCooldown(playerId, portal.getId())) {
            return false;
        }
        
        // Check distance
        double dist = Math.sqrt(Math.pow(portal.getX() - position.x, 2) + 
                               Math.pow(portal.getZ() - position.z, 2));
        double maxDist = getPluginConfig().getActivationDistance();
        
        return dist <= maxDist;
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
                LOG.info("Async preload completed for " + portal.getId());
            }
        });
    }
    
    /**
     * Enhanced teleport record polling with better async handling.
     */
    protected void pollTeleportRecords() {
        // Use world bridge for safer async access
        for (java.util.Map.Entry<UUID, PlayerRef> entry : getPlayerRefs().entrySet()) {
            UUID uuid = entry.getKey();
            PlayerRef pRef = entry.getValue();
            
            // Schedule async teleport record check
            loadBalancer.scheduleLoad(() -> {
                return checkTeleportRecordAsync(uuid, pRef);
            }, AsyncMetrics.AsyncTaskPriority.HIGH);
        }
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
            checkProximityAndPreloadAsync(playerId, 
                new WorldThreadBridge.PlayerPosition(null, x, y, z));
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