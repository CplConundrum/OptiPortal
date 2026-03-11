package com.optiportal.storage;

import com.optiportal.config.PluginConfig;
import com.optiportal.model.PortalEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

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
            System.err.println("[OptiPortal] Could not open previous backend (" + previousType
                    + ") — skipping migration, starting fresh.");
            return true; // treat as success so meta.txt gets updated
        }

        try {
            List<PortalEntry> entries = oldBackend.loadAll();
            System.out.println("[OptiPortal] Migrating " + entries.size() + " entries from "
                    + previousType + " → " + newBackend.getBackendType() + "...");

            newBackend.saveAll(entries);

            backupOldData(previousType, config);

            System.out.println("[OptiPortal] Migration complete. "
                    + entries.size() + " entries migrated to " + newBackend.getBackendType() + ".");
            return true;

        } catch (Exception e) {
            System.err.println("[OptiPortal] Migration FAILED: " + e.getMessage());
            e.printStackTrace();
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
                System.out.println("[OptiPortal] NOTE: MySQL remote database was NOT modified.");
                System.out.println("[OptiPortal] Clean up the remote 'portal_entries' table manually if desired.");
            }
            default -> System.out.println("[OptiPortal] No backup action defined for backend type: " + backendType);
        }
    }

    private static void safeCopy(File src, File dest) {
        if (!src.exists()) return;
        try {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[OptiPortal] Old data backed up → " + dest.getName());
        } catch (IOException e) {
            System.err.println("[OptiPortal] Could not backup old data: " + e.getMessage());
        }
    }
}
