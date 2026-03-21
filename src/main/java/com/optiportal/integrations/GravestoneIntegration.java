package com.optiportal.integrations;

import com.hypixel.hytale.event.EventRegistry;
import com.optiportal.config.PluginConfig;
import com.optiportal.player.DeathLocationTracker;
import zurku.gravestones.event.GravestoneBrokenEvent;
import zurku.gravestones.event.GravestoneCollectedEvent;
import zurku.gravestones.event.GravestoneCreatedEvent;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

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

        // Gravestone created → pre-load chunks around death location, flag for respawn capture
        events.register(GravestoneCreatedEvent.class, event -> {
            UUID owner = event.getOwnerUuid();
            int x = event.getX();
            int y = event.getY();
            int z = event.getZ();
            String world = event.getWorldName();
            deathTracker.onPlayerDeath(owner, x, y, z, world);
            pendingRespawnCapture.add(owner);
        });

        // Gravestone collected (all items picked up) → release cache
        events.register(GravestoneCollectedEvent.class, event -> {
            if (config.isGravestoneReleaseOnEmpty()) {
                deathTracker.onGravestoneReleased(event.getOwnerUuid());
            }
        });

        // Gravestone broken (block broken by anyone) → release cache
        events.register(GravestoneBrokenEvent.class, event -> {
            if (config.isGravestoneReleaseOnBreak()) {
                deathTracker.onGravestoneReleased(event.getOwnerUuid());
            }
        });

        LOG.info("[OptiPortal] Gravestone integration active — listening for Created/Collected/Broken events.");
    }
}