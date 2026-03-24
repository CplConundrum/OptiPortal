package com.optiportal.teleport;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ConcurrentHashMap<UUID, PlayerRef> playerRefs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;
    // Track last-seen TeleportRecord timestamp per player to avoid re-triggering
    private final ConcurrentHashMap<UUID, Long> lastSeenTeleportNanos = new ConcurrentHashMap<>();
    /** Tracks player's last known position before teleport for origin portal detection */
    private final ConcurrentHashMap<UUID, double[]> lastKnownPosition = new ConcurrentHashMap<>();

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
    private final ConcurrentHashMap<String, Integer> pendingHotspotCounts = new ConcurrentHashMap<>();

    private static final int HOTSPOT_CONFIDENCE_THRESHOLD = 3;


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
        // Poll TeleportRecord every second to detect walk-through portal teleports.
        // No portal-use event exists in Hytale for zone-trigger portals.
        int pollInterval = config.getPollIntervalSeconds();
        executor.scheduleWithFixedDelay(this::pollTeleportRecords, pollInterval, pollInterval, TimeUnit.SECONDS);
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
     * No-op on the base class; overridden by AsyncTeleportInterceptor.
     */
    public void refreshPortalCache() {}

    /**
     * Returns all portal entries for proximity checks.
     * Subclasses override this to return an in-memory cache instead of hitting storage.
     */
    protected List<PortalEntry> getAllPortalEntries() {
        return storage.loadAll();
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
                    plugin.getCacheManager().setZoneTier(normalizedZone, com.optiportal.model.CacheTier.HOT);
                    LOG.fine(() -> "[OptiPortal] Zone discovery HOT: " + normalizedZone
                            + " (was " + current + ")");
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
                        plugin.getCacheManager().setZoneTier(zoneName, com.optiportal.model.CacheTier.HOT);
                        LOG.fine(() -> "[OptiPortal] Zone discovery HOT (raw): " + zoneName
                                + " (was " + current + ")");
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
                        // ── Teleport detection ──────────────────────────────
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
                                                    LOG.fine(() -> "[OptiPortal] Poll teleport → "
                                                        + dx + "," + dy + "," + dz + " world=" + dw);
                                                    lingerOriginZone(fUuid);
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
                                        PortalEntry best = null;
                                        double bestD = config.getActivationDistance() * 2;
                                        for (PortalEntry pe : getAllPortalEntries()) {
                                            if (pe.isInstanced() || pe.getId().contains(":")) continue;
                                            if (!pe.getWorld().equals(jWorld)) continue;
                                            double d = Math.sqrt(Math.pow(pe.getX() - dstX, 2)
                                                               + Math.pow(pe.getZ() - dstZ, 2));
                                            if (d < bestD) { bestD = d; best = pe; }
                                        }
                                        if (best != null) {
                                            final PortalEntry fBest = best;
                                            learnPortalHotspot(jWorld, srcX, srcZ, fBest.getId());
                                            LOG.info(() -> "[OptiPortal] Jump hotspot learned: ("
                                                    + (int) srcX + ", " + (int) srcZ + ") → "
                                                    + fBest.getId() + " in " + jWorld);
                                        } else {
                                            LOG.fine(() -> "[OptiPortal] Jump detected but no zone"
                                                    + " near (" + (int)dstX + ", " + (int)dstZ
                                                    + ") in " + jWorld + " within "
                                                    + (config.getActivationDistance() * 2) + " blocks");
                                        }
                                    });
                                }
                            }

                            // Save position for origin detection on next teleport
                            lastKnownPosition.put(uuid, new double[]{cx, cy, cz});
                            final UUID fUuid2 = uuid;
                            executor.execute(() -> checkProximityAndPreload(fUuid2, curWorld, cx, cy, cz));
                        }
                    } catch (Exception e) {
                        LOG.warning("[OptiPortal] poll error: " + e);
                    }
                });
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void onAddPlayerToWorld(AddPlayerToWorldEvent event) {
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
        for (com.optiportal.model.PortalEntry entry : getAllPortalEntries()) {
            if (entry.isInstanced()) continue;
            if (!entry.getWorld().equals(worldName)) continue;
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
            double toX = entry.getX() - px;
            double toZ = entry.getZ() - pz;
            double horizDist = Math.sqrt(toX * toX + toZ * toZ);
            if (dy > vertBound) continue;
            if (horizDist > maxDist) continue;

            String id = entry.getId();
            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            int radius = resolveRadius(entry);

            if (entry.getStrategy() == WarmStrategy.WARM) {
                // WARM: no cooldown — tier promotion is a cheap map write
                com.optiportal.cache.CacheManager cm = plugin.getCacheManager();
                com.optiportal.model.CacheTier current = cm.getZoneTier(id);
                if (current != com.optiportal.model.CacheTier.HOT) {
                    cm.setZoneTier(id, com.optiportal.model.CacheTier.HOT);
                    LOG.fine("[OptiPortal] Proximity HOT: " + id
                        + " dist=" + String.format("%.1f", horizDist)
                        + " (was " + current + ")");
                }
            } else {
                // PREDICTIVE: cooldown guards chunk load
                if (isOnCooldown(null, id)) continue;
                // H3: skip if destination portal world is dead or has no spawn
                World destWorld = chunkPreloader.getWorldRegistry()
                        .resolveWorld(entry.getDestinationWorldUuid(), worldName);
                if (!com.optiportal.preload.WarmZoneManager.isPortalWorldUsable(destWorld)) continue;
                recordCooldown(null, id);
                LOG.fine("[OptiPortal] Proximity PREDICTIVE: " + id
                    + " dist=" + String.format("%.1f", horizDist) + " → load cx=" + cx + " cz=" + cz);
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
                        LOG.fine("[OptiPortal] Linked preload: " + linkedId
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
        PortalEntry destEntry = null;
        double bestDist = Double.MAX_VALUE;

        for (PortalEntry entry : getAllPortalEntries()) {
            if (entry.isInstanced()) continue;
            if (!entry.getWorld().equals(destWorld)) continue;
            if (entry.getId().contains(":")) continue;
            double dist = Math.sqrt(Math.pow(entry.getX() - dx, 2) + Math.pow(entry.getZ() - dz, 2));

            // Exact match (within 1 block) — teleporter lands the player precisely on the warp coords
            if (dist <= 1.0) {
                destEntry = entry;
                bestDist = dist;
                break;
            }
            if (dist < bestDist) {
                bestDist = dist;
                destEntry = entry;
            }
        }

        if (destEntry == null) return;
        if (bestDist > config.getActivationDistance() * 2) return; // sanity bound

        // Learn portal hotspot so future approaches pre-warm this destination.
        // srcPos is captured at the call site before the world thread advances lastKnownPosition
        // to the destination — reading lastKnownPosition here would give the wrong position.
        if (srcPos != null) {
            learnPortalHotspot(sourceWorld, srcPos[0], srcPos[2], destEntry.getId());
        }

        // Record the learned link if origin is known and both IDs are plain portal names
        if (originId != null && !originId.equals(destEntry.getId())
                && !originId.contains(":") && !destEntry.getId().contains(":")) {
            portalLinkRegistry.recordLink(originId, destEntry.getId());
        }

        // Preload the destination portal
        long lastReverseLoad = reversePreloadCooldowns.getOrDefault(destEntry.getId(), 0L);
        if (System.currentTimeMillis() - lastReverseLoad < 30_000L) return;
        reversePreloadCooldowns.put(destEntry.getId(), System.currentTimeMillis());
        int cx = ChunkPreloader.toChunkCoord(destEntry.getX());
        int cz = ChunkPreloader.toChunkCoord(destEntry.getZ());
        int radius = resolveRadius(destEntry);
        final PortalEntry logEntry = destEntry;
        final double logDist = bestDist;
        LOG.fine(() -> "[OptiPortal] Reverse preload destination: " + logEntry.getId()
            + " dist=" + String.format("%.1f", logDist) + " cx=" + cx + " cz=" + cz);
        predictiveLoadWithRam(destEntry.getId(), destEntry.getWorld(), cx, cz, radius);
    }

    /**
     * Linger: keeps the player's last active zone HOT for 30s after teleport.
     * The existing HOT→WARM decay timer handles cleanup — no extra scheduling needed.
     */
    protected String findNearestPortal(String worldName, double px, double py, double pz) {
        String best = null;
        double bestDist = config.getActivationDistance();
        for (PortalEntry entry : getAllPortalEntries()) {
            if (entry.isInstanced()) continue;
            if (!entry.getWorld().equals(worldName)) continue;
            if (entry.getId().contains(":")) continue;
            double dist = Math.sqrt(Math.pow(entry.getX() - px, 2) + Math.pow(entry.getZ() - pz, 2));
            if (dist <= bestDist) {
                bestDist = dist;
                best = entry.getId();
            }
        }
        return best;
    }

    protected void lingerOriginZone(UUID playerUuid) {
        double[] prePos = lastKnownPosition.get(playerUuid);
        if (prePos == null) return;
        // Find nearest portal to pre-teleport position across all worlds
        String prevZoneId = null;
        double bestDist = Double.MAX_VALUE;
        for (PortalEntry entry : getAllPortalEntries()) {
            if (entry.isInstanced()) continue;
            if (entry.getId().contains(":")) continue;
            double dist = Math.sqrt(Math.pow(entry.getX() - prePos[0], 2) + Math.pow(entry.getZ() - prePos[2], 2));
            if (dist < bestDist) {
                bestDist = dist;
                prevZoneId = entry.getId();
            }
        }
        if (prevZoneId == null || bestDist > config.getActivationDistance()) return;
        final String lingerZoneId = prevZoneId;
        plugin.getCacheManager().setZoneTier(lingerZoneId, com.optiportal.model.CacheTier.HOT);
        LOG.fine(() -> "[OptiPortal] Linger HOT: " + lingerZoneId + " (30s decay will clean up)");
    }

    /**
     * Player client is fully ready (spawned, UI loaded).
     * PlayerRef already cached in onAddPlayerToWorld; nothing extra needed here.
     */
    private void onPlayerReady(PlayerReadyEvent event) {
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

            // 1. Stamp the pre-portal position so the NEXT teleport poll can
            //    use it for portal-link learning (replaces the stale poll value).
            lastKnownPosition.put(fUuid, new double[]{px, py, pz});

            // 2. Immediately linger the origin zone HOT.
            //    lingerOriginZone() reads lastKnownPosition which we just set.
            lingerOriginZone(fUuid);
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
        World world = event.getWorld();
        if (world == null) return;

        com.hypixel.hytale.server.core.universe.PlayerRef playerRef = event.getPlayerRef();
        UUID playerUuid = (playerRef != null) ? playerRef.getUuid() : null;

        // Resolve spawn point via ISpawnProvider
        ISpawnProvider spawnProvider = world.getWorldConfig().getSpawnProvider();
        if (spawnProvider == null) return;

        Transform spawnTransform;
        try {
            spawnTransform = spawnProvider.getSpawnPoint(world, playerUuid);
        } catch (Exception e) {
            // getSpawnPoint may throw if playerUuid is null or spawn not configured
            return;
        }
        if (spawnTransform == null || spawnTransform.getPosition() == null) return;

        com.hypixel.hytale.math.vector.Vector3d pos = spawnTransform.getPosition();
        final String worldName = world.getName();
        final double spawnX = pos.x;
        final double spawnZ = pos.z;

        // Fire predictive load on executor — never block the connect event handler
        executor.execute(() -> {
            int cx = com.optiportal.preload.ChunkPreloader.toChunkCoord(spawnX);
            int cz = com.optiportal.preload.ChunkPreloader.toChunkCoord(spawnZ);
            String zoneId = "spawn:" + worldName + ":" + cx + ":" + cz;
            chunkPreloader.predictiveLoad(zoneId, worldName, cx, cz, config.getPredictiveRadius());
            LOG.fine("[OptiPortal] onPlayerConnect: pre-warming spawn at "
                    + worldName + " chunk (" + cx + ", " + cz + ")");
        });
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID uuid = event.getPlayerRef().getUuid();
        if (uuid != null) {
            removePlayerRef(uuid);
        }
    }

    /**
     * Remove a player's cached ref on disconnect.
     */
    /**
     * Look up a cached PlayerRef by UUID (populated on PlayerReadyEvent).
     * Returns null if the player is not online or not yet ready.
     */
    public com.hypixel.hytale.server.core.universe.PlayerRef getPlayerRef(UUID uuid) {
        return playerRefs.get(uuid);
    }

    public void removePlayerRef(UUID uuid) {
        playerRefs.remove(uuid);
        cooldowns.remove(uuid);
        lastKnownPosition.remove(uuid);
        lastSeenTeleportNanos.remove(uuid);
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

        // Use configurable bytesPerChunk instead of hardcoded 65536.
        // Keep the 1.5x overhead multiplier for entity/metadata overhead estimate.
        // config.getBytesPerChunk() default = 262144 (256 KB).
        double estimatedMB = (chunkCount * (double) config.getBytesPerChunk() * 1.5)
                             / (1024.0 * 1024.0);

        chunkPreloader.predictiveLoad(zoneId, worldName, cx, cz, radius)
                .thenRun(() -> {
                    var opt = storage.loadById(zoneId);
                    if (opt.isPresent()) {
                        com.optiportal.model.PortalEntry e = opt.get();
                        e.setRamEstimatedMB(estimatedMB);
                        e.setLastActive(java.time.Instant.now());
                        // updateEntryStats sets ramMarginalMB from config.getBytesPerChunk() too
                        chunkPreloader.updateEntryStats(e, chunkCount);
                        storage.save(e);
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Portal hotspot helpers
    // -------------------------------------------------------------------------

    /**
     * Record a source-portal position that links to a destination zone.
     * Confirmed on first observation — tier promotion (WARM→HOT) is cheap and
     * reversible, so no confidence threshold is needed here (unlike PortalLinkRegistry
     * which gates persistent chunk loads behind 5 observations).
     * Deduplicates by chunk bucket so position jitter across multiple runs doesn't
     * add redundant entries.
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
                        && ChunkPreloader.toChunkCoord(h.z) == chunkZ) return;
            }
        }

        // Require HOTSPOT_CONFIDENCE_THRESHOLD observations before confirming.
        // Prevents one-off teleports (/tp home, admin commands) from creating false hotspots.
        String key = sourceWorld + ":" + chunkX + ":" + chunkZ + ":" + destZoneId;
        int count = pendingHotspotCounts.merge(key, 1, Integer::sum);
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
                .add(new PortalHotspot(sx, sz, destZoneId));
        LOG.info(() -> "[OptiPortal] Confirmed portal hotspot: chunk (" + chunkX + ", " + chunkZ
                + ") in " + sourceWorld + " → " + destZoneId);
    }

    /**
     * When a player is near a known portal hotspot, promote the destination zone
     * from WARM → HOT so chunks are not evicted before the player arrives.
     * Called from checkProximityAndPreload and AsyncTeleportInterceptor's async variant.
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
    private static class PortalHotspot {
        final double x, z;
        final String destZoneId;

        PortalHotspot(double x, double z, String destZoneId) {
            this.x = x;
            this.z = z;
            this.destZoneId = destZoneId;
        }
    }

}
