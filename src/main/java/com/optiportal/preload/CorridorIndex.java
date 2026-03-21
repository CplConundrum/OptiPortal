package com.optiportal.preload;

import java.util.Map;
import java.util.logging.Logger;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.path.WorldPath;
import com.hypixel.hytale.server.core.universe.world.path.WorldPathChangedEvent;
import com.hypixel.hytale.server.core.universe.world.path.WorldPathConfig;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Maintains a set of packed chunk coordinates that lie within a configurable
 * radius of WorldPath waypoints across all loaded worlds.
 *
 * Chunks in this set are "corridor chunks" and are prioritized higher in
 * EnhancedChunkPreloader.buildChunkListEnhanced().
 *
 * The index is rebuilt atomically via volatile reference swap — concurrent
 * readers always see a complete snapshot, never a partial rebuild.
 *
 * Threading:
 *   buildIndex() may be called from any thread (executor.submit or event handler).
 *   isCorridor() reads are volatile — safe from any thread.
 *   World.getWorldPathConfig() returns a config object — safe off-thread.
 *   Transform.getPosition() is a value object — safe off-thread.
 */
public class CorridorIndex {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final WorldRegistry worldRegistry;
    private final int corridorRadiusChunks;

    /**
     * Volatile reference for lock-free read access. Writers call buildIndex()
     * which constructs a new set and swaps the reference atomically.
     */
    private volatile LongOpenHashSet corridorChunks = null;

    /**
     * @param worldRegistry        WorldRegistry for iterating loaded worlds
     * @param corridorRadiusChunks Chunk radius around each waypoint to mark as corridor
     */
    public CorridorIndex(WorldRegistry worldRegistry, int corridorRadiusChunks) {
        this.worldRegistry = worldRegistry;
        this.corridorRadiusChunks = Math.max(0, corridorRadiusChunks);
    }

    /**
     * Build (or rebuild) the corridor chunk index.
     *
     * Iterates all currently loaded worlds, reads their WorldPathConfig,
     * expands each waypoint by corridorRadiusChunks, and builds a new
     * LongOpenHashSet. Swaps the reference atomically on completion.
     *
     * Safe to call from any thread. Called:
     *   - Once at startup (via executor.submit)
     *   - On WorldPathChangedEvent
     */
    public void buildIndex() {
        LongOpenHashSet newSet = new LongOpenHashSet();
        int worldCount = 0;
        int pathCount = 0;
        int waypointCount = 0;

        for (World world : worldRegistry.getWorlds()) {
            worldCount++;
            try {
                WorldPathConfig pathConfig = world.getWorldPathConfig();
                if (pathConfig == null) continue;

                Map<String, WorldPath> paths = pathConfig.getPaths();
                if (paths == null || paths.isEmpty()) continue;

                for (WorldPath path : paths.values()) {
                    pathCount++;
                    java.util.List<Transform> waypoints = path.getWaypoints();
                    if (waypoints == null) continue;

                    for (Transform waypoint : waypoints) {
                        if (waypoint == null || waypoint.getPosition() == null) continue;
                        waypointCount++;

                        // Convert world-space X,Z to chunk coordinates.
                        // Hytale chunks are 32 blocks wide — use ChunkUtil.chunkCoordinate().
                        int wpCx = ChunkUtil.chunkCoordinate(waypoint.getPosition().x);
                        int wpCz = ChunkUtil.chunkCoordinate(waypoint.getPosition().z);

                        // Add all chunks within corridorRadiusChunks (square radius)
                        for (int dx = -corridorRadiusChunks; dx <= corridorRadiusChunks; dx++) {
                            for (int dz = -corridorRadiusChunks; dz <= corridorRadiusChunks; dz++) {
                                newSet.add(packChunkCoord(wpCx + dx, wpCz + dz));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.fine("[OptiPortal] CorridorIndex: error processing world '"
                        + world.getName() + "': " + e.getMessage());
            }
        }

        // Atomic swap — readers always see a complete set
        this.corridorChunks = newSet;

        LOG.info("[OptiPortal] CorridorIndex built: worlds=" + worldCount
                + " paths=" + pathCount
                + " waypoints=" + waypointCount
                + " corridorChunks=" + newSet.size());
    }

    /**
     * Returns true if the given chunk coordinate is within any path corridor.
     *
     * @param cx Chunk X coordinate
     * @param cz Chunk Z coordinate
     * @return true if this chunk is in a corridor
     */
    public boolean isCorridor(int cx, int cz) {
        LongOpenHashSet set = corridorChunks;
        return set != null && set.contains(packChunkCoord(cx, cz));
    }

    /**
     * Returns true if the given packed chunk index is within any path corridor.
     *
     * @param packedIndex Packed chunk coordinate (same format as WorldChunk.getIndex())
     * @return true if this chunk is in a corridor
     */
    public boolean isCorridor(long packedIndex) {
        LongOpenHashSet set = corridorChunks;
        return set != null && set.contains(packedIndex);
    }

    /**
     * Returns true if the index has been built and contains at least one chunk.
     *
     * @return true if the corridor index is populated
     */
    public boolean isEmpty() {
        LongOpenHashSet set = corridorChunks;
        return set == null || set.isEmpty();
    }

    /**
     * Returns the number of chunks currently in the corridor index.
     * Returns 0 if the index has not been built yet.
     */
    public int size() {
        LongOpenHashSet set = corridorChunks;
        return set == null ? 0 : set.size();
    }

    /**
     * Register a listener for WorldPathChangedEvent to trigger index rebuilds.
     *
     * WorldPathChangedEvent is fired when server-side path data changes.
     * The event handler calls buildIndex() synchronously within the handler
     * body — since buildIndex() just reads config objects and builds a set,
     * it is fast enough to run inline. For worlds with very large numbers of
     * paths/waypoints, consider submitting to executor instead.
     *
     * @param registry The plugin's EventRegistry
     */
    public void registerEventListener(EventRegistry registry) {
        try {
            registry.registerGlobal(WorldPathChangedEvent.class, event -> {
                LOG.fine("[OptiPortal] CorridorIndex: WorldPathChangedEvent received, rebuilding...");
                buildIndex();
            });
            LOG.info("[OptiPortal] CorridorIndex: registered WorldPathChangedEvent listener.");
        } catch (Exception e) {
            // If WorldPathChangedEvent registration fails (e.g. event not supported in this build),
            // log a warning and continue — the index will still be built at startup.
            LOG.warning("[OptiPortal] CorridorIndex: could not register WorldPathChangedEvent: "
                    + e.getMessage()
                    + " (corridor index will not auto-refresh on path changes)");
        }
    }

    /**
     * Pack chunk coordinates to a long index.
     * Formula: low 32 bits = cx (unsigned), high 32 bits = cz (unsigned).
     * Must match the formula used in CacheManager and ChunkOwnershipAuditor.
     */
    private static long packChunkCoord(int cx, int cz) {
        return ((long)(cx & 0xFFFFFFFF)) | ((long)(cz & 0xFFFFFFFF) << 32);
    }
}
