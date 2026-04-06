package com.optiportal.cache;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.optiportal.config.PluginConfig;
import com.optiportal.preload.WorldRegistry;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Periodically audits the CacheManager chunk ownership map against what
 * the engine reports as actually loaded, detecting eviction drift.
 *
 * <p><b>DORMANT: This class is intentionally not wired into startup.</b>
 * It may be activated in a future pass if eviction drift detection proves necessary.
 *
 * <p><b>Lifecycle safety note:</b> This class schedules recurring tasks via
 * {@code executor.scheduleAtFixedRate()}. If ever activated, callers must ensure
 * explicit cancellation via {@code ScheduledFuture.cancel()} or plugin shutdown
 * hooks to avoid task leaks, as {@link com.hypixel.hytale.server.core.plugin.PluginBase}
 * does not automatically cancel arbitrary executor tasks during cleanup.
 *
 * Audits run every N minutes (configurable via ownershipAuditIntervalMinutes).
 * On detection, CacheManager.onChunkEvicted() is called to clean up.
 *
 * "Drift" occurs when the engine evicts a chunk (due to memory pressure,
 * world unload, or other causes) without OptiPortal receiving a corresponding
 * event. The ownership map then references chunks that no longer exist,
 * causing RAM estimates and tier decisions to be stale.
 *
 * Audits run every N minutes (configurable via ownershipAuditIntervalMinutes).
 * On detection, CacheManager.onChunkEvicted() is called to clean up.
 *
 * Threading: audit runs on the plugin executor thread pool.
 * All CacheManager calls are safe from any thread.
 * World.getChunkStore().getChunkIndexes() is thread-safe.
 */
public class ChunkOwnershipAuditor {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final CacheManager cacheManager;
    private final WorldRegistry worldRegistry;
    private final ScheduledExecutorService executor;
    private final PluginConfig config;

    public ChunkOwnershipAuditor(CacheManager cacheManager,
                                 WorldRegistry worldRegistry,
                                 ScheduledExecutorService executor,
                                 PluginConfig config) {
        this.cacheManager = cacheManager;
        this.worldRegistry = worldRegistry;
        this.executor = executor;
        this.config = config;
    }

    /**
     * Start the periodic audit scheduler.
     * Audit runs immediately 1 minute after start, then every N minutes.
     */
    public void start() {
        int intervalMinutes = config.getOwnershipAuditIntervalMinutes();
        // Ensure minimum interval of 1 minute to avoid issues with scheduleAtFixedRate
        if (intervalMinutes < 1) {
            intervalMinutes = 1;
            LOG.warning("[OptiPortal] ChunkOwnershipAuditor: intervalMinutes must be >= 1, using 1");
        }
        // Initial delay of 1 minute to let the server finish startup
        executor.scheduleAtFixedRate(() -> {
            try {
                runAudit();
            } catch (Exception e) {
                LOG.warning("[OptiPortal] ChunkOwnershipAuditor: audit error: " + e.getMessage());
            }
        }, 1, intervalMinutes, TimeUnit.MINUTES);
        LOG.info("[OptiPortal] ChunkOwnershipAuditor scheduled every "
                + intervalMinutes + " minutes.");
    }

    /**
     * Run one full audit pass across all loaded worlds.
     * For each world, cross-references the ownership map against actual
     * loaded chunk indexes reported by ChunkStore.
     */
    public void runAudit() {
        for (World world : worldRegistry.getWorlds()) {
            try {
                auditWorld(world);
            } catch (Exception e) {
                LOG.warning("[OptiPortal] ChunkOwnershipAuditor: error auditing world '"
                        + world.getName() + "': " + e.getMessage());
            }
        }
    }

    // Safety threshold: if this fraction of checked chunks appear evicted, it
    // almost certainly indicates a lookup error (wrong pack formula, non-ticking
    // chunks excluded from getChunkIndexes(), etc.) rather than genuine eviction.
    // In that case we log a warning and skip all onChunkEvicted callbacks to
    // avoid corrupting the ownership map.
    private static final double EVICTION_SANITY_THRESHOLD = 0.5;

    private void auditWorld(World world) {
        String worldName = world.getName();

        // Fetch all loaded chunk indexes from the ChunkStore in one call.
        // getChunkIndexes() covers both ticking and non-ticking chunks, unlike
        // getChunkIfLoaded() which silently returns null for non-ticking chunks.
        LongSet loadedIndexes;
        try {
            loadedIndexes = world.getChunkStore().getChunkIndexes();
        } catch (Exception e) {
            LOG.warning("[OptiPortal] ChunkOwnershipAuditor: getChunkIndexes() failed for '"
                    + worldName + "': " + e.getMessage());
            return;
        }

        // Get only the packed chunk keys owned in this world — no prefix filtering needed.
        // Key encoding: cx = (int) key,  cz = (int)(key >>> 32)
        java.util.Set<Long> ownedKeys = cacheManager.getOwnedChunkKeys(worldName);
        if (ownedKeys.isEmpty()) return;

        int checkedCount = 0;
        int evictedCount = 0;

        // Collect evictions before applying them so we can run the sanity check first.
        java.util.List<int[]> evictions = new java.util.ArrayList<>();

        for (Long packedKey : ownedKeys) {
            checkedCount++;
            int cx = (int)(long) packedKey;
            int cz = (int)(packedKey >>> 32);
            // Convert to engine index format before lookup.
            // CacheManager stores cx-low/cz-high; ChunkStore.getChunkIndexes() uses ChunkUtil.indexChunk.
            long engineIndex = ChunkUtil.indexChunk(cx, cz);
            if (!loadedIndexes.contains(engineIndex)) {
                evictedCount++;
                evictions.add(new int[]{cx, cz});
            }
        }

        if (checkedCount == 0) return;

        double absentFraction = evictedCount / (double) checkedCount;

        // 100% eviction rate: world may be in the process of unloading.
        // Skip callbacks to avoid corrupting ownership for zones that will reload.
        if (evictedCount == checkedCount) {
            final int fc = checkedCount;
            LOG.fine(() -> "[OptiPortal] ChunkOwnershipAuditor: all " + fc
                    + " owned chunks absent for '" + worldName
                    + "' — possible world unload in progress, skipping.");
            return;
        }

        // Sanity check: if more than EVICTION_SANITY_THRESHOLD of checked chunks appear
        // evicted at once, something is likely wrong — skip callbacks to prevent ownership
        // corruption and log so the root cause can be investigated.
        if (absentFraction > EVICTION_SANITY_THRESHOLD) {
            final int fc = checkedCount, fe = evictedCount;
            LOG.warning(() -> "[OptiPortal] ChunkOwnershipAuditor: audit for '" + worldName
                    + "' found " + fe + "/" + fc
                    + " chunks absent — sanity threshold exceeded, skipping eviction callbacks.");
            return;
        }

        // Apply confirmed evictions in one batch call
        cacheManager.onChunksEvicted(worldName, evictions);

        if (evictedCount > 0 || LOG.isLoggable(java.util.logging.Level.FINE)) {
            LOG.info("[OptiPortal] Ownership audit for '" + worldName
                    + "': checked=" + checkedCount
                    + " evicted=" + evictedCount);
        }
    }
}
