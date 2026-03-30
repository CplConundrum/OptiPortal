package com.optiportal.storage;

import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Migrates portal data between storage backends.
 *
 * Called by StorageFactory when meta.txt indicates a backend change.
 * The new backend is already initialized (empty). This class reads all
 * entries from the old backend, writes them to the new one, and backs
 * up the old data file.
 *
 * Migration matrix: Any → Any (JSON ↔ SQLite ↔ H2 ↔ MySQL)
 *
 * Safety guarantees:
 * - Old data is never deleted — backed up with a timestamp suffix
 * - MySQL remote databases are NOT touched on migration away
 * - If migration throws, returns false; StorageFactory reverts to old backend
 * - meta.txt is only updated by StorageFactory on success
 */
public class StorageMigrator {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    /**
     * Migrate all data from the previous backend into the already-initialized newBackend.
     *
     * @param previousType  backend type string from meta.txt (e.g. "JSON")
     * @param newBackend    already-initialized target backend
     * @param config        plugin config (for data folder paths)
     * @return true if migration succeeded, false if it failed
     */
    public static boolean migrate(String previousType, StorageBackend newBackend, PluginConfig config) {
        StorageBackend oldBackend = StorageFactory.tryInit(previousType, config);
        if (oldBackend == null) {
            LOG.warning("[OptiPortal] Could not open previous backend (" + previousType
                    + ") — skipping migration, starting fresh.");
            return true; // treat as success so meta.txt gets updated
        }

        try {
            List<PortalEntry> entries = oldBackend.loadAll();
            LOG.info("[OptiPortal] Migrating " + entries.size() + " entries from "
                    + previousType + " → " + newBackend.getBackendType() + "...");

            newBackend.saveAll(entries);

            backupOldData(previousType, config);

            LOG.info("[OptiPortal] Migration complete. "
                    + entries.size() + " entries migrated to " + newBackend.getBackendType() + ".");
            return true;

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[OptiPortal] Migration FAILED", e);
            return false;
        } finally {
            try { oldBackend.close(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Backup helpers
    // -------------------------------------------------------------------------

    private static void backupOldData(String backendType, PluginConfig config) {
        // Sanitize timestamp for filenames (colons not valid on Windows)
        String ts = Instant.now().toString()
                .replace(":", "-")
                .replace(".", "-");
        File dir = config.getDataFolder();

        switch (backendType) {
            case "JSON"   -> safeCopy(new File(dir, "portal-data.json"),
                                      new File(dir, "portal-data." + ts + ".migration-bak.json"));
            case "SQLITE" -> safeCopy(new File(dir, "portal-data.db"),
                                      new File(dir, "portal-data." + ts + ".migration-bak.db"));
            case "H2"     -> safeCopy(new File(dir, "portal-data-h2.mv.db"),
                                      new File(dir, "portal-data-h2." + ts + ".migration-bak.mv.db"));
            case "MYSQL"  -> {
                LOG.info("[OptiPortal] NOTE: MySQL remote database was NOT modified.");
                LOG.info("[OptiPortal] Clean up the remote 'portal_entries' table manually if desired.");
            }
            default -> LOG.info("[OptiPortal] No backup action defined for backend type: " + backendType);
        }
    }

    private static void safeCopy(File src, File dest) {
        if (!src.exists()) return;
        try {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOG.info("[OptiPortal] Old data backed up → " + dest.getName());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[OptiPortal] Could not backup old data", e);
        }
    }
}
