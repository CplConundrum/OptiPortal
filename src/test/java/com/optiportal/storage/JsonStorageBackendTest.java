package com.optiportal.storage;

import com.optiportal.model.PortalEntry;
import com.optiportal.support.FakePluginConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for JsonStorageBackend write-behind ordering.
 *
 * Uses real file I/O with a JUnit @TempDir — no mocks.
 * Goal: protect the ordered write-behind persistence from data-loss regressions.
 */
class JsonStorageBackendTest {

    @TempDir
    File tempDir;

    private FakePluginConfig config;
    private JsonStorageBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        config  = new FakePluginConfig(tempDir, 0); // delay=0: immediate flush
        backend = new JsonStorageBackend(config);
        backend.init();
    }

    @AfterEach
    void tearDown() {
        try { backend.close(); } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Multiple saves to the same entry within the write-behind delay window must
     * coalesce to the newest snapshot — the flushed file must reflect the last save,
     * not an earlier one.
     */
    @Test
    void save_coalescesToLatestSnapshot() throws Exception {
        // Use a long delay so both saves are pending before any flush
        config.setWriteBehindDelayMs(10_000);

        PortalEntry v1 = portal("p1", "world", 0, 0, 0);
        v1.setNotes("first");
        backend.save(v1);

        PortalEntry v2 = portal("p1", "world", 5, 5, 5);
        v2.setNotes("second");
        backend.save(v2);

        // close() cancels the pending task and flushes the latest snapshot immediately
        backend.close();

        List<PortalEntry> loaded = reload();
        assertEquals(1, loaded.size());
        assertEquals("second", loaded.get(0).getNotes(),
                "Flushed file must contain the newest version, not an earlier one");
        assertEquals(5.0, loaded.get(0).getX(), 0.001,
                "Flushed coordinates must come from the last save");
    }

    /**
     * close() must flush any pending (not-yet-written) data immediately,
     * even when the write-behind delay has not yet elapsed.
     */
    @Test
    void close_flushesPendingWrites() throws Exception {
        config.setWriteBehindDelayMs(10_000); // would not flush for 10 seconds normally

        PortalEntry entry = portal("pending-entry", "world", 1, 2, 3);
        backend.save(entry);

        // Verify the file exists but may or may not have content (delay not expired)
        // Then close — this must force the flush
        backend.close();

        List<PortalEntry> loaded = reload();
        assertEquals(1, loaded.size(),
                "close() must flush pending write-behind data immediately");
        assertEquals("pending-entry", loaded.get(0).getId());
    }

    /**
     * Once a newer snapshot version has been flushed, an older pending flush
     * must not overwrite it.  The version guard (version <= flushedVersion) protects this.
     *
     * With delay=0 (synchronous flushing), saves are ordered and each flush advances
     * flushedVersion, so a subsequent read must see the final state.
     */
    @Test
    void olderFlushCannotOverwriteNewerVersion() throws Exception {
        // delay=0 → each save flushes synchronously; versions are strictly increasing
        PortalEntry e = portal("p1", "world", 0, 0, 0);
        e.setNotes("v1");
        backend.save(e);

        e = portal("p1", "world", 0, 0, 0);
        e.setNotes("v2");
        backend.save(e);

        e = portal("p1", "world", 0, 0, 0);
        e.setNotes("v3");
        backend.save(e);

        List<PortalEntry> loaded = reload();
        assertEquals(1, loaded.size());
        assertEquals("v3", loaded.get(0).getNotes(),
                "Final file must reflect the newest version; older flushes must not overwrite it");
    }

    /**
     * onConfigReload with delay=0 must switch subsequent saves to immediate flushing.
     * Verify by reloading the file right after save without calling close().
     */
    @Test
    void onConfigReload_updatesWriteBehindDelay() throws Exception {
        // Start with a long delay so saves are queued
        config.setWriteBehindDelayMs(10_000);
        backend.onConfigReload(config);

        PortalEntry entry = portal("c1", "world", 0, 0, 0);
        backend.save(entry);
        // File may not be updated yet (delay=10s)

        // Reload config with delay=0 (immediate flush)
        config.setWriteBehindDelayMs(0);
        backend.onConfigReload(config);

        PortalEntry entry2 = portal("c2", "world", 0, 0, 0);
        backend.save(entry2); // should flush immediately now

        // Read the file without closing — c2 should be there if delay=0 was honoured
        List<PortalEntry> loaded = reload();
        assertTrue(loaded.stream().anyMatch(p -> p.getId().equals("c2")),
                "After onConfigReload with delay=0, saves must flush immediately");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PortalEntry portal(String id, String world, double x, double y, double z) {
        return new PortalEntry(id, world, x, y, z, 0.0);
    }

    /** Load all entries from the on-disk file via a fresh backend instance. */
    private List<PortalEntry> reload() throws Exception {
        FakePluginConfig readConfig = new FakePluginConfig(tempDir, 0);
        JsonStorageBackend reader = new JsonStorageBackend(readConfig);
        reader.init();
        List<PortalEntry> result = reader.loadAll();
        reader.close();
        return result;
    }
}
