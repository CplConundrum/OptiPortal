package com.optiportal.storage;

import java.io.File;

import com.optiportal.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class H2StorageBackend extends AbstractSqlStorageBackend {

    private final PluginConfig config;

    public H2StorageBackend(PluginConfig config) {
        this.config = config;
    }

    @Override
    protected HikariDataSource createDataSource() {
        File dbFile = new File(config.getDataFolder(), "portal-data-h2");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:file:" + dbFile.getAbsolutePath() + ";AUTO_SERVER=FALSE");
        hc.setMaximumPoolSize(4);
        return new HikariDataSource(hc);
    }

    @Override
    public String getBackendType() { return "H2"; }

    @Override
    protected String upsertSql() {
        // Tier-1 (improvement pass): the original SQL used SQLite/PostgreSQL
        // "ON CONFLICT(id) DO UPDATE SET ... excluded.*" syntax, which H2 2.2
        // plain mode REJECTS — and save() swallows the SQLException, so every
        // H2-mode save was a silent no-op. H2's native upsert is
        // "MERGE INTO ... KEY(id) USING VALUES()" (insert when the key is new,
        // update every listed column when it exists). Verified by H2UpsertSqlTest.
        return """
            MERGE INTO portal_entries (
                id, world, x, y, z, yaw, creator, creation_date,
                strategy, warm_radius, warm_radius_x, warm_radius_z,
                instanced, notes, cache_ttl_days,
                ram_estimated, ram_marginal,
                preload_count, last_cache_tier, last_active, last_status,
                entry_type, activation_json, destination_world_uuid, updated_at
            )
            KEY(id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;
    }

}