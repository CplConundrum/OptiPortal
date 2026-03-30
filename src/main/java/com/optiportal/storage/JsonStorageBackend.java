package com.optiportal.storage;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.util.logging.Logger;

import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;

/**
 * JSON file storage backend.
 * Manages a single portal-data.json file in the plugin data folder.
 * Uses WAL-safe write pattern: write to .tmp, rename atomically.
 */
public class JsonStorageBackend implements StorageBackend {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (src, type, ctx) ->
                    new JsonPrimitive(src.toString()))
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, type, ctx) ->
                    Instant.parse(json.getAsString()))
            .disableHtmlEscaping()
            .create();

    private final PluginConfig config;
    private final File dataFile;
    private final File tmpFile;
    private final File bakFile;

    // In-memory map for fast access
    private final Map<String, PortalEntry> entries = new LinkedHashMap<>();

    // Volatile cached snapshot for lock-free reads (Issue 2 optimization)
    private volatile List<PortalEntry> cachedList = java.util.Collections.emptyList();

    // Optional cache updater for async invalidation
    private CacheUpdater cacheUpdater;

    /**
     * Interface for cache update notifications.
     */
    public interface CacheUpdater {
        void onUpdate(List<PortalEntry> currentEntries);
    }

    /**
     * Set the cache updater for invalidation notifications.
     */
    public void setCacheUpdater(CacheUpdater cacheUpdater) {
        this.cacheUpdater = cacheUpdater;
    }

    public JsonStorageBackend(PluginConfig config) {
        this.config = config;
        File dataFolder = config.getDataFolder();
        this.dataFile = new File(dataFolder, "portal-data.json");
        this.tmpFile  = new File(dataFolder, "portal-data.json.tmp");
        this.bakFile  = new File(dataFolder, "portal-data.json.bak");
    }

    @Override
    public void init() throws Exception {
        config.getDataFolder().mkdirs();

        if (dataFile.exists()) {
            loadFromDisk();
        } else {
            // Fresh install - write empty structure
            flush(List.of());
        }
        // Initialize cachedList after loading
        cachedList = java.util.Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    private void loadFromDisk() {
        try (Reader reader = new FileReader(dataFile)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;

            JsonArray portals = root.getAsJsonArray("portals");
            if (portals != null) {
                for (JsonElement element : portals) {
                    PortalEntry entry = GSON.fromJson(element, PortalEntry.class);
                    if (entry != null && entry.getId() != null) {
                        entries.put(entry.getId(), entry);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Failed to read portal-data.json: " + e.getMessage());
            // Attempt bak recovery
            if (bakFile.exists()) {
                LOG.warning("[OptiPortal] Attempting recovery from backup...");
                try {
                    Files.copy(bakFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    loadFromDisk();
                } catch (Exception ex) {
                    LOG.warning("[OptiPortal] Backup recovery failed: " + ex.getMessage());
                }
            }
        }
    }

    @Override
    public synchronized List<PortalEntry> loadAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public List<PortalEntry> loadAllCached() {
        return cachedList;
    }

    @Override
    public synchronized Optional<PortalEntry> loadById(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public void save(PortalEntry entry) {
        List<PortalEntry> snapshot;
        synchronized (this) {
            entries.put(entry.getId(), entry);
            snapshot = new ArrayList<>(entries.values());
        }
        cachedList = java.util.Collections.unmodifiableList(snapshot);
        flush(snapshot);
        if (cacheUpdater != null) {
            cacheUpdater.onUpdate(snapshot);
        }
    }

    @Override
    public void saveAll(List<PortalEntry> newEntries) {
        List<PortalEntry> snapshot;
        synchronized (this) {
            for (PortalEntry e : newEntries) {
                entries.put(e.getId(), e);
            }
            snapshot = new ArrayList<>(entries.values());
        }
        cachedList = java.util.Collections.unmodifiableList(snapshot);
        flush(snapshot);
        if (cacheUpdater != null) {
            cacheUpdater.onUpdate(snapshot);
        }
    }

    @Override
    public void delete(String id) {
        List<PortalEntry> snapshot = null;
        synchronized (this) {
            if (entries.remove(id) != null) {
                snapshot = new ArrayList<>(entries.values());
            }
        }
        if (snapshot != null) {
            cachedList = java.util.Collections.unmodifiableList(snapshot);
            flush(snapshot);
            if (cacheUpdater != null) {
                cacheUpdater.onUpdate(snapshot);
            }
        }
    }

    @Override
    public String getBackendType() {
        return "JSON";
    }

    @Override
    public void close() {
        List<PortalEntry> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(entries.values());
        }
        cachedList = java.util.Collections.unmodifiableList(snapshot);
        flush(snapshot);
    }

    /**
     * Atomic write: tmp → rename to data, backup old.
     * Snapshot is taken by the caller under the lock before this is called,
     * so disk I/O (serialize + fsync + rename) never blocks concurrent readers.
     */
    private void flush(List<PortalEntry> snapshot) {
        try {
            // Build JSON
            JsonObject root = new JsonObject();
            root.addProperty("_backendType", "JSON");
            root.addProperty("_lastSaved", Instant.now().toString());

            JsonArray array = new JsonArray();
            for (PortalEntry entry : snapshot) {
                array.add(GSON.toJsonTree(entry));
            }
            root.add("portals", array);

            // Write to tmp with fsync before rename to prevent partial-write corruption
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile);
                 java.nio.channels.FileChannel ch = fos.getChannel();
                 java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
                writer.flush();
                ch.force(true);
            }

            // Backup existing file
            if (dataFile.exists()) {
                Files.copy(dataFile.toPath(), bakFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // Atomic rename
            Files.move(tmpFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            LOG.warning("[OptiPortal] Failed to flush portal-data.json: " + e.getMessage());
        }
    }
}
