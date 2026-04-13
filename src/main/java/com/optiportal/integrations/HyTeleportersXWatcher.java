package com.optiportal.integrations;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.storage.StorageBackend;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Watches HyTeleportersX's Teleporters.json and mirrors teleporter locations
 * into OptiPortal as predictive portal entries.
 */
public class HyTeleportersXWatcher {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final PluginConfig config;
    private final StorageBackend storage;
    @SuppressWarnings("unused")
    private final WarmZoneManager warmZoneManager;
    private final ScheduledExecutorService executor;
    private final Runnable onSyncComplete;
    private final Consumer<String> onPortalDeleted;

    private ScheduledFuture<?> task;
    private long lastModified = -1;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public HyTeleportersXWatcher(PluginConfig config, StorageBackend storage,
                                 WarmZoneManager warmZoneManager, ScheduledExecutorService executor,
                                 Runnable onSyncComplete, Consumer<String> onPortalDeleted) {
        this.config = config;
        this.storage = storage;
        this.warmZoneManager = warmZoneManager;
        this.executor = executor;
        this.onSyncComplete = onSyncComplete;
        this.onPortalDeleted = onPortalDeleted;
    }

    public void start() {
        if (!config.isHyTeleportersXIntegrationEnabled()) {
            LOG.info("[OptiPortal] HyTeleportersX integration disabled.");
            return;
        }
        if (!isPluginAvailable()) {
            LOG.warning("[OptiPortal] HyTeleportersX integration enabled, but plugin '" 
                    + config.getHyTeleportersXPluginId() + "' is not loaded. Integration will stay inactive.");
            return;
        }

        executor.submit(this::checkAndSync);

        if (config.isHyTeleportersXWatchForChanges()) {
            int interval = config.getHyTeleportersXWatchIntervalSeconds();
            task = executor.scheduleAtFixedRate(this::checkAndSync, interval, interval, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            LOG.fine(() -> "[OptiPortal] HyTeleportersXWatcher stopping...");
            if (task != null) {
                task.cancel(false);
                task = null;
            }
        }
    }

    public boolean isWatchingForChanges() {
        return config.isHyTeleportersXWatchForChanges() && task != null;
    }

    public String getSourcePath() {
        return config.getHyTeleportersXSourcePath();
    }

    public int getWatchInterval() {
        return config.isHyTeleportersXWatchForChanges() ? config.getHyTeleportersXWatchIntervalSeconds() : 0;
    }

    public int forceRefresh() {
        if (!config.isHyTeleportersXIntegrationEnabled() || !isPluginAvailable()) {
            return 0;
        }
        lastModified = -1;
        File file = new File(config.getHyTeleportersXSourcePath());
        if (!file.exists()) return 0;
        try {
            List<PortalEntry> teleporters = parseTeleportersFile(file);
            syncTeleporters(teleporters);
            return teleporters.size();
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Failed to sync HyTeleportersX Teleporters.json: " + e.getMessage());
            return 0;
        }
    }

    private void checkAndSync() {
        if (stopped.get() || !config.isHyTeleportersXIntegrationEnabled() || !isPluginAvailable()) {
            return;
        }

        File file = new File(config.getHyTeleportersXSourcePath());
        if (!file.exists()) return;

        long modified = file.lastModified();
        if (modified == lastModified) return;
        lastModified = modified;

        try {
            List<PortalEntry> teleporters = parseTeleportersFile(file);
            syncTeleporters(teleporters);
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Failed to sync HyTeleportersX Teleporters.json: " + e.getMessage());
        }
    }

    private List<PortalEntry> parseTeleportersFile(File file) throws IOException {
        List<PortalEntry> result = new ArrayList<>();
        try (Reader reader = new FileReader(file)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray teleporterArray = root.getAsJsonArray("Teleporters");
            if (teleporterArray == null) return result;

            for (JsonElement element : teleporterArray) {
                if (!element.isJsonObject()) continue;
                JsonObject teleporter = element.getAsJsonObject();

                String world = getString(teleporter, "World");
                JsonObject pos = teleporter.has("Pos") && teleporter.get("Pos").isJsonObject()
                        ? teleporter.getAsJsonObject("Pos")
                        : null;
                if (world == null || pos == null) continue;
                if (!pos.has("X") || !pos.has("Y") || !pos.has("Z")) continue;

                int x = pos.get("X").getAsInt();
                int y = pos.get("Y").getAsInt();
                int z = pos.get("Z").getAsInt();

                String remappedWorld = config.remapWorldName(world);
                String id = buildTeleporterId(remappedWorld, x, y, z);

                PortalEntry entry = new PortalEntry(id, remappedWorld, x, y, z, 0.0);
                entry.setStrategy(WarmStrategy.PREDICTIVE);
                entry.setCreator(getString(teleporter, "Owner"));
                entry.setCreationDate(readInstant(teleporter.get("PlacedAt")));
                entry.setNotes("Synced from HyTeleportersX Teleporters.json");
                result.add(entry);
            }
        }
        return result;
    }

    private void syncTeleporters(List<PortalEntry> incomingTeleporters) {
        // Load registry snapshot filtered to only HyTeleportersX-owned entries
        // by checking if the ID prefix matches the configured prefix.
        // Use watcher-owned helper to avoid full registry read + local filtering.
        Map<String, PortalEntry> existing = storage.loadEntriesWithIdPrefix(
                config.getHyTeleportersXIdPrefix()).stream()
                .collect(Collectors.toMap(PortalEntry::getId, e -> e));

        List<PortalEntry> changedEntries = new ArrayList<>();
        Set<String> incomingIds = new HashSet<>();

        for (PortalEntry incoming : incomingTeleporters) {
            incomingIds.add(incoming.getId());

            PortalEntry ex = existing.get(incoming.getId());
            if (ex == null) {
                changedEntries.add(incoming);
                LOG.info("[OptiPortal] HyTeleportersX teleporter registered: " + incoming.getId()
                        + " [" + incoming.getX() + ", " + incoming.getY() + ", " + incoming.getZ() + "]");
                continue;
            }

            boolean changed = false;
            if (ex.getX() != incoming.getX() || ex.getY() != incoming.getY() || ex.getZ() != incoming.getZ()
                    || !safeEquals(ex.getWorld(), incoming.getWorld())) {
                ex.setWorld(incoming.getWorld());
                ex.setX(incoming.getX());
                ex.setY(incoming.getY());
                ex.setZ(incoming.getZ());
                ex.setYaw(incoming.getYaw());
                changed = true;
            }
            if (incoming.getCreator() != null && !safeEquals(ex.getCreator(), incoming.getCreator())) {
                ex.setCreator(incoming.getCreator());
                changed = true;
            }
            if (incoming.getCreationDate() != null && !incoming.getCreationDate().equals(ex.getCreationDate())) {
                ex.setCreationDate(incoming.getCreationDate());
                changed = true;
            }
            if (ex.getNotes() == null || ex.getNotes().isEmpty()) {
                ex.setNotes(incoming.getNotes());
                changed = true;
            }
            if (changed) {
                changedEntries.add(ex);
            }
        }

        if (!changedEntries.isEmpty()) {
            storage.saveAll(changedEntries);
        }

        // Remove teleporters that are in storage but no longer exist in file.
        // Only delete entries owned by this watcher (by prefix match)
        for (String existingId : existing.keySet()) {
            if (!incomingIds.contains(existingId)) {
                PortalEntry ex = existing.get(existingId);
                if (ex != null) {
                    onPortalDeleted.accept(existingId);
                    storage.delete(existingId);
                    LOG.info("[OptiPortal] HyTeleportersX teleporter removed: " + existingId);
                }
            }
        }

        onSyncComplete.run();
    }

    private String buildTeleporterId(String world, int x, int y, int z) {
        return config.getHyTeleportersXIdPrefix() + world + ":" + x + ":" + y + ":" + z;
    }

    private boolean isPluginAvailable() {
        String configuredId = config.getHyTeleportersXPluginId();
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

    private static String getString(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }

    private static Instant readInstant(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        try {
            return Instant.ofEpochMilli(element.getAsLong());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
