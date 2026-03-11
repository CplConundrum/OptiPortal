package com.optiportal.storage;

import com.google.gson.*;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * JSON file storage backend.
 * Manages a single portal-data.json file in the plugin data folder.
 * Uses WAL-safe write pattern: write to .tmp, rename atomically.
 */
public class JsonStorageBackend implements StorageBackend {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (src, type, ctx) ->
                    new JsonPrimitive(src.toString()))
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, type, ctx) ->
                    Instant.parse(json.getAsString()))
            .create();

    private final PluginConfig config;
    private final File dataFile;
    private final File tmpFile;
    private final File bakFile;

    // In-memory map for fast access
    private final Map<String, PortalEntry> entries = new LinkedHashMap<>();

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
            flush();
        }
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
            System.err.println("[OptiPortal] Failed to read portal-data.json: " + e.getMessage());
            // Attempt bak recovery
            if (bakFile.exists()) {
                System.err.println("[OptiPortal] Attempting recovery from backup...");
                try {
                    Files.copy(bakFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    loadFromDisk();
                } catch (Exception ex) {
                    System.err.println("[OptiPortal] Backup recovery failed: " + ex.getMessage());
                }
            }
        }
    }

    @Override
    public List<PortalEntry> loadAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public Optional<PortalEntry> loadById(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public void save(PortalEntry entry) {
        entries.put(entry.getId(), entry);
        flush();
    }

    @Override
    public void saveAll(List<PortalEntry> newEntries) {
        for (PortalEntry e : newEntries) {
            entries.put(e.getId(), e);
        }
        flush();
    }

    @Override
    public void delete(String id) {
        entries.remove(id);
        flush();
    }

    @Override
    public String getBackendType() {
        return "JSON";
    }

    @Override
    public void close() {
        flush();
    }

    /**
     * Atomic write: tmp → rename to data, backup old.
     * Ensures partial writes never corrupt the primary file.
     */
    private synchronized void flush() {
        try {
            // Build JSON
            JsonObject root = new JsonObject();
            root.addProperty("_backendType", "JSON");
            root.addProperty("_lastSaved", Instant.now().toString());

            JsonArray array = new JsonArray();
            for (PortalEntry entry : entries.values()) {
                array.add(GSON.toJsonTree(entry));
            }
            root.add("portals", array);

            // Write to tmp
            try (Writer writer = new FileWriter(tmpFile)) {
                GSON.toJson(root, writer);
            }

            // Backup existing file
            if (dataFile.exists()) {
                Files.copy(dataFile.toPath(), bakFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // Atomic rename
            Files.move(tmpFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            System.err.println("[OptiPortal] Failed to flush portal-data.json: " + e.getMessage());
        }
    }
}
