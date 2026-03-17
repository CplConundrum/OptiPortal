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
        return """
            INSERT INTO portal_entries (
                id, world, x, y, z, yaw, creator, creation_date,
                strategy, warm_radius, warm_radius_x, warm_radius_z,
                instanced, notes, cache_ttl_days,
                ram_estimated, ram_marginal,
                preload_count, last_cache_tier, last_active, last_status,
                entry_type, updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                yaw=excluded.yaw, strategy=excluded.strategy,
                warm_radius=excluded.warm_radius, warm_radius_x=excluded.warm_radius_x,
                warm_radius_z=excluded.warm_radius_z, instanced=excluded.instanced,
                notes=excluded.notes, cache_ttl_days=excluded.cache_ttl_days,
                ram_estimated=excluded.ram_estimated, ram_marginal=excluded.ram_marginal,
                preload_count=excluded.preload_count, last_cache_tier=excluded.last_cache_tier,
                last_active=excluded.last_active, last_status=excluded.last_status,
                entry_type=excluded.entry_type, updated_at=excluded.updated_at
        """;
    }

}