package com.optiportal.config;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Loads and holds all plugin configuration.
 * Falls back to hardcoded defaults if fields are missing or config is malformed.
 */
public class PluginConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Backend
    private String backend = "JSON";

    // Startup
    private String startupLoadStrategy = "STAGED";
    private int snapshotIntervalMinutes = 10;
    private boolean rebuildFromChunksOnCorruption = true;
    private int scheduledRebuildIntervalHours = 24;
    private boolean suppressRamWarnings = false;
    private int lowTrafficThreshold = 5;
    private boolean immuneToSimulationReduction = true;

    // Defaults
    private int defaultWarmRadius = 4;
    // Chunk batch size for warm loading — number of chunks requested simultaneously.
    // Lower = less TICK_STEP pressure on FluidPlugin/BlockModule pre-load hooks. Default: 4.
    private int warmBatchSize = 4;
    // Delay in ms between warm load batches. 0 = no delay (fire all at once). Default: 250.
    private int warmBatchDelayMs = 250;
    private String defaultStrategy = "PREDICTIVE";
    private int defaultTimeoutSeconds = 5;
    private int defaultBufferSecondsWarm = 60;
    private int defaultBufferSecondsPredictive = 20;

    // Activation
    private double activationDistance = 16;
    private double activationDistanceVertical = 8;
    private String activationShape = "ELLIPSOID";
    private boolean floorCeilingCheck = true;
    private boolean facingCheck = true;
    private int activationCooldownSeconds = 30;
    private int activationCommitWindowSeconds = 3;

    // TTL (days, -1 = never)
    private int ttlWarm = -1;
    private int ttlRecentHot = 7;
    private int ttlPredictive = 2;
    private int ttlLowTraffic = 1;
    private int ttlBed = 3;
    private int ttlDeathLocation = 1;

    // Cache
    private boolean persistColdCache = true;
    private String cacheDirectory = "preload-cache/";
    private int maxCacheAgeDays = 7;

    // Warps
    private String warpsSourcePath = "universe/warps.json";
    private boolean warpsWatchForChanges = true;
    private int warpsWatchIntervalSeconds = 30;
    private String warpsWorldField = "World";
    private String warpsIdField = "Id";
    private String warpsXField = "X";
    private String warpsYField = "Y";
    private String warpsZField = "Z";
    private String warpsYawField = "Yaw";
    // Maps world names as stored in warps.json to actual Hytale world registry names.
    // e.g. {"default": "Orbis"} — populated via config.json worldNameRemap object.
    private java.util.Map<String, String> worldNameRemap = new java.util.HashMap<>();

    // Gravestones
    private String gravestonesSourcePath = "plugins/Gravestones/gravestones.json";
    private boolean gravestonesWatchForChanges = true;
    private int gravestonesWatchIntervalSeconds = 5;

    // Integrations
    private boolean gravestoneIntegrationEnabled = false;
    private String gravestonePluginId = "gravestones";
    private boolean gravestoneReleaseOnBreak = true;
    private boolean gravestoneReleaseOnEmpty = true;

    // MySQL
    private String mysqlHost = "localhost";
    private int mysqlPort = 3306;
    private String mysqlDatabase = "preload";
    private String mysqlUsername = "";
    private String mysqlPassword = "";
    private String mysqlTablePrefix = "pre_";

    // UI
    private boolean showInstancedPortals = false;
    private boolean showBedSpawns = true;
    private boolean showDeathLocations = true;

    // Metrics
    private boolean metricsEnabled = true;
    private int bstatsPluginId = 0;

    // Update checker
    private boolean updateCheckerEnabled = true;

    // Keepalive
    private boolean keepaliveHot  = true;
    private boolean keepaliveWarm = true;
    private boolean keepaliveCold = false;
    private int keepaliveHotIntervalMinutes  = 5;
    private int keepaliveWarmIntervalMinutes = 15;
    private int keepaliveColdIntervalMinutes = 60;

    // Tier decay
    private int hotDecaySeconds = 30;
    private int warmDecayMinutes = 30;
    private int pollIntervalSeconds = 1;

    // Server load sensing
    /** Whether TPS monitoring is active. Default: true. */
    private boolean tpsMonitorEnabled = true;

    /** TPS below which batch size is capped at its minimum. Default: 15.0. */
    private double tpsLowThreshold = 15.0;

    /** TPS below which all new non-critical operations are queued. Default: 12.0. */
    private double tpsCriticalThreshold = 12.0;

    /**
     * If ChunkStore.getLoadedChunksCount() exceeds this across any world,
     * batch size is reduced proportionally. Default: 2000. -1 = disabled.
     */
    private int maxLoadedChunksPressureThreshold = 2000;

    // Ownership drift detection
    /** How often to audit chunk ownership for drift, in minutes. Default: 5. */
    private int ownershipAuditIntervalMinutes = 5;

    // Corridor prioritization (Phase 4)
    /** Whether CorridorIndex path-proximity prioritization is active. Default: true. */
    private boolean corridorPrioritizationEnabled = true;

    /** Chunk radius around each WorldPath waypoint to include as corridor. Default: 3. */
    private int corridorRadiusChunks = 3;

    // Velocity-aware activation (Phase 4)
    /** Whether velocity-based radius boost is enabled. Default: true. */
    private boolean velocityAwareActivation = true;

    /** Player speed (blocks/tick) above which radius is boosted by 1. Default: 0.5. */
    private double velocityRadiusBoostThreshold = 0.5;

    // Data folder reference
    private File dataFolder;

    private PluginConfig() {}

    public static PluginConfig load(File dataFolder) {
        PluginConfig config = new PluginConfig();
        config.dataFolder = dataFolder;

        File configFile = new File(dataFolder, "config.json");

        // Copy default config if not present
        if (!configFile.exists()) {
            try {
                dataFolder.mkdirs();
                InputStream defaultConfig = PluginConfig.class.getResourceAsStream("/config.json");
                if (defaultConfig != null) {
                    Files.copy(defaultConfig, configFile.toPath());
                }
            } catch (IOException e) {
                System.err.println("[OptiPortal] Could not save default config: " + e.getMessage());
                return config; // Return defaults
            }
        }

        // Merge any missing keys from the bundled default config into the user's file
        migrateConfig(configFile);

        // Parse config
        try (Reader reader = new FileReader(configFile)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            config.parseJson(json);
        } catch (Exception e) {
            System.err.println("[OptiPortal] Failed to parse config.json, using defaults: " + e.getMessage());
        }

        // Auto-discover file paths if they look like defaults or the files don't exist on disk
        config.runPathDiscoveryIfNeeded(configFile);

        return config;
    }

    private void parseJson(JsonObject json) {
        if (json == null) return;

        if (json.has("backend")) backend = json.get("backend").getAsString();
        if (json.has("bytesPerChunk")) bytesPerChunk = json.get("bytesPerChunk").getAsInt();
        if (json.has("startupLoadStrategy")) startupLoadStrategy = json.get("startupLoadStrategy").getAsString();
        if (json.has("snapshotIntervalMinutes")) snapshotIntervalMinutes = json.get("snapshotIntervalMinutes").getAsInt();
        if (json.has("suppressRamWarnings")) suppressRamWarnings = json.get("suppressRamWarnings").getAsBoolean();
        if (json.has("lowTrafficThreshold")) lowTrafficThreshold = json.get("lowTrafficThreshold").getAsInt();
        if (json.has("immuneToSimulationReduction")) immuneToSimulationReduction = json.get("immuneToSimulationReduction").getAsBoolean();

        if (json.has("defaults")) {
            JsonObject defaults = json.getAsJsonObject("defaults");
            if (defaults.has("warmRadius")) defaultWarmRadius = defaults.get("warmRadius").getAsInt();
            if (defaults.has("warmBatchSize")) warmBatchSize = defaults.get("warmBatchSize").getAsInt();
            if (defaults.has("warmBatchDelayMs")) warmBatchDelayMs = defaults.get("warmBatchDelayMs").getAsInt();
            if (defaults.has("strategy")) defaultStrategy = defaults.get("strategy").getAsString();
            if (defaults.has("timeoutSeconds")) defaultTimeoutSeconds = defaults.get("timeoutSeconds").getAsInt();
            if (defaults.has("bufferSeconds")) {
                JsonObject buffer = defaults.getAsJsonObject("bufferSeconds");
                if (buffer.has("WARM")) defaultBufferSecondsWarm = buffer.get("WARM").getAsInt();
                if (buffer.has("PREDICTIVE")) defaultBufferSecondsPredictive = buffer.get("PREDICTIVE").getAsInt();
            }
        }

        if (json.has("activation")) {
            JsonObject activation = json.getAsJsonObject("activation");
            if (activation.has("distance")) activationDistance = activation.get("distance").getAsDouble();
            if (activation.has("distanceVertical")) activationDistanceVertical = activation.get("distanceVertical").getAsDouble();
            if (activation.has("shape")) activationShape = activation.get("shape").getAsString();
            if (activation.has("floorCeilingCheck")) floorCeilingCheck = activation.get("floorCeilingCheck").getAsBoolean();
            if (activation.has("facingCheck")) facingCheck = activation.get("facingCheck").getAsBoolean();
            if (activation.has("cooldownSeconds")) activationCooldownSeconds = activation.get("cooldownSeconds").getAsInt();
            if (activation.has("commitWindowSeconds")) activationCommitWindowSeconds = activation.get("commitWindowSeconds").getAsInt();
            // Velocity-aware activation (Phase 4)
            if (activation.has("velocityAwareActivation"))
                velocityAwareActivation = activation.get("velocityAwareActivation").getAsBoolean();
            if (activation.has("velocityRadiusBoostThreshold"))
                velocityRadiusBoostThreshold = activation.get("velocityRadiusBoostThreshold").getAsDouble();
        }

        if (json.has("ttl")) {
            JsonObject ttl = json.getAsJsonObject("ttl");
            if (ttl.has("warm")) ttlWarm = ttl.get("warm").getAsInt();
            if (ttl.has("recentHot")) ttlRecentHot = ttl.get("recentHot").getAsInt();
            if (ttl.has("predictive")) ttlPredictive = ttl.get("predictive").getAsInt();
            if (ttl.has("lowTraffic")) ttlLowTraffic = ttl.get("lowTraffic").getAsInt();
            if (ttl.has("bed")) ttlBed = ttl.get("bed").getAsInt();
            if (ttl.has("deathLocation")) ttlDeathLocation = ttl.get("deathLocation").getAsInt();
        }

        if (json.has("warps")) {
            JsonObject warps = json.getAsJsonObject("warps");
            if (warps.has("sourcePath")) warpsSourcePath = warps.get("sourcePath").getAsString();
            if (warps.has("watchForChanges")) warpsWatchForChanges = warps.get("watchForChanges").getAsBoolean();
            if (warps.has("watchIntervalSeconds")) warpsWatchIntervalSeconds = warps.get("watchIntervalSeconds").getAsInt();
            if (warps.has("worldField")) warpsWorldField = warps.get("worldField").getAsString();
            if (warps.has("idField")) warpsIdField = warps.get("idField").getAsString();
            if (warps.has("xField")) warpsXField = warps.get("xField").getAsString();
            if (warps.has("yField")) warpsYField = warps.get("yField").getAsString();
            if (warps.has("zField")) warpsZField = warps.get("zField").getAsString();
            if (warps.has("yawField")) warpsYawField = warps.get("yawField").getAsString();
        }
        if (json.has("worldNameRemap")) {
            JsonObject remap = json.getAsJsonObject("worldNameRemap");
            worldNameRemap = new java.util.HashMap<>();
            for (var entry : remap.entrySet()) {
                worldNameRemap.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        if (json.has("gravestones")) {
            JsonObject gs = json.getAsJsonObject("gravestones");
            if (gs.has("sourcePath")) gravestonesSourcePath = gs.get("sourcePath").getAsString();
            if (gs.has("watchForChanges")) gravestonesWatchForChanges = gs.get("watchForChanges").getAsBoolean();
            if (gs.has("watchIntervalSeconds")) gravestonesWatchIntervalSeconds = gs.get("watchIntervalSeconds").getAsInt();
        }

        if (json.has("integrations")) {
            JsonObject integrations = json.getAsJsonObject("integrations");
            if (integrations.has("gravestone")) {
                JsonObject gs = integrations.getAsJsonObject("gravestone");
                if (gs.has("enabled")) gravestoneIntegrationEnabled = gs.get("enabled").getAsBoolean();
                if (gs.has("pluginId")) gravestonePluginId = gs.get("pluginId").getAsString();
                if (gs.has("releaseOnBreak")) gravestoneReleaseOnBreak = gs.get("releaseOnBreak").getAsBoolean();
                if (gs.has("releaseOnEmpty")) gravestoneReleaseOnEmpty = gs.get("releaseOnEmpty").getAsBoolean();
            }
        }

        if (json.has("mysql")) {
            JsonObject mysql = json.getAsJsonObject("mysql");
            if (mysql.has("host")) mysqlHost = mysql.get("host").getAsString();
            if (mysql.has("port")) mysqlPort = mysql.get("port").getAsInt();
            if (mysql.has("database")) mysqlDatabase = mysql.get("database").getAsString();
            if (mysql.has("username")) mysqlUsername = mysql.get("username").getAsString();
            if (mysql.has("password")) mysqlPassword = mysql.get("password").getAsString();
            if (mysql.has("tablePrefix")) mysqlTablePrefix = mysql.get("tablePrefix").getAsString();
        }

        if (json.has("ui")) {
            JsonObject ui = json.getAsJsonObject("ui");
            if (ui.has("showInstancedPortals")) showInstancedPortals = ui.get("showInstancedPortals").getAsBoolean();
            if (ui.has("showBedSpawns")) showBedSpawns = ui.get("showBedSpawns").getAsBoolean();
            if (ui.has("showDeathLocations")) showDeathLocations = ui.get("showDeathLocations").getAsBoolean();
        }

        if (json.has("metrics")) {
            JsonObject metrics = json.getAsJsonObject("metrics");
            if (metrics.has("bstatsEnabled")) metricsEnabled = metrics.get("bstatsEnabled").getAsBoolean();
            if (metrics.has("bstatsPluginId")) bstatsPluginId = metrics.get("bstatsPluginId").getAsInt();
        }

        if (json.has("updateChecker")) {
            JsonObject uc = json.getAsJsonObject("updateChecker");
            if (uc.has("enabled")) updateCheckerEnabled = uc.get("enabled").getAsBoolean();
        }

        if (json.has("keepalive")) {
            JsonObject ka = json.getAsJsonObject("keepalive");
            if (ka.has("hot"))  keepaliveHot  = ka.get("hot").getAsBoolean();
            if (ka.has("warm")) keepaliveWarm = ka.get("warm").getAsBoolean();
            if (ka.has("cold")) keepaliveCold = ka.get("cold").getAsBoolean();
            if (ka.has("hotIntervalMinutes"))  keepaliveHotIntervalMinutes  = ka.get("hotIntervalMinutes").getAsInt();
            if (ka.has("warmIntervalMinutes")) keepaliveWarmIntervalMinutes = ka.get("warmIntervalMinutes").getAsInt();
            if (ka.has("coldIntervalMinutes")) keepaliveColdIntervalMinutes = ka.get("coldIntervalMinutes").getAsInt();
        }

        if (json.has("decay")) {
            JsonObject decay = json.getAsJsonObject("decay");
            if (decay.has("hotDecaySeconds"))    hotDecaySeconds    = decay.get("hotDecaySeconds").getAsInt();
            if (decay.has("warmDecayMinutes"))   warmDecayMinutes   = decay.get("warmDecayMinutes").getAsInt();
            if (decay.has("pollIntervalSeconds")) pollIntervalSeconds = decay.get("pollIntervalSeconds").getAsInt();
        }

        if (json.has("async")) {
            JsonObject async = json.getAsJsonObject("async");
            if (async.has("tpsMonitorEnabled"))
                tpsMonitorEnabled = async.get("tpsMonitorEnabled").getAsBoolean();
            if (async.has("tpsLowThreshold"))
                tpsLowThreshold = async.get("tpsLowThreshold").getAsDouble();
            if (async.has("tpsCriticalThreshold"))
                tpsCriticalThreshold = async.get("tpsCriticalThreshold").getAsDouble();
            if (async.has("maxLoadedChunksPressureThreshold"))
                maxLoadedChunksPressureThreshold = async.get("maxLoadedChunksPressureThreshold").getAsInt();
        }

        if (json.has("cache")) {
            JsonObject cache = json.getAsJsonObject("cache");
            if (cache.has("ownershipAuditIntervalMinutes"))
                ownershipAuditIntervalMinutes = cache.get("ownershipAuditIntervalMinutes").getAsInt();
        }

        // Corridor prioritization (Phase 4)
        if (json.has("densityBased")) {
            JsonObject db = json.getAsJsonObject("densityBased");
            if (db.has("corridor")) {
                JsonObject corridor = db.getAsJsonObject("corridor");
                if (corridor.has("enabled"))
                    corridorPrioritizationEnabled = corridor.get("enabled").getAsBoolean();
                if (corridor.has("radiusChunks"))
                    corridorRadiusChunks = corridor.get("radiusChunks").getAsInt();
            }
        }
    }

    // --- Getters ---

    public File getDataFolder() { return dataFolder; }

    /**
     * Deep-merges missing keys from the bundled default config.json into the
     * server's existing config.json on disk. Existing values are never overwritten —
     * only absent keys are added. This ensures new config sections introduced in
     * plugin updates are visible to server operators without resetting their settings.
     *
     * <p>The merge is recursive for JsonObject values. Scalar values and arrays are
     * copied as-is when absent. {@code _comment} keys from defaults are copied only
     * when the containing object itself is new; existing objects keep their own comments.
     */
    private static void migrateConfig(File configFile) {
        // Load bundled defaults
        JsonObject defaults;
        try (InputStream in = PluginConfig.class.getResourceAsStream("/config.json")) {
            if (in == null) return; // no bundled resource — nothing to migrate
            defaults = GSON.fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                    JsonObject.class);
        } catch (Exception e) {
            System.err.println("[OptiPortal] Could not read bundled config.json for migration: " + e.getMessage());
            return;
        }
        if (defaults == null) return;

        // Load user's file
        JsonObject user;
        try (Reader reader = new FileReader(configFile)) {
            user = GSON.fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            System.err.println("[OptiPortal] Could not read config.json for migration: " + e.getMessage());
            return;
        }
        if (user == null) user = new JsonObject();

        boolean changed = deepMergeDefaults(user, defaults);

        if (changed) {
            try (Writer writer = new FileWriter(configFile)) {
                GSON.toJson(user, writer);
                System.out.println("[OptiPortal] config.json updated with new default keys from this version.");
            } catch (Exception e) {
                System.err.println("[OptiPortal] Could not write migrated config.json: " + e.getMessage());
            }
        }
    }

    /**
     * Recursively adds keys present in {@code defaults} but absent in {@code target}.
     *
     * @return true if any key was added (caller should write the file back)
     */
    private static boolean deepMergeDefaults(JsonObject target, JsonObject defaults) {
        boolean changed = false;
        for (var entry : defaults.entrySet()) {
            String key = entry.getKey();
            com.google.gson.JsonElement defaultVal = entry.getValue();

            if (!target.has(key)) {
                // Key entirely absent — copy the whole subtree from defaults
                target.add(key, defaultVal.deepCopy());
                changed = true;
            } else if (defaultVal.isJsonObject() && target.get(key).isJsonObject()) {
                // Both sides are objects — recurse to fill in missing nested keys
                if (deepMergeDefaults(target.getAsJsonObject(key), defaultVal.getAsJsonObject())) {
                    changed = true;
                }
            }
            // Otherwise the user already has a value — leave it alone
        }
        return changed;
    }

    /**
     * Runs path discovery for warps and gravestones if either path looks like
     * the bundled placeholder or the file doesn't exist on disk.
     * If better paths are found, updates the in-memory config AND writes them
     * back into config.json so they persist across restarts.
     */
    private void runPathDiscoveryIfNeeded(File configFile) {
        boolean warpsNeedsDiscovery = isPlaceholderOrMissing(warpsSourcePath);
        boolean gravestonesNeedsDiscovery = isPlaceholderOrMissing(gravestonesSourcePath);

        if (!warpsNeedsDiscovery && !gravestonesNeedsDiscovery) return;

        System.out.println("[OptiPortal] One or more file paths not configured — running path discovery...");

        PathDiscovery.DiscoveryResult result = PathDiscovery.discover(dataFolder);

        boolean changed = false;

        if (warpsNeedsDiscovery && result.foundWarps()) {
            warpsSourcePath = result.warpsPath;
            System.out.println("[OptiPortal] Auto-configured warps path: " + warpsSourcePath);
            changed = true;
        }

        if (gravestonesNeedsDiscovery && result.foundGravestones()) {
            gravestonesSourcePath = result.gravestonesPath;
            System.out.println("[OptiPortal] Auto-configured gravestones path: " + gravestonesSourcePath);
            changed = true;
        }

        if (changed) {
            persistDiscoveredPaths(configFile);
        }
    }

    /**
     * Returns true if a path is blank or the file doesn't exist on disk.
     */
    private boolean isPlaceholderOrMissing(String path) {
        if (path == null || path.isBlank()) return true;
        return !new File(path).exists();
    }

    /**
     * Writes the currently-configured warps and gravestones source paths back into
     * config.json on disk so they survive a server restart.
     * Uses a targeted JSON patch — all other config values and comment keys are preserved.
     */
    private void persistDiscoveredPaths(File configFile) {
        try {
            // Read current JSON (preserves _comment keys and all other values)
            JsonObject json;
            try (Reader reader = new FileReader(configFile)) {
                json = GSON.fromJson(reader, JsonObject.class);
            }
            if (json == null) json = new JsonObject();

            // Patch warps.sourcePath
            if (!json.has("warps") || !json.get("warps").isJsonObject()) {
                json.add("warps", new JsonObject());
            }
            json.getAsJsonObject("warps").addProperty("sourcePath", warpsSourcePath);

            // Patch gravestones.sourcePath
            if (!json.has("gravestones") || !json.get("gravestones").isJsonObject()) {
                json.add("gravestones", new JsonObject());
            }
            json.getAsJsonObject("gravestones").addProperty("sourcePath", gravestonesSourcePath);

            // Write back
            try (Writer writer = new FileWriter(configFile)) {
                GSON.toJson(json, writer);
            }
            System.out.println("[OptiPortal] Discovered paths saved to config.json.");
        } catch (Exception e) {
            System.err.println("[OptiPortal] Could not persist discovered paths to config.json: " + e.getMessage());
        }
    }

    /**
     * Re-reads config.json from disk and updates all fields in-place.
     * Called by the UI Reload button.
     */
    public void reload() {
        File configFile = new File(dataFolder, "config.json");
        try (java.io.Reader reader = new java.io.FileReader(configFile)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            parseJson(json);
            System.out.println("[OptiPortal] Config reloaded from disk.");
        } catch (Exception e) {
            System.err.println("[OptiPortal] Config reload failed: " + e.getMessage());
        }
    }
    public String getBackend() { return backend; }
    public String getStartupLoadStrategy() { return startupLoadStrategy; }
    public int getSnapshotIntervalMinutes() { return snapshotIntervalMinutes; }
    public boolean isRebuildFromChunksOnCorruption() { return rebuildFromChunksOnCorruption; }
    public int getScheduledRebuildIntervalHours() { return scheduledRebuildIntervalHours; }
    public boolean isSuppressRamWarnings() { return suppressRamWarnings; }
    public int getLowTrafficThreshold() { return lowTrafficThreshold; }
    public boolean isImmuneToSimulationReduction() { return immuneToSimulationReduction; }
    public int getDefaultWarmRadius() { return defaultWarmRadius; }
    public int getWarmBatchSize() { return warmBatchSize; }
    public int getWarmBatchDelayMs() { return warmBatchDelayMs; }
    /** Default predictive load radius — matches ServerOptimizer sim distance max (7). */
    public int getPredictiveRadius() { return 7; }
    public String getDefaultStrategy() { return defaultStrategy; }
    public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
    public int getDefaultBufferSecondsWarm() { return defaultBufferSecondsWarm; }
    public int getDefaultBufferSecondsPredictive() { return defaultBufferSecondsPredictive; }
    public double getActivationDistance() { return activationDistance; }
    public double getActivationDistanceVertical() { return activationDistanceVertical; }
    public String getActivationShape() { return activationShape; }
    public boolean isFloorCeilingCheck() { return floorCeilingCheck; }
    public boolean isFacingCheck() { return facingCheck; }
    public int getActivationCooldownSeconds() { return activationCooldownSeconds; }
    public int getActivationCommitWindowSeconds() { return activationCommitWindowSeconds; }
    public int getTtlWarm() { return ttlWarm; }
    public int getTtlRecentHot() { return ttlRecentHot; }
    public int getTtlPredictive() { return ttlPredictive; }
    public int getTtlLowTraffic() { return ttlLowTraffic; }
    public int getTtlBed() { return ttlBed; }
    public int getTtlDeathLocation() { return ttlDeathLocation; }
    public boolean isPersistColdCache() { return persistColdCache; }
    public String getCacheDirectory() { return cacheDirectory; }
    public int getMaxCacheAgeDays() { return maxCacheAgeDays; }
    public String getWarpsSourcePath() { return warpsSourcePath; }
    public boolean isWarpsWatchForChanges() { return warpsWatchForChanges; }
    public int getWarpsWatchIntervalSeconds() { return warpsWatchIntervalSeconds; }
    public String getWarpsWorldField() { return warpsWorldField; }
    public String getWarpsIdField() { return warpsIdField; }
    public String getWarpsXField() { return warpsXField; }
    public String getWarpsYField() { return warpsYField; }
    public String getWarpsZField() { return warpsZField; }
    public String getWarpsYawField() { return warpsYawField; }
    /** Translate a world name from warps.json to the actual Hytale registry name. */
    public String remapWorldName(String name) {
        return worldNameRemap.getOrDefault(name, name);
    }
    public String getGravestonesSourcePath() { return gravestonesSourcePath; }
    public boolean isGravestonesWatchForChanges() { return gravestonesWatchForChanges; }
    public int getGravestonesWatchIntervalSeconds() { return gravestonesWatchIntervalSeconds; }
    public boolean isGravestoneIntegrationEnabled() { return gravestoneIntegrationEnabled; }
    public String getGravestonePluginId() { return gravestonePluginId; }
    public boolean isGravestoneReleaseOnBreak() { return gravestoneReleaseOnBreak; }
    public boolean isGravestoneReleaseOnEmpty() { return gravestoneReleaseOnEmpty; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public String getMysqlTablePrefix() { return mysqlTablePrefix; }
    public boolean isShowInstancedPortals() { return showInstancedPortals; }
    public boolean isShowBedSpawns() { return showBedSpawns; }
    public boolean isShowDeathLocations() { return showDeathLocations; }
    public boolean isMetricsEnabled() { return metricsEnabled; }
    public int getBstatsPluginId() { return bstatsPluginId; }
    public boolean isUpdateCheckerEnabled() { return updateCheckerEnabled; }

    public boolean isKeepaliveHot()  { return keepaliveHot; }
    public boolean isKeepaliveWarm() { return keepaliveWarm; }
    public boolean isKeepaliveCold() { return keepaliveCold; }
    public int getKeepaliveHotIntervalMinutes()  { return keepaliveHotIntervalMinutes; }
    public int getKeepaliveWarmIntervalMinutes() { return keepaliveWarmIntervalMinutes; }
    public int getKeepaliveColdIntervalMinutes() { return keepaliveColdIntervalMinutes; }

    public int getHotDecaySeconds()     { return hotDecaySeconds; }
    public int getWarmDecayMinutes()    { return warmDecayMinutes; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }

    /**
     * Estimated bytes per chunk for RAM estimation formula.
     * Default 262144 = 256 KB. Override in config.json as "bytesPerChunk".
     */
    private int bytesPerChunk = 262144; // 256 KB default

    public int getBytesPerChunk() { return bytesPerChunk; }

    /**
     * How often to audit chunk ownership for drift, in minutes.
     */
    public int getOwnershipAuditIntervalMinutes() { return ownershipAuditIntervalMinutes; }

    // TPS monitor getters
    public boolean isTpsMonitorEnabled() { return tpsMonitorEnabled; }
    public double getTpsLowThreshold() { return tpsLowThreshold; }
    public double getTpsCriticalThreshold() { return tpsCriticalThreshold; }
    public int getMaxLoadedChunksPressureThreshold() { return maxLoadedChunksPressureThreshold; }

    // Phase 4: Corridor prioritization getters
    public boolean isCorridorPrioritizationEnabled() { return corridorPrioritizationEnabled; }
    public int getCorridorRadiusChunks() { return corridorRadiusChunks; }

    // Phase 4: Velocity-aware activation getters
    public boolean isVelocityAwareActivation() { return velocityAwareActivation; }
    public double getVelocityRadiusBoostThreshold() { return velocityRadiusBoostThreshold; }
}
