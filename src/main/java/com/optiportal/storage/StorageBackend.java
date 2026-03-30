package com.optiportal.storage;

import com.optiportal.model.PortalEntry;

import java.util.List;
import java.util.Optional;

/**
 * Storage backend interface. All backends implement the same contract so
 * the rest of the plugin never knows or cares which is active.
 * Migration between backends is handled by StorageMigrator.
 */
public interface StorageBackend {

    /**
     * Initialize the backend (create tables, open files, etc.)
     */
    void init() throws Exception;

    /**
     * Load all portal entries from storage.
     */
    List<PortalEntry> loadAll();

    /**
     * Load a single entry by ID.
     */
    Optional<PortalEntry> loadById(String id);

    /**
     * Save or update a portal entry.
     */
    void save(PortalEntry entry);

    /**
     * Save multiple entries in a single batch operation.
     */
    void saveAll(List<PortalEntry> entries);

    /**
     * Delete an entry by ID.
     */
    void delete(String id);

    /**
     * Returns the backend type identifier (e.g. "JSON", "SQLITE").
     */
    String getBackendType();

    /**
     * Flush and close all connections/files cleanly.
     */
    void close();

    /**
     * Load all portal entries from a cached snapshot (no-lock read).
     * Default implementation falls back to loadAll() for backends that don't cache.
     * JsonStorageBackend overrides this to return a volatile cached snapshot.
     */
    default List<PortalEntry> loadAllCached() {
        return loadAll();
    }
}
