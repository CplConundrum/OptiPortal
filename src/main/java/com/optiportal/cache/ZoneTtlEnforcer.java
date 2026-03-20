package com.optiportal.cache;

import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.optiportal.config.PluginConfig;
import com.optiportal.model.CacheTier;
import com.optiportal.model.PortalEntry;
import com.optiportal.storage.StorageBackend;

/**
 * Periodically enforces TTL (Time-To-Live) on portal entries.
 *
 * <p>Runs on a configurable interval (default every 24 hours) and removes entries
 * whose last-active timestamp exceeds their TTL. HOT and WARM zones are never
 * evicted by TTL to preserve user-configured persistent zones.
 *
 * <p>TTL is determined by:
 * <ul>
 *   <li>Per-zone override ({@code cacheTTLDays}) takes priority
 *   <li>Otherwise, falls back to type-specific global TTL:
 *       <ul>
 *         <li>{@code DEATH} entries → {@code ttlDeathLocation} (default 1 day)
 *         <li>{@code BED} entries → {@code ttlBed} (default 3 days)
 *         <li>{@code WARM} strategy → {@code ttlWarm} (default -1 = never expire)
 *         <li>{@code PREDICTIVE} strategy → {@code ttlPredictive} (default 2 days)
 *       </ul>
 * </ul>
 */
public class ZoneTtlEnforcer {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private final PluginConfig config;
    private final StorageBackend storage;
    private final CacheManager cacheManager;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> cleanupTask;

    public ZoneTtlEnforcer(PluginConfig config, StorageBackend storage,
                           CacheManager cacheManager, ScheduledExecutorService executor) {
        this.config = config;
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.executor = executor;
    }

    /**
     * Start the TTL enforcement scheduler.
     *
     * @param intervalHours Interval in hours between cleanup runs (default 24)
     */
    public void start() {
        int intervalHours = config.getTtlCleanupIntervalHours();
        cleanupTask = executor.scheduleAtFixedRate(this::runCleanup,
                intervalHours, intervalHours, TimeUnit.HOURS);
        LOG.info("[OptiPortal] ZoneTtlEnforcer: started with interval " + intervalHours + "h");
    }

    /**
     * Stop the TTL enforcement scheduler.
     */
    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
            LOG.info("[OptiPortal] ZoneTtlEnforcer: stopped.");
        }
    }

    /**
     * Reschedule the TTL enforcement task with the current config interval.
     */
    public void reschedule() {
        stop();
        start();
    }

    /**
     * Run the TTL cleanup task.
     *
     * <p>Iterates all portal entries and removes those whose last-active timestamp
     * exceeds their TTL. HOT and WARM zones are never evicted.
     */
    public void runCleanup() {
        Instant now = Instant.now();
        int removed = 0;

        for (PortalEntry entry : storage.loadAll()) {
            // Never evict entries that are currently HOT or WARM
            CacheTier tier = cacheManager.getZoneTier(entry.getId());
            if (tier == CacheTier.HOT || tier == CacheTier.WARM) {
                continue;
            }

            int ttlDays = resolveTtl(entry);
            if (ttlDays == -1) {
                continue; // never-expire sentinel
            }

            Instant lastActive = entry.getLastActive();
            if (lastActive == null) {
                continue; // no activity recorded yet — skip
            }

            if (lastActive.plusSeconds(ttlDays * 86_400L).isBefore(now)) {
                cacheManager.releaseZoneChunks(entry.getId());
                storage.delete(entry.getId());
                removed++;
                LOG.info("[OptiPortal] TTL expired: " + entry.getId()
                        + " (lastActive=" + lastActive + " ttlDays=" + ttlDays + ")");
            }
        }

        if (removed > 0) {
            LOG.info("[OptiPortal] ZoneTtlEnforcer: removed " + removed + " expired zone(s).");
        }
    }

    /**
     * Resolve the TTL for a portal entry.
     *
     * <p>Per-zone override ({@code cacheTTLDays}) takes priority.
     * Otherwise, falls back to type-specific global TTL.
     *
     * @param entry The portal entry
     * @return TTL in days, or -1 for never-expire
     */
    private int resolveTtl(PortalEntry entry) {
        // Per-zone override takes priority
        if (entry.getCacheTTLDays() != null) {
            return entry.getCacheTTLDays();
        }

        // Fall back to type-specific global TTL
        return switch (entry.getType()) {
            case DEATH -> config.getTtlDeathLocation();
            case BED -> config.getTtlBed();
            case PORTAL -> resolvePortalTtl(entry);
            default -> -1; // MANUAL entries never expire
        };
    }

    /**
     * Resolve the TTL for a portal entry based on its strategy.
     *
     * @param entry The portal entry
     * @return TTL in days, or -1 for never-expire
     */
    private int resolvePortalTtl(PortalEntry entry) {
        return switch (entry.getStrategy()) {
            case WARM -> config.getTtlWarm();
            case PREDICTIVE -> config.getTtlPredictive();
            default -> -1;
        };
    }
}
