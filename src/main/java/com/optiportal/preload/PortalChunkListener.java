package com.optiportal.preload;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.hypixel.hytale.builtin.portals.PortalsPlugin;
import com.hypixel.hytale.builtin.portals.components.PortalDevice;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.optiportal.cache.CacheManager;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.storage.StorageBackend;

/**
 * Listens to ChunkPreLoadProcessEvent to:
 *
 *   1. Auto-register PortalDevice-containing chunks as PREDICTIVE zones.
 *      If a loaded chunk has a PortalDevice component (an EnterPortalInteraction
 *      portal block), we register its chunk position as a preload zone so
 *      the approach-area is pre-warmed without manual warps.json entries.
 *
 *   2. Promote COLD cache zones to WARM when the server natively loads a chunk
 *      that belongs to one of our registered zones.
 *
 * Registration: call register(world) for each world as it is added (from
 * WorldRegistry.addWorldLoadCallback). The event is world-local, so must be
 * registered per world.
 */
public class PortalChunkListener {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final StorageBackend storage;
    private final CacheManager cacheManager;
    private final WarmZoneManager warmZoneManager;

    /**
     * Reverse index: worldName → (packed chunkIndex → zoneId).
     * Built at construction from storage, updated when new portal devices are
     * auto-registered. Replaces the O(n) full-scan in promoteColdZones().
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, String>> reverseIndex
            = new ConcurrentHashMap<>();

    public PortalChunkListener(StorageBackend storage, CacheManager cacheManager,
                               WarmZoneManager warmZoneManager) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.warmZoneManager = warmZoneManager;
        buildReverseIndex();
    }

    /** Pack (chunkX, chunkZ) into a single long for use as map key. */
    private static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
    }

    /** Build the reverse index from all entries currently in storage. */
    private void buildReverseIndex() {
        int count = 0;
        for (PortalEntry entry : storage.loadAll()) {
            if (entry.isInstanced()) continue;
            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            reverseIndex.computeIfAbsent(entry.getWorld(), k -> new ConcurrentHashMap<>())
                        .put(packChunk(cx, cz), entry.getId());
            count++;
        }
        final int finalCount = count;
        LOG.fine(() -> "[OptiPortal] PortalChunkListener: reverse index built with " + finalCount + " entries");
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
    }

    // -------------------------------------------------------------------------

    private void onChunkPreLoad(ChunkPreLoadProcessEvent event) {
        WorldChunk chunk = event.getChunk();
        World world = chunk.getWorld();
        if (world == null) return;

        // Get chunk coordinates directly from WorldChunk
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        // ── Task 1: PortalDevice auto-registration ────────────────────────────
        autoRegisterPortalDevice(event, world, chunkX, chunkZ);

        // ── Task 2: COLD→WARM promotion for known zones ───────────────────────
        promoteColdZones(world.getName(), chunkX, chunkZ);
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

        // Chunk center in world coordinates (Hytale chunks are 32x32 blocks)
        double worldX = chunkX * 32.0 + 16.0;
        double worldZ = chunkZ * 32.0 + 16.0;

        String zoneId = "portaldevice:" + world.getName() + ":" + chunkX + ":" + chunkZ;

        // Skip if already registered
        if (storage.loadById(zoneId).isPresent()) return;

        PortalEntry entry = new PortalEntry(zoneId, world.getName(), worldX, 64.0, worldZ, 0);
        entry.setStrategy(WarmStrategy.PREDICTIVE);
        entry.setType(PortalEntry.EntryType.MANUAL);
        storage.save(entry);

        // Keep reverse index in sync with new auto-registered zone
        reverseIndex.computeIfAbsent(world.getName(), k -> new ConcurrentHashMap<>())
                    .put(packChunk(chunkX, chunkZ), zoneId);

        // Exempt from decay if this portal's world is eternal
        if (!world.getWorldConfig().canUnloadChunks()) {
            cacheManager.markNoDecay(zoneId);
        }

        LOG.info(() -> "[OptiPortal] Auto-registered PortalDevice zone: " + zoneId
                + " at chunk (" + chunkX + ", " + chunkZ + ")");

        // If the destination world is live, also register its spawn point
        if (device.getDestinationWorld() != null) {
            warmZoneManager.scanWorldForPortalDestination(device.getDestinationWorld());
        }
    }

    /**
     * When any chunk loads, check if its position matches the centre chunk of a
     * registered zone and promote COLD/UNVISITED zones to WARM.
     *
     * Uses the pre-built reverse index for O(1) lookup instead of scanning all
     * stored portal entries on every chunk load event.
     */
    private void promoteColdZones(String worldName, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, String> worldIndex = reverseIndex.get(worldName);
        if (worldIndex == null) return;

        String zoneId = worldIndex.get(packChunk(chunkX, chunkZ));
        if (zoneId == null) return;

        CacheTier current = cacheManager.getZoneTier(zoneId);
        if (current == CacheTier.COLD || current == CacheTier.UNVISITED) {
            cacheManager.setZoneTier(zoneId, CacheTier.WARM);
            LOG.fine(() -> "[OptiPortal] PortalChunkListener: promoted zone "
                    + zoneId + " from " + current + " → WARM (server loaded chunk)");
        }
    }
}
