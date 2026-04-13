package com.optiportal.preload;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.hypixel.hytale.builtin.portals.PortalsPlugin;
import com.hypixel.hytale.builtin.portals.components.PortalDevice;
import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import com.optiportal.cache.CacheManager;
import com.optiportal.config.PluginConfig;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.storage.StorageBackend;

/**
 * Manages all WARM zone lifecycle.
 *
 * Startup sequence (STAGED mode):
 *   1. AllWorldsLoadedEvent fires → worlds are safe to load chunks into
 *   2. Load WARM zones in priority order: COLD-cached first, then UNVISITED
 *   3. PREDICTIVE zones are skipped entirely — loaded on first approach
 *
 * The staged load is triggered by AllWorldsLoadedEvent, not start0(), because
 * chunk loading calls require worlds to be registered in WorldRegistry first.
 */
public class WarmZoneManager {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final PluginConfig config;
    private final StorageBackend storage;
    private final CacheManager cacheManager;
    private final ScheduledExecutorService executor;

    // Injected after construction to avoid circular dependency
    private ChunkPreloader chunkPreloader;

    // Programmatically registered warm zones (from API or /preload setwarm)
    private final Map<String, PortalEntry> managedZones = new ConcurrentHashMap<>();

    // Track whether worlds are loaded yet
    private final AtomicBoolean worldsReady = new AtomicBoolean(false);
    // Guards runStagedLoad — only one execution allowed, ever.
    private final AtomicBoolean stagedLoadStarted = new AtomicBoolean(false);
    // Shutdown flag to prevent late startup callbacks from doing work
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile CompletableFuture<Void> universeReadyTask;

    public WarmZoneManager(PluginConfig config, StorageBackend storage,
                           CacheManager cacheManager, ScheduledExecutorService executor) {
        this.config = config;
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.executor = executor;
    }

    /** Called after ChunkPreloader is constructed to break circular dependency. */
    public void setChunkPreloader(ChunkPreloader chunkPreloader) {
        this.chunkPreloader = chunkPreloader;
    }

    /**
     * Register world lifecycle listeners.
     *
     * AllWorldsLoadedEvent fires once all worlds are up. At that point we seed
     * WorldRegistry directly from Universe.get().getWorlds() — no reflection needed —
     * then trigger the staged preload.
     */
    @SuppressWarnings("unchecked")
    public void registerWorldsLoadedListener(EventRegistry events) {
        events.register(AllWorldsLoadedEvent.class, event -> {
            // Guard against post-shutdown execution
            if (stopped.get()) {
                LOG.fine(() -> "[OptiPortal] AllWorldsLoadedEvent handler skipped: already stopped");
                return;
            }
            // Seed WorldRegistry from Universe — this is the authoritative world map
            Universe universe = Universe.get();
            if (universe != null && chunkPreloader != null) {
                // Load one snapshot for all per-world scans rather than one loadAll() per world
                java.util.List<PortalEntry> startupSnapshot = storage.loadAll();
                universe.getWorlds().values().forEach(world -> {
                    chunkPreloader.getWorldRegistry().addWorld(world);
                    LOG.info(() -> "[OptiPortal] Seeded world from Universe: " + world.getName());
                    // Auto-detect portal destination worlds from PortalWorld resource
                    scanWorldForPortalDestination(world, startupSnapshot);
                });
            } else {
                LOG.warning(() -> "[OptiPortal] AllWorldsLoadedEvent: Universe.get()="
                    + universe + " chunkPreloader=" + chunkPreloader);
            }

            worldsReady.set(true);
            if (pollTask != null) pollTask.cancel(false);
            if (stagedLoadStarted.compareAndSet(false, true)) {
                logWorldNames("AllWorldsLoadedEvent");
                executor.submit(this::runStagedLoad);
            }
        });
    }

    public void triggerStagedLoadOnce() {
        // Guard against post-shutdown execution
        if (stopped.get()) {
            LOG.fine(() -> "[OptiPortal] triggerStagedLoadOnce skipped: already stopped");
            return;
        }
        LOG.fine(() -> "[OptiPortal] triggerStagedLoadOnce called, stagedLoadStarted=" + stagedLoadStarted.get());
        worldsReady.set(true);
        if (pollTask != null) pollTask.cancel(false);
        if (stagedLoadStarted.compareAndSet(false, true)) {
            logWorldNames("first player join");
            executor.submit(this::runStagedLoad);
        } else {
            LOG.fine(() -> "[OptiPortal] triggerStagedLoadOnce: staged load already running, ignoring.");
        }
    }

    /** Scheduled task for pollWorldsReady. */
    private volatile java.util.concurrent.ScheduledFuture<?> pollTask;

    public void startStagedLoad() {
        // Guard against post-shutdown execution
        if (stopped.get()) {
            LOG.fine(() -> "[OptiPortal] startStagedLoad skipped: already stopped");
            return;
        }
        
        // Primary path: hook into Universe.getUniverseReady() — fires exactly once when
        // the universe is fully initialised. No polling needed.
        Universe universe = Universe.get();
        if (universe != null) {
            // Split the future chain: store the direct thenRunAsync stage for cancellation,
            // attach exceptionally as a side effect without overwriting.
            CompletableFuture<Void> readyTask =
                    universe.getUniverseReady().thenRunAsync(this::triggerStagedLoadOnce, executor);
            universeReadyTask = readyTask;
            readyTask.exceptionally(ex -> {
                LOG.warning(() -> "[OptiPortal] startStagedLoad: Universe.getUniverseReady() failed: "
                        + ex.getMessage() + " — falling back to polling");
                if (!stopped.get()) {
                    startPollingFallback();
                }
                return null;
            });
        } else {
            // Universe not yet available — use polling fallback
            LOG.warning(() -> "[OptiPortal] startStagedLoad: Universe.get() is null — using polling fallback");
            startPollingFallback();
        }
    }

    /** Polling fallback for environments where Universe.getUniverseReady() is unavailable. */
    private void startPollingFallback() {
        // Guard against post-shutdown execution
        if (stopped.get()) {
            LOG.fine(() -> "[OptiPortal] startPollingFallback skipped: already stopped");
            return;
        }
        pollTask = executor.scheduleWithFixedDelay(this::pollWorldsReady, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private final java.util.concurrent.atomic.AtomicInteger pollCount = new java.util.concurrent.atomic.AtomicInteger(0);

    private void pollWorldsReady() {
        // Guard against post-shutdown execution
        if (stopped.get()) {
            LOG.fine(() -> "[OptiPortal] pollWorldsReady skipped: already stopped");
            return;
        }
        if (worldsReady.get()) {
            if (pollTask != null) pollTask.cancel(false);
            return;
        }
        if (chunkPreloader == null) return;
        int count = pollCount.incrementAndGet();
        // Polling is a last-resort fallback — primary trigger is triggerStagedLoadOnce()
        // called from TeleportInterceptor when the first player joins.
        if (count >= 30) {
            LOG.warning(() -> "[OptiPortal] No player joined after 60s — staged load skipped.");
            worldsReady.set(true);
            if (pollTask != null) pollTask.cancel(false);
        }
    }

    private void logWorldNames(String trigger) {
        if (chunkPreloader != null) {
            java.util.Collection<com.hypixel.hytale.server.core.universe.world.World> worlds =
                    chunkPreloader.getWorldRegistry().getWorlds();
            LOG.info(() -> "[OptiPortal] Worlds ready (" + trigger + ", " + worlds.size() + " worlds): "
                    + worlds.stream()
                        .map(com.hypixel.hytale.server.core.universe.world.World::getName)
                        .collect(java.util.stream.Collectors.joining(", ")));
        }
    }

    private void runStagedLoad() {
        // Guard against post-shutdown execution
        if (stopped.get()) {
            LOG.fine(() -> "[OptiPortal] runStagedLoad skipped: already stopped");
            return;
        }
        
        LOG.info(() -> "[OptiPortal] runStagedLoad executing...");
        List<PortalEntry> all = storage.loadAll();

        List<PortalEntry> warmZones = all.stream()
                .filter(e -> e.getStrategy() == WarmStrategy.WARM)
                .filter(e -> !e.isInstanced())
                .sorted(Comparator.comparingInt(this::startupPriority))
                .toList();

        LOG.info(() -> "[OptiPortal] Staged load: " + warmZones.size()
                + " WARM zones (skipping " + (all.size() - warmZones.size()) + " PREDICTIVE)");

        if (warmZones.isEmpty()) {
            LOG.info(() -> "[OptiPortal] Startup load complete: 0 loaded, 0 skipped.");
            return;
        }

        int maxConcurrent = Math.max(1, config.getStagedLoadConcurrency());
        Semaphore sem = new Semaphore(maxConcurrent);
        AtomicInteger loaded = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();

        for (PortalEntry zone : warmZones) {
            try {
                sem.acquire(); // wait for a free slot — bounded by zone load time, not 120s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning(() -> "[OptiPortal] runStagedLoad interrupted.");
                break;
            }

            CompletableFuture<Void> f = loadWarmZone(zone)
                    .orTimeout(120, TimeUnit.SECONDS)
                    .handle((v, ex) -> {
                        sem.release();
                        if (ex != null) {
                            LOG.warning(() -> "[OptiPortal] Warm load failed for "
                                    + zone.getId() + ": " + ex.getMessage());
                            skipped.incrementAndGet();
                        } else {
                            loaded.incrementAndGet();
                        }
                        return (Void) null;
                    });
            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) ->
                    LOG.info(() -> "[OptiPortal] Startup load complete: "
                            + loaded.get() + " loaded, " + skipped.get() + " skipped."));

        // PRED zones are never chunk-loaded at startup, so their RAM stats are never
        // written unless a player has approached them before. Zones whose tier was
        // restored from the registry arrive with ramMarginalMB == 0 and show "--" in
        // the UI indefinitely. Compute and persist the expected RAM value from the zone
        // radius so the column shows a meaningful number immediately after restart.
        List<PortalEntry> statlessPred = all.stream()
                .filter(e -> e.getStrategy() != WarmStrategy.WARM)
                .filter(e -> !e.isInstanced())
                .filter(e -> e.getRamMarginalMB() <= 0)
                .toList();
        for (PortalEntry zone : statlessPred) {
            int fallback = config.getPredictiveRadius();
            int rx = zone.getWarmRadiusX() != null ? zone.getWarmRadiusX()
                   : zone.getWarmRadius() > 0      ? zone.getWarmRadius()
                   : fallback;
            int rz = zone.getWarmRadiusZ() != null ? zone.getWarmRadiusZ()
                   : zone.getWarmRadius() > 0      ? zone.getWarmRadius()
                   : fallback;
            int chunkCount = (2 * rx + 1) * (2 * rz + 1);
            zone.setRamMarginalMB((chunkCount * (double) config.getBytesPerChunk()) / (1024.0 * 1024.0));
            storage.save(zone);
        }
        if (!statlessPred.isEmpty()) {
            final int n = statlessPred.size();
            LOG.info(() -> "[OptiPortal] Startup: initialized RAM stats for " + n + " PRED zone(s).");
        }
    }

    private int startupPriority(PortalEntry e) {
        // P1: already has a COLD cache snapshot — fast restore
        // P2: UNVISITED — needs full load from disk, slower
        return switch (cacheManager.getZoneTier(e.getId())) {
            case COLD -> 1;
            default   -> 2;
        };
    }

    /**
     * Returns true if the given world is suitable for preloading.
     * A world with a PortalWorld resource must have exists()==true and a non-null spawn.
     * Regular (non-portal) worlds always return true.
     * Null or dead worlds return false.
     */
    public static boolean isPortalWorldUsable(World world) {
        if (world == null) return false;
        try {
            PortalWorld portalWorld = world.getEntityStore()
                    .getStore().getResource(PortalWorld.getResourceType());
            if (portalWorld == null) return true; // not a portal world — allow preload
            return portalWorld.exists() && portalWorld.getSpawnPoint() != null;
        } catch (Exception e) {
            // Resource not registered on this world — it is a regular world
            return true;
        }
    }

    /**
     * Load a warm zone's chunks (non-ticking geometry only, no entity simulation).
     * Uses getNonTickingChunkAsync — safe to call from any thread.
     * Returns a future that completes when the inner ring is loaded.
     */
    public CompletableFuture<Void> loadWarmZone(PortalEntry entry) {
        // H3: skip if the destination portal world is dead or has no spawn.
        // Only validate destination when a UUID is known or entry is a PORTAL type —
        // BED/DEATH/MANUAL entries have no separate destination world to gate on.
        if (entry.getDestinationWorldUuid() != null) {
            World zoneWorldCheck = chunkPreloader != null
                    ? chunkPreloader.getWorldRegistry().resolveWorld(
                            entry.getDestinationWorldUuid(), entry.getWorld())
                    : null;
            if (!isPortalWorldUsable(zoneWorldCheck)) {
                LOG.info("[OptiPortal] loadWarmZone: skipping dead/invalid portal world: "
                        + entry.getWorld() + " (zone=" + entry.getId() + ")");
                return CompletableFuture.completedFuture(null);
            }
        }

        int cx     = ChunkPreloader.toChunkCoord(entry.getX());
        int cz     = ChunkPreloader.toChunkCoord(entry.getZ());
        int radiusX = resolveRadiusX(entry);
        int radiusZ = resolveRadiusZ(entry);

        LOG.fine(() -> "[OptiPortal] Loading WARM zone: " + entry.getId()
                + " world=" + entry.getWorld()
                + " cx=" + cx + " cz=" + cz + " rx=" + radiusX + " rz=" + radiusZ);

        // Estimated RAM: bytesPerChunk already includes the 1.5x overhead factor.
        int chunkCount = (2 * radiusX + 1) * (2 * radiusZ + 1);
        double estimatedMB = (chunkCount * (double) config.getBytesPerChunk()) / (1024.0 * 1024.0);

        return chunkPreloader.warmLoad(entry, cx, cz, radiusX, radiusZ)
                // D4: supply executor so storage I/O runs on the plugin executor,
                //     not ForkJoinPool.commonPool() (the thenRunAsync default).
                .thenRunAsync(() -> {
                    if (stopped.get()) return; // Guard against post-shutdown execution
                    cacheManager.setZoneTier(entry.getId(), CacheTier.HOT);
                    // WARM strategy zones never expire by design; eternal worlds also get no-decay.
                    com.hypixel.hytale.server.core.universe.world.World zoneWorld =
                            chunkPreloader.getWorldRegistry().getWorld(entry.getWorld());
                    boolean isEternal = zoneWorld != null && !zoneWorld.getWorldConfig().canUnloadChunks();
                    if (isEternal) {
                        cacheManager.markNoDecay(entry.getId());
                        LOG.fine(() -> "[OptiPortal] Zone '" + entry.getId() + "' in eternal world — no-decay exempted.");
                    } else if (entry.getStrategy() == WarmStrategy.WARM) {
                        cacheManager.markWarmFloor(entry.getId());
                        LOG.fine(() -> "[OptiPortal] Zone '" + entry.getId() + "' marked WARM floor (strategy=WARM).");
                    }
                    // Heap-delta measurement is unreliable on a live server (GC + other plugins).
                    // Use estimate for both fields; TODO: instrument via chunk object sizing.
                    storage.loadById(entry.getId()).ifPresent(loaded -> {
                        loaded.setRamEstimatedMB(estimatedMB);
                        loaded.setLastActive(java.time.Instant.now());
                        chunkPreloader.updateEntryStats(loaded, chunkCount);
                        chunkPreloader.queueEntrySave(loaded);
                    });
                    LOG.fine(() -> "[OptiPortal] WARM zone loaded: " + entry.getId()
                            + " est=" + String.format("%.1f", estimatedMB) + "MB");
                }, executor)
                .exceptionally(ex -> {
                    LOG.warning(() -> "[OptiPortal] WARM zone load failed for "
                            + entry.getId() + ": " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Register a warm zone programmatically (API for other plugins / /preload setwarm).
     */
    public void registerWarmZone(String id, String worldName, double x, double y, double z, int radius) {
        PortalEntry entry = new PortalEntry(id, worldName, x, y, z, 0);
        entry.setStrategy(WarmStrategy.WARM);
        entry.setWarmRadius(radius);
        entry.setType(PortalEntry.EntryType.MANUAL);
        managedZones.put(id, entry);
        storage.save(entry);
        if (worldsReady.get()) {
            loadWarmZone(entry);
        }
    }

    /**
     * Scans a source world for PortalDevice blocks pointing to destination worlds.
     * For each loaded chunk whose storage entry has a block position, reads the
     * PortalDevice component directly via BlockModule and auto-registers the
     * destination world's spawn as a PREDICTIVE zone.
     *
     * Replaces the old PortalWorld-resource heuristic with a definitive component lookup.
     * Does nothing if PortalsPlugin is not installed.
     */
    public void scanWorldForPortalDestination(World world) {
        scanWorldForPortalDestination(world, storage.loadAll());
    }

    /**
     * Snapshot-aware variant. Callers that already hold a registry snapshot (e.g. the
     * AllWorldsLoadedEvent loop) should pass it here to avoid a redundant loadAll() per world.
     */
    public void scanWorldForPortalDestination(World world, java.util.List<PortalEntry> snapshot) {
        if (PortalsPlugin.getInstance() == null) return;

        java.util.List<PortalEntry> worldEntries = snapshot.stream()
                .filter(e -> e.getWorld().equals(world.getName()))
                .filter(e -> !e.isInstanced())
                .filter(e -> e.getType() == PortalEntry.EntryType.PORTAL)
                .collect(java.util.stream.Collectors.toList());

        for (PortalEntry entry : worldEntries) {
            // Unloaded chunks return null from BlockModule; ChunkPreLoadProcessEvent
            // will handle them when they arrive in memory.
            PortalDevice device;
            try {
                device = (PortalDevice) BlockModule.getComponent(
                        PortalDevice.getComponentType(),
                        world,
                        (int) entry.getX(),
                        (int) entry.getY(),
                        (int) entry.getZ());
            } catch (Exception e) {
                continue;
            }
            if (device == null) continue;

            World destWorld = device.getDestinationWorld();
            if (destWorld != null) {
                registerPortalDestination(destWorld);
            }
        }
    }

    /**
     * Returns true if a WARM/PREDICTIVE zone is already registered for the given world.
     */
    public boolean hasDestinationZone(String worldName) {
        String zoneId = "portaldest:" + worldName;
        return managedZones.containsKey(zoneId) || storage.loadById(zoneId).isPresent();
    }

    /**
     * Auto-registers a WARM zone centred on the destination world's spawn point
     * (falls back to origin if no PortalWorld resource is present).
     * Named "portaldest:<worldName>". Safe to call redundantly — skips if already registered.
     */
    public void registerPortalDestination(World destWorld) {
        String zoneId = "portaldest:" + destWorld.getName();
        if (hasDestinationZone(destWorld.getName())) return;

        double x = 0, y = 64, z = 0;
        try {
            PortalWorld portalWorld = destWorld.getEntityStore()
                    .getStore().getResource(PortalWorld.getResourceType());
            if (portalWorld != null && portalWorld.getSpawnPoint() != null) {
                com.hypixel.hytale.math.vector.Vector3d pos =
                        portalWorld.getSpawnPoint().getPosition();
                if (pos != null) { x = pos.x; y = pos.y; z = pos.z; }
            }
        } catch (Exception ignored) {
            // Not a portal world or resource not registered — use origin fallback
        }

        registerWarmZone(zoneId, destWorld.getName(), x, y, z, config.getDefaultWarmRadius());
        LOG.info("[OptiPortal] Auto-registered portal destination warm zone: "
                + zoneId + " in " + destWorld.getName());
    }

    /**
     * Called when a world is destroyed (RemoveWorldEvent).
     * Evicts all warm zones for that world from CacheManager and managedZones so
     * that keepalive and status counters stay accurate. Zone definitions are kept in
     * storage so they re-warm automatically when the world is re-created.
     */
    public void onWorldRemoved(com.hypixel.hytale.server.core.universe.world.World world) {
        onWorldRemoved(world, storage.loadAll());
    }

    /**
     * Snapshot-aware variant. Load the registry once per world removal event and reuse the snapshot.
     * The public method delegates to this snapshot-aware overload.
     */
    private void onWorldRemoved(com.hypixel.hytale.server.core.universe.world.World world, java.util.List<PortalEntry> snapshot) {
        String worldName = world.getName();
        LOG.info("[OptiPortal] World removed: " + worldName + " — evicting warm zones.");

        java.util.List<String> toEvict = snapshot.stream()
                .filter(e -> e.getWorld().equals(worldName))
                .map(PortalEntry::getId)
                .collect(java.util.stream.Collectors.toList());

        for (String zoneId : toEvict) {
            cacheManager.releaseZoneChunks(zoneId);
            // Preserve COLD tier so PRED zones don't show UNVISITED when the world
            // is re-created. WARM zones self-heal via scanWorldForPortalDestination;
            // PRED zones have no re-init path and must stay in zoneTiers as COLD so
            // the keepalive and UI remain correct until the next player visit.
            cacheManager.setZoneTier(zoneId, CacheTier.COLD);
            managedZones.remove(zoneId);
            LOG.fine("[OptiPortal] Evicted zone " + zoneId + " from removed world " + worldName);
        }
    }

    /**
     * Serialize all HOT/WARM zones to COLD on shutdown.
     */
    public void serializeAll() {
        serializeAll(storage.loadAll());
    }

    /**
     * Snapshot-aware variant. Load the registry once per shutdown and reuse the snapshot.
     * The public method delegates to this snapshot-aware overload.
     */
    private void serializeAll(java.util.List<PortalEntry> snapshot) {
        managedZones.forEach((id, zone) -> cacheManager.setZoneTier(id, CacheTier.COLD));
        snapshot.stream()
                .filter(e -> e.getStrategy() == WarmStrategy.WARM)
                .forEach(e -> cacheManager.setZoneTier(e.getId(), CacheTier.COLD));
    }

    private int resolveRadius(PortalEntry entry) {
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }

    /**
     * Resolve X-axis radius from entry, falling back to uniform radius then config default.
     * Private to match existing resolveRadius pattern in this class.
     */
    private int resolveRadiusX(PortalEntry entry) {
        if (entry.getWarmRadiusX() != null) return entry.getWarmRadiusX();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }

    /**
     * Resolve Z-axis radius from entry, falling back to uniform radius then config default.
     * Private to match existing resolveRadius pattern in this class.
     */
    private int resolveRadiusZ(PortalEntry entry) {
        if (entry.getWarmRadiusZ() != null) return entry.getWarmRadiusZ();
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }

    public Map<String, PortalEntry> getManagedZones() {
        return Collections.unmodifiableMap(managedZones);
    }

    public boolean isWorldsReady() {
        return worldsReady.get();
    }

    /**
     * Stops the WarmZoneManager and cancels any pending startup tasks.
     * Call from plugin shutdown before executor.shutdown().
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            LOG.fine(() -> "[OptiPortal] WarmZoneManager stopping...");
            if (pollTask != null) {
                pollTask.cancel(false);
                pollTask = null;
            }
            if (universeReadyTask != null) {
                universeReadyTask.cancel(false);
                universeReadyTask = null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // RAM measurement
    // -------------------------------------------------------------------------

}
