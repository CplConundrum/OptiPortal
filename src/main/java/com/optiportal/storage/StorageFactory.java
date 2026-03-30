package com.optiportal.storage;

import com.optiportal.config.PluginConfig;

import java.io.File;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates and initializes the appropriate StorageBackend.
 *
 * Startup flow:
 *  1. Read meta.txt to find previously-used backend
 *  2. If backend changed → run StorageMigrator (old → new)
 *     - On migration failure → fall back to old backend, leave meta.txt unchanged
 *  3. If no meta.txt → fresh install, write meta.txt with current backend
 *
 * The backend returned is always initialized (init() already called).
 */
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    public static StorageBackend create(PluginConfig config) {
        File metaFile = new File(config.getDataFolder(), "meta.txt");
        String configured = config.getBackend().toUpperCase();
        String previous = readMeta(metaFile);

        // Attempt to initialize the configured backend
        StorageBackend newBackend = tryInit(configured, config);
        if (newBackend == null) {
            LOG.warning("[OptiPortal] Could not initialize configured backend (" + configured
                    + "), falling back to JSON.");
            newBackend = forceJson(config);
        }

        // Fresh install — no migration needed
        if (previous == null) {
            writeMeta(metaFile, newBackend.getBackendType());
            return newBackend;
        }

        // Same backend — nothing to do
        if (previous.equals(newBackend.getBackendType())) {
            return newBackend;
        }

        // Backend changed — attempt migration
        LOG.info("[OptiPortal] Backend change detected: " + previous + " → " + newBackend.getBackendType());
        boolean migrated = StorageMigrator.migrate(previous, newBackend, config);

        if (migrated) {
            writeMeta(metaFile, newBackend.getBackendType());
            return newBackend;
        } else {
            // Migration failed — close the new (empty) backend and fall back to old
            LOG.severe("[OptiPortal] Migration failed. Reverting to previous backend: " + previous);
            newBackend.close();
            StorageBackend fallback = tryInit(previous, config);
            if (fallback == null) {
                LOG.severe("[OptiPortal] Could not re-open previous backend either. Using JSON.");
                fallback = forceJson(config);
                writeMeta(metaFile, "JSON");
            }
            // Leave meta.txt unchanged — next startup will retry migration
            return fallback;
        }
    }

    static StorageBackend tryInit(String type, PluginConfig config) {
        StorageBackend backend = switch (type) {
            case "SQLITE" -> new SqliteStorageBackend(config);
            case "H2"     -> new H2StorageBackend(config);
            case "MYSQL"  -> new MySqlStorageBackend(config);
            default       -> new JsonStorageBackend(config);
        };
        try {
            backend.init();
            return backend;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[OptiPortal] Failed to init " + type + " backend", e);
            try { backend.close(); } catch (Exception closeEx) {
                LOG.fine("[OptiPortal] Could not close backend after failed init: " + closeEx.getMessage());
            }
            return null;
        }
    }

    private static StorageBackend forceJson(PluginConfig config) {
        StorageBackend json = new JsonStorageBackend(config);
        try {
            json.init();
            return json;
        } catch (Exception e) {
            throw new RuntimeException("[OptiPortal] Fatal: could not initialize JSON fallback backend", e);
        }
    }

    private static String readMeta(File metaFile) {
        if (!metaFile.exists()) return null;
        try { return Files.readString(metaFile.toPath()).trim().toUpperCase(); }
        catch (Exception e) { return null; }
    }

    private static void writeMeta(File metaFile, String backendType) {
        try {
            metaFile.getParentFile().mkdirs();
            Files.writeString(metaFile.toPath(), backendType);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[OptiPortal] Could not write meta.txt", e);
        }
    }
}
