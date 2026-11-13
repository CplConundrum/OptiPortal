package com.optiportal.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.optiportal.model.PortalEntry;
import com.optiportal.support.FakePluginConfig;

/**
 * Tier-1 (improvement pass) — empirical proof for the H2 upsert-SQL finding:
 * H2 2.2.224 in plain mode must execute the backend's upsert statement. The
 * original SQL used SQLite/PostgreSQL {@code ON CONFLICT(id) DO UPDATE SET ...
 * excluded.*} syntax, which H2 plain mode rejects — and save() swallows the
 * resulting SQLException as a warning, so H2-mode saves were silent no-ops.
 *
 * This test exercises the REAL H2StorageBackend against a real H2 file database:
 * 1. first save = INSERT path,
 * 2. second save = CONFLICT path (must overwrite, not error),
 * 3. loadById must reflect the overwritten value.
 */
class H2UpsertSqlTest {

    @Test
    void h2UpsertInsertsAndOverwrites(@TempDir File tempDir) throws Exception {
        FakePluginConfig config = new FakePluginConfig(tempDir);
        H2StorageBackend backend = new H2StorageBackend(config);
        backend.init();

        PortalEntry entry = new PortalEntry("h2-upsert-zone", "world_a", 1.0, 2.0, 3.0, 0.0);
        backend.save(entry);

        Optional<PortalEntry> first = backend.loadById("h2-upsert-zone");
        assertTrue(first.isPresent(),
                "H2: first save (INSERT path) must persist the row — a missing row means the upsert SQL was rejected");
        assertEquals(1.0, first.get().getX(), 1e-9);

        // CONFLICT path — same id again with a changed value.
        PortalEntry updated = new PortalEntry("h2-upsert-zone", "world_b", 9.0, 2.0, 3.0, 0.0);
        backend.save(updated);

        Optional<PortalEntry> second = backend.loadById("h2-upsert-zone");
        assertTrue(second.isPresent(), "H2: overwritten row must still be loadable");
        assertEquals("world_b", second.get().getWorld(),
                "H2: second save (CONFLICT path) must OVERWRITE, not fail silently");
        assertEquals(9.0, second.get().getX(), 1e-9,
                "H2: the overwritten row must carry the new value");

        // JDBC-level proof: the H2 database ITSELF must hold the overwritten row
        // (memory maps alone would not prove the SQL statement executed).
        try (java.sql.Connection conn = backend.dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT world, x FROM portal_entries WHERE id = ?")) {
            ps.setString(1, "h2-upsert-zone");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "H2: the portal_entries row must exist in the database");
                assertEquals("world_b", rs.getString(1), "H2: DB row must carry the overwritten value");
                assertEquals(9.0, rs.getDouble(2), 1e-9, "H2: DB row x must carry the overwritten value");
            }
        }
    }
}
