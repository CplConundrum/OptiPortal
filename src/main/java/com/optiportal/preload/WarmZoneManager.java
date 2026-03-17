package com.optiportal.preload;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.universe.Universe;
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

    // Pending zones to load once worlds become ready (if startStagedLoad fires early)
    private final List<PortalEntry> pendingLoad = new CopyOnWriteArrayList<>();

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
            // Seed WorldRegistry from Universe — this is the authoritative world map
            Universe universe = Universe.get();
            if (universe != null && chunkPreloader != null) {
                universe.getWorlds().values().forEach(world -> {
                    chunkPreloader.getWorldRegistry().addWorld(world);
                    System.out.println("[OptiPortal] Seeded world from Universe: " + world.getName());
                });
            } else {
                System.err.println("[OptiPortal] AllWorldsLoadedEvent: Universe.get()="
                    + universe + " chunkPreloader=" + chunkPreloader);
            }

            worldsReady.set(true);
            if (stagedLoadStarted.compareAndSet(false, true)) {
                logWorldNames("AllWorldsLoadedEvent");
                executor.submit(this::runStagedLoad);
            }
        });
    }

    public void triggerStagedLoadOnce() {
        System.out.println("[OptiPortal] triggerStagedLoadOnce called, stagedLoadStarted=" + stagedLoadStarted.get());
        worldsReady.set(true);
        if (stagedLoadStarted.compareAndSet(false, true)) {
            logWorldNames("first player join");
            executor.submit(this::runStagedLoad);
        } else {
            System.out.println("[OptiPortal] triggerStagedLoadOnce: staged load already running, ignoring.");
        }
    }

    public void startStagedLoad() {
        // Just start the polling fallback — actual load is triggered by triggerStagedLoadOnce()
        // from PlayerReadyEvent, which ensures WorldRegistry is seeded before load starts.
        executor.scheduleWithFixedDelay(this::pollWorldsReady, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private final java.util.concurrent.atomic.AtomicInteger pollCount = new java.util.concurrent.atomic.AtomicInteger(0);

    private void pollWorldsReady() {
        if (worldsReady.get()) return; // player join or event already triggered
        if (chunkPreloader == null) return;
        int count = pollCount.incrementAndGet();
        // Polling is a last-resort fallback — primary trigger is triggerStagedLoadOnce()
        // called from TeleportInterceptor when the first player joins.
        if (count >= 30) {
            System.err.println("[OptiPortal] No player joined after 60s — staged load skipped.");
            worldsReady.set(true);
        }
    }

    private void logWorldNames(String trigger) {
        if (chunkPreloader != null) {
            java.util.Collection<com.hypixel.hytale.server.core.universe.world.World> worlds =
                    chunkPreloader.getWorldRegistry().getWorlds();
            System.out.println("[OptiPortal] Worlds ready (" + trigger + ", " + worlds.size() + " worlds): "
                    + worlds.stream()
                        .map(com.hypixel.hytale.server.core.universe.world.World::getName)
                        .collect(java.util.stream.Collectors.joining(", ")));
        }
    }

    private void runStagedLoad() {
        System.out.println("[OptiPortal] runStagedLoad executing...");
        List<PortalEntry> all = storage.loadAll();

        List<PortalEntry> warmZones = all.stream()
                .filter(e -> e.getStrategy() == WarmStrategy.WARM)
                .filter(e -> !e.isInstanced())
                .sorted(Comparator.comparingInt(this::startupPriority))
                .toList();

        System.out.println("[OptiPortal] Staged load: " + warmZones.size()
                + " WARM zones (skipping " + (all.size() - warmZones.size()) + " PREDICTIVE)");

        int loaded = 0;
        int skipped = 0;
        for (PortalEntry zone : warmZones) {
            try {
                loadWarmZone(zone).get(120, TimeUnit.SECONDS);
                loaded++;
            } catch (TimeoutException e) {
                System.err.println("[OptiPortal] Warm load timed out for " + zone.getId() + " — skipping");
                skipped++;
            } catch (Exception e) {
                System.err.println("[OptiPortal] Warm load failed for " + zone.getId() + ": " + e.getMessage());
                skipped++;
            }
        }

        System.out.println("[OptiPortal] Startup load complete: " + loaded + " loaded, " + skipped + " skipped.");
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
     * Load a warm zone's chunks (non-ticking geometry only, no entity simulation).
     * Uses getNonTickingChunkAsync — safe to call from any thread.
     * Returns a future that completes when the inner ring is loaded.
     */
    public CompletableFuture<Void> loadWarmZone(PortalEntry entry) {
        int cx     = ChunkPreloader.toChunkCoord(entry.getX());
        int cz     = ChunkPreloader.toChunkCoord(entry.getZ());
        int radius = resolveRadius(entry);

        System.out.println("[OptiPortal] Loading WARM zone: " + entry.getId()
                + " world=" + entry.getWorld()
                + " cx=" + cx + " cz=" + cz + " r=" + radius);

        // Estimated RAM: (2R+1)^2 chunks × 64KB × 1.5x overhead for entities/metadata
        int chunkCount = (2 * radius + 1) * (2 * radius + 1);
        double estimatedMB = (chunkCount * 65536.0 * 1.5) / (1024.0 * 1024.0);

        return chunkPreloader.warmLoad(entry.getId(), entry.getWorld(), cx, cz, radius)
                .thenRun(() -> {
                    cacheManager.setZoneTier(entry.getId(), CacheTier.HOT);
                    // Heap-delta measurement is unreliable on a live server (GC + other plugins).
                    // Use estimate for both fields; TODO: instrument via chunk object sizing.
                    storage.loadById(entry.getId()).ifPresent(loaded -> {
                        loaded.setRamEstimatedMB(estimatedMB);
                        loaded.setRamMarginalMB(estimatedMB); // Also update marginal RAM
                        chunkPreloader.updateEntryStats(loaded, chunkCount);
                        storage.save(loaded);
                    });
                    System.out.println("[OptiPortal] WARM zone loaded: " + entry.getId()
                            + " est=" + String.format("%.1f", estimatedMB) + "MB");
                })
                .exceptionally(ex -> {
                    System.err.println("[OptiPortal] WARM zone load failed for "
                            + entry.getId() + ": " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Register a warm zone programmatically (API for other plugins / /preload setwarm).
     */
    public void registerWarmZone(String id, double x, double y, double z, int radius) {
        PortalEntry entry = new PortalEntry(id, "default", x, y, z, 0);
        entry.setStrategy(WarmStrategy.WARM);
        entry.setWarmRadius(radius);
        entry.setType(PortalEntry.EntryType.MANUAL);
        managedZones.put(id, entry);
        storage.save(entry);
        if (worldsReady.get()) {
            loadWarmZone(entry);
        } else {
            pendingLoad.add(entry);
        }
    }

    /**
     * Serialize all HOT/WARM zones to COLD on shutdown.
     */
    public void serializeAll() {
        managedZones.forEach((id, zone) -> cacheManager.setZoneTier(id, CacheTier.COLD));
        storage.loadAll().stream()
                .filter(e -> e.getStrategy() == WarmStrategy.WARM)
                .forEach(e -> cacheManager.setZoneTier(e.getId(), CacheTier.COLD));
    }

    private int resolveRadius(PortalEntry entry) {
        if (entry.getWarmRadius() > 0) return entry.getWarmRadius();
        return config.getDefaultWarmRadius();
    }

    public Map<String, PortalEntry> getManagedZones() {
        return Collections.unmodifiableMap(managedZones);
    }

    public boolean isWorldsReady() {
        return worldsReady.get();
    }

    // -------------------------------------------------------------------------
    // RAM measurement
    // -------------------------------------------------------------------------

}