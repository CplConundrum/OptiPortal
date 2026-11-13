package com.optiportal.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.optiportal.preload.WorldRegistry;
import com.optiportal.config.PluginConfig;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Periodically audits the {@link CacheManager} chunk-ownership map against what the engine
 * reports as actually loaded, detecting eviction drift (chunks the engine evicted without
 * OptiPortal noticing).
 *
 * <p>Activation (2026-11-12 wiring): constructed by {@code OptiPortal} and started
 * alongside the keepalive when {@code chunkPressure.ownershipAuditEnabled} is true
 * (default). Audits run every N minutes ({@code chunkPressure.ownershipAuditIntervalMinutes},
 * default 60) on a fixed delay with a 1-minute initial delay.
 *
 * <p>Lifecycle: {@link #stop()} cancels the timer (interrupting the idle wait) and fences
 * the in-flight pass - a stopped pass applies no mutations. The shared executor shutdown at
 * plugin stop is a backstop, not a substitute for quiescing before registry serialization.
 *
 * <p>Threading: audits run on the plugin's executor pool; all CacheManager calls are safe
 * from any thread; {@code World.getChunkStore().getChunkIndexes()} is a live (non-atomic)
 * view, so evictions are revalidated just before they are applied (F4).
 */
public class ChunkOwnershipAuditor {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final CacheManager cacheManager;
    private final ScheduledExecutorService executor;
    private final PluginConfig config;
    private final WorldSnapshotSource snapshots;

    /** Lifecycle fence: a pass in flight at stop() time applies no mutations. */
    private final AtomicBoolean active = new AtomicBoolean(false);
    // Lifecycle fence: bumped on every stop()/reschedule(); an in-flight pass that
    // outlives the stop (or survives a reschedule that re-armed `active`) sees a
    // changed epoch and applies nothing.
    private final AtomicLong passEpoch = new AtomicLong(0);
    /** Arm guard: a double start() is a no-op. */
    private final AtomicBoolean armed  = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> task;

    /**
     * Per-world loaded-chunk snapshot source (seam: production wraps
     * {@link WorldRegistry}; tests substitute fakes to simulate the observation-to-apply gap).
     */
    public interface WorldSnapshotSource {
        List<String> worldNames();

        /** Live view of the world's loaded chunk indexes (engine index = cx &lt;&lt; 32 | cz). */
        LongSet loadedIndexes(String worldName);
    }

    public ChunkOwnershipAuditor(CacheManager cacheManager,
                                 WorldRegistry worldRegistry,
                                 ScheduledExecutorService executor,
                                 PluginConfig config) {
        this(cacheManager, executor, config, new RegistrySnapshotSource(worldRegistry));
    }

    ChunkOwnershipAuditor(CacheManager cacheManager,
                          ScheduledExecutorService executor,
                          PluginConfig config,
                          WorldSnapshotSource snapshots) {
        this.cacheManager = cacheManager;
        this.executor     = executor;
        this.config       = config;
        this.snapshots    = snapshots;
    }

    /** Production snapshot source: the engine's live ChunkStore views via WorldRegistry. */
    private static final class RegistrySnapshotSource implements WorldSnapshotSource {
        private final WorldRegistry worldRegistry;

        RegistrySnapshotSource(WorldRegistry worldRegistry) {
            this.worldRegistry = worldRegistry;
        }

        @Override
        public List<String> worldNames() {
            List<String> names = new ArrayList<>();
            for (World w : worldRegistry.getWorlds()) {
                names.add(w.getName());
            }
            return names;
        }

        @Override
        public LongSet loadedIndexes(String worldName) {
            World w = worldRegistry.getWorld(worldName);
            return w.getChunkStore().getChunkIndexes();
        }
    }

    public void start() {
        if (!armed.compareAndSet(false, true)) {
            LOG.fine("[OptiPortal] Ownership auditor: already armed - start() ignored");
            return;
        }
        int intervalMinutes = config.getOwnershipAuditIntervalMinutes();
        if (intervalMinutes < 1) {
            LOG.warning("[OptiPortal] Ownership auditor: interval below 1 minute - clamped to 1");
            intervalMinutes = 1;
        }
        // I1.2: fixed delay - a slow audit can never overlap the next one.
        task = executor.scheduleWithFixedDelay(this::runAuditSafely, 1, intervalMinutes, TimeUnit.MINUTES);
        active.set(true);
        LOG.info("[OptiPortal] Chunk ownership auditor started (every " + intervalMinutes + "m, fixed delay)");
    }

    public void stop() {
        active.set(false);
        passEpoch.incrementAndGet();
        ScheduledFuture<?> t = task;
        task = null;
        if (t != null) {
            // I1.1: cancel(true) interrupts the idle wait; an in-flight pass is fenced
            // by the `active` flag (it applies no mutations once stopped).
            t.cancel(true);
        }
        armed.set(false);
    }

    public void reschedule() {
        stop();
        start();
    }

    private void runAuditSafely() {
        if (!active.get()) return;
        try {
            runAudit();
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Ownership audit failed: " + e.getMessage());
        }
    }

    public void runAudit() {
        for (String worldName : snapshots.worldNames()) {
            if (!active.get()) return;
            try {
                auditWorld(worldName);
            } catch (Exception e) {
                LOG.warning("[OptiPortal] Ownership audit for world '" + worldName + "' failed: " + e.getMessage());
            }
        }
    }

    private void auditWorld(String worldName) {
        // ---- observation phase ----------------------------------------------------
        final long epoch = passEpoch.get();
        LongSet loaded;
        try {
            loaded = snapshots.loadedIndexes(worldName);
        } catch (Exception e) {
            LOG.warning("[OptiPortal] Ownership audit: could not read chunk indexes for '"
                    + worldName + "': " + e.getMessage());
            return;
        }

        Set<Long> ownedKeys = cacheManager.getOwnedChunkKeys(worldName);
        if (ownedKeys.isEmpty()) {
            return;
        }

        int checkedCount = 0;
        int evictedCount = 0;
        List<int[]> evictions = new ArrayList<>();
        List<Set<String>> observedOwners = new ArrayList<>();
        List<Map<String, Long>> observedZoneGens = new ArrayList<>();

        for (Long key : ownedKeys) {
            int cx = (int) (long) key;
            int cz = (int) (key >>> 32);
            checkedCount++;
            long engineIndex = ChunkUtil.indexChunk(cx, cz);
            if (!loaded.contains(engineIndex)) {
                evictedCount++;
                evictions.add(new int[]{cx, cz});
                // F4: capture the observed owner set AND the per-owner zone generation
                // at observation time; the application phase skips the coord if ownership
                // moved OR any observed zone advanced its generation (re-promotion /
                // re-registration) in the gap.
                Set<String> obsOwners = new HashSet<>(cacheManager.getChunkOwners(worldName, cx, cz));
                observedOwners.add(obsOwners);
                Map<String, Long> obsGens = new HashMap<>();
                for (String z : obsOwners) {
                    obsGens.put(z, cacheManager.getZoneGeneration(z));
                }
                observedZoneGens.add(obsGens);
            }
        }

        if (evictedCount == 0) {
            LOG.fine("[OptiPortal] Ownership audit: world '" + worldName + "' clean (checked=" + checkedCount + ")");
            return;
        }
        if (evictedCount == checkedCount) {
            LOG.info("[OptiPortal] Ownership audit: world '" + worldName
                    + "' appears to be unloading (all " + checkedCount + " owned chunks missing) - skipping eviction application");
            return;
        }
        if (checkedCount > 0 && (double) evictedCount / checkedCount > 0.5) {
            LOG.info("[OptiPortal] Ownership audit: world '" + worldName + "' missing "
                    + evictedCount + "/" + checkedCount + " owned chunks (>50%) - looks like a world unload, skipping");
            return;
        }

        // ---- F4: conditional application -------------------------------------------
        // Revalidate before applying: (a) a chunk reloaded in the observation-to-apply gap
        // must not be re-evicted, (b) a coord whose ownership changed in the gap
        // (re-registration/transfer) must not be erased by the stale observation, and
        // (c) a coord whose observed zone advanced its generation in the gap
        // (re-promotion / re-registration) must not be downgraded from the fresh state.
        // Residual: a release+re-registration of the SAME zone with no tier change is
        // indistinguishable from the observed state (bounded to one coord, self-heals
        // on the next audit pass).
        if (!active.get() || passEpoch.get() != epoch) return; // I1.1: stop/reschedule fence
        LongSet loadedNow = loaded;
        try {
            loadedNow = snapshots.loadedIndexes(worldName);
        } catch (Exception e) {
            LOG.fine("[OptiPortal] Ownership audit: second index read failed - using the observation snapshot");
        }

        List<int[]> toApply = new ArrayList<>();
        for (int i = 0; i < evictions.size(); i++) {
            if (!active.get() || passEpoch.get() != epoch) return; // I1.1: stop/reschedule fence (per-coord)
            int[] c = evictions.get(i);
            long engineIndex = ChunkUtil.indexChunk(c[0], c[1]);
            if (loadedNow != null && loadedNow.contains(engineIndex)) {
                continue; // (a) reloaded in the gap
            }
            Set<String> nowOwners = cacheManager.getChunkOwners(worldName, c[0], c[1]);
            if (!nowOwners.equals(observedOwners.get(i))) {
                continue; // (b) ownership moved in the gap
            }
            boolean zoneAdvanced = false;
            for (Map.Entry<String, Long> ge : observedZoneGens.get(i).entrySet()) {
                if (cacheManager.getZoneGeneration(ge.getKey()) != ge.getValue()) {
                    zoneAdvanced = true;
                    break;
                }
            }
            if (zoneAdvanced) {
                continue; // (c) observed zone re-promoted/re-registered in the gap
            }
            toApply.add(c);
        }

        if (toApply.isEmpty()) {
            LOG.info("[OptiPortal] Ownership audit: world '" + worldName + "': "
                    + evictedCount + " observed evictions no longer current - nothing applied");
            return;
        }

        // Final fence: a stop()/reschedule() between the per-coord checks and this
        // call must not let the stale pass apply.
        if (!active.get() || passEpoch.get() != epoch) return;
        cacheManager.onChunksEvicted(worldName, toApply);
        LOG.info("[OptiPortal] Ownership audit: world '" + worldName + "': checked="
                + checkedCount + " evictedApplied=" + toApply.size() + " (observed=" + evictedCount + ")");
    }
}
