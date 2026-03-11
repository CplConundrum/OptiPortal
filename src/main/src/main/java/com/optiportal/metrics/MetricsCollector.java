package com.optiportal.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects runtime performance metrics.
 * Thread-safe atomic counters - no world thread overhead.
 */
public class MetricsCollector {

    private final AtomicInteger preloadsFired = new AtomicInteger(0);
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);
    private final AtomicInteger chunksDeduped = new AtomicInteger(0);
    private final AtomicLong totalRestoreTimeMs = new AtomicLong(0);
    private final AtomicLong totalFreshLoadTimeMs = new AtomicLong(0);
    private final AtomicInteger restoreCount = new AtomicInteger(0);
    private final AtomicInteger freshLoadCount = new AtomicInteger(0);

    public void recordPreload() { preloadsFired.incrementAndGet(); }
    public void recordCacheHit() { cacheHits.incrementAndGet(); }
    public void recordCacheMiss() { cacheMisses.incrementAndGet(); }
    public void recordChunksDeduped(int count) { chunksDeduped.addAndGet(count); }
    public void recordRestoreTime(long ms) { totalRestoreTimeMs.addAndGet(ms); restoreCount.incrementAndGet(); }
    public void recordFreshLoadTime(long ms) { totalFreshLoadTimeMs.addAndGet(ms); freshLoadCount.incrementAndGet(); }

    public int getPreloadsFired() { return preloadsFired.get(); }
    public int getCacheHits() { return cacheHits.get(); }
    public int getCacheMisses() { return cacheMisses.get(); }
    public int getChunksDeduped() { return chunksDeduped.get(); }

    public double getCacheHitRate() {
        int total = cacheHits.get() + cacheMisses.get();
        return total == 0 ? 0 : (double) cacheHits.get() / total * 100;
    }

    public long getAvgRestoreTimeMs() {
        int count = restoreCount.get();
        return count == 0 ? 0 : totalRestoreTimeMs.get() / count;
    }

    public long getAvgFreshLoadTimeMs() {
        int count = freshLoadCount.get();
        return count == 0 ? 0 : totalFreshLoadTimeMs.get() / count;
    }

    public String getSummary() {
        return String.format(
            "[OptiPortal] Stats: preloads=%d | cache hit rate=%.1f%% | avg restore=%dms | avg fresh=%dms | chunks deduped=%d",
            getPreloadsFired(), getCacheHitRate(), getAvgRestoreTimeMs(), getAvgFreshLoadTimeMs(), getChunksDeduped()
        );
    }
}
