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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private volatile int writeBehindDelayMs;
    private final ScheduledExecutorService flushExecutor;

    // In-memory map for fast access
    private final Map<String, PortalEntry> entries = new LinkedHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, PortalEntry> entryIndex = new java.util.concurrent.ConcurrentHashMap<>();

    // Volatile cached snapshot for lock-free reads (Issue 2 optimization)
    private volatile List<PortalEntry> cachedList = java.util.Collections.emptyList();

    // Optional cache updater for async invalidation
    private CacheUpdater cacheUpdater;
    private final Object flushLock = new Object();
    private final Object ioLock = new Object();
    private final AtomicLong snapshotVersion = new AtomicLong(0);
    private volatile ScheduledFuture<?> pendingFlushTask;
    private volatile List<PortalEntry> pendingFlushSnapshot;
    private volatile long pendingFlushVersion;
    private volatile long flushedVersion;
    private volatile boolean closed;

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
        this.writeBehindDelayMs = Math.max(0, config.getStorageWriteBehindDelayMs());
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "OptiPortal-JsonFlush");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.setRemoveOnCancelPolicy(true);
        this.flushExecutor = executor;
    }

    @Override
    public void init() throws Exception {
        config.getDataFolder().mkdirs();

        if (dataFile.exists()) {
            loadFromDisk();
        } else {
            // Fresh install - write empty structure
            flush(List.<PortalEntry>of(), snapshotVersion.incrementAndGet());
        }
        // Initialize cachedList and entryIndex after loading
        entryIndex.putAll(entries);
        cachedList = java.util.Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    private void loadFromDisk() {
        loadFromDisk(false);
    }

    private void loadFromDisk(boolean recovering) {
        try (Reader reader = new FileReader(dataFile)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;

            JsonArray portals = root.getAsJsonArray("portals");
            if (portals != null) {
                for (JsonElement element : portals) {
                    PortalEntry entry = GSON.fromJson(element, PortalEntry.class);
                    if (entry != null && entry.getId() != null) {
                        entries.put(entry.getId(), entry);
                        entryIndex.put(entry.getId(), entry);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Failed to read portal-data.json: " + e.getMessage());
            if (!recovering && bakFile.exists()) {
                LOG.warning("[OptiPortal] Attempting recovery from backup...");
                try {
                    Files.copy(bakFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    loadFromDisk(true);   // one retry only — if bak is also bad, give up
                } catch (Exception ex) {
                    LOG.warning("[OptiPortal] Backup recovery also failed — starting with empty portal list: " + ex.getMessage());
                }
            } else if (recovering) {
                LOG.warning("[OptiPortal] Backup is also corrupt — starting with empty portal list.");
            }
        }
    }

    @Override
    public List<PortalEntry> loadAll() {
        return cachedList;
    }

    @Override
    public List<PortalEntry> loadAllCached() {
        return cachedList;
    }

    @Override
    public List<PortalEntry> loadEntriesWithIdPrefix(String prefix) {
        // Use the existing cached list to filter without rebuilding from disk
        // This avoids the full JSON deserialization overhead during startup/reload
        if (prefix == null || prefix.isEmpty()) {
            return loadAllCached();
        }
        return cachedList.stream()
                .filter(entry -> entry.getId() != null && entry.getId().startsWith(prefix))
                .toList();
    }

    @Override
    public List<PortalEntry> loadWarpSyncEntries(String excludedIdPrefix) {
        // Use the existing cached list to filter without rebuilding from disk
        // Returns only PORTAL-type entries not owned by HyTeleportersX
        return cachedList.stream()
                .filter(entry -> entry.getType() == PortalEntry.EntryType.PORTAL
                               && (excludedIdPrefix == null || !entry.getId().startsWith(excludedIdPrefix)))
                .toList();
    }

    @Override
    public Optional<PortalEntry> loadById(String id) {
        return Optional.ofNullable(entryIndex.get(id));
    }

    @Override
    public void onConfigReload(PluginConfig config) {
        this.writeBehindDelayMs = Math.max(0, config.getStorageWriteBehindDelayMs());
    }

    @Override
    public void save(PortalEntry entry) {
        List<PortalEntry> snapshot;
        long version;
        synchronized (this) {
            entries.put(entry.getId(), entry);
            entryIndex.put(entry.getId(), entry);
            snapshot = new ArrayList<>(entries.values());
            version = snapshotVersion.incrementAndGet();
        }
        cachedList = java.util.Collections.unmodifiableList(snapshot);
        queueFlush(snapshot, version);
        if (cacheUpdater != null) {
            cacheUpdater.onUpdate(snapshot);
        }
    }

    @Override
    public void saveAll(List<PortalEntry> newEntries) {
        List<PortalEntry> snapshot;
        long version;
        synchronized (this) {
            for (PortalEntry e : newEntries) {
                entries.put(e.getId(), e);
                entryIndex.put(e.getId(), e);
            }
            snapshot = new ArrayList<>(entries.values());
            version = snapshotVersion.incrementAndGet();
        }
        cachedList = java.util.Collections.unmodifiableList(snapshot);
        queueFlush(snapshot, version);
        if (cacheUpdater != null) {
            cacheUpdater.onUpdate(snapshot);
        }
    }

    @Override
    public void delete(String id) {
        List<PortalEntry> snapshot = null;
        long version = -1;
        synchronized (this) {
            if (entries.remove(id) != null) {
                entryIndex.remove(id);
                snapshot = new ArrayList<>(entries.values());
                version = snapshotVersion.incrementAndGet();
            }
        }
        if (snapshot != null) {
            cachedList = java.util.Collections.unmodifiableList(snapshot);
            queueFlush(snapshot, version);
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
        long version;
        synchronized (this) {
            snapshot = new ArrayList<>(entries.values());
            version = snapshotVersion.incrementAndGet();
        }
        synchronized (flushLock) {
            closed = true;
            ScheduledFuture<?> task = pendingFlushTask;
            if (task != null) {
                task.cancel(false);
                pendingFlushTask = null;
            }
            pendingFlushSnapshot = null;
            pendingFlushVersion = 0;
        }
        cachedList = java.util.Collections.unmodifiableList(snapshot);
        flush(snapshot, version);
        flushExecutor.shutdown();
    }

    private void queueFlush(List<PortalEntry> snapshot, long version) {
        if (closed) {
            flush(snapshot, version);
            return;
        }
        if (writeBehindDelayMs <= 0) {
            flush(snapshot, version);
            return;
        }

        synchronized (flushLock) {
            pendingFlushSnapshot = snapshot;
            pendingFlushVersion = version;
            ScheduledFuture<?> task = pendingFlushTask;
            if (task != null) {
                task.cancel(false);
            }
            pendingFlushTask = flushExecutor.schedule(this::flushPendingSnapshot,
                    writeBehindDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void flushPendingSnapshot() {
        List<PortalEntry> snapshot;
        long version;
        synchronized (flushLock) {
            snapshot = pendingFlushSnapshot;
            version = pendingFlushVersion;
            pendingFlushSnapshot = null;
            pendingFlushVersion = 0;
            pendingFlushTask = null;
        }
        if (snapshot != null) {
            flush(snapshot, version);
        }
    }

    /**
     * Atomic write: tmp → rename to data, backup old.
     * Snapshot is taken by the caller under the lock before this is called,
     * so disk I/O (serialize + fsync + rename) never blocks concurrent readers.
     */
    private void flush(List<PortalEntry> snapshot, long version) {
        synchronized (ioLock) {
            if (version <= flushedVersion) {
                return;
            }
            try {
                JsonObject root = new JsonObject();
                root.addProperty("_backendType", "JSON");
                root.addProperty("_lastSaved", Instant.now().toString());

                JsonArray array = new JsonArray();
                for (PortalEntry entry : snapshot) {
                    array.add(GSON.toJsonTree(entry));
                }
                root.add("portals", array);

                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile);
                     java.nio.channels.FileChannel ch = fos.getChannel();
                     java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8)) {
                    GSON.toJson(root, writer);
                    writer.flush();
                    ch.force(true);
                }

                if (dataFile.exists()) {
                    Files.copy(dataFile.toPath(), bakFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                Files.move(tmpFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                flushedVersion = version;
            } catch (IOException e) {
                LOG.warning("[OptiPortal] Failed to flush portal-data.json: " + e.getMessage());
            }
        }
    }
}
