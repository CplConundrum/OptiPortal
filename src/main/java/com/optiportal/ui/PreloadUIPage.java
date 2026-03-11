package com.optiportal.ui;

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

import java.util.List;

public class PreloadUIPage extends InteractiveCustomUIPage<PreloadUIPage.Data> {

    private static final String UI_FILE = "PreloadUI.ui";

    private final OptiPortal plugin;

    public PreloadUIPage(PlayerRef playerRef, OptiPortal plugin) {
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

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-10s  %-24s  %-5s  %-6s  %s%n",
                "Tier", "Name", "Strat", "Radius", "RAM"));
        sb.append("─".repeat(60)).append("\n");

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
            String tierLabel = switch (tier) {
                case HOT        -> "[HOT]    ";
                case WARM       -> "[WARM]   ";
                case COLD       -> "[COLD]   ";
                case REBUILDING -> "[REBUILD]";
                case UNVISITED  -> "[--]     ";
                default         -> "[-]      ";
            };
            sb.append(String.format("%-10s  %-24s  %-5s  r=%-4d  %.0f MB%n",
                    tierLabel, z.getId(),
                    z.getStrategy() == WarmStrategy.WARM ? "WARM" : "PRED",
                    radius, z.getRamEstimatedMB()));
        }

        if (zones.isEmpty()) sb.append("  No zones registered.\n");

        cmd.set("#WarpTableContent.Text", sb.toString().trim());
        cmd.set("#WarpStats.Text", String.format(
                "HOT %d  WARM %d  COLD %d  Unvisited %d  |  RAM Est %.1f MB  Shared %d chunks",
                hot, warm, cold, unvisited, totalRam, cache.getTotalSharedChunks()));
        cmd.set("#StorageBackend.Text", "Storage: " + cfg.getBackend().toUpperCase());
    }

    public static void openFor(Player player, PlayerRef playerRef, OptiPortal plugin) {
        World world = plugin.getChunkPreloader().getWorldRegistry().getAnyWorld();
        if (world == null) return;
        Ref<EntityStore>   ref   = playerRef.getReference();
        Store<EntityStore> store = world.getEntityStore().getStore();
        player.getPageManager().openCustomPage(ref, store, new PreloadUIPage(playerRef, plugin));
    }
}