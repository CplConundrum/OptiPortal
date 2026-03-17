package com.optiportal.teleport;

import java.util.logging.Logger;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DiscoverZoneEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.builtin.adventure.teleporter.interaction.server.UsedTeleporter;
import com.hypixel.hytale.math.vector.Location;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportRecord;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import com.optiportal.OptiPortal;
import com.optiportal.config.PluginConfig;
import com.optiportal.player.DeathLocationTracker;
import com.optiportal.player.RespawnTracker;
import com.optiportal.integrations.GravestoneIntegration;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.preload.ChunkPreloader;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.storage.StorageBackend;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final ConcurrentHashMap<String, Long> reversePreloadCooldowns = new ConcurrentHashMap<>();

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
        executor.scheduleAtFixedRate(this::pollTeleportRecords, pollInterval, pollInterval, TimeUnit.SECONDS);
    }

    // Protected getters for subclass access
    protected PluginConfig getConfig() {
        return config;
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
        // Always log raw name so we can see what Hytale sends
        System.out.println("[OptiPortal] DiscoverZoneEvent raw='" + zoneName + "'");
        if (zoneName == null || zoneName.isBlank()) return;

        String normalizedZone = normalizeZoneName(zoneName);
        System.out.println("[OptiPortal] DiscoverZoneEvent normalized='" + normalizedZone + "'");

        boolean matched = false;

        // Try normalized name first
        var entry1 = storage.loadById(normalizedZone);
        if (entry1.isPresent()) {
            var entry = entry1.get();
            System.out.println("[OptiPortal] DiscoverZoneEvent matched storage id='" + normalizedZone
                + "' strategy=" + entry.getStrategy());
            if (!entry.isInstanced() && entry.getStrategy() == WarmStrategy.PREDICTIVE) {
                if (!isOnCooldown(null, normalizedZone)) {
                    recordCooldown(null, normalizedZone);
                    int cx = ChunkPreloader.toChunkCoord(entry.getX());
                    int cz = ChunkPreloader.toChunkCoord(entry.getZ());
                    int radius = resolveRadius(entry);
                    System.out.println("[OptiPortal] Zone discovery trigger: " + normalizedZone
                            + " → predictive load cx=" + cx + " cz=" + cz + " r=" + radius);
                    predictiveLoadWithRam(normalizedZone, entry.getWorld(), cx, cz, radius);
                    matched = true;
                }
            }
        }

        // Also try original name if different
        if (!matched && !normalizedZone.equals(zoneName)) {
            var entry2 = storage.loadById(zoneName);
            if (entry2.isPresent()) {
                var entry = entry2.get();
                System.out.println("[OptiPortal] DiscoverZoneEvent matched storage id='" + zoneName
                    + "' strategy=" + entry.getStrategy());
                if (!entry.isInstanced() && entry.getStrategy() == WarmStrategy.PREDICTIVE) {
                    if (!isOnCooldown(null, zoneName)) {
                        recordCooldown(null, zoneName);
                        int cx = ChunkPreloader.toChunkCoord(entry.getX());
                        int cz = ChunkPreloader.toChunkCoord(entry.getZ());
                        System.out.println("[OptiPortal] Zone discovery trigger (raw): " + zoneName
                                + " → predictive load cx=" + cx + " cz=" + cz);
                        predictiveLoadWithRam(zoneName, entry.getWorld(), cx, cz, resolveRadius(entry));
                        matched = true;
                    }
                }
            }
        }

        if (!matched) {
            System.out.println("[OptiPortal] DiscoverZoneEvent no match for raw='" + zoneName
                + "' normalized='" + normalizedZone + "' — check portal IDs in storage");
        }
    }

    /**
     * Player arrived in a world. If any WARM zones exist for this world
     * that aren't already loaded, trigger warm load for them.
     * Also fires on world-switch teleports.
     */
        /**
     * Polls TeleportRecord for all online players every second.
     * Detects walk-through portal teleports that fire no player-facing event.
     */
    private void pollTeleportRecords() {
        // getComponent must be called on the world tick thread.
        // Submit the read to the world via World.execute(Runnable).
        for (java.util.Map.Entry<UUID, PlayerRef> entry : playerRefs.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerRef pRef = entry.getValue();
            try {
                World world = chunkPreloader.getWorldRegistry().getWorldForPlayer(pRef);
                if (world == null) continue;
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
                                            com.hypixel.hytale.math.vector.Vector3d pos = dest.getPosition();
                                            if (pos != null && destWorld != null) {
                                                final String dw = destWorld;
                                                final double dx = pos.x, dy = pos.y, dz = pos.z;
                                                final UUID fUuid = uuid;
                                                // Snapshot pre-teleport position for origin portal lookup
                                                final double[] prePos = lastKnownPosition.get(fUuid);
                                                executor.execute(() -> {
                                                    System.out.println("[OptiPortal] Poll teleport → "
                                                        + dx + "," + dy + "," + dz + " world=" + dw);
                                                    lingerOriginZone(fUuid);
                                                    checkProximityAndPreload(fUuid, dw, dx, dy, dz);
                                                    // Derive origin portal from pre-teleport position
                                                    String originId = prePos != null
                                                        ? findNearestPortal(dw.equals(w.getName()) ? dw : w.getName(), prePos[0], prePos[1], prePos[2])
                                                        : null;
                                                    reversePreloadOrigin(fUuid, originId, dw, dx, dy, dz);
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
                            // Save position for origin detection on next teleport
                            lastKnownPosition.put(uuid, new double[]{cx, cy, cz});
                            final UUID fUuid2 = uuid;
                            executor.execute(() -> checkProximityAndPreload(fUuid2, curWorld, cx, cy, cz));
                        }
                    } catch (Exception e) {
                        System.out.println("[OptiPortal] poll error: " + e);
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
                    System.out.println("[OptiPortal] UsedTeleporter detected: dest="
                        + dest.x + "," + dest.y + "," + dest.z + " world=" + worldName);
                    checkProximityAndPreload(null, worldName, dest.x, dest.y, dest.z);
                } else {
                    System.out.println("[OptiPortal] AddPlayerToWorld: UsedTeleporter has invalid destination position");
                }
            } else {
                System.out.println("[OptiPortal] AddPlayerToWorld: no UsedTeleporter (login or non-teleporter move)");
            }
        }
    }

    /**
     * Check proximity to all portals and trigger preload if within activation distance.
     */
    private void checkProximityAndPreload(UUID playerUuid, String worldName, double px, double py, double pz) {
        double maxDist = config.getActivationDistance();
        final double VERTICAL_BOUND = 4.0;

        for (com.optiportal.model.PortalEntry entry : storage.loadAll()) {
            if (entry.isInstanced()) continue;
            if (!entry.getWorld().equals(worldName)) continue;
            if (entry.getId().contains(":")) continue; // skip death:, respawn:, etc.

            // Vertical hard bound
            double dy = Math.abs(py - entry.getY());
            double toX = entry.getX() - px;
            double toZ = entry.getZ() - pz;
            double horizDist = Math.sqrt(toX * toX + toZ * toZ);
            if (dy > VERTICAL_BOUND) continue;
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
    }

    /**
     * When a player arrives at a destination portal, preload the origin portal
     * so that walking back through is seamless.
     *
     * Finds the portal entry closest to the teleport destination coordinates —
     * that's the destination warp. Then preloads it so the return trip is hot.
     */
    private void reversePreloadOrigin(UUID playerUuid, String originId, String destWorld, double dx, double dy, double dz) {
        PortalEntry destEntry = null;
        double bestDist = Double.MAX_VALUE;

        for (PortalEntry entry : storage.loadAll()) {
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
        System.out.println("[OptiPortal] Reverse preload destination: " + destEntry.getId()
            + " dist=" + String.format("%.1f", bestDist) + " cx=" + cx + " cz=" + cz);
        predictiveLoadWithRam(destEntry.getId(), destEntry.getWorld(), cx, cz, radius);
    }

    /**
     * Linger: keeps the player's last active zone HOT for 30s after teleport.
     * The existing HOT→WARM decay timer handles cleanup — no extra scheduling needed.
     */
    private String findNearestPortal(String worldName, double px, double py, double pz) {
        String best = null;
        double bestDist = config.getActivationDistance();
        for (PortalEntry entry : storage.loadAll()) {
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

    private void lingerOriginZone(UUID playerUuid) {
        double[] prePos = lastKnownPosition.get(playerUuid);
        if (prePos == null) return;
        // Find nearest portal to pre-teleport position across all worlds
        String prevZoneId = null;
        double bestDist = Double.MAX_VALUE;
        for (PortalEntry entry : storage.loadAll()) {
            if (entry.isInstanced()) continue;
            if (entry.getId().contains(":")) continue;
            double dist = Math.sqrt(Math.pow(entry.getX() - prePos[0], 2) + Math.pow(entry.getZ() - prePos[2], 2));
            if (dist < bestDist) {
                bestDist = dist;
                prevZoneId = entry.getId();
            }
        }
        if (prevZoneId == null || bestDist > config.getActivationDistance()) return;
        plugin.getCacheManager().setZoneTier(prevZoneId, com.optiportal.model.CacheTier.HOT);
        System.out.println("[OptiPortal] Linger HOT: " + prevZoneId + " (30s decay will clean up)");
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
                    System.out.println("[OptiPortal] Respawn detected (no gravestones) for " + uuid);
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
        @SuppressWarnings("deprecation")
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
                                System.out.println("[OptiPortal] TeleportRecord dest: "
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
     * Look up a cached PlayerRef by UUID (populated on PlayerReadyEvent).
     * Returns null if the player is not online or not yet ready.
     */
    public PlayerRef getPlayerRef(UUID uuid) {
        return playerRefs.get(uuid);
    }

    /**
     * Remove a player's cached ref on disconnect (call from a disconnect handler if available).
     */
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

    public void removePlayerRef(UUID uuid) {
        playerRefs.remove(uuid);
        cooldowns.remove(uuid);
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
        String key = (playerId != null ? playerId.toString() : "global") + ":" + zoneId;
        Long last = cooldowns
                .computeIfAbsent(playerId != null ? playerId : new UUID(0, 0), k -> new ConcurrentHashMap<>())
                .get(zoneId);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < (config.getActivationCooldownSeconds() * 1000L);
    }

    private void recordCooldown(UUID playerId, String zoneId) {
        cooldowns
                .computeIfAbsent(playerId != null ? playerId : new UUID(0, 0), k -> new ConcurrentHashMap<>())
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
     * Fire a predictive load and record RAM delta + estimate on completion.
     */
    private void predictiveLoadWithRam(String zoneId, String worldName, int cx, int cz, int radius) {
        int chunkCount = (2 * radius + 1) * (2 * radius + 1);
        // More accurate RAM estimation: 64KB per chunk for data + 1.5x overhead for entities/metadata
        double estimatedMB = (chunkCount * 65536.0 * 1.5) / (1024.0 * 1024.0);

        chunkPreloader.predictiveLoad(zoneId, worldName, cx, cz, radius)
                .thenRun(() -> {
                    var opt = storage.loadById(zoneId);
                    if (opt.isPresent()) {
                        com.optiportal.model.PortalEntry e = opt.get();
                        e.setRamEstimatedMB(estimatedMB);
                        e.setRamMarginalMB(estimatedMB); // Also update marginal RAM
                        chunkPreloader.updateEntryStats(e, chunkCount);
                        storage.save(e);
                    }
                });
    }

}