package com.optiportal.integrations;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.optiportal.OptiPortal;
import com.optiportal.config.PluginConfig;
import com.optiportal.player.DeathLocationTracker;
import com.optiportal.preload.WorldRegistry;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Optional integration for BetterGravestone.
 *
 * BetterGravestone does not expose the same public events as Zurku's Gravestones plugin,
 * so this integration hooks player deaths through the ECS death system and periodically
 * checks the gravestone block/component to know when the tracked death location can be released.
 */
public class BetterGravestoneWatcher {

    private static final Logger LOG = Logger.getLogger("OptiPortal");
    private static final String OWNER_COMPONENT_CLASS =
            "com.mcodelogic.gravestone.component.GravestoneOwnerData";
    private static final String OWNER_COMPONENT_TYPE_METHOD = "getComponentType";
    private static final String OWNER_METHOD = "getOwner";
    private static final String ITEMS_METHOD = "getItems";

    private final OptiPortal plugin;
    private final PluginConfig config;
    private final DeathLocationTracker deathTracker;
    private final GravestoneIntegration gravestoneIntegration;
    private final WorldRegistry worldRegistry;
    private final ScheduledExecutorService executor;

    private final Map<UUID, TrackedGravestone> trackedGravestones = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final BetterGravestoneDeathSystem deathSystem = new BetterGravestoneDeathSystem();

    private ScheduledFuture<?> task;
    private boolean deathSystemRegistered;
    private int gravestoneBlockId = Integer.MIN_VALUE;
    private Method getComponentTypeMethod;
    private Method getOwnerMethod;
    private Method getItemsMethod;

    public BetterGravestoneWatcher(
            OptiPortal plugin,
            PluginConfig config,
            DeathLocationTracker deathTracker,
            GravestoneIntegration gravestoneIntegration,
            WorldRegistry worldRegistry,
            ScheduledExecutorService executor
    ) {
        this.plugin = plugin;
        this.config = config;
        this.deathTracker = deathTracker;
        this.gravestoneIntegration = gravestoneIntegration;
        this.worldRegistry = worldRegistry;
        this.executor = executor;
    }

    public void start() {
        if (!config.isBetterGravestoneIntegrationEnabled()) {
            LOG.info("[OptiPortal] BetterGravestone integration disabled.");
            return;
        }
        if (!isPluginAvailable()) {
            LOG.warning("[OptiPortal] BetterGravestone integration enabled, but plugin '" +
                    config.getBetterGravestonePluginId() + "' is not loaded. Integration will stay inactive.");
            return;
        }
        if (!resolveBetterGravestoneBindings()) {
            return;
        }

        stopped.set(false);

        if (!deathSystemRegistered) {
            plugin.getEntityStoreRegistry().registerSystem(deathSystem);
            deathSystemRegistered = true;
        }

        int interval = Math.max(1, config.getBetterGravestoneWatchIntervalSeconds());
        task = executor.scheduleAtFixedRate(this::validateTrackedGravestones, interval, interval, TimeUnit.SECONDS);
        LOG.info("[OptiPortal] BetterGravestone integration active — tracking gravestone lifecycle.");
    }

    public void stop() {
        if (stopped.compareAndSet(false, true) && task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private boolean resolveBetterGravestoneBindings() {
        gravestoneBlockId = BlockType.getAssetMap().getIndex(config.getBetterGravestoneBlockId());
        if (gravestoneBlockId == Integer.MIN_VALUE) {
            LOG.warning("[OptiPortal] BetterGravestone integration enabled, but block '" +
                    config.getBetterGravestoneBlockId() + "' is not registered. Integration will stay inactive.");
            return false;
        }

        try {
            Class<?> ownerComponentClass = Class.forName(
                    OWNER_COMPONENT_CLASS,
                    false,
                    getClass().getClassLoader()
            );
            getComponentTypeMethod = ownerComponentClass.getMethod(OWNER_COMPONENT_TYPE_METHOD);
            getOwnerMethod = ownerComponentClass.getMethod(OWNER_METHOD);
            getItemsMethod = ownerComponentClass.getMethod(ITEMS_METHOD);
            return true;
        } catch (ReflectiveOperationException e) {
            LOG.warning("[OptiPortal] BetterGravestone integration enabled, but owner component bindings were not found. Integration will stay inactive.");
            return false;
        }
    }

    private void validateTrackedGravestones() {
        if (stopped.get() || !config.isBetterGravestoneIntegrationEnabled() || !isPluginAvailable()) {
            return;
        }

        for (Map.Entry<UUID, TrackedGravestone> entry : trackedGravestones.entrySet()) {
            UUID playerId = entry.getKey();
            TrackedGravestone tracked = entry.getValue();
            World world = worldRegistry.getWorld(tracked.worldName());
            if (world == null) {
                continue;
            }

            long chunkIndex = ChunkUtil.indexChunk(tracked.x(), tracked.z());
            WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
            if (chunk == null) {
                continue;
            }

            if (chunk.getBlock(tracked.x(), tracked.y(), tracked.z()) != gravestoneBlockId) {
                releaseTrackedGravestone(playerId);
                continue;
            }

            if (isContainerEmptyOrMismatched(world, tracked, playerId)) {
                releaseTrackedGravestone(playerId);
            }
        }
    }

    private boolean isContainerEmptyOrMismatched(World world, TrackedGravestone tracked, UUID playerId) {
        try {
            Ref<ChunkStore> blockEntity = BlockModule.getBlockEntity(world, tracked.x(), tracked.y(), tracked.z());
            if (blockEntity == null || !blockEntity.isValid()) {
                return true;
            }

            @SuppressWarnings("rawtypes")
            Store blockStore = blockEntity.getStore();
            @SuppressWarnings("rawtypes")
            ComponentType componentType = (ComponentType) getComponentTypeMethod.invoke(null);
            Object ownerComponent = blockStore.getComponent(blockEntity, componentType);
            if (ownerComponent == null) {
                return true;
            }

            Object ownerValue = getOwnerMethod.invoke(ownerComponent);
            if (!playerId.toString().equals(ownerValue)) {
                return true;
            }

            Object itemsValue = getItemsMethod.invoke(ownerComponent);
            return !(itemsValue instanceof Map<?, ?> items) || items.isEmpty();
        } catch (Exception e) {
            LOG.fine(() -> "[OptiPortal] BetterGravestone validation failed for " + playerId + ": " + e.getMessage());
            return false;
        }
    }

    private void recordDeath(UUID playerId, double x, double y, double z, String worldName) {
        deathTracker.onPlayerDeath(playerId, x, y, z, worldName);
        gravestoneIntegration.markPendingRespawn(playerId);
        trackedGravestones.put(
                playerId,
                new TrackedGravestone(worldName, floorToBlock(x), floorToBlock(y), floorToBlock(z))
        );
    }

    private void releaseTrackedGravestone(UUID playerId) {
        trackedGravestones.remove(playerId);
        deathTracker.onGravestoneReleased(playerId);
    }

    private boolean isPluginAvailable() {
        String configuredId = config.getBetterGravestonePluginId();
        if (configuredId == null || configuredId.isBlank()) {
            return false;
        }

        try {
            if (configuredId.contains(":")) {
                return HytaleServer.get().getPluginManager().getPlugin(PluginIdentifier.fromString(configuredId)) != null;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }

        for (PluginBase plugin : HytaleServer.get().getPluginManager().getPlugins()) {
            if (plugin != null && configuredId.equals(plugin.getIdentifier().getName())) {
                return true;
            }
        }

        return false;
    }

    private static int floorToBlock(double value) {
        return (int) Math.floor(value);
    }

    private record TrackedGravestone(String worldName, int x, int y, int z) {
    }

    private final class BetterGravestoneDeathSystem extends DeathSystems.OnDeathSystem {

        @Override
        @Nonnull
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void onComponentAdded(
                @Nonnull Ref<EntityStore> ref,
                @Nonnull DeathComponent component,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            if (stopped.get() || !config.isBetterGravestoneIntegrationEnabled() || !isPluginAvailable()) {
                return;
            }

            Player player = commandBuffer.getComponent(ref, Player.getComponentType());
            PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
            TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
            if (player == null || playerRef == null || transform == null) {
                return;
            }

            Vector3d position = transform.getPosition();
            String worldName = commandBuffer.getExternalData().getWorld().getName();
            recordDeath(playerRef.getUuid(), position.x, position.y, position.z, worldName);
        }
    }
}
