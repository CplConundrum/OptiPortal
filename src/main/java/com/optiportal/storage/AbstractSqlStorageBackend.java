package com.optiportal.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger("OptiPortal");
    private static final Gson GSON = new Gson();
    protected HikariDataSource dataSource;

    // Optional cache updater for async invalidation
    private CacheUpdater cacheUpdater;

    // In-memory mirror of DB rows — keeps cache notifications free of DB round-trips.
    // Populated lazily on first write; authoritative DB is always the source of truth on startup.
    private final LinkedHashMap<String, PortalEntry> memEntries = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, PortalEntry> memIndex = new ConcurrentHashMap<>();
    private volatile List<PortalEntry> cachedList = Collections.emptyList();

    /**
     * Interface for cache update notifications.
     */
    public interface CacheUpdater {
        void onUpdate(List<PortalEntry> currentEntries);
    }

    /**
     * Set the cache updater for invalidation notifications.
     */
    public void setCacheUpdater(CacheUpdater cacheUpdater) {
        this.cacheUpdater = cacheUpdater;
    }

    @Override
    public void init() throws Exception {
        dataSource = createDataSource();
        createTable();
        hydrateMemory();
    }

    protected abstract HikariDataSource createDataSource() throws Exception;

    private void createTable() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS portal_entries (
                    id                   VARCHAR(255) PRIMARY KEY,
                    world                VARCHAR(255),
                    x                    DOUBLE,
                    y                    DOUBLE,
                    z                    DOUBLE,
                    yaw                  DOUBLE,
                    creator              VARCHAR(255),
                    creation_date        VARCHAR(64),
                    strategy             VARCHAR(32)  DEFAULT 'PREDICTIVE',
                    warm_radius          INT          DEFAULT -1,
                    warm_radius_x        INT,
                    warm_radius_z        INT,
                    instanced            BOOLEAN      DEFAULT FALSE,
                    notes                TEXT,
                    cache_ttl_days       INT,
                    ram_estimated        DOUBLE       DEFAULT 0,
                    ram_marginal         DOUBLE       DEFAULT 0,
                    preload_count        INT          DEFAULT 0,
                    last_cache_tier      VARCHAR(32)  DEFAULT 'UNVISITED',
                    last_active          VARCHAR(64),
                    last_status          VARCHAR(64)  DEFAULT 'UNVISITED',
                    entry_type           VARCHAR(32)  DEFAULT 'PORTAL',
                    activation_json      TEXT,
                    destination_world_uuid VARCHAR(36),
                    updated_at           VARCHAR(64)
                )
            """);
            // Migrate existing databases that predate these columns
            migrateAddColumnIfMissing(stmt, "activation_json",       "TEXT");
            migrateAddColumnIfMissing(stmt, "destination_world_uuid", "VARCHAR(36)");
        }
    }

    /**
     * Silently adds a column to portal_entries if it does not already exist.
     * Used for forward-compatible schema migrations on server upgrade.
     */
    private void migrateAddColumnIfMissing(Statement stmt, String column, String definition) {
        try {
            stmt.execute("ALTER TABLE portal_entries ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) {
            // Column already exists — safe to ignore
        }
    }

    @Override
    public List<PortalEntry> loadAll() {
        return cachedList;
    }

    @Override
    public Optional<PortalEntry> loadById(String id) {
        return Optional.ofNullable(memIndex.get(id));
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
        boolean success = false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindEntry(ps, entry);
            ps.executeUpdate();
            success = true;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[OptiPortal] SQL save error", e);
        }
        if (success) {
            List<PortalEntry> snapshot;
            synchronized (this) {
                memEntries.put(entry.getId(), entry);
                memIndex.put(entry.getId(), entry);

                snapshot = rebuildSnapshot();
            }
            if (cacheUpdater != null) {
                cacheUpdater.onUpdate(snapshot);
            }
        }
    }

    @Override
    public void saveAll(List<PortalEntry> entries) {
        if (entries == null || entries.isEmpty()) return;

        String sql = upsertSql();
        boolean success = false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            try {
                for (PortalEntry entry : entries) {
                    bindEntry(ps, entry);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                success = true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[OptiPortal] SQL saveAll error", e);
        }
        if (success) {
            List<PortalEntry> snapshot;
            synchronized (this) {
                for (PortalEntry entry : entries) {
                    memEntries.put(entry.getId(), entry);
                    memIndex.put(entry.getId(), entry);
                }
                snapshot = rebuildSnapshot();
            }
            if (cacheUpdater != null) {
                cacheUpdater.onUpdate(snapshot);
            }
        }
    }

    @Override
    public void delete(String id) {
        boolean success = false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM portal_entries WHERE id = ?")) {
            ps.setString(1, id);
            int rows = ps.executeUpdate();
            success = rows > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[OptiPortal] SQL delete error", e);
        }
        if (success) {
            List<PortalEntry> snapshot;
            synchronized (this) {
                memEntries.remove(id);

                memIndex.remove(id);
                snapshot = rebuildSnapshot();
            }
            if (cacheUpdater != null) {
                cacheUpdater.onUpdate(snapshot);
            }
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * Loads all rows from the database into the in-memory map.
     * Called once at init(). After this, save/saveAll/delete keep the map in sync.
     */
    private synchronized void hydrateMemory() {
        List<PortalEntry> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM portal_entries");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[OptiPortal] SQL hydrateMemory error", e);
        }
        for (PortalEntry e : rows) {
            memEntries.put(e.getId(), e);
            memIndex.put(e.getId(), e);
        }
        cachedList = Collections.unmodifiableList(new ArrayList<>(memEntries.values()));
    }

    /**
     * Rebuilds the cached snapshot from the in-memory map.
     * Must be called while holding the lock on `this`.
     */
    private List<PortalEntry> rebuildSnapshot() {
        List<PortalEntry> snap = Collections.unmodifiableList(new ArrayList<>(memEntries.values()));
        cachedList = snap;
        return snap;
    }

    @Override
    public List<PortalEntry> loadAllCached() {
        return cachedList;
    }

    protected void bindEntry(PreparedStatement ps, PortalEntry e) throws SQLException {
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
        // activation_json: serialize the six per-zone activation override fields
        java.util.Map<String, Object> activationMap = new java.util.LinkedHashMap<>();
        activationMap.put("activationDistance",           e.getActivationDistance());
        activationMap.put("activationDistanceHorizontal", e.getActivationDistanceHorizontal());
        activationMap.put("activationDistanceVertical",   e.getActivationDistanceVertical());
        activationMap.put("activationShape",              e.getActivationShape());
        activationMap.put("floorCeilingCheck",            e.getFloorCeilingCheck());
        activationMap.put("facingCheck",                  e.getFacingCheck());
        boolean hasActivation = activationMap.values().stream().anyMatch(v -> v != null);
        ps.setString(23, hasActivation ? GSON.toJson(activationMap) : null);
        // destination_world_uuid
        ps.setString(24, e.getDestinationWorldUuid() != null ? e.getDestinationWorldUuid().toString() : null);
        ps.setString(25, Instant.now().toString());
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
        // activation_json: deserialize per-zone activation overrides (H2 fix)
        String activationJson = rs.getString("activation_json");
        if (activationJson != null) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> m = GSON.fromJson(activationJson, java.util.Map.class);
                if (m != null) {
                    if (m.get("activationDistance") instanceof Number n)
                        e.setActivationDistance(n.doubleValue());
                    if (m.get("activationDistanceHorizontal") instanceof Number n)
                        e.setActivationDistanceHorizontal(n.doubleValue());
                    if (m.get("activationDistanceVertical") instanceof Number n)
                        e.setActivationDistanceVertical(n.doubleValue());
                    if (m.get("activationShape") instanceof String s)
                        e.setActivationShape(s);
                    if (m.get("floorCeilingCheck") instanceof Boolean b)
                        e.setFloorCeilingCheck(b);
                    if (m.get("facingCheck") instanceof Boolean b)
                        e.setFacingCheck(b);
                }
            } catch (Exception ignored) { /* corrupt JSON — skip activation overrides */ }
        }
        // destination_world_uuid (H1 fix)
        String dwu = rs.getString("destination_world_uuid");
        if (dwu != null) e.setDestinationWorldUuid(java.util.UUID.fromString(dwu));
        return e;
    }
}