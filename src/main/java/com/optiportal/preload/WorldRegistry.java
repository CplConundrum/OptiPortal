package com.optiportal.preload;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;

/**
 * Maintains a name → World map by listening to AddWorldEvent / RemoveWorldEvent.
 * WorldEvent implements IEvent<String> (keyed by world name), so we use
 * registerGlobal with explicit type witnesses to satisfy the generic bounds.
 */
public class WorldRegistry {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final ConcurrentHashMap<String, World> worlds = new ConcurrentHashMap<>();
    private final List<Consumer<String>> worldUnloadCallbacks = new CopyOnWriteArrayList<>();
    private final List<Consumer<World>> worldLoadCallbacks = new CopyOnWriteArrayList<>();

    /**
     * Tracks worlds whose load callbacks have already been fired.
     * Prevents double-invocation when both AddWorldEvent and StartWorldEvent
     * are received for the same world, or when seedFromUniverse() overlaps with
     * an in-flight StartWorldEvent.
     */
    private final java.util.Set<String> initializedWorlds = ConcurrentHashMap.newKeySet();

    public void register(EventRegistry events) {
        // AddWorldEvent: add to map immediately so lookups work, but defer callbacks
        // until StartWorldEvent confirms the world is fully operational.
        events.<String, AddWorldEvent>registerGlobal(AddWorldEvent.class, event -> {
            worlds.put(event.getWorld().getName(), event.getWorld());
        });

        // StartWorldEvent: world is now fully started — fire load callbacks exactly once.
        events.<String, StartWorldEvent>registerGlobal(StartWorldEvent.class, event -> {
            World world = event.getWorld();
            worlds.put(world.getName(), world); // idempotent
            if (initializedWorlds.add(world.getName())) {
                worldLoadCallbacks.forEach(cb -> cb.accept(world));
                LOG.fine(() -> "[OptiPortal] WorldRegistry: world started: " + world.getName());
            }
        });

        events.<String, RemoveWorldEvent>registerGlobal(RemoveWorldEvent.class, event -> {
            World world = event.getWorld();
            String name = world.getName();
            worlds.remove(name);
            initializedWorlds.remove(name); // allow re-init if the world is re-added
            worldUnloadCallbacks.forEach(cb -> cb.accept(name));
        });
    }

    /**
     * Look up a world by name. Returns null if the world is not currently loaded.
     */
    /** Manually register a world — used as fallback when AddWorldEvent fires before plugin loads. */
    public void addWorld(World world) {
        worlds.put(world.getName(), world);
    }

    /** Register a callback to be invoked (with world name) on RemoveWorldEvent. */
    public void addWorldUnloadCallback(Consumer<String> callback) {
        worldUnloadCallbacks.add(callback);
    }

    /** Register a callback to be invoked (with new World) on AddWorldEvent. */
    public void addWorldLoadCallback(Consumer<World> callback) {
        worldLoadCallbacks.add(callback);
    }

    public World getWorld(String name) {
        return worlds.get(name);
    }

    /**
     * All currently loaded worlds.
     */
    public Collection<World> getWorlds() {
        return Collections.unmodifiableCollection(worlds.values());
    }

    /** Returns any loaded world, or null if none. Used for world-thread dispatch. */
    public World getAnyWorld() {
        return worlds.values().stream().findFirst().orElse(null);
    }

    /**
     * Returns the World the given player is currently in.
     * Matches playerRef.getWorldUuid() against each world's EntityStore UUID.
     * Falls back to getAnyWorld() if not matched (single-world servers always match).
     */
    public World getWorldForPlayer(com.hypixel.hytale.server.core.universe.PlayerRef playerRef) {
        try {
            java.util.UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid != null) {
                for (World w : worlds.values()) {
                    // World UUID is the UUID of its EntityStore's first ref, or matched via getName()
                    // against the world UUID stored in TeleportRecord. Since World has no getUUID(),
                    // we match by checking if the world's entity store contains the player's ref UUID.
                    com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref =
                            w.getEntityStore().getRefFromUUID(playerRef.getUuid());
                    if (ref != null) {
                        return w;
                    }
                }
            }
        } catch (Exception ignored) {}
        return getAnyWorld();
    }

    /**
     * Seeds the world map from Universe.get() for any worlds already live
     * at the time this method is called.
     *
     * Call this once at plugin startup, after register(EventRegistry) has been
     * called, to capture worlds that were already loaded before our plugin started.
     * Safe to call at any time — addWorld() is idempotent (ConcurrentHashMap.put).
     */
    public void seedFromUniverse() {
        Universe universe = Universe.get();
        if (universe == null) return;
        Map<String, World> live = universe.getWorlds();
        if (live == null || live.isEmpty()) return;
        live.values().forEach(world -> {
            worlds.put(world.getName(), world);
            // Worlds already live at startup have already started — fire callbacks now,
            // guarded by initializedWorlds to prevent double-firing with StartWorldEvent.
            if (initializedWorlds.add(world.getName())) {
                worldLoadCallbacks.forEach(cb -> cb.accept(world));
            }
        });
        LOG.info(() -> "[OptiPortal] WorldRegistry.seedFromUniverse: seeded " + live.size() + " worlds.");
    }

    /**
     * Sum of loaded chunk counts across all currently loaded worlds.
     * Each world's ChunkStore.getLoadedChunksCount() is a live snapshot.
     * Returns 0 if no worlds are loaded.
     *
     * Thread-safe: worlds map is ConcurrentHashMap; getLoadedChunksCount() is AtomicInteger.
     */
    public int getTotalLoadedChunkCount() {
        int total = 0;
        for (World world : worlds.values()) {
            try {
                total += world.getChunkStore().getLoadedChunksCount();
            } catch (Exception ignored) {
                // World may be shutting down — skip
            }
        }
        return total;
    }
}
