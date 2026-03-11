package com.optiportal.integrations;

import com.hypixel.hytale.event.EventRegistry;
import com.optiportal.config.PluginConfig;
import com.optiportal.player.DeathLocationTracker;
import zurku.gravestones.event.GravestoneBrokenEvent;
import zurku.gravestones.event.GravestoneCollectedEvent;
import zurku.gravestones.event.GravestoneCreatedEvent;

import java.util.UUID;

/**
 * Integration with Zurku's Gravestones plugin (1.1.0+).
 *
 * All three gravestone events implement IEvent<Void> — use register() not registerGlobal().
 * They are dispatched via HytaleServer EventBus, so we listen on the standard EventRegistry.
 *
 * GravestoneCreatedEvent  — player died, gravestone placed → pre-load death location chunks
 * GravestoneCollectedEvent — player collected all items → release death location cache
 * GravestoneBrokenEvent   — gravestone block broken (admin/other) → release death location cache
 */
public class GravestoneIntegration {

    private final PluginConfig config;
    private final DeathLocationTracker deathTracker;

    public GravestoneIntegration(PluginConfig config, DeathLocationTracker deathTracker) {
        this.config = config;
        this.deathTracker = deathTracker;
    }

    public void init(EventRegistry events) {
        if (!config.isGravestoneIntegrationEnabled()) {
            System.out.println("[OptiPortal] Gravestone integration disabled.");
            return;
        }

        // Gravestone created → pre-load chunks around death location
        events.register(GravestoneCreatedEvent.class, event -> {
            UUID owner = event.getOwnerUuid();
            int x = event.getX();
            int y = event.getY();
            int z = event.getZ();
            String world = event.getWorldName();
            deathTracker.onPlayerDeath(owner, x, y, z, world);
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

        System.out.println("[OptiPortal] Gravestone integration active — listening for Created/Collected/Broken events.");
    }
}
