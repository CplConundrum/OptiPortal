package com.optiportal.teleport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.hypixel.hytale.builtin.adventure.teleporter.interaction.server.UsedTeleporter;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.math.vector.Location;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DiscoverZoneEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportRecord;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.optiportal.OptiPortal;
import com.optiportal.config.PluginConfig;
import com.optiportal.integrations.GravestoneIntegration;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.player.DeathLocationTracker;
import com.optiportal.player.RespawnTracker;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.storage.StorageBackend;

/**
 * Wires up player events to chunk pre-loading.
 *
 * Three hooks:
 *
 * 1. DiscoverZoneEvent — fires when a player enters a named zone.
 *    Zone names in Hytale correspond to warp/portal IDs. If the discovered
 *    zone matches a PREDICTIVE portal entry, fire a predictive load.
 *    This is the closest Hytale exposes to a "player approached a portal" event.
 *
 * 2. AddPlayerToWorldEvent — fires when a player arrives in a world
 *    (login, world switch, teleport). Pre-load WARM zones for that world
 *    that aren't already HOT.
 *
 * 3. PlayerReadyEvent — fires when the player's client is fully ready.
 *    Trigger bed + death location pre-loading for respawn screen preparation.
 *
 * No PlayerMoveEvent exists in this Hytale build.
 */
public class TeleportInterceptor {

    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final OptiPortal plugin;
    private final PluginConfig config;
    private final WarmZoneManager warmZoneManager;
    private final ChunkPreloader chunkPreloader;
    private final StorageBackend storage;
    private final com.optiportal.preload.PortalLinkRegistry portalLinkRegistry;
    private final RespawnTracker respawnTracker;
    private final DeathLocationTracker deathLocationTracker;
    private final GravestoneIntegration gravestoneIntegration;
    private volatile List<PortalEntry> portalCache = Collections.emptyList();
    private volatile Map<String, List<PortalEntry>> portalCacheByWorld = Collections.emptyMap();
    /** Spatial index by world: worldName → chunk-bucketed portal entries. */
    private volatile Map<String, PortalSpatialIndex> portalSpatialIndexByWorld = Collections.emptyMap();

    /**
     * Fallback pending respawn set used when GravestoneIntegration is null
     * (gravestones plugin not installed). Populated by the re-login detection
     * in onPlayerReady.
     */
    private final Set<UUID> pendingRespawnCapture = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Separate cooldown for reverse preloads — independent of proximity cooldown. */
    protected final ConcurrentHashMap<String, Long> reversePreloadCooldowns = new ConcurrentHashMap<>();

    // Cooldown tracking: playerId → zoneId → last trigger time ms
    // Prevents re-triggering the same zone preload repeatedly
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> cooldowns
            = new ConcurrentHashMap<>();

    // Online player ref cache: populated on PlayerReadyEvent, used by command UI lookup
    final ConcurrentHashMap<UUID, PlayerRef> playerRefs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;
    // Track last-seen TeleportRecord timestamp per player to avoid re-triggering
    private final ConcurrentHashMap<UUID, Long> lastSeenTeleportNanos = new ConcurrentHashMap<>();
    /** Tracks player's last known position before teleport for origin portal detection */
    private final ConcurrentHashMap<UUID, double[]> lastKnownPosition = new ConcurrentHashMap<>();
    /** Last position that actually triggered a proximity scan; used to skip tiny no-op movement. */
    private final ConcurrentHashMap<UUID, double[]> lastProximityCheckPosition = new ConcurrentHashMap<>();
    /** Last wall-clock time a proximity scan actually ran for this player. */
    private final ConcurrentHashMap<UUID, Long> lastProximityCheckMs = new ConcurrentHashMap<>();

    /**
     * Portal hotspot index: source-world → list of confirmed hotspots.
     * Each hotspot maps a source position (near an adventure.teleporter block) to the
     * destination zone ID. Populated by learnPortalHotspot after reaching the confidence
     * threshold. Used by checkHotspotPromotion.
     */
    protected final ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<PortalHotspot>>
            portalHotspots = new ConcurrentHashMap<>();

    /**
     * Pending hotspot observations: canonical key → count.
     * Key format: "sourceWorld:chunkX:chunkZ:destZoneId".
     * Entries graduate to portalHotspots after HOTSPOT_CONFIDENCE_THRESHOLD observations.
     * Prevents one-off teleports (/tp home, admin commands) from creating false hotspots.
     */
    private final ConcurrentHashMap<String, PendingHotspot> pendingHotspotCounts = new ConcurrentHashMap<>();

    private ScheduledFuture<?> pollTask;
    private ScheduledFuture<?> cleanupTask;
    private ScheduledFuture<?> cooldownCleanupTask;
    /** Round-robin cursor for fair player batch selection */
    private final AtomicInteger proximityBatchCursor = new AtomicInteger(0);
    /** Task for staggered routine proximity scanning */
    private volatile ScheduledFuture<?> staggeredProximityTask;

    // Staggered proximity update configuration
    private static final int POSITION_UPDATE_BATCH_SIZE = 10;
    private static final int POSITION_UPDATE_INTERVAL_MS = 200;

    private static final int  HOTSPOT_CONFIDENCE_THRESHOLD  = 3;
    private static final long PENDING_HOTSPOT_TTL_MS        = 7L  * 24 * 60 * 60 * 1000;
    private static final long CONFIRMED_HOTSPOT_TTL_MS      = 30L * 24 * 60 * 60 * 1000;
    private static final long HOTSPOT_CLEANUP_INTERVAL_H    = 24;
    private static final long COOLDOWN_CLEANUP_INTERVAL_H    = 1;


    public TeleportInterceptor(OptiPortal plugin, PluginConfig config,
                               WarmZoneManager warmZoneManager,
                               ChunkPreloader chunkPreloader, StorageBackend storage,
                               com.optiportal.preload.PortalLinkRegistry portalLinkRegistry,
                               RespawnTracker respawnTracker, DeathLocationTracker deathLocationTracker,
                               GravestoneIntegration gravestoneIntegration,
                               ScheduledExecutorService executor) {
        this.plugin = plugin;
        this.config = config;
        this.warmZoneManager = warmZoneManager;
        this.chunkPreloader = chunkPreloader;
        this.storage = storage;
        this.portalLinkRegistry = portalLinkRegistry;
        this.respawnTracker = respawnTracker;
        this.deathLocationTracker = deathLocationTracker;
        this.gravestoneIntegration = gravestoneIntegration;
        this.executor = executor;
        refreshPortalCache();
        // Poll TeleportRecord every second to detect walk-through portal teleports.
        // No portal-use event exists in Hytale for zone-trigger portals.
        int pollInterval = config.getPollIntervalSeconds();
        pollTask = executor.scheduleWithFixedDelay(
                this::pollTeleportRecords, pollInterval, pollInterval, TimeUnit.SECONDS);
        cleanupTask = executor.scheduleWithFixedDelay(
                this::cleanupStaleHotspots,
                HOTSPOT_CLEANUP_INTERVAL_H, HOTSPOT_CLEANUP_INTERVAL_H, TimeUnit.HOURS);
        cooldownCleanupTask = executor.scheduleWithFixedDelay(
                this::pruneStaleCooldowns,
                COOLDOWN_CLEANUP_INTERVAL_H, COOLDOWN_CLEANUP_INTERVAL_H, TimeUnit.HOURS);
        // Start staggered routine proximity scanning to reduce burstiness in proximity work
        staggeredProximityTask = executor.scheduleAtFixedRate(
                this::runStaggeredProximityBatch,
                POSITION_UPDATE_INTERVAL_MS,
                POSITION_UPDATE_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    // Protected getters for subclass access
    protected PluginConfig getConfig() {
        return config;
    }

    protected OptiPortal getPlugin() {
        return plugin;
    }

    protected ConcurrentHashMap<UUID, PlayerRef> getPlayerRefs() {
        return playerRefs;
    }

    protected StorageBackend getStorage() {
        return storage;
    }

    protected ChunkPreloader getChunkPreloader() {
        return chunkPreloader;
    }

    protected com.optiportal.preload.PortalLinkRegistry getPortalLinkRegistry() {
        return portalLinkRegistry;
    }

    /**
     * Refreshes the in-memory portal cache from storage.
     */
    public void refreshPortalCache() {
        rebuildPortalCachesFrom(storage.loadAll());
    }

    private void rebuildPortalCachesFrom(List<PortalEntry> entries) {
        List<PortalEntry> allSnapshot = Collections.unmodifiableList(new java.util.ArrayList<>(entries));

        // Group entries by world
        Map<String, List<PortalEntry>> grouped = new HashMap<>();
        for (PortalEntry entry : allSnapshot) {
            grouped.computeIfAbsent(entry.getWorld(), k -> new java.util.ArrayList<>()).add(entry);
        }

        // Build immutable portalCacheByWorld
        Map<String, List<PortalEntry>> byWorldSnapshot = new HashMap<>();
        for (Map.Entry<String, List<PortalEntry>> e : grouped.entrySet()) {
            byWorldSnapshot.put(
                    e.getKey(),
                    Collections.unmodifiableList(new java.util.ArrayList<>(e.getValue()))
            );
        }

        // Build immutable portalSpatialIndexByWorld
        Map<String, PortalSpatialIndex> spatialIndexSnapshot = new java.util.HashMap<>(grouped.size());
        for (Map.Entry<String, List<PortalEntry>> e : grouped.entrySet()) {
            spatialIndexSnapshot.put(e.getKey(), new PortalSpatialIndex(e.getValue()));
        }

        // Publish all three caches via volatile writes
        this.portalCache = allSnapshot;
        this.portalCacheByWorld = Collections.unmodifiableMap(byWorldSnapshot);
        this.portalSpatialIndexByWorld = Collections.unmodifiableMap(spatialIndexSnapshot);
    }

    /**
     * Returns all portal entries for proximity checks.
     */
    protected List<PortalEntry> getAllPortalEntries() {
        return portalCache;
    }

    protected List<PortalEntry> getPortalEntriesForWorld(String worldName) {
        if (worldName == null) return Collections.emptyList();
        return portalCacheByWorld.getOrDefault(worldName, Collections.emptyList());
    }

    /** Returns the spatial index for a world, or null if world has no portals. */
    protected PortalSpatialIndex getSpatialIndexForWorld(String worldName) {
        if (worldName == null) return null;
        return portalSpatialIndexByWorld.get(worldName);
    }

    /**
     * Gets nearby plain portal candidates using chunk-bucketed spatial index.
     * Queries chunk neighborhoods around (x, z) and returns a deduplicated candidate set.
     *
     * @param worldName World name to search in
     * @param x X coordinate (world space)
     * @param z Z coordinate (world space)
     * @param radiusBlocks Search radius in blocks
     * @return Deduplicated list of plain portal candidates
     */
    protected List<PortalEntry> getNearbyPlainPortalCandidates(String worldName, double x, double z, double radiusBlocks) {
        if (worldName == null || radiusBlocks < 0) return Collections.emptyList();

        PortalSpatialIndex index = getSpatialIndexForWorld(worldName);
        if (index == null) return Collections.emptyList();

        // Convert to chunk coordinates
        int cx = ChunkPreloader.toChunkCoord(x);
        int cz = ChunkPreloader.toChunkCoord(z);

        // Calculate chunk search radius (1 chunk per 32 blocks)
        int radiusChunks = Math.max(1, (int) Math.ceil(radiusBlocks / 32.0));

        // Collect candidates from chunk neighborhood
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        java.util.ArrayList<PortalEntry> candidates = new java.util.ArrayList<>();

        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                long chunkKey = ChunkPreloader.packChunk(cx + dx, cz + dz);
                for (PortalEntry entry : index.getPlainPortalsInChunk(chunkKey)) {
                    if (seenIds.add(entry.getId())) {
                        candidates.add(entry);
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Gets nearby portal device candidates using chunk-bucketed spatial index.
     *
     * @param worldName World name to search in
     * @param x X coordinate (world space)
     * @param z Z coordinate (world space)
     * @param radiusBlocks Search radius in blocks
     * @return Deduplicated list of portal device candidates
     */
    protected List<PortalEntry> getNearbyPortalDeviceCandidates(String worldName, double x, double z, double radiusBlocks) {
        if (worldName == null || radiusBlocks < 0) return Collections.emptyList();

        PortalSpatialIndex index = getSpatialIndexForWorld(worldName);
        if (index == null) return Collections.emptyList();

        // Convert to chunk coordinates
        int cx = ChunkPreloader.toChunkCoord(x);
        int cz = ChunkPreloader.toChunkCoord(z);

        // Calculate chunk search radius
        int radiusChunks = Math.max(1, (int) Math.ceil(radiusBlocks / 32.0));

        // Collect candidates from chunk neighborhood
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        java.util.ArrayList<PortalEntry> candidates = new java.util.ArrayList<>();

        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                long chunkKey = ChunkPreloader.packChunk(cx + dx, cz + dz);
                for (PortalEntry entry : index.getPortalDevicesInChunk(chunkKey)) {
                    if (seenIds.add(entry.getId())) {
                        candidates.add(entry);
                    }
                }
            }
        }

        return candidates;
    }

    protected ConcurrentHashMap<UUID, Long> getLastSeenTeleportNanos() {
        return lastSeenTeleportNanos;
    }

    protected ScheduledExecutorService getExecutor() {
        return executor;
    }

    protected ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> getCooldowns() {
        return cooldowns;
    }

    /**
     * Register all event listeners. Call from OptiPortal.registerEvents().
     */
    public void registerEvents(EventRegistry events) {
        // DiscoverZoneEvent$Display is the concrete subclass dispatched by Hytale.
        // The abstract DiscoverZoneEvent is never dispatched directly.
        events.registerUnhandled(DiscoverZoneEvent.Display.class, this::onDiscoverZone);

        // AddPlayerToWorldEvent — IEvent<String> → registerGlobal
        events.<String, AddPlayerToWorldEvent>registerGlobal(
                AddPlayerToWorldEvent.class, this::onAddPlayerToWorld);

        // PlayerReadyEvent — PlayerEvent<String> → registerGlobal
        events.<String, PlayerReadyEvent>registerGlobal(
                PlayerReadyEvent.class, this::onPlayerReady);

        // PlayerDisconnectEvent — IEvent<Void> → registerGlobal
        events.<Void, PlayerDisconnectEvent>registerGlobal(
                PlayerDisconnectEvent.class, this::onPlayerDisconnect);

        // DrainPlayerFromWorldEvent — fires on the world thread when a player leaves
        // any world (portal travel, world switch, etc.). Gives us the exact departure
        // position for origin zone linger and portal link learning — zero polling delay.
        events.<String, DrainPlayerFromWorldEvent>registerGlobal(
                DrainPlayerFromWorldEvent.class, this::onDrainPlayerFromWorld);

        // PlayerConnectEvent — fires before the player is placed in a world.
        // IEvent<Void> → must use register(), not registerGlobal().
        // We use this to pre-warm the spawn chunk area before the player arrives,
        // eliminating cold-load spikes on first login and after world transitions.
        events.register(PlayerConnectEvent.class, this::onPlayerConnect);
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    /**
     * Player discovered a zone — check if it matches a PREDICTIVE portal.
     * Zone name format in Hytale: regionName from ZoneDiscoveryInfo.
     */
    private void onDiscoverZone(DiscoverZoneEvent.Display event) {
        // Guard against post-shutdown execution
        if (plugin.isShuttingDown()) return;
        
        String zoneName = event.getDiscoveryInfo().regionName();
        LOG.fine(() -> "[OptiPortal] DiscoverZoneEvent raw='" + zoneName + "'");
        if (zoneName == null || zoneName.isBlank()) return;

        String normalizedZone = normalizeZoneName(zoneName);
        LOG.fine(() -> "[OptiPortal] DiscoverZoneEvent normalized='" + normalizedZone + "'");

        boolean matched = false;

        // Try normalized name first
        var entry1 = storage.loadById(normalizedZone);
        if (entry1.isPresent()) {
            var entry = entry1.get();
            LOG.fine(() -> "[OptiPortal] DiscoverZoneEvent matched storage id='" + normalizedZone
                + "' strategy=" + entry.getStrategy());
            if (!entry.isInstanced() && entry.getStrategy() == WarmStrategy.PREDICTIVE) {
                if (!isOnCooldown(null, normalizedZone)) {
                    recordCooldown(null, normalizedZone);
                    int cx = ChunkPreloader.toChunkCoord(entry.getX());
                    int cz = ChunkPreloader.toChunkCoord(entry.getZ());
                    int radius = resolveRadius(entry);
                    LOG.fine(() -> "[OptiPortal] Zone discovery trigger: " + normalizedZone
                            + " → predictive load cx=" + cx + " cz=" + cz + " r=" + radius);
                    triggerPredictiveLoad(normalizedZone, entry.getWorld(), cx, cz, radius, null);
                    matched = true;
                }
            } else if (!entry.isInstanced() && entry.getStrategy() == WarmStrategy.WARM) {
                com.optiportal.model.CacheTier current = plugin.getCacheManager().getZoneTier(normalizedZone);
                if (current != com.optiportal.model.CacheTier.HOT) {
                    if (plugin.getCacheManager().getOwnedChunkCount(normalizedZone) > 0) {
                        plugin.getCacheManager().setZoneTier(normalizedZone, com.optiportal.model.CacheTier.HOT);
                        LOG.fine(() -> "[OptiPortal] Zone discovery HOT: " + normalizedZone
                                + " (was " + current + ")");
                    } else {
                        // Zone has no owned/pinned chunks — load failed or not yet completed.
                        // Re-trigger warm load; setZoneTier(HOT) will be called on completion.
                        LOG.fine(() -> "[OptiPortal] Zone discovery: WARM zone '" + normalizedZone
                                + "' has no owned chunks — re-triggering warm load");
                        warmZoneManager.loadWarmZone(entry);
                    }
                }
                matched = true;
            }
        }

        // Also try original name if different
        if (!matched && !normalizedZone.equals(zoneName)) {
            var entry2 = storage.loadById(zoneName);
            if (entry2.isPresent()) {
                var entry = entry2.get();
                LOG.fine(() -> "[OptiPortal] DiscoverZoneEvent matched storage id='" + zoneName
                    + "' strategy=" + entry.getStrategy());
                if (!entry.isInstanced() && entry.getStrategy() == WarmStrategy.PREDICTIVE) {
                    if (!isOnCooldown(null, zoneName)) {
                        recordCooldown(null, zoneName);
                        int cx = ChunkPreloader.toChunkCoord(entry.getX());
                        int cz = ChunkPreloader.toChunkCoord(entry.getZ());
                        LOG.fine(() -> "[OptiPortal] Zone discovery trigger (raw): " + zoneName
                                + " → predictive load cx=" + cx + " cz=" + cz);
                        triggerPredictiveLoad(zoneName, entry.getWorld(), cx, cz, resolveRadius(entry), null);
                        matched = true;
                    }
                } else if (!entry.isInstanced() && entry.getStrategy() == WarmStrategy.WARM) {
                    com.optiportal.model.CacheTier current = plugin.getCacheManager().getZoneTier(zoneName);
                    if (current != com.optiportal.model.CacheTier.HOT) {
                        if (plugin.getCacheManager().getOwnedChunkCount(zoneName) > 0) {
                            plugin.getCacheManager().setZoneTier(zoneName, com.optiportal.model.CacheTier.HOT);
                            LOG.fine(() -> "[OptiPortal] Zone discovery HOT (raw): " + zoneName
                                    + " (was " + current + ")");
                        } else {
                            LOG.fine(() -> "[OptiPortal] Zone discovery (raw): WARM zone '" + zoneName
                                    + "' has no owned chunks — re-triggering warm load");
                            warmZoneManager.loadWarmZone(entry);
                        }
                    }
                    matched = true;
                }
            }
        }

        if (!matched) {
            LOG.fine(() -> "[OptiPortal] DiscoverZoneEvent no match for raw='" + zoneName
                + "' normalized='" + normalizedZone + "' — check portal IDs in storage");
        }
    }

    /**
     * Polls TeleportRecord for all online players every second.
     * Detects walk-through portal teleports that fire no player-facing event.
     */
    protected void pollTeleportRecords() {
        // getComponent must be called on the world tick thread.
        // Submit the read to the world via World.execute(Runnable).
        for (java.util.Map.Entry<UUID, PlayerRef> entry : playerRefs.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerRef pRef = entry.getValue();
            try {
                World world = chunkPreloader.getWorldRegistry().getWorldForPlayer(pRef);
                if (world == null) {
                    // Player is no longer in any world — clean up stale ref
                    playerRefs.remove(uuid);
                    lastSeenTeleportNanos.remove(uuid);
                    lastKnownPosition.remove(uuid);
                    cooldowns.remove(uuid);
                    continue;
                }
                final World w = world;
                w.execute(() -> {
                    try {
                        // ── Teleport detection ─────────────────────────────
                        TeleportRecord record = pRef.getComponent(TeleportRecord.getComponentType());
                        if (record != null) {
                            TeleportRecord.Entry last = record.getLastTeleport();
                            if (last != null) {
                                long ts = last.timestampNanos();
                                Long prev = lastSeenTeleportNanos.get(uuid);
                                if (prev == null || prev != ts) {
                                    lastSeenTeleportNanos.put(uuid, ts);
                                    long ageNanos = System.nanoTime() - ts;
                                    if (ageNanos <= 3_000_000_000L) {
                                        Location dest = last.destination();
                                        if (dest != null) {
                                            String destWorld = dest.getWorld();
                                            // Same-world teleports omit the world name in TeleportRecord
                                            if (destWorld == null) destWorld = w.getName();
                                            com.hypixel.hytale.math.vector.Vector3d pos = dest.getPosition();
                                            if (pos != null) {
                                                final String dw = destWorld;
                                                final double dx = pos.x, dy = pos.y, dz = pos.z;
                                                final UUID fUuid = uuid;
                                                // Snapshot pre-teleport position for origin portal lookup
                                                final double[] prePos = lastKnownPosition.get(fUuid);
                                                // Compute source world before entering executor lambda
                                                final String sourceWorld = dw.equals(w.getName()) ? dw : w.getName();
                                                executor.execute(() -> {
                                                    // Guard against post-shutdown execution
                                                    if (plugin.isShuttingDown()) return;
                                                    
                                                    LOG.fine(() -> "[OptiPortal] Poll teleport → "
                                                        + dx + "," + dy + "," + dz + " world=" + dw);
                                                    lingerOriginZone(fUuid, sourceWorld);
                                                    checkProximityAndPreload(fUuid, dw, dx, dy, dz);
                                                    // Derive origin portal from pre-teleport position
                                                    String originId = prePos != null
                                                        ? findNearestPortal(sourceWorld, prePos[0], prePos[1], prePos[2])
                                                        : null;
                                                    // Pass prePos directly — by the time this executor task
                                                    // runs, lastKnownPosition may already hold the destination
                                                    reversePreloadOrigin(fUuid, originId, sourceWorld, dw, dx, dy, dz, prePos);
                                                });
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── Position poll (every tick) ──────────────────────
                        // Runs unconditionally so WARM zones promote HOT as
                        // the player walks toward them, no teleport required.
                        com.hypixel.hytale.math.vector.Transform transform = pRef.getTransform();
                        if (transform != null && transform.getPosition() != null) {
                            final String curWorld = w.getName();
                            final double cx = transform.getPosition().x;
                            final double cy = transform.getPosition().y;
                            final double cz = transform.getPosition().z;

                            // ── Position-jump detection ──────────────────────
                            // adventure.teleporter same-world portals may not update TeleportRecord.
                            // A jump > 16 blocks between poll cycles cannot be normal movement,
                            // so it reliably signals a same-world teleport.
                            // srcPos is captured here before lastKnownPosition is overwritten.
                            double[] prevPos = lastKnownPosition.get(uuid);
                            if (prevPos != null) {
                                double jumpSq = (cx - prevPos[0]) * (cx - prevPos[0])
                                              + (cz - prevPos[2]) * (cz - prevPos[2]);
                                if (jumpSq > 1024.0) { // > 32 blocks
                                    final double srcX = prevPos[0], srcZ = prevPos[2];
                                    final double dstX = cx, dstZ = cz;
                                    final String jWorld = curWorld;
                                    executor.execute(() -> {
                                        // Guard against post-shutdown execution
                                        if (plugin.isShuttingDown()) return;
                                        
                                        // Use spatial-index candidate lookup instead of full-world scan
                                        PortalEntry best = null;
                                        double bestDist = config.getActivationDistance() * 2;
                                        double bestDistSq = bestDist * bestDist;
                                        List<PortalEntry> candidates = getNearbyPlainPortalCandidates(jWorld, dstX, dstZ, bestDist);

                                        for (PortalEntry pe : candidates) {
                                            double dx = pe.getX() - dstX;
                                            double dz = pe.getZ() - dstZ;
                                            double dSq = dx * dx + dz * dz;
                                            if (dSq < bestDistSq) {
                                                bestDistSq = dSq;
                                                best = pe;
                                            }
                                        }
                                        if (best != null) {
                                            final PortalEntry fBest = best;
                                            learnPortalHotspot(jWorld, srcX, srcZ, fBest.getId());
                                        } else {
                                            LOG.fine(() -> "[OptiPortal] Jump detected but no zone"
                                                    + " near (" + (int)dstX + ", " + (int)dstZ
                                                    + ") in " + jWorld + " within "
                                                    + (config.getActivationDistance() * 2) + " blocks");
                                        }
                                    });
                                }
                            }

                            // Save position for origin detection on next teleport.
                            // Routine proximity checks are handled exclusively by the
                            // staggered scheduler — do not duplicate them here.
                            lastKnownPosition.put(uuid, new double[]{cx, cy, cz});
                        }
                    } catch (Exception e) {
                        LOG.warning(() -> "[OptiPortal] poll error: " + e);
                    }
                });
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void onAddPlayerToWorld(AddPlayerToWorldEvent event) {
        // Guard against post-shutdown execution
        if (plugin.isShuttingDown()) return;
        
        World world = event.getWorld();
        if (world == null) return;
        String worldName = world.getName();

        // Seed WorldRegistry
        chunkPreloader.getWorldRegistry().addWorld(world);

        // Cache PlayerRefs
        for (PlayerRef ref : world.getPlayerRefs()) {
            UUID uuid = ref.getUuid();
            if (uuid != null) playerRefs.put(uuid, ref);
        }

        // Trigger staged load if not yet run
        warmZoneManager.triggerStagedLoadOnce();

        // Check if the player used a teleporter — UsedTeleporter component carries destination coords.
        // Holder.getComponent(ComponentType) is the direct API (confirmed from javap).
        com.hypixel.hytale.component.Holder<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> holder = event.getHolder();
        if (holder != null) {
            UsedTeleporter usedTeleporter = holder.getComponent(UsedTeleporter.getComponentType());
            if (usedTeleporter != null) {
                com.hypixel.hytale.math.vector.Vector3d dest = usedTeleporter.getDestinationPosition();
                // Only check proximity if destination position is valid
                if (dest != null && !Double.isNaN(dest.x) && !Double.isNaN(dest.y) && !Double.isNaN(dest.z)) {
                    LOG.fine(() -> "[OptiPortal] UsedTeleporter detected: dest="
                        + dest.x + "," + dest.y + "," + dest.z + " world=" + worldName);
                    checkProximityAndPreload(null, worldName, dest.x, dest.y, dest.z);
                } else {
                    LOG.fine("[OptiPortal] AddPlayerToWorld: UsedTeleporter has invalid destination position");
                }
            } else {
                LOG.fine("[OptiPortal] AddPlayerToWorld: no UsedTeleporter (login or non-teleporter move)");
            }
        }
    }

    /**
     * Check proximity to all portals and trigger preload if within activation distance.
     */
    private void checkProximityAndPreload(UUID playerUuid, String worldName, double px, double py, double pz) {
        // Use the larger of the global activation distance and any per-portal horizontal override
        // so portals with a wider override radius are not missed by the spatial index query.
        PortalSpatialIndex idx = getSpatialIndexForWorld(worldName);
        double queryRadius = (idx != null && idx.getMaxHorizontalActivationDistance() > config.getActivationDistance())
                ? idx.getMaxHorizontalActivationDistance()
                : config.getActivationDistance();
        List<PortalEntry> candidates = getNearbyPlainPortalCandidates(worldName, px, pz, queryRadius);
        
        for (PortalEntry entry : candidates) {
            if (entry.isInstanced()) continue;
            if (entry.getId().contains(":")) continue; // skip death:, respawn:, etc.

            // Per-zone horizontal override; falls back to global config
            double maxDist = (entry.getActivationDistanceHorizontal() != null)
                    ? entry.getActivationDistanceHorizontal()
                    : config.getActivationDistance();
            // Vertical bound with per-zone override support
            double vertBound = (entry.getActivationDistanceVertical() != null)
                    ? entry.getActivationDistanceVertical()
                    : config.getActivationDistanceVertical();
            double dy = Math.abs(py - entry.getY());
            double dx = entry.getX() - px;
            double dz = entry.getZ() - pz;
            double horizDistSq = dx * dx + dz * dz;
            if (dy > vertBound) continue;
            if (horizDistSq > maxDist * maxDist) continue;
            String id = entry.getId();
            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            int radius = resolveRadius(entry);

            if (entry.getStrategy() == WarmStrategy.WARM) {
                // WARM: no cooldown — tier promotion is a cheap map write
                com.optiportal.cache.CacheManager cm = plugin.getCacheManager();
                com.optiportal.model.CacheTier current = cm.getZoneTier(id);
                if (current != com.optiportal.model.CacheTier.HOT) {
                    if (cm.getOwnedChunkCount(id) > 0) {
                        cm.setZoneTier(id, com.optiportal.model.CacheTier.HOT);
                        LOG.fine(() -> "[OptiPortal] Proximity HOT: " + id
                            + " dist=" + String.format("%.1f", Math.sqrt(horizDistSq))
                            + " (was " + current + ")");
                    } else {
                        // Zone not yet loaded — re-trigger; HOT will be set on load completion
                        LOG.fine(() -> "[OptiPortal] Proximity: WARM zone '" + id
                            + "' has no owned chunks — re-triggering warm load");
                        warmZoneManager.loadWarmZone(entry);
                    }
                }
            } else {
                // PREDICTIVE: cooldown guards chunk load
                if (isOnCooldown(null, id)) continue;
                // H3: skip if destination portal world is dead or has no spawn
                World destWorld = chunkPreloader.getWorldRegistry()
                        .resolveWorld(entry.getDestinationWorldUuid(), worldName);
                if (!com.optiportal.preload.WarmZoneManager.isPortalWorldUsable(destWorld)) continue;
                recordCooldown(null, id);
                LOG.fine(() -> "[OptiPortal] Proximity PREDICTIVE: " + id
                    + " dist=" + String.format("%.1f", Math.sqrt(horizDistSq)) + " → load cx=" + cx + " cz=" + cz);
                predictiveLoadWithRam(id, worldName, cx, cz, radius);
            }

            // If we know the linked portal for this one, preload it too
            String linkedId = portalLinkRegistry.getLinkedPortal(id);
            if (linkedId != null) {
                long now = System.currentTimeMillis();
                boolean shouldFire = reversePreloadCooldowns.compute(linkedId, (k, last) ->
                        (last == null || now - last >= 30_000L) ? now : last) == now;
                if (shouldFire) {
                    storage.loadById(linkedId).ifPresent(linked -> {
                        int lcx = ChunkPreloader.toChunkCoord(linked.getX());
                        int lcz = ChunkPreloader.toChunkCoord(linked.getZ());
                        int lradius = resolveRadius(linked);
                        LOG.fine(() -> "[OptiPortal] Linked preload: " + linkedId
                            + " (linked to " + id + ") cx=" + lcx + " cz=" + lcz);
                        predictiveLoadWithRam(linkedId, linked.getWorld(), lcx, lcz, lradius);
                    });
                }
            }
        }

        // Promote destination zones for any learned portal hotspots near the player's position
        checkHotspotPromotion(worldName, px, pz);
    }

    /**
     * When a player arrives at a destination portal, preload the origin portal
     * so that walking back through is seamless.
     *
     * Finds the portal entry closest to the teleport destination coordinates —
     * that's the destination warp. Then preloads it so the return trip is hot.
     */
    protected void reversePreloadOrigin(UUID playerUuid, String originId, String sourceWorld, String destWorld, double dx, double dy, double dz, double[] srcPos) {
        // D1: Guard against race condition where srcPos is null or position not yet captured
        if (srcPos == null) {
            LOG.fine(() -> "[OptiPortal] Reverse preload skipped: no source position available for " + playerUuid);
            return;
        }

        PortalEntry destEntry = null;
        double bestDistSq = Double.MAX_VALUE;

        // Use spatial index for candidate reduction
        double maxDist = config.getActivationDistance() * 2;
        double maxDistSq = maxDist * maxDist;
        double exactMatchDistSq = 1.0 * 1.0;
        
        List<PortalEntry> candidates = getNearbyPlainPortalCandidates(destWorld, dx, dz, maxDist);
        
        for (PortalEntry entry : candidates) {
            // Exact match (within 1 block) — teleporter lands the player precisely on the warp coords
            double entryDx = entry.getX() - dx;
            double entryDz = entry.getZ() - dz;
            double distSq = entryDx * entryDx + entryDz * entryDz;
            if (distSq <= exactMatchDistSq) {
                destEntry = entry;
                bestDistSq = distSq;
                break;
            }
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                destEntry = entry;
            }
        }

        if (destEntry == null) return;
        if (bestDistSq > maxDistSq) return; // sanity bound

        // Learn portal hotspot so future approaches pre-warm this destination.
        // srcPos is captured at the call site before the world thread advances lastKnownPosition
        // to the destination — reading lastKnownPosition here would give the wrong position.
        learnPortalHotspot(sourceWorld, srcPos[0], srcPos[2], destEntry.getId());

        // Record the learned link if origin is known and both IDs are plain portal names
        if (originId != null && !originId.equals(destEntry.getId())
                && !originId.contains(":") && !destEntry.getId().contains(":")) {
            portalLinkRegistry.recordLink(originId, destEntry.getId());
        }

        // Preload the destination portal
        long now = System.currentTimeMillis();
        boolean shouldFire = reversePreloadCooldowns.compute(destEntry.getId(), (k, last) ->
                (last == null || now - last >= 30_000L) ? now : last) == now;
        if (!shouldFire) return;
        int cx = ChunkPreloader.toChunkCoord(destEntry.getX());
        int cz = ChunkPreloader.toChunkCoord(destEntry.getZ());
        int radius = resolveRadius(destEntry);
        final PortalEntry logEntry = destEntry;
        final double logDist = Math.sqrt(bestDistSq);
        LOG.fine(() -> "[OptiPortal] Reverse preload destination: " + logEntry.getId()
            + " dist=" + String.format("%.1f", logDist) + " cx=" + cx + " cz=" + cz);
        predictiveLoadWithRam(destEntry.getId(), destEntry.getWorld(), cx, cz, radius);
    }

    /**
     * Linger: keeps the player's last active zone HOT for 30s after teleport.
     * The existing HOT→WARM decay timer handles cleanup — no extra scheduling needed.
     */
    protected String findNearestPortal(String worldName, double px, double py, double pz) {
        // Use spatial index for candidate reduction
        double maxDist = config.getActivationDistance();
        List<PortalEntry> candidates = getNearbyPlainPortalCandidates(worldName, px, pz, maxDist);
        
        String best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (PortalEntry entry : candidates) {
            double dx = entry.getX() - px;
            double dz = entry.getZ() - pz;
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = entry.getId();
            }
        }
        
        return best;
    }

    protected void lingerOriginZone(UUID playerUuid, String worldName) {
        double[] prePos = lastKnownPosition.get(playerUuid);
        if (prePos == null || worldName == null) return;
        
        // Use spatial index for candidate reduction
        double maxDist = config.getActivationDistance();
        double maxDistSq = maxDist * maxDist;
        List<PortalEntry> candidates = getNearbyPlainPortalCandidates(worldName, prePos[0], prePos[2], maxDist);
        
        String prevZoneId = null;
        double bestDistSq = Double.MAX_VALUE;
        for (PortalEntry entry : candidates) {
            double dx = entry.getX() - prePos[0];
            double dz = entry.getZ() - prePos[2];
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                prevZoneId = entry.getId();
            }
        }
        if (prevZoneId == null || bestDistSq > maxDistSq) return;
        final String lingerZoneId = prevZoneId;
        // Guard: only linger-HOT if the zone's chunks are actually owned/pinned in memory.
        // If owned chunk count is 0, the zone was never loaded — setting HOT would be a false
        // promotion with no keepLoaded backing it.
        if (plugin.getCacheManager().getOwnedChunkCount(lingerZoneId) > 0) {
            plugin.getCacheManager().setZoneTier(lingerZoneId, com.optiportal.model.CacheTier.HOT);
            LOG.fine(() -> "[OptiPortal] Linger HOT: " + lingerZoneId + " (30s decay will clean up)");
        }
    }

    /**
     * Player client is fully ready (spawned, UI loaded).
     * PlayerRef already cached in onAddPlayerToWorld; nothing extra needed here.
     */
    private void onPlayerReady(PlayerReadyEvent event) {
        // Guard against post-shutdown execution
        if (plugin.isShuttingDown()) return;
        
        Player player = event.getPlayer();
        if (player == null) return;

        @SuppressWarnings("removal")
        PlayerRef ref = player.getPlayerRef();
        if (ref == null) return;

        World world = chunkPreloader.getWorldRegistry().getWorldForPlayer(ref);
        if (world == null) return;

        String worldName = world.getName();
        plugin.getLogger().at(java.util.logging.Level.INFO).log(
                "[OptiPortal] PlayerReady in world: " + worldName);

        // Seed WorldRegistry and cache PlayerRef.
        // If the UUID is already in playerRefs, this is a respawn or return from instance
        // rather than a fresh login — mark as pending respawn capture.
        chunkPreloader.getWorldRegistry().addWorld(world);
        if (ref != null) {
            UUID uuid = ref.getUuid();
            if (uuid != null) {
                boolean alreadyOnline = playerRefs.containsKey(uuid);
                playerRefs.put(uuid, ref);
                if (alreadyOnline && (gravestoneIntegration == null || !gravestoneIntegration.hasPendingRespawn(uuid))) {
                    // No gravestone event fired (gravestones disabled or not installed),
                    // but player re-entered the ready state while already online —
                    // treat as death/instance return and capture their spawn position.
                    if (gravestoneIntegration != null) {
                        gravestoneIntegration.markPendingRespawn(uuid);
                    } else {
                        pendingRespawnCapture.add(uuid);
                    }
                    LOG.fine(() -> "[OptiPortal] Respawn detected (no gravestones) for " + uuid);
                }
            }
        }

        // Trigger staged warm load on first player join.
        warmZoneManager.triggerStagedLoadOnce();

        // Pre-load death location and handle respawn position tracking.
        if (ref != null && ref.getUuid() != null) {
            UUID preloadUuid = ref.getUuid();

            // Always preload the death location if one exists.
            deathLocationTracker.preloadDeathLocation(preloadUuid);

            // If the player died/returned from instance, capture their spawn position
            // as the new respawn location. Otherwise preload their known respawn point.
            boolean pendingCapture = (gravestoneIntegration != null && gravestoneIntegration.consumePendingRespawn(preloadUuid))
                    || pendingRespawnCapture.remove(preloadUuid);
            if (pendingCapture) {
                com.hypixel.hytale.server.core.universe.PlayerRef captureRef = playerRefs.get(preloadUuid);
                if (captureRef != null) {
                    com.hypixel.hytale.math.vector.Transform transform = captureRef.getTransform();
                    if (transform != null && transform.getPosition() != null) {
                        double rx = transform.getPosition().x;
                        double ry = transform.getPosition().y;
                        double rz = transform.getPosition().z;
                        respawnTracker.onRespawn(preloadUuid, rx, ry, rz, worldName);
                    }
                }
            } else {
                respawnTracker.preloadRespawn(preloadUuid);
            }
        }

        // Read TeleportRecord from the cached PlayerRef to detect same-world portal teleports.
        // PlayerRef.getComponent() is a direct convenience method — no Holder traversal needed.
        PlayerRef cachedRef = playerRefs.get(ref != null ? ref.getUuid() : null);
        if (cachedRef != null) {
            TeleportRecord record = cachedRef.getComponent(TeleportRecord.getComponentType());
            if (record != null) {
                TeleportRecord.Entry last = record.getLastTeleport();
                if (last != null) {
                    // Only act on teleports within the last 5 seconds to avoid stale login records
                    long ageNanos = System.nanoTime() - last.timestampNanos();
                    if (ageNanos < 5_000_000_000L) {
                        Location dest = last.destination();
                        if (dest != null) {
                            String destWorld = dest.getWorld() != null ? dest.getWorld() : worldName;
                            com.hypixel.hytale.math.vector.Vector3d pos = dest.getPosition();
                            if (pos != null) {
                                LOG.fine(() -> "[OptiPortal] TeleportRecord dest: "
                                    + pos.x + "," + pos.y + "," + pos.z + " world=" + destWorld);
                                checkProximityAndPreload(null, destWorld, pos.x, pos.y, pos.z);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Player is leaving a world (portal travel or world switch).
     * Fires on the world thread with the player's exact departure Transform.
     *
     * We use this to:
     *   1. Capture the player's last known position before the world change.
     *   2. Linger the origin zone HOT immediately (no 1-second poll delay).
     *
     * onPlayerDisconnect handles actual disconnects separately; this only fires
     * for world-to-world transitions where the player stays on the server.
     */
    private void onDrainPlayerFromWorld(DrainPlayerFromWorldEvent event) {
        // Guard against post-shutdown execution
        if (plugin.isShuttingDown()) return;
        
        World world = event.getWorld();
        Transform transform = event.getTransform();
        if (world == null || transform == null) return;

        com.hypixel.hytale.math.vector.Vector3d pos = transform.getPosition();
        if (pos == null) return;

        // Resolve player UUID from the departing entity's UUIDComponent.
        // This runs on the world thread — component access is safe here.
        UUIDComponent uuidComp = event.getHolder()
                .getComponent(UUIDComponent.getComponentType());
        UUID playerUuid = (uuidComp != null) ? uuidComp.getUuid() : null;

        final double px = pos.x, py = pos.y, pz = pos.z;
        final UUID fUuid = playerUuid;

        // Offload to executor — linger/link ops are not world-thread-safe.
        executor.execute(() -> {
            if (fUuid == null) return; // no UUID = non-player entity, skip
            if (plugin.isShuttingDown()) return; // Guard against post-shutdown execution

            // 1. Stamp the pre-portal position so the NEXT teleport poll can
            //    use it for portal-link learning (replaces the stale poll value).
            lastKnownPosition.put(fUuid, new double[]{px, py, pz});

            // 2. Immediately linger the origin zone HOT.
            //    lingerOriginZone() reads lastKnownPosition which we just set.
            lingerOriginZone(fUuid, world.getName());
        });
    }

    /**
     * Player is connecting to the server for the first time this session.
     * Fires before the player is assigned to a world — the ideal moment to
     * pre-warm their spawn-point chunks without blocking the world thread.
     *
     * IEvent<Void>: fires on the main/universe thread (not a world thread).
     * Component access is NOT safe here — use only PlayerRef and World methods.
     *
     * We pre-load the spawn chunk area as a PREDICTIVE load so the world
     * thread does not cold-load those chunks when the player arrives.
     */
    private void onPlayerConnect(PlayerConnectEvent event) {
        // Guard against post-shutdown execution
        if (plugin.isShuttingDown()) return;
        
        World world = event.getWorld();
        if (world == null) return;

        com.hypixel.hytale.server.core.universe.PlayerRef playerRef = event.getPlayerRef();
        UUID playerUuid = (playerRef != null) ? playerRef.getUuid() : null;

        // Resolve spawn point via ISpawnProvider
        ISpawnProvider spawnProvider = world.getWorldConfig().getSpawnProvider();
        if (spawnProvider == null) return;

        // Guard against null UUID - ISpawnProvider.getSpawnPoint is a non-null API contract
        if (playerUuid == null) {
            LOG.fine(() -> "[OptiPortal] onPlayerConnect: null UUID, skipping spawn prewarm");
            return;
        }
        // getSpawnPoint may throw if spawn not configured
        Transform spawnTransform;
        try {
            spawnTransform = spawnProvider.getSpawnPoint(world, playerUuid);
        } catch (Exception e) {
            return;
        }
        if (spawnTransform == null || spawnTransform.getPosition() == null) return;

        com.hypixel.hytale.math.vector.Vector3d pos = spawnTransform.getPosition();
        final String worldName = world.getName();
        final double spawnX = pos.x;
        final double spawnZ = pos.z;

        // Fire predictive load on executor — never block the connect event handler
        executor.execute(() -> {
            if (plugin.isShuttingDown()) return; // Guard against post-shutdown execution
            int cx = com.optiportal.preload.ChunkPreloader.toChunkCoord(spawnX);
            int cz = com.optiportal.preload.ChunkPreloader.toChunkCoord(spawnZ);
            String zoneId = "spawn:" + worldName + ":" + cx + ":" + cz;
            chunkPreloader.predictiveLoad(zoneId, worldName, cx, cz, config.getPredictiveRadius());
            LOG.fine("[OptiPortal] onPlayerConnect: pre-warming spawn at "
                    + worldName + " chunk (" + cx + ", " + cz + ")");
        });
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        // Guard against post-shutdown execution
        if (plugin.isShuttingDown()) return;
        
        UUID uuid = event.getPlayerRef().getUuid();
        if (uuid != null) {
            removePlayerRef(uuid);
        }
    }

    /**
     * Look up a cached PlayerRef by UUID (populated on PlayerReadyEvent).
     * Returns null if the player is not online or not yet ready.
     */
    public com.hypixel.hytale.server.core.universe.PlayerRef getPlayerRef(UUID uuid) {
        return playerRefs.get(uuid);
    }

    /** Cancel scheduled teleport polling and hotspot cleanup tasks. Subclasses that add their own resources should call super.stop(). */
    public void stop() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }
        if (cooldownCleanupTask != null) {
            cooldownCleanupTask.cancel(false);
            cooldownCleanupTask = null;
        }
        if (staggeredProximityTask != null) {
            staggeredProximityTask.cancel(false);
            staggeredProximityTask = null;
        }

        // Clear all interceptor-owned tracking collections to prevent retention
        pendingRespawnCapture.clear();
        reversePreloadCooldowns.clear();
        cooldowns.clear();
        playerRefs.clear();
        lastSeenTeleportNanos.clear();
        lastKnownPosition.clear();
        lastProximityCheckPosition.clear();
        lastProximityCheckMs.clear();
        portalHotspots.clear();
        pendingHotspotCounts.clear();
        portalCache = Collections.emptyList();
        portalCacheByWorld = Collections.emptyMap();
    }

    /**
     * Called when a world is removed. Clears world-scoped hotspot state.
     * @param worldName The name of the removed world
     */
    public void onWorldRemoved(String worldName) {
        // Remove all confirmed hotspots for this world
        portalHotspots.remove(worldName);
        // Remove all pending hotspot keys that start with this world name
        pendingHotspotCounts.keySet().removeIf(key -> key.startsWith(worldName + ":"));
        
        // Remove world from spatial index to prevent stale lookups
        Map<String, PortalSpatialIndex> newSpatialIndex = new java.util.HashMap<>(portalSpatialIndexByWorld);
        newSpatialIndex.remove(worldName);
        portalSpatialIndexByWorld = Collections.unmodifiableMap(newSpatialIndex);
        
        LOG.fine(() -> "[OptiPortal] Cleared hotspot and spatial index state for removed world: " + worldName);
    }

    /**
     * Called when a portal is deleted. Removes related hotspot state.
     * @param portalId The ID of the deleted portal
     */
    public void onPortalDeleted(String portalId) {
        rebuildPortalCachesFrom(portalCache.stream()
                .filter(entry -> !entry.getId().equals(portalId))
                .toList());

        // Remove any pending hotspot keys that reference this portal as destination
        pendingHotspotCounts.keySet().removeIf(key -> key.endsWith(":" + portalId));

        // Remove confirmed hotspot entries that target this deleted portal
        portalHotspots.forEach((world, list) ->
                list.removeIf(h -> h.destZoneId.equals(portalId)));

        // Drop empty world buckets so they do not accumulate after portal deletion.
        portalHotspots.entrySet().removeIf(e -> e.getValue().isEmpty());

        // Prune reversePreloadCooldowns for the deleted portal
        reversePreloadCooldowns.remove(portalId);

        LOG.fine(() -> "[OptiPortal] Cleared pending and confirmed hotspot state for deleted portal: " + portalId);
    }

    /**
     * Prunes stale reversePreloadCooldowns entries based on age.
     * Called periodically to prevent indefinite growth from one-time portal IDs.
     */
    public void pruneStaleCooldowns() {
        long now = System.currentTimeMillis();
        long cutoff = now - 30_000L; // 30-second TTL for reverse preload cooldowns
        
        reversePreloadCooldowns.entrySet().removeIf(entry -> {
            Long last = entry.getValue();
            return last != null && last < cutoff;
        });
        
        LOG.fine(() -> "[OptiPortal] Pruned stale reversePreloadCooldowns: "
                + "remaining=" + reversePreloadCooldowns.size());
    }

    /**
     * Removes pending and confirmed hotspot entries that have not been observed
     * within their respective TTL windows.
     *
     * Pending entries expire after {@value #PENDING_HOTSPOT_TTL_MS} ms.
     * Confirmed entries expire after {@value #CONFIRMED_HOTSPOT_TTL_MS} ms.
     *
     * Both values use {@code lastSeenMs} — a real wall-clock timestamp updated
     * on every observation — not observation counts.
     *
     * Scheduled automatically; also safe to call manually for testing.
     */
    public void cleanupStaleHotspots() {
        long now             = System.currentTimeMillis();
        long pendingCutoff   = now - PENDING_HOTSPOT_TTL_MS;
        long confirmedCutoff = now - CONFIRMED_HOTSPOT_TTL_MS;

        pendingHotspotCounts.entrySet().removeIf(e -> e.getValue().lastSeenMs < pendingCutoff);

        portalHotspots.forEach((world, list) ->
                list.removeIf(h -> h.lastSeenMs < confirmedCutoff));

        // Drop empty world buckets so they do not accumulate for removed worlds.
        portalHotspots.entrySet().removeIf(e -> e.getValue().isEmpty());

        LOG.fine(() -> "[OptiPortal] Hotspot TTL cleanup complete:"
                + " pending=" + pendingHotspotCounts.size()
                + ", confirmed=" + portalHotspots.values().stream().mapToInt(java.util.List::size).sum());
    }

    public void removePlayerRef(UUID uuid) {
        playerRefs.remove(uuid);
        cooldowns.remove(uuid);
        lastKnownPosition.remove(uuid);
        lastProximityCheckPosition.remove(uuid);
        lastProximityCheckMs.remove(uuid);
        lastSeenTeleportNanos.remove(uuid);
        pendingRespawnCapture.remove(uuid);
    }

    /**
     * Get the next batch of players for proximity checks using round-robin fairness.
     *
     * Package-private for focused regression tests.
     *
     * @return List of player UUIDs to check in this batch
     */
    List<UUID> getNextProximityBatch() {
        // Snapshot player refs to avoid concurrent modification
        List<UUID> allPlayers = new ArrayList<>(playerRefs.keySet());
        // Sort for a stable iteration order — ConcurrentHashMap.keySet() ordering
        // is not guaranteed, so without this the cursor rotation does not produce a
        // consistent round-robin cycle when the map changes between ticks.
        Collections.sort(allPlayers);
        int total = allPlayers.size();
        if (total == 0) {
            return Collections.emptyList();
        }

        // Advance cursor by one batch size for round-robin fairness
        int start = Math.floorMod(proximityBatchCursor.getAndAdd(POSITION_UPDATE_BATCH_SIZE), total);

        // Collect up to batch size
        List<UUID> batch = new ArrayList<>(POSITION_UPDATE_BATCH_SIZE);
        for (int i = 0; i < total && batch.size() < POSITION_UPDATE_BATCH_SIZE; i++) {
            batch.add(allPlayers.get((start + i) % total));
        }

        return batch;
    }

    /**
     * Process a single player's proximity check using the live-safe access patterns.
     * Package-private for focused regression tests.
     *
     * @param playerUuid Player UUID to check
     */
    protected void processPlayerProximity(UUID playerUuid) {
        PlayerRef playerRef = playerRefs.get(playerUuid);
        if (playerRef == null) {
            return; // Player ref missing — skip
        }

        try {
            World world = chunkPreloader.getWorldRegistry().getWorldForPlayer(playerRef);
            if (world == null) {
                return; // Missing world — skip
            }

            // Mirror the thread-safety model of pollTeleportRecords: read getTransform()
            // only on the world execution path, never from the scheduler thread.
            world.execute(() -> {
                com.hypixel.hytale.math.vector.Transform transform = playerRef.getTransform();
                final String curWorld = world.getName();
                final double cx = transform.getPosition().x;
                final double cy = transform.getPosition().y;
                final double cz = transform.getPosition().z;

                // Use existing skip logic based on last scan time and movement thresholds
                if (!shouldRunProximityCheck(playerUuid, cx, cy, cz)) return;

                // Submit to executor for actual proximity check
                executor.execute(() -> {
                    if (plugin.isShuttingDown()) return;
                    checkProximityAndPreload(playerUuid, curWorld, cx, cy, cz);
                });
            });
        } catch (Exception e) {
            // Log but don't fail the batch
            LOG.warning(() -> "[OptiPortal] Error processing player proximity for " + playerUuid + ": " + e.getMessage());
        }
    }

    /**
     * Run a single batch of staggered proximity checks.
     * Package-private for focused regression tests.
     */
    void runStaggeredProximityBatch() {
        try {
            List<UUID> batch = getNextProximityBatch();
            if (batch.isEmpty()) {
                return;
            }

            for (UUID playerId : batch) {
                processPlayerProximity(playerId);
            }
        } catch (Exception e) {
            LOG.warning(() -> "[OptiPortal] Error in staggered proximity batch: " + e.getMessage());
        }
    }

    /**
     * Skip proximity work when the player has barely moved since the last successful check.
     * This reduces always-on polling overhead while still reacting quickly to real movement.
     */
    private boolean shouldRunProximityCheck(UUID playerUuid, double x, double y, double z) {
        if (playerUuid == null) return true;

        long now = System.currentTimeMillis();
        double[] current = new double[]{x, y, z};
        double[] previous = lastProximityCheckPosition.get(playerUuid);
        Long lastMs = lastProximityCheckMs.get(playerUuid);
        if (previous == null || lastMs == null) {
            lastProximityCheckPosition.put(playerUuid, current);
            lastProximityCheckMs.put(playerUuid, now);
            return true;
        }

        double dx = x - previous[0];
        double dy = y - previous[1];
        double dz = z - previous[2];
        double distSq = dx * dx + dy * dy + dz * dz;

        // Large moves should trigger immediately, even if the last scan was recent.
        if (distSq >= 16.0) { // about 4 blocks total movement
            lastProximityCheckPosition.put(playerUuid, current);
            lastProximityCheckMs.put(playerUuid, now);
            return true;
        }

        // For smaller movement, require both some movement and a short cadence gap.
        if (distSq < 1.0) {
            return false;
        }
        if (now - lastMs < 4_000L) {
            return false;
        }

        lastProximityCheckPosition.put(playerUuid, current);
        lastProximityCheckMs.put(playerUuid, now);
        return true;
    }

    /**
     * Returns true if the zone has been loaded (tier is not UNVISITED).
     * True positional HOT detection requires TransformComponent API access;
     * for now we simply report whether the zone is loaded (WARM) vs not (UNVISITED).
     * The worldName/cx/cz/chunkRadius params are reserved for future positional check.
     */
    public boolean isPlayerNearChunk(String worldName, int cx, int cz, int chunkRadius) {
        // HOT = at least one known player is online (we cache refs from PlayerReadyEvent)
        return !playerRefs.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Strip Hytale's i18n key prefix from zone names.
     * e.g. "server.map.region.Berkan" → "Berkan"
     */
    private String normalizeZoneName(String zoneName) {
        if (zoneName.startsWith("server.map.region.")) {
            return zoneName.substring("server.map.region.".length());
        }
        if (zoneName.startsWith("server.map.zone.")) {
            return zoneName.substring("server.map.zone.".length());
        }
        return zoneName;
    }

    private boolean isOnCooldown(UUID playerId, String zoneId) {
        if (playerId == null) return false;
        Long last = cooldowns.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).get(zoneId);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < (config.getActivationCooldownSeconds() * 1000L);
    }

    private void recordCooldown(UUID playerId, String zoneId) {
        if (playerId == null) return;
        cooldowns.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                 .put(zoneId, System.currentTimeMillis());
    }

    private int resolveRadius(PortalEntry entry) {
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getPredictiveRadius();
    }

    // -------------------------------------------------------------------------
    // RAM measurement
    // -------------------------------------------------------------------------

    /**
     * Hook called when a predictive load is about to be triggered from DiscoverZoneEvent.
     * AsyncTeleportInterceptor overrides this to add velocity-aware radius adjustment.
     *
     * @param zoneId    Zone ID
     * @param worldName World name
     * @param cx        Chunk X
     * @param cz        Chunk Z
     * @param radius    Resolved base radius
     * @param playerRef The player whose zone discovery triggered this (may be null)
     */
    protected void triggerPredictiveLoad(String zoneId, String worldName,
            int cx, int cz, int radius,
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef) {
        predictiveLoadWithRam(zoneId, worldName, cx, cz, radius);
    }

    /**
     * Fire a predictive load and record RAM delta + estimate on completion.
     */
    protected void predictiveLoadWithRam(String zoneId, String worldName, int cx, int cz, int radius) {
        int chunkCount = (2 * radius + 1) * (2 * radius + 1);

        // bytesPerChunk already includes the 1.5x overhead factor — do not multiply again.
        double estimatedMB = (chunkCount * (double) config.getBytesPerChunk())
                             / (1024.0 * 1024.0);

        chunkPreloader.predictiveLoad(zoneId, worldName, cx, cz, radius)
                // D4: thenRunAsync dispatches storage I/O to the plugin executor instead of
                //     running on whichever thread completed the chunk future (may be world thread).
                .thenRunAsync(() -> {
                    if (plugin.isShuttingDown()) return; // Guard against post-shutdown execution
                    var opt = storage.loadById(zoneId);
                    if (opt.isPresent()) {
                        com.optiportal.model.PortalEntry e = opt.get();
                        e.setRamEstimatedMB(estimatedMB);
                        e.setLastActive(java.time.Instant.now());
                        chunkPreloader.queueEntrySave(e);
                    }
                }, executor)
                .exceptionally(ex -> {
                    if (!(ex instanceof com.optiportal.preload.ChunkLoadAbortedException)
                            && !(ex.getCause() instanceof com.optiportal.preload.ChunkLoadAbortedException)) {
                        LOG.warning("[OptiPortal] predictiveLoadWithRam error for " + zoneId + ": " + ex.getMessage());
                    }
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // Portal hotspot helpers
    // -------------------------------------------------------------------------

    /**
     * Record a source-portal position that links to a destination zone.
     * Hotspots are tracked by chunk bucket and require multiple observations before
     * confirmation to filter one-off teleports and noisy position jumps.
     */
    protected void learnPortalHotspot(String sourceWorld, double sx, double sz, String destZoneId) {
        int chunkX = ChunkPreloader.toChunkCoord(sx);
        int chunkZ = ChunkPreloader.toChunkCoord(sz);

        // Skip if an equivalent confirmed hotspot already exists
        java.util.concurrent.CopyOnWriteArrayList<PortalHotspot> list =
                portalHotspots.get(sourceWorld);
        if (list != null) {
            for (PortalHotspot h : list) {
                if (!h.destZoneId.equals(destZoneId)) continue;
                if (ChunkPreloader.toChunkCoord(h.x) == chunkX
                        && ChunkPreloader.toChunkCoord(h.z) == chunkZ) {
                    // Refresh staleness timer on re-observation of a confirmed hotspot.
                    h.lastSeenMs = System.currentTimeMillis();
                    LOG.fine(() -> "[OptiPortal] Hotspot re-observed: chunk (" + chunkX + ", " + chunkZ
                            + ") in " + sourceWorld + " → " + destZoneId);
                    return;
                }
            }
        }

        // Require HOTSPOT_CONFIDENCE_THRESHOLD observations before confirming.
        // Prevents one-off teleports (/tp home, admin commands) from creating false hotspots.
        String key = sourceWorld + ":" + chunkX + ":" + chunkZ + ":" + destZoneId;
        long now = System.currentTimeMillis();
        PendingHotspot pending = pendingHotspotCounts.compute(key, (k, existing) -> {
            if (existing == null) return new PendingHotspot(now);
            existing.count++;
            existing.lastSeenMs = now;
            return existing;
        });
        int count = pending.count;
        if (count < HOTSPOT_CONFIDENCE_THRESHOLD) {
            LOG.fine(() -> "[OptiPortal] Hotspot pending: " + destZoneId
                    + " chunk (" + chunkX + ", " + chunkZ + ") in " + sourceWorld
                    + " (" + count + "/" + HOTSPOT_CONFIDENCE_THRESHOLD + ")");
            return;
        }

        // Confirmed — graduate to active hotspot
        pendingHotspotCounts.remove(key);
        portalHotspots.computeIfAbsent(sourceWorld,
                        k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new PortalHotspot(sx, sz, destZoneId, System.currentTimeMillis()));
        LOG.info(() -> "[OptiPortal] Confirmed portal hotspot: chunk (" + chunkX + ", " + chunkZ
                + ") in " + sourceWorld + " → " + destZoneId);
    }

    /**
     * When a player is near a known portal hotspot, promote the destination zone
     * from WARM → HOT so chunks are not evicted before the player arrives.
     * Called from checkProximityAndPreload.
     */
    protected void checkHotspotPromotion(String worldName, double px, double pz) {
        java.util.concurrent.CopyOnWriteArrayList<PortalHotspot> list = portalHotspots.get(worldName);
        if (list == null || list.isEmpty()) return;
        double maxDistSq = config.getActivationDistance() * config.getActivationDistance();
        com.optiportal.cache.CacheManager cm = plugin.getCacheManager();
        for (PortalHotspot h : list) {
            double ddx = h.x - px, ddz = h.z - pz;
            if (ddx * ddx + ddz * ddz > maxDistSq) continue;
            com.optiportal.model.CacheTier tier = cm.getZoneTier(h.destZoneId);
            if (tier == com.optiportal.model.CacheTier.WARM) {
                cm.setZoneTier(h.destZoneId, com.optiportal.model.CacheTier.HOT);
                LOG.fine(() -> "[OptiPortal] Hotspot approach: promoted " + h.destZoneId + " → HOT");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /** Lightweight record of a learned source-portal position → destination zone mapping. */
    private static final class PendingHotspot {
        int  count;
        long lastSeenMs;

        PendingHotspot(long lastSeenMs) {
            this.count      = 1;
            this.lastSeenMs = lastSeenMs;
        }
    }

    private static class PortalHotspot {
        final double x, z;
        final String destZoneId;
        volatile long lastSeenMs;

        PortalHotspot(double x, double z, String destZoneId, long lastSeenMs) {
            this.x          = x;
            this.z          = z;
            this.destZoneId = destZoneId;
            this.lastSeenMs = lastSeenMs;
        }
    }

}
