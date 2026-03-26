package com.optiportal.async;

import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.optiportal.preload.WorldRegistry;

/**
 * Load balancer for async operations to prevent world thread overload.
 *
 * This component manages the scheduling and execution of async operations,
 * implementing load balancing, adaptive batch sizing, and priority-based
 * scheduling to optimize world thread usage.
 */
public class AsyncLoadBalancer {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    // Load balancing configuration
    private static final int MAX_CONCURRENT_OPERATIONS = 10;
    private static final int DEFAULT_BATCH_SIZE = 4;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 16;
    private static final long LOAD_ADJUSTMENT_INTERVAL_MS = 5000;
    
    // State tracking
    private final AtomicInteger activeOperations = new AtomicInteger(0);
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong totalExecutionTime = new AtomicLong(0);
    private final AtomicInteger currentBatchSize = new AtomicInteger(DEFAULT_BATCH_SIZE);
    private final AtomicInteger totalQueuedCount = new AtomicInteger(0);
    
    // A2: Lock-free EMA stored as raw bits in an AtomicLong.
    // Avoids DoubleAdder+AtomicInteger pair whose integer counter would overflow
    // after ~2 billion operations on a long-running server.
    // CAS loop converges quickly under contention; α=0.1 weights recent samples.
    private final AtomicLong emaBits = new AtomicLong(Double.doubleToLongBits(0.0));
    private static final double EMA_ALPHA = 0.1;
    
    // Priority queues
    private final ConcurrentHashMap<AsyncMetrics.AsyncTaskPriority, ConcurrentLinkedDeque<Supplier<CompletableFuture<Void>>>> pendingTasks =
            new ConcurrentHashMap<>();
    
    private final ScheduledExecutorService executor;
    private final AsyncMetrics metrics;
    private final AsyncErrorHandler errorHandler;
    
    /** Optional world registry for chunk pressure sensing. Null = disabled. */
    private final WorldRegistry worldRegistry;
    
    /** Optional TPS monitor for server-load-aware scheduling. Null = disabled. */
    private final WorldTpsMonitor tpsMonitor;
    
    public AsyncLoadBalancer(ScheduledExecutorService executor,
                            AsyncMetrics metrics,
                            AsyncErrorHandler errorHandler) {
        this(executor, metrics, errorHandler, null);
    }
    
    public AsyncLoadBalancer(ScheduledExecutorService executor,
                            AsyncMetrics metrics,
                            AsyncErrorHandler errorHandler,
                            WorldTpsMonitor tpsMonitor) {
        this(executor, metrics, errorHandler, tpsMonitor, null);
    }
    
    /**
     * Constructor with WorldRegistry for chunk pressure sensing.
     * Use this in OptiPortal.start0() to enable server-wide chunk pressure awareness.
     */
    public AsyncLoadBalancer(ScheduledExecutorService executor,
                            AsyncMetrics metrics,
                            AsyncErrorHandler errorHandler,
                            WorldTpsMonitor tpsMonitor,
                            WorldRegistry worldRegistry) {
        this.executor = executor;
        this.metrics = metrics;
        this.errorHandler = errorHandler;
        this.tpsMonitor = tpsMonitor;
        this.worldRegistry = worldRegistry;
        
        // Initialize priority queues
        for (AsyncMetrics.AsyncTaskPriority priority : AsyncMetrics.AsyncTaskPriority.values()) {
            pendingTasks.put(priority, new ConcurrentLinkedDeque<>());
        }
        
        // Start load adjustment scheduler
        startLoadAdjustmentScheduler();
        
        // Start task processor
        startTaskProcessor();
    }
    
    /**
     * Schedule a load operation with priority.
     *
     * @param operation The operation to schedule
     * @param priority Operation priority
     * @return CompletableFuture that completes when operation is done
     */
    public CompletableFuture<Void> scheduleLoad(Supplier<CompletableFuture<Void>> operation,
                                               AsyncMetrics.AsyncTaskPriority priority) {
        // Under critical TPS: queue ALL new operations regardless of active count.
        // This prevents adding new load to a server already failing to keep up.
        // Exception: CRITICAL priority tasks (player teleports) always execute immediately.
        if (tpsMonitor != null
                && tpsMonitor.isCriticallyLoaded()
                && priority != AsyncMetrics.AsyncTaskPriority.CRITICAL) {
            LOG.fine("[OptiPortal] AsyncLoadBalancer: TPS critical — queuing " + priority + " operation");
            return queueOperation(operation, priority);
        }
        
        if (activeOperations.get() >= MAX_CONCURRENT_OPERATIONS) {
            // Queue the operation if we're at capacity
            return queueOperation(operation, priority);
        }
        
        // Execute immediately if we have capacity
        return executeOperation(operation, priority);
    }
    
    /**
     * Calculate optimal batch size based on current load and TPS.
     *
     * @param totalChunks Total number of chunks to process
     * @return Optimal batch size
     */
    public int calculateOptimalBatchSize(int totalChunks) {
        int currentLoad = activeOperations.get();
        double avgExecutionTime = getAverageExecutionTime();
        
        // Start with the current adaptive batch size
        int adjustedSize = currentBatchSize.get();
        
        // Existing load-based adjustment
        if (currentLoad > MAX_CONCURRENT_OPERATIONS * 0.8) {
            adjustedSize = Math.max(MIN_BATCH_SIZE, adjustedSize / 2);
        } else if (currentLoad < MAX_CONCURRENT_OPERATIONS * 0.3 && avgExecutionTime < 100) {
            adjustedSize = Math.min(MAX_BATCH_SIZE, adjustedSize + 1);
        }
        
        // TPS-based adjustment (applied on top of load-based)
        if (tpsMonitor != null) {
            double tps = tpsMonitor.getCurrentTps();
        
            if (tps < WorldTpsMonitor.TPS_CRITICAL_THRESHOLD) {
                // Server critically lagged — force minimum batch size
                adjustedSize = MIN_BATCH_SIZE;
                LOG.fine("[OptiPortal] AsyncLoadBalancer: TPS=" + String.format("%.1f", tps)
                        + " (critical) → batch capped at " + MIN_BATCH_SIZE);
            } else if (tps < 15.0) {
                // Severely lagged — cap at 2
                adjustedSize = Math.min(adjustedSize, 2);
            } else if (tps < WorldTpsMonitor.TPS_LOW_THRESHOLD) {
                // Moderately lagged — halve the calculated size
                adjustedSize = Math.max(MIN_BATCH_SIZE, adjustedSize / 2);
            } else if (tps < WorldTpsMonitor.NOMINAL_TPS) {
                // Slightly under nominal (18–20 TPS) — reduce by 25%
                adjustedSize = Math.max(MIN_BATCH_SIZE, (int)(adjustedSize * 0.75));
            }
        }
        
        // Chunk pressure adjustment: if the server has >4000 loaded chunks, reduce batch
        // size to avoid adding GC and memory pressure. Thresholds are approximate.
        int serverChunks = getTotalServerChunkCount();
        if (serverChunks > 8000) {
            // Very high chunk pressure — cap at 1
            adjustedSize = MIN_BATCH_SIZE;
            LOG.fine("[OptiPortal] AsyncLoadBalancer: server chunks=" + serverChunks
                    + " (very high) → batch capped at " + MIN_BATCH_SIZE);
        } else if (serverChunks > 4000) {
            // High chunk pressure — halve the batch size
            adjustedSize = Math.max(MIN_BATCH_SIZE, adjustedSize / 2);
        }
        
        // Never exceed the total chunk count
        return Math.min(adjustedSize, totalChunks);
    }
    
    /**
     * Sum of loaded chunks across all worlds. Returns 0 if worldRegistry is null
     * or no worlds are loaded. Used for chunk pressure-aware batch sizing.
     */
    private int getTotalServerChunkCount() {
        if (worldRegistry == null) return 0;
        try {
            return worldRegistry.getTotalLoadedChunkCount();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Get current load statistics.
     * 
     * @return Load statistics
     */
    public LoadStats getLoadStats() {
        return new LoadStats(
            activeOperations.get(),
            totalOperations.get(),
            getAverageExecutionTime(),
            currentBatchSize.get(),
            getQueuedTaskCount()
        );
    }
    
    /**
     * Queue an operation for later execution.
     * 
     * @param operation The operation to queue
     * @param priority Operation priority
     * @return CompletableFuture that completes when operation is done
     */
    private CompletableFuture<Void> queueOperation(Supplier<CompletableFuture<Void>> operation,
                                                   AsyncMetrics.AsyncTaskPriority priority) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Deque<Supplier<CompletableFuture<Void>>> queue = pendingTasks.get(priority);
        queue.addLast(() -> {
            return operation.get().whenComplete((result, ex) -> {
                if (ex == null) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(ex);
                }
            });
        });
        totalQueuedCount.incrementAndGet();
        LOG.fine("Queued operation with priority: " + priority);
        return future;
    }
    
    /**
     * Execute an operation immediately.
     * 
     * @param operation The operation to execute
     * @param priority Operation priority
     * @return CompletableFuture that completes when operation is done
     */
    private CompletableFuture<Void> executeOperation(Supplier<CompletableFuture<Void>> operation,
                                                    AsyncMetrics.AsyncTaskPriority priority) {
        activeOperations.incrementAndGet();
        totalOperations.incrementAndGet();
        
        long startTime = System.currentTimeMillis();
        
        if (metrics != null) {
            metrics.recordAsyncTaskStart(priority);
        }
        
        return operation.get().whenComplete((result, ex) -> {
            long duration = System.currentTimeMillis() - startTime;
            activeOperations.decrementAndGet();
            totalExecutionTime.addAndGet(duration);  // keep for LoadStats.totalOperations context

            // A2: Update EMA using lock-free CAS loop on emaBits.
            // α=0.1: new = 0.1*sample + 0.9*previous. Seed from 0 on first sample.
            long prevBits, nextBits;
            do {
                prevBits = emaBits.get();
                double prev = Double.longBitsToDouble(prevBits);
                double next = (prev == 0.0) ? duration : EMA_ALPHA * duration + (1.0 - EMA_ALPHA) * prev;
                nextBits = Double.doubleToLongBits(next);
            } while (!emaBits.compareAndSet(prevBits, nextBits));

            if (metrics != null) {
                metrics.recordAsyncTaskComplete(priority, duration);
            }
            if (ex != null) {
                errorHandler.handleWorldThreadError(
                        ex instanceof Exception ? (Exception) ex : new Exception(ex), "loadBalancer");
            }
        });
    }
    
    /**
     * Start the load adjustment scheduler.
     */
    private void startLoadAdjustmentScheduler() {
        executor.scheduleAtFixedRate(() -> {
            try {
                adjustBatchSize();
            } catch (Exception e) {
                LOG.warning("Error in load adjustment: " + e.getMessage());
            }
        }, LOAD_ADJUSTMENT_INTERVAL_MS, LOAD_ADJUSTMENT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Start the task processor to handle queued tasks.
     */
    private void startTaskProcessor() {
        executor.scheduleAtFixedRate(() -> {
            try {
                processQueuedTasks();
            } catch (Exception e) {
                LOG.warning("Error processing queued tasks: " + e.getMessage());
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Adjust batch size based on current performance.
     */
    private void adjustBatchSize() {
        double avgExecutionTime = getAverageExecutionTime();
        int currentLoad = activeOperations.get();
        
        int newSize = currentBatchSize.get();
        
        if (avgExecutionTime > 200 || currentLoad > MAX_CONCURRENT_OPERATIONS * 0.7) {
            // Performance is poor - reduce batch size
            newSize = Math.max(MIN_BATCH_SIZE, newSize - 1);
        } else if (avgExecutionTime < 50 && currentLoad < MAX_CONCURRENT_OPERATIONS * 0.4) {
            // Performance is good - increase batch size
            newSize = Math.min(MAX_BATCH_SIZE, newSize + 1);
        }
        
        if (newSize != currentBatchSize.get()) {
            currentBatchSize.set(newSize);
            LOG.fine("Adjusted batch size to " + newSize + " (avg time: " + avgExecutionTime + "ms, load: " + currentLoad + ")");
        }
    }
    
    /**
     * Process queued tasks by priority.
     */
    private void processQueuedTasks() {
        if (totalQueuedCount.get() == 0) {
            return; // Nothing queued — skip all monitor acquisitions
        }
        int available = MAX_CONCURRENT_OPERATIONS - activeOperations.get();
        if (available <= 0) {
            return; // At capacity
        }

        int dispatched = 0;

        // Process tasks by priority (CRITICAL first, then HIGH, NORMAL, LOW, BACKGROUND)
        for (AsyncMetrics.AsyncTaskPriority priority : AsyncMetrics.AsyncTaskPriority.values()) {
            if (dispatched >= available) break;

            Deque<Supplier<CompletableFuture<Void>>> queue = pendingTasks.get(priority);

            while (dispatched < available) {
                Supplier<CompletableFuture<Void>> operation = queue.pollFirst(); // lock-free
                if (operation == null) break; // This priority queue is empty

                totalQueuedCount.decrementAndGet();
                executeOperation(operation, priority);
                dispatched++;
            }
        }
    }
    
    /**
     * Get average execution time as a lock-free EMA (α=0.1).
     * Returns 0.0 until the first sample is recorded.
     */
    private double getAverageExecutionTime() {
        return Double.longBitsToDouble(emaBits.get());
    }
    
    /**
     * Get total queued task count.
     * A1: Now O(1) using counter instead of O(n) iteration
     *
     * @return Total number of queued tasks
     */
    private int getQueuedTaskCount() {
        return totalQueuedCount.get();
    }
    
    /**
     * Load statistics data class.
     */
    public static class LoadStats {
        public final int activeOperations;
        public final long totalOperations;
        public final double averageExecutionTime;
        public final int currentBatchSize;
        public final int queuedTasks;
        
        public LoadStats(int activeOperations, long totalOperations, double averageExecutionTime,
                        int currentBatchSize, int queuedTasks) {
            this.activeOperations = activeOperations;
            this.totalOperations = totalOperations;
            this.averageExecutionTime = averageExecutionTime;
            this.currentBatchSize = currentBatchSize;
            this.queuedTasks = queuedTasks;
        }
        
        @Override
        public String toString() {
            return String.format(
                "LoadStats{active=%d, total=%d, avgTime=%.1fms, batchSize=%d, queued=%d}",
                activeOperations, totalOperations, averageExecutionTime, currentBatchSize, queuedTasks
            );
        }
    }
}
