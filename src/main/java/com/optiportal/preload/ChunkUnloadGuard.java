package com.optiportal.preload;

import java.util.logging.Logger;

import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkUnloadEvent;
import com.optiportal.cache.CacheManager;

/**
 * Subscribes to {@link ChunkUnloadEvent} on a single world and cancels chunk eviction
 * for any chunk currently owned by a warm zone.
 *
 * <p>This replaces the timing-dependent heartbeat approach in {@link AsyncKeepaliveManager}
 * as the primary retention mechanism. Because {@code ChunkUnloadEvent} fires synchronously
 * on the world thread immediately before the engine evicts a chunk, cancelling it is
 * race-free — there is no window between the keepalive timer and the eviction sweep.
 *
 * <p>The heartbeat is kept at a much longer interval (30/60/120 min) as a belt-and-suspenders
 * fallback for the brief window before this guard's subscription activates on a new world.
 *
 * <p>Registration: one instance per world, created in
 * {@link PortalChunkListener#register(World)} when the world is added.
 */
public class ChunkUnloadGuard {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final CacheManager cacheManager;
    private final World world;

    public ChunkUnloadGuard(CacheManager cacheManager, World world) {
        this.cacheManager = cacheManager;
        this.world = world;
    }

    /**
     * Subscribe to {@link ChunkUnloadEvent} on this world's event registry.
     * Called once when the world is registered via {@code WorldRegistry.addWorldLoadCallback}.
     */
    public void register() {
        world.getEventRegistry().register(ChunkUnloadEvent.class, this::onChunkUnload);
        LOG.fine("[OptiPortal] ChunkUnloadGuard registered for world: " + world.getName());
    }

    private void onChunkUnload(ChunkUnloadEvent event) {
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        String worldName = world.getName();

        if (cacheManager.isChunkOwned(worldName, cx, cz)) {
            event.setCancelled(true);
            // Preserve the keepalive counter so the engine does not immediately re-schedule eviction
            event.setResetKeepAlive(false);
            LOG.fine(() -> "[OptiPortal] Cancelled unload for owned chunk ("
                    + cx + ", " + cz + ") in " + worldName);
        }
    }
}
