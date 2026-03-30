package com.optiportal.config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Plugin configuration loader and accessor.
 * Loads config.json from the plugin data folder and provides typed accessors.
 * Supports migration of legacy config fields.
 */
public class PluginConfig {
    private static final Logger LOG = Logger.getLogger(PluginConfig.class.getName());

    // Data folder (set by load())
    private File dataFolder;

    // Backend
    private String backend = "rocksdb";
    private int bytesPerChunk = 98304; // 96 KB = 64 KB base × 1.5 overhead
    private String startupLoadStrategy = "sequential";
    private int snapshotIntervalMinutes = 60;
    private boolean suppressRamWarnings = false;
    private int lowTrafficThreshold = 10;
    private boolean immuneToSimulationReduction = false;
    private int stagedLoadConcurrency = 5;

    // Defaults
    private int defaultWarmRadius = 5;
    private int warmBatchSize = 10;
    private int warmBatchDelayMs = 50;
    private String defaultStrategy = "radius";
    private int defaultTimeoutSeconds = 30;
    private int defaultBufferSecondsWarm = 60;
    private int defaultBufferSecondsPredictive = 30;

    // Activation
    private double activationDistance = 48.0;
    private double activationDistanceVertical = 64.0;
    private String activationShape = "cylinder";
    private boolean floorCeilingCheck = true;
    private boolean facingCheck = false;
    private int activationCooldownSeconds = 30;
    private int activationCommitWindowSeconds = 3;
    // Velocity-aware activation (Phase 4)
    private boolean velocityAwareActivation = false;
    private double velocityRadiusBoostThreshold = 0.3;
    private int predictiveRadius = 7;

    // Decay (how fast zones cool down)
    private int hotDecaySeconds = 30;
    private int warmDecayMinutes = 45;
    private int pollIntervalSeconds = 1;

    // TTL (days, -1 = never)
    private int ttlWarm = -1;
    private int ttlRecentHot = 7;
    private int ttlPredictive = 2;
    private int ttlLowTraffic = 1;
    private int ttlBed = 3;
    private int ttlDeathLocation = 1;
    private int ttlCleanupIntervalHours = 24;

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
    private Map<String, String> worldNameRemap = new HashMap<>();

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
    private boolean uiEnabled = true;
    private String uiPagePath = "Common/UI/Custom/OptiPortalUI.ui";

    // Metrics
    private boolean metricsEnabled = false;
    private String metricsEndpoint = "";
    private int metricsIntervalSeconds = 60;

    // Update Checker
    private boolean updateCheckerEnabled = true;
    private String updateCheckerUrl = "";

    // Rebuild from chunks
    private boolean rebuildFromChunksOnCorruption = false;
    private int scheduledRebuildIntervalHours = 24;

    // Async TPS monitor
    private boolean tpsMonitorEnabled = true;
    private double tpsLowThreshold = 18.0;
    private double tpsCriticalThreshold = 12.0;

    // Corridor prioritization (Phase 4)
    private boolean corridorPrioritizationEnabled = true;
    private int corridorRadiusChunks = 3;

    // Portal links
    private int portalLinksConfidenceThreshold = 5;
    private int portalLinksPendingDecayDays = 7;

    // Keepalive per-tier schedule
    // Defaults are set high (30/60/120 min) because ChunkUnloadGuard (H1) is now the
    // primary retention mechanism. The heartbeat is a belt-and-suspenders fallback only.
    private boolean keepaliveHot = true;
    private int keepaliveHotIntervalMinutes = 30;
    private boolean keepaliveWarm = true;
    private int keepaliveWarmIntervalMinutes = 60;
    private boolean keepaliveCold = false;
    private int keepaliveColdIntervalMinutes = 120;

    // Chunk pressure / auditing
    private int maxLoadedChunksPressureThreshold = 2048;
    private int ownershipAuditIntervalMinutes = 60;

    /**
     * Load configuration from the given data folder.
     * If config.json does not exist, creates a default one.
     */
    public static PluginConfig load(File dataFolder) {
        File configFile = new File(dataFolder, "config.json");
        if (!configFile.exists()) {
            migrateConfig(configFile);
        }
        try {
            String json = new String(java.nio.file.Files.readAllBytes(configFile.toPath()));
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            PluginConfig config = new PluginConfig();
            config.dataFolder = dataFolder;
            config.parseJson(root);
            config.runPathDiscoveryIfNeeded(configFile);
            return config;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to load config.json", e);
            throw new RuntimeException(e);
        }
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
        if (json.has("stagedLoadConcurrency")) stagedLoadConcurrency = json.get("stagedLoadConcurrency").getAsInt();

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
            if (activation.has("predictiveRadius"))
                predictiveRadius = activation.get("predictiveRadius").getAsInt();
        }

        if (json.has("decay")) {
            JsonObject decay = json.getAsJsonObject("decay");
            if (decay.has("hotDecaySeconds")) hotDecaySeconds = decay.get("hotDecaySeconds").getAsInt();
            if (decay.has("warmDecayMinutes")) warmDecayMinutes = decay.get("warmDecayMinutes").getAsInt();
            if (decay.has("pollIntervalSeconds")) pollIntervalSeconds = decay.get("pollIntervalSeconds").getAsInt();
        }

        if (json.has("ttl")) {
            JsonObject ttl = json.getAsJsonObject("ttl");
            if (ttl.has("warm")) ttlWarm = ttl.get("warm").getAsInt();
            if (ttl.has("recentHot")) ttlRecentHot = ttl.get("recentHot").getAsInt();
            if (ttl.has("predictive")) ttlPredictive = ttl.get("predictive").getAsInt();
            if (ttl.has("lowTraffic")) ttlLowTraffic = ttl.get("lowTraffic").getAsInt();
            if (ttl.has("bed")) ttlBed = ttl.get("bed").getAsInt();
            if (ttl.has("deathLocation")) ttlDeathLocation = ttl.get("deathLocation").getAsInt();
            if (ttl.has("cleanupIntervalHours")) ttlCleanupIntervalHours = ttl.get("cleanupIntervalHours").getAsInt();
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
            worldNameRemap = new HashMap<>();
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
            if (ui.has("enabled")) uiEnabled = ui.get("enabled").getAsBoolean();
            if (ui.has("pagePath")) uiPagePath = ui.get("pagePath").getAsString();
        }

        if (json.has("metrics")) {
            JsonObject metrics = json.getAsJsonObject("metrics");
            if (metrics.has("enabled")) metricsEnabled = metrics.get("enabled").getAsBoolean();
            if (metrics.has("endpoint")) metricsEndpoint = metrics.get("endpoint").getAsString();
            if (metrics.has("intervalSeconds")) metricsIntervalSeconds = metrics.get("intervalSeconds").getAsInt();
        }

        if (json.has("updateChecker")) {
            JsonObject updateChecker = json.getAsJsonObject("updateChecker");
            if (updateChecker.has("enabled")) updateCheckerEnabled = updateChecker.get("enabled").getAsBoolean();
            if (updateChecker.has("url")) updateCheckerUrl = updateChecker.get("url").getAsString();
        }

        if (json.has("portalLinks")) {
            JsonObject portalLinks = json.getAsJsonObject("portalLinks");
            if (portalLinks.has("confidenceThreshold")) portalLinksConfidenceThreshold = portalLinks.get("confidenceThreshold").getAsInt();
            if (portalLinks.has("pendingDecayDays")) portalLinksPendingDecayDays = portalLinks.get("pendingDecayDays").getAsInt();
        }

        if (json.has("keepalive")) {
            JsonObject ka = json.getAsJsonObject("keepalive");
            if (ka.has("hot")) {
                JsonElement hotEl = ka.get("hot");
                if (hotEl.isJsonObject()) {
                    JsonObject hot = hotEl.getAsJsonObject();
                    if (hot.has("enabled")) keepaliveHot = hot.get("enabled").getAsBoolean();
                    if (hot.has("intervalMinutes")) keepaliveHotIntervalMinutes = hot.get("intervalMinutes").getAsInt();
                } else {
                    keepaliveHot = hotEl.getAsBoolean();
                }
            }
            if (ka.has("warm")) {
                JsonElement warmEl = ka.get("warm");
                if (warmEl.isJsonObject()) {
                    JsonObject warm = warmEl.getAsJsonObject();
                    if (warm.has("enabled")) keepaliveWarm = warm.get("enabled").getAsBoolean();
                    if (warm.has("intervalMinutes")) keepaliveWarmIntervalMinutes = warm.get("intervalMinutes").getAsInt();
                } else {
                    keepaliveWarm = warmEl.getAsBoolean();
                }
            }
            if (ka.has("cold")) {
                JsonElement coldEl = ka.get("cold");
                if (coldEl.isJsonObject()) {
                    JsonObject cold = coldEl.getAsJsonObject();
                    if (cold.has("enabled")) keepaliveCold = cold.get("enabled").getAsBoolean();
                    if (cold.has("intervalMinutes")) keepaliveColdIntervalMinutes = cold.get("intervalMinutes").getAsInt();
                } else {
                    keepaliveCold = coldEl.getAsBoolean();
                }
            }
            // Legacy flat keys (old config format)
            if (ka.has("hotIntervalMinutes")) keepaliveHotIntervalMinutes = ka.get("hotIntervalMinutes").getAsInt();
            if (ka.has("warmIntervalMinutes")) keepaliveWarmIntervalMinutes = ka.get("warmIntervalMinutes").getAsInt();
            if (ka.has("coldIntervalMinutes")) keepaliveColdIntervalMinutes = ka.get("coldIntervalMinutes").getAsInt();
        }

        if (json.has("chunkPressure")) {
            JsonObject cp = json.getAsJsonObject("chunkPressure");
            if (cp.has("maxLoadedThreshold")) maxLoadedChunksPressureThreshold = cp.get("maxLoadedThreshold").getAsInt();
            if (cp.has("ownershipAuditIntervalMinutes")) ownershipAuditIntervalMinutes = cp.get("ownershipAuditIntervalMinutes").getAsInt();
        }

        if (json.has("cache")) {
            JsonObject cache = json.getAsJsonObject("cache");
            if (cache.has("persistColdCache")) persistColdCache = cache.get("persistColdCache").getAsBoolean();
            if (cache.has("cacheDirectory")) cacheDirectory = cache.get("cacheDirectory").getAsString();
            if (cache.has("maxCacheAgeDays")) maxCacheAgeDays = cache.get("maxCacheAgeDays").getAsInt();
        }

        if (json.has("rebuildFromChunks")) {
            JsonObject rebuild = json.getAsJsonObject("rebuildFromChunks");
            if (rebuild.has("enabled")) rebuildFromChunksOnCorruption = rebuild.get("enabled").getAsBoolean();
            if (rebuild.has("intervalHours")) scheduledRebuildIntervalHours = rebuild.get("intervalHours").getAsInt();
        }

        if (json.has("tpsMonitor")) {
            JsonObject tps = json.getAsJsonObject("tpsMonitor");
            if (tps.has("enabled")) tpsMonitorEnabled = tps.get("enabled").getAsBoolean();
            if (tps.has("lowThreshold")) tpsLowThreshold = tps.get("lowThreshold").getAsDouble();
            if (tps.has("criticalThreshold")) tpsCriticalThreshold = tps.get("criticalThreshold").getAsDouble();
        }
    }

    private static void migrateConfig(File configFile) {
        LOG.log(Level.INFO, "Creating default config.json");
        JsonObject defaults = new JsonObject();
        defaults.addProperty("warmRadius", 5);
        defaults.addProperty("warmBatchSize", 10);
        defaults.addProperty("warmBatchDelayMs", 50);
        defaults.addProperty("strategy", "radius");
        defaults.addProperty("timeoutSeconds", 30);
        JsonObject buffer = new JsonObject();
        buffer.addProperty("WARM", 60);
        buffer.addProperty("PREDICTIVE", 30);
        defaults.add("bufferSeconds", buffer);

        JsonObject activation = new JsonObject();
        activation.addProperty("distance", 48.0);
        activation.addProperty("distanceVertical", 64.0);
        activation.addProperty("shape", "cylinder");
        activation.addProperty("floorCeilingCheck", true);
        activation.addProperty("facingCheck", false);
        activation.addProperty("cooldownSeconds", 30);
        activation.addProperty("commitWindowSeconds", 3);
        activation.addProperty("predictiveRadius", 7);

        JsonObject ttl = new JsonObject();
        ttl.addProperty("warm", -1);
        ttl.addProperty("recentHot", 7);
        ttl.addProperty("predictive", 2);
        ttl.addProperty("lowTraffic", 1);
        ttl.addProperty("bed", 3);
        ttl.addProperty("deathLocation", 1);
        ttl.addProperty("cleanupIntervalHours", 24);

        JsonObject cache = new JsonObject();
        cache.addProperty("persistColdCache", true);
        cache.addProperty("cacheDirectory", "preload-cache/");
        cache.addProperty("maxCacheAgeDays", 7);

        JsonObject warps = new JsonObject();
        warps.addProperty("sourcePath", "universe/warps.json");
        warps.addProperty("watchForChanges", true);
        warps.addProperty("watchIntervalSeconds", 30);
        warps.addProperty("worldField", "World");
        warps.addProperty("idField", "Id");
        warps.addProperty("xField", "X");
        warps.addProperty("yField", "Y");
        warps.addProperty("zField", "Z");
        warps.addProperty("yawField", "Yaw");

        JsonObject gravestones = new JsonObject();
        gravestones.addProperty("sourcePath", "plugins/Gravestones/gravestones.json");
        gravestones.addProperty("watchForChanges", true);
        gravestones.addProperty("watchIntervalSeconds", 5);

        JsonObject integrations = new JsonObject();
        JsonObject gsIntegration = new JsonObject();
        gsIntegration.addProperty("enabled", false);
        gsIntegration.addProperty("pluginId", "gravestones");
        gsIntegration.addProperty("releaseOnBreak", true);
        gsIntegration.addProperty("releaseOnEmpty", true);
        integrations.add("gravestone", gsIntegration);

        JsonObject mysql = new JsonObject();
        mysql.addProperty("host", "localhost");
        mysql.addProperty("port", 3306);
        mysql.addProperty("database", "preload");
        mysql.addProperty("username", "");
        mysql.addProperty("password", "");
        mysql.addProperty("tablePrefix", "pre_");

        JsonObject ui = new JsonObject();
        ui.addProperty("enabled", true);
        ui.addProperty("pagePath", "Common/UI/Custom/OptiPortalUI.ui");

        JsonObject metrics = new JsonObject();
        metrics.addProperty("enabled", false);
        metrics.addProperty("endpoint", "");
        metrics.addProperty("intervalSeconds", 60);

        JsonObject updateChecker = new JsonObject();
        updateChecker.addProperty("enabled", true);
        updateChecker.addProperty("url", "");

        JsonObject rebuild = new JsonObject();
        rebuild.addProperty("enabled", false);
        rebuild.addProperty("intervalHours", 24);

        JsonObject keepaliveHotObj = new JsonObject();
        keepaliveHotObj.addProperty("enabled", true);
        keepaliveHotObj.addProperty("intervalMinutes", 30);
        JsonObject keepaliveWarmObj = new JsonObject();
        keepaliveWarmObj.addProperty("enabled", true);
        keepaliveWarmObj.addProperty("intervalMinutes", 60);
        JsonObject keepaliveColdObj = new JsonObject();
        keepaliveColdObj.addProperty("enabled", false);
        keepaliveColdObj.addProperty("intervalMinutes", 120);
        JsonObject keepalive = new JsonObject();
        keepalive.add("hot", keepaliveHotObj);
        keepalive.add("warm", keepaliveWarmObj);
        keepalive.add("cold", keepaliveColdObj);

        JsonObject root = new JsonObject();
        root.addProperty("backend", "rocksdb");
        root.addProperty("bytesPerChunk", 98304);
        root.addProperty("startupLoadStrategy", "sequential");
        root.addProperty("snapshotIntervalMinutes", 60);
        root.addProperty("suppressRamWarnings", false);
        root.addProperty("lowTrafficThreshold", 10);
        root.addProperty("immuneToSimulationReduction", false);
        root.addProperty("stagedLoadConcurrency", 5);
        root.add("defaults", defaults);
        root.add("activation", activation);
        root.add("ttl", ttl);
        root.add("cache", cache);
        root.add("warps", warps);
        root.add("gravestones", gravestones);
        root.add("integrations", integrations);
        root.add("mysql", mysql);
        root.add("ui", ui);
        root.add("metrics", metrics);
        root.add("updateChecker", updateChecker);
        root.add("rebuildFromChunks", rebuild);
        root.add("keepalive", keepalive);

        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(root.toString());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to write default config.json", e);
            throw new RuntimeException(e);
        }
    }

    private static boolean deepMergeDefaults(JsonObject target, JsonObject defaults) {
        boolean changed = false;
        for (var entry : defaults.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (!target.has(key)) {
                target.add(key, value);
                changed = true;
            } else if (value.isJsonObject() && target.get(key).isJsonObject()) {
                changed |= deepMergeDefaults(target.get(key).getAsJsonObject(), value.getAsJsonObject());
            }
        }
        return changed;
    }

    private void runPathDiscoveryIfNeeded(File configFile) {
        // Placeholder for future path discovery logic
    }

    private boolean isPlaceholderOrMissing(String path) {
        return path == null || path.isEmpty() || path.contains("PLACEHOLDER") || path.contains("MISSING");
    }

    private void persistDiscoveredPaths(File configFile) {
        // Placeholder for persisting discovered paths
    }

    public void reload() {
        if (dataFolder == null) {
            throw new IllegalStateException("PluginConfig was not loaded via load(File) — dataFolder is null");
        }
        reload(new File(dataFolder, "config.json"));
    }
    
    /**
     * Reload configuration from a specific file path.
     * Used internally by OptiPortal for hot-reload.
     */
    public void reload(File configFile) {
        if (configFile.exists()) {
            try {
                String json = new String(java.nio.file.Files.readAllBytes(configFile.toPath()));
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                parseJson(root);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Failed to reload config.json", e);
            }
        }
    }

    // Getters
    public String getBackend() { return backend; }
    public int getBytesPerChunk() { return bytesPerChunk; }
    public String getStartupLoadStrategy() { return startupLoadStrategy; }
    public int getSnapshotIntervalMinutes() { return snapshotIntervalMinutes; }
    public boolean isSuppressRamWarnings() { return suppressRamWarnings; }
    public int getLowTrafficThreshold() { return lowTrafficThreshold; }
    public boolean isImmuneToSimulationReduction() { return immuneToSimulationReduction; }
    public int getStagedLoadConcurrency() { return stagedLoadConcurrency; }
    public int getDefaultWarmRadius() { return defaultWarmRadius; }
    public int getWarmBatchSize() { return warmBatchSize; }
    public int getWarmBatchDelayMs() { return warmBatchDelayMs; }
    public int getPredictiveRadius() { return predictiveRadius; }
    public int getHotDecaySeconds() { return hotDecaySeconds; }
    public int getWarmDecayMinutes() { return warmDecayMinutes; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
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
    public boolean isVelocityAwareActivation() { return velocityAwareActivation; }
    public double getVelocityRadiusBoostThreshold() { return velocityRadiusBoostThreshold; }
    public int getTtlWarm() { return ttlWarm; }
    public int getTtlRecentHot() { return ttlRecentHot; }
    public int getTtlPredictive() { return ttlPredictive; }
    public int getTtlLowTraffic() { return ttlLowTraffic; }
    public int getTtlBed() { return ttlBed; }
    public int getTtlDeathLocation() { return ttlDeathLocation; }
    public int getTtlCleanupIntervalHours() { return ttlCleanupIntervalHours; }
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
    public boolean isUiEnabled() { return uiEnabled; }
    public String getUiPagePath() { return uiPagePath; }
    public boolean isMetricsEnabled() { return metricsEnabled; }
    public String getMetricsEndpoint() { return metricsEndpoint; }
    public int getMetricsIntervalSeconds() { return metricsIntervalSeconds; }
    public boolean isUpdateCheckerEnabled() { return updateCheckerEnabled; }
    public String getUpdateCheckerUrl() { return updateCheckerUrl; }
    public boolean isRebuildFromChunksOnCorruption() { return rebuildFromChunksOnCorruption; }
    public int getScheduledRebuildIntervalHours() { return scheduledRebuildIntervalHours; }
    public boolean isTpsMonitorEnabled() { return tpsMonitorEnabled; }
    public double getTpsLowThreshold() { return tpsLowThreshold; }
    public double getTpsCriticalThreshold() { return tpsCriticalThreshold; }
    public boolean isCorridorPrioritizationEnabled() { return corridorPrioritizationEnabled; }
    public int getCorridorRadiusChunks() { return corridorRadiusChunks; }
    public int getPortalLinksConfidenceThreshold() { return portalLinksConfidenceThreshold; }
    public int getPortalLinksPendingDecayDays() { return portalLinksPendingDecayDays; }
    public boolean isKeepaliveHot() { return keepaliveHot; }
    public int getKeepaliveHotIntervalMinutes() { return keepaliveHotIntervalMinutes; }
    public boolean isKeepaliveWarm() { return keepaliveWarm; }
    public int getKeepaliveWarmIntervalMinutes() { return keepaliveWarmIntervalMinutes; }
    public boolean isKeepaliveCold() { return keepaliveCold; }
    public int getKeepaliveColdIntervalMinutes() { return keepaliveColdIntervalMinutes; }
    public int getMaxLoadedChunksPressureThreshold() { return maxLoadedChunksPressureThreshold; }
    public int getOwnershipAuditIntervalMinutes() { return ownershipAuditIntervalMinutes; }
    public File getDataFolder() { return dataFolder; }
}
