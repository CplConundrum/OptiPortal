package com.optiportal.integrations;

import com.hypixel.hytale.event.EventRegistry;
import com.optiportal.config.PluginConfig;
import com.optiportal.player.DeathLocationTracker;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.function.Consumer;

/**
 * Integration with Zurku's Gravestones plugin (1.1.0+).
 *
 * All three gravestone events implement IEvent<Void> — use register() not registerGlobal().
 * They are dispatched via HytaleServer EventBus, so we listen on the standard EventRegistry.
 *
 * GravestoneCreatedEvent  — player died, gravestone placed → pre-load death location chunks,
 *                           mark player as pending respawn capture for RespawnTracker.
 * GravestoneCollectedEvent — player collected all items → release death location cache
 * GravestoneBrokenEvent   — gravestone block broken (admin/other) → release death location cache
 */
public class GravestoneIntegration {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final PluginConfig config;
    private final DeathLocationTracker deathTracker;

    /**
     * Players who have died since their last PlayerReadyEvent.
     * TeleportInterceptor checks and clears this set on each PlayerReadyEvent
     * to know when to capture a respawn location.
     */
    private final Set<UUID> pendingRespawnCapture =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public GravestoneIntegration(PluginConfig config, DeathLocationTracker deathTracker) {
        this.config = config;
        this.deathTracker = deathTracker;
    }

    /** Returns true and clears the flag if this player has a pending respawn capture. */
    public boolean consumePendingRespawn(UUID playerId) {
        return pendingRespawnCapture.remove(playerId);
    }

    /** Returns true if a pending respawn capture flag exists without consuming it. */
    public boolean hasPendingRespawn(UUID playerId) {
        return pendingRespawnCapture.contains(playerId);
    }

    /** Manually mark a player as pending respawn capture (used when gravestones are disabled). */
    public void markPendingRespawn(UUID playerId) {
        pendingRespawnCapture.add(playerId);
    }

    public void init(EventRegistry events) {
        if (!config.isGravestoneIntegrationEnabled()) {
            LOG.info("[OptiPortal] Gravestone integration disabled.");
            return;
        }

        Class<?> createdEventClass = loadEventClass("zurku.gravestones.event.GravestoneCreatedEvent");
        Class<?> collectedEventClass = loadEventClass("zurku.gravestones.event.GravestoneCollectedEvent");
        Class<?> brokenEventClass = loadEventClass("zurku.gravestones.event.GravestoneBrokenEvent");
        if (createdEventClass == null || collectedEventClass == null || brokenEventClass == null) {
            LOG.warning("[OptiPortal] Gravestone integration enabled, but Gravestones event classes were not found. Integration will stay inactive.");
            return;
        }

        // Gravestone created → pre-load chunks around death location, flag for respawn capture
        registerReflective(events, createdEventClass, event -> {
            UUID owner = (UUID) invoke(event, "getOwnerUuid");
            int x = ((Number) invoke(event, "getX")).intValue();
            int y = ((Number) invoke(event, "getY")).intValue();
            int z = ((Number) invoke(event, "getZ")).intValue();
            String world = (String) invoke(event, "getWorldName");
            deathTracker.onPlayerDeath(owner, x, y, z, world);
            pendingRespawnCapture.add(owner);
        });

        // Gravestone collected (all items picked up) → release cache
        registerReflective(events, collectedEventClass, event -> {
            if (config.isGravestoneReleaseOnEmpty()) {
                deathTracker.onGravestoneReleased((UUID) invoke(event, "getOwnerUuid"));
            }
        });

        // Gravestone broken (block broken by anyone) → release cache
        registerReflective(events, brokenEventClass, event -> {
            if (config.isGravestoneReleaseOnBreak()) {
                deathTracker.onGravestoneReleased((UUID) invoke(event, "getOwnerUuid"));
            }
        });

        LOG.info("[OptiPortal] Gravestone integration active — listening for Created/Collected/Broken events.");
    }

    private Class<?> loadEventClass(String className) {
        try {
            return Class.forName(className, false, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerReflective(EventRegistry events, Class<?> eventClass, Consumer<Object> handler) {
        events.register((Class) eventClass, event -> handler.accept(event));
    }

    private Object invoke(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read Gravestones event method " + methodName, e);
        }
    }
}
