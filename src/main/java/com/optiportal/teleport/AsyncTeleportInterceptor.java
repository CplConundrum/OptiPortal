package com.optiportal.teleport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
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
 * <p><b>DORMANT: This class is intentionally not wired into startup.</b>
 * It may be activated in a future pass if the original TeleportInterceptor proves insufficient.
 *
 * <p>This extends the original TeleportInterceptor with better async operations,
 * event-driven updates, and reduced world thread impact.
 */
public class AsyncTeleportInterceptor extends TeleportInterceptor {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final WorldThreadBridge worldBridge;
    private final AsyncLoadBalancer loadBalancer;
    private final AsyncMetrics metrics;
    
    // Player position cache for staggered updates
    private final ConcurrentHashMap<UUID, PlayerPositionCache> positionCaches;

    // Last confirmed position per player, keyed by UUID.
    // Used to detect position jumps (same-world portal teleports) in the async path.
    // Stored as double[]{x, z, worldUuidMostSig, worldUuidLeastSig} to guard against
    // cross-world false positives.
    private final ConcurrentHashMap<UUID, double[]> lastAsyncPosition = new ConcurrentHashMap<>();
    
    // In-memory portal cache - populated at startup, invalidated on mutations
    private volatile List<PortalEntry> portalCache = Collections.emptyList();
    
    // Staggered position update configuration
    private static final int POSITION_UPDATE_BATCH_SIZE = 10;
    private static final int POSITION_UPDATE_INTERVAL_MS = 200;

    // Round-robin cursor for fair player batch selection
    private final AtomicInteger batchCursor = new AtomicInteger(0);

    // Handle for the staggered position update task — cancelled in stop()
    private volatile java.util.concurrent.ScheduledFuture<?> positionUpdateTask;
    
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
        if (playerId == null) return false;
        Long last = getCooldowns()
                .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .get(zoneId);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < (getConfig().getActivationCooldownSeconds() * 1000L);
    }

    protected void recordCooldown(UUID playerId, String zoneId) {
        if (playerId == null) return;
        getCooldowns()
                .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
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
    public List<PortalEntry> getPortalCache() {
        return portalCache;
    }
    
    /**
     * Start staggered position updates to reduce world thread impact.
     */
    private void startStaggeredPositionUpdates() {
        positionUpdateTask = getExecutor().scheduleAtFixedRate(() -> {
            try {
                updatePlayerPositionsBatch();
            } catch (Exception e) {
                LOG.warning(() -> "Error in staggered position update: " + e.getMessage());
            }
        }, 0, POSITION_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Cancel the staggered position update task and parent tasks. Call from plugin shutdown before executor.shutdown(). */
    @Override
    public void stop() {
        java.util.concurrent.ScheduledFuture<?> task = positionUpdateTask;
        if (task != null) task.cancel(false);
        
        // Clear subclass-owned caches to prevent retention
        positionCaches.clear();
        lastAsyncPosition.clear();
        portalCache = Collections.emptyList();
        
        // Chain to parent to ensure pollTask and cleanupTask are also cancelled
        super.stop();
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
        List<UUID> allPlayers = new ArrayList<>(getPlayerRefs().keySet());
        int total = allPlayers.size();
        if (total == 0) return Collections.emptyList();

        // Advance cursor by one batch each call for round-robin fairness
        int start = Math.floorMod(batchCursor.getAndAdd(POSITION_UPDATE_BATCH_SIZE), total);

        List<UUID> batch = new ArrayList<>(POSITION_UPDATE_BATCH_SIZE);
        for (int i = 0; i < total && batch.size() < POSITION_UPDATE_BATCH_SIZE; i++) {
            UUID playerId = allPlayers.get((start + i) % total);
            PlayerPositionCache cache = positionCaches.get(playerId);
            if (cache == null || cache.shouldUpdate()) {
                batch.add(playerId);
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
                        positionCaches.computeIfAbsent(playerId, k -> new PlayerPositionCache()).update();

                        // Capture previous position before overwriting — used as portal source
                        // if TeleportRecord confirms a same-world adventure.teleporter jump.
                        // Store: [x, z, worldUuid.mostSigBits, worldUuid.leastSigBits]
                        double[] prev = lastAsyncPosition.get(playerId);
                        java.util.UUID wUuid = position.worldUuid;
                        double wMsb = wUuid != null ? Double.longBitsToDouble(wUuid.getMostSignificantBits()) : 0;
                        double wLsb = wUuid != null ? Double.longBitsToDouble(wUuid.getLeastSignificantBits()) : 0;
                        lastAsyncPosition.put(playerId, new double[]{position.x, position.z, wMsb, wLsb});

                        // Portal hotspot learning via TeleportRecord.
                        // adventure.teleporter sets destination world = null (same-world);
                        // command teleports (/tp home etc.) set an explicit world name.
                        // Read TeleportRecord on the world thread and return its timestamp so
                        // we can deduplicate and only learn each teleport once.
                        final double[] capturedPrev = prev;
                        final String wName = world.getName();
                        final double dstX = position.x, dstZ = position.z;
                        worldBridge.executeOnWorldThread(world, () -> {
                            try {
                                com.hypixel.hytale.server.core.modules.entity.teleport
                                        .TeleportRecord rec = playerRef.getComponent(
                                        com.hypixel.hytale.server.core.modules.entity.teleport
                                                .TeleportRecord.getComponentType());
                                if (rec == null) return null;
                                var entry = rec.getLastTeleport();
                                if (entry == null) return null;
                                var dest = entry.destination();
                                // Only same-world adventure.teleporter portals have null world
                                if (dest == null || dest.getWorld() != null) return null;
                                return entry.timestampNanos();
                            } catch (Exception e) {
                                return null;
                            }
                        }).thenAcceptAsync((Long ts) -> {
                            if (ts == null) return;
                            // Deduplicate: each teleport event has a unique nanosecond timestamp
                            Long prevTs = getLastSeenTeleportNanos().get(playerId);
                            if (prevTs != null && prevTs.equals(ts)) return;
                            getLastSeenTeleportNanos().put(playerId, ts);
                            // We need a pre-teleport position in the same world as source
                            if (capturedPrev == null
                                    || capturedPrev[2] != wMsb || capturedPrev[3] != wLsb) return;
                            final double srcX = capturedPrev[0], srcZ = capturedPrev[1];
                            // Find nearest destination zone to landing position
                            PortalEntry best = null;
                            double bestDist = getPluginConfig().getActivationDistance();
                            double bestDistSq = bestDist * bestDist;
                            for (PortalEntry pe : getPortalCache()) {
                                if (pe.isInstanced() || pe.getId().contains(":")) continue;
                                if (!pe.getWorld().equals(wName)) continue;
                                double dx = pe.getX() - dstX;
                                double dz = pe.getZ() - dstZ;
                                double dSq = dx * dx + dz * dz;
                                if (dSq < bestDistSq) { bestDistSq = dSq; best = pe; }
                            }
                            if (best != null) {
                                final PortalEntry fBest = best;
                                learnPortalHotspot(wName, srcX, srcZ, fBest.getId());
                            }
                        }, getExecutor()).exceptionally(ex -> null);

                        checkProximityAndPreloadAsync(playerId, world.getName(), position.x, position.y, position.z);
                    }
                }, getExecutor())
                .exceptionally(ex -> {
                    LOG.warning(() -> "Position update error for player " + playerId + ": " + ex.getMessage());
                    return null;
                });
    }
    
    @Override
    public void removePlayerRef(UUID uuid) {
        super.removePlayerRef(uuid);
        positionCaches.remove(uuid);
        lastAsyncPosition.remove(uuid);
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
            } else {
                // COLD or UNVISITED — chunks absent, trigger load
                if (!isOnCooldown(playerId, portal.getId())) {
                    triggerAsyncPreload(portal, playerId);
                }
            }

            // Also preload the portal linked to this one (e.g. the return trip)
            String linkedId = getPortalLinkRegistry().getLinkedPortal(portal.getId());
            if (linkedId != null) {
                long now = System.currentTimeMillis();
                boolean shouldFire = reversePreloadCooldowns.compute(linkedId, (k, last) ->
                        (last == null || now - last >= 30_000L) ? now : last) == now;
                if (shouldFire) {
                    getStorage().loadById(linkedId).ifPresent(linked -> {
                        loadBalancer.scheduleLoad(() ->
                            getChunkPreloader().predictiveLoad(
                                linkedId, linked.getWorld(),
                                ChunkPreloader.toChunkCoord(linked.getX()),
                                ChunkPreloader.toChunkCoord(linked.getZ()),
                                resolveRadius(linked)),
                            AsyncMetrics.AsyncTaskPriority.HIGH)
                        .whenComplete((r, ex) -> {
                            if (ex != null)
                                LOG.warning("[OptiPortal] Linked async preload failed for "
                                        + linkedId + ": " + ex.getMessage());
                        });
                    });
                }
            }
        }

        // Preload destination zones for any nearby portal devices in this (source) world
        checkNearbyPortalDevicesForDestination(playerId, worldName, x, y, z);

        // Promote destination zones to HOT for any learned portal hotspots near the player
        checkHotspotPromotion(worldName, x, z);
    }

    /**
     * Scan for portaldevice entries in the player's current (source) world.
     * When the player is within activation distance of a portal device, trigger
     * load of the corresponding portaldest zone in the destination world.
     *
     * This fires pre-arrival: the destination world warms up while the player
     * is still standing in front of the portal, not after they step through.
     */
    private void checkNearbyPortalDevicesForDestination(UUID playerId, String worldName,
            double x, double y, double z) {
        double activationDist = getPluginConfig().getActivationDistance();
        double activationDistV = getPluginConfig().getActivationDistanceVertical();

        for (PortalEntry device : getPortalCache()) {
            if (!device.getId().startsWith("portaldevice:")) continue;
            if (!device.getWorld().equals(worldName)) continue;

            double dx = device.getX() - x;
            double dy = device.getY() - y;
            double dz = device.getZ() - z;
            if (Math.abs(dy) > activationDistV) continue;
            if (dx * dx + dz * dz > activationDist * activationDist) continue;

            // Player is near this portal device — resolve destination world if UUID is known.
            // If UUID is not yet persisted (pre-1.1.4 entries), destWorldName stays null and
            // we promote all portaldest zones rather than skipping entirely.
            java.util.UUID destUuid = device.getDestinationWorldUuid();
            String destWorldName = null;
            if (destUuid != null) {
                World destWorld = getChunkPreloader().getWorldRegistry().resolveWorld(destUuid, null);
                if (destWorld != null) {
                    destWorldName = destWorld.getName();
                }
            }

            // Find all destination zones and trigger load
            for (PortalEntry dest : getPortalCache()) {
                if (dest.isInstanced()) continue;
                if (dest.getId().startsWith("portaldevice:")) continue; // skip source-side entries

                // If destination world is resolved, restrict to that world; otherwise consider all zones
                if (destWorldName != null && !dest.getWorld().equals(destWorldName)) continue;

                CacheTier tier = getPlugin().getCacheManager().getZoneTier(dest.getId());
                if (tier == CacheTier.HOT) continue;
                if (tier == CacheTier.WARM) {
                    getPlugin().getCacheManager().setZoneTier(dest.getId(), CacheTier.HOT);
                    LOG.fine(() -> "[OptiPortal] Portal device approach: promoted " + dest.getId() + " → HOT");
                    continue;
                }
                // Only trigger a full chunk load when we have a precise destination match
                if (destWorldName != null && !isOnCooldown(playerId, dest.getId())) {
                    LOG.fine(() -> "[OptiPortal] Portal device approach: triggering preload of "
                            + dest.getId() + " (player near " + device.getId() + ")");
                    triggerAsyncPreload(dest, playerId);
                }
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
        double globalR = getPluginConfig().getActivationDistance();
        String globalShape = getPluginConfig().getActivationShape();
        List<PortalEntry> cache = getPortalCache();

        for (PortalEntry entry : cache) {
            if (entry.isInstanced()) continue;
            if (!entry.getWorld().equals(worldName)) continue;
            if (entry.getId().contains(":")) continue;

            // Per-zone horizontal activation distance override (F1)
            double rH = (entry.getActivationDistanceHorizontal() != null)
                    ? entry.getActivationDistanceHorizontal()
                    : globalR;
            
            double rV = (entry.getActivationDistanceVertical() != null)
                    ? entry.getActivationDistanceVertical()
                    : getPluginConfig().getActivationDistanceVertical();
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
        // H3: resolve destination world and validate it before wasting chunk budget
        World destWorld = getChunkPreloader().getWorldRegistry()
                .resolveWorld(portal.getDestinationWorldUuid(), portal.getWorld());

        if (!WarmZoneManager.isPortalWorldUsable(destWorld)) {
            LOG.fine("[OptiPortal] triggerAsyncPreload: skipping invalid portal world for zone "
                    + portal.getId());
            return;
        }

        // H3: skip if the player has already died in this portal world (they will see death UI)
        if (destWorld != null && playerId != null) {
            try {
                PortalWorld pw = destWorld.getEntityStore()
                        .getStore().getResource(PortalWorld.getResourceType());
                if (pw != null && pw.getDiedInWorld().contains(playerId)) {
                    LOG.fine("[OptiPortal] triggerAsyncPreload: player " + playerId
                            + " died in portal world — skipping predictive load for " + portal.getId());
                    return;
                }
            } catch (Exception ignored) {
                // Not a portal world resource — proceed normally
            }
        }

        // Record cooldown
        recordCooldown(playerId, portal.getId());

        // Schedule preload with proper priority and error handling
        // Uses PortalEntry overload for UUID-keyed world resolution (H7)
        loadBalancer.scheduleLoad(() ->
            getChunkPreloader().predictiveLoad(
                portal,
                ChunkPreloader.toChunkCoord(portal.getX()),
                ChunkPreloader.toChunkCoord(portal.getZ()),
                resolveRadius(portal)),
        AsyncMetrics.AsyncTaskPriority.HIGH)
        .whenComplete((result, ex) -> {
            if (ex != null) {
                LOG.warning("[OptiPortal] Async preload failed for " + portal.getId() + ": " + ex.getMessage());
            } else {
                LOG.fine(() -> "[OptiPortal] Async preload completed for " + portal.getId());
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
            Queue<CompletableFuture<Void>> futures = new ArrayDeque<>(entries.size());
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
            removePlayerRef(playerId);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        final String worldName = world.getName();
        return worldBridge.getTeleportRecordAsync(world, playerRef)
                .thenAccept(record -> {
                    if (record != null) {
                        processTeleportRecord(playerId, record, worldName);
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
    private void processTeleportRecord(UUID playerId, com.hypixel.hytale.server.core.modules.entity.teleport.TeleportRecord.Entry record, String currentWorldName) {
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
                    // Same-world adventure.teleporter portals omit the world name —
                    // fall back to the world the player is currently in.
                    if (destWorld == null) destWorld = currentWorldName;
                    com.hypixel.hytale.math.vector.Vector3d pos = dest.getPosition();
                    if (pos != null) {
                        // Process teleport — proximity check / tier promotion
                        processTeleportAsync(playerId, destWorld, pos.x, pos.y, pos.z);
                        // Portal link learning: record source→destination mapping.
                        // Capture the last-known async position as the pre-teleport source.
                        // There is an inherent race with updatePlayerPositionAsync but this
                        // is best-effort — the hotspot system also learns independently.
                        final double[] srcPos = lastAsyncPosition.get(playerId);
                        final String fDestWorld = destWorld;
                        final double fdx = pos.x, fdy = pos.y, fdz = pos.z;
                        lingerOriginZone(playerId, currentWorldName);
                        String originId = srcPos != null
                                ? findNearestPortal(currentWorldName, srcPos[0], 0, srcPos[1])
                                : null;
                        // D1: Guard against race condition where srcPos is null
                        if (srcPos != null) {
                            reversePreloadOrigin(playerId, originId, currentWorldName,
                                    fDestWorld, fdx, fdy, fdz, srcPos);
                        }
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
        private long lastUpdate;
        private static final long UPDATE_INTERVAL_MS = 1000; // Update every second

        public boolean shouldUpdate() {
            return System.currentTimeMillis() - lastUpdate >= UPDATE_INTERVAL_MS;
        }

        public void update() {
            this.lastUpdate = System.currentTimeMillis();
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
                        LOG.fine(() -> String.format(
                                "[OptiPortal] Velocity boost: speed=%.2f threshold=%s radius=%d → %d zone=%s",
                                speed, speedThreshold, baseRadius, boostedRadius, zoneId));
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
