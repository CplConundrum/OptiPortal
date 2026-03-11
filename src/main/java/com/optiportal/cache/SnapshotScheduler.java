package com.optiportal.cache;

import com.optiportal.config.PluginConfig;

import java.util.concurrent.*;

/** Periodically snapshots HOT zone state to disk. */
public class SnapshotScheduler {

    private final PluginConfig config;
    private final CacheManager cacheManager;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    public SnapshotScheduler(PluginConfig config, CacheManager cacheManager, ScheduledExecutorService executor) {
        this.config = config;
        this.cacheManager = cacheManager;
        this.executor = executor;
    }

    public void start() {
        int intervalMinutes = config.getSnapshotIntervalMinutes();
        task = executor.scheduleAtFixedRate(this::snapshot, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    public void stop() {
        if (task != null) task.cancel(false);
    }

    /** Cancel and reschedule using current config. Safe to call at runtime. */
    public void reschedule() {
        stop();
        start();
    }

    private void snapshot() {
        // TODO: Serialize all HOT/WARM zone state to disk async
        cacheManager.saveRegistry();
    }
}