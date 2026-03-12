package com.optiportal.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.Gson;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Shared SQL logic for H2, SQLite, and MySQL backends.
 * Each subclass provides the HikariDataSource configuration.
 */
public abstract class AbstractSqlStorageBackend implements StorageBackend {

    private static final Gson GSON = new Gson();
    protected HikariDataSource dataSource;

    @Override
    public void init() throws Exception {
        dataSource = createDataSource();
        createTable();
    }

    protected abstract HikariDataSource createDataSource() throws Exception;

    private void createTable() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS portal_entries (
                    id              VARCHAR(255) PRIMARY KEY,
                    world           VARCHAR(255),
                    x               DOUBLE,
                    y               DOUBLE,
                    z               DOUBLE,
                    yaw             DOUBLE,
                    creator         VARCHAR(255),
                    creation_date   VARCHAR(64),
                    strategy        VARCHAR(32)  DEFAULT 'PREDICTIVE',
                    warm_radius     INT          DEFAULT -1,
                    warm_radius_x   INT,
                    warm_radius_z   INT,
                    instanced       BOOLEAN      DEFAULT FALSE,
                    notes           TEXT,
                    cache_ttl_days  INT,
                    ram_estimated   DOUBLE       DEFAULT 0,
                    ram_marginal    DOUBLE       DEFAULT 0,
                    preload_count   INT          DEFAULT 0,
                    last_cache_tier VARCHAR(32)  DEFAULT 'UNVISITED',
                    last_active     VARCHAR(64),
                    last_status     VARCHAR(64)  DEFAULT 'UNVISITED',
                    entry_type      VARCHAR(32)  DEFAULT 'PORTAL',
                    activation_json TEXT,
                    updated_at      VARCHAR(64)
                )
            """);
        }
    }

    @Override
    public List<PortalEntry> loadAll() {
        List<PortalEntry> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM portal_entries");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            System.err.println("[OptiPortal] SQL loadAll error: " + e.getMessage());
        }
        return result;
    }

    @Override
    public Optional<PortalEntry> loadById(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM portal_entries WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            System.err.println("[OptiPortal] SQL loadById error: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns the full upsert SQL for this backend's dialect.
     * SQLite/H2 use ON CONFLICT(id) DO UPDATE SET ... excluded.*
     * MySQL uses INSERT INTO ... ON DUPLICATE KEY UPDATE col=VALUES(col), ...
     */
    protected abstract String upsertSql();

    @Override
    public void save(PortalEntry entry) {
        String sql = upsertSql();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindEntry(ps, entry);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[OptiPortal] SQL save error: " + e.getMessage());
        }
    }

    @Override
    public void saveAll(List<PortalEntry> entries) {
        for (PortalEntry entry : entries) save(entry);
    }

    @Override
    public void delete(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM portal_entries WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[OptiPortal] SQL delete error: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private void bindEntry(PreparedStatement ps, PortalEntry e) throws SQLException {
        ps.setString(1, e.getId());
        ps.setString(2, e.getWorld());
        ps.setDouble(3, e.getX());
        ps.setDouble(4, e.getY());
        ps.setDouble(5, e.getZ());
        ps.setDouble(6, e.getYaw());
        ps.setString(7, e.getCreator());
        ps.setString(8, e.getCreationDate() != null ? e.getCreationDate().toString() : null);
        ps.setString(9, e.getStrategy().name());
        ps.setInt(10, e.getWarmRadius());
        if (e.getWarmRadiusX() != null) ps.setInt(11, e.getWarmRadiusX()); else ps.setNull(11, Types.INTEGER);
        if (e.getWarmRadiusZ() != null) ps.setInt(12, e.getWarmRadiusZ()); else ps.setNull(12, Types.INTEGER);
        ps.setBoolean(13, e.isInstanced());
        ps.setString(14, e.getNotes());
        if (e.getCacheTTLDays() != null) ps.setInt(15, e.getCacheTTLDays()); else ps.setNull(15, Types.INTEGER);
        ps.setDouble(16, e.getRamEstimatedMB());
        ps.setDouble(17, e.getRamMarginalMB());
        ps.setInt(18, e.getPreloadCount());
        ps.setString(19, e.getLastCacheTier().name());
        ps.setString(20, e.getLastActive() != null ? e.getLastActive().toString() : null);
        ps.setString(21, e.getLastStatus());
        ps.setString(22, e.getType().name());
        ps.setString(23, Instant.now().toString());
    }

    private PortalEntry mapRow(ResultSet rs) throws SQLException {
        PortalEntry e = new PortalEntry();
        e.setId(rs.getString("id"));
        e.setWorld(rs.getString("world"));
        e.setX(rs.getDouble("x"));
        e.setY(rs.getDouble("y"));
        e.setZ(rs.getDouble("z"));
        e.setYaw(rs.getDouble("yaw"));
        e.setCreator(rs.getString("creator"));
        String cd = rs.getString("creation_date");
        if (cd != null) e.setCreationDate(Instant.parse(cd));
        e.setStrategy(WarmStrategy.valueOf(rs.getString("strategy")));
        e.setWarmRadius(rs.getInt("warm_radius"));
        int wrx = rs.getInt("warm_radius_x"); if (!rs.wasNull()) e.setWarmRadiusX(wrx);
        int wrz = rs.getInt("warm_radius_z"); if (!rs.wasNull()) e.setWarmRadiusZ(wrz);
        e.setInstanced(rs.getBoolean("instanced"));
        e.setNotes(rs.getString("notes"));
        int ttl = rs.getInt("cache_ttl_days"); if (!rs.wasNull()) e.setCacheTTLDays(ttl);
        e.setRamEstimatedMB(rs.getDouble("ram_estimated"));
        e.setRamMarginalMB(rs.getDouble("ram_marginal"));
        e.setPreloadCount(rs.getInt("preload_count"));
        e.setLastCacheTier(CacheTier.valueOf(rs.getString("last_cache_tier")));
        String la = rs.getString("last_active");
        if (la != null) e.setLastActive(Instant.parse(la));
        e.setLastStatus(rs.getString("last_status"));
        e.setType(PortalEntry.EntryType.valueOf(rs.getString("entry_type")));
        return e;
    }
}