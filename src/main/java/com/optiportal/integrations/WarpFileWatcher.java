package com.optiportal.integrations;

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
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.builtin.teleport.TeleportPlugin;
import com.hypixel.hytale.builtin.teleport.Warp;
import com.hypixel.hytale.math.vector.Transform;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.preload.WarmZoneManager;
import com.optiportal.storage.StorageBackend;

/**
 * Watches warps.json for changes and syncs new/removed/updated warps
 * into the plugin storage backend automatically.
 *
 * On first run: imports all warps from warps.json into storage.
 * On change: diffs against current registry, adds/removes/updates as needed.
 * Runs fully async - world thread is never touched.
 */
public class WarpFileWatcher {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final PluginConfig config;
    private final StorageBackend storage;
    private final WarmZoneManager warmZoneManager;
    private final ScheduledExecutorService executor;
    private final Runnable onSyncComplete;
    
    private ScheduledFuture<?> task;
    private long lastModified = -1;
    
    public WarpFileWatcher(PluginConfig config, StorageBackend storage,
                           WarmZoneManager warmZoneManager, ScheduledExecutorService executor,
                           Runnable onSyncComplete) {
        this.config = config;
        this.storage = storage;
        this.warmZoneManager = warmZoneManager;
        this.executor = executor;
        this.onSyncComplete = onSyncComplete;
    }
    
    /** Constructor for backwards compatibility - delegates with empty callback. */
    public WarpFileWatcher(PluginConfig config, StorageBackend storage,
                           WarmZoneManager warmZoneManager, ScheduledExecutorService executor) {
        this(config, storage, warmZoneManager, executor, () -> {});
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
        // Try native path first
        int nativeSynced = syncNativeWarps();
        if (nativeSynced >= 0) {
            return nativeSynced;
        }

        // Fallback: file-based
        lastModified = -1; // force re-read even if file unchanged
        File warpsFile = new File(config.getWarpsSourcePath());
        if (!warpsFile.exists()) return 0;
        try {
            List<PortalEntry> warps = parseWarpsFile(warpsFile);
            syncWarps(warps);
            return warps.size();
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Failed to sync warps.json: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Sync warps from the native TeleportPlugin in-memory map.
     * Called on startup and optionally on each poll interval.
     * Returns the number of warps synced, or -1 if TeleportPlugin is not ready.
     *
     * Replaces parseWarpsFile/syncWarps for the native path. The file-based
     * path remains as fallback for servers that don't use TeleportPlugin.
     *
     * IMPORTANT: TeleportPlugin.get() may return null if the plugin is not
     * installed. isWarpsLoaded() may return false on first call at startup.
     * We skip silently and let the next scheduled poll retry.
     */
    private int syncNativeWarps() {
        TeleportPlugin tp = TeleportPlugin.get();
        if (tp == null || !tp.isWarpsLoaded()) {
            return -1; // not ready yet — will retry on next poll
        }

        java.util.Map<String, Warp> nativeWarps = tp.getWarps();
        if (nativeWarps == null) return 0;

        // Build list of PortalEntry from native Warp objects
        List<PortalEntry> incoming = new ArrayList<>();
        for (Warp warp : nativeWarps.values()) {
            Transform transform = warp.getTransform();
            if (transform == null || transform.getPosition() == null) continue;

            com.hypixel.hytale.math.vector.Vector3d pos = transform.getPosition();
            float yaw = 0.0f;  // Transform.getYaw() not available in this Hytale build; yaw not critical for preloading

            String mappedWorld = config.remapWorldName(warp.getWorld());
            PortalEntry entry = new PortalEntry(warp.getId(), mappedWorld, pos.x, pos.y, pos.z, yaw);
            entry.setCreator(warp.getCreator() != null ? warp.getCreator() : "");
            if (warp.getCreationDate() != null) {
                entry.setCreationDate(warp.getCreationDate());
            }
            incoming.add(entry);
        }

        syncWarps(incoming);
        return incoming.size();
    }

    private void checkAndSync() {
        // Try native TeleportPlugin map first — more accurate and zero file I/O
        int nativeSynced = syncNativeWarps();
        if (nativeSynced >= 0) {
            // Native path succeeded — skip file-based sync
            return;
        }

        // Fallback: file-based sync (for servers without TeleportPlugin or before it loads)
        File warpsFile = new File(config.getWarpsSourcePath());
        if (!warpsFile.exists()) return;

        long modified = warpsFile.lastModified();
        if (modified == lastModified) return; // No change
        lastModified = modified;

        try {
            List<PortalEntry> warps = parseWarpsFile(warpsFile);
            syncWarps(warps);
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Failed to sync warps.json: " + e.getMessage());
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
        // Single bulk load replaces N per-warp loadById() calls
        Map<String, PortalEntry> existing = storage.loadAll()
                .stream().collect(Collectors.toMap(PortalEntry::getId, e -> e));
    
        Set<String> incomingIds = new HashSet<>();
    
        for (PortalEntry incoming : incomingWarps) {
            incomingIds.add(incoming.getId());
    
            PortalEntry ex = existing.get(incoming.getId());
            if (ex == null) {
                // New warp - register with default strategy
                incoming.setStrategy(WarmStrategy.PREDICTIVE);
                storage.save(incoming);
                LOG.info("[OptiPortal] New warp registered: " + incoming.getId()
                        + " [" + incoming.getX() + ", " + incoming.getY() + ", " + incoming.getZ() + "] → PREDICTIVE");
            } else {
                // Check if coordinates changed
                if (ex.getX() != incoming.getX() || ex.getY() != incoming.getY() || ex.getZ() != incoming.getZ()) {
                    // Coordinates moved - purge old cache, update
                    ex.setX(incoming.getX());
                    ex.setY(incoming.getY());
                    ex.setZ(incoming.getZ());
                    ex.setYaw(incoming.getYaw());
                    storage.save(ex);
                    LOG.info("[OptiPortal] Warp moved, cache purged: " + incoming.getId());
                }
            }
        }
    
        // Remove warps that are in storage but no longer exist in file.
        // Only delete PORTAL-type entries — MANUAL zones, death records, and respawn records
        // share the same storage backend and must not be touched by warp sync.
        for (String existingId : existing.keySet()) {
            if (!incomingIds.contains(existingId)) {
                PortalEntry ex = existing.get(existingId);
                if (ex != null && ex.getType() == PortalEntry.EntryType.PORTAL) {
                    storage.delete(existingId);
                    LOG.info("[OptiPortal] Warp removed: " + existingId);
                }
            }
        }

        // Notify portal cache that storage has changed
        onSyncComplete.run();
    }
}
