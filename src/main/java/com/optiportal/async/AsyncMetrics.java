package com.optiportal.async;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Metrics collection for async operations.
 *
 * This component tracks performance metrics for async operations,
 * including execution times, error rates, and operation counts.
 */
public class AsyncMetrics {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    // Counters
    private final LongAdder worldThreadExecutions = new LongAdder();
    private final LongAdder worldThreadErrors = new LongAdder();
    private final LongAdder chunkLoadSuccesses = new LongAdder();
    private final LongAdder chunkLoadErrors = new LongAdder();
    private final LongAdder positionUpdateErrors = new LongAdder();
    private final LongAdder keepaliveErrors = new LongAdder();
    private final LongAdder activeAsyncTasks = new LongAdder();
    
    // Timing
    private final AtomicLong totalWorldThreadExecutionTime = new AtomicLong();
    private final AtomicLong totalChunkLoadTime = new AtomicLong();
    
    // Error tracking by operation
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> operationErrors = 
            new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Record successful world thread execution.
     * 
     * @param operation The operation name
     * @param durationMs Execution duration in milliseconds
     */
    public void recordWorldThreadExecution(String operation, long durationMs) {
        worldThreadExecutions.increment();
        totalWorldThreadExecutionTime.addAndGet(durationMs);
        
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("World thread execution: " + operation + " took " + durationMs + "ms");
        }
    }
    
    /**
     * Record world thread error.
     * 
     * @param operation The operation name
     * @param durationMs Execution duration before error
     */
    public void recordWorldThreadError(String operation, long durationMs) {
        worldThreadErrors.increment();
        operationErrors.computeIfAbsent(operation, k -> new AtomicLong()).incrementAndGet();
        
        LOG.warning("World thread error: " + operation + " after " + durationMs + "ms");
    }
    
    /**
     * Record successful chunk load.
     * 
     * @param cx Chunk X coordinate
     * @param cz Chunk Z coordinate
     * @param durationMs Load duration in milliseconds
     */
    public void recordChunkLoadSuccess(int cx, int cz, long durationMs) {
        chunkLoadSuccesses.increment();
        totalChunkLoadTime.addAndGet(durationMs);
        
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Chunk load success: " + cx + "," + cz + " took " + durationMs + "ms");
        }
    }
    
    /**
     * Record chunk load error.
     * 
     * @param cx Chunk X coordinate
     * @param cz Chunk Z coordinate
     * @param durationMs Load duration before error
     */
    public void recordChunkLoadError(int cx, int cz, long durationMs) {
        chunkLoadErrors.increment();
        operationErrors.computeIfAbsent("chunkLoad", k -> new AtomicLong()).incrementAndGet();
        
        LOG.warning("Chunk load error: " + cx + "," + cz + " after " + durationMs + "ms");
    }
    
    /**
     * Record position update error.
     */
    public void recordPositionUpdateError() {
        positionUpdateErrors.increment();
        operationErrors.computeIfAbsent("positionUpdate", k -> new AtomicLong()).incrementAndGet();
        
        LOG.warning("Position update error recorded");
    }
    
    /**
     * Record keepalive error.
     * 
     * @param tier The cache tier that failed
     */
    public void recordKeepaliveError(String tier) {
        keepaliveErrors.increment();
        operationErrors.computeIfAbsent("keepalive_" + tier, k -> new AtomicLong()).incrementAndGet();
        
        LOG.warning("Keepalive error for tier: " + tier);
    }
    
    /**
     * Record async task start.
     * 
     * @param priority Task priority
     */
    public void recordAsyncTaskStart(AsyncTaskPriority priority) {
        activeAsyncTasks.increment();
        
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Async task started with priority: " + priority);
        }
    }
    
    /**
     * Record async task completion.
     * 
     * @param priority Task priority
     * @param durationMs Task duration in milliseconds
     */
    public void recordAsyncTaskComplete(AsyncTaskPriority priority, long durationMs) {
        activeAsyncTasks.decrement();
        
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Async task completed with priority: " + priority + " in " + durationMs + "ms");
        }
    }
    
    /**
     * Get current error statistics.
     * 
     * @return Error statistics
     */
    public AsyncErrorHandler.ErrorStats getErrorStats() {
        long totalOperations = worldThreadExecutions.sum() + chunkLoadSuccesses.sum();
        long totalErrors = worldThreadErrors.sum() + chunkLoadErrors.sum() + 
                          positionUpdateErrors.sum() + keepaliveErrors.sum();
        
        double errorRate = totalOperations > 0 ? (double) totalErrors / totalOperations : 0.0;
        
        return new AsyncErrorHandler.ErrorStats(
            worldThreadErrors.sum(),
            chunkLoadErrors.sum(),
            positionUpdateErrors.sum(),
            keepaliveErrors.sum(),
            errorRate
        );
    }
    
    /**
     * Get performance summary.
     * 
     * @return Performance summary
     */
    public PerformanceSummary getPerformanceSummary() {
        long worldThreadExec = worldThreadExecutions.sum();
        long worldThreadErr = worldThreadErrors.sum();
        long chunkLoadSuccess = chunkLoadSuccesses.sum();
        long chunkLoadErr = chunkLoadErrors.sum();
        
        double worldThreadAvgTime = worldThreadExec > 0 ? 
            (double) totalWorldThreadExecutionTime.get() / worldThreadExec : 0.0;
        double chunkLoadAvgTime = chunkLoadSuccess > 0 ? 
            (double) totalChunkLoadTime.get() / chunkLoadSuccess : 0.0;
        
        return new PerformanceSummary(
            worldThreadExec, worldThreadErr, worldThreadAvgTime,
            chunkLoadSuccess, chunkLoadErr, chunkLoadAvgTime,
            activeAsyncTasks.sum()
        );
    }
    
    /**
     * Reset all metrics.
     */
    public void reset() {
        worldThreadExecutions.reset();
        worldThreadErrors.reset();
        chunkLoadSuccesses.reset();
        chunkLoadErrors.reset();
        positionUpdateErrors.reset();
        keepaliveErrors.reset();
        activeAsyncTasks.reset();
        
        totalWorldThreadExecutionTime.set(0);
        totalChunkLoadTime.set(0);
        operationErrors.clear();
        
        LOG.info("Async metrics reset");
    }
    
    /**
     * Performance summary data class.
     */
    public static class PerformanceSummary {
        public final long worldThreadExecutions;
        public final long worldThreadErrors;
        public final double worldThreadAverageTime;
        public final long chunkLoadSuccesses;
        public final long chunkLoadErrors;
        public final double chunkLoadAverageTime;
        public final long activeAsyncTasks;
        
        public PerformanceSummary(long worldThreadExecutions, long worldThreadErrors, 
                                double worldThreadAverageTime, long chunkLoadSuccesses, 
                                long chunkLoadErrors, double chunkLoadAverageTime,
                                long activeAsyncTasks) {
            this.worldThreadExecutions = worldThreadExecutions;
            this.worldThreadErrors = worldThreadErrors;
            this.worldThreadAverageTime = worldThreadAverageTime;
            this.chunkLoadSuccesses = chunkLoadSuccesses;
            this.chunkLoadErrors = chunkLoadErrors;
            this.chunkLoadAverageTime = chunkLoadAverageTime;
            this.activeAsyncTasks = activeAsyncTasks;
        }
        
        @Override
        public String toString() {
            return String.format(
                "PerformanceSummary{worldThread=%d/%d (%.1fms avg), chunkLoad=%d/%d (%.1fms avg), active=%d}",
                worldThreadExecutions, worldThreadErrors, worldThreadAverageTime,
                chunkLoadSuccesses, chunkLoadErrors, chunkLoadAverageTime,
                activeAsyncTasks
            );
        }
    }
    
    /**
     * Async task priority enumeration.
     */
    public enum AsyncTaskPriority {
        CRITICAL(1),    // Player teleportation, immediate needs
        HIGH(2),        // Chunk preloading for active players
        NORMAL(3),      // Keepalive operations
        LOW(4),         // Background maintenance
        BACKGROUND(5);  // Analytics, metrics
        
        public final int value;
        
        AsyncTaskPriority(int value) {
            this.value = value;
        }
    }
    
}