package com.optiportal.storage;

import com.optiportal.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class MysqlStorageBackend extends AbstractSqlStorageBackend {

    private final PluginConfig config;

    public MysqlStorageBackend(PluginConfig config) {
        this.config = config;
    }

    @Override
    protected HikariDataSource createDataSource() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                config.getMysqlHost(), config.getMysqlPort(), config.getMysqlDatabase()));
        hc.setUsername(config.getMysqlUsername());
        hc.setPassword(config.getMysqlPassword());
        hc.setMaximumPoolSize(10);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        return new HikariDataSource(hc);
    }

    @Override
    public String getBackendType() { return "MYSQL"; }

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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z),
                yaw=VALUES(yaw), strategy=VALUES(strategy),
                warm_radius=VALUES(warm_radius), warm_radius_x=VALUES(warm_radius_x),
                warm_radius_z=VALUES(warm_radius_z), instanced=VALUES(instanced),
                notes=VALUES(notes), cache_ttl_days=VALUES(cache_ttl_days),
                ram_estimated=VALUES(ram_estimated), ram_marginal=VALUES(ram_marginal),
                preload_count=VALUES(preload_count), last_cache_tier=VALUES(last_cache_tier),
                last_active=VALUES(last_active), last_status=VALUES(last_status),
                entry_type=VALUES(entry_type), updated_at=VALUES(updated_at)
        """;
    }

}