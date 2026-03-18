package com.optiportal.integrations;

import com.google.gson.*;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.storage.StorageBackend;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Watches warps.json for changes and syncs new/removed/updated warps
 * into the plugin storage backend automatically.
 *
 * On first run: imports all warps from warps.json into storage.
 * On change: diffs against current registry, adds/removes/updates as needed.
 * Runs fully async - world thread is never touched.
 */
public class WarpFileWatcher {

    private final PluginConfig config;
    private final StorageBackend storage;
    private final WarmZoneManager warmZoneManager;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> task;
    private long lastModified = -1;

    // Track known warp IDs for diff detection
    private final Set<String> knownWarpIds = ConcurrentHashMap.newKeySet();

    public WarpFileWatcher(PluginConfig config, StorageBackend storage,
                           WarmZoneManager warmZoneManager, ScheduledExecutorService executor) {
        this.config = config;
        this.storage = storage;
        this.warmZoneManager = warmZoneManager;
        this.executor = executor;
    }

    public void start() {
        // Initial import
        executor.submit(this::checkAndSync);

        if (config.isWarpsWatchForChanges()) {
            int interval = config.getWarpsWatchIntervalSeconds();
            task = executor.scheduleAtFixedRate(this::checkAndSync, interval, interval, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        if (task != null) task.cancel(false);
    }

    /** Force immediate re-read (called by /preload refresh warps). Returns count of warps synced. */
    public int forceRefresh() {
        lastModified = -1; // force re-read even if file unchanged
        File warpsFile = new File(config.getWarpsSourcePath());
        if (!warpsFile.exists()) return 0;
        try {
            List<PortalEntry> warps = parseWarpsFile(warpsFile);
            syncWarps(warps);
            return warps.size();
        } catch (Exception e) {
            System.err.println("[OptiPortal] Failed to sync warps.json: " + e.getMessage());
            return 0;
        }
    }

    private void checkAndSync() {
        File warpsFile = new File(config.getWarpsSourcePath());
        if (!warpsFile.exists()) return;

        long modified = warpsFile.lastModified();
        if (modified == lastModified) return; // No change
        lastModified = modified;

        try {
            List<PortalEntry> warps = parseWarpsFile(warpsFile);
            syncWarps(warps);
        } catch (Exception e) {
            System.err.println("[OptiPortal] Failed to sync warps.json: " + e.getMessage());
        }
    }

    private List<PortalEntry> parseWarpsFile(File file) throws IOException {
        List<PortalEntry> result = new ArrayList<>();
        try (Reader reader = new FileReader(file)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray warpsArray = root.getAsJsonArray("Warps");
            if (warpsArray == null) return result;

            for (JsonElement element : warpsArray) {
                JsonObject warp = element.getAsJsonObject();
                String id = warp.get(config.getWarpsIdField()).getAsString();
                String world = config.remapWorldName(warp.get(config.getWarpsWorldField()).getAsString());
                double x = warp.get(config.getWarpsXField()).getAsDouble();
                double y = warp.get(config.getWarpsYField()).getAsDouble();
                double z = warp.get(config.getWarpsZField()).getAsDouble();
                double yaw = warp.get(config.getWarpsYawField()).getAsDouble();

                PortalEntry entry = new PortalEntry(id, world, x, y, z, yaw);
                entry.setCreator(warp.has("Creator") ? warp.get("Creator").getAsString() : "");
                if (warp.has("CreationDate")) {
                    try { entry.setCreationDate(Instant.parse(warp.get("CreationDate").getAsString())); }
                    catch (Exception ignored) {}
                }
                result.add(entry);
            }
        }
        return result;
    }

    private void syncWarps(List<PortalEntry> incomingWarps) {
        Set<String> incomingIds = new HashSet<>();

        for (PortalEntry incoming : incomingWarps) {
            incomingIds.add(incoming.getId());

            Optional<PortalEntry> existing = storage.loadById(incoming.getId());
            if (existing.isEmpty()) {
                // New warp - register with default strategy
                incoming.setStrategy(WarmStrategy.PREDICTIVE);
                storage.save(incoming);
                System.out.println("[OptiPortal] New warp registered: " + incoming.getId()
                        + " [" + incoming.getX() + ", " + incoming.getY() + ", " + incoming.getZ() + "] → PREDICTIVE");
            } else {
                // Check if coordinates changed
                PortalEntry ex = existing.get();
                if (ex.getX() != incoming.getX() || ex.getY() != incoming.getY() || ex.getZ() != incoming.getZ()) {
                    // Coordinates moved - purge old cache, update
                    ex.setX(incoming.getX());
                    ex.setY(incoming.getY());
                    ex.setZ(incoming.getZ());
                    ex.setYaw(incoming.getYaw());
                    storage.save(ex);
                    System.out.println("[OptiPortal] Warp moved, cache purged: " + incoming.getId());
                }
            }
        }

        // Remove warps that no longer exist in file
        for (String knownId : new HashSet<>(knownWarpIds)) {
            if (!incomingIds.contains(knownId)) {
                storage.delete(knownId);
                System.out.println("[OptiPortal] Warp removed: " + knownId);
            }
        }

        knownWarpIds.clear();
        knownWarpIds.addAll(incomingIds);
    }
}
