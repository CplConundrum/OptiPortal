package com.optiportal.ui;

import java.util.List;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.optiportal.OptiPortal;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;

public class OptiPortalUIPage extends InteractiveCustomUIPage<OptiPortalUIPage.Data> {

    private static final String UI_FILE = "OptiPortalUI.ui";

    private final OptiPortal plugin;

    public OptiPortalUIPage(PlayerRef playerRef, OptiPortal plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.plugin = plugin;
    }

    public static class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec
            .builder(Data.class, Data::new)
            .build();
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder evt,
                      Store<EntityStore> store) {
        cmd.append(UI_FILE);
        render(cmd);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        // no-op
    }

    private void render(UICommandBuilder cmd) {
        CacheManager      cache = plugin.getCacheManager();
        PluginConfig      cfg   = plugin.getPluginConfig();
        List<PortalEntry> zones = plugin.getStorage().loadAll();

        int hot = 0, warm = 0, cold = 0, unvisited = 0;
        double totalRam = 0;
        double totalActualRam = 0;

        // Set column headers
        cmd.set("#HdrTier.Text",   "TIER");
        cmd.set("#HdrName.Text",   "NAME");
        cmd.set("#HdrType.Text",   "TYPE");
        cmd.set("#HdrStrat.Text",  "STRAT");
        cmd.set("#HdrRadius.Text", "RADIUS");
        cmd.set("#HdrRam.Text",    "EST RAM");
        cmd.set("#HdrRamMarginal.Text", "ACTUAL RAM");
        cmd.set("#HdrPreload.Text", "PRELOAD");
        cmd.set("#HdrTTL.Text",    "TTL");
        cmd.set("#HdrStatus.Text", "STATUS");

        // Build each column as a separate multi-line string
        StringBuilder colTier   = new StringBuilder();
        StringBuilder colName   = new StringBuilder();
        StringBuilder colType   = new StringBuilder();
        StringBuilder colStrat  = new StringBuilder();
        StringBuilder colRadius = new StringBuilder();
        StringBuilder colRam    = new StringBuilder();
        StringBuilder colRamMarginal = new StringBuilder();
        StringBuilder colPreload = new StringBuilder();
        StringBuilder colTTL    = new StringBuilder();
        StringBuilder colStatus = new StringBuilder();

        for (PortalEntry z : zones) {
            CacheTier tier   = cache.getZoneTier(z.getId());
            int       radius = z.getWarmRadius() > 0 ? z.getWarmRadius()
                    : (z.getStrategy() == WarmStrategy.WARM
                        ? cfg.getDefaultWarmRadius() : cfg.getPredictiveRadius());

            switch (tier) {
                case HOT       -> hot++;
                case WARM      -> warm++;
                case COLD      -> cold++;
                case UNVISITED -> unvisited++;
                default        -> {}
            }
            totalRam += z.getRamEstimatedMB();
            totalActualRam += z.getRamMarginalMB();

            String tierLabel = switch (tier) {
                case HOT        -> "[HOT]";
                case WARM       -> "[WARM]";
                case COLD       -> "[COLD]";
                case REBUILDING -> "[REBUILD]";
                case UNVISITED  -> "[--]";
                default         -> "[-]";
            };

            String statusLabel = switch (tier) {
                case HOT        -> "Active";
                case WARM       -> "Warm";
                case COLD       -> "Cold";
                case REBUILDING -> "Rebuilding";
                case UNVISITED  -> "Unvisited";
                default         -> "Unknown";
            };

            // Entry type label
            String typeLabel = switch (z.getType()) {
                case PORTAL -> "PORTAL";
                case BED    -> "BED";
                case DEATH  -> "DEATH";
                case MANUAL -> "MANUAL";
            };

            // Strategy label
            String stratLabel = z.getStrategy() == WarmStrategy.WARM ? "WARM" : "PRED";

            // RAM marginal info
            String ramMarginal = z.getRamMarginalMB() > 0
                    ? String.format("+%.2f MB", z.getRamMarginalMB()) : "--";

            // Preload count
            String preload = z.getPreloadCount() > 0 ? String.valueOf(z.getPreloadCount()) : "--";

            // Cache TTL info
            String ttl = z.getCacheTTLDays() != null && z.getCacheTTLDays() > 0
                    ? z.getCacheTTLDays() + "d" : "--";

            colTier.append(tierLabel).append("\n");
            colName.append(z.getId()).append("\n");
            colType.append(typeLabel).append("\n");
            colStrat.append(stratLabel).append("\n");
            colRadius.append("r=").append(radius).append("\n");
            colRam.append(z.getRamEstimatedMB() > 0
                    ? String.format("%.2f MB", z.getRamEstimatedMB()) : "--").append("\n");
            colRamMarginal.append(ramMarginal).append("\n");
            colPreload.append(preload).append("\n");
            colTTL.append(ttl).append("\n");
            colStatus.append(statusLabel).append("\n");
        }

        if (zones.isEmpty()) {
            colName.append("No zones registered.");
        }

        cmd.set("#ColTier.Text",   colTier.toString().trim());
        cmd.set("#ColName.Text",   colName.toString().trim());
        cmd.set("#ColType.Text",   colType.toString().trim());
        cmd.set("#ColStrat.Text",  colStrat.toString().trim());
        cmd.set("#ColRadius.Text", colRadius.toString().trim());
        cmd.set("#ColRam.Text",    colRam.toString().trim());
        cmd.set("#ColRamMarginal.Text", colRamMarginal.toString().trim());
        cmd.set("#ColPreload.Text", colPreload.toString().trim());
        cmd.set("#ColTTL.Text",    colTTL.toString().trim());
        cmd.set("#ColStatus.Text", colStatus.toString().trim());

        cmd.set("#CommandRef1.Text",
                "/preload list   /preload strategy <id> <WARM|PRED>   /preload shape <id> <ELLIPSOID|CYLINDER|BOX>   " +
                "/preload radius <id> <n>   /preload radiusxz <id> <rx> <rz>   /preload setwarm <id> [r]   /preload unsetwarm <id>");
        cmd.set("#CommandRef2.Text",
                "/preload ram   /preload refresh warps   /preload reload   " +
                "/preload migrate <backend>   /preload backup <list|restore <date>>   /preload help");

        cmd.set("#WarpStats.Text", String.format(
                "HOT %d   WARM %d   COLD %d   Unvisited %d   |   RAM Est %.1f MB   Actual %.1f MB   Shared %d chunks",
                hot, warm, cold, unvisited, totalRam, totalActualRam, cache.getTotalSharedChunks()));
        cmd.set("#StorageBackend.Text", "Storage: " + cfg.getBackend().toUpperCase());
    }

    public static void openFor(Player player, PlayerRef playerRef, OptiPortal plugin) {
        World world = plugin.getChunkPreloader().getWorldRegistry().getAnyWorld();
        if (world == null) return;
        Ref<EntityStore>   ref   = playerRef.getReference();
        Store<EntityStore> store = world.getEntityStore().getStore();
        player.getPageManager().openCustomPage(ref, store, new OptiPortalUIPage(playerRef, plugin));
    }
}
