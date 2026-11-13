package com.optiportal.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.optiportal.support.FakePluginConfig;

/**
 * Structural pin for the Postgres upsert statement, self-contained (no live
 * Postgres server - matching the house pattern where external-DB backends such
 * as MySQL carry no in-gate live-DB test; the dialect validity is proven as an
 * ops step against a real Postgres, e.g. the LXC hytale box).
 *
 * <p>The statement is the native PostgreSQL {@code ON CONFLICT (id) DO UPDATE
 * SET ... excluded.*} upsert - the same shape this repo's PROVEN SQLite upsert
 * uses. These assertions lock the regression surface:
 * <ul>
 *   <li>exactly 25 value placeholders (one per table column),</li>
 *   <li>every one of the 22 non-key, non-insert-once columns updated via
 *       {@code col=excluded.col},</li>
 *   <li>the conflict key ({@code id}) and the insert-once fields
 *       ({@code creator}, {@code creation_date}) NOT in the update set.</li>
 * </ul>
 */
class PostgresUpsertSqlTest {

    /** The 22 columns the ON CONFLICT branch must overwrite (all except id,
     *  creator, creation_date). */
    private static final List<String> UPDATED_COLUMNS = List.of(
            "world", "x", "y", "z", "yaw", "strategy",
            "warm_radius", "warm_radius_x", "warm_radius_z", "instanced",
            "notes", "cache_ttl_days",
            "ram_estimated", "ram_marginal",
            "preload_count", "last_cache_tier", "last_active", "last_status",
            "entry_type", "activation_json", "destination_world_uuid", "updated_at");

    /** upsertSql() is protected - accessible because this test lives in the
     *  same package (as H2UpsertSqlTest exercises the H2 backend directly). */
    private String upsert() {
        PostgresStorageBackend backend =
                new PostgresStorageBackend(new FakePluginConfig(new java.io.File("target")));
        return backend.upsertSql();
    }

    @Test
    void usesNativePostgresOnConflictUpsert() {
        String sql = upsert();
        assertTrue(sql.contains("ON CONFLICT (id) DO UPDATE SET"),
                "must use the native PostgreSQL ON CONFLICT upsert dialect");
        assertFalse(sql.contains("ON DUPLICATE KEY"),
                "must NOT use the MySQL ON DUPLICATE KEY dialect");
    }

    @Test
    void hasOnePlaceholderPerTableColumn() {
        String sql = upsert();
        // Isolate the VALUES (...) clause and count its '?' placeholders.
        Matcher m = Pattern.compile("VALUES\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        assertTrue(m.find(), "VALUES clause must be present");
        int placeholders = (int) m.group(1).chars().filter(c -> c == '?').count();
        assertEquals(25, placeholders, "25 columns \u2192 25 placeholders");
    }

    @Test
    void updatesEveryMutableColumnViaExcluded() {
        String sql = upsert().replaceAll("\s+", " ");
        for (String col : UPDATED_COLUMNS) {
            assertTrue(sql.contains(col + "=excluded." + col),
                    "ON CONFLICT branch must update " + col + " via excluded." + col);
        }
    }

    @Test
    void neverUpdatesTheConflictKeyOrInsertOnceFields() {
        String sql = upsert().replaceAll("\s+", " ");
        assertFalse(sql.contains("id=excluded.id"),
                "the conflict key (id) must not be in the update set");
        assertFalse(sql.contains("creator=excluded.creator"),
                "creator is an insert-once field and must not be overwritten");
        assertFalse(sql.contains("creation_date=excluded.creation_date"),
                "creation_date is an insert-once field and must not be overwritten");
    }
}
