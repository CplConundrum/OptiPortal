package com.optiportal.systems;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.optiportal.player.DeathLocationTracker;

import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * ECS system for tracking player death locations for OptiPortal cache management.
 *
 * <p><b>DORMANT: This system is intentionally not wired into startup.</b>
 * Death location tracking is already handled by {@link com.optiportal.integrations.GravestoneIntegration},
 * which registers for GravestoneCreatedEvent and calls {@link com.optiportal.player.DeathLocationTracker#onPlayerDeath}.
 * This ECS-based approach would duplicate that flow and is therefore left dormant.
 *
 * <p>Registered via {@code EntityStore.REGISTRY.registerSystem()} and listens for
 * the {@code DeathComponent} added event (triggered on player death). When a player
 * dies, this system captures the death location and triggers OptiPortal's death
 * location tracker to pre-load the chunk for respawn caching.
 *
 * <p>Pattern confirmed from Hytale's {@code PlayerDeathMarker} implementation:
 * <pre>
 * {@code
 * public void onComponentAdded(
 *     @Nonnull Ref<EntityStore> ref,
 *     @Nonnull DeathComponent component,
 *     @Nonnull Store<EntityStore> store,
 *     @Nonnull CommandBuffer<EntityStore> commandBuffer
 * ) {
 *     Player playerComponent = commandBuffer.getComponent(ref, Player.getComponentType());
 *     TransformComponent transformComponent = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
 *     Vector3d position = transformComponent.getPosition();
 *     World world = commandBuffer.getExternalData().getWorld();
 *     String worldName = world.getName();
 * }
 * </pre>
 */
public class PlayerDeathSystem extends DeathSystems.OnDeathSystem {

    private final DeathLocationTracker deathLocationTracker;

    public PlayerDeathSystem(DeathLocationTracker deathLocationTracker) {
        this.deathLocationTracker = deathLocationTracker;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        // Only track player deaths
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(
        @Nonnull com.hypixel.hytale.component.Ref<EntityStore> ref,
        @Nonnull DeathComponent component,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // Only process player entities
        if (commandBuffer.getComponent(ref, Player.getComponentType()) == null) {
            return;
        }

        // UUID lives on PlayerRef, not on the deprecated Entity base
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }

        Vector3d position = transform.getPosition();
        String worldName = commandBuffer.getExternalData().getWorld().getName();
        UUID playerUuid = playerRef.getUuid();

        deathLocationTracker.onPlayerDeath(playerUuid, position.x, position.y, position.z, worldName);
    }
}
