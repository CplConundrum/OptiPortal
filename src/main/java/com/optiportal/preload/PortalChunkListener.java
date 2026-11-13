package com.optiportal.preload;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.hypixel.hytale.builtin.portals.PortalsPlugin;
import com.hypixel.hytale.builtin.portals.components.PortalDevice;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.storage.StorageBackend;

/**
 * Listens to ChunkPreLoadProcessEvent to auto-register portal devices as PREDICTIVE zones.
 *
 * <p>This service is available in the live runtime, but its world-event registration is
 * config-gated. When enabled, it can auto-register portal devices and promote zones on
 * engine chunk-preload events. When disabled, it still remains useful as an index-backed
 * helper for zone cleanup paths such as delete/removeFromIndex.
 *
 * <p>Behavior:
 * <ol>
 *   <li>Auto-register PortalDevice-containing chunks as PREDICTIVE zones. If a loaded chunk has a PortalDevice component (an EnterPortalInteraction portal block), we register its chunk position as a preload zone so the approach-area is pre-warmed without manual warps.json entries.</li>
 *   <li>Promote COLD cache zones to WARM when the server natively loads a chunk that belongs to one of our registered zones.</li>
 * </ol>
 *
 * <p>Registration: call register(world) for each world as it is added (from WorldRegistry.addWorldLoadCallback). The event is world-local, so must be registered per world.
 */
public class PortalChunkListener {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final StorageBackend storage;
    private final CacheManager cacheManager;
    private final WarmZoneManager warmZoneManager;
    private final PluginConfig config;
    private final java.util.concurrent.Executor executor;

    /**
     * Reverse index: worldName → (packed chunkIndex → zoneId).
     * Covers the full zone footprint (all chunks within radius), not just the centre.
     * Built at construction from storage, updated when new portal devices are auto-registered.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, String>> reverseIndex
            = new ConcurrentHashMap<>();

    /**
     * In-memory cache of PortalEntry objects keyed by zone ID.
     * Eliminates storage.loadById() calls on the hot chunk-load event path.
     * Populated at construction, kept in sync on save/delete.
     */
    private final ConcurrentHashMap<String, PortalEntry> entryCache = new ConcurrentHashMap<>();

    public PortalChunkListener(StorageBackend storage, CacheManager cacheManager,
                               WarmZoneManager warmZoneManager, PluginConfig config,
                               java.util.concurrent.Executor executor) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.warmZoneManager = warmZoneManager;
        this.config = config;
        this.executor = executor;
        buildReverseIndex();
    }

    /** Pack (chunkX, chunkZ) into a single long for use as map key. */
    private static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
    }

    /**
     * Build the reverse index from all entries currently in storage.
     * Indexes every chunk in each zone's full footprint (H4) so that ownership
     * can be registered for any chunk the engine loads within a warm zone.
     */
    private void buildReverseIndex() {
        int count = 0;
        int defaultRadius = config.getDefaultWarmRadius();
        for (PortalEntry entry : storage.loadAll()) {
            if (entry.isInstanced()) continue;
            entryCache.put(entry.getId(), entry);
            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            int rx = entry.resolvedRadiusX(defaultRadius);
            int rz = entry.resolvedRadiusZ(defaultRadius);
            ConcurrentHashMap<Long, String> worldIndex =
                    reverseIndex.computeIfAbsent(entry.getWorld(), k -> new ConcurrentHashMap<>());
            for (int dx = -rx; dx <= rx; dx++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    // putIfAbsent: smaller-radius zone that already owns a chunk takes priority
                    worldIndex.putIfAbsent(packChunk(cx + dx, cz + dz), entry.getId());
                }
            }
            count++;
        }
        final int finalCount = count;
        LOG.fine(() -> "[OptiPortal] PortalChunkListener: reverse index built for " + finalCount + " zones");
    }

    /**
     * Resolve the zone ID that owns the given chunk, or null if none.
     * O(1) lookup against the pre-built reverse index.
     */
    private String resolveZoneForChunk(String worldName, int cx, int cz) {
        ConcurrentHashMap<Long, String> worldIndex = reverseIndex.get(worldName);
        if (worldIndex == null) return null;
        return worldIndex.get(packChunk(cx, cz));
    }

    /**
     * Register this listener on a specific world's event bus.
     * Must be called each time a new world is added (via AddWorldEvent callback).
     * ChunkPreLoadProcessEvent is IEvent<String> (keyed by chunk position),
     * so we use registerGlobal with the chunk position as the key.
     */
    public void register(World world) {
        world.getEventRegistry().<String, ChunkPreLoadProcessEvent>registerGlobal(
                ChunkPreLoadProcessEvent.class, this::onChunkPreLoad);

        // H1: register event-driven chunk unload guard for this world
        new ChunkUnloadGuard(cacheManager, world).register();
    }

    // -------------------------------------------------------------------------

    private void onChunkPreLoad(ChunkPreLoadProcessEvent event) {
        WorldChunk chunk = event.getChunk();
        // 0.7.7: WorldChunk no longer exposes getWorld() — the event carries it.
        World world = event.getWorld();
        if (world == null) return;

        // Get chunk coordinates directly from WorldChunk
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        // ── Task 1: PortalDevice auto-registration ────────────────────────────
        autoRegisterPortalDevice(event, world, chunkX, chunkZ);

        // ── Task 2: COLD→WARM promotion for known zones ───────────────────────
        promoteColdZones(world, chunkX, chunkZ);

        // ── Task 3: event-driven ownership registration (H4) ─────────────────
        // Covers chunks loaded by the engine outside of OptiPortal's explicit requests
        // (player movement, NPC pathfinding, etc.) so ChunkUnloadGuard can protect them.
        String worldName = world.getName();
        if (!cacheManager.isChunkOwned(worldName, chunkX, chunkZ)) {
            String owningZone = resolveZoneForChunk(worldName, chunkX, chunkZ);
            if (owningZone != null) {
                cacheManager.registerOwnership(owningZone, worldName, chunkX, chunkZ, chunk);
                LOG.fine(() -> "[OptiPortal] Event-driven ownership: (" + chunkX + ", " + chunkZ
                        + ") in " + worldName + " → zone " + owningZone);
            }
        }
    }

    /**
     * If the loaded chunk contains a PortalDevice, auto-register it as a
     * PREDICTIVE zone named "portaldevice:<world>:<chunkX>:<chunkZ>".
     */
    private void autoRegisterPortalDevice(ChunkPreLoadProcessEvent event,
                                          World world, int chunkX, int chunkZ) {
        if (PortalsPlugin.getInstance() == null) return;

        PortalDevice device;
        try {
            device = event.getHolder().getComponent(PortalDevice.getComponentType());
        } catch (Exception e) {
            return; // PortalDevice component type not registered yet
        }
        if (device == null) return;

        // Zone bookkeeping is deliberately SYNCHRONOUS (world thread):
        // onChunkPreLoad runs promoteColdZones and the H4 event-driven
        // ownership registration right after this method returns, in the
        // same event -- deferring the index publication off-thread would
        // leave them seeing a missing index. The only off-thread part is
        // storage persistence (inside the bookkeeping).
        boolean created = autoRegisterPortalDeviceZone(
                world.getName(), chunkX, chunkZ,
                device.getDestinationWorldUuid(),
                world.getWorldConfig().canUnloadChunks());
        if (created && device.getDestinationWorld() != null) {
            // One-shot per zone: existing zones return created=false above, so
            // reloads of an already-registered chunk never re-scan the
            // destination world (original semantics, preserved).
            warmZoneManager.scanWorldForPortalDestination(device.getDestinationWorld());
        }
    }

    /**
     * Synchronous (world-thread) zone bookkeeping for an auto-registered
     * PortalDevice zone. entryCache + the full-footprint reverse index are
     * published before this method returns, so the same event's promote and
     * H4 ownership steps see the new zone. Only storage persistence is
     * off-thread (executor). Returns true if a NEW entry was created, false
     * for the existing-entry (backfill) path. Package-private for tests.
     */
    boolean autoRegisterPortalDeviceZone(String worldName, int chunkX, int chunkZ,
                                          UUID destUuid, boolean canUnload) {
        // Chunk center in world coordinates (Hytale chunks are 32x32 blocks)
        double worldX = chunkX * 32.0 + 16.0;
        double worldZ = chunkZ * 32.0 + 16.0;

        String zoneId = "portaldevice:" + worldName + ":" + chunkX + ":" + chunkZ;

        // If already registered, backfill destination UUID if not yet persisted
        PortalEntry existingEntry = entryCache.get(zoneId);
        if (existingEntry != null) {
            if (existingEntry.getDestinationWorldUuid() == null && destUuid != null) {
                existingEntry.setDestinationWorldUuid(destUuid);
                entryCache.put(zoneId, existingEntry);
                executor.execute(() -> storage.save(existingEntry));
                LOG.fine(() -> "[OptiPortal] Backfilled destination UUID for existing zone: " + zoneId);
            }
            return false;
        }

        PortalEntry entry = new PortalEntry(zoneId, worldName, worldX, 64.0, worldZ, 0);
        entry.setStrategy(WarmStrategy.PREDICTIVE);
        entry.setType(PortalEntry.EntryType.MANUAL);
        if (destUuid != null) {
            entry.setDestinationWorldUuid(destUuid);
        }
        entryCache.put(zoneId, entry);
        executor.execute(() -> storage.save(entry));

        // Keep reverse index in sync with new auto-registered zone (full footprint, not just centre)
        int defaultRadius = config.getDefaultWarmRadius();
        int rx = entry.resolvedRadiusX(defaultRadius);
        int rz = entry.resolvedRadiusZ(defaultRadius);
        ConcurrentHashMap<Long, String> worldIdx =
                reverseIndex.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                worldIdx.putIfAbsent(packChunk(chunkX + dx, chunkZ + dz), zoneId);
            }
        }

        // Exempt from decay if this portal's world is eternal
        if (!canUnload) {
            cacheManager.markNoDecay(zoneId);
        }

        LOG.info(() -> "[OptiPortal] Auto-registered PortalDevice zone: " + zoneId
                + " at chunk (" + chunkX + ", " + chunkZ + ")");
        return true;
    }

    // Test accessors (package-private)
    PortalEntry entryCacheLookup(String zoneId) { return entryCache.get(zoneId); }
    String reverseIndexLookup(String worldName, long chunkKey) {
        ConcurrentHashMap<Long, String> idx = reverseIndex.get(worldName);
        return idx == null ? null : idx.get(chunkKey);
    }
    /**
     * When any chunk loads, check if its position matches a chunk in a registered
     * zone and promote COLD/UNVISITED zones to WARM. Also reads the PortalDevice
     * component to confirm/update the destination world UUID and auto-register the
     * destination warm zone if not yet present.
     */
    private void promoteColdZones(World world, int chunkX, int chunkZ) {
        String worldName = world.getName();
        ConcurrentHashMap<Long, String> worldIndex = reverseIndex.get(worldName);
        if (worldIndex == null) return;

        long key = packChunk(chunkX, chunkZ);
        String zoneId = worldIndex.get(key);
        if (zoneId == null) return;

        // Guard against stale entries left by zones deleted without calling removeFromIndex
        PortalEntry entry = entryCache.get(zoneId);
        if (entry == null) {
            worldIndex.remove(key);
            return;
        }

        // H2: read PortalDevice component directly to confirm/update destination UUID
        // Only scan when destination UUID is not yet known (Issue 3 optimization)
        if (entry.getType() == PortalEntry.EntryType.PORTAL
                && entry.getDestinationWorldUuid() == null) {
            try {
                PortalDevice device = (PortalDevice) BlockModule.getComponent(
                        PortalDevice.getComponentType(),
                        world,
                        (int) entry.getX(),
                        (int) entry.getY(),
                        (int) entry.getZ());
                if (device != null) {
                    java.util.UUID destUuid = device.getDestinationWorldUuid();
                    if (destUuid != null) {
                        entry.setDestinationWorldUuid(destUuid);
                        entryCache.put(entry.getId(), entry);
                        executor.execute(() -> storage.save(entry));   // async — does not stall event handler
                        LOG.info("[OptiPortal] Updated destination UUID for zone " + zoneId + " → " + destUuid);
                    }
                    World destWorld = device.getDestinationWorld();
                    if (destWorld != null && !warmZoneManager.hasDestinationZone(destWorld.getName())) {
                        warmZoneManager.registerPortalDestination(destWorld);
                    }
                }
            } catch (Exception ignored) {
                // PortalDevice component not available — chunk may not carry the block
            }
        }

        CacheTier current = cacheManager.getZoneTier(zoneId);
        if (current == CacheTier.COLD || current == CacheTier.UNVISITED) {
            cacheManager.setZoneTier(zoneId, CacheTier.WARM);
            LOG.fine(() -> "[OptiPortal] PortalChunkListener: promoted zone "
                    + zoneId + " from " + current + " → WARM (server loaded chunk)");
        }
    }

    /**
     * Remove a zone from the reverse index when it is deleted.
     *
     * @param zoneId Zone ID to remove
     */
    public void removeFromIndex(String zoneId) {
        entryCache.remove(zoneId);
        for (ConcurrentHashMap<Long, String> worldIndex : reverseIndex.values()) {
            worldIndex.entrySet().removeIf(entry -> entry.getValue().equals(zoneId));
        }
        LOG.fine(() -> "[OptiPortal] PortalChunkListener: removed zone " + zoneId + " from reverse index");
    }

    /**
     * Called when a world is destroyed (RemoveWorldEvent via H6).
     * Clears the reverse index for that world so lookups don't reference dead chunks.
     * The zone definitions remain in storage; the index is rebuilt when the world re-registers.
     */
    public void onWorldRemoved(com.hypixel.hytale.server.core.universe.world.World world) {
        String worldName = world.getName();
        reverseIndex.remove(worldName);
        // Also clear entryCache entries for this world to prevent stale references
        entryCache.entrySet().removeIf(entry -> entry.getValue().getWorld().equals(worldName));
        LOG.fine("[OptiPortal] PortalChunkListener: cleared reverseIndex and entryCache for removed world: " + worldName);
    }
}
