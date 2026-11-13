package com.optiportal.storage;

import com.optiportal.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Postgres storage backend (HikariCP-pooled).
 *
 * <p>Uses the native PostgreSQL upsert dialect
 * {@code ON CONFLICT (id) DO UPDATE SET ... excluded.*} — the same syntax the
 * SQLite backend uses, which PostgreSQL supports natively.
 */
public class PostgresStorageBackend extends AbstractSqlStorageBackend {

    private final PluginConfig config;

    public PostgresStorageBackend(PluginConfig config) {
        this.config = config;
    }

    @Override
    protected HikariDataSource createDataSource() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s",
                config.getPgHost(), config.getPgPort(), config.getPgDatabase()));
        hc.setUsername(config.getPgUsername());
        hc.setPassword(config.getPgPassword());
        hc.setMaximumPoolSize(10);
        return new HikariDataSource(hc);
    }

    @Override
    public String getBackendType() { return "POSTGRES"; }

    @Override
    protected String createTableDdl() {
        // PostgreSQL has no bare DOUBLE type - DOUBLE PRECISION is the 8-byte float.
        return super.createTableDdl().replace("DOUBLE", "DOUBLE PRECISION");
    }


    @Override
    protected String upsertSql() {
        return """
            INSERT INTO portal_entries (
                id, world, x, y, z, yaw, creator, creation_date,
                strategy, warm_radius, warm_radius_x, warm_radius_z,
                instanced, notes, cache_ttl_days,
                ram_estimated, ram_marginal,
                preload_count, last_cache_tier, last_active, last_status,
                entry_type, activation_json, destination_world_uuid, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                yaw=excluded.yaw, strategy=excluded.strategy,
                warm_radius=excluded.warm_radius, warm_radius_x=excluded.warm_radius_x,
                warm_radius_z=excluded.warm_radius_z, instanced=excluded.instanced,
                notes=excluded.notes, cache_ttl_days=excluded.cache_ttl_days,
                ram_estimated=excluded.ram_estimated, ram_marginal=excluded.ram_marginal,
                preload_count=excluded.preload_count, last_cache_tier=excluded.last_cache_tier,
                last_active=excluded.last_active, last_status=excluded.last_status,
                entry_type=excluded.entry_type, activation_json=excluded.activation_json,
                destination_world_uuid=excluded.destination_world_uuid,
                updated_at=excluded.updated_at
        """;
    }
}
