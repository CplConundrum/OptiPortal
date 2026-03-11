package com.optiportal.preload;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains a name → World map by listening to AddWorldEvent / RemoveWorldEvent.
 * WorldEvent implements IEvent<String> (keyed by world name), so we use
 * registerGlobal with explicit type witnesses to satisfy the generic bounds.
 */
public class WorldRegistry {

    private final ConcurrentHashMap<String, World> worlds = new ConcurrentHashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void register(EventRegistry events) {
        // WorldEvent implements IEvent<String> — use raw register to bypass Void bound check
        events.<String, AddWorldEvent>registerGlobal(AddWorldEvent.class, event -> {
            World world = event.getWorld();
            worlds.put(world.getName(), world);
        });
        events.<String, RemoveWorldEvent>registerGlobal(RemoveWorldEvent.class, event -> {
            World world = event.getWorld();
            worlds.remove(world.getName());
        });
    }

    /**
     * Look up a world by name. Returns null if the world is not currently loaded.
     */
    /** Manually register a world — used as fallback when AddWorldEvent fires before plugin loads. */
    public void addWorld(World world) {
        worlds.put(world.getName(), world);
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
}