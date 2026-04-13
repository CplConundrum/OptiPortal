package com.optiportal.ui;

import java.util.List;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
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
import javax.annotation.Nonnull;

public class OptiPortalUIPage extends InteractiveCustomUIPage<OptiPortalUIPage.PageData> {

    private final OptiPortal plugin;
    private String selectedZoneId = null;

    public OptiPortalUIPage(PlayerRef playerRef, OptiPortal plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, PageData.CODEC);
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Event data

    public enum Action { Select, Flush, Delete }

    public static class PageData {
        public static final BuilderCodec<PageData> CODEC = BuilderCodec
            .builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Action", new EnumCodec<>(Action.class)), (o, v) -> o.action = v, o -> o.action).add()
            .append(new KeyedCodec<>("ZoneId", Codec.STRING),                  (o, v) -> o.zoneId = v, o -> o.zoneId).add()
            .build();

        public Action action;
        public String zoneId;
    }

    // -------------------------------------------------------------------------
    // Build (initial render) — uses Pages/PluginListPage.ui as the root container.
    // This is a builtin Hytale page the client already has; we repurpose its elements:
    //   #PluginList         → zone list (left panel)
    //   #PluginName         → zone ID title (right panel header)
    //   #PluginIdentifier   → tier/strategy summary
    //   #PluginVersion      → radius/RAM/preload info
    //   #PluginDescription  → detail text
    //   #DescriptiveOnlyOption → cleared and reused for Flush/Delete buttons

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder evt, @Nonnull Store<EntityStore> store) {
        cmd.append("Pages/PluginListPage.ui");
        List<PortalEntry> zones = plugin.getStorage().loadAllCached();
        renderZoneList(cmd, evt, zones);
        renderDetailPanel(cmd, evt);
    }

    // -------------------------------------------------------------------------
    // Event handling

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull PageData data) {
        switch (data.action) {
            case Select -> {
                selectedZoneId = data.zoneId;
                refresh();
            }
            case Flush -> {
                plugin.getCacheManager().releaseZoneChunks(data.zoneId);
                plugin.getCacheManager().setZoneTier(data.zoneId, CacheTier.COLD);
                refresh();
            }
            case Delete -> {
                String id = data.zoneId;
                plugin.getCacheManager().deregisterAllChunks(id);
                plugin.getCacheManager().removeTierEntry(id);
                var pcl = plugin.getPortalChunkListener();
                if (pcl != null) pcl.removeFromIndex(id);
                var portalLinkRegistry = plugin.getPortalLinkRegistry();
                if (portalLinkRegistry != null) portalLinkRegistry.removeLink(id);
                plugin.getTeleportInterceptor().onPortalDeleted(id);
                plugin.getStorage().delete(id);
                plugin.getTeleportInterceptor().refreshPortalCache();
                selectedZoneId = null;
                refresh();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Refresh (subsequent updates — does NOT re-append the root page)

    private void refresh() {
        List<PortalEntry> zones = plugin.getStorage().loadAllCached();
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder   evt = new UIEventBuilder();
        renderZoneList(cmd, evt, zones);
        renderDetailPanel(cmd, evt);
        sendUpdate(cmd, evt, false);
    }

    // -------------------------------------------------------------------------
    // Render helpers

    private void renderZoneList(UICommandBuilder cmd, UIEventBuilder evt, List<PortalEntry> zones) {
        CacheManager cache = plugin.getCacheManager();
        cmd.clear("#PluginList");

        if (zones.isEmpty()) {
            cmd.appendInline("#PluginList",
                    "Label { Style: (FontSize: 13, Alignment: Center); Anchor: (Horizontal: 6, Top: 8, Bottom: 8); }");
            cmd.set("#PluginList[0].Text", "No zones registered.");
            return;
        }

        for (int i = 0; i < zones.size(); i++) {
            PortalEntry z    = zones.get(i);
            CacheTier   tier = cache.getZoneTier(z.getId());
            boolean     sel  = z.getId().equals(selectedZoneId);
            String      text = tierLabel(tier, z, cache) + "  " + (sel ? "> " + z.getId() : z.getId());

            // BasicTextButton is a root button — text via .TextSpans, binding directly on the index element
            cmd.append("#PluginList", "Pages/BasicTextButton.ui");
            cmd.set("#PluginList[" + i + "].TextSpans", Message.raw(text));
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#PluginList[" + i + "]",
                    EventData.of("Action", Action.Select.name()).append("ZoneId", z.getId()), false);
        }
    }

    private void renderDetailPanel(UICommandBuilder cmd, UIEventBuilder evt) {
        if (selectedZoneId == null) {
            cmd.set("#PluginName.Text", "OptiPortal  —  Zone Manager");
            cmd.set("#PluginIdentifier.Text", "Select a zone from the list to view details.");
            cmd.set("#PluginVersion.Text", "");
            cmd.set("#PluginDescription.Text",
                    "/preload list\n" +
                    "/preload setwarm <id> [radius]\n" +
                    "/preload unsetwarm <id>\n" +
                    "/preload strategy <id> <WARM|PREDICTIVE>\n" +
                    "/preload radius <id> <X> [Z]\n" +
                    "/preload activation <id> <dist|reset>\n" +
                    "/preload shape <id> <ELLIPSOID|CYLINDER|BOX>\n" +
                    "/preload ttl <id> <days|-1|reset>\n" +
                    "/preload preload <id>\n" +
                    "/preload zone <id>\n" +
                    "/preload delete <id>\n" +
                    "/preload flush\n" +
                    "/preload ram\n" +
                    "/preload status\n" +
                    "/preload links [remove <id>|clear-pending]\n" +
                    "/preload refresh warps\n" +
                    "/preload reload\n" +
                    "/preload migrate <JSON|SQLITE|H2|MYSQL>\n" +
                    "/preload backup <list|restore <date>>\n" +
                    "/preload help");
            cmd.clear("#DescriptiveOnlyOption");
            cmd.appendInline("#DescriptiveOnlyOption",
                    "Label { Style: (FontSize: 11); FlexWeight: 1; }");
            cmd.set("#DescriptiveOnlyOption[0].Text", overallStats());
            return;
        }

        var found = plugin.getStorage().loadById(selectedZoneId);
        if (found.isEmpty()) {
            cmd.set("#PluginName.Text", "Zone not found");
            cmd.set("#PluginIdentifier.Text", selectedZoneId);
            cmd.set("#PluginVersion.Text", "");
            cmd.set("#PluginDescription.Text", "The selected zone no longer exists.");
            cmd.clear("#DescriptiveOnlyOption");
            selectedZoneId = null;
            return;
        }

        PortalEntry  entry = found.get();
        CacheManager cache = plugin.getCacheManager();
        PluginConfig cfg   = plugin.getPluginConfig();
        CacheTier    tier  = cache.getZoneTier(entry.getId());
        int radius = entry.getWarmRadius() > 0 ? entry.getWarmRadius()
                : (entry.getStrategy() == WarmStrategy.WARM
                        ? cfg.getDefaultWarmRadius() : cfg.getPredictiveRadius());

        cmd.set("#PluginName.Text", entry.getId());
        cmd.set("#PluginIdentifier.Text", String.format("%s  |  %s  |  Type: %s  |  World: %s",
                tierLabel(tier),
                entry.getStrategy() == WarmStrategy.WARM ? "WARM" : "PRED",
                entry.getType().name(),
                entry.getWorld() != null ? entry.getWorld() : "--"));
        cmd.set("#PluginVersion.Text", String.format(
                "Radius: %s  |  RAM: %s  |  Preloads: %d  |  Shared chunks: %d",
                radius > 0 ? String.valueOf(radius) : "--",
                entry.getRamMarginalMB() > 0 ? String.format("%.2f MB", entry.getRamMarginalMB()) : "--",
                entry.getPreloadCount(),
                cache.getTotalSharedChunks()));

        StringBuilder detail = new StringBuilder();
        if (entry.getWarmRadiusX() != null)
            detail.append("Radius X override: ").append(entry.getWarmRadiusX()).append("\n");
        if (entry.getWarmRadiusZ() != null)
            detail.append("Radius Z override: ").append(entry.getWarmRadiusZ()).append("\n");
        if (entry.getActivationDistanceHorizontal() != null)
            detail.append("Activation dist: ").append(entry.getActivationDistanceHorizontal()).append("\n");
        if (entry.getCacheTTLDays() != null)
            detail.append("TTL (days): ").append(entry.getCacheTTLDays()).append("\n");
        if (entry.getActivationShape() != null)
            detail.append("Shape: ").append(entry.getActivationShape()).append("\n");
        if (detail.length() == 0) detail.append("No overrides set.\n");
        detail.append("Residency: ").append(residencyLabel(entry, tier, cache)).append("\n");
        cmd.set("#PluginDescription.Text", detail.toString().trim());

        // Bottom bar: stats label (FlexWeight: 1 pushes it left) + Flush + Delete buttons
        cmd.clear("#DescriptiveOnlyOption");
        cmd.appendInline("#DescriptiveOnlyOption",
                "Label { Style: (FontSize: 11); FlexWeight: 1; }");
        cmd.set("#DescriptiveOnlyOption[0].Text", overallStats());
        cmd.append("#DescriptiveOnlyOption", "Pages/BasicTextButton.ui");
        cmd.set("#DescriptiveOnlyOption[1].TextSpans", Message.raw("[ Flush ]"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#DescriptiveOnlyOption[1]",
                EventData.of("Action", Action.Flush.name()).append("ZoneId", entry.getId()), false);
        cmd.append("#DescriptiveOnlyOption", "Pages/BasicTextButton.ui");
        cmd.set("#DescriptiveOnlyOption[2].TextSpans", Message.raw("[ Delete ]"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#DescriptiveOnlyOption[2]",
                EventData.of("Action", Action.Delete.name()).append("ZoneId", entry.getId()), false);
    }

    // -------------------------------------------------------------------------
    // Utilities

    private String overallStats() {
        CacheManager cache = plugin.getCacheManager();
        PluginConfig cfg   = plugin.getPluginConfig();
        List<PortalEntry> all = plugin.getStorage().loadAllCached();
        int hot = 0, warm = 0, cold = 0, unvisited = 0;
        int verifiedHot = 0;
        double totalRam = 0;
        for (PortalEntry z : all) {
            CacheTier tier = cache.getZoneTier(z.getId());
            switch (tier) {
                case HOT        -> {
                    hot++;
                    if (cache.getOwnedChunkCount(z.getId()) >= expectedChunkCount(z)) verifiedHot++;
                }
                case WARM       -> warm++;
                case COLD       -> cold++;
                case UNVISITED  -> unvisited++;
                default         -> {}
            }
            totalRam += z.getRamMarginalMB();
        }
        return String.format("HOT %d (%d verified)  WARM %d  COLD %d  Unvisited %d  |  RAM %.1f MB  Shared %d chunks  |  Storage: %s",
                hot, verifiedHot, warm, cold, unvisited, totalRam,
                cache.getTotalSharedChunks(), cfg.getBackend().toUpperCase());
    }

    private String tierLabel(CacheTier tier) {
        return switch (tier) {
            case HOT        -> "[HOT]";
            case WARM       -> "[WARM]";
            case COLD       -> "[COLD]";
            case REBUILDING -> "[REBUILD]";
            case UNVISITED  -> "[--]";
            default         -> "[-]";
        };
    }

    private String tierLabel(CacheTier tier, PortalEntry entry, CacheManager cache) {
        if (tier != CacheTier.HOT) return tierLabel(tier);
        int owned = cache.getOwnedChunkCount(entry.getId());
        int expected = expectedChunkCount(entry);
        if (owned >= expected) return "[HOT:OK]";
        if (owned > 0) return "[HOT:PART]";
        return "[HOT:?]";
    }

    private String residencyLabel(PortalEntry entry, CacheTier tier, CacheManager cache) {
        if (tier != CacheTier.HOT) {
            return "not asserted while tier is " + tier;
        }
        int owned = cache.getOwnedChunkCount(entry.getId());
        int expected = expectedChunkCount(entry);
        if (owned >= expected) {
            return "verified ownership pins (" + owned + "/" + expected + ")";
        }
        if (owned > 0) {
            return "partial ownership pins (" + owned + "/" + expected + ")";
        }
        return "assumed only; no ownership pins recorded";
    }

    private int expectedChunkCount(PortalEntry entry) {
        int radiusX = resolveRadiusX(entry);
        int radiusZ = resolveRadiusZ(entry);
        return (2 * radiusX + 1) * (2 * radiusZ + 1);
    }

    private int resolveRadiusX(PortalEntry entry) {
        if (entry.getWarmRadiusX() != null) return entry.getWarmRadiusX();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return entry.getStrategy() == WarmStrategy.WARM
                ? plugin.getPluginConfig().getDefaultWarmRadius()
                : plugin.getPluginConfig().getPredictiveRadius();
    }

    private int resolveRadiusZ(PortalEntry entry) {
        if (entry.getWarmRadiusZ() != null) return entry.getWarmRadiusZ();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return entry.getStrategy() == WarmStrategy.WARM
                ? plugin.getPluginConfig().getDefaultWarmRadius()
                : plugin.getPluginConfig().getPredictiveRadius();
    }

    // -------------------------------------------------------------------------
    // Static open helper

    public static void openFor(Player player, PlayerRef playerRef, OptiPortal plugin) {
        World world = plugin.getChunkPreloader().getWorldRegistry().getWorldForPlayer(playerRef);
        if (world == null) world = plugin.getChunkPreloader().getWorldRegistry().getAnyWorld();
        if (world == null) return;
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) return;
        Store<EntityStore> store = world.getEntityStore().getStore();
        player.getPageManager().openCustomPage(ref, store,
                new OptiPortalUIPage(playerRef, plugin));
    }
}
